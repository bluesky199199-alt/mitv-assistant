package com.mitv.assistant.adb

import android.content.Context
import com.mitv.assistant.data.AppPrefs
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 单台电视的 ADB 连接持有者：复用长连接，失效自动重建。
 */
object TvAdb {

    const val DEFAULT_PORT = 5555

    private var client: AdbClient? = null
    private var boundHost: String? = null
    private var keyPair: KeyPair? = null

    fun ensureKey(context: Context): KeyPair {
        keyPair?.let { return it }
        synchronized(this) {
            keyPair?.let { return it }
            val prefs = AppPrefs.get(context.applicationContext)
            val restored = AdbCryptoCompat.keyPairFrom(prefs.adbPublicKey, prefs.adbPrivateKey)
            val kp = restored ?: AdbCryptoCompat.generateKeyPair().also {
                val (pubB64, privB64) = AdbCryptoCompat.encodeKeyPair(it)
                prefs.adbPublicKey = pubB64
                prefs.adbPrivateKey = privB64
            }
            keyPair = kp
            return kp
        }
    }

    /** 公钥指纹用于帮助页展示识别指纹 */
    fun fingerprint(context: Context): String = try {
        val pub = ensureKey(context).public.encoded
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(pub)
        digest.take(8).joinToString(":") { "%02X".format(it) }
    } catch (_: Exception) {
        "-"
    }

    /**
     * 获取可用连接；必要时重新连接。
     *
     * 未授权时抛出 [AdbUnauthorizedException]——调用方应改走 [ensureAuthorized]，
     * 由它维持电视端弹窗并等待用户放行。
     */
    @Synchronized
    @Throws(IOException::class)
    fun acquire(context: Context, ip: String, port: Int = DEFAULT_PORT, timeoutMs: Long = 14_000): AdbClient {
        client?.takeIf { it.connected && boundHost == "$ip:$port" }?.let { return it }
        reset()
        val fresh = AdbClient(ip, port, ensureKey(context))
        try {
            fresh.connect(timeoutMs)
        } catch (e: Exception) {
            runCatching { fresh.close() }
            throw e
        }
        client = fresh
        boundHost = "$ip:$port"
        return fresh
    }

    /**
     * 授权门禁：确保电视已放行本机的调试权限，返回可用连接。
     *
     * 工作方式：每次尝试都新建 TCP 会话并与 adbd 完成「签名→公钥」握手；
     * 若电视端弹出了确认框却未点确认，下一次重连会让公钥再次送达、弹窗保持可见。
     * 用户按下「一律允许/确定」后的下一轮探测即会通过 `echo` 探针并立即返回。
     *
     * @param hint 每轮循环回调一次的界面提示文案
     */
    suspend fun ensureAuthorized(
        context: Context,
        ip: String,
        port: Int = DEFAULT_PORT,
        totalWindowMs: Long = 180_000,
        retryEveryMs: Long = 2_500,
        hint: ((String) -> Unit)? = null,
    ): AdbClient {
        val started = System.currentTimeMillis()
        var attempt = 0
        var lastError: Exception? = null

        while (System.currentTimeMillis() - started < totalWindowMs) {
            attempt++
            try {
                val remainingMs = (totalWindowMs - (System.currentTimeMillis() - started)).coerceAtLeast(1_000)
                // 在同一条认证会话中等待用户确认，避免 14 秒后主动断开导致授权弹窗反复消失。
                // Socket.connect + adblib.connect 都是阻塞调用，必须离开主线程；
                // 否则授权弹窗出现后 UI 会冻结，用户看到的状态一直停在“请求放行”。
                val candidate = withContext(Dispatchers.IO) {
                    acquire(context, ip, port, timeoutMs = remainingMs)
                }
                // 探针：一条最小 shell 命令。未授权会在 acquire/connect 抛出，不会走到这里
                val echo = withContext(Dispatchers.IO) {
                    String(candidate.exec("echo __auth_ok__"), Charsets.UTF_8)
                }
                if (echo.contains("__auth_ok__")) return candidate
                throw IOException("ADB 探测响应异常：${echo.take(60)}")
            } catch (e: AdbUnauthorizedException) {
                lastError = e
                // 保持重试：让 adbd 反复收到公钥 → 电视上的确认框持续存在
            } catch (e: Exception) {
                lastError = e
                reset()
            }

            val remainSec = (totalWindowMs - (System.currentTimeMillis() - started)) / 1000
            hint?.invoke(
                "正在请求电视放行调试权限（第 $attempt 次，剩约 ${remainSec}s）…" +
                    "若电视出现「允许USB调试？」请勾选一律允许并确定",
            )
            delay(retryEveryMs)
        }

        throw AdbUnauthorizedException(
            "等了 ${totalWindowMs / 1000} 秒仍未获得授权。" +
                "检查清单：①电视「设置→账号与安全→ADB调试」是否打开；" +
                "②电视上是否有被忽略/遮挡的确认弹窗；③手机与电视是否同一网络。(${lastError?.message ?: ""})",
        )
    }

    @Synchronized
    fun reset() {
        client?.close()
        client = null
        boundHost = null
    }

    /** 仅探测端口是否开放（不触发认证流程） */
    fun probePort(ip: String, port: Int = DEFAULT_PORT, timeoutMs: Int = 1200): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }
}
