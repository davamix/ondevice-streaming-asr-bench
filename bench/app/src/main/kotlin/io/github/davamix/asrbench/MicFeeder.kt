package io.github.davamix.asrbench

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock

/**
 * Feeds an arm from the live microphone, for the single real-microphone check
 * (PLAN.md §7, §10 step 20). Nothing measured is fed this way (D5).
 *
 * It mirrors [PacedFeeder] so the arm cannot tell the difference: the same
 * 100 ms frames, the same sink, the same timestamps. The microphone is the
 * clock here, since a read blocks until the audio exists. Every frame is also
 * written into [audio], which the caller allocates at the recording's length,
 * so the exact samples the arm heard live can be fed again through a
 * [PacedFeeder] afterwards. That replay is the comparison the check exists
 * for: same audio, live against simulated.
 *
 * Slip means something different here. A file feeder is late when the sink
 * held it past a frame's due time. A microphone does not wait: audio keeps
 * arriving in AudioRecord's buffer while the sink works, so "late" is how far
 * the audio handed over trails what the microphone has already captured. It
 * is taken from [AudioRecord.getTimestamp], and is 0 where the device does not
 * report one. The buffer holds [BUFFER_S] seconds, so a slow sink backs up
 * rather than dropping audio.
 *
 * Requires RECORD_AUDIO, granted by the owner on the phone, and a visible
 * activity: Android silences the microphone for apps in the background.
 */
class MicFeeder(
    private val audio: Wav.Audio,
    private val frameMs: Int = PacedFeeder.DEFAULT_FRAME_MS,
) : AudioFeeder {

    /** Mean RMS level of what was captured, dBFS; set when the feed ends. */
    var rmsDbfs: Double? = null
        private set

    /** Where each recording came from, for the results. */
    val source = "VOICE_RECOGNITION"

    @SuppressLint("MissingPermission") // checked by the caller before recording
    override fun feed(
        sink: PacedFeeder.Sink,
        onStart: (() -> Unit)?,
        shouldStop: (() -> Boolean)?,
        onProgress: PacedFeeder.Progress?,
    ): PacedFeeder.FeedStats {
        val sr = audio.sampleRate
        val frame = sr * frameMs / 1000
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sr,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sr * 2 * BUFFER_S),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise (permission, or the microphone is in use)"
        }

        val total = audio.samples.size
        var offset = 0
        var frames = 0
        var sinkBusy = 0L
        var maxSlip = 0L
        var lastSlip = 0L
        var sumSq = 0.0
        val ts = AudioTimestamp()

        try {
            rec.startRecording()
            val start = SystemClock.elapsedRealtime()
            val startUptime = SystemClock.uptimeMillis()
            onStart?.invoke()

            while (offset < total) {
                if (shouldStop?.invoke() == true) break
                val n = minOf(frame, total - offset)
                var got = 0
                while (got < n) {
                    val r = rec.read(audio.samples, offset + got, n - got,
                        AudioRecord.READ_BLOCKING)
                    check(r >= 0) { "AudioRecord.read failed: $r" }
                    got += r
                }

                // How far behind the live capture this frame is being handed
                // over: samples captured by now, minus samples consumed.
                val slip = if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                    AudioRecord.SUCCESS
                ) {
                    val captured = ts.framePosition +
                        (System.nanoTime() - ts.nanoTime) * sr / 1_000_000_000L
                    maxOf(0L, (captured - (offset + n)) * 1000L / sr)
                } else 0L
                if (slip > maxSlip) maxSlip = slip
                lastSlip = slip

                val pcm = audio.samples.copyOfRange(offset, offset + n)
                for (s in pcm) sumSq += s.toDouble() * s
                val atMs = offset * 1000L / sr

                val t0 = SystemClock.elapsedRealtime()
                sink.onFrame(pcm, atMs)
                sinkBusy += SystemClock.elapsedRealtime() - t0

                offset += n
                frames++
                onProgress?.onFrame(offset * 1000L / sr, sinkBusy, slip)
            }

            val end = SystemClock.elapsedRealtime()
            val awake = SystemClock.uptimeMillis() - startUptime
            if (offset > 0) {
                rmsDbfs = 20 * kotlin.math.log10(
                    maxOf(kotlin.math.sqrt(sumSq / offset) / 32768.0, 1e-12)
                )
            }
            return PacedFeeder.FeedStats(
                frames = frames,
                audioDurationMs = audio.durationMs,
                feedStartUptimeMs = start,
                feedEndUptimeMs = end,
                sinkBusyMs = sinkBusy,
                maxSlipMs = maxSlip,
                finalSlipMs = lastSlip,
                suspendedMs = (end - start) - awake,
            )
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    companion object {
        /** AudioRecord buffer, seconds: room for a slow sink to back up into. */
        const val BUFFER_S = 10
    }
}
