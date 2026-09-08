package com.iot.control

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.iot.control.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var iotManager: AliyunIotManager
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("iot_config", MODE_PRIVATE)
        iotManager = (application as IoTApp).iotManager

        setupListeners()
        refreshStatus()
        autoConnect()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        setupManagerListeners()
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnOpenSwitches.setOnClickListener {
            startActivity(Intent(this, SwitchesActivity::class.java))
        }

        // 旋钮初始配置（温度范围 0~100°C，步长1）
        binding.knobTemperature.apply {
            min = 0f
            max = 100f
            step = 1f
            value = 25f
            // 拖动时实时更新按钮文字
            onValueChanged = { v ->
                binding.btnSetTemp.text = "下发 ${formatTemp(v)}°C"
            }
            // 松手时下发（用户也可点击按钮手动下发）
            onValueFinalized = { v ->
                iotManager.setTemperature(getSavedConfig(), v)
                appendLog("旋钮下发: 温度 ${formatTemp(v)}°C (Temperature=$v)")
                // 变色反馈
                val original = ColorStateList.valueOf(getColor(R.color.blue_600))
                val highlight = ColorStateList.valueOf(getColor(R.color.green_600))
                binding.btnSetTemp.backgroundTintList = highlight
                binding.btnSetTemp.text = "已下发 ${formatTemp(v)}°C"
                binding.btnSetTemp.postDelayed({
                    binding.btnSetTemp.backgroundTintList = original
                    binding.btnSetTemp.text = "下发 ${formatTemp(v)}°C"
                }, 800)
            }
        }

        // 手动点击下发按钮
        binding.btnSetTemp.setOnClickListener {
            val temp = binding.knobTemperature.value
            iotManager.setTemperature(getSavedConfig(), temp)
            appendLog("按钮下发: 温度 ${formatTemp(temp)}°C (Temperature=$temp)")
            // 变色反馈
            val original = ColorStateList.valueOf(getColor(R.color.blue_600))
            val highlight = ColorStateList.valueOf(getColor(R.color.green_600))
            binding.btnSetTemp.backgroundTintList = highlight
            binding.btnSetTemp.text = "已下发 ${formatTemp(temp)}°C"
            binding.btnSetTemp.postDelayed({
                binding.btnSetTemp.backgroundTintList = original
                binding.btnSetTemp.text = "下发 ${formatTemp(temp)}°C"
            }, 800)
        }

        // 清除日志
        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = "暂无日志"
            Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
        }

        // 自定义消息下发
        val customTopic = "/k1jrhJxxEiu/SJD_app/user/update"
        binding.btnSendCustom.setOnClickListener {
            val payload = binding.etCustomPayload.text.toString().trim()
            if (payload.isEmpty()) {
                Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!iotManager.isConnected()) {
                Toast.makeText(this, "未连接，无法发送", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            iotManager.publishCustom(customTopic, payload, qos = 0, retained = false)
            appendLog("发送 → $customTopic : $payload")
        }
        binding.btnFillTemplate.setOnClickListener {
            binding.etCustomPayload.setText("{\"from\":\"phone\",\"num\":999}")
            binding.etCustomPayload.setSelection(binding.etCustomPayload.text.length)
            Toast.makeText(this, "已填入默认模板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupManagerListeners() {
        iotManager.statusListener = { status, msg ->
            runOnUiThread {
                updateStatusUI(status, msg)
                appendLog(msg)
            }
        }

        iotManager.messageListener = { topic, payload ->
            runOnUiThread {
                // 自定义 Topic 消息处理
                if (topic.contains("/user/update")) {
                    handleCustomMessage(payload)
                } else {
                    appendLog("收到: $payload")
                    parseAndUpdateStatus(payload)
                }
            }
        }
    }

    /**
     * 处理自定义 Topic 消息，例如：
     *  - {"from":"phone","type":"switch","index":1,"value":1}
     *  - {"from":"phone","type":"switch_all","value":1}
     *  - {"from":"phone","type":"temperature","value":25.0}
     *  - {"from":"phone","num":999}
     */
    private fun handleCustomMessage(payload: String) {
        appendLog("收到指令: $payload")
        binding.tvLatestRaw.text = payload
        try {
            val json = JSONObject(payload)
            val from = json.optString("from", "未知")
            val type = json.optString("type", "")
            val index = json.opt("index")
            val value = json.opt("value")
            val num = json.opt("num")

            // 在参数区域显示
            binding.layoutParams.removeAllViews()
            binding.tvNoData.visibility = android.view.View.GONE
            addParamRow("from", from)

            when {
                type == "switch" -> {
                    val idx = index?.toString() ?: "--"
                    val on = value?.toString() == "1"
                    appendLog("开关$idx ${if (on) "开启" else "关闭"} (value=$value)")
                    addParamRow("type", "switch 单控")
                    addParamRow("index", idx)
                    addParamRow("value", value?.toString() ?: "--")
                }
                type == "switch_all" -> {
                    val on = value?.toString() == "1"
                    appendLog("全部开关 ${if (on) "开启" else "关闭"} (value=$value)")
                    addParamRow("type", "switch_all 全控")
                    addParamRow("value", value?.toString() ?: "--")
                }
                type == "temperature" -> {
                    appendLog("温度设置: $value°C")
                    addParamRow("type", "temperature 温度")
                    addParamRow("value", value?.toString() ?: "--")
                }
                type == "power" -> {
                    val on = value?.toString() == "1"
                    appendLog("电源开关 ${if (on) "开启" else "关闭"} (value=$value)")
                    addParamRow("type", "power 电源")
                    addParamRow("value", value?.toString() ?: "--")
                }
                type == "light" -> {
                    val on = value?.toString() == "1"
                    appendLog("灯开关 ${if (on) "开启" else "关闭"} (value=$value)")
                    addParamRow("type", "light 灯")
                    addParamRow("value", value?.toString() ?: "--")
                }
                else -> {
                    // 兼容旧格式 {"from":"phone","num":999}
                    appendLog("来源: $from, 数值: $num, type=$type")
                    if (type.isNotEmpty()) addParamRow("type", type)
                    if (num != null) addParamRow("num", num.toString())
                    if (value != null) addParamRow("value", value.toString())
                    if (index != null) addParamRow("index", index.toString())
                }
            }
        } catch (e: Exception) {
            appendLog("解析失败: ${e.message}")
        }
    }

    private fun addParamRow(key: String, value: String) {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
        }
        val keyView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = key
            textSize = 15f
            setTextColor(getColor(R.color.gray_600))
        }
        val valueView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = value
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.gray_800))
            gravity = Gravity.END
        }
        row.addView(keyView)
        row.addView(valueView)
        binding.layoutParams.addView(row)
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
                setControlEnabled(false)
                binding.tvDeviceStatus.text = "设备离线"
            }
            AliyunIotManager.Status.CONNECTING -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_yellow)
                binding.tvStatus.text = "连接中…"
                binding.tvStatus.setTextColor(getColor(R.color.gray_600))
                setControlEnabled(false)
            }
            AliyunIotManager.Status.CONNECTED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_green)
                binding.tvStatus.text = "已连接"
                binding.tvStatus.setTextColor(getColor(R.color.green_700))
                setControlEnabled(true)
                binding.tvDeviceStatus.text = "设备在线"
            }
            AliyunIotManager.Status.ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_red)
                binding.tvStatus.text = "连接错误"
                binding.tvStatus.setTextColor(getColor(R.color.red_600))
                setControlEnabled(false)
            }
        }
    }

    private fun setControlEnabled(enabled: Boolean) {
        binding.btnSetTemp.isEnabled = enabled
        binding.btnSetTemp.alpha = if (enabled) 1f else 0.5f
        binding.knobTemperature.isEnabled = enabled
        binding.knobTemperature.alpha = if (enabled) 1f else 0.5f
        binding.btnOpenSwitches.isEnabled = enabled
        binding.btnOpenSwitches.alpha = if (enabled) 1f else 0.5f
    }

    private fun getSavedConfig(): AliyunIotManager.DeviceConfig {
        return AliyunIotManager.DeviceConfig(
            productKey = prefs.getString("productKey", "") ?: "",
            deviceName = prefs.getString("deviceName", "") ?: "",
            deviceSecret = prefs.getString("deviceSecret", "") ?: "",
            region = prefs.getString("region", "cn-shanghai") ?: "cn-shanghai"
        )
    }

    private fun autoConnect() {
        val config = getSavedConfig()
        if (config.productKey.isNotBlank()
            && config.deviceName.isNotBlank()
            && config.deviceSecret.isNotBlank()
            && !iotManager.isConnected()
        ) {
            setupManagerListeners()
            // 用户上次主动断开过 → 不自动重连，提示需手动点击连接
            if (iotManager.isManualDisconnected()) {
                appendLog("上次已主动断开，需手动点击连接")
            } else {
                iotManager.connect(config)
                appendLog("应用启动，自动连接设备...")
            }
        }
    }

    private fun parseAndUpdateStatus(payload: String) {
        // 始终显示原始数据
        binding.tvLatestRaw.text = payload

        try {
            val json = JSONObject(payload)
            // 从 params / data / 根级别提取属性
            val params = json.optJSONObject("params")
                ?: json.optJSONObject("data")
                ?: json

            val keys = params.keys()
            if (!keys.hasNext()) return

            // 清空之前的参数行
            binding.layoutParams.removeAllViews()
            binding.tvNoData.visibility = android.view.View.GONE

            // 动态生成每个参数的行
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.opt(key)
                val row = LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    orientation = LinearLayout.HORIZONTAL
                }
                val keyView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                    )
                    text = key
                    textSize = 15f
                    setTextColor(getColor(R.color.gray_600))
                }
                val valueView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = formatValue(value)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(getColor(R.color.gray_800))
                    gravity = Gravity.END
                }
                row.addView(keyView)
                row.addView(valueView)
                binding.layoutParams.addView(row)
            }
        } catch (e: Exception) {
            // 非 JSON 格式，原始数据已显示
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatTemp(v: Float): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    private fun formatValue(v: Any?): String {
        return when (v) {
            is Boolean -> if (v) "开" else "关"
            is Number -> {
                val d = v.toDouble()
                if (d == d.toInt().toDouble()) d.toInt().toString() else d.toString()
            }
            else -> v?.toString() ?: "--"
        }
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val current = binding.tvLog.text.toString()
        val line = "[$time] $msg"
        // 最新日志显示在最上面
        binding.tvLog.text = if (current.isBlank() || current == "暂无日志") line else "$line\n$current"
    }
}
