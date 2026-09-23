package io.github.davamix.asrbench

import android.os.SystemClock

/**
 * Releases PCM to a sink in **wall-clock-paced** chunks (PLAN.md §7).
 *
 * This is the piece the whole method rests on. Measured runs are file-fed
 * rather than microphone-fed, because you cannot say the same sentence twice
 * identically and mic input would confound every comparison with your own
 * delivery. But a file read at full speed is not streaming -- it hands the
 * model the future, which is exactly what a live model does not get.
 *
 * So frames are released on a real clock: frame N is not handed over until
 * N * frameMs have actually elapsed. The model sees precisely what it would
 * see from a microphone, while the input stays byte-identical across every arm
 * and every repetition.
 *
 * Two things are measured while feeding, and both matter more than they look:
 *
 *  - [FeedStats.sinkBusyMs] -- total time spent *inside* the sink. Divided by
 *    audio duration this is the fraction of real time the model consumes, i.e.
 *    the honest sustained RTF. Above 1.0 the model cannot keep up with a
 *    microphone and latency grows without bound.
 *
 *  - [FeedStats.maxSlipMs] -- how far behind schedule the feeder ever fell.
 *    Non-zero slip means the sink blocked longer than a frame; growing slip
 *    over a session is thermal throttling becoming visible.
 */
class PacedFeeder(
    private val audio: Wav.Audio,
    private val frameMs: Int = DEFAULT_FRAME_MS,
) {

    fun interface Sink {
        /**
         * @param pcm   the frame, freshly sliced (never reused between calls)
         * @param atMs  audio-timeline position of this frame's first sample
         */
        fun onFrame(pcm: ShortArray, atMs: Long)
    }

    class FeedStats(
        val frames: Int,
        val audioDurationMs: Long,
        val feedStartUptimeMs: Long,
        val feedEndUptimeMs: Long,
        val sinkBusyMs: Long,
        val maxSlipMs: Long,
        val finalSlipMs: Long,
        /**
         * Wall-clock time minus CPU-awake time across the whole feed. Non-zero
         * means the device suspended mid-feed, so the model was not actually
         * receiving audio in real time no matter what the schedule says.
         */
        val suspendedMs: Long,
    ) {
        /** Fraction of real time the sink consumed. > 1.0 = cannot keep up. */
        val rtfSustained: Double
            get() = if (audioDurationMs > 0) sinkBusyMs.toDouble() / audioDurationMs else Double.NaN
    }

    private val framesamples: Int = audio.sampleRate * frameMs / 1000

    /**
     * Feeds [audio] to [sink], pacing to the wall clock.
     *
     * [onStart] runs immediately before the first frame is released, so a
     * caller can timestamp "speech started" without racing the first frame.
     */
    fun feed(
        sink: Sink,
        onStart: (() -> Unit)? = null,
        /**
         * Checked before every frame. Returning true stops the feed early.
         *
         * A consumer can fail mid-clip -- the platform recognizer aborts
         * immediately if a language pack is missing, for instance -- and a
         * feeder that keeps pushing into something that has stopped reading
         * will block forever once the pipe buffer fills. Stopping early turns
         * that hang into a recorded failure.
         */
        shouldStop: (() -> Boolean)? = null,
    ): FeedStats {
        val total = audio.samples.size
        var offset = 0
        var frames = 0
        var sinkBusy = 0L
        var maxSlip = 0L
        var lastSlip = 0L

        // Paced against elapsedRealtime, which keeps counting through
        // suspend, rather than uptimeMillis, which does not. Pacing against
        // uptime lets a dozing device silently stretch a "real-time" feed.
        val start = SystemClock.elapsedRealtime()
        val startUptime = SystemClock.uptimeMillis()
        onStart?.invoke()

        while (offset < total) {
            if (shouldStop?.invoke() == true) break
            val n = minOf(framesamples, total - offset)
            val scheduled = start + frames.toLong() * frameMs

            // Wait until this frame is genuinely due. A model must not be
            // allowed to see audio before it would exist.
            var now = SystemClock.elapsedRealtime()
            if (now < scheduled) {
                SystemClock.sleep(scheduled - now)
                now = SystemClock.elapsedRealtime()
            }

            val slip = now - scheduled
            if (slip > maxSlip) maxSlip = slip
            lastSlip = slip

            val frame = audio.samples.copyOfRange(offset, offset + n)
            val atMs = offset * 1000L / audio.sampleRate

            val t0 = SystemClock.elapsedRealtime()
            sink.onFrame(frame, atMs)
            sinkBusy += SystemClock.elapsedRealtime() - t0

            offset += n
            frames++
        }

        val end = SystemClock.elapsedRealtime()
        val awake = SystemClock.uptimeMillis() - startUptime
        return FeedStats(
            suspendedMs = (end - start) - awake,
            frames = frames,
            audioDurationMs = audio.durationMs,
            feedStartUptimeMs = start,
            feedEndUptimeMs = end,
            sinkBusyMs = sinkBusy,
            maxSlipMs = maxSlip,
            finalSlipMs = lastSlip,
        )
    }

    companion object {
        /**
         * 100 ms. Small enough that it does not itself dominate the latency
         * figures, large enough to avoid measuring scheduler jitter.
         */
        const val DEFAULT_FRAME_MS = 100
    }
}
