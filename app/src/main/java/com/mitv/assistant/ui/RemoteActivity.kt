package com.mitv.assistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.mitv.assistant.App
import com.mitv.assistant.R
import com.mitv.assistant.net.MiTvHttp
import com.mitv.assistant.remote.RemoteController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RemoteActivity : AppCompatActivity() {

    private lateinit var ip: String
    private lateinit var favRow: LinearLayout
    private lateinit var volumeSlider: Slider
    private lateinit var volumeValueText: TextView
    private var httpOk = false

    /** 滑条由程序更新时抑制 feedback 回环 */
    @Volatile private var sliderBusyFromCode = false

    private val allKeys: Map<Int, RemoteController.TvKey> by lazy {
        mapOf(
            R.id.btnPower to RemoteController.TvKey.POWER,
            R.id.btnMute to RemoteController.TvKey.MUTE,
            R.id.btnMenu to RemoteController.TvKey.MENU,
            R.id.btnHome to RemoteController.TvKey.HOME,
            R.id.btnBack to RemoteController.TvKey.BACK,
            R.id.btnVolUp to RemoteController.TvKey.VOLUME_UP,
            R.id.btnVolDown to RemoteController.TvKey.VOLUME_DOWN,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)

        val saved = App.get().prefs.tvIp
        if (saved.isNullOrBlank()) {
            Toast.makeText(this, R.string.current_tv_none, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        ip = saved
        findViewById<TextView>(R.id.targetText).text = "目标：$ip"

        allKeys.forEach { (viewId, key) ->
            findViewById<View>(viewId).setOnClickListener { fire(key) }
        }

        volumeSlider = findViewById(R.id.volumeSlider)
        volumeValueText = findViewById(R.id.volumeValueText)
        bindVolumeSlider()

        // 信号源快捷键：HTTP changesource，失败回退 ADB 键码
        findViewById<View>(R.id.btnSrcHdmi1).setOnClickListener { switchSource("hdmi1", 243) }
        findViewById<View>(R.id.btnSrcHdmi2).setOnClickListener { switchSource("hdmi2", 244) }
        findViewById<View>(R.id.btnSrcTv).setOnClickListener { switchSource("tv", 242) }

        setupTouchpad()

        findViewById<View>(R.id.btnSendText).setOnClickListener { showSendTextDialog() }
        favRow = findViewById(R.id.favRow)
        rebuildFavorites()

        lifecycleScope.launch(Dispatchers.IO) {
            httpOk = MiTvHttp.isAlive(ip)
            if (httpOk) runOnUiThread { refreshVolume(false) }
        }
    }

    // ------------------------------------------------------------------ 触控板

    /**
     * 手势映射：
     * 滑动 → 按位移阈值触发方向键（速度自适应节流）；
     * 单击 → OK；双击 → 返回；长按滑动中震感提示方向。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpad() {
        val pad = findViewById<View>(R.id.touchpad)
        val okPad = findViewById<MaterialButton>(R.id.btnOkPad)
        okPad.setOnClickListener { fire(RemoteController.TvKey.OK) }

        val origin = FloatArray(2)
        var downAt = 0L
        var lastFire = 0L
        var lastTapUp = 0L

        pad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    origin[0] = event.x; origin[1] = event.y; downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - origin[0]
                    val dy = event.y - origin[1]
                    val dist = Math.hypot(dx.toDouble(), dy.toDouble())
                    if (dist > THRESHOLD_PX && System.currentTimeMillis() - lastFire > REPEAT_MS) {
                        when {
                            Math.abs(dx) > Math.abs(dy) -> fire(
                                if (dx > 0) RemoteController.TvKey.RIGHT else RemoteController.TvKey.LEFT,
                            )
                            else -> fire(if (dy > 0) RemoteController.TvKey.DOWN else RemoteController.TvKey.UP)
                        }
                        vibrate()
                        // 以当前位置为原点继续累积（平滑漂移）
                        origin[0] = event.x; origin[1] = event.y
                        lastFire = System.currentTimeMillis()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dt = System.currentTimeMillis() - downAt
                    if (dt < TAP_MAX_MS) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUp < DOUBLE_TAP_MS) {
                            fire(RemoteController.TvKey.BACK)
                            vibrate()
                            lastTapUp = 0
                        } else {
                            lastTapUp = now
                            pad.postDelayed({ lastTapUp = 0 }, DOUBLE_TAP_MS.toLong())
                        }
                    }
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ 按键与音量

    private fun fire(key: RemoteController.TvKey) {
        vibrate()
        lifecycleScope.launch {
            RemoteController.sendKey(applicationContext, ip, key)
            if (key in VOL_KEYS) refreshVolume(delayFirst = true)
        }
    }

    /** 读取并展示音量（依赖 6095 直控）。allowWrite 控制是否把读数同步到滑条。 */
    private fun refreshVolume(delayFirst: Boolean, allowWrite: Boolean = true) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (delayFirst) Thread.sleep(320)
            val vol = MiTvHttp.getVolume(ip) ?: run {
                runOnUiThread { volumeValueText.text = "--" }
                return@launch
            }
            runOnUiThread {
                volumeValueText.text = "$vol"
                if (allowWrite) {
                    sliderBusyFromCode = true
                    volumeSlider.value = vol.coerceIn(0, 100).toFloat()
                    sliderBusyFromCode = false
                }
            }
        }
    }

    private fun bindVolumeSlider() {
        volumeSlider.addOnChangeListener { _, value, fromUser ->
            volumeValueText.text = "${value.toInt()}"
            if (!fromUser || sliderBusyFromCode || !httpOk) return@addOnChangeListener
            applyVolume(value.toInt())
        }
    }

    /** 直达音量：读取当前值并逐步逼近目标（每步 ±2 防止丢步） */
    private fun applyVolume(target: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var cur = MiTvHttp.getVolume(ip) ?: return@launch
                var guard = 0
                while (cur != target && guard < 60) {
                    val key = if (cur < target) "volumeup" else "volumedown"
                    val step = minOf(Math.abs(target - cur), 2)
                    repeat(step) { MiTvHttp.sendKey(ip, key) }
                    Thread.sleep(90)
                    guard += step
                    cur = if (guard % 8 == 0) (MiTvHttp.getVolume(ip) ?: break) else cur + if (cur < target) step else -step
                }
                runOnUiThread { volumeValueText.text = "$target" }
            } catch (_: Exception) {
            }
        }
    }

    private fun switchSource(source: String, fallbackKeycode: Int) {
        vibrate()
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = MiTvHttp.changeSource(ip, source)
            if (!ok) {
                RemoteController.sendRawKeycode(applicationContext, ip, fallbackKeycode)
            }
        }
    }

    // ------------------------------------------------------------------ 常用应用 / 文本 / 反馈

    private fun rebuildFavorites() {
        favRow.removeAllViews()
        val favorites = App.get().prefs.favorites

        favorites.forEachIndexed { index, pkg ->
            val chip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 12 }
                text = displayName(pkg)
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                setOnClickListener { launchPkg(pkg) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@RemoteActivity)
                        .setTitle("删除常用应用")
                        .setMessage(pkg)
                        .setPositiveButton(R.string.confirm) { _, _ ->
                            val list = App.get().prefs.favorites
                            if (index < list.size) list.removeAt(index)
                            App.get().prefs.favorites = list
                            rebuildFavorites()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
            }
            favRow.addView(chip)
        }

        val addBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            text = getString(R.string.add_app)
            isAllCaps = false
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener { showAddFavoriteDialog() }
        }
        favRow.addView(addBtn)
    }

    private fun displayName(pkg: String): String =
        App.get().prefs.aliasFor(pkg) ?: pkg.substringAfterLast('.').ifBlank { pkg }

    private fun launchPkg(pkg: String) {
        lifecycleScope.launch {
            RemoteController.launchApp(applicationContext, ip, pkg)
        }
    }

    private fun showAddFavoriteDialog() {
        val edit = EditText(this).apply { hint = "例如 com.pplive.androidphone.livtv"; setSingleLine(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle("添加电视上应用的包名")
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val pkg = edit.text.toString().trim()
                if (!PKG_NAME.matches(pkg)) {
                    Toast.makeText(this, "包名格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val list = App.get().prefs.favorites
                if (!list.contains(pkg)) {
                    list.add(pkg)
                    App.get().prefs.favorites = list
                }
                rebuildFavorites()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSendTextDialog() {
        val edit = EditText(this).apply { setSingleLine(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.text_dialog_title))
            .setMessage("将调用 ADB input text 注入当前焦点输入框。仅支持英文数字及符号。")
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty() || !ASCII.matches(text)) {
                    Toast.makeText(this, "仅支持英文数字及常见符号", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch { RemoteController.sendText(applicationContext, ip, text) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun vibrate() {
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(16, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        private const val THRESHOLD_PX = 30.0
        private const val REPEAT_MS = 150L
        private const val TAP_MAX_MS = 220L
        private const val DOUBLE_TAP_MS = 300
        private val VOL_KEYS = setOf(
            RemoteController.TvKey.VOLUME_UP,
            RemoteController.TvKey.VOLUME_DOWN,
            RemoteController.TvKey.MUTE,
        )
        private val PKG_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_\-]+)+$""")
        private val ASCII = Regex("""^[ -~]{1,120}$""")
    }
}
