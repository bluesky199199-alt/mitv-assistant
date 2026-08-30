package com.mitv.assistant.adb

import java.io.IOException

/**
 * 电视管理操作层：在已授权的 [AdbClient] 上执行运维类命令。
 * 全部为阻塞式；UI 层需在协程 Dispatchers.IO + withTimeout 中调用。
 */
object TvOps {

    private const val TMP_APK = "/data/local/tmp/mitvassistant_install.apk"

    /** 电视概要信息，供信息面板展示 */
    data class TvInfo(
        val model: String,
        val brand: String,
        val androidVersion: String,
        val resolution: String,
        val memory: String,
        val storage: String,
        val uptime: String,
    ) {
        fun asMonoText(): String = """
            型号    $model
            品牌    $brand
            系统    Android $androidVersion
            分辨率  $resolution
            内存    $memory
            存储    $storage
            已运行  $uptime
        """.trimIndent()
    }

    fun fetchInfo(client: AdbClient): TvInfo {
        fun prop(key: String): String =
            String(client.exec("getprop $key"), Charsets.UTF_8).trim()

        val memRaw = String(client.exec("cat /proc/meminfo | head -n 3"), Charsets.UTF_8)
        var memory = "未知"
        runCatching {
            val totalKb = Regex("MemTotal:\\s+(\\d+)").find(memRaw)?.groupValues?.get(1)?.toLong()
            val availKb = Regex("MemAvailable:\\s+(\\d+)").find(memRaw)?.groupValues?.get(1)?.toLong()
            if (totalKb != null) {
                val totalGb = "%.1fGB".format(totalKb / 1024f / 1024f)
                memory = if (availKb != null) "$totalGb（可用 %.1fGB）".format(availKb / 1024f / 1024f) else totalGb
            }
        }

        val dfRaw = String(client.exec("df -h /data | tail -n 1"), Charsets.UTF_8).trim()
        val storage = Regex("""\S+\s+(\S+)\s+(\S+)\s+(\S+)\s+\d+%""")
            .find(dfRaw)?.let { m -> "可用 ${m.groupValues[3]} / 共 ${m.groupValues[1]}" } ?: "未知"

        val upRaw = String(client.exec("cat /proc/uptime"), Charsets.UTF_8)
        val upSec = Regex("^\\d+").find(upRaw.trim())?.value?.toFloatOrNull() ?: 0f
        val days = (upSec / 86400).toInt()
        val hours = ((upSec % 86400) / 3600).toInt()
        val minutes = ((upSec % 3600) / 60).toInt()
        val uptime = if (days > 0) "${days}天${hours}小时" else "${hours}小时${minutes}分"

        return TvInfo(
            model = prop("ro.product.model").ifBlank { prop("ro.product.device") },
            brand = prop("ro.product.brand"),
            androidVersion = prop("ro.build.version.release"),
            resolution = prop("ro.product.display").ifBlank { "电视输出分辨率" },
            memory = memory,
            storage = storage,
            uptime = uptime,
        )
    }

    /** 清理应用缓存（trim-caches，释放整个设备缓存目录） */
    fun clearCaches(client: AdbClient): Boolean {
        val out = String(client.exec("pm trim-caches 4096G && echo __done__"), Charsets.UTF_8)
        return out.contains("__done__")
    }

    /** 重启电视 */
    fun reboot(client: AdbClient): Boolean {
        client.exec("(reboot || reboot -f) >/dev/null 2>&1")
        return true
    }

    /**
     * 安装手机选中的 APK 到电视：推送 → pm install -r → 清理。
     * 返回安装日志尾部供 UI 展示。
     */
    fun installApk(client: AdbClient, apkBytes: ByteArray, onProgress: ((Int) -> Unit)? = null): String {
        runCatching { client.exec("rm -f $TMP_APK") }

        try {
            client.pushFile(TMP_APK, apkBytes, onProgress)
        } catch (e: IOException) {
            throw IOException("推送失败：${e.message}")
        }

        val out = String(client.exec("pm install -r $TMP_APK"), Charsets.UTF_8).trim()
        runCatching { client.exec("rm -f $TMP_APK") }

        if (!out.contains("Success", ignoreCase = true)) {
            throw IOException(out.lineSequence().filter { it.isNotBlank() }.take(2).joinToString("; ").ifBlank { "pm install 未返回成功" })
        }
        return out
    }
}
