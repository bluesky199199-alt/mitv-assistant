package com.mitv.assistant

import android.app.Application
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.data.AppPrefs

class App : Application() {

    lateinit var prefs: AppPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs.get(this)
        // 预生成/恢复 ADB 密钥对（首次生成会写盘，放主线程一次性约几十毫秒可接受）
        TvAdb.ensureKey(this)
    }

    companion object {
        @Volatile
        private var instance: App? = null

        fun get(): App = requireNotNull(instance)
    }
}
