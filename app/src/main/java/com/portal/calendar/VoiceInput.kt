package com.portal.calendar

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Tap-to-talk capture: records 16 kHz mono PCM from the Portal's handset mic,
 * auto-stops after a beat of trailing silence (or a hard cap), and hands back
 * a WAV the AI can transcribe. NOT always-listening — the Portal has no
 * wake-word hook for sideloaded apps, and the single mic is poor for room
 * pickup; tap-to-talk is the honest, working shape.
 *
 * Runs entirely off the main thread; callbacks are invoked on that worker
 * thread, so the UI layer must re-post to the main looper.
 */
class VoiceInput {
    @Volatile private var stopRequested = false
    @Volatile var running = false; private set

    companion object {
        private const val RATE = 16000
        private const val MAX_MS = 12000          // hard ceiling
        private const val TRAIL_SILENCE_MS = 1600 // stop this long after speech ends
        private const val MIN_SPEECH_MS = 400     // ignore a stray blip
        private const val SPEECH_AMP = 1600        // 16-bit abs amplitude = "speech"
    }

    fun stop() { stopRequested = true }

    /** Records and returns WAV bytes, or null if nothing usable was captured. */
    fun record(onLevel: (Float) -> Unit = {}): ByteArray? {
        stopRequested = false
        running = true
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return null
            val source = MediaRecorder.AudioSource.VOICE_RECOGNITION
            val rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE)) // ~1s buffer
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                // Fall back to the plain mic source if VOICE_RECOGNITION isn't routed.
                rec.release()
                val mic = AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
                if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); return null }
                return runLoop(mic, onLevel)
            }
            return runLoop(rec, onLevel)
        } catch (e: SecurityException) {
            return null // RECORD_AUDIO not granted
        } catch (e: Exception) {
            return null
        } finally {
            running = false
        }
    }

    private fun runLoop(rec: AudioRecord, onLevel: (Float) -> Unit): ByteArray? {
        val pcm = ByteArrayOutputStream()
        val chunk = ShortArray(RATE / 10) // 100 ms
        var elapsedMs = 0
        var speechMs = 0
        var trailingSilenceMs = 0
        var sawSpeech = false
        // startRecording() can throw if the single mic is held by another app —
        // keep it INSIDE the try so the finally always releases the AudioRecord.
        try {
            rec.startRecording()
            while (!stopRequested && elapsedMs < MAX_MS) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                var peak = 0
                for (i in 0 until n) { val a = kotlin.math.abs(chunk[i].toInt()); if (a > peak) peak = a }
                onLevel((peak / 8000f).coerceIn(0f, 1f))
                // Append little-endian PCM16.
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    bytes[i * 2] = (chunk[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((chunk[i].toInt() shr 8) and 0xFF).toByte()
                }
                pcm.write(bytes)
                val chunkMs = n * 1000 / RATE
                elapsedMs += chunkMs
                if (peak >= SPEECH_AMP) {
                    sawSpeech = true; speechMs += chunkMs; trailingSilenceMs = 0
                } else if (sawSpeech) {
                    trailingSilenceMs += chunkMs
                    if (trailingSilenceMs >= TRAIL_SILENCE_MS) break
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (speechMs < MIN_SPEECH_MS) return null
        return wrapWav(pcm.toByteArray())
    }

    /** Minimal 44-byte WAV header for PCM16 mono. */
    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val totalDataLen = pcm.size + 36
        val byteRate = RATE * 2
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) = out.write(byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        str("RIFF"); i32(totalDataLen); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(1); i32(RATE); i32(byteRate); i16(2); i16(16)
        str("data"); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}
