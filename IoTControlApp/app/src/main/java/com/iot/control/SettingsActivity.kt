package com.iot.control

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iot.control.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var iotManager: AliyunIotManager
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("iot_config", MODE_PRIVATE)
        iotManager = (application as IoTApp).iotManager

        loadSavedConfig()
        setupListeners()
        refreshStatus()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            saveConfig()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            val config = getConfigFromInput()
            if (config.productKey.isBlank() || config.deviceName.isBlank() || config.deviceSecret.isBlank()) {
                Toast.makeText(this, "请填写完整的设备三元组", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 连接前自动保存，避免重启后丢失
            saveConfig()
            if (iotManager.isConnected()) {
                iotManager.disconnect()
            } else {
                iotManager.connect(config)
            }
        }

        iotManager.statusListener = { status, msg ->
            runOnUiThread {
                updateStatusUI(status, msg)
                appendLog(msg)
            }
        }

        iotManager.messageListener = { topic, payload ->
            runOnUiThread {
                appendLog("收到 [$topic]: $payload")
            }
        }
    }

    private fun refreshStatus() {
        if (iotManager.isConnected()) {
            updateStatusUI(AliyunIotManager.Status.CONNECTED, "已连接到阿里云 IoT")
        } else {
            updateStatusUI(AliyunIotManager.Status.DISCONNECTED, "未连接")
        }
    }

    private fun updateStatusUI(status: AliyunIotManager.Status, msg: String) {
        when (status) {
            AliyunIotManager.Status.DISCONNECTED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_gray)
                binding.tvStatus.text = "未连接"
                binding.tvStatus.setTextColor(getColor(R.color.gray_600))
                binding.btnConnect.text = "连接设备"
                binding.btnConnect.setBackgroundColor(getColor(R.color.green_600))
            }
            AliyunIotManager.Status.CONNECTING -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_yellow)
                binding.tvStatus.text = "连接中…"
                binding.tvStatus.setTextColor(getColor(R.color.gray_600))
                binding.btnConnect.isEnabled = false
            }
            AliyunIotManager.Status.CONNECTED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_green)
                binding.tvStatus.text = "已连接"
                binding.tvStatus.setTextColor(getColor(R.color.green_700))
                binding.btnConnect.text = "断开连接"
                binding.btnConnect.setBackgroundColor(getColor(R.color.red_500))
                binding.btnConnect.isEnabled = true
            }
            AliyunIotManager.Status.ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_red)
                binding.tvStatus.text = "连接错误"
                binding.tvStatus.setTextColor(getColor(R.color.red_600))
                binding.btnConnect.text = "连接设备"
                binding.btnConnect.setBackgroundColor(getColor(R.color.green_600))
                binding.btnConnect.isEnabled = true
            }
        }
    }

    private fun getConfigFromInput(): AliyunIotManager.DeviceConfig {
        return AliyunIotManager.DeviceConfig(
            productKey = binding.etProductKey.text.toString().trim(),
            deviceName = binding.etDeviceName.text.toString().trim(),
            deviceSecret = binding.etDeviceSecret.text.toString().trim(),
            region = binding.etRegion.text.toString().trim().ifBlank { "cn-shanghai" }
        )
    }

    private fun saveConfig() {
        val config = getConfigFromInput()
        prefs.edit().apply {
            putString("productKey", config.productKey)
            putString("deviceName", config.deviceName)
            putString("deviceSecret", config.deviceSecret)
            putString("region", config.region)
            apply()
        }
    }

    private fun loadSavedConfig() {
        binding.etProductKey.setText(prefs.getString("productKey", ""))
        binding.etDeviceName.setText(prefs.getString("deviceName", ""))
        binding.etDeviceSecret.setText(prefs.getString("deviceSecret", ""))
        binding.etRegion.setText(prefs.getString("region", "cn-shanghai"))
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val current = binding.tvLog.text.toString()
        val newLog = if (current == "暂无日志") "[$time] $msg" else "$current\n[$time] $msg"
        binding.tvLog.text = newLog
    }
}
