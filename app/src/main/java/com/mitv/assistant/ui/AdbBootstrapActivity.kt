package com.mitv.assistant.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitv.assistant.App
import com.mitv.assistant.adb.AdbUnauthorizedException
import com.mitv.assistant.adb.TvAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * USB/网络 ADB 引导页。
 *
 * 手机和电视的 USB-A 口通常都是 USB Host，OTG 线不能把两个 Host 变成 USB Device；
 * 因此 USB 线仅作供电/物理连接提示，ADB 控制实际走局域网。
 */
class AdbBootstrapActivity : AppCompatActivity() {
    private lateinit var ipInput: EditText
    private lateinit var status: TextView
    private lateinit var checkButton: Button
    private lateinit var authorizeButton: Button
    private lateinit var enableButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "ADB 连接助手"

        val pad = (resources.displayMetrics.density * 18).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = "USB 检测 + 无线 ADB"
            textSize = 23f
            setTextColor(0xFF202124.toInt())
        })
        root.addView(TextView(this).apply {
            text = "当前方案：小米 K40 通过 Wi-Fi 与电视通信；USB-C OTG 接电视 USB-A 不能直接建立 ADB 主机连接。电视 USB-A 通常也是 Host 口。"
            textSize = 14f
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "电视 IP 地址"
            textSize = 14f
        })
        ipInput = EditText(this).apply {
            hint = "例如 192.168.1.105"
            setSingleLine(true)
            setText(App.get().prefs.tvIp.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(ipInput, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "状态：尚未检测。请让手机和电视连接同一个 Wi-Fi / 局域网。"
            textSize = 14f
            setPadding(0, pad, 0, pad)
        }
        root.addView(status)

        checkButton = addButton(root, "检测电视 TCP 5555")
        authorizeButton = addButton(root, "连接并请求电视授权")
        enableButton = addButton(root, "尝试开启 / 重启电视 TCP 5555")

        checkButton.setOnClickListener { runCheck() }
        authorizeButton.setOnClickListener { requestAuthorization() }
        enableButton.setOnClickListener { enableTcpAdb() }

        root.addView(TextView(this).apply {
            text = """
                使用说明
                1. 手机与电视连接同一个路由器 / Wi-Fi。
                2. 在电视设置中打开「ADB 调试」或「网络调试」。
                3. 点击检测；若 5555 已开放，再点击连接授权。
                4. 电视出现「允许 USB 调试？」时，勾选「一律允许」并确认。
                5. 只有当前 ADB 已连接且获授权时，App 才能尝试执行 setprop 并重启 adbd。

                重要限制
                • App 不能在 ADB 完全关闭时，隔空强行打开电视的 ADB；Android 的安全机制不允许普通 App 绕过电视端授权。
                • Android 11 及更新系统的「无线调试」配对模式可能使用动态配对端口和配对码；本版本先支持传统 TCP 5555，不会伪称已完成新式配对。
                • USB-C OTG 转 USB-A 连接电视 USB-A，通常是 Host 对 Host，不能代替 USB 调试线。若电视另有专用 USB Device/Service 口，需按该机型说明确认。
                • TCP 5555 开启后会扩大局域网控制面，建议仅在可信家庭网络使用，不用时关闭电视调试。
            """.trimIndent()
            textSize = 13f
            setPadding(0, pad, 0, pad)
        })

        setContentView(scroll)
    }

    private fun addButton(parent: LinearLayout, label: String): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = (resources.displayMetrics.density * 8).toInt()
        })
        return button
    }

    private fun targetIp(): String? {
        val ip = ipInput.text.toString().trim()
        val valid = Regex("""^(\d{1,3}\.){3}\d{1,3}$""").matches(ip) &&
            ip.split(".").all { it.toIntOrNull() in 0..255 }
        if (!valid) {
            status.text = "请输入有效的电视 IPv4 地址。"
            return null
        }
        App.get().prefs.tvIp = ip
        return ip
    }

    private fun setBusy(busy: Boolean, message: String) {
        status.text = message
        checkButton.isEnabled = !busy
        authorizeButton.isEnabled = !busy
        enableButton.isEnabled = !busy
    }

    private fun runCheck() {
        val ip = targetIp() ?: return
        setBusy(true, "正在检测 $ip:5555 …")
        lifecycleScope.launch {
            val open = withContext(Dispatchers.IO) { TvAdb.probePort(ip, timeoutMs = 1800) }
            setBusy(false, if (open) {
                "检测结果：TCP 5555 已开放。下一步点击「连接并请求电视授权」。"
            } else {
                "检测结果：TCP 5555 未开放。请先在电视设置中开启 ADB / 网络调试。仅靠手机 App 或这根 OTG 线，无法在 ADB 关闭时远程开启它。"
            })
        }
    }

    private fun requestAuthorization() {
        val ip = targetIp() ?: return
        setBusy(true, "正在连接电视；如果电视弹出授权框，请用遥控器勾选「一律允许」并确认。")
        lifecycleScope.launch {
            try {
                val client = TvAdb.ensureAuthorized(
                    applicationContext, ip, totalWindowMs = 90_000,
                    hint = { message -> runOnUiThread { status.text = message } },
                )
                val result = withContext(Dispatchers.IO) {
                    String(client.exec("echo MITV_ADB_AUTH_OK"), Charsets.UTF_8).trim()
                }
                setBusy(false, if (result.contains("MITV_ADB_AUTH_OK")) {
                    "授权成功：电视 ADB 已可用。现在可以使用原有应用管理、儿童锁和远程看屏功能。"
                } else {
                    "已连接，但授权探针返回异常：$result"
                })
            } catch (e: Exception) {
                setBusy(false, "连接失败：${e.message ?: e.javaClass.simpleName}\n请确认电视已开启 TCP 5555、手机与电视在同一局域网。")
            }
        }
    }

    private fun enableTcpAdb() {
        val ip = targetIp() ?: return
        setBusy(true, "先检查现有 ADB 通道。此操作仅在电视已开放并授权 5555 时可执行。")
        lifecycleScope.launch {
            try {
                val open = withContext(Dispatchers.IO) { TvAdb.probePort(ip) }
                if (!open) throw IOException("电视 TCP 5555 当前关闭；没有现存 ADB 通道，App 无法远程执行开启命令。请先在电视菜单中打开 ADB / 网络调试。")
                val client = TvAdb.ensureAuthorized(
                    applicationContext, ip, totalWindowMs = 60_000,
                    hint = { message -> runOnUiThread { status.text = message } },
                )
                val property = withContext(Dispatchers.IO) {
                    String(client.exec("setprop service.adb.tcp.port 5555; getprop service.adb.tcp.port"), Charsets.UTF_8).trim()
                }
                if (property != "5555") {
                    throw IOException("电视未接受 service.adb.tcp.port 属性（返回：${property.ifBlank { "空" }}）。部分量产固件禁止普通 shell 修改该属性。")
                }
                withContext(Dispatchers.IO) {
                    runCatching { client.exec("stop adbd; start adbd") }
                    TvAdb.reset()
                    delay(2200)
                }
                val reopened = withContext(Dispatchers.IO) { TvAdb.probePort(ip, timeoutMs = 3500) }
                setBusy(false, if (reopened) {
                    "操作完成：电视 TCP 5555 已重新开放。原有功能可继续使用。"
                } else {
                    "已发送 adbd 重启命令，但暂未检测到 5555。可能是电视固件限制或 adbd 重启失败，请在电视端检查 ADB 设置。"
                })
            } catch (e: Exception) {
                TvAdb.reset()
                setBusy(false, "未能开启 TCP 5555：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
