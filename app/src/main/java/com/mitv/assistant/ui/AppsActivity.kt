package com.mitv.assistant.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mitv.assistant.App
import com.mitv.assistant.R
import com.mitv.assistant.adb.AdbClient
import com.mitv.assistant.adb.AdbUnauthorizedException
import com.mitv.assistant.adb.TvAdb
import com.mitv.assistant.adb.TvOps
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表行数据 */
data class AppRow(
    val pkg: String,
    val alias: String?,
    val isSystem: Boolean,
    val guarded: Boolean,
    var enabled: Boolean,
    var checked: Boolean,
)

/**
 * 应用管理 / 儿童锁。
 *
 * 通过 ADB 的 `pm disable-user --user 0 <pkg>` 禁用、`pm enable <pkg>` 启用；
 * 禁用是持久的，重启电视仍生效，随时可恢复。
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val allRows = mutableListOf<AppRow>()
    private val visibleRows = mutableListOf<AppRow>()

    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private var includeSystem = false

    /** 安装 APK 的文件选择器 */
    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installPickedApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        statusText = findViewById(R.id.statusText)
        progress = findViewById(R.id.progress)

        adapter = AppAdapter(visibleRows) { row -> toggleApp(row) }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@AppsActivity)
            adapter = this@AppsActivity.adapter
        }

        findViewById<CheckBox>(R.id.checkSystemApps).setOnCheckedChangeListener { _, checked ->
            includeSystem = checked
            if (allRows.isNotEmpty()) refreshVisible() else loadApps()
        }

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener { loadApps() }
        findViewById<MaterialButton>(R.id.btnApplyKids).setOnClickListener { applyKidsLock() }
        findViewById<MaterialButton>(R.id.btnRestoreAll).setOnClickListener { restoreAll() }

        // 运维工具条
        findViewById<View>(R.id.btnToolInfo).setOnClickListener { toolTvInfo() }
        findViewById<View>(R.id.btnToolInstall).setOnClickListener {
            apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
        }
        findViewById<View>(R.id.btnToolCache).setOnClickListener { toolClearCache() }
        findViewById<View>(R.id.btnToolReboot).setOnClickListener { toolReboot() }

        if (intent.getBooleanExtra("focus_tools", false)) {
            statusText.text = "提示：上方工具条包含 电视信息 / 安装APK / 清理缓存 / 重启电视"
        }

        findViewById<TextView>(R.id.searchBox).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshVisible()
        })

        // 预勾选白名单
        loadApps()
    }

    // ------------------------------------------------------------------ 数据加载

    private fun adbErrorText(t: Throwable?): String =
        getString(R.string.toast_send_failed, t?.message ?: "未知错误") + "\n\n" + getString(R.string.adb_required_tip)

    private fun loadApps() {
        val ip = App.get().prefs.tvIp ?: run { finish(); return }
        statusText.text = getString(R.string.loading_apps)
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                // 授权门禁：未放行时保持电视端弹窗并等待用户确认
                TvAdb.ensureAuthorized(applicationContext, ip, hint = { msg ->
                    runOnUiThread { statusText.text = msg }
                })
                val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, ip) }
                val rows = withContext(Dispatchers.IO) { fetchRows(client) }
                allRows.clear()
                allRows.addAll(rows)
                progress.visibility = View.GONE
                statusText.text = "共 ${rows.size} 个应用，其中已禁用 ${rows.count { !it.enabled }} 个"
                refreshVisible()
            } catch (t: Throwable) {
                progress.visibility = View.GONE
                statusText.text = if (t is AdbUnauthorizedException) t.message!!
                else adbErrorText(t)
            }
        }
    }

    private fun fetchRows(client: AdbClient): List<AppRow> {
        fun listCmd(cmd: String): Set<String> =
            String(client.exec(cmd), Charsets.UTF_8).lineSequence()
                .mapNotNull { line -> line.trim().takeIf { it.startsWith("package:") }?.removePrefix("package:") }
                .toSet()

        val users = listCmd("pm list packages -3")
        val all = if (includeSystem) {
            listCmd("pm list packages") - HIDDEN_GUARDS
        } else {
            users.filterNot { it in HIDDEN_GUARDS }.toSet()
        }
        val disabled = listCmd("pm list packages -d")
        val whitelist = App.get().prefs.whitelist

        return all.sorted().map { pkg ->
            AppRow(
                pkg = pkg,
                alias = App.get().prefs.aliasFor(pkg),
                isSystem = pkg !in users,
                guarded = isGuardedHome(pkg),
                enabled = pkg !in disabled,
                checked = whitelist.contains(pkg),
            )
        }
    }

    /** 电视桌面/系统UI这类组件一旦禁用可能导致无法操作电视 */
    private fun isGuardedHome(pkg: String): Boolean =
        HOME_PATTERNS.any { it.containsMatchIn(pkg) }

    private fun refreshVisible() {
        val query = (findViewById<View>(R.id.searchBox) as TextView).text.toString().trim().lowercase()
        visibleRows.clear()
        visibleRows.addAll(
            allRows.filter { r ->
                query.isBlank() || r.pkg.lowercase().contains(query) || r.alias?.lowercase()?.contains(query) == true
            },
        )
        adapter.notifyDataSetChanged()
    }

    // ------------------------------------------------------------------ 操作

    private fun toggleApp(row: AppRow) {
        if (row.guarded && row.enabled) {
            MaterialAlertDialogBuilder(this)
                .setTitle("受保护的组件")
                .setMessage("${row.pkg}\n\n这是疑似电视桌面/关键组件，禁用后可能需要恢复出厂才能修复。确定要继续吗？")
                .setPositiveButton(R.string.confirm) { _, _ -> execute(listOf(opDisable(row.pkg))) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else if (row.enabled) {
            execute(listOf(opDisable(row.pkg)))
        } else {
            execute(listOf(opEnable(row.pkg)))
        }
    }

    private fun applyKidsLock() {
        val checkedSet = visibleRows.filter { it.checked }.map { it.pkg }.toMutableSet()
        // 白名单为全部勾选集合，而不仅可见列表：避免搜索过滤导致误删白名单
        allRows.filter { it.checked }.forEach { checkedSet.add(it.pkg) }

        val toDisable = allRows.filter { !it.isSystem && !it.guarded && !it.checked && it.enabled }
        val toEnable = allRows.filter { it.checked && !it.enabled && !it.guarded }

        if (toDisable.isEmpty() && toEnable.isEmpty()) {
            Toast.makeText(this, "没有需要变更的应用", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apply_kids)
            .setMessage("将禁用 ${toDisable.size} 个未勾选的第三方应用，并启用 ${toEnable.size} 个勾选应用。确认执行？")
            .setPositiveButton(R.string.confirm) { _, _ ->
                persistWhitelist(checkedSet)
                execute(toDisable.map { opDisable(it.pkg) } + toEnable.map { opEnable(it.pkg) })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreAll() {
        val toEnable = allRows.filter { !it.enabled && !it.guarded }
        if (toEnable.isEmpty()) {
            Toast.makeText(this, "当前列表中没有已禁用的应用", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_all)
            .setMessage("将重新启用 ${toEnable.size} 个应用。确认？")
            .setPositiveButton(R.string.confirm) { _, _ ->
                execute(toEnable.map { opEnable(it.pkg) })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persistWhitelist(set: MutableSet<String>) {
        App.get().prefs.whitelist = LinkedHashSet(set)
    }

    private data class ShellOp(val desc: String, val command: String, val enable: Boolean)

    private fun opDisable(pkg: String) = ShellOp("禁用 $pkg", "pm disable-user --user 0 $pkg", false)
    private fun opEnable(pkg: String) = ShellOp("启用 $pkg", "pm enable $pkg", true)

    /** 顺序执行 shell 操作并在 UI 反馈进度；结束后重载状态。 */
    private fun execute(ops: List<ShellOp>) {
        val ip = App.get().prefs.tvIp ?: return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                TvAdb.ensureAuthorized(applicationContext, ip, totalWindowMs = 60_000, hint = { msg ->
                    runOnUiThread { statusText.text = msg }
                })
            } catch (t: Throwable) {
                progress.visibility = View.GONE
                Toast.makeText(this@AppsActivity, t.message ?: "未获得授权", Toast.LENGTH_LONG).show()
                return@launch
            }
            var okCount = 0
            val failures = mutableListOf<String>()
            try {
                val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, ip) }
                withContext(Dispatchers.IO) {
                    ops.forEachIndexed { index, op ->
                        runOnUiThread {
                            statusText.text = "(${index + 1}/${ops.size}) ${op.desc}"
                        }
                        try {
                            val out = String(client.exec(op.command), Charsets.UTF_8)
                            when {
                                out.contains("Error", true) || out.contains("Exception", true) ->
                                    failures += "${op.desc}: ${out.lineSequence().firstOrNull { it.isNotBlank() } ?: "失败"}"
                                else -> okCount++
                            }
                        } catch (e: Exception) {
                            failures += "${op.desc}: ${e.message}"
                            throw e
                        }
                    }
                }
            } catch (_: Exception) {
                // 连接中断则终止后续
            }
            progress.visibility = View.GONE
            Toast.makeText(
                this@AppsActivity,
                if (failures.isEmpty()) "完成：成功 $okCount 项" else "部分失败（${failures.size}），请刷新后查看状态",
                Toast.LENGTH_LONG,
            ).show()
            loadApps()
        }
    }

    // ------------------------------------------------------------------ 运维工具

    private fun gate(block: suspend () -> Unit) {
        val ip = App.get().prefs.tvIp ?: return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                TvAdb.ensureAuthorized(applicationContext, ip, totalWindowMs = 90_000, hint = { msg ->
                    runOnUiThread { statusText.text = msg }
                })
                block()
            } catch (t: Throwable) {
                statusText.text = t.message ?: "操作失败"
                Toast.makeText(this@AppsActivity, getString(R.string.tools_need_adb), Toast.LENGTH_LONG).show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun toolTvInfo() = gate {
        val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, App.get().prefs.tvIp!!) }
        val info = withContext(Dispatchers.IO) { TvOps.fetchInfo(client) }
        MaterialAlertDialogBuilder(this)
            .setTitle("电视信息")
            .setMessage(info.asMonoText())
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun toolClearCache() = gate {
        val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, App.get().prefs.tvIp!!) }
        withContext(Dispatchers.IO) { TvOps.clearCaches(client) }
        statusText.text = getString(R.string.cache_cleared)
    }

    private fun toolReboot() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.reboot_confirm_title))
            .setMessage(getString(R.string.reboot_confirm_msg))
            .setPositiveButton(R.string.confirm) { _, _ ->
                gate {
                    val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, App.get().prefs.tvIp!!) }
                    withContext(Dispatchers.IO) { TvOps.reboot(client) }
                    TvAdb.reset()
                    statusText.text = getString(R.string.reboot_sent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun installPickedApk(uri: android.net.Uri) = gate {
        val bytes = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: run {
            statusText.text = "读取 APK 失败"
            return@gate
        }
        val dialogView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, null)
        val percentText = dialogView.findViewById<TextView>(android.R.id.text1)
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tool_install_apk))
            .setView(dialogView)
            .setCancelable(false)
            .show()

        try {
            val client = withContext(Dispatchers.IO) { TvAdb.acquire(applicationContext, App.get().prefs.tvIp!!) }
            withContext(Dispatchers.IO) {
                TvOps.installApk(client, bytes) { p ->
                    runOnUiThread { percentText.text = getString(R.string.install_running, p.coerceIn(0, 100)) }
                }
            }
            percentText.text = getString(R.string.install_success)
            kotlinx.coroutines.delay(600)
        } catch (t: Throwable) {
            Toast.makeText(
                this@AppsActivity,
                getString(R.string.install_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        } finally {
            dlg.dismiss()
        }
    }

    // ------------------------------------------------------------------ 列表适配器

    inner class AppAdapter(
        private val rows: List<AppRow>,
        private val onToggle: (AppRow) -> Unit,
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.checkWhitelist)
            val label: TextView = v.findViewById(R.id.appLabel)
            val pkgView: TextView = v.findViewById(R.id.appPackage)
            val badge: TextView = v.findViewById(R.id.stateBadge)
            val toggle: MaterialButton = v.findViewById(R.id.btnToggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.checked
            holder.check.setOnCheckedChangeListener { _, checked -> row.checked = checked }

            holder.label.text = buildString {
                append(row.alias ?: row.pkg.substringAfterLast('.'))
                if (row.isSystem) append(" · 系统")
                if (row.guarded) append(" · 受保护")
            }
            holder.pkgView.text = row.pkg

            if (!row.enabled) {
                holder.badge.text = getString(R.string.disabled)
                holder.badge.setTextColor(ContextCompat.getColor(this@AppsActivity, R.color.warn_red))
                holder.toggle.text = getString(R.string.enabled)
            } else {
                holder.badge.text = getString(R.string.enabled)
                holder.badge.setTextColor(ContextCompat.getColor(this@AppsActivity, R.color.ok_green))
                holder.toggle.text = getString(R.string.disabled)
            }

            holder.toggle.setOnClickListener { onToggle(row) }

            // 长按设置备注名
            holder.itemView.setOnLongClickListener {
                showAliasDialog(row)
                true
            }
        }
    }

    private fun showAliasDialog(row: AppRow) {
        val edit = android.widget.EditText(this).apply { setSingleLine(true); setText(row.alias ?: "") }
        MaterialAlertDialogBuilder(this)
            .setTitle("备注名（留空清除）")
            .setMessage(row.pkg)
            .setView(edit)
            .setPositiveButton(R.string.confirm) { _, _ ->
                App.get().prefs.setAlias(row.pkg, edit.text.toString().trim().ifBlank { null })
                loadApps()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        /** 完全隐藏的系统关键包 */
        private val HIDDEN_GUARDS = setOf("android", "com.android.systemui", "com.android.providers.settings")

        /** 需二次确认的疑似桌面/启动器模式 */
        private val HOME_PATTERNS = listOf(
            Regex("""(?i)(launcher|tvhome|tvmain|mitv\.tv|patchwall)"""),
        )
    }
}
