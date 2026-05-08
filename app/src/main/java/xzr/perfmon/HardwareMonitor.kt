/*
adb root
adb shell setenforce 0
adb shell chmod 644 /sys/kernel/ged/hal/gpu_utilization
 */

package xzr.perfmon

import android.content.Context
import android.os.HardwarePropertiesManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

data class CpuCoreStat(
    var lastTotal: Long = 0,
    var lastIdle: Long = 0
)

data class CpuStats(val total: Int, val perCore: Map<Int, Int>)

class HardwareMonitor {

    // ── CPU ──────────────────────────────────────────────────────────────────
    private var lastTotal: Long = 0
    private var lastIdle: Long = 0
    private val coreStats = mutableMapOf<Int, CpuCoreStat>()

    /** 單次讀取 /proc/stat，同時回傳 total 和 per-core 數據，避免開兩次檔案。 */
    fun getCpuStats(): CpuStats {
        var total = 0
        val perCore = mutableMapOf<Int, Int>()

        try {
            BufferedReader(InputStreamReader(File("/proc/stat").inputStream())).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: break
                    if (!l.startsWith("cpu")) continue

                    val parts = l.split("\\s+".toRegex())
                    val user    = parts[1].toLong()
                    val nice    = parts[2].toLong()
                    val system  = parts[3].toLong()
                    val idle    = parts[4].toLong()
                    val iowait  = parts[5].toLong()
                    val irq     = parts[6].toLong()
                    val softirq = parts[7].toLong()
                    val ticks   = user + nice + system + idle + iowait + irq + softirq

                    if (l.startsWith("cpu ")) {
                        // 彙總行
                        val diffTotal = ticks - lastTotal
                        val diffIdle  = idle  - lastIdle
                        lastTotal = ticks
                        lastIdle  = idle
                        total = if (diffTotal > 0)
                            ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
                        else 0
                    } else {
                        // per-core 行 (cpu0, cpu1, ...)
                        val coreIndex = parts[0].removePrefix("cpu").toIntOrNull() ?: continue
                        val stat = coreStats.getOrPut(coreIndex) { CpuCoreStat() }
                        val diffTotal = ticks - stat.lastTotal
                        val diffIdle  = idle  - stat.lastIdle
                        stat.lastTotal = ticks
                        stat.lastIdle  = idle
                        perCore[coreIndex] = if (diffTotal > 0)
                            ((diffTotal - diffIdle) * 100 / diffTotal).toInt().coerceIn(0, 100)
                        else 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HardwareMonitor", "getCpuStats failed", e)
        }

        return CpuStats(total, perCore)
    }

    // ── GPU Load ──────────────────────────────────────────────────────────────
    private val gpuEma = EmaFilter()
    private var gpuDebugDone = false

    fun getGpuLoadEma(): Int = gpuEma.update(getGpuLoad())

    fun getGpuLoad(): Int {
        debugGpuPathsOnce()
        return try {
            val file = File("/sys/kernel/ged/hal/gpu_utilization")
            if (file.exists() && file.canRead()) {
                file.readText().trim()
                    .replace("%", "")
                    .split("\\s+".toRegex())
                    .firstOrNull()
                    ?.toIntOrNull() ?: 0
            } else -1
        } catch (e: Exception) {
            Log.e("innocomm", "gpu_utilization read failed: ${e.message}")
            -1
        }
    }

    // ── GPU Freq（快取 1 秒）──────────────────────────────────────────────────
    private var gpuFreqCacheTime = 0L
    private var gpuFreqCached = -1
    // 快取找到的有效路徑，避免每次 listFiles()
    private var gpuFreqFile: File? = null
    private var gpuFreqUseProcDump = false

    fun getGpuFreqMHz(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - gpuFreqCacheTime < 1000) return gpuFreqCached
        gpuFreqCacheTime = now
        gpuFreqCached = readGpuFreqMHz()
        return gpuFreqCached
    }

    private fun readGpuFreqMHz(): Int {
        // 使用已快取的路徑
        gpuFreqFile?.let { f ->
            return if (gpuFreqUseProcDump) readProcGpuFreq(f) else readDevfreqHz(f)
        }

        // 1. devfreq Mali (Hz)
        File("/sys/class/devfreq").listFiles()?.forEach { dir ->
            if (!dir.name.contains("mali", ignoreCase = true)) return@forEach
            val f = File(dir, "cur_freq")
            if (f.exists() && f.canRead()) {
                gpuFreqFile = f
                gpuFreqUseProcDump = false
                return readDevfreqHz(f)
            }
        }

        // 2. /proc/gpufreq/gpufreq_var_dump (KHz)
        File("/proc/gpufreq/gpufreq_var_dump").let { f ->
            if (f.exists() && f.canRead()) {
                gpuFreqFile = f
                gpuFreqUseProcDump = true
                return readProcGpuFreq(f)
            }
        }

        // 3. GED hal current_freqency (Hz, MTK typo)
        File("/sys/kernel/ged/hal/current_freqency").let { f ->
            if (f.exists() && f.canRead()) {
                gpuFreqFile = f
                gpuFreqUseProcDump = false
                return readDevfreqHz(f)
            }
        }

        return -1
    }

    private fun readDevfreqHz(f: File): Int {
        val hz = f.readText().trim().toLongOrNull() ?: return -1
        return if (hz > 0) (hz / 1_000_000).toInt() else -1
    }

    private fun readProcGpuFreq(f: File): Int {
        val match = Regex("g_cur_opp_freq\\s*=\\s*(\\d+)").find(f.readText()) ?: return -1
        val khz = match.groupValues[1].toLongOrNull() ?: return -1
        return if (khz > 0) (khz / 1000).toInt() else -1
    }

