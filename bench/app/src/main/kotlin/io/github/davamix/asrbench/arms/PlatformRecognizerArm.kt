package io.github.davamix.asrbench.arms

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.davamix.asrbench.Arm
import io.github.davamix.asrbench.ArmResult
import io.github.davamix.asrbench.PacedFeeder
import io.github.davamix.asrbench.TranscriptTracker
import io.github.davamix.asrbench.Wav
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Arm A -- the platform's own on-device recognizer. Zero megabytes bundled.
 *
 * This is the bar every other arm has to beat. If Google's recognizer is good
 * enough on the target audio, the size question answers itself and shipping a
 * model is unjustified. That is a legitimate, money-saving result, which is
 * why this arm is built first.
 *
 * **How audio gets in without a microphone.** API 33 added
 * [RecognizerIntent.EXTRA_AUDIO_SOURCE]: hand the recognizer a
 * [ParcelFileDescriptor] and it reads from that instead of the mic. So a pipe
 * is created, the read end goes to the recognizer, and the paced feeder writes
 * 100 ms frames into the write end on the wall clock. The recognizer cannot
 * tell the difference, the input is identical across arms and repetitions, and
 * the app never requests RECORD_AUDIO (PLAN.md §11.4, D5).
 *
 * Closing the write end is what signals end-of-audio.
 */
