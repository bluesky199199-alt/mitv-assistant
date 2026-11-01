package com.mitv.assistant.adb

import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbCrypto
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.util.concurrent.TimeUnit

/** ADB 授权未完成：请在电视上确认本机 RSA 公钥。 */
class AdbUnauthorizedException(message: String) : IOException(message)

/**
 * ADB 客户端适配层。
 *
 * 协议帧、AUTH 状态机、RSA 签名、公钥序列化和流控制全部交给
 * tananaev/adblib 处理；本类只保留项目现有的 exec / 截屏 / push 接口。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair,
) : Closeable {

    @Volatile
    var connected: Boolean = false
        private set

    private var socket: Socket? = null
    private var connection: AdbConnection? = null
    private var maxData: Int = 4096

    @Synchronized
    fun connect(timeoutMs: Long): Boolean {
        check(!connected) { "already connected" }

        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(host, port), 5000)
            socket = sock

            val base64 = object : AdbBase64 {
                override fun encodeToString(data: ByteArray): String =
                    android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
            }
            val crypto = AdbCrypto.loadAdbKeyPair(base64, keyPair)
            val conn = AdbConnection.create(sock, crypto)
            connection = conn

            // false 表示允许标准 AUTH 流程继续：先签名，第二次 TOKEN 时发送公钥。
            // 若电视尚未授权，adblib 会继续等待电视端确认，而不是提前终止认证。
            val ok = conn.connect(timeoutMs, TimeUnit.MILLISECONDS, false)
            if (!ok) {
                close()
                throw AdbUnauthorizedException(
                    "已连接电视 $host:$port，但在 ${timeoutMs / 1000} 秒内未完成 ADB RSA 授权。请查看电视屏幕并确认授权弹窗。"
                )
            }

            maxData = runCatching { conn.maxData }.getOrDefault(4096).coerceAtLeast(1024)
            connected = true
            return true
        } catch (e: AdbUnauthorizedException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw IOException("ADB 连接失败（$host:$port）：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    fun exec(command: String): ByteArray = drain("shell:$command")

    fun screencapViaExec(): ByteArray = drain("exec:screencap -p")

    fun screencapViaShell(): ByteArray = drain("shell:screencap -p")

    fun shellText(command: String): String = String(exec(command), Charsets.UTF_8)

    private fun drain(destination: String): ByteArray {
        val conn = requireConnection()
        val stream = try {
            conn.open(destination)
        } catch (e: Exception) {
            throw IOException("打开 ADB 服务失败：$destination：${e.message}", e)
        }

        val output = ByteArrayOutputStream()
        try {
            while (true) {
                val part = try {
                    stream.read()
                } catch (_: IOException) {
                    // ADB 对端以 CLSE 结束服务流；已收到的数据仍然有效。
                    break
                }
                if (part != null && part.isNotEmpty()) output.write(part)
            }
        } finally {
            runCatching { stream.close() }
        }
        return output.toByteArray()
    }

    /** 通过 ADB SYNC 协议推送文件；按对端 maxData 分块，兼容老电视 adbd。 */
    fun pushFile(remotePath: String, data: ByteArray, onProgress: ((Int) -> Unit)? = null) {
        val stream = requireConnection().open("sync:")
        try {
            val sendPayload = "$remotePath,${data.size}".toByteArray(Charsets.UTF_8) +
                byteArrayOf('\n'.code.toByte())
            stream.write(syncFrame(SYNC_SEND, sendPayload))

            var offset = 0
            val chunkSize = minOf(maxData - 8, 4096).coerceAtLeast(1024)
            while (offset < data.size) {
                val count = minOf(chunkSize, data.size - offset)
                stream.write(syncFrame(SYNC_DATA, data.copyOfRange(offset, offset + count)))
                offset += count
                onProgress?.invoke((offset * 100L / data.size).toInt())
            }

            val mtime = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((System.currentTimeMillis() / 1000L).toInt()).array()
            stream.write(syncFrame(SYNC_DONE, mtime))

            val reply = ByteArrayOutputStream()
            while (reply.size() < 4096) {
                val packet = try { stream.read() } catch (_: IOException) { break }
                if (packet == null || packet.isEmpty()) continue
                reply.write(packet)
                val bytes = reply.toByteArray()
                if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("OKAY".toByteArray())) return
                if (bytes.size >= 8 && bytes.copyOfRange(0, 4).contentEquals("FAIL".toByteArray())) {
                    val length = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    val message = String(bytes, 8, minOf(length.coerceAtLeast(0), bytes.size - 8), Charsets.UTF_8)
                    throw IOException("ADB 文件推送失败：$message")
                }
            }
            throw IOException("ADB SYNC 未返回 OKAY：${reply.size()} 字节")
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun syncFrame(id: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(id).putInt(payload.size).put(payload).array()

    private fun requireConnection(): AdbConnection {
        if (!connected) throw IOException("ADB 尚未连接")
        return connection ?: throw IOException("ADB 连接对象不存在")
    }

    @Synchronized
    override fun close() {
        connected = false
        runCatching { connection?.close() }
        connection = null
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val SYNC_SEND = 0x41454E53
        private const val SYNC_DATA = 0x44415441
        private const val SYNC_DONE = 0x454E4F44
    }
}
