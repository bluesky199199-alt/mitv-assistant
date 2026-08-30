package com.mitv.assistant.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mitv.assistant.adb.AdbClient
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.net.MiTvHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 遥控门面：优先走 6095 HTTP 直控；不可用或该键不支持时回退 ADB `input keyevent`。
 */
object RemoteController {

    enum class TvKey(val httpCode: String?, val adbCode: Int?) {
        POWER("power", 26),
        VOLUME_UP("volumeup", 24),
        VOLUME_DOWN("volumedown", 25),
        MUTE(null, 164),
        HOME("home", 3),
        MENU("menu", 82),
        BACK("back", 4),
        UP("up", 19),
        DOWN("down", 20),
        LEFT("left", 21),
        RIGHT("right", 22),
        OK("enter", 23),
    }

    sealed class SendResult {
        data class Ok(val channel: String) : SendResult()
        data class Fail(val message: String) : SendResult()
    }

    private const val OP_TIMEOUT_MS = 9_000L

    /** 当前电视 6095 是否存活（用于 UI 决定是否显示音量数值等）。 */
    fun httpAlive(ip: String): Boolean = MiTvHttp.isAlive(ip)

    suspend fun sendKey(context: Context, ip: String, key: TvKey): SendResult = withContext(Dispatchers.IO) {
        if (key.httpCode != null && MiTvHttp.sendKey(ip, key.httpCode!!)) {
            return@withContext SendResult.Ok("直控(6095)")
        }
        runAdb(context, ip) { exec("input keyevent ${key.adbCode}") }
            .fold({ SendResult.Fail(it) }, { SendResult.Ok("ADB调试") })
    }

    /** 发送任意数字键码（信号源切换等枚举外场景） */
    suspend fun sendRawKeycode(context: Context, ip: String, code: Int): SendResult =
        withContext(Dispatchers.IO) {
            runAdb(context, ip) { exec("input keyevent $code") }
                .fold({ SendResult.Fail(it) }, { SendResult.Ok("ADB keycode") })
        }

    /** 通过 ADB 启动电视上的应用。 */
    suspend fun launchApp(context: Context, ip: String, packageName: String): SendResult =
        withContext(Dispatchers.IO) {
            runAdb(context, ip) {
                val out = String(
                    exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1"),
                    Charsets.UTF_8,
                )
                check(out.contains("Events injected")) {
                    out.lineSequence().lastOrNull { it.isNotBlank() } ?: "启动失败"
                }
            }.fold({ SendResult.Fail(it) }, { SendResult.Ok("ADB") })
        }

    /** 发送 ASCII 文本到电视当前焦点输入框。空格用 %s 转义。 */
    suspend fun sendText(context: Context, ip: String, text: String): SendResult =
        withContext(Dispatchers.IO) {
            val escaped = text.replace(" ", "%s")
            runAdb(context, ip) { exec("input text '$escaped'") }
                .fold({ SendResult.Fail(it) }, { SendResult.Ok("ADB input") })
        }

    /**
     * 截取电视当前画面。
     * 优先使用 `exec:` 服务读二进制输出；个别老 adbd 不支持时退回 `shell:` 并修复换行污染。
     */
    suspend fun screencap(context: Context, ip: String): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val client = TvAdb.acquire(context, ip, timeoutMs = 16_000)
            var png: ByteArray? = try {
                client.screencapViaExec()
            } catch (_: Exception) {
                null
            }
            if (png == null || BitmapFactory.decodeByteArray(png, 0, png.size) == null) {
                png = repairCrlf(client.screencapViaShell())
            }
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size)
            if (bmp != null) Result.success(bmp)
            else Result.failure(IllegalStateException("截屏数据无法解码"))
        } catch (e: TimeoutCancellationException) {
            TvAdb.reset()
            Result.failure(IllegalStateException("截屏超时，请重试或降低刷新频率"))
        } catch (e: Exception) {
            TvAdb.reset()
            Result.failure(e)
        }
    }

    /** shell 通道可能把 \n 改写成 \r\n 导致 PNG 损坏；做一次去 \r 修复后由调用方再解码。 */
    private fun repairCrlf(raw: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream(raw.size)
        var i = 0
        while (i < raw.size) {
            if (raw[i] == 0x0D.toByte() && i + 1 < raw.size && raw[i + 1] == 0x0A.toByte()) {
                bos.write(0x0A)
                i += 2
            } else {
                bos.write(raw[i].toInt())
                i += 1
            }
        }
        return bos.toByteArray()
    }

    private suspend fun <T> runAdb(context: Context, ip: String, block: AdbClient.() -> T): Result<T> =
        try {
            val client = TvAdb.acquire(context, ip, timeoutMs = 16_000)
            val value = withTimeout(OP_TIMEOUT_MS) { block(client) }
            Result.success(value)
        } catch (e: TimeoutCancellationException) {
            TvAdb.reset()
            Result.failure(IllegalStateException("电视响应超时"))
        } catch (e: Exception) {
            TvAdb.reset()
            Result.failure(e)
        }

    private inline fun <T> Result<T>.fold(onErr: (String) -> SendResult, onOk: (T) -> SendResult): SendResult =
        if (isSuccess) onOk(getOrThrow()) else onErr(friendlyMessage(exceptionOrNull()))

    private fun friendlyMessage(t: Throwable?): String = when {
        t == null -> "未知错误"
        else -> "${t.javaClass.simpleName}: ${t.message ?: ""}"
    }
}
