package com.mitv.assistant.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小米电视早期固件暴露在局域网的 HTTP 直控接口（端口 6095）。
 * 参考开源实现 pymitv：
 *   GET http://IP:6095/controller?action=keyevent&keycode=xxx
 *   GET http://IP:6095/request?action=isalive
 *   GET http://IP:6095/general?action=getVolum
 * 新固件可能关闭该接口，届时由 ADB 通道兜底。
 */
object MiTvHttp {

    const val PORT = 6095

    private const val TIMEOUT_MS = 1500

    fun baseUrl(ip: String) = "http://$ip:$PORT"

    /**
     * 设备探活。返回 true 表示 6095 接口可用。
     */
    fun isAlive(ip: String): Boolean = try {
        val conn = open("/request?action=isalive", ip)
        val code = conn.responseCode
        readAll(conn)
        code == 200
    } catch (_: Exception) {
        false
    }

    /**
     * 发送按键。可用 keycode（经验证的字面量）：
     * power, up, down, left, right, enter, back, home, menu, volumeup, volumedown
     */
    fun sendKey(ip: String, keycode: String): Boolean = try {
        val conn = open("/controller?action=keyevent&keycode=$keycode", ip)
        val code = conn.responseCode
        readAll(conn)
        code == 200
    } catch (_: Exception) {
        false
    }

    /** 切换信号源：hdmi1 / hdmi2 等 */
    fun changeSource(ip: String, source: String): Boolean = try {
        val conn = open("/controller?action=changesource&source=$source", ip)
        val code = conn.responseCode
        readAll(conn)
        code == 200
    } catch (_: Exception) {
        false
    }

    /**
     * 查询音量。响应形如 {"status":0,"data":"{\"volum\":32,...}"}，data 是二次编码的 JSON 字符串。
     */
    fun getVolume(ip: String): Int? = try {
        val body = readAll(open("/general?action=getVolum", ip))
        val outer = JSONObject(body)
        val inner = JSONObject(outer.optString("data"))
        when (inner.optInt("volum", Int.MIN_VALUE)) {
            Int.MIN_VALUE -> null
            else -> inner.getInt("volum")
        }
    } catch (_: Exception) {
        null
    }

    /** 获取电视型号信息（部分固件返回 oslcd / 版本等字段，仅供展示）。 */
    fun getInfo(ip: String): String? = try {
        val body = readAll(open("/request?action=getinfo", ip))
        JSONObject(body).optString("version").ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun open(pathAndQuery: String, ip: String): HttpURLConnection {
        val conn = URL("${baseUrl(ip)}$pathAndQuery").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        return conn
    }

    private fun readAll(conn: HttpURLConnection): String =
        try { conn.inputStream.bufferedReader().use { it.readText() } }
        catch (_: Exception) { "" }
        finally { conn.disconnect() }
}