class PlatformRecognizerArm(
    /**
     * Segmented-session mode. The recognizer normally endpoints and stops at
     * the first pause, which is correct for a 5 s utterance and useless for a
     * 6 minute session. API 33's EXTRA_SEGMENTED_SESSION keeps the session
     * open for the whole audio source and delivers per-segment results.
     */
    private val segmented: Boolean = false,
) : Arm {

    override val id = "A"
    override val label = "Android on-device SpeechRecognizer" +
        if (segmented) " (segmented)" else ""
    override val runtime = "platform (android.speech)"
    override val diskSizeMb = 0.0

    private var available: Boolean? = null

    override fun supports(language: String): Boolean = available != false

    override fun load(context: Context): Long {
        val t0 = SystemClock.uptimeMillis()
        available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return SystemClock.uptimeMillis() - t0
    }

    override fun transcribe(
        context: Context,
        audio: Wav.Audio,
        language: String,
        threads: Int,
    ): ArmResult {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return ArmResult.failed(
                "on-device recognition unavailable on this build",
                audio.durationMs,
            )
        }

        val main = Handler(Looper.getMainLooper())
        val tracker = TranscriptTracker()
        val done = CountDownLatch(1)

        // Callbacks arrive on the main thread while the feeder runs on this
        // one, so the shared timing state lives in a holder with volatile
        // fields rather than in captured locals.
        val state = State()
        val segmentTexts = mutableListOf<String>()

        fun bestOf(bundle: Bundle?): String =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()

        fun noteText(text: String) {
            if (text.isBlank()) return
            if (state.firstPartialMs == null) state.firstPartialMs = SystemClock.uptimeMillis()
            tracker.update(text)
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                noteText(bestOf(partialResults))
            }

            override fun onResults(results: Bundle?) {
                noteText(bestOf(results))
                state.finalMs = SystemClock.uptimeMillis()
                if (!segmented) done.countDown()
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                val t = bestOf(segmentResults)
                if (t.isNotBlank()) {
                    state.segments++
                    segmentTexts += t
                    // Segment results are final-per-segment, so the running
                    // transcript is the concatenation so far.
                    noteText(segmentTexts.joinToString(" "))
                }
                state.finalMs = SystemClock.uptimeMillis()
            }

            override fun onEndOfSegmentedSession() {
                state.finalMs = SystemClock.uptimeMillis()
                done.countDown()
            }

            override fun onError(error: Int) {
                state.errorCode = error
                done.countDown()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        var recognizer: SpeechRecognizer? = null
        try {
            // SpeechRecognizer must be created and driven from a Looper thread.
            val created = CountDownLatch(1)
            main.post {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer?.setRecognitionListener(listener)
                created.countDown()
            }
            if (!created.await(10, TimeUnit.SECONDS)) {
                return ArmResult.failed("timed out creating recognizer", audio.durationMs)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47(language))
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                // Feed from the pipe, not the microphone.
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
                putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                    audio.sampleRate,
                )
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)

                if (segmented) {
                    // Keep the session open for as long as the audio source
                    // has audio, rather than stopping at the first pause.
                    putExtra(
                        RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                        RecognizerIntent.EXTRA_AUDIO_SOURCE,
                    )
                }
            }

            main.post { recognizer?.startListening(intent) }

            // The feed runs on its own thread with a watchdog.
            //
            // Writing to the pipe blocks once its ~64 KB buffer fills, so if
            // the recognizer stops reading -- which it does immediately when a
            // language pack is missing -- an inline feed would block forever
            // and the run would hang rather than report. That is not
            // hypothetical: it is exactly what the emulator did, and PLAN.md
            // §12 lists a missing Spanish pack on this MIUI build as a live
            // risk. A stalled arm must produce a recorded failure.
            var stats: PacedFeeder.FeedStats? = null
            var feedError: String? = null
            val feeder = Thread({
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { stream ->
                        stats = PacedFeeder(audio).feed(
                            sink = { pcm, _ -> stream.writePcm16(pcm) },
                            onStart = { state.feedStartMs = SystemClock.uptimeMillis() },
                            shouldStop = { state.errorCode != null },
                        )
                    }
                } catch (t: Throwable) {
                    feedError = "${t.javaClass.simpleName}: ${t.message}"
                }
            }, "paced-feeder")
            feeder.start()

            // Generous but finite: real time plus slack. Exceeding this means
            // the consumer stalled.
            val feedBudgetMs = audio.durationMs * 2 + 15_000
            feeder.join(feedBudgetMs)
            if (feeder.isAlive) {
                // Closing the read end breaks the blocked write with EPIPE.
                runCatching { readEnd.close() }
                feeder.join(5_000)
                if (feedError == null) {
                    feedError = "feed stalled: consumer stopped reading after " +
                        "${feedBudgetMs}ms budget"
                }
            }
            // Closing the write end is the end-of-audio signal.
            state.speechEndMs = SystemClock.uptimeMillis()

            main.post { recognizer?.stopListening() }

            // If the recognizer already errored there is nothing left to wait
            // for; only wait out the full timeout when a result is still
            // plausibly coming.
            val settled = if (state.errorCode != null) {
                done.await(2, TimeUnit.SECONDS)
            } else {
                done.await(FINAL_TIMEOUT_S, TimeUnit.SECONDS)
            }

            val err = state.errorCode
            if (err != null && tracker.text.isBlank()) {
                return ArmResult.failed(
                    "recognizer error ${errorName(err)}" +
                        (feedError?.let { "; $it" } ?: ""),
                    audio.durationMs,
                )
            }

            return ArmResult(
                hypothesis = tracker.text,
                latencyFirstPartialMs = state.firstPartialMs?.let { it - state.feedStartMs },
                latencyFinalMs = state.finalMs?.let { it - state.speechEndMs },
                partialInstability = tracker.revisions,
                partialUpdates = tracker.updates,
                // The recognition runs in Google's process, not ours. Time
                // spent in our sink is just a pipe write, so it says nothing
                // about the model. Reporting a number here would be a lie
                // (PLAN.md D6) -- sustained behaviour for Arm A has to come
                // from result timing instead.
                rtfSustained = null,
                maxSlipMs = stats?.maxSlipMs ?: 0,
                audioDurationMs = audio.durationMs,
                peakRssMb = io.github.davamix.asrbench.Telemetry.peakRssMb(),
                error = when {
                    feedError != null -> feedError
                    !settled -> "timed out after ${FINAL_TIMEOUT_S}s waiting for final result"
                    err != null -> "recognizer error ${errorName(err)} (partial text kept)"
                    else -> null
                },
                extra = mapOf(
                    "segmented" to segmented,
                    "segments" to state.segments,
                    "feed_frames" to (stats?.frames ?: 0),
                    "sink_busy_ms" to (stats?.sinkBusyMs ?: 0),
                    "feed_complete" to (stats != null),
                ),
            )
        } catch (t: Throwable) {
            return ArmResult.failed("${t.javaClass.simpleName}: ${t.message}", audio.durationMs)
        } finally {
            val r = recognizer
            if (r != null) {
                val closed = CountDownLatch(1)
                main.post {
                    runCatching { r.cancel() }
                    runCatching { r.destroy() }
                    closed.countDown()
                }
                closed.await(5, TimeUnit.SECONDS)
            }
            runCatching { readEnd.close() }
        }
    }

    private fun OutputStream.writePcm16(pcm: ShortArray) {
        val bb = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(pcm)
        write(bb.array())
        flush()
    }

    private fun bcp47(language: String): String = when (language) {
        "en" -> "en-US"
        "es" -> "es-ES"
        else -> language
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
        else -> "UNKNOWN($code)"
    }

    /** Cross-thread timing state: callbacks land on the main thread. */
    private class State {
        @Volatile var feedStartMs = 0L
        @Volatile var speechEndMs = 0L
        @Volatile var firstPartialMs: Long? = null
        @Volatile var finalMs: Long? = null
        @Volatile var errorCode: Int? = null
        @Volatile var segments = 0
    }

    private companion object {
        /**
         * Generous: this is a latency experiment, and silently truncating a
         * slow arm would flatter it. A timeout is recorded as a result.
         */
        const val FINAL_TIMEOUT_S = 120L
    }
}
