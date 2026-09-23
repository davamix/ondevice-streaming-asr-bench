package io.github.davamix.asrbench.arms

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the platform to download an on-device recognition language pack.
 *
 * `SpeechRecognizer.triggerModelDownload()` (API 33) is the supported way to
 * do this. It matters here because the device under test has only `es-ES`
 * installed, so Arm A cannot be measured on English at all until `en-US`
 * arrives -- and there is no reliable Settings path for it on this build.
 *
 * Two things to know about the API on API 33:
 *
 *  - It is **fire-and-forget**. The `ModelDownloadListener` overload that
 *    reports progress and errors only arrives in API 34, so on this device the
 *    only way to know whether anything happened is to poll
 *    `checkRecognitionSupport` and watch the installed/pending lists.
 *  - It is a **request, not a command**. The download happens inside Google's
 *    process and can be declined, deferred, or silently ignored. A run that
 *    ends with the pack still missing is a legitimate outcome, not a bug here.
 */
object LanguagePackInstaller {

    private const val TAG = "AsrBench"

    class Outcome(
        val language: String,
        val installedBefore: List<String>,
        val installedAfter: List<String>,
        val pendingAfter: List<String>,
        val succeeded: Boolean,
        val wentPending: Boolean,
        val waitedMs: Long,
        val error: String?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "language" to language,
            "installed_before" to installedBefore,
            "installed_after" to installedAfter,
            "pending_after" to pendingAfter,
            "succeeded" to succeeded,
            "went_pending" to wentPending,
            "waited_ms" to waitedMs,
            "error" to error,
        )
    }

    /**
     * Triggers the download and then polls until the pack appears, [timeoutMs]
     * elapses, or the platform stops reporting it as pending.
     */
    fun install(
        context: Context,
        language: String,
        timeoutMs: Long = 5 * 60 * 1000,
        pollMs: Long = 15_000,
    ): Outcome {
        val started = SystemClock.uptimeMillis()

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return Outcome(language, emptyList(), emptyList(), emptyList(),
                false, false, 0, "on-device recognition unavailable")
        }

        val before = RecognitionSupportProbe.probe(context, language, timeoutS = 30)
        Log.i(TAG, "INSTALL[$language] before: installed=${before.installedOnDevice} " +
            "pending=${before.pendingOnDevice}")

        if (before.isInstalled(language)) {
            return Outcome(language, before.installedOnDevice, before.installedOnDevice,
                before.pendingOnDevice, true, false,
                SystemClock.uptimeMillis() - started, null)
        }

        val main = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var triggerError: String? = null

        try {
            val created = CountDownLatch(1)
            main.post {
                runCatching {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }.onFailure { triggerError = "create: ${it.message}" }
                created.countDown()
            }
            created.await(15, TimeUnit.SECONDS)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            val triggered = CountDownLatch(1)
            main.post {
                runCatching { recognizer?.triggerModelDownload(intent) }
                    .onFailure { triggerError = "trigger: ${it.message}" }
                triggered.countDown()
            }
            triggered.await(15, TimeUnit.SECONDS)
            Log.i(TAG, "INSTALL[$language] triggerModelDownload dispatched " +
                "(error=${triggerError ?: "none"})")

            // Poll. The pack may go pending first, then installed.
            var wentPending = false
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(pollMs)
                val now = RecognitionSupportProbe.probe(context, language, timeoutS = 30)
                val pending = now.pendingOnDevice.any {
                    it.substringBefore('-').equals(language.substringBefore('-'), true)
                }
                if (pending) wentPending = true
                Log.i(TAG, "INSTALL[$language] poll: installed=${now.installedOnDevice} " +
                    "pending=${now.pendingOnDevice}")

                if (now.isInstalled(language)) {
                    return Outcome(language, before.installedOnDevice,
                        now.installedOnDevice, now.pendingOnDevice,
                        true, wentPending, SystemClock.uptimeMillis() - started,
                        triggerError)
                }
            }

            val end = RecognitionSupportProbe.probe(context, language, timeoutS = 30)
            return Outcome(
                language, before.installedOnDevice, end.installedOnDevice,
                end.pendingOnDevice, false, wentPending,
                SystemClock.uptimeMillis() - started,
                triggerError ?: "timed out after ${timeoutMs / 1000}s; " +
                    "the platform never reported $language as installed",
            )
        } catch (t: Throwable) {
            return Outcome(language, before.installedOnDevice, emptyList(), emptyList(),
                false, false, SystemClock.uptimeMillis() - started,
                "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            val r = recognizer
            if (r != null) {
                val closed = CountDownLatch(1)
                main.post { runCatching { r.destroy() }; closed.countDown() }
                closed.await(5, TimeUnit.SECONDS)
            }
        }
    }
}
