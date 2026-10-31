package com.mitv.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局轻量配置存储。
 */
class AppPrefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mitv_prefs", Context.MODE_PRIVATE)

    /** 当前选择的电视 IP */
    var tvIp: String?
        get() = sp.getString(KEY_TV_IP, null)
        set(value) = sp.edit().putString(KEY_TV_IP, value).apply()

    /** 上次成功使用的通道描述，仅用于展示 */
    var lastChannel: String?
        get() = sp.getString(KEY_LAST_CHANNEL, null)
        set(value) = sp.edit().putString(KEY_LAST_CHANNEL, value).apply()

    /** 遥控器常用应用包名列表 */
    var favorites: MutableList<String>
        get() = sp.getString(KEY_FAVORITES, "")!!.split('\n').filter { it.isNotBlank() }.toMutableList()
        set(value) = sp.edit().putString(KEY_FAVORITES, value.joinToString("\n")).apply()

    /** 儿童锁白名单包名集合 */
    var whitelist: MutableSet<String>
        get() = LinkedHashSet(sp.getString(KEY_WHITELIST, "")!!.split('\n').filter { it.isNotBlank() })
        set(value) = sp.edit().putString(KEY_WHITELIST, value.joinToString("\n")).apply()

    /** 本工具实际执行禁用的包；恢复操作只针对该集合。 */
    var managedDisabledPackages: MutableSet<String>
        get() = LinkedHashSet(sp.getString(KEY_MANAGED_DISABLED, "")!!.split('\\n').filter { it.isNotBlank() })
        set(value) = sp.edit().putString(KEY_MANAGED_DISABLED, value.joinToString("\\n")).apply()

    /** 最近操作记录（本地保存，最多保留 100 条）。 */
    var operationLog: MutableList<String>
        get() = sp.getString(KEY_OPERATION_LOG, "")!!.split('\\n').filter { it.isNotBlank() }.toMutableList()
        set(value) = sp.edit().putString(KEY_OPERATION_LOG, value.takeLast(100).joinToString("\\n")).apply()

    fun logOperation(message: String) {
        val entries = operationLog
        entries.add("${System.currentTimeMillis()}|$message")
        operationLog = entries
    }

    /** 包名 -> 备注名 */
    fun aliasFor(pkg: String): String? =
        sp.getString(KEY_ALIASES, "")!!.split('\n')
            .firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')?.ifBlank { null }

    fun setAlias(pkg: String, alias: String?) {
        val rest = sp.getString(KEY_ALIASES, "")!!.split('\n').filter { it.isNotBlank() && !it.startsWith("$pkg|") }.toMutableList()
        if (!alias.isNullOrBlank()) rest.add("$pkg|$alias")
        sp.edit().putString(KEY_ALIASES, rest.joinToString("\n")).apply()
    }

    /** 本机生成的 ADB 密钥对（Base64 编码的 X509 公钥 / PKCS8 私钥） */
    var adbPrivateKey: String?
        get() = sp.getString(KEY_ADB_PRIV, null)
        set(value) = sp.edit().putString(KEY_ADB_PRIV, value).apply()

    var adbPublicKey: String?
        get() = sp.getString(KEY_ADB_PUB, null)
        set(value) = sp.edit().putString(KEY_ADB_PUB, value).apply()

    companion object {
        private const val KEY_TV_IP = "tv_ip"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_MANAGED_DISABLED = "managed_disabled_packages"
        private const val KEY_OPERATION_LOG = "operation_log"
        private const val KEY_ALIASES = "aliases"
        private const val KEY_ADB_PRIV = "adb_priv_pkcs8_b64"
        private const val KEY_ADB_PUB = "adb_pub_x509_b64"

        @Volatile
        private var instance: AppPrefs? = null

        fun get(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }
}
