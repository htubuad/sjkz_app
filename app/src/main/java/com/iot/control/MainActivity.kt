package com.iot.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

    private var powerOn = false
    private var lightOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("iot_config", MODE_PRIVATE)
        iotManager = (application as IoTApp).iotManager

        // 从集中状态恢复电源和灯的开关状态
        val savedState = iotManager.getDeviceState()
        powerOn = savedState.power
        lightOn = savedState.light
        updatePowerUI()
        updateLightUI()

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

        // 温度卡片折叠/展开
        binding.layoutTempHeader.setOnClickListener {
            val isExpanded = binding.layoutTempContent.visibility == View.VISIBLE
            binding.layoutTempContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvTempArrow.rotation = if (isExpanded) 0f else 90f
        }

        // 旋钮初始配置（温度范围 0~100°C，步长1）
        binding.knobTemperature.apply {
            min = 0f
            max = 100f
            step = 1f
            value = 25f
            // 拖动时实时更新温度提示文字
            onValueChanged = { v ->
                binding.tvTempValue.text = "当前温度: ${formatTemp(v)}°C"
            }
            // 松手时自动下发
            onValueFinalized = { v ->
                iotManager.setTemperature(getSavedConfig(), v)
                appendLog("旋钮下发: 温度 ${formatTemp(v)}°C (Temperature=$v)")
                // 下发反馈：文字变绿后恢复
                val original = getColor(R.color.blue_600)
                val highlight = getColor(R.color.green_600)
                binding.tvTempValue.setTextColor(highlight)
                binding.tvTempValue.text = "已下发: ${formatTemp(v)}°C"
                binding.tvTempValue.postDelayed({
                    binding.tvTempValue.setTextColor(original)
                    binding.tvTempValue.text = "当前温度: ${formatTemp(v)}°C"
                }, 800)
            }
        }

        // 清除日志
        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = "暂无日志"
            Toast.makeText(this, "日志已清除", Toast.LENGTH_SHORT).show()
        }

        // 电源开关
        binding.btnPower.setOnClickListener {
            powerOn = !powerOn
            updatePowerUI()
            iotManager.setPower(getSavedConfig(), powerOn)
            appendLog("电源 ${if (powerOn) "开启" else "关闭"}")
        }

        // 灯开关
        binding.btnLight.setOnClickListener {
            lightOn = !lightOn
            updateLightUI()
            iotManager.setLight(getSavedConfig(), lightOn)
            appendLog("灯 ${if (lightOn) "开启" else "关闭"}")
        }

        // 自定义消息下发
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
            val topic = getCurrentCustomTopic()
            iotManager.publishCustom(topic, payload, qos = 0, retained = false)
            appendLog("发送 → $topic : $payload")
        }
        binding.btnFillTemplate.setOnClickListener {
            binding.etCustomPayload.setText("{\"num\":999}")
            binding.etCustomPayload.setSelection(binding.etCustomPayload.text.length)
            Toast.makeText(this, "已填入默认模板", Toast.LENGTH_SHORT).show()
        }

        // 长按原始数据复制到剪贴板
        binding.tvLatestRaw.setOnLongClickListener {
            val text = binding.tvLatestRaw.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("原始数据", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "原始数据已复制到剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂无数据可复制", Toast.LENGTH_SHORT).show()
            }
            true
        }

        // 长按连接日志复制到剪贴板
        binding.tvLog.setOnLongClickListener {
            val text = binding.tvLog.text?.toString() ?: ""
            if (text.isNotBlank() && text != "暂无日志") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("连接日志", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "连接日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            }
            true
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
                // 自定义 Topic 消息处理（/user/update 和 /user/get 都走此分支）
                if (topic.contains("/user/update") || topic.contains("/user/get")) {
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
        binding.tvLatestRaw.text = prettyJson(payload)
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
            // from 字段存在时才显示（避免出现 "from — 未知"）
            if (from != "未知") {
                addParamRow("from", from)
            }

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
                    // 温度值来自 from 字段（如 {"from":"25.0","type":"temperature"}）
                    val temp = from.toDoubleOrNull()
                    val tempStr = if (temp != null) formatTemp(temp.toFloat()) else from
                    appendLog("温度: ${tempStr}°C")
                    addParamRow("type", "temperature 温度")
                    addParamRow("温度", "$tempStr °C")
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
                    // 通用：遍历 JSON 所有字段并显示，温度字段自动带 °C
                    // （方法顶部已记录"收到指令"日志，此处不再重复）
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        // from 已在上面单独处理，这里跳过避免重复
                        if (key == "from") continue
                        val v = json.opt(key)
                        addParamRow(formatKey(key), formatValue(v, key))
                    }
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
        // 刷新自定义消息下发的 Topic 显示，使其与设置页填写的 DeviceName 一致
        binding.tvCustomTopic.text = "Topic: ${getCurrentCustomTopic()}"
    }

    /**
     * 根据设置页当前保存的 productKey + deviceName 计算自定义下发 Topic
     */
    private fun getCurrentCustomTopic(): String {
        val cfg = getSavedConfig()
        val pk = cfg.productKey.ifBlank { "k1jrhJxxEiu" }
        val dn = cfg.deviceName.ifBlank { "SJD_app" }
        return "/$pk/$dn/user/update"
    }

    private fun updateStatusUI(status: AliyunIotManager.Status, msg: String) {
        when (status) {
            AliyunIotManager.Status.DISCONNECTED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_gray)
                binding.tvStatus.text = "未连接"
                binding.tvStatus.setTextColor(getColor(R.color.gray_600))
                setControlEnabled(false)
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
        binding.knobTemperature.isEnabled = enabled
        binding.knobTemperature.alpha = if (enabled) 1f else 0.5f
        binding.tvTempValue.alpha = if (enabled) 1f else 0.5f
        binding.btnOpenSwitches.isEnabled = enabled
        binding.btnOpenSwitches.alpha = if (enabled) 1f else 0.5f
        binding.btnPower.isEnabled = enabled
        binding.btnPower.alpha = if (enabled) 1f else 0.5f
        binding.btnLight.isEnabled = enabled
        binding.btnLight.alpha = if (enabled) 1f else 0.5f
    }

    private fun updatePowerUI() {
        if (powerOn) {
            binding.btnPower.text = "电源: 开"
            binding.btnPower.backgroundTintList = ColorStateList.valueOf(getColor(R.color.green_600))
        } else {
            binding.btnPower.text = "电源: 关"
            binding.btnPower.backgroundTintList = ColorStateList.valueOf(getColor(R.color.gray_400))
        }
    }

    private fun updateLightUI() {
        if (lightOn) {
            binding.btnLight.text = "灯: 开"
            binding.btnLight.backgroundTintList = ColorStateList.valueOf(getColor(R.color.yellow_500))
        } else {
            binding.btnLight.text = "灯: 关"
            binding.btnLight.backgroundTintList = ColorStateList.valueOf(getColor(R.color.gray_400))
        }
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
        // 显示原始数据（JSON 美化后）
        binding.tvLatestRaw.text = prettyJson(payload)

        try {
            val json = JSONObject(payload)
            // 直接解析接收到的 JSON 顶层字段（与自定义下发格式一致）
            val keys = json.keys()
            if (!keys.hasNext()) return

            // 清空之前的参数行
            binding.layoutParams.removeAllViews()
            binding.tvNoData.visibility = android.view.View.GONE

            // 动态生成每个参数的行
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.opt(key)
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
                    text = formatKey(key)
                    textSize = 15f
                    setTextColor(getColor(R.color.gray_600))
                }
                val valueView = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    text = formatValue(value, key)
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

    /**
     * JSON 美化（缩进格式），失败时返回原字符串
     */
    private fun prettyJson(payload: String): String {
        return try {
            val obj = JSONObject(payload)
            obj.toString(2)  // 缩进 2 空格
        } catch (e: Exception) {
            // 不是 JSON 对象，尝试数组
            try {
                val arr = org.json.JSONArray(payload)
                arr.toString(2)
            } catch (e2: Exception) {
                payload
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatTemp(v: Float): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }

    private fun formatValue(v: Any?, key: String = ""): String {
        val base = when (v) {
            is Boolean -> if (v) "开" else "关"
            is Number -> {
                val d = v.toDouble()
                if (d == d.toInt().toDouble()) d.toInt().toString() else d.toString()
            }
            else -> v?.toString() ?: "--"
        }
        // 温度字段附加单位
        return if (isTemperatureKey(key)) "$base °C" else base
    }

    /**
     * 将字段名转为更友好的中文显示，未知字段保持原名
     */
    private fun formatKey(key: String): String {
        return when (key) {
            "temperature" -> "温度 (temperature)"
            "humidity" -> "湿度 (humidity)"
            "switch" -> "开关 (switch)"
            "power" -> "电源 (power)"
            "light" -> "灯 (light)"
            else -> key
        }
    }

    private fun isTemperatureKey(key: String): Boolean {
        return key.equals("temperature", ignoreCase = true) ||
                key.equals("temp", ignoreCase = true)
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val current = binding.tvLog.text.toString()
        val line = "[$time] $msg"
        // 最新日志显示在最上面
        val combined = if (current.isBlank() || current == "暂无日志") line else "$line\n$current"
        // 只保留最近 10 条
        val lines = combined.split("\n").take(10)
        binding.tvLog.text = lines.joinToString("\n")
    }
}
