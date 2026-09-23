package io.github.davamix.asrbench

import android.content.Context

/**
 * One deployable stack: a model plus the runtime that runs it.
 *
 * The unit of comparison is deliberately the *stack*, not the model. Runtime
 * cannot be held constant across this matrix -- Moonshine ships its own SDK,
 * Android's recognizer is a black box, sherpa-onnx covers the rest -- so
 * pretending to isolate the model would be a fiction (PLAN.md D6). Each arm
 * records its own runtime, and differences are never attributed to the model
 * alone.
 */
interface Arm : AutoCloseable {

    /** Matrix letter: "A".."E". */
    val id: String

    /** Human-readable stack description, e.g. "moonshine-tiny-en / ai.moonshine:moonshine-voice". */
    val label: String

    /** Runtime, recorded per arm so D6 stays honest in the results. */
    val runtime: String

    /** Total on-disk size of the weights this arm loads, 0 for the platform arm. */
    val diskSizeMb: Double

    /** True if this arm can run the given BCP-47 language on this device. */
    fun supports(language: String): Boolean

    /**
     * Load the model. Called once per arm, outside any measurement window;
     * the returned duration is reported as `model_load_ms` (cold start).
     */
    fun load(context: Context): Long

    /**
     * Transcribe one clip, pacing the audio to the wall clock.
     *
     * Implementations feed [audio] through a [PacedFeeder] and report the
     * timing of what came back. They must not pre-read the whole clip.
     */
    fun transcribe(
        context: Context,
        audio: Wav.Audio,
        language: String,
        threads: Int,
    ): ArmResult

    override fun close() {}
}

/**
 * What one clip through one arm produced.
 *
 * Timestamps are uptimeMillis-based and only meaningful relative to each
 * other within a single run.
 */
class ArmResult(
    /** Final hypothesis text. Scored on the PC, never on the device. */
    val hypothesis: String,

    /** Speech start -> first text on screen. */
    val latencyFirstPartialMs: Long?,

    /** End of speech -> final text. The user-facing number. */
    val latencyFinalMs: Long?,

    /** Previously displayed words that were later rewritten. */
    val partialInstability: Int,

    /** Updates to the hypothesis during the clip. */
    val partialUpdates: Int,

    /** Fraction of real time the model consumed. Null where unmeasurable. */
    val rtfSustained: Double?,

    /** Worst lag behind the pacing schedule. */
    val maxSlipMs: Long,

    /** Audio duration as fed. */
    val audioDurationMs: Long,

    /** Peak RSS of this process after the clip. */
    val peakRssMb: Double?,

    /** Non-null if the arm failed. A failure is a result, not an omission. */
    val error: String? = null,

    /** Anything arm-specific worth keeping. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun failed(reason: String, audioDurationMs: Long = 0): ArmResult = ArmResult(
            hypothesis = "",
            latencyFirstPartialMs = null,
            latencyFinalMs = null,
            partialInstability = 0,
            partialUpdates = 0,
            rtfSustained = null,
            maxSlipMs = 0,
            audioDurationMs = audioDurationMs,
            peakRssMb = null,
            error = reason,
        )
    }
}
