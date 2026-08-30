package com.mitv.assistant.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mitv.assistant.App
import com.mitv.assistant.R
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.remote.RemoteController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程看屏：ADB screencap 截图按所选频率刷新（截图流，非实时视频）。
 */
class CastActivity : AppCompatActivity() {

    private lateinit var screenView: ImageView
    private lateinit var waitingText: TextView
    private lateinit var latencyText: TextView
    private lateinit var btnToggle: MaterialButton

    private var previewJob: Job? = null
    private var lastBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast)

        screenView = findViewById(R.id.screenView)
        waitingText = findViewById(R.id.waitingText)
        latencyText = findViewById(R.id.latencyText)
        btnToggle = findViewById(R.id.btnTogglePreview)

        btnToggle.setOnClickListener { if (previewJob?.isActive == true) stopPreview() else startPreview() }
        findViewById<MaterialButton>(R.id.btnSnapshot).setOnClickListener { saveSnapshot() }

        val ip = App.get().prefs.tvIp
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, R.string.current_tv_none, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun periodMs(): Long = when {
        findViewById<android.widget.RadioButton>(R.id.rbFps4).isChecked -> 250L
        findViewById<android.widget.RadioButton>(R.id.rbFps2).isChecked -> 500L
        else -> 1000L
    }

    private fun startPreview() {
        val ip = App.get().prefs.tvIp ?: return
        waitingText.visibility = View.GONE
        btnToggle.isEnabled = false
        latencyText.text = "正在请求电视放行调试权限…"

        lifecycleScope.launch {
            try {
                // 授权门禁：保证开始取屏前电视已放行
                TvAdb.ensureAuthorized(applicationContext, ip, totalWindowMs = 120_000, hint = { msg ->
                    runOnUiThread { latencyText.text = msg }
                })
                launchPreviewJob(ip)
            } catch (t: Throwable) {
                latencyText.text = ""
                waitingText.visibility = View.VISIBLE
                Toast.makeText(this@CastActivity, t.message ?: "未获得授权", Toast.LENGTH_LONG).show()
            } finally {
                btnToggle.isEnabled = true
            }
        }
    }

    private fun launchPreviewJob(ip: String) {
        btnToggle.text = getString(R.string.cast_stop)
        previewJob = lifecycleScope.launch(Dispatchers.IO) {
            var consecutiveErrors = 0
            while (coroutineContext.isActive && isActive) {
                val t0 = System.currentTimeMillis()
                val result = RemoteController.screencap(applicationContext, ip)
                val elapsed = System.currentTimeMillis() - t0
                result.fold(
                    onSuccess = { bmp ->
                        consecutiveErrors = 0
                        lastBitmap = bmp
                        runOnUiThread {
                            screenView.setImageBitmap(bmp)
                            latencyText.text = "单帧耗时 ${elapsed}ms"
                        }
                    },
                    onFailure = { err ->
                        consecutiveErrors++
                        runOnUiThread { latencyText.text = "错误：${err.message}" }
                    },
                )
                if (consecutiveErrors >= 4) break
                val wait = (periodMs() - elapsed).coerceAtLeast(60)
                delay(wait)
            }
            launch(Dispatchers.Main) { stopPreview("预览已停止（连续多次失败，请检查 ADB 授权）") }
        }
    }

    private fun stopPreview(message: String? = null) {
        previewJob?.cancel()
        previewJob = null
        btnToggle.text = getString(R.string.cast_start)
        waitingText.visibility = View.VISIBLE
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun saveSnapshot() {
        val bmp = lastBitmap ?: run {
            lifecycleScope.launch(Dispatchers.IO) {
                val ip = App.get().prefs.tvIp ?: return@launch
                RemoteController.screencap(applicationContext, ip).fold(
                    onSuccess = { fresh -> lastBitmap = fresh; persist(fresh) },
                    onFailure = { e -> toastMain(e.message ?: "截屏失败") },
                )
            }
            return
        }
        persist(bmp)
    }

    private fun persist(bmp: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "MiTvScreenshots",
                ).apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "MITV_$stamp.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toastMain(getString(R.string.cast_saved) + "：" + file.absolutePath)
            } catch (e: Exception) {
                toastMain("保存失败：${e.message}")
            }
        }
    }

    private fun toastMain(msg: String) =
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        previewJob?.cancel()
    }
}
