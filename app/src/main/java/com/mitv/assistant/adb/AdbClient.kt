package com.mitv.assistant.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** 坏协议帧等底层错误 */
private class AdbProtocolException(msg: String) : IOException(msg)

/**
 * TCP 能到达 adbd，但电视端还没有确认本机的调试授权：
 * 需要用户用遥控器在电视上的「允许USB调试？」弹窗里放行。
 */
class AdbUnauthorizedException(message: String) : IOException(message)

/**
 * Kotlin 不直接暴露 Object.wait/notifyAll，这里经平台类型转换调用。
 * 必须在对应的 synchronized 块内使用。
 */
private fun Any.objectWait(ms: Long = 0) {
    if (ms <= 0L) (this as java.lang.Object).wait() else (this as java.lang.Object).wait(ms)
}

private fun Any.objectNotifyAll() {
    (this as java.lang.Object).notifyAll()
}

/**
 * 精简实现的 ADB over TCP 客户端（参考 tananaev/adblib 与 AOSP aprotocol 消息帧格式）。
 *
 * 用途：电视开启 ADB 调试后，通过本客户端执行 shell 命令，
 * 实现按键模拟(input keyevent)、应用启停(pm enable/disable-user)、截屏(screencap)等能力。
 */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val keyPair: KeyPair,
) : java.io.Closeable {

    companion object {
        const val CMD_CNXN = 0x4E584E43
        const val CMD_AUTH = 0x48545541
        const val CMD_OPEN = 0x4E45504F
        const val CMD_OKAY = 0x59414B4F
        const val CMD_CLSE = 0x45534C43
        const val CMD_WRTE = 0x45545257

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLICKEY = 3

        /** 协议版本与声明支持的最大包体 */
        const val CONNECT_VERSION = 0x01000000
        const val MAX_PAYLOAD = 256 * 1024

        const val HEADER_LENGTH = 24

        /** ADB SYNC 帧类型：SEND / DATA / DONE */
        private const val SYNC_SEND = 0x41454E53
        private const val SYNC_DATA = 0x44415441
        private const val SYNC_DONE = 0x444F4E45

        /** 单个 DATA 块上限（60KB，兼容老 adbd 的 64K 缓冲） */
        private const val SYNC_CHUNK = 60 * 1024

        /** 无认证 adbd（ro.adb.secure=0）场景：收到首个 CNXN 后再静默观察的毫秒数 */
        const val INSECURE_GRACE_MS = 1200L

        /** 握手负载："host::" + 结尾 NUL 字节 */
        private val CONNECT_PAYLOAD: ByteArray =
            "host::".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
    }

    class Message(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
    )

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null

    @Volatile
    var connected = false
        private set

    var maxData: Int = MAX_PAYLOAD
        private set

    private var lastLocalId = 0
    private val openStreams = ConcurrentHashMap<Int, AdbStream>()
    private var sentSignature = false
    private var connectionThread: Thread? = null

    // ---- 授权状态机（仅在这些同步块内读写：readerLoop / waitForAuthorization）----
    /** 是否收到过 adbd 的 AUTH TOKEN 挑战 */
    @Volatile private var authChallengeSeen = false
    /** 认证挑战之后是否再次收到了 CNXN（= 电视端已放行） */
    @Volatile private var authorizedAfterAuth = false
    /** 首个 CNXN（握手横幅）到达时间；secure 设备在放行前不会重复发 */
    @Volatile private var firstBannerMs = 0L
    /** 最近一次 TOKEN 挑战到达时间 */
    @Volatile private var lastTokenAtMs = 0L
    /** 是否已经发送过公钥包 */
    @Volatile private var publicKeySent = false

    /**
     * 建立连接并完成 RSA 授权检测。
     *
     * - 电视从未记录本机公钥：adbd 会弹出「允许USB调试」确认框，本方法抛出
     *   [AdbUnauthorizedException]；调用方应在窗口期内重试，弹窗会持续保持。
     * - 已授权或 adbd 未启用认证：CNXN 到达后直接返回 true。
     */
    fun connect(timeoutMs: Long): Boolean {
        check(!connected) { "already connected" }

        val sock = Socket()
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(host, port), 4000)
        sock.soTimeout = 0
        socket = sock
        input = sock.getInputStream().buffered()
        output = sock.getOutputStream()

        send(generate(CMD_CNXN, CONNECT_VERSION, MAX_PAYLOAD, CONNECT_PAYLOAD))

        // 启动后台收包线程处理 AUTH / CNXN / 流数据
        connectionThread = Thread({ readerLoop() }, "adb-reader-$host").apply {
            isDaemon = true
            start()
        }

        return waitForAuthorization(timeoutMs)
    }

    private fun waitForAuthorization(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(this) {
            while (true) {
                if (authorizedAfterAuth) { connected = true; return true }
                // 无认证机制的 adbd：没有 AUTH 挑战、横幅已到且超过观察期 → 视为就绪
                if (!authChallengeSeen && firstBannerMs != 0L &&
                    System.currentTimeMillis() - firstBannerMs >= INSECURE_GRACE_MS
                ) {
                    connected = true
                    return true
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                objectWait(minOf(remain, 250))
            }
        }
        close()
        if (authChallengeSeen) {
            throw AdbUnauthorizedException(
                "电视端待确认：请在电视上用遥控器选择「一律允许/确定」（本次窗口 ${timeoutMs / 1000} 秒内未放行）",
            )
        }
        throw IOException("ADB 连接超时（$host:$port）：请确认电视的「ADB调试」开关已打开")
    }

    fun open(destination: String): AdbStream {
        require(connected) { "not connected" }
        val localId = ++lastLocalId
        val stream = AdbStream(this, localId)
        openStreams[localId] = stream
        send(
            generate(
                CMD_OPEN, localId, 0,
                destination.toByteArray(Charsets.UTF_8) + byteArrayOf(0),
            )
        )
        stream.waitForReady()
        if (stream.isClosed) {
            openStreams.remove(localId)
            throw IOException("ADB 打开「$destination」被设备拒绝（可能未在电视上允许调试）")
        }
        return stream
    }

    internal fun replyOkay(localId: Int, remoteId: Int) = send(generate(CMD_OKAY, localId, remoteId, null))

    internal fun writeStream(localId: Int, remoteId: Int, data: ByteArray) =
        send(generate(CMD_WRTE, localId, remoteId, data))

    internal fun closeStream(localId: Int, remoteId: Int) =
        send(generate(CMD_CLSE, localId, remoteId, null))

    /** 阻塞执行一次性 shell 命令并收集全部输出（调用方负责用协程超时保护）。 */
    fun exec(command: String): ByteArray = drain("shell:$command")

    /** 二进制安全：exec 服务不经过 pty，适合截屏等原始字节流。 */
    fun screencapViaExec(): ByteArray = drain("exec:screencap -p")

    /** 老固件回退路径，输出可能带换行污染，调用方需要修复。 */
    fun screencapViaShell(): ByteArray = drain("shell:screencap -p")

    private fun drain(service: String): ByteArray {
        val stream = open(service)
        try {
            val bos = ByteArrayOutputStream()
            while (true) {
                val chunk = stream.read() ?: break
                bos.write(chunk)
            }
            return bos.toByteArray()
        } finally {
            runCatching { stream.close() }
        }
    }

    fun shellText(command: String): String = String(exec(command), Charsets.UTF_8)

    // ------------------------------------------------------------------ 文件推送（ADB SYNC 协议）

    /**
     * 通过 `sync:` 服务把一段字节流写到设备本地路径（用于 APK 安装等）。
     * 调用方需用协程超时保护；成功返回，失败抛 IOException。
     */
    fun pushFile(remotePath: String, data: ByteArray, onProgress: ((Int) -> Unit)? = null) {
        val stream = open("sync:")
        try {
            val sendPayload = "$remotePath,${data.size}".toByteArray(Charsets.UTF_8) + byteArrayOf('\n'.code.toByte())
            stream.write(syncFrame(SYNC_SEND, sendPayload))

            var off = 0
            while (off < data.size) {
                val n = minOf(SYNC_CHUNK, data.size - off)
                stream.write(syncFrame(SYNC_DATA, data.copyOfRange(off, off + n)))
                off += n
                onProgress?.invoke((off * 100L / data.size).toInt())
            }

            val doneValue = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((System.currentTimeMillis() / 1000L).toInt()).array()
            stream.write(syncFrame(SYNC_DONE, doneValue))

            // 等待状态帧：OKAY 或 FAIL(+错误串)。每个 read() 是一个 WRTE 数据块。
            val collected = java.io.ByteArrayOutputStream()
            while (collected.size() < 260) {
                val chunk = stream.read() ?: break
                if (chunk.isEmpty()) continue
                collected.write(chunk)
                val buf = collected.toByteArray()
                if (buf.size >= 4 && buf[0] == 'O'.code.toByte() && buf[1] == 'K'.code.toByte() &&
                    buf[2] == 'A'.code.toByte() && buf[3] == 'Y'.code.toByte()
                ) return
                if (buf.size > 16 && buf[0] == 'F'.code.toByte() && buf[1] == 'A'.code.toByte()) break
            }
            val reply = collected.toByteArray()
            if (reply.size >= 4 && reply[0] == 'F'.code.toByte()) {
                val errLen = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val errText = String(reply, 8, minOf(errLen.coerceAtLeast(0), reply.size - 8), Charsets.UTF_8)
                throw IOException("同步推送失败：$errText")
            }
            throw IOException("同步推送未确认（收到 ${reply.size} 字节）")
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun syncFrame(id: Int, payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(id)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    override fun close() {
        connected = false
        runCatching { socket?.close() }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun send(packet: ByteArray) {
        synchronized(output!!) {
            output!!.write(packet)
            output!!.flush()
        }
    }

    private fun readerLoop() {
        try {
            val streamIn = DataInputStream(input!!)
            while (!Thread.currentThread().isInterrupted) {
                val msg = readMessage(streamIn)

                when (msg.command) {
                    CMD_AUTH -> if (msg.arg0 == AUTH_TOKEN) {
                        synchronized(this@AdbClient) {
                            authChallengeSeen = true
                            lastTokenAtMs = System.currentTimeMillis()
                            this@AdbClient.objectNotifyAll()
                        }
                        // 双包策略（对齐实测可用的电视管家类应用）：
                        // 立刻把「签名」和「完整公钥」一起发给 adbd。
                        // 已登记过的密钥走签名直接放行；陌生密钥则由公钥包在毫秒级触发电视端弹窗，
                        // 不再依赖部分固件缺失的「签名失败后重发 TOKEN」行为。
                        send(generate(CMD_AUTH, AUTH_SIGNATURE, 0, AdbCryptoCompat.signToken(keyPair, msg.payload)))
                        sentSignature = true
                        // 公钥包首发一次；此后若 adbd 因弹窗未确认而重发 TOKEN，则跟随补发保持弹窗有效
                        if (!publicKeySent) {
                            publicKeySent = true
                            runCatching {
                                send(generate(CMD_AUTH, AUTH_PUBLICKEY, 0, AdbCryptoCompat.adbPublicKeyPayload(keyPair)))
                            }
                        } else {
                            runCatching {
                                send(generate(CMD_AUTH, AUTH_PUBLICKEY, 0, AdbCryptoCompat.adbPublicKeyPayload(keyPair)))
                            }
                        }
                    }

                    CMD_CNXN -> synchronized(this) {
                        maxData = msg.arg1
                        if (authChallengeSeen) {
                            // 认证挑战之后又收到 CNXN = 用户已在电视端放行
                            authorizedAfterAuth = true
                            connected = true
                        } else if (firstBannerMs == 0L) {
                            firstBannerMs = System.currentTimeMillis()
                        }
                        this@AdbClient.objectNotifyAll()
                    }

                    CMD_OKAY, CMD_WRTE, CMD_CLSE -> {
                        val stream = openStreams[msg.arg1] ?: continue
                        synchronized(stream) {
                            when (msg.command) {
                                CMD_OKAY -> stream.onReady(msg.arg0)
                                CMD_WRTE -> {
                                    stream.onPayload(msg.payload)
                                    stream.sendAcknowledge()
                                }
                                CMD_CLSE -> stream.onPeerClosed()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 连接中断：收尾所有流并通知等待者
        } finally {
            connected = false
            for (stream in openStreams.values) {
                runCatching { stream.closeByFailure() }
            }
            openStreams.clear()
            synchronized(this) { this@AdbClient.objectNotifyAll() }
        }
    }

    private fun readMessage(dataIn: DataInputStream): Message {
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        var filled = 0
        while (filled < HEADER_LENGTH) {
            val n = dataIn.read(header.array(), filled, HEADER_LENGTH - filled)
            if (n < 0) throw IOException("adb 连接已关闭")
            filled += n
        }
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val length = header.int
        val checksum = header.int
        val magic = header.int

        if (command != (magic xor -1)) throw AdbProtocolException("坏消息头")

        val payload = if (length > 0) {
            if (length > MAX_PAYLOAD) throw AdbProtocolException("payload 过大 $length")
            val bytes = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = dataIn.read(bytes, off, length - off)
                if (n < 0) throw IOException("payload 中断")
                off += n
            }
            bytes
        } else ByteArray(0)

        if (payload.isNotEmpty()) {
            val sum = payload.fold(0L) { acc, b -> acc + (b.toInt() and 0xFF) }
            if (sum != checksum.toLong()) throw AdbProtocolException("校验和不匹配")
        }
        return Message(command, arg0, arg1, payload)
    }

    // ------------------------------------------------------------------ 协议编解码

    private fun checksumOf(payload: ByteArray): Int {
        var sum = 0L
        for (b in payload) sum += b.toInt() and 0xFF
        return sum.toInt()
    }

    private fun generate(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?): ByteArray {
        val body = payload ?: ByteArray(0)
        val buffer = ByteBuffer.allocate(HEADER_LENGTH + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(body.size)
        buffer.putInt(if (body.isEmpty()) 0 else checksumOf(body))
        buffer.putInt(cmd.inv())
        buffer.put(body)
        return buffer.array()
    }

    // ------------------------------------------------------------------ 流

    inner class AdbStream internal constructor(
        private val client: AdbClient,
        private val localId: Int,
    ) {
        private var remoteId = 0
        private val queue = ConcurrentLinkedQueue<ByteArray>()
        private var readyForWrite = false

        @Volatile
        var isClosed = false
            private set

        private var pendingClose = false

        internal fun onReady(remote: Int) {
            remoteId = remote
            readyForWrite = true
            synchronized(this) { objectNotifyAll() }
        }

        internal fun onPayload(payload: ByteArray) {
            queue.add(payload)
            synchronized(queue) { queue.objectNotifyAll() }
        }

        internal fun sendAcknowledge() = client.replyOkay(localId, remoteId)

        internal fun onPeerClosed() {
            pendingClose = queue.isNotEmpty()
            if (!pendingClose) isClosed = true
            synchronized(queue) { queue.objectNotifyAll() }
            synchronized(this) { objectNotifyAll() }
        }

        internal fun closeByFailure() {
            isClosed = true
            synchronized(queue) { queue.objectNotifyAll() }
            synchronized(this) { objectNotifyAll() }
        }

        internal fun waitForReady() {
            val deadline = System.currentTimeMillis() + 10_000
            synchronized(this) {
                while (!readyForWrite && !isClosed) {
                    val remain = deadline - System.currentTimeMillis()
                    if (remain <= 0) throw IOException("等待 ADB 响应超时")
                    objectWait(remain)
                }
            }
        }

        /** 读一块数据；返回 null 表示流结束。 */
        fun read(): ByteArray? {
            synchronized(queue) {
                var chunk = queue.poll()
                while (chunk == null && !isClosed) {
                    queue.objectWait(1000)
                    chunk = queue.poll()
                }
                if (chunk != null && pendingClose && queue.isEmpty()) isClosed = true
                return chunk
            }
        }

        fun write(data: ByteArray) {
            synchronized(this) {
                while (!isClosed && !readyForWrite) objectWait(1000)
                if (isClosed) throw IOException("stream closed")
                readyForWrite = false
            }
            client.writeStream(localId, remoteId, data)
        }

        fun close() {
            if (isClosed) return
            isClosed = true
            runCatching { client.closeStream(localId, remoteId) }
            synchronized(queue) { queue.objectNotifyAll() }
            synchronized(this) { objectNotifyAll() }
        }
    }
}
