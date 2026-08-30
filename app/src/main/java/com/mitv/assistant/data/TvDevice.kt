package com.mitv.assistant.data

/** 局域网内发现的一台候选电视。 */
data class TvDevice(
    val ip: String,
    var name: String = "小米电视",
    /** 发现来源：SSDP / mDNS / 手动 */
    var source: String = "",
    /** 6095 直控接口是否存活 */
    var httpAlive: Boolean = false,
    /** ADB 5555 端口是否开放 */
    var adbPortOpen: Boolean = false,
) {
    override fun toString(): String = "$name ($ip)"
}
