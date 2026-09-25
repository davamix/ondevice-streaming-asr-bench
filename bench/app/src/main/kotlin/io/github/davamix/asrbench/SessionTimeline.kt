package io.github.davamix.asrbench

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * What happens *inside* a long clip.
 *
 * A row holds one set of numbers per clip. For a 5 s utterance that is enough;
 * for a 6-minute session (PLAN.md §6, `rtf_sustained`) it hides exactly what
 * the session exists to show: whether the model falls behind as the phone
 * heats up. So a clip at least [MIN_AUDIO_MS] long gets a timeline, and
 * shorter clips get none, which leaves their rows as they were.
 *
 *  - **Windows**, every [WINDOW_MS] of audio: the compute spent in that
 *    stretch over its length (the `rtf_sustained` of the window), how late
 *    the feeder was releasing audio, battery temperature, the platform's
 *    thermal status and headroom, resident memory, and whether the screen
 *    was on or a call in progress (someone using the phone).
 *  - **Events**, one per final text: each final segment (VAD arms) or
 *    completed line (Moonshine), with its span on the audio timeline and when
 *    its text was committed, all in ms from the feed's start. Final latency
 *    per utterance, early in the session against late, comes from these.
 *
 * It also enforces the 43 C abort (PLAN.md §11.3) *during* a clip. The runner
 * checks temperature only between clips, and a session is one clip six
 * minutes long.
 *
 * Decode time spent off the feed thread is credited to the window in which
 * the decode finished, so a window can be off by one decode (~0.3 s of 30).
 */
class SessionTimeline private constructor(private val context: Context) {

    private val offThreadMs = AtomicLong()
    private val windows = JSONArray()
    private val events = JSONArray()

    private var nextMs = WINDOW_MS
    private var lastAt = 0L
    private var lastSink = 0L
    private var lastOff = 0L
    private var seenAt = 0L
    private var seenSink = 0L
    private var seenSlip = 0L

    /** Non-null once the battery reads at or above [ABORT_TEMP_C]. */
    @Volatile var abortReason: String? = null
        private set

    /** Feed-side progress; wire to [PacedFeeder.feed]'s `onProgress`. */
    val progress = PacedFeeder.Progress { audioMs, sinkBusyMs, slipMs ->
        seenAt = audioMs
        seenSink = sinkBusyMs
        seenSlip = slipMs
        if (audioMs >= nextMs) {
            sample()
            while (nextMs <= audioMs) nextMs += WINDOW_MS
        }
    }

    /** Decode time spent on another thread (the VAD arms' worker). */
    fun addDecode(ms: Long) {
        offThreadMs.addAndGet(ms)
    }

    /** One final segment or completed line. Any thread. */
    fun event(e: JSONObject) {
        synchronized(events) { events.put(e) }
    }

    /**
     * Close the last, partial window. Call once the clip's decoding is done,
     * so decodes that finish after the feed are counted.
     */
    fun finish() {
        if (seenAt > lastAt || offThreadMs.get() > lastOff) sample()
    }

    fun windowsJson(): JSONArray = windows

    fun eventsJson(): JSONArray = synchronized(events) { JSONArray(events.toString()) }

    private fun sample() {
        val off = offThreadMs.get()
        val span = seenAt - lastAt
        val busy = (seenSink - lastSink) + (off - lastOff)
        val bat = Telemetry.battery(context)
        val thermal = Telemetry.thermal(context)
        val usage = Telemetry.usage(context)
        windows.put(JSONObject().apply {
            put("audio_ms", seenAt)
            put("sink_ms", seenSink - lastSink)
            put("decode_ms", off - lastOff)
            put("rtf", if (span > 0) busy.toDouble() / span else JSONObject.NULL)
            put("slip_ms", seenSlip)
            put("temp_c", bat.tempC ?: JSONObject.NULL)
            put("thermal_status", thermal.status ?: JSONObject.NULL)
            put("thermal_headroom", thermal.headroom ?: JSONObject.NULL)
            put("rss_mb", Telemetry.currentRssMb() ?: JSONObject.NULL)
            put("screen_on", usage.screenOn ?: JSONObject.NULL)
            put("audio_mode", usage.audioMode ?: JSONObject.NULL)
        })
        lastAt = seenAt
        lastSink = seenSink
        lastOff = off

        val t = bat.tempC
        if (t != null && t >= ABORT_TEMP_C && abortReason == null) {
            abortReason = "battery $t C >= abort threshold $ABORT_TEMP_C C mid-clip, " +
                "at ${seenAt / 1000}s of audio"
            Log.e(TAG, "ABORT: $abortReason")
        }
    }

    companion object {
        private const val TAG = "AsrBench"

        const val WINDOW_MS = 30_000L

        /** Clips at least this long get a timeline; every short clip is < 30 s. */
        const val MIN_AUDIO_MS = 60_000L

        /** Same threshold as [BenchmarkRunner.Config.abortTempC]. */
        const val ABORT_TEMP_C = 43.0

        fun forAudio(context: Context, audio: Wav.Audio): SessionTimeline? =
            if (audio.durationMs >= MIN_AUDIO_MS) SessionTimeline(context) else null
    }
}
