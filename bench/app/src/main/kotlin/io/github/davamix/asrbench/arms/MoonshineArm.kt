package io.github.davamix.asrbench.arms

import android.content.Context
import android.os.SystemClock
import android.util.Log
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import io.github.davamix.asrbench.Arm
import io.github.davamix.asrbench.ArmResult
import io.github.davamix.asrbench.PacedFeeder
import io.github.davamix.asrbench.Telemetry
import io.github.davamix.asrbench.TranscriptTracker
import io.github.davamix.asrbench.Wav
import java.io.File
import java.util.function.Consumer

/**
 * Arm B -- Moonshine streaming, English. The streaming-native contender.
 *
 * Unlike Arm A this is a real streaming model running in *our* process, which
 * means two things the platform recognizer could not give us:
 *
 *  - `rtf_sustained` is finally measurable. Time spent inside `addAudio()` is
 *    time this model consumed, so the fraction of real time it needs is a
 *    fact rather than a guess (contrast Arm A, where it is left null by
 *    design -- PLAN.md D6).
 *  - `peak_rss_mb` means something, because the weights are in our heap.
 *
 * **Models are loaded from disk, not downloaded.** The SDK ships its own
 * downloader (okhttp + WorkManager + `ModelCache`), and using it would mean a
 * silent upstream change could alter what was measured. `loadFromFiles()`
 * takes a directory, so the harness pushes the exact `.ort` files pinned in
 * `models/MODELS.md` and points at those (D8).
 */
