package io.github.davamix.asrbench

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.davamix.asrbench.arms.LanguagePackInstaller
import io.github.davamix.asrbench.arms.MoonshineArm
import io.github.davamix.asrbench.arms.PlatformRecognizerArm
import io.github.davamix.asrbench.arms.RecognitionSupportProbe
import io.github.davamix.asrbench.arms.SherpaOfflineArm
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

        // Model variant dirs are created here too, for the same reason the
        // corpus dirs are: adb push creates them as `shell`, and a
        // shell-owned directory inside the app's own external files dir is not
        // readable by the app. That failure mode already cost a full 160-row
        // emulator run that reported "transcriber not loaded" on every row.
        val modelDirs = argList("model_dirs", "")
            .map { "models/$it" }
        val dirs = listOf(
            "corpus", "corpus/audio", "corpus/audio/short", "corpus/audio/session",
            "models", "results", "mic",
        ) + modelDirs
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
        // Empty means every source; see BenchmarkRunner.Config.sources.
        val sources = argList("sources", "")
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

        // The continuous-inference cap can be lowered, never raised: 10
        // minutes is a safety policy for the phone (§11.3). Lowering it is how
        // the checkpoint written at each break gets exercised on the emulator
        // in minutes rather than an hour.
        val sessionCapMs = minOf(arg("session_cap_s", "600").toLong(), 600L) * 1000

        val arms = buildArms(armIds, buckets, threads)

        Log.i(TAG, "benchmark arms=$armIds langs=$langs buckets=$buckets " +
            "reps=$reps threads=$threads emulator=$onEmulator thermal=$enforceThermal")

        val outcome = BenchmarkRunner(
            context,
            BenchmarkRunner.Config(
                arms = arms,
                languages = langs,
                buckets = buckets,
                sources = sources,
                reps = reps,
                threads = threads,
                label = label,
                seed = seed,
                enforceThermal = enforceThermal,
                sessionCapMs = sessionCapMs,
            ),
        ).run()

        Log.i(TAG, "wrote ${outcome.rows} rows -> ${outcome.resultFile.absolutePath}")
        if (outcome.aborted != null) Log.e(TAG, "run aborted: ${outcome.aborted}")
        assertTrue("no rows produced", outcome.rows > 0)
    }

    /**
     * The single real-microphone check (PLAN.md §7, §10 step 20). Validation,
     * not measurement: nothing it produces enters the results table.
     *
     *   -e arm D:parakeet-tdt-v3-int8  -e lang en  -e seconds 60
     *
     * The owner reads the sentences shown on the phone while one arm
     * transcribes the microphone live. The recording is kept, and the same
     * samples then go through the same arm again, file-fed and paced exactly
     * as every measured run is. If the method is sound, the two agree: the
     * text, and the timing, of live input and of the simulation. Whatever
     * still differs from the corpus runs is the audio itself (a real voice,
     * room and microphone), which is the qualitative half of the check.
     *
     * The recording and both rows are written to files/mic/, not results/:
     * they are the owner's voice (§11.7), `run_bench.py --mic` pulls them into
     * the gitignored corpus/self-recorded/, and no later pull sweeps them into
     * the repo.
     */
    @Test
    fun micCheck() {
        val armSpec = arg("arm", "D:parakeet-tdt-v3-int8")
        val lang = arg("lang", "en")
        val seconds = arg("seconds", "60").toInt()
        val threads = arg("threads", "4").toInt()
        val label = arg("label", "mic")
        // Live, then the replay: twice this of continuous inference, which
        // must stay inside the 10-minute cap (§11.3).
        require(seconds in 10..MIC_MAX_S) { "seconds must be 10..$MIC_MAX_S" }

        val micDir = File(context.getExternalFilesDir(null), MIC_DIR).apply { mkdirs() }
        val prompt = File(micDir, "prompt-$lang.txt").takeIf { it.isFile }
            ?.readText()?.trim().orEmpty()
        check(prompt.isNotEmpty()) { "no script at ${micDir.absolutePath}/prompt-$lang.txt" }

        // The start gate the matrix runner applies before every clip (§11.3).
        if (!Telemetry.isEmulator()) {
            val bat = Telemetry.battery(context)
            check(bat.charging != true) { "device is charging" }
            check((bat.tempC ?: 0.0) < 35.0) { "battery ${bat.tempC} C, start gate is 35 C" }
        }

        val arm = buildArms(listOf(armSpec), listOf("mic"), threads).single()
        check(arm.supports(lang)) { "${arm.label} does not support $lang" }

        // Opened through the shell, not startActivity(): MIUI aborts an app
        // starting its own activity from the background ("MIUILOG-
        // Permission Denied Activity"), and while instrumented, this app is
        // in the background. The shell may start activities from anywhere.
        instr.uiAutomation.executeShellCommand(
            "am start -n ${context.packageName}/${MicCheckActivity::class.java.name} " +
                "--es ${MicCheckActivity.EXTRA_LANG} $lang"
        ).close()
        val shown = SystemClock.uptimeMillis() + SCREEN_WAIT_MS
        while (MicCheckActivity.current == null) {
            check(SystemClock.uptimeMillis() < shown) {
                "the mic check screen did not open within ${SCREEN_WAIT_MS / 1000}s"
            }
            Thread.sleep(200)
        }
        val screen = MicCheckActivity.current!!
        try {
            val deadline = SystemClock.uptimeMillis() + PERMISSION_WAIT_MS
            if (!micGranted()) mic("waiting for microphone permission: tap Allow on the phone")
            while (!micGranted()) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "RECORD_AUDIO not granted within ${PERMISSION_WAIT_MS / 1000}s"
                }
                Thread.sleep(500)
            }

            screen.setStatus("Loading the model…")
            val loadMs = arm.load(context)
            mic("loaded ${arm.label} in ${loadMs}ms")
            for (i in 5 downTo 1) {
                screen.setStatus("Start reading in $i…")
                mic("start reading in $i")
                Thread.sleep(1000)
            }

            val sr = Wav.REQUIRED_SAMPLE_RATE
            val audio = Wav.Audio(ShortArray(seconds * sr), sr)
            val feeder = MicFeeder(audio)
            screen.countdown("● Recording: read aloud", seconds)
            mic("RECORDING ${seconds}s: read now")
            val before = Telemetry.battery(context)
            val live = arm.transcribe(context, audio, lang, threads, feeder)
            val afterLive = Telemetry.battery(context)
            screen.setStatus("Stop. Thank you. Processing…")
            mic("recording done: level %.1f dBFS, error=%s".format(
                feeder.rmsDbfs ?: Double.NaN, live.error ?: "-"))

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())
            val clipId = "mic-$lang-$stamp"
            Wav.write(File(micDir, "$clipId.wav"), audio)

            // The same samples, through the same arm, fed as every measured
            // run is.
            mic("replaying the recording, file-fed")
            val replay = arm.transcribe(context, audio, lang, threads)
            val afterReplay = Telemetry.battery(context)

            val writer = ResultWriter(context, label, subdir = MIC_DIR)
            writer.note("purpose", "real-microphone check (PLAN.md §7): the same audio " +
                "transcribed live from the microphone (rep 0) and file-fed (rep 1)")
            writer.note("mic_source", feeder.source)
            writer.note("mic_rms_dbfs", feeder.rmsDbfs)
            writer.note("prompt", prompt)
            val clip = Manifest.Clip(clipId, "$clipId.wav", lang, "mic",
                audio.durationMs / 1000.0, "self_recorded")
            writer.add(arm, clip, 0, threads, live.withExtra("feed" to "mic"),
                before, afterLive, loadMs)
            writer.add(arm, clip, 1, threads, replay.withExtra("feed" to "file"),
                afterLive, afterReplay, loadMs)
            val out = writer.write()
            mic("wrote ${out.name} and $clipId.wav")
            screen.setStatus("Finished. You can put the phone down.")
            Thread.sleep(2000)
        } finally {
            screen.finish()
            arm.close()
        }
    }

    private fun micGranted(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun mic(msg: String) = Log.i(TAG, "MIC: $msg")

    /**
     * The arms named by [armIds], e.g. "A", "B:moonshine-small-en",
     * "D:parakeet-tdt-v3-int8". Shared by the matrix run and the microphone
     * check, so both build an arm the same way.
     */
    private fun buildArms(armIds: List<String>, buckets: List<String>, threads: Int): List<Arm> {
        // Arms C, D and E only: how often open speech is re-decoded for
        // partial text. 0 shows text only when the VAD closes a segment.
        val partialMs = arg("partial_ms",
            SherpaOfflineArm.DEFAULT_PARTIAL_INTERVAL_MS.toString()).toLong()

        // Arm C only: Moonshine option overrides for an ablation, e.g.
        // "max_tokens_per_second=13". Rows then carry a distinct variant.
        val moonshineOverrides = argList("moonshine_options", "").associate {
            val (k, v) = it.split('=', limit = 2).also { p ->
                require(p.size == 2) { "moonshine_options entry '$it' is not key=value" }
            }
            k.trim() to v.trim()
        }

        // Arm B is selected per variant: "B:moonshine-small-en", or plain
        // "B" for every variant that has been pushed to the device.
        return armIds.flatMap { spec ->
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
                // C (Moonshine base-es), D (Parakeet) and E (Whisper):
                // "E:whisper-base-int8", or plain "E" for every pushed
                // variant. One variant per run is the protocol: each one
                // loaded holds its weights in memory.
                "C", "D", "E" -> {
                    val known = SherpaOfflineArm.variants().filterValues { it.armId == id }
                    val wanted = if (detail.isNotEmpty()) {
                        listOf(detail)
                    } else {
                        known.keys.filter { v ->
                            File(context.getExternalFilesDir(null), "models/$v").isDirectory
                        }
                    }
                    check(wanted.isNotEmpty()) {
                        "no arm $id variants found under files/models/. " +
                            "Push them with scripts/run_bench.py --push-models"
                    }
                    wanted.map { v ->
                        val model = requireNotNull(known[v]) {
                            "unknown arm $id variant '$v'; known: ${known.keys}"
                        }
                        SherpaOfflineArm(v, model, threads, partialMs,
                            if (id == "C") moonshineOverrides else emptyMap())
                    }
                }
                else -> throw IllegalArgumentException(
                    "unknown arm $id. Implemented: A, B, C, D, E"
                )
            }
        }
    }

    private companion object {
        const val TAG = "AsrBench"

        /** Where the microphone check writes; see [micCheck]. */
        const val MIC_DIR = "mic"
        const val MIC_MAX_S = 180
        const val PERMISSION_WAIT_MS = 120_000L
        const val SCREEN_WAIT_MS = 20_000L
    }
}
