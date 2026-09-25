package io.github.davamix.asrbench.arms

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriberOption
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.davamix.asrbench.Arm
import io.github.davamix.asrbench.ArmResult
import io.github.davamix.asrbench.AudioFeeder
import io.github.davamix.asrbench.SessionTimeline
import io.github.davamix.asrbench.Telemetry
import io.github.davamix.asrbench.TranscriptTracker
import io.github.davamix.asrbench.Wav
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arms C, D and E -- an offline model made live with Silero VAD.
 *
 * None of Moonshine `base-es` (C), Parakeet (D) or Whisper (E) is
 * streaming-native. Each takes a whole utterance and returns its text. This
 * arm is the standard way to put such a model behind a live microphone, and
 * the way sherpa-onnx's own "simulate streaming" Android example does it:
 *
 *  - **Silero VAD cuts the paced audio into utterances.** A segment closes
 *    after [VAD_MIN_SILENCE_S] of silence, and that segment is decoded once
 *    more for its **final** text.
 *  - **While speech is still open, the audio so far is re-decoded every
 *    [partialIntervalMs]** and shown as a partial. The whole segment is
 *    decoded from its start each time, so partials rewrite text freely.
 *    That is the flicker `partial_instability` exists to count (PLAN.md D3).
 *
 * Two choices here change what the numbers mean, so they are stated:
 *
 *  - **Decoding runs on its own thread, not inside the feed.** Moonshine's SDK
 *    decodes inside `addAudio()` (README finding 7), and that is exactly what
 *    a live app must avoid, because it stalls the capture thread. Here the
 *    feed path does only the VAD, which is cheap, and hands segments to one
 *    worker thread. So schedule slip stays near zero and says little. The
 *    cost of a slow model shows up instead in final latency and in
 *    `rtf_sustained`, which here is VAD time plus decode time over audio time.
 *  - **Partials are skipped, not queued, while the worker is busy.** A model
 *    slower than the interval simply shows fewer partials, as a sensible app
 *    would. A final can still wait behind a partial that is already running,
 *    because a decode cannot be interrupted. Each row records that wait
 *    (`last_final_wait_ms`), so the cost of showing partials can be separated
 *    from the cost of the model.
 *
 * **Arm C decodes with the Moonshine SDK, not sherpa-onnx.** PLAN.md §5 names
 * the Moonshine SDK as Arm C's runtime, and sherpa-onnx's Moonshine loader
 * expects its own ONNX exports, not the `.ort` files `base-es` ships. So the
 * VAD, the partial cadence and the timing are shared with D and E, and only
 * the decode call differs: `Transcriber.transcribeWithoutStreaming()`. That
 * call runs Moonshine's own Silero VAD over whatever it is given before
 * decoding (core/transcriber.cpp, v0.1.5). Here it is given a segment this
 * VAD has already cut, so its VAD is switched off with `vad_threshold=0`,
 * which Moonshine documents as "disables VAD": every call then decodes
 * exactly the audio it is handed, as one line, like D and E do. Moonshine has
 * no thread setting (README finding 8), so for C the thread count is nominal.
 *
 * Models load from `files/models/<variant>` at the revisions pinned in
 * `models/MODELS.md`. Nothing downloads itself (D8).
 */
