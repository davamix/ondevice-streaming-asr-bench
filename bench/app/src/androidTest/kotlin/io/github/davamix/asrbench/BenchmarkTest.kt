package io.github.davamix.asrbench

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.davamix.asrbench.arms.LanguagePackInstaller
import io.github.davamix.asrbench.arms.MoonshineArm
import io.github.davamix.asrbench.arms.PlatformRecognizerArm
import io.github.davamix.asrbench.arms.RecognitionSupportProbe
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The harness entry point. Headless by design -- this is an instrument, not a
 * demo; a UI comes after there are numbers (PLAN.md §9).
 *
 * Driven from the host:
 *
 *   adb shell am instrument -w \
 *     -e class io.github.davamix.asrbench.BenchmarkTest#benchmark \
 *     -e arms A -e langs en,es -e buckets short -e reps 4 \
 *     io.github.davamix.asrbench.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `scripts/run_bench.py` wraps that with the pre-flight checks from §11.6.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkTest {

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instr.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun arg(key: String, default: String): String =
        args.getString(key)?.takeIf { it.isNotBlank() } ?: default

    private fun argList(key: String, default: String): List<String> =
        arg(key, default).split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Probes what the platform recognizer supports on this build and writes it
     * to the results dir.
     *
     * Runs before any measurement because it answers an open risk in §12 --
     * whether Spanish is actually installed on this MIUI build -- for free, in
     * seconds, with no inference and no thermal cost.
     */
    @Test
    fun probeRecognitionSupport() {
        val langs = argList("langs", "en-US,es-ES")
        val doc = JSONObject().apply {
            put("device", JSONObject(Telemetry.deviceInfo(context)))
            put("probed_utc", System.currentTimeMillis())
        }
        val results = JSONObject()

        for (lang in langs) {
            val support = RecognitionSupportProbe.probe(context, lang)
            results.put(lang, JSONObject(support.toMap()))
            Log.i(TAG, "support[$lang]: available=${support.onDeviceAvailable} " +
                "installed=${support.installedOnDevice} " +
                "pending=${support.pendingOnDevice} " +
                "supported=${support.supportedOnDevice} " +
                "error=${support.error}")
        }
        doc.put("languages", results)

        val dir = File(context.getExternalFilesDir(null), "results").apply { mkdirs() }
        val out = File(dir, "recognition-support.json")
        out.writeText(doc.toString(2))
        Log.i(TAG, "wrote ${out.absolutePath}")
    }

    /**
     * Requests an on-device recognition language pack via the supported
     * `SpeechRecognizer.triggerModelDownload()` API (API 33).
     *
     *   -e lang en-US   -e timeout_s 300
     *
     * Needed because this handset ships only `es-ES`, which makes Arm A
     * unmeasurable on English. Note that Google Translate's offline packs are
     * a *different* mechanism and do not satisfy this.
     */
    @Test
    fun downloadLanguagePack() {
        val lang = arg("lang", "en-US")
        val timeoutS = arg("timeout_s", "300").toLong()

        val outcome = LanguagePackInstaller.install(
            context, lang, timeoutMs = timeoutS * 1000,
        )

        Log.i(TAG, "INSTALL RESULT[$lang]: succeeded=${outcome.succeeded} " +
            "wentPending=${outcome.wentPending} " +
            "installedAfter=${outcome.installedAfter} " +
            "pendingAfter=${outcome.pendingAfter} " +
            "waited=${outcome.waitedMs}ms error=${outcome.error}")

        val dir = File(context.getExternalFilesDir(null), "results").apply { mkdirs() }
        File(dir, "language-pack-$lang.json").writeText(
            JSONObject(outcome.toMap()).toString(2)
        )

        // A refused or deferred download is a real outcome, not a harness
        // failure -- the request is made into Google's process and can simply
        // be declined. It is reported, not asserted.
    }

    /**
     * Creates the corpus/model directory tree **as the app**, and reports what
     * the app can actually see there.
     *
     * `adb push` writes as the `shell` user. On Android 11+ a directory that
     * shell creates inside an app's own external files dir is not reliably
     * readable by that app -- the push succeeds, the app sees nothing, and the
     * failure looks like a missing corpus rather than a permissions problem.
     * Having the app create the directories first makes them app-owned, and
     * pushes into them land readable.
     *
     * Run before pushing anything. `scripts/run_bench.py` does this
     * automatically.
     */
    @Test
    fun prepareDirs() {
        val filesDir = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir returned null -- external storage unavailable")

        val dirs = listOf(
            "corpus", "corpus/audio", "corpus/audio/short", "corpus/audio/session",
            "models", "results",
        )
        for (d in dirs) {
            val f = File(filesDir, d)
            val ok = f.exists() || f.mkdirs()
            Log.i(TAG, "mkdir $d -> $ok (${f.absolutePath})")
        }

        // Report what is visible, so a push that silently failed is obvious.
        Log.i(TAG, "PREPARE files dir = ${filesDir.absolutePath}")
        Log.i(TAG, "PREPARE writable = ${filesDir.canWrite()}")
        val corpus = File(filesDir, "corpus")
        val manifest = File(corpus, "manifest.json")
        Log.i(TAG, "PREPARE manifest exists=${manifest.exists()} " +
            "canRead=${manifest.canRead()} size=${manifest.length()}")
        val shortDir = File(filesDir, "corpus/audio/short")
        Log.i(TAG, "PREPARE short clips visible = ${shortDir.list()?.size ?: -1}")

        assertTrue("could not create ${corpus.absolutePath}", corpus.isDirectory)
    }

    /**
     * Validates everything except the recognizer: WAV parsing, the pacing
     * loop, the instability counter and the JSON writer.
     *
     * This is the test that belongs on the emulator. The emulator has no
     * on-device recognition service, and its timings are meaningless anyway
     * (D1) -- but "does the manifest parse, does the audio load, does JSON
     * come back" is exactly what it is for, and every adb path gets exercised
     * here before it ever touches the phone (§11.5).
     */
    @Test
    fun plumbing() {
        val filesDir = context.getExternalFilesDir(null)!!
        val manifestFile = File(filesDir, "corpus/manifest.json")
        assertTrue("corpus not pushed: ${manifestFile.absolutePath}", manifestFile.exists())

        val manifest = Manifest.read(manifestFile)
        Log.i(TAG, "manifest parsed: ${manifest.clips.size} clips")
        assertTrue("manifest empty", manifest.clips.isNotEmpty())

        val clip = manifest.byBucket("short").first()
        val audioFile = File(filesDir, "corpus/${clip.audioRelPath}")
        assertTrue("missing audio ${audioFile.absolutePath}", audioFile.exists())

        // WAV parsing enforces 16 kHz mono PCM16; anything else throws.
        val audio = Wav.read(audioFile)
        Log.i(TAG, "wav ok: ${clip.clipId} ${audio.durationMs}ms " +
            "${audio.samples.size} samples @ ${audio.sampleRate}Hz")
        assertEquals(16000, audio.sampleRate)

        // Pacing: frames must arrive on the wall clock, not as fast as the
        // disk can read. If this is wrong every latency number is wrong.
        var frames = 0
        var lastAtMs = -1L
        val stats = PacedFeeder(audio).feed({ pcm, atMs ->
            frames++
            assertTrue("frame $frames out of order", atMs > lastAtMs)
            assertTrue("empty frame", pcm.isNotEmpty())
            lastAtMs = atMs
        })
        val elapsed = stats.feedEndUptimeMs - stats.feedStartUptimeMs
        Log.i(TAG, "paced feed: $frames frames, audio=${stats.audioDurationMs}ms " +
            "wall=${elapsed}ms maxSlip=${stats.maxSlipMs}ms " +
            "sinkBusy=${stats.sinkBusyMs}ms")

        // Wall-clock time must track audio duration. A fast-as-possible read
        // would finish in milliseconds and silently invalidate the method.
        assertTrue(
            "feed finished in ${elapsed}ms for ${stats.audioDurationMs}ms of audio " +
                "-- pacing is not working",
            elapsed >= stats.audioDurationMs - PacedFeeder.DEFAULT_FRAME_MS,
        )
        assertTrue("feed took far too long: ${elapsed}ms", elapsed < stats.audioDurationMs * 2)

        // Instability metric: extension is free, rewriting is charged.
        val t = TranscriptTracker()
        t.update("the quick")
        t.update("the quick brown")
        assertEquals("pure extension must cost nothing", 0, t.revisions)
        t.update("the quick red")
        assertEquals("one rewritten word", 1, t.revisions)
        t.update("a slow dog")
        assertEquals("three more rewritten", 4, t.revisions)

        // Telemetry and the JSON writer.
        val bat = Telemetry.battery(context)
        Log.i(TAG, "battery: ${bat.pct}% ${bat.tempC}C charging=${bat.charging}")
        Log.i(TAG, "peak rss: ${Telemetry.peakRssMb()} MB, emulator=${Telemetry.isEmulator()}")

        val writer = ResultWriter(context, "plumbing")
        writer.note("purpose", "plumbing only -- no model, no timings of record")
        writer.add(
            arm = PlatformRecognizerArm(),
            clip = clip,
            rep = 0,
            threads = 4,
            result = ArmResult(
                hypothesis = "",
                latencyFirstPartialMs = null,
                latencyFinalMs = null,
                partialInstability = t.revisions,
                partialUpdates = t.updates,
                rtfSustained = stats.rtfSustained,
                maxSlipMs = stats.maxSlipMs,
                audioDurationMs = stats.audioDurationMs,
                peakRssMb = Telemetry.peakRssMb(),
                extra = mapOf("frames" to frames, "wall_ms" to elapsed),
            ),
            batteryBefore = bat,
            batteryAfter = Telemetry.battery(context),
            modelLoadMs = 0,
        )
        val out = writer.write()
        Log.i(TAG, "wrote ${out.absolutePath} (${out.length()} bytes)")
        assertTrue("result file not written", out.exists() && out.length() > 0)
    }

    /** Arm A end to end on a single clip -- the cheapest possible plumbing check. */
    @Test
    fun smokeArmA() {
        val manifestFile = File(context.getExternalFilesDir(null), "corpus/manifest.json")
        assertTrue(
            "corpus not pushed: ${manifestFile.absolutePath}",
            manifestFile.exists(),
        )
        val manifest = Manifest.read(manifestFile)
        val clip = manifest.clips.first { it.bucket == "short" && it.language == "en" }
        val audioFile = File(context.getExternalFilesDir(null), "corpus/${clip.audioRelPath}")
        assertTrue("missing audio ${audioFile.absolutePath}", audioFile.exists())

        val audio = Wav.read(audioFile)
        Log.i(TAG, "smoke clip ${clip.clipId}: ${audio.durationMs}ms @ ${audio.sampleRate}Hz")

        PlatformRecognizerArm().use { arm ->
            arm.load(context)
            val r = arm.transcribe(context, audio, clip.language, threads = 4)
            Log.i(TAG, "smoke result: err=${r.error} " +
                "final=${r.latencyFinalMs}ms first=${r.latencyFirstPartialMs}ms " +
                "instab=${r.partialInstability} text=${r.hypothesis.take(120)}")
        }
    }

    /** The matrix run. */
    @Test
    fun benchmark() {
        val armIds = argList("arms", "A")
        val langs = argList("langs", "en,es")
        val buckets = argList("buckets", "short")
        val reps = arg("reps", "4").toInt()
        val threads = arg("threads", "4").toInt()
        val label = arg("label", "bench")
        val seed = arg("seed", "1").toInt()

        // The emulator reports a fixed, meaningless battery temperature, so the
        // thermal gate would either never fire or fire always. It is for the
        // phone (PLAN.md §11.3); on the emulator the harness is only being
        // checked for correctness (D1, §11.5).
        val onEmulator = Telemetry.isEmulator()
        val enforceThermal = arg("thermal", if (onEmulator) "false" else "true").toBoolean()

        // Arm B is selected per variant: "B:moonshine-small-en", or plain
        // "B" for every variant that has been pushed to the device.
        val arms = armIds.flatMap { spec ->
            val id = spec.substringBefore(':').uppercase()
            val detail = spec.substringAfter(':', "")
            when (id) {
                "A" -> listOf(
                    PlatformRecognizerArm(segmented = buckets.contains("session"))
                )
                "B" -> {
                    val wanted = if (detail.isNotEmpty()) {
                        listOf(detail)
                    } else {
                        MoonshineArm.variants().keys.filter { v ->
                            File(context.getExternalFilesDir(null), "models/$v").isDirectory
                        }
                    }
                    check(wanted.isNotEmpty()) {
                        "no Moonshine variants found under files/models/. " +
                            "Push them with scripts/run_bench.py --push-models"
                    }
                    wanted.map { v ->
                        MoonshineArm(v, MoonshineArm.variants()[v] ?: 0.0)
                    }
                }
                else -> throw IllegalArgumentException(
                    "arm $id is not implemented yet. Implemented: A, B"
                )
            }
        }

        Log.i(TAG, "benchmark arms=$armIds langs=$langs buckets=$buckets " +
            "reps=$reps threads=$threads emulator=$onEmulator thermal=$enforceThermal")

        val outcome = BenchmarkRunner(
            context,
            BenchmarkRunner.Config(
                arms = arms,
                languages = langs,
                buckets = buckets,
                reps = reps,
                threads = threads,
                label = label,
                seed = seed,
                enforceThermal = enforceThermal,
            ),
        ).run()

        Log.i(TAG, "wrote ${outcome.rows} rows -> ${outcome.resultFile.absolutePath}")
        if (outcome.aborted != null) Log.e(TAG, "run aborted: ${outcome.aborted}")
        assertTrue("no rows produced", outcome.rows > 0)
    }

    private companion object {
        const val TAG = "AsrBench"
    }
}
