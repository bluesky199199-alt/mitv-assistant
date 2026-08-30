package com.mitv.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitv.assistant.App
import com.mitv.assistant.R
import com.mitv.assistant.adb.AdbClient
import com.mitv.assistant.adb.AdbUnauthorizedException
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.data.TvDevice
import com.mitv.assistant.net.TvDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: android.view.View
    private lateinit var deviceNameText: TextView
    private lateinit var deviceStateText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        deviceNameText = findViewById(R.id.deviceNameText)
        deviceStateText = findViewById(R.id.deviceStateText)

        findViewById<android.view.View>(R.id.deviceCard).setOnClickListener { showDeviceDialog() }
        findViewById<android.view.View>(R.id.btnScanInline).setOnClickListener { scanAndPick() }
        findViewById<android.view.View>(R.id.tileRemote).setOnClickListener {
            requireTv { startActivity(Intent(this, RemoteActivity::class.java)) }
        }
        findViewById<android.view.View>(R.id.tileApps).setOnClickListener {
            requireTv { startActivity(Intent(this, AppsActivity::class.java)) }
        }
        findViewById<android.view.View>(R.id.tileCast).setOnClickListener {
            requireTv { startActivity(Intent(this, CastActivity::class.java)) }
        }
        findViewById<android.view.View>(R.id.tileTools).setOnClickListener {
            requireTv { startActivity(Intent(this, AppsActivity::class.java).putExtra("focus_tools", true)) }
        }
        findViewById<android.view.View>(R.id.helpText).setOnClickListener { showHelp() }

        renderSaved()
    }

    override fun onResume() {
        super.onResume()
        renderSaved()
    }

    private fun requireTv(action: () -> Unit) {
        if (App.get().prefs.tvIp.isNullOrBlank()) {
            Toast.makeText(this, R.string.current_tv_none, Toast.LENGTH_SHORT).show()
            return
        }
        action()
    }

    /** 渲染已保存电视 + 后台刷新真实状态灯 */
    private fun renderSaved() {
        val ip = App.get().prefs.tvIp
        if (ip.isNullOrBlank()) {
            statusDot.setBackgroundResource(R.drawable.dot_red)
            deviceNameText.text = getString(R.string.current_tv_none)
            deviceStateText.text = getString(R.string.device_pill_idle)
            return
        }
        deviceNameText.text = "当前电视：$ip"
        deviceStateText.text = "检测中…"
        statusDot.setBackgroundResource(R.drawable.dot_orange)

        lifecycleScope.launch(Dispatchers.IO) {
            val httpOk = com.mitv.assistant.net.MiTvHttp.isAlive(ip)
            var adbState = if (!TvAdb.probePort(ip)) "closed" else "unknown"
            if (adbState == "unknown") {
                try {
                    val probe = AdbClient(ip, TvAdb.DEFAULT_PORT, TvAdb.ensureKey(applicationContext))
                    try {
                        probe.connect(6_000)
                        adbState = "ready"
                    } catch (_: AdbUnauthorizedException) {
                        adbState = "unauthorized"
                    } catch (_: Exception) {
                        adbState = "error"
                    } finally {
                        runCatching { probe.close() }
                    }
                } catch (_: Exception) {
                    adbState = "error"
                }
            }
            withContext(Dispatchers.Main) {
                val dotRes = when (adbState) {
                    "ready" -> R.drawable.dot_green
                    "unauthorized" -> R.drawable.dot_orange
                    else -> R.drawable.dot_red
                }
                statusDot.setBackgroundResource(dotRes)
                deviceStateText.text = buildString {
                    append(if (httpOk) "直控 ✔ · " else "直控 ✘ · ")
                    append(
                        when (adbState) {
                            "ready" -> "ADB 可用"
                            "unauthorized" -> "ADB 待确认（点这里并保持页面）"
                            "closed" -> "ADB 未开启(5555)"
                            else -> "ADB 状态未知"
                        },
                    )
                }
            }
        }
    }

    /** 设备卡点击：扫描后从候选里挑选；长按手输 IP */
    private fun showDeviceDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("选择操作")
            .setItems(arrayOf("扫描局域网设备", "手动输入 IP")) { _, which ->
                when (which) {
                    0 -> scanAndPick()
                    1 -> showManualIpDialog()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scanAndPick() {
        MaterialAlertDialogBuilder(this)
            .setTitle("正在扫描…")
            .setMessage("请稍候，稍后会列出可用设备。\n\n提示：手机需与电视在同一 WiFi。")
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { dialog ->
                lifecycleScope.launch {
                    val devices = runCatching { TvDiscovery.discover(applicationContext) }.getOrDefault(emptyList())
                    dialog.dismiss()
                    if (devices.isEmpty()) {
                        Toast.makeText(this@MainActivity, getString(R.string.scan_empty), Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    pickFromList(devices)
                }
            }
    }

    private fun pickFromList(devices: List<TvDevice>) {
        val labels = devices.mapIndexed { i, d ->
            "$i. ${d.ip}（${d.source.ifBlank { "扫描" }}${if (d.httpAlive) " · 直控✔" else ""}${if (d.adbPortOpen) " · ADB口开" else ""}）"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("发现 ${devices.size} 台设备")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                App.get().prefs.tvIp = chosen.ip
                renderSaved()
                Toast.makeText(this, getString(R.string.toast_device_saved, chosen.ip), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showManualIpDialog() {
        val container = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null) as? android.view.ViewGroup
        val edit = EditText(this).apply { hint = getString(R.string.ip_hint); setSingleLine(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_ip)
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val ip = edit.text.toString().trim()
                if (!IPV4.matches(ip)) {
                    Toast.makeText(this, "IP 格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                App.get().prefs.tvIp = ip
                renderSaved()
                Toast.makeText(this, getString(R.string.toast_device_saved, ip), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .also { _ ->
                @Suppress("UNUSED_EXPRESSION")
                container
            }
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("使用帮助")
            .setMessage(
                """
                快速上手：
                1️⃣ 遥控器：默认走电视 6095 直控接口，无需任何设置。
                2️⃣ 应用管理/看屏/工具：需要电视「设置→账号与安全→ADB调试」打开；
                   打开后在本页等待几秒，电视会立刻弹出授权框，
                   用遥控器勾选“一律允许”再按确定即可（一次性）。
                3️⃣ 点设备卡可重新扫描或手输电视 IP。

                常见问题：
                · 扫不到：确认同一 WiFi、电视非深度待机，或打开电视的投屏开关后重扫。
                · 直控✘：新固件关闭了 6095 接口，不影响其他功能。

                本机公钥指纹：
                ${TvAdb.fingerprint(App.get())}
                """.trimIndent(),
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    companion object {
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    }
}
