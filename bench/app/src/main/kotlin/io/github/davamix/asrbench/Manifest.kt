package io.github.davamix.asrbench

import org.json.JSONObject
import java.io.File

/**
 * The corpus manifest, as written by `scripts/build_corpus.py` and pushed to
 * the device alongside the audio.
 *
 * Reference transcripts are deliberately *not* read here. The device emits
 * hypothesis text only; scoring happens on the PC. That keeps the harness
 * small and means a scoring bug never costs a re-run (PLAN.md §6).
 */
class Manifest(val clips: List<Clip>) {

    class Clip(
        val clipId: String,
        val audioRelPath: String,
        val language: String,
        val bucket: String,
        val durationS: Double,
        val source: String,
    ) {
        val isSession: Boolean get() = bucket == "session"
    }

    fun byBucket(bucket: String): List<Clip> = clips.filter { it.bucket == bucket }

    fun byLanguage(language: String): List<Clip> = clips.filter { it.language == language }

    fun find(clipId: String): Clip? = clips.firstOrNull { it.clipId == clipId }

    companion object {
        fun read(file: File): Manifest {
            val root = JSONObject(file.readText())
            val arr = root.getJSONArray("clips")
            val clips = ArrayList<Clip>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                clips += Clip(
                    clipId = o.getString("clip_id"),
                    audioRelPath = o.getString("audio"),
                    language = o.getString("language"),
                    bucket = o.getString("bucket"),
                    durationS = o.getDouble("duration_s"),
                    source = o.optString("source", "unknown"),
                )
            }
            return Manifest(clips)
        }
    }
}
