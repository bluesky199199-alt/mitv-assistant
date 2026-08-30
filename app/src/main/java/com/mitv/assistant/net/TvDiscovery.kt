package com.mitv.assistant.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.data.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 局域网电视发现：
 * 1. SSDP 组播（小米电视会响应 DIAL / ssdp:all）
 * 2. mDNS 发现 _airplay._tcp（小米电视默认开启的无线投屏服务）
 * 然后对候选 IP 并行探测 6095 直控与 5555 ADB 能力。
 */
object TvDiscovery {

    /** 总入口：扫描 + 能力探测，返回按可用性排序的设备列表。 */
    suspend fun discover(context: Context): List<TvDevice> = coroutineScope {
        val appCtx = context.applicationContext
        val ssdpJob = async(Dispatchers.IO) { ssdpScan(3600).map { TvDevice(it, source = "SSDP") } }
        val nsdJob = async(Dispatchers.IO) {
            runCatching { mdnsAirplay(appCtx) }.getOrDefault(emptyList())
                .map { TvDevice(it, source = "投屏服务(mDNS)") }
        }

        val merged = LinkedHashMap<String, TvDevice>()
        (ssdpJob.await() + nsdJob.await()).forEach { d ->
            merged.getOrPut(d.ip) { d }
        }

        withContext(Dispatchers.IO) { probeAndSort(merged.values.toList()) }
    }

    /** 探测一台设备并更新其能力标记。 */
    fun probeOne(device: TvDevice) {
        device.httpAlive = MiTvHttp.isAlive(device.ip)
        device.adbPortOpen = adbPortOpen(device.ip)
    }

    private fun probeAndSort(devices: List<TvDevice>): List<TvDevice> =
        devices.parallelStream()
            .peek { probeOne(it) }
            .toList()
            .sortedWith(
                compareByDescending<TvDevice> { it.httpAlive || it.adbPortOpen }
                    .thenByDescending { it.httpAlive }
                    .thenBy { it.ip }
            )

    private fun adbPortOpen(ip: String): Boolean = try {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(ip, 5555), 1200)
            true
        }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ SSDP

    private fun ssdpScan(durationMs: Long): List<String> {
        val ips = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                val messages = listOf(
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n",
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n",
                )
                messages.forEachIndexed { idx, msg ->
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    collectReplies(socket, durationMs / messages.size, ips)
                    if (idx == 0) ips += packetAddressesLater(socket, durationMs / messages.size)
                }
            }
        } catch (_: Exception) {
            // 扫描失败静默处理：用户可手动输 IP
        }
        return ips.toList()
    }

    /** 第二个窗口：继续收包并把来源地址也作为候选（有些设备 LOCATION 写主机名）。 */
    private fun packetAddressesLater(socket: DatagramSocket, windowMs: Long): Set<String> {
        val sink = mutableSetOf<String>()
        collectReplies(socket, windowMs, sink)
        return sink
    }

    private fun collectReplies(socket: DatagramSocket, windowMs: Long, sink: MutableSet<String>) {
        if (windowMs <= 0) return
        val deadline = System.currentTimeMillis() + windowMs
        val buf = ByteArray(2048)
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                packet.address?.hostAddress?.let(sink::add)
                extractLocationHost(String(packet.data, 0, packet.length, Charsets.ISO_8859_1))
                    ?.let(sink::add)
            } catch (_: SocketTimeoutException) {
                // 继续等到窗口结束
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun extractLocationHost(text: String): String? {
        val location = text.lineSequence().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?: return null
        val url = location.substringAfter(':').trim()
        return runCatching {
            InetAddress.getByName(extractAuthority(url)).hostAddress
        }.getOrNull()
    }

    private fun extractAuthority(url: String): String =
        url.substringAfter("//").substringBefore('/')

    // ------------------------------------------------------------------ mDNS 投屏服务

    /**
     * 小米电视默认开启 AirPlay 兼容投屏服务，注册在 _airplay._tcp。
     * 使用 NsdManager 同步风格 API：阻塞收集 + 逐个解析。
     */
    private fun mdnsAirplay(context: Context): List<String> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = Collections.synchronizedList(mutableListOf<NsdServiceInfo>())

        class Listener : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                if (!found.any { it.serviceName == serviceInfo.serviceName }) found.add(serviceInfo)
            }
        }

        val listener = Listener()
        runCatching { nsd.discoverServices("_airplay._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { return emptyList() }

        // 收集窗口 2.4s
        try {
            Thread.sleep(2400)
        } catch (_: InterruptedException) {
        }
        val stopped = CountDownLatch(1)
        Thread { runCatching { nsd.stopServiceDiscovery(listener) }.also { stopped.countDown() } }.start()
        stopped.await(800, TimeUnit.MILLISECONDS)

        // 逐个解析地址（NsdManager 同时只允许一个 resolve）
        val hosts = mutableListOf<String>()
        for (info in found.take(6)) {
            resolveHost(nsd, info)?.let(hosts::add)
        }
        return hosts.filterNotNull().distinct()
    }

    private fun resolveHost(nsd: NsdManager, info: NsdServiceInfo): String? {
        var resolved: String? = null
        val latch = CountDownLatch(1)
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo?, errorCode: Int) { latch.countDown() }
            override fun onServiceResolved(i: NsdServiceInfo?) {
                resolved = i?.host?.hostAddress
                latch.countDown()
            }
        }
        runCatching { nsd.resolveService(info, resolver) }
            .onFailure { return null }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return resolved
    }
}
