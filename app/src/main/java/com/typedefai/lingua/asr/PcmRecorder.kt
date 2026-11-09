package com.typedefai.lingua.asr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Records 16 kHz mono PCM16 from the microphone and returns normalized float samples in [-1, 1].
 *
 * Recording runs on a background coroutine; [stop] must be called from a coroutine. The recorder
 * holds one utterance at a time; call [start] again after [stop] for the next turn.
 */
class PcmRecorder(
  private val sampleRateHz: Int = 16_000,
  private val maxSeconds: Int = 30,
) : AutoCloseable {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val isRecording = AtomicBoolean(false)
  private val buffer = ByteArrayOutputStream()
  private var audioRecord: AudioRecord? = null
  private var readJob: Job? = null
  private var closed = false

  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (closed || !isRecording.compareAndSet(false, true)) return false

    val minBufferSize = AudioRecord.getMinBufferSize(
      sampleRateHz,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBufferSize <= 0) {
      isRecording.set(false)
      return false
    }

    val record = runCatching {
      AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        sampleRateHz,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minBufferSize, sampleRateHz),
      )
    }.getOrNull()

    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
      record?.release()
      isRecording.set(false)
      return false
    }

    buffer.reset()
    audioRecord = record
    val started = runCatching { record.startRecording() }.isSuccess
    if (!started) {
      record.release()
      audioRecord = null
      isRecording.set(false)
      return false
    }

    readJob = scope.launch {
      val chunk = ShortArray(1_600) // 100 ms at 16 kHz.
      val maxBytes = maxSeconds * sampleRateHz * 2
      while (isActive && isRecording.get()) {
        val read = record.read(chunk, 0, chunk.size)
        if (read > 0) {
          for (i in 0 until read) {
            val value = chunk[i].toInt()
            buffer.write(value and 0xFF)
            buffer.write((value shr 8) and 0xFF)
          }
          if (buffer.size() >= maxBytes) {
            isRecording.set(false)
            break
          }
        } else if (read < 0) {
          break
        }
      }
    }
    return true
  }

  /** Stops recording and returns the captured audio as 16 kHz mono float samples. */
  suspend fun stop(): FloatArray {
    isRecording.set(false)
    runCatching { audioRecord?.stop() }
    readJob?.join()
    readJob = null
    audioRecord?.release()
    audioRecord = null

    val pcm = buffer.toByteArray()
    buffer.reset()
    val shortCount = pcm.size / 2
    val samples = FloatArray(shortCount)
    for (i in 0 until shortCount) {
      val lo = pcm[i * 2].toInt() and 0xFF
      val hi = pcm[i * 2 + 1].toInt() shl 8
      samples[i] = (lo or hi).toShort().toFloat() / 32768f
    }
    return samples
  }

  override fun close() {
    closed = true
    isRecording.set(false)
    runCatching { audioRecord?.stop() }
    runCatching { audioRecord?.release() }
    audioRecord = null
    scope.cancel()
  }
}
