package io.github.davamix.asrbench

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import java.io.File
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The only screen the harness has, and only for the real-microphone check
 * (PLAN.md §7, §10 step 20). The instrumented test `BenchmarkTest#micCheck`
 * opens it and drives it; it is not a demo UI.
 *
 * It exists for three reasons:
 *
 *  - **Permission, the ordinary way.** RECORD_AUDIO is asked for here and the
 *    owner taps Allow on the phone. Granting it over adb (`pm grant`) needs
 *    MIUI's "USB debugging (Security settings)", which §11.1 rules out.
 *  - **The microphone needs a visible app.** Android hands silence to an app
 *    that records from the background, and a headless instrumentation process
 *    is not reliably foreground. While this activity is on screen, it is.
 *  - **The reader needs a script.** It shows the sentences to read and when
 *    to start and stop.
 */
class MicCheckActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var prompt: TextView
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No title bar: apps targeting SDK 35+ are drawn edge to edge, and a
        // title bar then covers the first line, which here is the countdown.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, pad)
            text = "Preparing…"
        }
        // The script is a file run_bench.py pushes, named by language: the
        // activity is started from the shell, where text with spaces and
        // accents does not survive as an intent extra.
        val lang = intent.getStringExtra(EXTRA_LANG).orEmpty()
        prompt = TextView(this).apply {
            textSize = 22f
            setLineSpacing(0f, 1.3f)
            text = File(getExternalFilesDir(null), "mic/prompt-$lang.txt")
                .takeIf { it.isFile }?.readText()?.trim().orEmpty()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(prompt)
        }
        setContentView(ScrollView(this).apply {
            addView(body)
            // Keep clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

        current = this
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            status.text = "Allow microphone access to continue"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    /** Any thread. */
    fun setStatus(text: String) = runOnUiThread {
        timer?.cancel()
        status.text = text
    }

    /** Any thread: "[label] 0:42 left", ticking down on the UI thread. */
    fun countdown(label: String, seconds: Int) = runOnUiThread {
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(left: Long) {
                val s = ((left + 999) / 1000).toInt()
                status.text = "$label  %d:%02d left".format(s / 60, s % 60)
            }

            override fun onFinish() {
                status.text = "$label  0:00"
            }
        }.start()
    }

    override fun onDestroy() {
        timer?.cancel()
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LANG = "lang"
        private const val REQUEST_MIC = 1

        /** The screen on show, for the test that drives it; null when none. */
        @Volatile
        var current: MicCheckActivity? = null
            private set
    }
}
