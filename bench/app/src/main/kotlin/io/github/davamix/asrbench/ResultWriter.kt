package io.github.davamix.asrbench

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Accumulates run rows and writes them as JSON to the app's external files
 * dir, where `adb pull` can retrieve them.
 *
 * Everything the harness touches lives inside
 * `/sdcard/Android/data/<applicationId>/files/`, which goes away when the app
 * is uninstalled. That is the clean exit, and it is the entire write scope on
 * the device (PLAN.md §11.2).
 */
class ResultWriter(
    private val context: Context,
    private val runLabel: String,
    /**
     * Under the app's files dir. "results" is what `run_bench.py` pulls into
     * the repo; the microphone check writes elsewhere, because its rows come
     * from the owner's voice and stay off the repo (PLAN.md §11.7).
     */
    private val subdir: String = "results",
) {

    private val rows = JSONArray()
    private val started = System.currentTimeMillis()
    private var notes = JSONObject()

    fun note(key: String, value: Any?) {
        notes.put(key, value ?: JSONObject.NULL)
    }

    fun add(
        arm: Arm,
        clip: Manifest.Clip,
        rep: Int,
        threads: Int,
        result: ArmResult,
        batteryBefore: Telemetry.Battery,
        batteryAfter: Telemetry.Battery,
        modelLoadMs: Long,
        /** At the start of the clip; null where not taken. */
        usage: Telemetry.Usage? = null,
    ) {
        rows.put(JSONObject().apply {
            put("arm", arm.id)
            put("arm_label", arm.label)
            put("runtime", arm.runtime)
            put("disk_size_mb", arm.diskSizeMb)

            put("clip_id", clip.clipId)
            put("lang", clip.language)
            put("bucket", clip.bucket)
            put("source", clip.source)
            put("rep", rep)
            put("threads", threads)

            put("hypothesis", result.hypothesis)
            putOrNull("latency_first_partial_ms", result.latencyFirstPartialMs)
            putOrNull("latency_final_ms", result.latencyFinalMs)
            put("partial_instability", result.partialInstability)
            put("partial_updates", result.partialUpdates)
            putOrNull("rtf_sustained", result.rtfSustained)
            put("max_slip_ms", result.maxSlipMs)
            put("audio_duration_ms", result.audioDurationMs)
            putOrNull("peak_rss_mb", result.peakRssMb)
            put("model_load_ms", modelLoadMs)

            putOrNull("battery_temp_c_before", batteryBefore.tempC)
            putOrNull("battery_temp_c_after", batteryAfter.tempC)
            putOrNull("battery_pct_before", batteryBefore.pct)
            putOrNull("battery_pct_after", batteryAfter.pct)
            putOrNull("charging", batteryBefore.charging)
            if (usage != null) {
                putOrNull("screen_on", usage.screenOn)
                putOrNull("audio_mode", usage.audioMode)
            }

            putOrNull("error", result.error)

            if (result.extra.isNotEmpty()) {
                put("extra", JSONObject().apply {
                    result.extra.forEach { (k, v) -> putOrNull(k, v) }
                })
            }
        })
    }

    fun write(): File {
        val dir = File(context.getExternalFilesDir(null), subdir).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(started))

        val doc = JSONObject().apply {
            put("schema", 1)
            put("run_label", runLabel)
            put("started_utc", isoUtc(started))
            put("finished_utc", isoUtc(System.currentTimeMillis()))
            put("device", JSONObject(Telemetry.deviceInfo(context)))
            put("notes", notes)
            put("runs", rows)
        }

        val out = File(dir, "$runLabel-$stamp.json")
        out.writeText(doc.toString(2))
        return out
    }

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    private fun JSONObject.putOrNull(key: String, value: Any?) {
        if (value == null) put(key, JSONObject.NULL) else put(key, value)
    }
}
