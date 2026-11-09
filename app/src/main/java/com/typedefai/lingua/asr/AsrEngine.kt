package com.typedefai.lingua.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.audiotranscriber.AudioTranscriber
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * MediaPipe AudioTranscriber wrapper for the bundle chosen in settings ([AsrModel.DEFAULT] otherwise).
 *
 * The task wants 16 kHz mono float PCM. All native calls run on one dedicated thread because the
 * transcriber is stateful and not thread-safe.
 */
class AsrEngine(
  private val context: Context,
  private val model: AsrModel = AsrModel.DEFAULT,
) : AutoCloseable {

  private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "litert-asr").apply { isDaemon = true }
  }.asCoroutineDispatcher()

  private var transcriber: AudioTranscriber? = null

  /** Loads the model and warms up the native task. Safe to call more than once. */
  suspend fun warmUp() {
    withContext(dispatcher) {
      ensureTranscriber()
    }
  }

  /** Transcribes one complete utterance, returning the final text (may be blank). */
  suspend fun transcribe(samples: FloatArray): String = withContext(dispatcher) {
    if (samples.isEmpty()) return@withContext ""
    val engine = ensureTranscriber()
    val start = SystemClock.elapsedRealtime()

    val finalParts = mutableListOf<String>()
    val chunkSize = 1_600 // 100 ms at 16 kHz, same cadence as Gallery's ASR service.
    var offset = 0
    while (offset < samples.size) {
      val end = minOf(offset + chunkSize, samples.size)
      val result = engine.transcribe(samples.copyOfRange(offset, end), false)
      result.segments()
        .filter { it.isFinal }
        .map { it.transcript().trim() }
        .filter { it.isNotEmpty() }
        .forEach { finalParts.add(it) }
      offset = end
    }

    val finalResult = engine.finishStream()
    finalResult.segments()
      .filter { it.isFinal }
      .map { it.transcript().trim() }
      .filter { it.isNotEmpty() }
      .forEach { finalParts.add(it) }

    // Some bundles only populate result.text; prefer incremental finals when available.
    val text = finalParts.joinToString(" ").ifBlank { finalResult.text.trim() }
    Log.d(
      TRACE,
      "ASR ${model.fileName} transcribed ${samples.size} samples in " +
        "${SystemClock.elapsedRealtime() - start} ms (${text.length} chars)",
    )
    runCatching { engine.prewarm() }
    text
  }

  private fun ensureTranscriber(): AudioTranscriber {
    transcriber?.let { return it }
    val spec = model
    val modelFile = findModelFile(spec.fileName)
      ?: error(
        "ASR model ${spec.fileName} not found. Push it to " +
          "${context.getExternalFilesDir(null)?.resolve("models")?.absolutePath}.",
      )

    val cacheDir = File(context.cacheDir, "asr").apply { mkdirs() }
    val baseOptions = BaseOptions.builder()
      .setModelAssetPath(modelFile.absolutePath)
      .setDelegate(spec.delegate)
      .build()
    val options = AudioTranscriber.AudioTranscriberOptions.builder()
      .setBaseOptions(baseOptions)
      .setSampleRateHz(SAMPLE_RATE_HZ)
      .setEnableInterimResults(false)
      .setModelArchitecture(AudioTranscriber.ModelArchitecture.AUTO)
      .setCacheDir(cacheDir.absolutePath)
      .build()

    return AudioTranscriber.createFromOptions(context, options).also { created ->
      transcriber = created
      Log.d(TRACE, "ASR loaded ${spec.fileName} with ${spec.delegate}")
      runCatching { created.prewarm() }
    }
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

  override fun close() {
    runCatching {
      runBlocking(dispatcher) {
        transcriber?.close()
        transcriber = null
      }
    }
    dispatcher.close()
  }

  companion object {
    const val SAMPLE_RATE_HZ = 16_000

    private const val TRACE = "VoiceTrace"
  }
}
