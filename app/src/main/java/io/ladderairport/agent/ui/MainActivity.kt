package io.ladderairport.agent.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.R
import io.ladderairport.agent.databinding.ActivityMainBinding
import io.ladderairport.agent.databinding.PageConfigBinding
import io.ladderairport.agent.databinding.PageDashboardBinding
import io.ladderairport.agent.databinding.PageLogsBinding
import io.ladderairport.agent.mobile.Mobile
import io.ladderairport.agent.model.AgentStatus
import io.ladderairport.agent.service.AgentService
import io.ladderairport.agent.util.QrCodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardBinding: PageDashboardBinding
    private lateinit var configBinding: PageConfigBinding
    private lateinit var logsBinding: PageLogsBinding

    private val prefs by lazy { LadderApplication.instance.prefs }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "通知权限未授予，前台服务通知将无法展示", Toast.LENGTH_SHORT).show()
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQrContent(result.contents)
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchQrScanner()
        } else {
            Toast.makeText(this, "需要相机权限以扫描配对二维码", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dashboardBinding = PageDashboardBinding.inflate(layoutInflater)
        configBinding = PageConfigBinding.inflate(layoutInflater)
        logsBinding = PageLogsBinding.inflate(layoutInflater)

        setupNavigation()
        initDashboard()
        initConfig()
        initLogs()
        observeData()
        checkPermissions()
    }

    private fun setupNavigation() {
        val adapter = MainPagerAdapter(dashboardBinding, configBinding, logsBinding)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 2

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> binding.viewPager.currentItem = 0
                R.id.nav_config -> binding.viewPager.currentItem = 1
                R.id.nav_logs -> binding.viewPager.currentItem = 2
            }
            true
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> binding.bottomNav.selectedItemId = R.id.nav_dashboard
                    1 -> binding.bottomNav.selectedItemId = R.id.nav_config
                    2 -> binding.bottomNav.selectedItemId = R.id.nav_logs
                }
            }
        })
    }

    private fun initDashboard() {
        try {
            val agentVer = Mobile.getVersion()
            val singboxVer = Mobile.getSingboxVersion()
            dashboardBinding.tvCoreVersion.text = "sing-box $singboxVer"
            binding.tvAppSubtitle.text = "Agent $agentVer"
        } catch (_: Exception) {
            dashboardBinding.tvCoreVersion.text = "sing-box runtime"
        }

        updateDashboardConfigDisplay()

        dashboardBinding.btnPower.setOnClickListener {
            val isRunning = AgentService.isServiceRunning.value == true
            if (isRunning) {
                AgentService.stop(this)
            } else {
                if (!validateInputs()) {
                    binding.viewPager.currentItem = 1
                    return@setOnClickListener
                }
                saveInputsToPrefs()
                AgentService.start(this)
            }
        }

        dashboardBinding.tvBatteryStatus.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun initConfig() {
        configBinding.etPanelUrl.setText(prefs.panelUrl)
        configBinding.etNodeId.setText(prefs.nodeId)
        configBinding.etToken.setText(prefs.token)
        configBinding.switchAutoStart.isChecked = prefs.autoStart

        configBinding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoStart = isChecked
        }

        configBinding.btnScanQrCode.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        configBinding.btnSaveConfig.setOnClickListener {
            saveInputsToPrefs()
            updateDashboardConfigDisplay()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        configBinding.btnEnroll.setOnClickListener {
            performEnrollment(autoStartAfter = false)
        }

        configBinding.layoutBatteryOpt.setOnClickListener {
            requestBatteryOptimizationExemption()
        }
    }

    private fun initLogs() {
        logsBinding.btnClearLogs.setOnClickListener {
            LadderApplication.clearLogs()
        }

        logsBinding.btnCopyLogs.setOnClickListener {
            val lines = LadderApplication.logLines.value ?: emptyList()
            if (lines.isEmpty()) {
                Toast.makeText(this, "当前无日志", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LadderAirport Logs", lines.joinToString("\n")))
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboardConfigDisplay() {
        val nodeId = prefs.nodeId
        val panelUrl = prefs.panelUrl
        if (nodeId.isNotBlank()) {
            dashboardBinding.tvHeroNodeName.text = nodeId
            dashboardBinding.tvHeroPanelUrl.text = panelUrl
        } else {
            dashboardBinding.tvHeroNodeName.text = "未配置节点"
            dashboardBinding.tvHeroPanelUrl.text = "请切换至「配置」页面填入或扫码导入信息"
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("请对准 Panel 节点配对二维码")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        scanQrLauncher.launch(options)
    }

    private fun handleScannedQrContent(rawText: String) {
        val info = QrCodeParser.parse(rawText)
        if (info == null) {
            AlertDialog.Builder(this)
                .setTitle("二维码无效")
                .setMessage("未能识别有效的 LadderAirport 配对参数：\n\n$rawText")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        // Fill inputs
        configBinding.etPanelUrl.setText(info.panelUrl)
        configBinding.etNodeId.setText(info.nodeId)
        configBinding.etToken.setText(info.token)
        saveInputsToPrefs()
        updateDashboardConfigDisplay()

        AlertDialog.Builder(this)
            .setTitle("扫码配对成功")
            .setMessage("已识别节点配置：\n\nPanel: ${info.panelUrl}\n节点 ID: ${info.nodeId}\n\n是否立即一键注册 (Enroll) 并启动 Agent？")
            .setPositiveButton("注册并启动") { _, _ ->
                performEnrollment(autoStartAfter = true)
            }
            .setNegativeButton("仅保存配置") { _, _ ->
                binding.viewPager.currentItem = 0
            }
            .show()
    }

    private fun observeData() {
        AgentService.isServiceRunning.observe(this) { isRunning ->
            updateRunningState(isRunning)
        }

        AgentService.currentStatus.observe(this) { status ->
            updateStatusDetails(status)
        }

        LadderApplication.logLines.observe(this) { lines ->
            logsBinding.tvTerminalOutput.text = if (lines.isEmpty()) {
                "等待日志输出..."
            } else {
                lines.joinToString("\n")
            }
            logsBinding.tvLogCount.text = "${lines.size} lines"
            logsBinding.scrollLogs.post {
                logsBinding.scrollLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateRunningState(isRunning: Boolean) {
        if (isRunning) {
            // Dashboard Hero
            dashboardBinding.pillStatus.setBackgroundColor(getColor(R.color.status_running_bg))
            dashboardBinding.dotStatus.setBackgroundColor(getColor(R.color.status_running))
            dashboardBinding.tvStatusText.text = "运行中"
            dashboardBinding.tvStatusText.textColor(R.color.status_running)

            dashboardBinding.btnPower.text = "停止 Agent 服务"
            dashboardBinding.btnPower.backgroundTintList = getColorStateList(R.color.status_error)

            // Top Bar
            binding.topStatusBadge.setBackgroundColor(getColor(R.color.status_running_bg))
            binding.topStatusDot.setBackgroundColor(getColor(R.color.status_running))
            binding.topStatusText.text = "在线"
            binding.topStatusText.textColor(R.color.status_running)

            // Disable edits during run
            configBinding.etPanelUrl.isEnabled = false
            configBinding.etNodeId.isEnabled = false
            configBinding.etToken.isEnabled = false
            configBinding.btnEnroll.isEnabled = false
            configBinding.cardQrPairing.visibility = View.GONE
        } else {
            // Dashboard Hero
            dashboardBinding.pillStatus.setBackgroundColor(getColor(R.color.status_stopped_bg))
            dashboardBinding.dotStatus.setBackgroundColor(getColor(R.color.status_stopped))
            dashboardBinding.tvStatusText.text = "已停止"
            dashboardBinding.tvStatusText.textColor(R.color.status_stopped)

            dashboardBinding.btnPower.text = "启动 Agent"
            dashboardBinding.btnPower.backgroundTintList = getColorStateList(R.color.primary)

            // Top Bar
            binding.topStatusBadge.setBackgroundColor(getColor(R.color.status_stopped_bg))
            binding.topStatusDot.setBackgroundColor(getColor(R.color.status_stopped))
            binding.topStatusText.text = "已停止"
            binding.topStatusText.textColor(R.color.status_stopped)

            // Enable edits
            configBinding.etPanelUrl.isEnabled = true
            configBinding.etNodeId.isEnabled = true
            configBinding.etToken.isEnabled = true
            configBinding.btnEnroll.isEnabled = true
            configBinding.cardQrPairing.visibility = View.VISIBLE
        }
    }

    private fun updateStatusDetails(status: AgentStatus) {
        dashboardBinding.tvMetricUplink.text = formatBytes(status.uplinkBytes)
        dashboardBinding.tvMetricDownlink.text = formatBytes(status.downlinkBytes)
        dashboardBinding.tvMetricConns.text = status.connections.toString()
        dashboardBinding.tvMetricUptime.text = formatUptime(status.uptimeSecs)

        if (status.lastError.isNotBlank() && !status.running) {
            dashboardBinding.tvHeroError.visibility = View.VISIBLE
            dashboardBinding.tvHeroError.text = "异常: ${status.lastError}"
            dashboardBinding.pillStatus.setBackgroundColor(getColor(R.color.status_error_bg))
            dashboardBinding.dotStatus.setBackgroundColor(getColor(R.color.status_error))
            dashboardBinding.tvStatusText.text = "运行异常"
            dashboardBinding.tvStatusText.textColor(R.color.status_error)

            binding.topStatusBadge.setBackgroundColor(getColor(R.color.status_error_bg))
            binding.topStatusDot.setBackgroundColor(getColor(R.color.status_error))
            binding.topStatusText.text = "异常"
            binding.topStatusText.textColor(R.color.status_error)
        } else {
            dashboardBinding.tvHeroError.visibility = View.GONE
        }
    }

    private fun validateInputs(): Boolean {
        val url = configBinding.etPanelUrl.text?.toString()?.trim() ?: ""
        val nodeId = configBinding.etNodeId.text?.toString()?.trim() ?: ""
        val token = configBinding.etToken.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            configBinding.etPanelUrl.error = "请输入 Panel 基础地址"
            return false
        }
        if (nodeId.isBlank()) {
            configBinding.etNodeId.error = "请输入节点 ID"
            return false
        }
        if (token.isBlank()) {
            configBinding.etToken.error = "请输入令牌"
            return false
        }
        return true
    }

    private fun saveInputsToPrefs() {
        prefs.panelUrl = configBinding.etPanelUrl.text?.toString()?.trim() ?: ""
        prefs.nodeId = configBinding.etNodeId.text?.toString()?.trim() ?: ""
        prefs.token = configBinding.etToken.text?.toString()?.trim() ?: ""
    }

    private fun performEnrollment(autoStartAfter: Boolean = false) {
        if (!validateInputs()) {
            binding.viewPager.currentItem = 1
            return
        }
        saveInputsToPrefs()
        updateDashboardConfigDisplay()

        val progress = AlertDialog.Builder(this)
            .setTitle("正在注册节点")
            .setMessage("正在生成密钥与证书请求 (含 SAN) 并向 Panel 申请管理证书...")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enrollJson = JSONObject().apply {
                    put("panel_url", prefs.panelUrl)
                    put("node_id", prefs.nodeId)
                    put("enroll_token", prefs.token)
                    put("data_dir", filesDir.absolutePath)
                }.toString()

                val resJson = Mobile.enroll(enrollJson)
                val resObj = JSONObject(resJson)

                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    if (resObj.optBoolean("ok", false)) {
                        val issuedToken = resObj.optString("token", "")
                        if (issuedToken.isNotBlank()) {
                            prefs.token = issuedToken
                            configBinding.etToken.setText(issuedToken)
                        }
                        LadderApplication.appendLog("节点注册成功：证书与私钥已保存在沙箱")

                        if (autoStartAfter) {
                            Toast.makeText(this@MainActivity, "注册成功，正在启动 Agent...", Toast.LENGTH_SHORT).show()
                            binding.viewPager.currentItem = 0
                            AgentService.start(this@MainActivity)
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("注册成功")
                                .setMessage("管理证书已签发并保存在应用私有沙箱中，现在可以启动 Agent！")
                                .setPositiveButton("立即前往仪表盘") { _, _ ->
                                    binding.viewPager.currentItem = 0
                                }
                                .setNegativeButton("关闭", null)
                                .show()
                        }
                    } else {
                        val errMsg = resObj.optString("error", "未知注册失败")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("注册失败 (HTTP 400/500)")
                            .setMessage(errMsg)
                            .setPositiveButton("确定", null)
                            .show()
                        LadderApplication.appendLog("注册失败: $errMsg")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("注册异常")
                        .setMessage(e.message ?: "网络连接异常")
                        .setPositiveButton("确定", null)
                        .show()
                    LadderApplication.appendLog("注册异常: ${e.message}")
                }
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this, "已获取忽略电池优化白名单", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format("%.2f GB", gb)
    }

    private fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%dh %02dm %02ds", hrs, mins, secs)
        } else if (mins > 0) {
            String.format("%dm %02ds", mins, secs)
        } else {
            "${secs}s"
        }
    }

    private fun android.widget.TextView.textColor(resId: Int) {
        setTextColor(getColor(resId))
    }
}
