package io.github.davamix.asrbench

import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlin.random.Random

/**
 * Drives the matrix: for each repetition, for each (arm, clip), feed the clip
 * and record a row.
 *
 * Two things here are methodology, not housekeeping:
 *
 *  - **Arm order is randomised per repetition** (PLAN.md §6). If every rep ran
 *    A then B then C, the device would be coldest for A and hottest for C
 *    every single time, and run order would masquerade as an arm effect.
 *
 *  - **The thermal gate is enforced in the loop**, not just at the start
 *    (§11.3). The test device is the owner's only phone; a run that would push
 *    it past 43 C stops, and the stop is recorded as a result rather than
 *    silently skipped.
 */
class BenchmarkRunner(
    private val context: Context,
    private val config: Config,
) {

    class Config(
        val arms: List<Arm>,
        val languages: List<String>,
        val buckets: List<String>,
        /**
         * Corpus sources to include, e.g. "fleurs_en_norm". Empty means all.
         * Lets a re-measure skip sources it does not need: ranking the
         * English models needs the level-matched and noisy clips, not the
         * very quiet originals, which would add half again the phone time.
         */
        val sources: List<String> = emptyList(),
        val reps: Int = 4,
        val threads: Int = 4,
        val label: String = "run",
        val seed: Int = 1,
        /** Start gate. Refuse to begin a clip above this. */
        val maxStartTempC: Double = 35.0,
        /** Hard abort. Stop everything above this. */
        val abortTempC: Double = 43.0,
        /** Set false only on the emulator, which reports no meaningful temp. */
        val enforceThermal: Boolean = true,
        /** Cooldown between clips, milliseconds. */
        val cooldownMs: Long = 2_000,
        /**
         * Cap on *continuous* inference before a mandatory break (§11.3).
         * Exceeding it is what makes a run continuous rather than intermittent,
         * which is the thing the policy limits.
         */
        val sessionCapMs: Long = 10 * 60 * 1000,
        /** Length of that mandatory break. */
        val sessionBreakMs: Long = 120_000,
    )

    class Outcome(val resultFile: File, val rows: Int, val aborted: String?)

    private val filesDir: File get() = context.getExternalFilesDir(null)!!

    fun run(): Outcome {
        val manifestFile = File(filesDir, "corpus/manifest.json")
        check(manifestFile.exists()) {
            "corpus manifest missing at ${manifestFile.absolutePath}. " +
                "Push it with scripts/push_corpus.py first."
        }
        val manifest = Manifest.read(manifestFile)
        val writer = ResultWriter(context, config.label)

        writer.note("frame_ms", PacedFeeder.DEFAULT_FRAME_MS)
        writer.note("reps", config.reps)
        writer.note("threads", config.threads)
        writer.note("seed", config.seed)
        writer.note("sources", config.sources.joinToString(",").ifEmpty { "all" })
        writer.note("thermal_enforced", config.enforceThermal)

        val clips = manifest.clips.filter {
            it.language in config.languages && it.bucket in config.buckets &&
                (config.sources.isEmpty() || it.source in config.sources)
        }.sortedBy { it.clipId }

        check(clips.isNotEmpty()) {
            "no clips matched languages=${config.languages} buckets=${config.buckets} " +
                "sources=${config.sources.ifEmpty { listOf("all") }}"
        }

        Log.i(TAG, "matrix: ${config.arms.size} arms x ${clips.size} clips x ${config.reps} reps")

        // Load every arm once, outside the measurement windows.
        // A load failure must not be swallowed. Silently continuing produced
        // 160 rows of "transcriber not loaded" from one unreadable model
        // directory -- a full run's worth of thermal budget spent recording
        // the same message. An arm that cannot load is dropped, loudly, and
        // the reason is written into the results.
        // Keyed by the arm itself, not its letter: two variants of one arm
        // share a letter, and would otherwise record each other's load time.
        val loadMs = HashMap<Arm, Long>()
        val usable = ArrayList<Arm>()
        for (arm in config.arms) {
            val result = runCatching { arm.load(context) }
            result.onSuccess { ms ->
                loadMs[arm] = ms
                usable += arm
                Log.i(TAG, "loaded arm ${arm.id} (${arm.label}) in ${ms}ms")
            }.onFailure { t ->
                val why = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "arm ${arm.id} (${arm.label}) FAILED TO LOAD: $why")
                writer.note("load_failed_${arm.id}_${arm.label}", why)
            }
        }
        check(usable.isNotEmpty()) {
            "no arm loaded successfully; see load_failed_* in the results notes"
        }

        // Keep the CPU awake for the whole matrix. A dozing device suspends
        // between paced frames, which stretches a "real-time" feed without the
        // feeder noticing -- a 6-minute session took 17 minutes of wall clock
        // that way. The lock is released in the finally below, always.
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "asrbench:run")
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(MAX_RUN_MS)
        Log.i(TAG, "wake lock acquired")

        var rows = 0
        var aborted: String? = null
        var sessionStart = System.currentTimeMillis()
        var breaks = 0

        outer@ for (rep in 0 until config.reps) {
            // Randomise arm order per repetition so run order cannot be
            // mistaken for an arm effect.
            val order = usable.shuffled(Random(config.seed * 1000 + rep))
            Log.i(TAG, "rep $rep arm order: ${order.joinToString(",") { it.id }}")

            for (arm in order) {
                for (clip in clips) {
                    if (!arm.supports(clip.language)) {
                        Log.i(TAG, "arm ${arm.id} does not support ${clip.language}, skipping")
                        continue
                    }

                    // §11.3 caps continuous inference at 10 minutes. A long
                    // matrix is allowed to exceed that in total, but only by
                    // being broken up -- the cap is on how long the phone runs
                    // hot without a pause, not on how much work it does.
                    val elapsed = System.currentTimeMillis() - sessionStart
                    if (elapsed >= config.sessionCapMs) {
                        breaks++
                        Log.i(TAG, "session cap reached (${elapsed / 1000}s) -- " +
                            "pausing ${config.sessionBreakMs / 1000}s (break #$breaks)")
                        // Checkpoint while nothing is being measured. Results
                        // are otherwise written only at the end, so a crash an
                        // hour into a 90-minute run would lose all of it. The
                        // same file is overwritten, and marked incomplete
                        // until the final write.
                        writer.note("complete", false)
                        runCatching { writer.write() }
                            .onSuccess { Log.i(TAG, "checkpoint: $rows rows -> ${it.name}") }
                            .onFailure { Log.w(TAG, "checkpoint write failed: $it") }
                        Thread.sleep(config.sessionBreakMs)
                        sessionStart = System.currentTimeMillis()
                    }

                    val before = Telemetry.battery(context)

                    if (config.enforceThermal) {
                        val t = before.tempC
                        if (t != null && t >= config.abortTempC) {
                            aborted = "battery ${t} C >= abort threshold ${config.abortTempC} C"
                            Log.e(TAG, "ABORT: $aborted")
                            break@outer
                        }
                        if (before.charging == true) {
                            aborted = "device is charging; charging heat plus inference heat compounds"
                            Log.e(TAG, "ABORT: $aborted")
                            break@outer
                        }
                        if (t != null && t >= config.maxStartTempC) {
                            Log.w(TAG, "waiting to cool: ${t} C >= ${config.maxStartTempC} C")
                            if (!coolDown(config.maxStartTempC)) {
                                aborted = "did not cool below ${config.maxStartTempC} C in time"
                                Log.e(TAG, "ABORT: $aborted")
                                break@outer
                            }
                        }
                    }

                    val audioFile = File(filesDir, "corpus/${clip.audioRelPath}")
                    if (!audioFile.exists()) {
                        Log.w(TAG, "missing audio ${audioFile.absolutePath}, skipping")
                        continue
                    }

                    val result = runCatching {
                        val audio = Wav.read(audioFile)
                        arm.transcribe(context, audio, clip.language, config.threads)
                    }.getOrElse { t ->
                        ArmResult.failed(
                            "${t.javaClass.simpleName}: ${t.message}",
                            (clip.durationS * 1000).toLong(),
                        )
                    }

                    val after = Telemetry.battery(context)
                    writer.add(
                        arm = arm,
                        clip = clip,
                        rep = rep,
                        threads = config.threads,
                        result = result,
                        batteryBefore = before,
                        batteryAfter = after,
                        modelLoadMs = loadMs[arm] ?: -1L,
                    )
                    rows++

                    Log.i(
                        TAG,
                        "rep=$rep arm=${arm.id} clip=${clip.clipId} " +
                            "final=${result.latencyFinalMs}ms " +
                            "first=${result.latencyFirstPartialMs}ms " +
                            "instab=${result.partialInstability} " +
                            "err=${result.error ?: "-"}",
                    )

                    if (config.cooldownMs > 0) Thread.sleep(config.cooldownMs)
                }
            }
        }

        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.i(TAG, "wake lock released")
        }
        writer.note("session_breaks", breaks)
        writer.note("session_cap_ms", config.sessionCapMs)
        writer.note("complete", true)
        if (aborted != null) writer.note("aborted", aborted)
        usable.forEach { runCatching { it.close() } }

        val out = writer.write()
        Log.i(TAG, "wrote $rows rows to ${out.absolutePath}")
        return Outcome(out, rows, aborted)
    }

    /** Wait for the battery to drop below [target], up to a bounded time. */
    private fun coolDown(target: Double): Boolean {
        val deadline = System.currentTimeMillis() + COOLDOWN_LIMIT_MS
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000)
            val t = Telemetry.battery(context).tempC ?: return true
            Log.i(TAG, "cooling: ${t} C (target < $target C)")
            if (t < target) return true
        }
        return false
    }

    private companion object {
        const val TAG = "AsrBench"
        const val COOLDOWN_LIMIT_MS = 10 * 60 * 1000L

        /** Upper bound on a single matrix run; the wake lock expires with it. */
        const val MAX_RUN_MS = 3 * 60 * 60 * 1000L
    }
}
