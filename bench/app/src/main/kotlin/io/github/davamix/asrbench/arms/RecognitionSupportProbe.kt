package io.github.davamix.asrbench.arms

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Asks the platform recognizer what it can actually do on *this* build.
 *
 * PLAN.md §12 lists "is Arm A's Spanish on-device model present on this
 * Xiaomi/MIUI build, or does it need a download?" as an open risk that could
 * eliminate Arm A for Spanish entirely. `checkRecognitionSupport` (API 33)
 * answers it directly and costs nothing -- no inference, no thermal budget --
 * so it runs before any measurement rather than being discovered halfway
 * through a matrix.
 *
 * The distinction that matters is installed vs. supported: a language can be
 * *supported* (downloadable) while not *installed*, in which case Arm A will
 * fail or silently fall back at run time.
 */
object RecognitionSupportProbe {

    class Support(
        val language: String,
        val onDeviceAvailable: Boolean,
        val installedOnDevice: List<String>,
        val pendingOnDevice: List<String>,
        val supportedOnDevice: List<String>,
        val onlineLanguages: List<String>,
        val error: String?,
    ) {
        /** True if this exact language is installed and usable offline now. */
        fun isInstalled(bcp47: String): Boolean =
            installedOnDevice.any { it.equals(bcp47, ignoreCase = true) } ||
                installedOnDevice.any { it.substringBefore('-').equals(bcp47.substringBefore('-'), true) }

        fun toMap(): Map<String, Any?> = mapOf(
            "language" to language,
            "on_device_recognition_available" to onDeviceAvailable,
            "installed_on_device" to installedOnDevice,
            "pending_on_device" to pendingOnDevice,
            "supported_on_device" to supportedOnDevice,
            "online_languages" to onlineLanguages,
            "installed_for_this_language" to isInstalled(language),
            "error" to error,
        )
    }

    fun probe(context: Context, bcp47: String, timeoutS: Long = 30): Support {
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (!available) {
            return Support(bcp47, false, emptyList(), emptyList(), emptyList(), emptyList(),
                "on-device recognition unavailable")
        }

        val main = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var result: RecognitionSupport? = null
        var errorText: String? = null

        val executor = Executor { it.run() }
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                result = recognitionSupport
                latch.countDown()
            }

            override fun onError(error: Int) {
                errorText = "checkRecognitionSupport error $error"
                latch.countDown()
            }
        }

        var recognizer: SpeechRecognizer? = null
        try {
            val created = CountDownLatch(1)
            main.post {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                created.countDown()
            }
            created.await(10, TimeUnit.SECONDS)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            main.post { recognizer?.checkRecognitionSupport(intent, executor, callback) }
            if (!latch.await(timeoutS, TimeUnit.SECONDS)) {
                errorText = "checkRecognitionSupport timed out after ${timeoutS}s"
            }

            val rs = result
            return Support(
                language = bcp47,
                onDeviceAvailable = true,
                installedOnDevice = rs?.installedOnDeviceLanguages.orEmpty(),
                pendingOnDevice = rs?.pendingOnDeviceLanguages.orEmpty(),
                supportedOnDevice = rs?.supportedOnDeviceLanguages.orEmpty(),
                onlineLanguages = rs?.onlineLanguages.orEmpty(),
                error = errorText,
            )
        } catch (t: Throwable) {
            return Support(bcp47, available, emptyList(), emptyList(), emptyList(), emptyList(),
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
