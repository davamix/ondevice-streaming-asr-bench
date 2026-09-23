package io.github.davamix.asrbench.arms

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import io.github.davamix.asrbench.PacedFeeder
import io.github.davamix.asrbench.Telemetry
import io.github.davamix.asrbench.TranscriptTracker
import io.github.davamix.asrbench.Wav
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arms D and E -- an offline model made live with Silero VAD, via sherpa-onnx.
 *
 * Neither Parakeet (D) nor Whisper (E) is streaming-native. Both take a whole
 * utterance and return its text. This arm is the standard way to put such a
 * model behind a live microphone, and the way sherpa-onnx's own
 * "simulate streaming" Android example does it:
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
) : Arm {

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
    }

    override val id = model.armId
    override val label = variant
    override val runtime = "sherpa-onnx $SHERPA_VERSION (ONNX Runtime, static) + Silero VAD"
    override val diskSizeMb = model.sizeMb

    private var recognizer: OfflineRecognizer? = null
    private var config: OfflineRecognizerConfig? = null
    private var vad: Vad? = null
    private var worker: ExecutorService? = null
    private var currentLanguage: String? = null
    private var rssAfterLoadMb: Double? = null
    private var peakRssAfterLoadMb: Double? = null

    /** Whisper (multilingual) and Parakeet v3 both cover en and es. */
    override fun supports(language: String): Boolean = language == "en" || language == "es"

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
        }
        // The feature dimension here is a placeholder: both model families
        // read theirs from the model's own metadata (Parakeet v3 uses 128
        // mel bins, Whisper 80) and override it.
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Wav.REQUIRED_SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )

        val t0 = SystemClock.uptimeMillis()
        // A null AssetManager makes both constructors load from file paths.
        val rec = OfflineRecognizer(null, cfg)
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

        recognizer = rec
        config = cfg
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

    override fun transcribe(
        context: Context,
        audio: Wav.Audio,
        language: String,
        threads: Int,
    ): ArmResult {
        val rec = recognizer
            ?: return ArmResult.failed("recognizer not loaded", audio.durationMs)
        val v = vad ?: return ArmResult.failed("vad not loaded", audio.durationMs)
        val exec = worker ?: return ArmResult.failed("worker not started", audio.durationMs)

        return try {
            // Barrier: a decode left running by a clip that timed out must not
            // overlap this clip's setConfig or its measurements.
            exec.submit(Runnable {}).get(BARRIER_TIMEOUT_S, TimeUnit.SECONDS)

            if (model is Model.Whisper && language != currentLanguage) {
                val cfg = config!!
                cfg.modelConfig.whisper.language = language
                rec.setConfig(cfg)
                currentLanguage = language
            }
            v.reset()
            v.clear()

            runClip(rec, v, exec, audio)
        } catch (e: Throwable) {
            ArmResult.failed("${e.javaClass.simpleName}: ${e.message}", audio.durationMs)
        }
    }

    private fun runClip(
        rec: OfflineRecognizer,
        v: Vad,
        exec: ExecutorService,
        audio: Wav.Audio,
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

        fun decode(samples: FloatArray): Pair<String, String> {
            val s = rec.createStream()
            try {
                s.acceptWaveform(samples, audio.sampleRate)
                rec.decode(s)
                val r = rec.getResult(s)
                return r.text.trim() to r.lang
            } finally {
                s.release()
            }
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
                    val (text, lang) = decode(seg.samples)
                    val t1 = SystemClock.uptimeMillis()
                    st.finalDecodes++
                    st.finalDecodeMs += t1 - t0
                    st.maxFinalDecodeMs = maxOf(st.maxFinalDecodeMs, t1 - t0)
                    st.lastFinalDecodeMs = t1 - t0
                    st.lastFinalWaitMs = t0 - submitted
                    if (lang.isNotBlank()) st.lang = lang
                    if (text.isNotBlank()) finals += text
                    st.finalized = index + 1
                    show(null)
                    st.finalCommitMs = SystemClock.uptimeMillis()
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
                    val (text, _) = decode(samples)
                    st.partialDecodes++
                    st.partialDecodeMs += SystemClock.uptimeMillis() - t0
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

        val stats = PacedFeeder(audio).feed(
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
            shouldStop = { st.error != null },
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
            error = st.error
                ?: if (timedOut) "timed out waiting for decodes after ${drainEnd - speechEnd}ms" else null,
            extra = mapOf(
                "variant" to variant,
                "model_type" to model.modelType,
                "num_threads" to this.threads,
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
            ),
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
    }

    companion object {
        private const val TAG = "AsrBench"

        /** Must match scripts/fetch_runtime.py. */
        const val SHERPA_VERSION = "1.13.8"

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
         * The Kotlin API defaults this to 5 s, which would cut the corpus's
         * 7-8 s sentences mid-word and hand the model two fragments. 20 s is
         * the native library's own default, and Whisper's window is 30 s.
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
        )
    }
}
