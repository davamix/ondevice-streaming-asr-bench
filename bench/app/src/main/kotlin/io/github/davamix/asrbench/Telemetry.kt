package io.github.davamix.asrbench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import java.io.File

/**
 * Device-side telemetry: memory, thermals, and what this phone actually is.
 *
 * Deliberately records no serial, no account, no build fingerprint that could
 * identify the handset (PLAN.md §11.7 -- the repo is public and the device is
 * the owner's personal phone). SoC, core layout and RAM are the properties the
 * results actually depend on, and none of them identify anyone.
 */
object Telemetry {

    /** Peak resident set size of *this* process, in MB, from VmHWM. */
    fun peakRssMb(): Double? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { it / 1024.0 }
        }
    }.getOrNull()

    /** Current RSS in MB, from VmRSS. Sampled during long runs. */
    fun currentRssMb(): Double? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { it / 1024.0 }
        }
    }.getOrNull()

    class Battery(val tempC: Double?, val pct: Int?, val charging: Boolean?)

    /**
     * Battery temperature and level. The thermal gate in PLAN.md §11.3 (start
     * below 35 C, abort at 43 C) is enforced by the host-side runner, but the
     * values are recorded here too so every run in the JSON carries the
     * thermal context it was produced under.
     */
    fun battery(context: Context): Battery {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        return Battery(
            tempC = if (tempTenths != null && tempTenths != Int.MIN_VALUE) tempTenths / 10.0 else null,
            pct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (status >= 0) {
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            } else null,
        )
    }

    /** Non-identifying device description for the results header. */
    fun deviceInfo(context: Context): Map<String, Any?> = mapOf(
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "soc_manufacturer" to Build.SOC_MANUFACTURER,
        "soc_model" to Build.SOC_MODEL,
        "android_release" to Build.VERSION.RELEASE,
        "sdk_int" to Build.VERSION.SDK_INT,
        "abis" to Build.SUPPORTED_ABIS.toList(),
        "cpu_cores" to Runtime.getRuntime().availableProcessors(),
        "total_ram_mb" to totalRamMb(),
        "is_emulator" to isEmulator(),
        // Intentionally absent: Build.SERIAL, Build.FINGERPRINT, any account id.
    )

    fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("vbox", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    private fun totalRamMb(): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemTotal:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.let { it / 1024 }
        }
    }.getOrNull()
}
