package com.wireless.audiostreamer.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.wireless.audiostreamer.network.Protocol
import kotlin.concurrent.thread

/** Captures this phone's microphone (mono) for remote mic sharing — separate from the main
 *  internal-audio mirroring feature, which uses AudioPlaybackCapture instead. */
class MicCapturer(private val onFrame: (ShortArray) -> Unit) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val min = AudioRecord.getMinBufferSize(
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val size = maxOf(if (min > 0) min else 0, Protocol.SAMPLES * 2 * 4)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            rec.startRecording()
            worker = thread(isDaemon = true, name = "was-mic") { loop(rec) }
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = ShortArray(Protocol.SAMPLES)
        while (running) {
            var got = 0
            while (running && got < frame.size) {
                val n = rec.read(frame, got, frame.size - got, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    running = false
                    break
                }
                got += n
            }
            if (running) onFrame(frame.copyOf())
        }
    }

    fun stop() {
        running = false
        try { worker?.join(400) } catch (e: InterruptedException) { }
        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
    }
}
