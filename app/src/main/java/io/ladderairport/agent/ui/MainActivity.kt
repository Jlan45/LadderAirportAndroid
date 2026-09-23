package io.ladderairport.agent.ui

import android.Manifest
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ladderairport.agent.LadderApplication
import io.ladderairport.agent.R
import io.ladderairport.agent.databinding.ActivityMainBinding
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

        initViews()
        observeData()
        checkPermissions()
    }

    private fun initViews() {
        // Load versions
        try {
            val agentVer = Mobile.getVersion()
            val singboxVer = Mobile.getSingboxVersion()
            binding.tvVersion.text = "Agent $agentVer (sing-box $singboxVer)"
        } catch (_: Exception) {
            binding.tvVersion.text = "LadderAirport Android"
        }

        // Fill form fields
        binding.etPanelUrl.setText(prefs.panelUrl)
        binding.etNodeId.setText(prefs.nodeId)
        binding.etToken.setText(prefs.token)
        binding.switchAutoStart.isChecked = prefs.autoStart

        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoStart = isChecked
        }

        // Scan QR button
        binding.btnScanQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchQrScanner()
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Save button
        binding.btnSaveConfig.setOnClickListener {
            saveInputsToPrefs()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        // Enroll button
        binding.btnEnroll.setOnClickListener {
            performEnrollment(autoStartAfter = false)
        }

        // Start/Stop toggle button
        binding.btnToggleAgent.setOnClickListener {
            val isRunning = AgentService.isServiceRunning.value == true
            if (isRunning) {
                AgentService.stop(this)
            } else {
                if (!validateInputs()) return@setOnClickListener
                saveInputsToPrefs()
                AgentService.start(this)
            }
        }

        // Battery optimization
        binding.btnBatteryOpt.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        // Clear logs
        binding.btnClearLogs.setOnClickListener {
            LadderApplication.clearLogs()
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
        binding.etPanelUrl.setText(info.panelUrl)
        binding.etNodeId.setText(info.nodeId)
        binding.etToken.setText(info.token)
        saveInputsToPrefs()

        AlertDialog.Builder(this)
            .setTitle("扫码配对成功")
            .setMessage("已识别节点配置：\n\nPanel: ${info.panelUrl}\n节点 ID: ${info.nodeId}\n\n是否立即一键注册 (Enroll) 并启动 Agent？")
            .setPositiveButton("注册并启动") { _, _ ->
                performEnrollment(autoStartAfter = true)
            }
            .setNegativeButton("仅保存配置", null)
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
            binding.tvLogs.text = lines.joinToString("\n")
            binding.scrollLogs.post {
                binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun updateRunningState(isRunning: Boolean) {
        if (isRunning) {
            binding.viewStatusDot.setBackgroundColor(getColor(R.color.status_running))
            binding.tvStatus.text = getString(R.string.status_running)
            binding.btnToggleAgent.text = getString(R.string.btn_stop)
            binding.btnToggleAgent.setBackgroundColor(getColor(R.color.status_error))
            binding.etPanelUrl.isEnabled = false
            binding.etNodeId.isEnabled = false
            binding.etToken.isEnabled = false
            binding.btnEnroll.isEnabled = false
            binding.btnScanQr.isEnabled = false
        } else {
            binding.viewStatusDot.setBackgroundColor(getColor(R.color.status_stopped))
            binding.tvStatus.text = getString(R.string.status_stopped)
            binding.btnToggleAgent.text = getString(R.string.btn_start)
            binding.btnToggleAgent.setBackgroundColor(getColor(R.color.primary))
            binding.etPanelUrl.isEnabled = true
            binding.etNodeId.isEnabled = true
            binding.etToken.isEnabled = true
            binding.btnEnroll.isEnabled = true
            binding.btnScanQr.isEnabled = true
        }
    }

    private fun updateStatusDetails(status: AgentStatus) {
        binding.tvStatsTraffic.text = String.format(
            getString(R.string.traffic_stat),
            formatBytes(status.uplinkBytes),
            formatBytes(status.downlinkBytes)
        )

        val uptimeFormatted = formatUptime(status.uptimeSecs)
        binding.tvStatsConns.text = String.format(
            getString(R.string.connections_stat),
            status.connections,
            uptimeFormatted
        )

        if (status.lastError.isNotBlank() && !status.running) {
            binding.tvLastError.visibility = View.VISIBLE
            binding.tvLastError.text = "异常: ${status.lastError}"
            binding.viewStatusDot.setBackgroundColor(getColor(R.color.status_error))
            binding.tvStatus.text = getString(R.string.status_error)
        } else {
            binding.tvLastError.visibility = View.GONE
        }
    }

    private fun validateInputs(): Boolean {
        val url = binding.etPanelUrl.text?.toString()?.trim() ?: ""
        val nodeId = binding.etNodeId.text?.toString()?.trim() ?: ""
        val token = binding.etToken.text?.toString()?.trim() ?: ""

        if (url.isBlank()) {
            binding.etPanelUrl.error = "请输入 Panel 基础地址"
            return false
        }
        if (nodeId.isBlank()) {
            binding.etNodeId.error = "请输入节点 ID"
            return false
        }
        if (token.isBlank()) {
            binding.etToken.error = "请输入令牌"
            return false
        }
        return true
    }

    private fun saveInputsToPrefs() {
        prefs.panelUrl = binding.etPanelUrl.text?.toString()?.trim() ?: ""
        prefs.nodeId = binding.etNodeId.text?.toString()?.trim() ?: ""
        prefs.token = binding.etToken.text?.toString()?.trim() ?: ""
    }

    private fun performEnrollment(autoStartAfter: Boolean = false) {
        if (!validateInputs()) return
        saveInputsToPrefs()

        val progress = AlertDialog.Builder(this)
            .setTitle("正在注册节点")
            .setMessage("正在生成密钥与证书请求并向 Panel 申请证书...")
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
                            binding.etToken.setText(issuedToken)
                        }
                        LadderApplication.appendLog("节点一键注册成功，证书已就绪")

                        if (autoStartAfter) {
                            Toast.makeText(this@MainActivity, "注册成功，正在启动 Agent...", Toast.LENGTH_SHORT).show()
                            AgentService.start(this@MainActivity)
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("注册成功")
                                .setMessage("管理证书已签发并保存在应用私有存储中，现在可以启动 Agent！")
                                .setPositiveButton("好的", null)
                                .show()
                        }
                    } else {
                        val errMsg = resObj.optString("error", "未知注册失败")
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("注册失败")
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
                Toast.makeText(this, "已获取忽略电池优化权限", Toast.LENGTH_SHORT).show()
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
}