class MoonshineArm(
    /** Variant directory name under `files/models/`, e.g. "moonshine-tiny-en". */
    private val variant: String,
    override val diskSizeMb: Double,
    /**
     * Model architecture id passed to `loadFromFiles`.
     *
     * The second parameter of `loadFromFiles(path, int)` is **not** a flags
     * bitfield despite sitting next to `setTranscribeFlags`; it is the model
     * architecture. Passing 0 selects the non-streaming layout, which looks
     * for `encoder_model.ort` + `decoder_model_merged.ort` -- the files the
     * legacy `base-*` models ship -- and fails on a streaming variant with
     * "Required encoder model file does not exist".
     *
     * 5 is what `MicTranscriber` (the SDK's own streaming path) defaults to,
     * which is how this was established: the value is not in any public
     * constant, so it was read out of that class's bytecode.
     */
    private val arch: Int = STREAMING_ARCH,
) : Arm {

    override val id = "B"
    override val label = "Moonshine ${variant.removePrefix("moonshine-")}"
    override val runtime = "ai.moonshine:moonshine-voice:0.1.5 (ONNX Runtime)"

    private var transcriber: Transcriber? = null
    private var modelDir: File? = null

    /** Moonshine streaming `.ort` assets exist for English only (§4.1). */
    override fun supports(language: String): Boolean = language == "en"

    override fun load(context: Context): Long {
        val dir = File(context.getExternalFilesDir(null), "models/$variant")
        check(dir.isDirectory) {
            "model dir missing: ${dir.absolutePath}. Push it with " +
                "scripts/run_bench.py --push-models"
        }
        modelDir = dir

        val t0 = SystemClock.uptimeMillis()
        val t = Transcriber()
        t.loadFromFiles(dir.absolutePath, arch)
        check(t.isLoaded) { "Transcriber.loadFromFiles reported not loaded for $variant" }
        transcriber = t
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(TAG, "loaded $variant from ${dir.absolutePath} in ${ms}ms")
        return ms
    }

    override fun transcribe(
        context: Context,
        audio: Wav.Audio,
        language: String,
        threads: Int,
    ): ArmResult {
        val t = transcriber
            ?: return ArmResult.failed("transcriber not loaded", audio.durationMs)

        val tracker = TranscriptTracker()
        val state = State()
        val lines = LinkedHashMap<Long, String>()

        val listener = object : TranscriptEventListener() {
            override fun onLineStarted(e: TranscriptEvent.LineStarted) {
                state.lineStarts++
            }

            override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
                // The hook the partial-instability metric exists for (§5).
                val line = e.line ?: return
                if (state.firstPartialMs == null && !line.text.isNullOrBlank()) {
                    state.firstPartialMs = SystemClock.uptimeMillis()
                }
                lines[line.id] = line.text.orEmpty()
                tracker.update(lines.values.joinToString(" ").trim())
                state.lastTextMs = SystemClock.uptimeMillis()
                if (line.lastTranscriptionLatencyMs > 0) {
                    state.sdkLatencySum += line.lastTranscriptionLatencyMs
                    state.sdkLatencyCount++
                }
            }

            override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
                val line = e.line ?: return
                lines[line.id] = line.text.orEmpty()
                tracker.update(lines.values.joinToString(" ").trim())
                state.finalMs = SystemClock.uptimeMillis()
                state.lastTextMs = state.finalMs
                state.linesCompleted++
            }

            override fun onError(e: TranscriptEvent.Error) {
                state.error = e.toString()
            }
        }

        val consumer = Consumer<TranscriptEvent> { ev -> ev.accept(listener) }
        t.addListener(consumer)

        return try {
            val stream = t.createStream()
            t.startStream(stream)

            // Moonshine takes float PCM in [-1, 1]; the corpus is PCM16.
            val stats = PacedFeeder(audio).feed(
                sink = { pcm, _ ->
                    t.addAudioToStream(stream, pcm.toFloatPcm(), audio.sampleRate)
                },
                onStart = { state.feedStartMs = SystemClock.uptimeMillis() },
                shouldStop = { state.error != null },
            )
            val speechEnd = SystemClock.uptimeMillis()

            // Flush: stopStream drains whatever the model still holds.
            t.stopStream(stream)
            val drainEnd = SystemClock.uptimeMillis()
            if (state.finalMs == null) state.finalMs = drainEnd
            t.freeStream(stream)

            // A streaming model can finish its last line *before* the audio
            // ends -- trailing silence gives it time to catch up -- which made
            // a naive (finalMs - speechEnd) come back as -350 ms. Negative
            // latency is not a thing; it means the text was already on screen
            // when the speaker stopped. Report 0 and keep the lead time
            // separately, since "finished early" is a real property worth
            // seeing rather than an artefact to clamp away.
            val lastText = state.lastTextMs ?: state.finalMs
            val finalLatency = lastText?.let { maxOf(0L, it - speechEnd) }
            val leadMs = lastText?.let { speechEnd - it }?.takeIf { it > 0 }

            ArmResult(
                hypothesis = tracker.text,
                latencyFirstPartialMs = state.firstPartialMs?.let { it - state.feedStartMs },
                latencyFinalMs = finalLatency,
                partialInstability = tracker.revisions,
                partialUpdates = tracker.updates,
                // Real here, unlike Arm A: this model ran in our process, so
                // time inside the sink is time it actually consumed.
                rtfSustained = stats.rtfSustained,
                maxSlipMs = stats.maxSlipMs,
                audioDurationMs = audio.durationMs,
                peakRssMb = Telemetry.peakRssMb(),
                error = state.error,
                extra = mapOf(
                    "variant" to variant,
                    "arch" to arch,
                    "lines_started" to state.lineStarts,
                    "lines_completed" to state.linesCompleted,
                    "sink_busy_ms" to stats.sinkBusyMs,
                    "suspended_ms" to (stats.suspendedMs),
                    "drain_ms" to (drainEnd - speechEnd),
                    "finished_early_ms" to leadMs,
                    // The SDK's own latency figure, kept as an independent
                    // cross-check on our wall-clock measurement.
                    "sdk_mean_latency_ms" to
                        if (state.sdkLatencyCount > 0)
                            state.sdkLatencySum / state.sdkLatencyCount else null,
                ),
            )
        } catch (e: Throwable) {
            ArmResult.failed("${e.javaClass.simpleName}: ${e.message}", audio.durationMs)
        } finally {
            t.removeListener(consumer)
        }
    }

    override fun close() {
        runCatching { transcriber?.close() }
        transcriber = null
    }

    /** PCM16 -> float in [-1, 1]. */
    private fun ShortArray.toFloatPcm(): FloatArray =
        FloatArray(size) { this[it] / 32768f }

    private class State {
        @Volatile var feedStartMs = 0L
        @Volatile var firstPartialMs: Long? = null
        @Volatile var finalMs: Long? = null
        @Volatile var lastTextMs: Long? = null
        @Volatile var error: String? = null
        @Volatile var lineStarts = 0
        @Volatile var linesCompleted = 0
        @Volatile var sdkLatencySum = 0L
        @Volatile var sdkLatencyCount = 0
    }

    companion object {
        private const val TAG = "AsrBench"

        /** Streaming architecture; see the [arch] constructor parameter. */
        const val STREAMING_ARCH = 5

        /** Sizes match `models/MODELS.md` at the pinned revision. */
        fun variants(): Map<String, Double> = mapOf(
            "moonshine-tiny-en" to 77.7,
            "moonshine-small-en" to 224.1,
            "moonshine-medium-en" to 416.0,
        )
    }
}