class SherpaOfflineArm(
    /** Model directory name under `files/models/`, e.g. "whisper-base-int8". */
    private val variant: String,
    private val model: Model,
    /**
     * ONNX Runtime intra-op threads. Held at 4 across the matrix (PLAN.md §6).
     * Unlike Moonshine's SDK, sherpa-onnx exposes this, so it is real.
     */
    private val threads: Int,
    /** 0 disables partials: text appears only when the VAD closes a segment. */
    private val partialIntervalMs: Long = DEFAULT_PARTIAL_INTERVAL_MS,
    /**
     * Arm C only: Moonshine options to set on top of [MOONSHINE_OPTIONS], for
     * ablations such as a higher `max_tokens_per_second`. A non-empty map
     * renames the variant, so an ablation never pools with the main run.
     */
    private val moonshineOverrides: Map<String, String> = emptyMap(),
) : Arm {

    /** What rows record as `variant`: the model, plus any ablation. */
    private val variantKey = if (moonshineOverrides.isEmpty()) variant
        else variant + moonshineOverrides.entries.joinToString("") { "+${it.key}=${it.value}" }

    private val moonshineOptions: List<TranscriberOption> =
        MOONSHINE_OPTIONS.filter { it.name !in moonshineOverrides } +
            moonshineOverrides.map { TranscriberOption(it.key, it.value) }

    /** What to load, and which arm of the matrix it belongs to. */
    sealed class Model(val armId: String, val sizeMb: Double, val modelType: String) {
        class Whisper(
            sizeMb: Double,
            val encoder: String,
            val decoder: String,
            val tokens: String,
        ) : Model("E", sizeMb, "whisper")

        class NemoTransducer(
            sizeMb: Double,
            val encoder: String,
            val decoder: String,
            val joiner: String,
            val tokens: String,
        ) : Model("D", sizeMb, "nemo_transducer")

        /**
         * A legacy non-streaming Moonshine model (`encoder_model.ort` +
         * `decoder_model_merged.ort`), decoded by the Moonshine SDK.
         *
         * [arch] sets the decoder's layer count and head sizes, so it must
         * match the model: `MOONSHINE_MODEL_ARCH_BASE` (1) for `base-es`.
         * Nothing checks it at load; with the tiny value (0) the files load
         * and the first decode fails its input-count check (v0.1.5 source).
         */
        class MoonshineNonStreaming(
            sizeMb: Double,
            val arch: Int,
            val languages: Set<String>,
        ) : Model("C", sizeMb, "moonshine_non_streaming")
    }

    override val id = model.armId
    override val label = variantKey
    override val runtime = when (model) {
        is Model.MoonshineNonStreaming ->
            "$MOONSHINE_RUNTIME + Silero VAD via sherpa-onnx $SHERPA_VERSION"
        else -> "sherpa-onnx $SHERPA_VERSION (ONNX Runtime, static) + Silero VAD"
    }
    override val diskSizeMb = model.sizeMb

    private var recognizer: OfflineRecognizer? = null
    private var config: OfflineRecognizerConfig? = null
    private var moonshine: Transcriber? = null
    private var vad: Vad? = null
    private var worker: ExecutorService? = null
    private var currentLanguage: String? = null
    private var rssAfterLoadMb: Double? = null
    private var peakRssAfterLoadMb: Double? = null

    /** Whisper (multilingual) and Parakeet v3 cover en and es; base-es only es. */
    override fun supports(language: String): Boolean = when (model) {
        is Model.MoonshineNonStreaming -> language in model.languages
        else -> language == "en" || language == "es"
    }

    override fun load(context: Context): Long {
        val root = File(context.getExternalFilesDir(null), "models")
        val dir = File(root, variant)
        check(dir.isDirectory) {
            "model dir missing: ${dir.absolutePath}. Push it with " +
                "scripts/run_bench.py --push-models $VAD_DIR,$variant"
        }
        val vadFile = File(root, "$VAD_DIR/$VAD_FILE")
        check(vadFile.isFile) {
            "VAD missing: ${vadFile.absolutePath}. Push it with " +
                "scripts/run_bench.py --push-models $VAD_DIR"
        }
        fun path(name: String): String {
            val f = File(dir, name)
            check(f.isFile) { "missing ${f.absolutePath}" }
            return f.absolutePath
        }

        val t0 = SystemClock.uptimeMillis()
        if (model is Model.MoonshineNonStreaming) {
            // The same files the loader requires, checked here so a missing
            // one names itself instead of surfacing as a native exception.
            listOf("encoder_model.ort", "decoder_model_merged.ort", "tokenizer.bin")
                .forEach { path(it) }
            val t = Transcriber(moonshineOptions)
            t.loadFromFiles(dir.absolutePath, model.arch)
            check(t.isLoaded) { "Transcriber.loadFromFiles reported not loaded for $variant" }
            moonshine = t
        } else {
            val cfg = sherpaConfig(model, ::path)
            // A null AssetManager makes the constructor load from file paths.
            recognizer = OfflineRecognizer(null, cfg)
            config = cfg
        }
        val v = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE_S,
                    minSpeechDuration = VAD_MIN_SPEECH_S,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = VAD_MAX_SPEECH_S,
                ),
                sampleRate = Wav.REQUIRED_SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        val ms = SystemClock.uptimeMillis() - t0

        vad = v
        currentLanguage = "en"
        worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sherpa-decode").apply { isDaemon = true }
        }
        rssAfterLoadMb = Telemetry.currentRssMb()
        peakRssAfterLoadMb = Telemetry.peakRssMb()
        Log.i(TAG, "loaded $variant (${model.modelType}, $threads threads) in ${ms}ms; " +
            "rss=${rssAfterLoadMb}MB peak=${peakRssAfterLoadMb}MB")
        return ms
    }

    /** Recognizer config for the sherpa-onnx models (D and E). */
    private fun sherpaConfig(model: Model, path: (String) -> String): OfflineRecognizerConfig {
        val modelConfig = when (model) {
            is Model.Whisper -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    // Set per clip in transcribe(); an app knows which
                    // language the user chose, as Arm A requires it to.
                    language = "en",
                    task = "transcribe",
                ),
                tokens = path(model.tokens),
                modelType = model.modelType,
                numThreads = threads,
                provider = "cpu",
            )
            is Model.NemoTransducer -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(model.encoder),
                    decoder = path(model.decoder),
                    joiner = path(model.joiner),
                ),
                tokens = path(model.tokens),
                modelType = model.modelType,
                numThreads = threads,
                provider = "cpu",
            )
            is Model.MoonshineNonStreaming -> error("$variant is not a sherpa-onnx model")
        }
        // The feature dimension here is a placeholder: both model families
        // read theirs from the model's own metadata (Parakeet v3 uses 128
        // mel bins, Whisper 80) and override it.
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Wav.REQUIRED_SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
    }

    override fun transcribe(
        context: Context,
        audio: Wav.Audio,
        language: String,
        threads: Int,
        feeder: AudioFeeder,
    ): ArmResult {
        if (recognizer == null && moonshine == null) {
            return ArmResult.failed("model not loaded", audio.durationMs)
        }
        val v = vad ?: return ArmResult.failed("vad not loaded", audio.durationMs)
        val exec = worker ?: return ArmResult.failed("worker not started", audio.durationMs)

        return try {
            // Barrier: a decode left running by a clip that timed out must not
            // overlap this clip's setConfig or its measurements.
            exec.submit(Runnable {}).get(BARRIER_TIMEOUT_S, TimeUnit.SECONDS)

            if (model is Model.Whisper && language != currentLanguage) {
                val cfg = config!!
                cfg.modelConfig.whisper.language = language
                recognizer!!.setConfig(cfg)
                currentLanguage = language
            }
            v.reset()
            v.clear()

            runClip(v, exec, audio, feeder, SessionTimeline.forAudio(context, audio))
        } catch (e: Throwable) {
            ArmResult.failed("${e.javaClass.simpleName}: ${e.message}", audio.durationMs)
        }
    }

    /** One decode's output. [lines] is how many lines Moonshine returned. */
    private class Decoded(val text: String, val lang: String, val lines: Int)

    /** Decode one whole utterance. Worker thread only. */
    private fun decode(samples: FloatArray, sampleRate: Int): Decoded {
        moonshine?.let { t ->
            val transcript = t.transcribeWithoutStreaming(samples, sampleRate)
                ?: error("transcribeWithoutStreaming returned no transcript")
            // One line is expected, since Moonshine's own VAD is off; the
            // count is recorded so that stays checked rather than assumed.
            val lines = transcript.lines.orEmpty()
            val text = lines.joinToString(" ") { it.text.orEmpty().trim() }.trim()
            return Decoded(text, "", lines.size)
        }
        val rec = checkNotNull(recognizer) { "recognizer not loaded" }
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, sampleRate)
            rec.decode(s)
            val r = rec.getResult(s)
            return Decoded(r.text.trim(), r.lang, 1)
        } finally {
            s.release()
        }
    }

    private fun runClip(
        v: Vad,
        exec: ExecutorService,
        audio: Wav.Audio,
        feeder: AudioFeeder,
        /** Non-null for a session-length clip; see [SessionTimeline]. */
        timeline: SessionTimeline?,
    ): ArmResult {
        val st = State()
        val tracker = TranscriptTracker()
        // Written only on the worker thread.
        val finals = ArrayList<String>()
        val pending = ArrayList<Future<*>>()
        val inFlight = AtomicInteger(0)

        // Everything fed so far, and nothing beyond it. Filled frame by frame,
        // so a partial can never see audio the feeder has not yet released.
        val heard = FloatArray(audio.samples.size)
        var fed = 0
        var segIndex = 0
        var openAt = -1
        var lastSegEnd = 0
        var lastPartialSubmit = 0L

        fun decode(samples: FloatArray): Decoded =
            decode(samples, audio.sampleRate).also {
                st.maxLinesPerDecode = maxOf(st.maxLinesPerDecode, it.lines)
            }

        // Worker thread only.
        fun show(partial: String?) {
            val text = (finals + listOfNotNull(partial?.takeIf { it.isNotBlank() }))
                .joinToString(" ").trim()
            if (text == tracker.text) return
            val now = SystemClock.uptimeMillis()
            if (st.firstTextMs == null && text.isNotBlank()) st.firstTextMs = now
            tracker.update(text)
            st.lastTextMs = now
        }

        fun submitFinal(seg: SpeechSegment, index: Int) {
            val submitted = SystemClock.uptimeMillis()
            inFlight.incrementAndGet()
            pending += exec.submit(Runnable {
                try {
                    val t0 = SystemClock.uptimeMillis()
                    val d = decode(seg.samples)
                    val text = d.text
                    val lang = d.lang
                    val t1 = SystemClock.uptimeMillis()
                    timeline?.addDecode(t1 - t0)
                    st.finalDecodes++
                    st.finalDecodeMs += t1 - t0
                    st.maxFinalDecodeMs = maxOf(st.maxFinalDecodeMs, t1 - t0)
                    st.lastFinalDecodeMs = t1 - t0
                    st.lastFinalWaitMs = t0 - submitted
                    if (lang.isNotBlank()) st.lang = lang
                    if (text.isNotBlank()) finals += text
                    st.finalized = index + 1
                    show(null)
                    val committed = SystemClock.uptimeMillis()
                    st.finalCommitMs = committed
                    timeline?.event(JSONObject().apply {
                        val sr = audio.sampleRate
                        put("seg", index)
                        put("start_ms", seg.start * 1000L / sr)
                        put("end_ms", (seg.start + seg.samples.size) * 1000L / sr)
                        put("closed_ms", submitted - st.feedStartMs)
                        put("decode_start_ms", t0 - st.feedStartMs)
                        put("commit_ms", committed - st.feedStartMs)
                        put("text", text)
                    })
                } catch (e: Throwable) {
                    st.error = "final decode: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    inFlight.decrementAndGet()
                }
            })
        }

        fun submitPartial(samples: FloatArray, index: Int) {
            inFlight.incrementAndGet()
            pending += exec.submit(Runnable {
                try {
                    // Stale: this segment was finalized while the job waited.
                    if (st.finalized > index) return@Runnable
                    val t0 = SystemClock.uptimeMillis()
                    val text = decode(samples).text
                    val ms = SystemClock.uptimeMillis() - t0
                    timeline?.addDecode(ms)
                    st.partialDecodes++
                    st.partialDecodeMs += ms
                    if (st.finalized > index) return@Runnable
                    show(text)
                } catch (e: Throwable) {
                    st.error = "partial decode: ${e.javaClass.simpleName}: ${e.message}"
                } finally {
                    inFlight.decrementAndGet()
                }
            })
        }

        fun drainSegments(flushed: Boolean) {
            while (!v.empty()) {
                val seg = v.front()
                v.pop()
                submitFinal(seg, segIndex++)
                lastSegEnd = seg.start + seg.samples.size
                st.lastSegmentClosedMs = SystemClock.uptimeMillis()
                if (flushed) st.flushedSegments++
                openAt = -1
            }
        }

        val stats = feeder.feed(
            sink = { pcm, _ ->
                val f = FloatArray(pcm.size) { pcm[it] / 32768f }
                f.copyInto(heard, fed)
                fed += f.size
                v.acceptWaveform(f)
                drainSegments(flushed = false)

                val speaking = v.isSpeechDetected()
                if (speaking && openAt < 0) {
                    // The VAD dates a segment's start this far before the
                    // point where it declares speech; partials use the same
                    // start, so they and the final see the same audio.
                    openAt = maxOf(lastSegEnd, fed - VAD_LOOKBACK_SAMPLES)
                    lastPartialSubmit = SystemClock.uptimeMillis()
                    st.speechOnsets++
                } else if (!speaking) {
                    openAt = -1
                }

                val now = SystemClock.uptimeMillis()
                if (partialIntervalMs > 0 && openAt >= 0 && inFlight.get() == 0 &&
                    now - lastPartialSubmit >= partialIntervalMs
                ) {
                    submitPartial(heard.copyOfRange(openAt, fed), segIndex)
                    lastPartialSubmit = now
                }
            },
            onStart = { st.feedStartMs = SystemClock.uptimeMillis() },
            shouldStop = { st.error != null || timeline?.abortReason != null },
            onProgress = timeline?.progress,
        )
        val speechEnd = SystemClock.uptimeMillis()

        // The audio has ended: whatever speech the VAD still holds open is an
        // utterance now. Every arm gets this flush at end of audio -- Arm A
        // sees EOF, Moonshine gets stopStream() -- so none is kept waiting
        // for silence that a file cannot supply.
        v.flush()
        drainSegments(flushed = true)

        val deadline = speechEnd + DRAIN_TIMEOUT_MS + audio.durationMs * 5
        var timedOut = false
        for (f in pending) {
            val left = deadline - SystemClock.uptimeMillis()
            try {
                f.get(maxOf(1L, left), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                timedOut = true
                break
            }
        }
        val drainEnd = SystemClock.uptimeMillis()
        timeline?.finish()

        // When the text became final: the last final decode's commit. Moonshine
        // is measured the same way, from its last completed line.
        val finalAt = st.finalCommitMs ?: st.lastTextMs ?: drainEnd
        val audioEnd = st.feedStartMs + audio.durationMs
        val decodeMs = st.partialDecodeMs + st.finalDecodeMs

        return ArmResult(
            hypothesis = finals.joinToString(" ").trim(),
            latencyFirstPartialMs = st.firstTextMs?.let { it - st.feedStartMs },
            latencyFinalMs = maxOf(0L, finalAt - speechEnd),
            partialInstability = tracker.revisions,
            partialUpdates = tracker.updates,
            // Compute on both threads over audio time. Partials are included:
            // they are real work the phone does, and real heat.
            rtfSustained = (stats.sinkBusyMs + decodeMs).toDouble() / audio.durationMs,
            maxSlipMs = stats.maxSlipMs,
            audioDurationMs = audio.durationMs,
            peakRssMb = Telemetry.peakRssMb(),
            error = st.error ?: timeline?.abortReason
                ?: if (timedOut) "timed out waiting for decodes after ${drainEnd - speechEnd}ms" else null,
            extra = mapOf(
                "variant" to variantKey,
                "model_type" to model.modelType,
                // Null for Arm C: the Moonshine SDK has no thread setting, so
                // ONNX Runtime's default pool applies (README finding 8).
                "num_threads" to this.threads.takeIf { moonshine == null },
                "partial_interval_ms" to partialIntervalMs,
                "vad" to "silero thr=$VAD_THRESHOLD silence=${VAD_MIN_SILENCE_S}s " +
                    "speech=${VAD_MIN_SPEECH_S}s max=${VAD_MAX_SPEECH_S}s win=$VAD_WINDOW",
                "segments" to segIndex,
                "flushed_segments" to st.flushedSegments,
                "speech_onsets" to st.speechOnsets,
                "partial_decodes" to st.partialDecodes,
                "partial_decode_ms" to st.partialDecodeMs,
                "final_decodes" to st.finalDecodes,
                "final_decode_ms" to st.finalDecodeMs,
                "max_final_decode_ms" to st.maxFinalDecodeMs,
                "last_final_decode_ms" to st.lastFinalDecodeMs,
                // How long the last final queued behind a partial already
                // running. Zero with partials off.
                "last_final_wait_ms" to st.lastFinalWaitMs,
                "sink_busy_ms" to stats.sinkBusyMs,
                // The part of rtf_sustained that is not optional: without
                // partials, this is all the compute an utterance costs.
                "rtf_finals_only" to
                    (stats.sinkBusyMs + st.finalDecodeMs).toDouble() / audio.durationMs,
                "suspended_ms" to stats.suspendedMs,
                "drain_ms" to (drainEnd - speechEnd),
                "final_slip_ms" to stats.finalSlipMs,
                "speech_end_lag_ms" to (speechEnd - audioEnd),
                // Signed: negative means the text was final before the audio
                // ended. The unbiased counterpart of latency_final_ms.
                "final_after_audio_end_ms" to (finalAt - audioEnd),
                // When the VAD closed the last segment, against the audio end.
                // Negative: closed on trailing silence. Positive: flushed.
                "last_segment_closed_after_audio_end_ms" to
                    st.lastSegmentClosedMs?.let { it - audioEnd },
                "detected_lang" to st.lang,
                "rss_mb" to Telemetry.currentRssMb(),
                "rss_after_load_mb" to rssAfterLoadMb,
                "peak_rss_after_load_mb" to peakRssAfterLoadMb,
            ) + (if (timeline != null) mapOf(
                "timeline" to timeline.windowsJson(),
                "final_events" to timeline.eventsJson(),
            ) else emptyMap()) + if (moonshine != null) mapOf(
                "moonshine_options" to
                    moonshineOptions.joinToString(",") { "${it.name}=${it.value}" },
                // Must be 1: more would mean Moonshine re-segmented the audio.
                "max_lines_per_decode" to st.maxLinesPerDecode,
            ) else emptyMap(),
        )
    }

    override fun close() {
        worker?.let {
            it.shutdown()
            runCatching { it.awaitTermination(BARRIER_TIMEOUT_S, TimeUnit.SECONDS) }
        }
        worker = null
        runCatching { vad?.release() }
        vad = null
        runCatching { recognizer?.release() }
        recognizer = null
        runCatching { moonshine?.close() }
        moonshine = null
    }

    /** Shared between the feed thread and the worker. */
    private class State {
        @Volatile var feedStartMs = 0L
        @Volatile var firstTextMs: Long? = null
        @Volatile var lastTextMs: Long? = null
        @Volatile var finalCommitMs: Long? = null
        @Volatile var lastSegmentClosedMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var finalized = 0
        @Volatile var flushedSegments = 0
        @Volatile var speechOnsets = 0
        @Volatile var partialDecodes = 0
        @Volatile var partialDecodeMs = 0L
        @Volatile var finalDecodes = 0
        @Volatile var finalDecodeMs = 0L
        @Volatile var maxFinalDecodeMs = 0L
        @Volatile var lastFinalDecodeMs: Long? = null
        @Volatile var lastFinalWaitMs: Long? = null
        @Volatile var lang: String? = null
        @Volatile var maxLinesPerDecode = 0
    }

    companion object {
        private const val TAG = "AsrBench"

        /** Must match scripts/fetch_runtime.py. */
        const val SHERPA_VERSION = "1.13.8"

        /** Arm C's decoder; the same SDK as Arm B (see MoonshineArm). */
        const val MOONSHINE_RUNTIME = "ai.moonshine:moonshine-voice:0.1.5 (ONNX Runtime)"

        /**
         * Arm C's Moonshine options. Everything else is the SDK default,
         * including `max_tokens_per_second` 6.5, which Moonshine says needs
         * raising only for non-Latin scripts.
         *
         *  - `vad_threshold=0`: Moonshine's own VAD off, so each call decodes
         *    exactly the segment it is given (see the class comment).
         *  - `return_audio_data=false`: do not copy every segment's audio back
         *    into Java. It is never read, and a partial would copy it twice
         *    a second.
         */
        val MOONSHINE_OPTIONS = listOf(
            TranscriberOption("vad_threshold", "0"),
            TranscriberOption("return_audio_data", "false"),
        )

        /**
         * 500 ms, the Moonshine SDK's default update interval, so the two
         * stacks refresh partial text on the same cadence. sherpa-onnx's own
         * example uses 200 ms.
         */
        const val DEFAULT_PARTIAL_INTERVAL_MS = 500L

        const val VAD_DIR = "silero-vad"
        const val VAD_FILE = "silero_vad.onnx"

        // sherpa-onnx defaults, except the maximum segment length.
        const val VAD_THRESHOLD = 0.5f
        const val VAD_MIN_SILENCE_S = 0.25f
        const val VAD_MIN_SPEECH_S = 0.25f
        const val VAD_WINDOW = 512

        /**
         * Not a hard cut: once open speech passes this length, the native VAD
         * raises its threshold to 0.9 and drops the minimum silence to 0.1 s,
         * so the segment ends at the next brief pause. The Kotlin API
         * defaults this to 5 s, which would split the corpus's 7-8 s
         * sentences at a breath and hand the model two halves. 20 s is the
         * native library's own default, and Whisper's window is 30 s.
         */
        const val VAD_MAX_SPEECH_S = 20f

        /**
         * How far before its detection point the VAD dates a segment's start:
         * two windows plus the minimum speech duration, as in sherpa-onnx's
         * VoiceActivityDetector.
         */
        private val VAD_LOOKBACK_SAMPLES =
            2 * VAD_WINDOW + (VAD_MIN_SPEECH_S * Wav.REQUIRED_SAMPLE_RATE).toInt()

        private const val DRAIN_TIMEOUT_MS = 60_000L
        private const val BARRIER_TIMEOUT_S = 300L

        /** Sizes match `models/MODELS.md` at the pinned revisions. */
        fun variants(): Map<String, Model> = mapOf(
            "whisper-base-int8" to Model.Whisper(
                160.6, "base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt",
            ),
            "whisper-small-int8" to Model.Whisper(
                375.4, "small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt",
            ),
            "parakeet-tdt-v3-int8" to Model.NemoTransducer(
                670.5, "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt",
            ),
            // Non-commercial licence: experiment only (models/MODELS.md).
            "moonshine-base-es" to Model.MoonshineNonStreaming(
                64.8, JNI.MOONSHINE_MODEL_ARCH_BASE, setOf("es"),
            ),
        )
    }
}
