package com.typedefai.lingua.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.EmptyCoroutineContext

/**
 * MediaPipe TextToSpeech wrapper, modelled on the official Android sample
 * (`examples/text_to_speech/android`): headless [TextToSpeech] results are written into a
 * streaming [AudioTrack] of the rate reported by the engine.
 *
 * Synthesis and playback run on separate threads and are connected by a small queue, so the next
 * clause is already being synthesized and vocoded while the current one is still playing. That
 * overlap is what keeps speech continuous instead of pausing between sentences.
 */
class TtsEngine(
  private val context: Context,
  private val model: TtsModel = TtsModel.DEFAULT,
  voiceIdOverride: String = "",
) : SpeechEngine {

  /** Voice passed to every synthesis call; empty means the bundle default. */
  @Volatile
  var voiceId: String = voiceIdOverride.ifBlank { model.voiceId }
    private set

  /** MediaPipe task calls: engine create/synthesize/cancel/close. */
  private val synthesisDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "litert-tts").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  /** AudioTrack writes and drain; blocking writes must not stall synthesis. */
  private val playbackDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "litert-tts-audio").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private var tts: TextToSpeech? = null

  /** Track currently owned by the playback job; used by [stop] for barge-in. */
  @Volatile private var activeTrack: AudioTrack? = null

  /** Queue currently feeding playback; cancelled on stop/close so a stuck write cannot wedge. */
  @Volatile private var activeQueue: Channel<AudioSegment>? = null

  @Volatile private var closed = false

  /** Loads Kokoro. A synthesis warmup blocked startup and did not reduce first-audio latency. */
  override suspend fun warmUp() {
    withContext(synthesisDispatcher) {
      val start = SystemClock.elapsedRealtime()
      ensureTts()
      Log.d(TRACE, "TTS model loaded after ${SystemClock.elapsedRealtime() - start} ms")
    }
  }

  /**
   * Speaks a stream of short clause chunks through one continuous [AudioTrack].
   *
   * Each clause is synthesized on the TTS thread and handed to the playback thread through a
   * queue, so playback and synthesis overlap. Logs cover per-clause synthesis, write, and drain.
   */
  override suspend fun speakClauses(
    textChunks: Flow<String>,
    onStarted: () -> Unit,
  ) {
    withContext(synthesisDispatcher) {
      if (closed) return@withContext
      val start = SystemClock.elapsedRealtime()
      Log.d(TRACE, "speakClauses start")
      val engine = ensureTts()
      val queue = Channel<AudioSegment>(capacity = QUEUE_CAPACITY)
      activeQueue = queue
      try {
        coroutineScope {
          val player = launch(playbackDispatcher) { playQueue(queue) }
          var started = false
          var chunkIndex = 0
          try {
            textChunks.collect { text ->
              if (text.isBlank()) return@collect
              chunkIndex++
              val clauseStart = SystemClock.elapsedRealtime()
              val result = engine.synthesize(
                text,
                SynthesisOptions(voiceId = voiceId),
              )
              val samples = SilenceTrimmer.trim(
                result.samples,
                trimHead = chunkIndex > 1,
                trimTail = true,
              )
              Log.d(
                TRACE,
                "TTS clause #$chunkIndex (${text.length} chars, \"${text.take(48)}\") -> " +
                  "${result.samples.size} samples, ${samples.size} after trim, in " +
                  "${SystemClock.elapsedRealtime() - clauseStart} ms",
              )
              if (samples.isEmpty()) return@collect
              if (!started) {
                started = true
                Log.d(
                  TRACE,
                  "first TTS audio after ${SystemClock.elapsedRealtime() - start} ms " +
                    "(${samples.size} samples)",
                )
                runCatching { onStarted() }
              }
              queue.send(AudioSegment(samples, result.sampleRateHz))
            }
            Log.d(
              TRACE,
              "TTS synthesis complete after ${SystemClock.elapsedRealtime() - start} ms " +
                "($chunkIndex clauses)",
            )
          } finally {
            queue.close()
          }
          player.join()
        }
      } catch (e: CancellationException) {
        Log.d(TRACE, "speakClauses cancelled")
        queue.cancel()
        runCatching { engine.cancel() }
        pauseAndFlush()
        throw e
      } finally {
        if (activeQueue === queue) {
          activeQueue = null
        }
      }
      Log.d(TRACE, "TTS playback drain done after ${SystemClock.elapsedRealtime() - start} ms")
    }
  }

  /** Switches the voice for the next utterances without reloading the bundle. */
  override fun updateVoice(newVoiceId: String) {
    voiceId = newVoiceId.ifBlank { model.voiceId }
    Log.d(TRACE, "TTS voice -> $voiceId (no reload)")
  }

  /** Aborts in-flight synthesis and playback. Safe to call from any thread. */
  override fun stop() {
    Log.d(TRACE, "TTS stop requested")
    runCatching { tts?.cancel() }
    activeQueue?.cancel()
    // Mirror the official sample's barge-in: pause and drop anything still buffered.
    pauseAndFlush()
  }

  private fun pauseAndFlush() {
    runCatching { activeTrack?.pause() }
    runCatching { activeTrack?.flush() }
  }

  /** Playback side: writes queued clauses into one continuous streaming track. */
  private suspend fun playQueue(queue: ReceiveChannel<AudioSegment>) {
    var track: AudioTrack? = null
    var sampleRateHz = SAMPLE_RATE_HZ
    var writtenSamples = 0L
    try {
      for (segment in queue) {
        if (segment.sampleRateHz > 0) {
          sampleRateHz = segment.sampleRateHz
        }
        val sink = track ?: createAudioTrack(sampleRateHz).also {
          it.play()
          track = it
          activeTrack = it
        }
        val writeStart = SystemClock.elapsedRealtime()
        val written = writeAll(sink, segment.samples)
        if (written > 0) {
          writtenSamples += written
        }
        Log.d(
          TRACE,
          "TTS wrote ${segment.samples.size} samples in " +
            "${SystemClock.elapsedRealtime() - writeStart} ms (total=$writtenSamples)",
        )
      }
      track?.let { waitForPlayback(it, writtenSamples, sampleRateHz) }
    } finally {
      activeTrack = null
      track?.let {
        runCatching { it.stop() }
        it.release()
      }
    }
  }

  /**
   * Writes with [AudioTrack.WRITE_NON_BLOCKING] so cancellation and [close] can never get stuck
   * inside a blocking native write while the track is paused or released.
   */
  private suspend fun writeAll(track: AudioTrack, samples: FloatArray): Int {
    var offset = 0
    while (offset < samples.size) {
      currentCoroutineContext().ensureActive()
      val written = track.write(
        samples,
        offset,
        samples.size - offset,
        AudioTrack.WRITE_NON_BLOCKING,
      )
      when {
        written > 0 -> offset += written
        written == 0 -> delay(5)
        else -> error("AudioTrack write failed: $written")
      }
    }
    return offset
  }

  private fun ensureTts(): TextToSpeech {
    tts?.let { return it }
    val spec = model
    val modelFile = findModelFile(spec.fileName)
      ?: error(
        "TTS model ${spec.fileName} not found. Push it to " +
          (context.getExternalFilesDir(null)?.resolve("models")?.absolutePath
            ?: File(context.filesDir, "models").absolutePath) + ".",
      )
    val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    val created = try {
      TextToSpeech.create(
        TextToSpeechOptions(
          baseOptions = BaseOptions.builder()
            .setModelAssetPath(modelFile.absolutePath)
            .setDelegate(spec.delegate)
            .build(),
          defaultVoiceId = voiceId,
          defaultLocale = spec.locale,
          speakingRate = spec.speakingRate,
          targetBucket = spec.targetBucket,
          cacheDirectory = cacheDir.absolutePath,
        ),
      )
    } catch (t: Throwable) {
      error("TTS ${spec.name} initialization failed: ${t.message ?: t::class.java.simpleName}")
    }

    tts = created
    Log.d(
      TRACE,
      "TTS loaded ${spec.fileName} with ${spec.delegate} " +
        "(voice=$voiceId, rate=${spec.speakingRate}, bucket=${spec.targetBucket})",
    )
    runCatching { created.listVoices() }
    return created
  }

  private fun findModelFile(fileName: String): File? {
    val dirs = listOfNotNull(
      context.getExternalFilesDir(null)?.resolve("models"),
      File(context.filesDir, "models"),
    )
    return dirs.asSequence()
      .map { File(it, fileName) }
      .firstOrNull { it.isFile }
  }

  /** Streaming track sized like the official sample: half a second of float mono. */
  private fun createAudioTrack(sampleRateHz: Int): AudioTrack {
    val minBufferBytes = AudioTrack.getMinBufferSize(
      sampleRateHz,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    )
    val bufferBytes = maxOf(minBufferBytes, sampleRateHz * Float.SIZE_BYTES / 2)
    return AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build(),
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setSampleRate(sampleRateHz)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setBufferSizeInBytes(bufferBytes)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
  }

  /** Waits for the buffered audio to drain, bounded by its own duration plus 1 s of slack. */
  private suspend fun waitForPlayback(track: AudioTrack, writtenSamples: Long, sampleRateHz: Int) {
    if (writtenSamples <= 0L) return
    val playbackMs = writtenSamples * 1000L / sampleRateHz
    val deadline = SystemClock.elapsedRealtime() + playbackMs + 1_000L
    while (
      track.playbackHeadPosition.toLong() < writtenSamples &&
      track.playState == AudioTrack.PLAYSTATE_PLAYING &&
      SystemClock.elapsedRealtime() < deadline
    ) {
      delay(20)
    }
  }

  override fun close() {
    closed = true
    Log.d(TRACE, "TTS close: cancelling")
    runCatching { tts?.cancel() }
    activeQueue?.cancel()
    pauseAndFlush()
    // Release the sink too: a playback coroutine still parked in write() must not keep the
    // synthesis thread busy, or TextToSpeech.close() below would never run.
    runCatching { activeTrack?.release() }
    activeTrack = null

    val start = SystemClock.elapsedRealtime()
    // Run the native close on the thread that created the task, but never wait forever: a wedged
    // MediaPipe close must not keep the settings reload on the loading screen.
    val done = CompletableDeferred<Unit>()
    synthesisDispatcher.dispatch(EmptyCoroutineContext) {
      runCatching { tts?.close() }
      tts = null
      done.complete(Unit)
    }
    val finished = runBlocking {
      withTimeoutOrNull(CLOSE_TIMEOUT_MS) { done.await() } != null
    }
    if (finished) {
      Log.d(TRACE, "TTS engine closed in ${SystemClock.elapsedRealtime() - start} ms")
      synthesisDispatcher.close()
      playbackDispatcher.close()
    } else {
      Log.w(
        TRACE,
        "TextToSpeech.close did not finish within ${CLOSE_TIMEOUT_MS} ms; abandoning the engine",
      )
    }
  }

  private class AudioSegment(val samples: FloatArray, val sampleRateHz: Int)

  companion object {
    private const val SAMPLE_RATE_HZ = 24_000

    /** Clauses buffered ahead of playback; each is at most a few hundred KB of float PCM. */
    private const val QUEUE_CAPACITY = 8

    /** Upper bound for MediaPipe TextToSpeech.close(); a wedged close is abandoned. */
    private const val CLOSE_TIMEOUT_MS = 20_000L

    private const val TRACE = "VoiceTrace"
  }
}