    // ── Temperature（快取 2 秒）──────────────────────────────────────────────
    private var hwPropsAvailable: Boolean? = null
    private var tempCacheTime = 0L
    private var tempCached = 0.0
    // 第一次掃描後快取有效的 temp 檔案清單，後續直接讀值
    private var validThermalFiles: List<File>? = null

    fun getCpuTemperature(context: Context): Double {
        val now = SystemClock.elapsedRealtime()
        if (now - tempCacheTime < 2000) return tempCached
        tempCacheTime = now
        tempCached = readTemperature(context)
        return tempCached
    }

    private fun readTemperature(context: Context): Double {
        // 方法 1：HardwarePropertiesManager（system app，只檢測一次）
        if (hwPropsAvailable != false) {
            try {
                val hwMgr = context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE)
                        as HardwarePropertiesManager
                val temps = hwMgr.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                )
                if (temps.isNotEmpty()) {
                    hwPropsAvailable = true
                    return temps.average()
                }
                hwPropsAvailable = false
                Log.w("innocomm", "HardwarePropertiesManager 溫度為空，改用 thermal sysfs")
            } catch (e: Exception) {
                hwPropsAvailable = false
                Log.w("innocomm", "HardwarePropertiesManager 不可用，改用 thermal sysfs: ${e.message}")
            }
        }

        // 方法 2：/sys/class/thermal sysfs（通用）
        return getThermalSysfsTemp()
    }

    private fun getThermalSysfsTemp(): Double {
        // 第一次：掃描並快取有效的 temp 檔案
        val files = validThermalFiles ?: buildValidThermalFiles().also { validThermalFiles = it }
        if (files.isEmpty()) return 0.0

        val readings = files.mapNotNull { f ->
            try {
                val raw = f.readText().trim().toLongOrNull() ?: return@mapNotNull null
                val celsius = if (raw > 1000) raw / 1000.0 else raw.toDouble()
                celsius.takeIf { it in 0.0..120.0 }
            } catch (_: Exception) { null }
        }

        return if (readings.isEmpty()) 0.0 else readings.average()
    }

    private fun buildValidThermalFiles(): List<File> {
        val excludeKeywords = listOf("skin", "charger", "battery", "pmic", "flash", "pa", "wifi", "usb")
        return File("/sys/class/thermal")
            .listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.filter { zone ->
                // 過濾非 CPU zone
                val typeFile = File(zone, "type")
                if (typeFile.exists() && typeFile.canRead()) {
                    val type = typeFile.readText().trim().lowercase()
                    if (excludeKeywords.any { type.contains(it) }) return@filter false
                }
                File(zone, "temp").let { it.exists() && it.canRead() }
            }
            ?.map { File(it, "temp") }
            ?: emptyList()
    }

    // ── APU IPS ─────────────────────────────────────────────────────────────
    private var lastApuIpi = 0L
    private var lastSampleTime = 0L
    private var lastIpsRaw = 0.0

    fun getApuIpsRaw(): Int {
        val currentIpi = try {
            var total = 0L
            File("/proc/interrupts").useLines { lines ->
                lines.forEach { line ->
                    if (line.contains("apu_ipi")) {
                        val numbers = "\\d+".toRegex().findAll(line).map { it.value.toLong() }.toList()
                        if (numbers.size > 1) {
                            total = numbers.drop(1).sum()
                        }
                    }
                }
            }
            total
        } catch (e: Exception) {
            -1L
        }

        val currentTime = SystemClock.elapsedRealtime()
        if (currentIpi < 0) return lastIpsRaw.toInt()

        if (lastApuIpi == 0L || currentTime <= lastSampleTime) {
            lastApuIpi = currentIpi
            lastSampleTime = currentTime
            return 0
        }

        val timeDiffMs = currentTime - lastSampleTime
        val ipiDiff = currentIpi - lastApuIpi

        lastApuIpi = currentIpi
        lastSampleTime = currentTime

        lastIpsRaw = (ipiDiff.toDouble() / timeDiffMs) * 1000.0
        return lastIpsRaw.toInt()
    }

    // ── GPU Debug（只跑一次）─────────────────────────────────────────────────
    private fun debugGpuPathsOnce() {
        if (gpuDebugDone) return
        gpuDebugDone = true

        val gedHal = File("/sys/kernel/ged/hal")
        if (gedHal.exists()) {
            Log.d("innocomm", "[GPU debug] /sys/kernel/ged/hal/ entries: ${gedHal.list()?.joinToString(", ")}")
            val util = File(gedHal, "gpu_utilization")
            Log.d("innocomm", "[GPU debug] gpu_utilization exists=${util.exists()} canRead=${util.canRead()}")
            if (util.exists() && util.canRead())
                Log.d("innocomm", "[GPU debug] gpu_utilization content: ${util.readText().trim()}")
        } else {
            Log.d("innocomm", "[GPU debug] /sys/kernel/ged/hal/ does NOT exist")
        }

        val devfreqEntries = File("/sys/class/devfreq").list()?.joinToString(", ") ?: "(not found)"
        Log.d("innocomm", "[GPU debug] /sys/class/devfreq/ entries: $devfreqEntries")

        val procGpufreq = File("/proc/gpufreq")
        if (procGpufreq.exists()) {
            listOf("gpufreq_opp_freq", "gpufreq_var_dump", "gpufreq_opp_dump").forEach { name ->
                val f = File(procGpufreq, name)
                if (f.exists() && f.canRead()) {
                    val preview = f.readLines().take(5).joinToString(" | ")
                    Log.d("innocomm", "[GPU debug] /proc/gpufreq/$name: $preview")
                }
            }
        }
    }
}
