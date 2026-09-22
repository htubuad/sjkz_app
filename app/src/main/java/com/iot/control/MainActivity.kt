package com.iot.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.iot.control.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val cn = java.util.Locale("zh", "CN")
        java.util.Locale.setDefault(cn)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        @Suppress("DEPRECATION")
        config.locale = cn
        @Suppress("DEPRECATION")
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var iotManager: AliyunIotManager
    private lateinit var prefs: SharedPreferences

    private var powerOn = false
    private var lightOn = false

    private var chart1ViewMode = 1000
    private var chart2ViewMode = 1000

    private var chart1HistoryMode = false
    private var chart2HistoryMode = false

    private val deviceOfflineTimeoutMs = 120_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentConnectionStatus = AliyunIotManager.Status.DISCONNECTED
    private var deviceCheckRunnable: Runnable? = null
    private val pulseHandlers = java.util.concurrent.ConcurrentHashMap<Int, Handler>()
    private val animatorSets = java.util.concurrent.ConcurrentHashMap<Int, android.animation.AnimatorSet>()
    private val logTimeFmt by lazy { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("iot_config", MODE_PRIVATE)
        iotManager = (application as IoTApp).iotManager

        chart1ViewMode = prefs.getInt("chart1_view_mode", 1000)
        chart2ViewMode = prefs.getInt("chart2_view_mode", 1000)

        // 从集中状态恢复电源和灯的开关状态
        val savedState = iotManager.getDeviceState()
        powerOn = savedState.power
        lightOn = savedState.light
        updatePowerUI()
        updateLightUI()
        // 从集中状态恢复设定值显示（默认显示 Field2）
        binding.tvSetPointValue.text = "当前 Field2(设定值): ${formatSetPoint(savedState.setPoint)}"
        binding.etSetPoint.setText(formatSetPoint(savedState.setPoint))

        // 目标字段切换监听
        binding.rgTargetField.setOnCheckedChangeListener { _, checkedId ->
            val state = iotManager.getDeviceState()
            when (checkedId) {
                R.id.rbField1 -> {
                    binding.tvSetPointValue.text = "当前 Field1(温度设定): ${formatSetPoint(state.field1SetValue)}"
                    binding.etSetPoint.setText(formatSetPoint(state.field1SetValue))
                }
                R.id.rbField2 -> {
                    binding.tvSetPointValue.text = "当前 Field2(设定值): ${formatSetPoint(state.setPoint)}"
                    binding.etSetPoint.setText(formatSetPoint(state.setPoint))
                }
            }
        }

        setupListeners()
        setupScrollAutoTop()
        refreshStatus()
        binding.chartTemperature.setAccentColor(Color.parseColor("#3B82F6"))
        binding.chartTemperature.showYLabels = true
        binding.chartTemperature2.setAccentColor(Color.parseColor("#10B981"))
        binding.chartTemperature2.showYLabels = true
        binding.tvChart2Current.setTextColor(Color.parseColor("#10B981"))

        binding.chartTemperature.onDoubleTapCallback = {
            val intent = Intent(this, ChartFullscreenActivity::class.java)
            intent.putExtra("chart_index", 0)
            intent.putExtra("chart_color", Color.parseColor("#3B82F6"))
            intent.putExtra("is_history_mode", chart1HistoryMode)
            startActivity(intent)
        }
        binding.chartTemperature2.onDoubleTapCallback = {
            val intent = Intent(this, ChartFullscreenActivity::class.java)
            intent.putExtra("chart_index", 1)
            intent.putExtra("chart_color", Color.parseColor("#10B981"))
            intent.putExtra("is_history_mode", chart2HistoryMode)
            startActivity(intent)
        }

        autoConnect()
        startDeviceOnlineCheck()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        setupManagerListeners()
        refreshChart()
        refreshChart2()
        startDeviceOnlineCheck()
    }

    override fun onPause() {
        super.onPause()
        stopDeviceOnlineCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDeviceOnlineCheck()
        mainHandler.removeCallbacksAndMessages(null)
        pulseHandlers.values.forEach { it.removeCallbacksAndMessages(null) }
        pulseHandlers.clear()
        animatorSets.values.forEach { it.cancel() }
        animatorSets.clear()
        iotManager.tfStateListener = null
        iotManager.queryResultListener = null
        iotManager.statusListener = null
        iotManager.temperatureHistoryListener = null
        iotManager.temperature2HistoryListener = null
        iotManager.sentPayloadListener = null
        iotManager.logListener = null
        iotManager.ackListener = null
        iotManager.messageListener = null
    }

    private fun initChartViewModeChips() {
        val chip1Map = mapOf(
            R.id.chipChart1_100 to 100,
            R.id.chipChart1_500 to 500,
            R.id.chipChart1_all to 1000
        )
        val chip1ResId = chip1Map.entries.firstOrNull { it.value == chart1ViewMode }?.key ?: R.id.chipChart1_all
        binding.chipGroupChart1.check(chip1ResId)
        binding.chipGroupChart1.setOnCheckedChangeListener { _, checkedId ->
            chart1ViewMode = chip1Map[checkedId] ?: 1000
            prefs.edit().putInt("chart1_view_mode", chart1ViewMode).apply()
            refreshChart()
        }

        val chip2Map = mapOf(
            R.id.chipChart2_100 to 100,
            R.id.chipChart2_500 to 500,
            R.id.chipChart2_all to 1000
        )
        val chip2ResId = chip2Map.entries.firstOrNull { it.value == chart2ViewMode }?.key ?: R.id.chipChart2_all
        binding.chipGroupChart2.check(chip2ResId)
        binding.chipGroupChart2.setOnCheckedChangeListener { _, checkedId ->
            chart2ViewMode = chip2Map[checkedId] ?: 1000
            prefs.edit().putInt("chart2_view_mode", chart2ViewMode).apply()
            refreshChart2()
        }

        binding.layoutChartHeader.setOnClickListener {
            val isExpanded = binding.layoutChartContent.visibility == View.VISIBLE
            binding.layoutChartContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvChartArrow.rotation = if (isExpanded) 0f else 90f
            if (chart1HistoryMode) {
                updateHistoryChart1(iotManager.getQueryBuffer())
            } else {
                refreshChart()
            }
        }

        binding.layoutChart2Header.setOnClickListener {
            val isExpanded = binding.layoutChart2Content.visibility == View.VISIBLE
            binding.layoutChart2Content.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvChart2Arrow.rotation = if (isExpanded) 0f else 90f
            if (chart2HistoryMode) {
                updateHistoryChart2(iotManager.getQueryBuffer())
            } else {
                refreshChart2()
            }
        }
    }

    private fun setupListeners() {
        initChartViewModeChips()

        selectedStartDate = todayDefault
        selectedEndDate = todayDefault
        binding.tvStartDate.text = selectedStartDate
        binding.tvStartDate.setTextColor(getColor(R.color.gray_800))
        binding.tvEndDate.text = selectedEndDate
        binding.tvEndDate.setTextColor(getColor(R.color.gray_800))
        binding.tvStartTime.text = selectedStartTime
        binding.tvStartTime.setTextColor(getColor(R.color.gray_800))
        binding.tvEndTime.text = selectedEndTime
        binding.tvEndTime.setTextColor(getColor(R.color.gray_800))

        binding.tvStartDate.setOnClickListener {
            showStartDatePicker()
        }

        binding.tvStartTime.setOnClickListener {
            showStartTimePicker()
        }

        binding.tvEndDate.setOnClickListener {
            showEndDatePicker()
        }

        binding.tvEndTime.setOnClickListener {
            showEndTimePicker()
        }

        binding.layoutQueryHeader.setOnClickListener {
            val isExpanded = binding.layoutQueryContent.visibility == View.VISIBLE
            binding.layoutQueryContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvQueryArrow.rotation = if (isExpanded) 0f else 90f
        }

        binding.btnQuery.setOnClickListener {
            if (iotManager.getQueryState() == AliyunIotManager.QueryState.QUERYING) {
                iotManager.cancelQuery()
                resetQueryUI()
            } else {
                doQueryHistory()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnOpenSwitches.setOnClickListener {
            startActivity(Intent(this, SwitchesActivity::class.java))
        }

        binding.btnClearChart.setOnClickListener {
            if (chart1HistoryMode) {
                restoreChart1Realtime()
            } else {
                iotManager.clearTemperatureHistory()
                binding.chartTemperature.clear()
                binding.tvChartTitle.text = "🌡 实时温度曲线"
                binding.tvChartTemp.text = "--"
                binding.tvChartInfo.text = "0 个点"
                binding.tvChartCurrent.text = "当前: --"
                Toast.makeText(this, "实时曲线已清空", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearChart2.setOnClickListener {
            if (chart2HistoryMode) {
                restoreChart2Realtime()
            } else {
                iotManager.clearTemperature2History()
                binding.chartTemperature2.clear()
                binding.tvChart2Title.text = "🌡 实时温度曲线2"
                binding.tvChart2Temp.text = "--"
                binding.tvChart2Info.text = "0 个点"
                binding.tvChart2Current.text = "当前: --"
                Toast.makeText(this, "实时曲线2已清空", Toast.LENGTH_SHORT).show()
            }
        }

        // 设定值卡片折叠/展开
        binding.layoutSetPointHeader.setOnClickListener {
            val isExpanded = binding.layoutSetPointContent.visibility == View.VISIBLE
            binding.layoutSetPointContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.tvSetPointArrow.rotation = if (isExpanded) 0f else 90f
        }

        // 设定值确定按钮：�?RadioGroup 选择下发�?Field1 �?Field2
        binding.btnSetPointConfirm.setOnClickListener {
            val text = binding.etSetPoint.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入数值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val value = text.toFloatOrNull()
            if (value == null) {
                Toast.makeText(this, "请输入有效数值", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isField1 = binding.rbField1.isChecked
            if (isField1) {
                iotManager.setField1(getSavedConfig(), value)
                appendLog("下发 Field1(温度设定): $value")
            } else {
                iotManager.setSetPoint(getSavedConfig(), value)
                appendLog("下发 Field2(设定值): $value")
            }
            val original = getColor(R.color.blue_600)
            val highlight = getColor(R.color.green_600)
            binding.tvSetPointValue.setTextColor(highlight)
            binding.tvSetPointValue.text = "已下发: $value"
            binding.tvSetPointValue.postDelayed({
                binding.tvSetPointValue.setTextColor(original)
                val s = iotManager.getDeviceState()
                binding.tvSetPointValue.text = if (isField1)
                    "当前 Field1(温度设定): ${formatSetPoint(s.field1SetValue)}"
                else
                    "当前 Field2(设定值): ${formatSetPoint(s.setPoint)}"
            }, 800)
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvLog.text = "暂无日志"
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
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

    private var isUserTouching = false
    private var isAutoScrollingBack = false

    private fun setupScrollAutoTop() {
    }

    private fun setupManagerListeners() {
        iotManager.tfStateListener = { tfState ->
            runOnUiThread {
                refreshTfStatus()
            }
        }

        iotManager.queryResultListener = { state, records, error ->
            runOnUiThread {
                handleQueryResult(state, records, error)
            }
        }

        iotManager.statusListener = { status, msg ->
            runOnUiThread {
                currentConnectionStatus = status
                updateStatusUI(status, msg)
                refreshOnlineStatus()
                refreshTfStatus()
                if (!msg.startsWith("发送中")) appendLog(msg)
            }
        }

        iotManager.temperatureHistoryListener = {
            runOnUiThread {
                refreshChart()
            }
        }

        iotManager.temperature2HistoryListener = {
            runOnUiThread {
                refreshChart2()
            }
        }

        // 控制端下发原始报文显示到收发日志
        iotManager.sentPayloadListener = { _, payload ->
            runOnUiThread {
                appendLog("发送: $payload")
            }
        }

        // 通用事件日志（如 QUERY_DATA 进度报文等）
        iotManager.logListener = { msg ->
            runOnUiThread {
                appendLog(msg)
            }
        }

        // 回执弹窗由 AliyunIotManager 统一显示，这里记录日志并回读状态刷新 UI
        iotManager.ackListener = { success, label ->
            runOnUiThread {
                refreshTfStatus()
                // 无论成功还是失败，都从集中状态刷新本地 UI
                // 成功: 以 ACK 回读的状态刷新；失败: 从已回滚到发送前快照的状态刷新（恢复按钮和设定值）
                val state = iotManager.getDeviceState()
                powerOn = state.power
                lightOn = state.light
                updatePowerUI()
                updateLightUI()
                // 设定值：文本与输入框同步（按当前选择的字段显示）
                val isF1 = binding.rbField1.isChecked
                val fieldValue = if (isF1) state.field1SetValue else state.setPoint
                val fieldLabel = if (isF1) "Field1(温度设定)" else "Field2(设定值)"
                binding.tvSetPointValue.text = "当前 $fieldLabel: ${formatSetPoint(fieldValue)}"
                if (!binding.etSetPoint.hasFocus()) {
                    binding.etSetPoint.setText(formatSetPoint(fieldValue))
                }
                if (success) {
                    if (label != "状态同步") {
                        appendLog("✓ 设备已执行: $label")
                    }
                } else {
                    appendLog("✗ 未收到设备回复: $label (超时)，已恢复状态")
                }
            }
        }

        iotManager.messageListener = { topic, payload ->
            runOnUiThread {
                if (topic.contains("/user/update") || topic.contains("/user/get")) {
                    handleCustomMessage(payload)
                } else {
                    appendLog("收到: $payload")
                    parseAndUpdateStatus(payload)
                }
                refreshTfStatus()
            }
        }
    }

    /**
     * 处理自定义 Topic 消息，支持标准接收报文格式：
     * {"DeviceID":"001_V1.2.0","Dir":"D>C","Temp":46.4,"RSSI":-61,"Switches":0,"Field1":0.00,"Field2":0.00}
     *
     * Dir 含义: C>D=控制端发送, D>C=设备发送, ACK=回执
     */
    private fun handleCustomMessage(payload: String) {
        appendLog("收到指令: $payload")
        try {
            val json = JSONObject(payload)

            // 在参数区域显示
            binding.layoutParams.removeAllViews()
            binding.tvNoData.visibility = android.view.View.GONE

            val deviceId = json.optString("DeviceID", "")
            val rawDir = json.optString("Dir", json.optString("方向", json.optString("Flag", "")))
            val direction = when {
                rawDir.equals("ACK", ignoreCase = true) -> "ACK"
                rawDir.equals("D>C", ignoreCase = true) -> "D>C"
                else -> "ACK"
            }

            if (deviceId.isNotEmpty() || rawDir.isNotEmpty()) {
                addParamRow("DeviceID", deviceId)
                addParamRow("Dir", direction)

                // Temp 温度（接收报文专用字段）
                val temp = json.optDouble("Temp", Double.NaN)
                if (!temp.isNaN()) addParamRow("Temp (温度)", "${formatTemp(temp.toFloat())} °C")

                // RSSI 信号强度
                val rssi = json.optInt("RSSI", Int.MIN_VALUE)
                if (rssi != Int.MIN_VALUE) addParamRow("RSSI (信号)", "$rssi dBm")

                // Switches 开关位掩码
                val switches = json.optInt("Switches", Int.MIN_VALUE)
                if (switches != Int.MIN_VALUE) addParamRow("Switches", switches.toString())

                // Field1
                val field1 = json.optDouble("Field1", Double.NaN)
                if (!field1.isNaN()) addParamRow("Field1", "%.2f".format(field1))

                // Field1_data
                val field1Data = json.optDouble("Field1_data", Double.NaN)
                if (!field1Data.isNaN()) addParamRow("Field1_data", "%.2f".format(field1Data))

                // Field2
                val field2 = json.optDouble("Field2", Double.NaN)
                if (!field2.isNaN()) addParamRow("Field2", "%.2f".format(field2))

                // Field2_data
                val field2Data = json.optDouble("Field2_data", Double.NaN)
                if (!field2Data.isNaN()) addParamRow("Field2_data", "%.2f".format(field2Data))

                return
            }

            // 兼容旧格式：遍历 JSON 所有字段并显示
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = json.opt(key)
                addParamRow(formatKey(key), formatValue(v, key))
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
        refreshOnlineStatus()
        refreshTfStatus()
        if (iotManager.isConnected()) {
            updateStatusUI(AliyunIotManager.Status.CONNECTED, "已连接到阿里云 IoT")
        } else {
            updateStatusUI(AliyunIotManager.Status.DISCONNECTED, "未连接")
        }
    }

    private fun refreshOnlineStatus() {
        val mqttConnected = iotManager.isConnected()
        if (!mqttConnected) {
            binding.onlineDot.setBackgroundResource(R.drawable.circle_gray)
            binding.tvOnlineStatus.text = "未知"
            binding.tvOnlineStatus.setTextColor(getColor(R.color.gray_600))
            stopPulse(binding.onlineRing)
            updateQueryButtonState()
            return
        }
        if (!iotManager.hasReceivedDevicePacket) {
            binding.onlineDot.setBackgroundResource(R.drawable.circle_gray)
            binding.tvOnlineStatus.text = "未知"
            binding.tvOnlineStatus.setTextColor(getColor(R.color.gray_600))
            stopPulse(binding.onlineRing)
            updateQueryButtonState()
            return
        }
        val deviceOnline = (System.currentTimeMillis() - iotManager.lastDevicePacketTimeMs) < deviceOfflineTimeoutMs
        when {
            deviceOnline -> {
                binding.onlineDot.setBackgroundResource(R.drawable.circle_green)
                binding.tvOnlineStatus.text = "在线"
                binding.tvOnlineStatus.setTextColor(getColor(R.color.green_700))
                stopPulse(binding.onlineRing)
            }
            else -> {
                binding.onlineDot.setBackgroundResource(R.drawable.circle_red)
                binding.tvOnlineStatus.text = "断开"
                binding.tvOnlineStatus.setTextColor(getColor(R.color.red_600))
                binding.onlineRing.setBackgroundResource(R.drawable.circle_red_ring)
                startPulse(binding.onlineRing)
            }
        }
        updateQueryButtonState()
    }

    private fun refreshTfStatus() {
        val deviceOnline = iotManager.isConnected() &&
            iotManager.hasReceivedDevicePacket &&
            (System.currentTimeMillis() - iotManager.lastDevicePacketTimeMs) < deviceOfflineTimeoutMs
        if (!deviceOnline) {
            binding.tfDot.setBackgroundResource(R.drawable.circle_gray)
            binding.tvTfStatus.text = "TF卡--"
            binding.tvTfStatus.setTextColor(getColor(R.color.gray_600))
            stopPulse(binding.tfRing)
            updateQueryButtonState()
            return
        }
        when (iotManager.getTfState()) {
            -1 -> {
                binding.tfDot.setBackgroundResource(R.drawable.circle_gray)
                binding.tvTfStatus.text = "TF卡--"
                binding.tvTfStatus.setTextColor(getColor(R.color.gray_600))
                stopPulse(binding.tfRing)
            }
            1 -> {
                binding.tfDot.setBackgroundResource(R.drawable.circle_green)
                binding.tvTfStatus.text = "TF正常"
                binding.tvTfStatus.setTextColor(getColor(R.color.green_700))
                stopPulse(binding.tfRing)
            }
            else -> {
                binding.tfDot.setBackgroundResource(R.drawable.circle_red)
                binding.tvTfStatus.text = "TF异常"
                binding.tvTfStatus.setTextColor(getColor(R.color.red_600))
                startPulse(binding.tfRing)
            }
        }
        updateQueryButtonState()
    }

    private fun updateQueryButtonState() {
        val deviceOnline = iotManager.isConnected() &&
            iotManager.hasReceivedDevicePacket &&
            (System.currentTimeMillis() - iotManager.lastDevicePacketTimeMs) < deviceOfflineTimeoutMs
        val tfOk = iotManager.getTfState() == 1
        val enabled = deviceOnline && tfOk
        binding.btnQuery.isEnabled = enabled
        binding.btnQuery.alpha = if (enabled) 1.0f else 0.45f
        val pickerAlpha = if (enabled) 1.0f else 0.4f
        binding.tvStartDate.isEnabled = enabled
        binding.tvStartDate.alpha = pickerAlpha
        binding.tvStartTime.isEnabled = enabled
        binding.tvStartTime.alpha = pickerAlpha
        binding.tvEndDate.isEnabled = enabled
        binding.tvEndDate.alpha = pickerAlpha
        binding.tvEndTime.isEnabled = enabled
        binding.tvEndTime.alpha = pickerAlpha
    }

    private fun startPulse(target: android.view.View) {
        target.visibility = View.VISIBLE
        target.alpha = 0.9f
        target.scaleX = 1f
        target.scaleY = 1f
        val scaleX = android.animation.ObjectAnimator.ofFloat(target, "scaleX", 1f, 3.5f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(target, "scaleY", 1f, 3.5f)
        val alpha = android.animation.ObjectAnimator.ofFloat(target, "alpha", 0.9f, 0f)
        val animatorSet = android.animation.AnimatorSet()
        animatorSet.playTogether(scaleX, scaleY, alpha)
        animatorSet.duration = 1200
        animatorSet.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        animatorSet.start()
        animatorSets[target.id] = animatorSet
        pulseHandlers[target.id]?.removeCallbacksAndMessages(null)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (target.visibility == View.VISIBLE) {
                    target.alpha = 0.9f
                    target.scaleX = 1f
                    target.scaleY = 1f
                    animatorSet.start()
                    handler.postDelayed(this, 1300)
                }
            }
        }
        pulseHandlers[target.id] = handler
        handler.postDelayed(runnable, 1300)
    }

    private fun stopPulse(target: android.view.View) {
        pulseHandlers[target.id]?.removeCallbacksAndMessages(null)
        pulseHandlers.remove(target.id)
        animatorSets[target.id]?.cancel()
        animatorSets.remove(target.id)
        target.visibility = View.GONE
        target.alpha = 1f
        target.scaleX = 1f
        target.scaleY = 1f
    }

    private fun startDeviceOnlineCheck() {
        deviceCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                refreshOnlineStatus()
                refreshTfStatus()
                mainHandler.postDelayed(this, 10_000L)
            }
        }
        deviceCheckRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopDeviceOnlineCheck() {
        deviceCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        deviceCheckRunnable = null
    }

    private val todayDefault: String by lazy {
        val cal = java.util.Calendar.getInstance(java.util.Locale("zh", "CN"))
        "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private var selectedStartDate: String = ""
    private var selectedStartTime: String = "00:00:00"
    private var selectedEndDate: String = ""
    private var selectedEndTime: String = "23:59:59"

    private fun showStartDatePicker() {
        val cn = java.util.Locale("zh", "CN")
        val cal = java.util.Calendar.getInstance(cn)
        val datePicker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择开始日期")
            .setSelection(cal.timeInMillis)
            .build()
        datePicker.addOnPositiveButtonClickListener { selection ->
            val sel = java.util.Calendar.getInstance(cn).apply { timeInMillis = selection }
            selectedStartDate = "%04d-%02d-%02d".format(
                sel.get(java.util.Calendar.YEAR),
                sel.get(java.util.Calendar.MONTH) + 1,
                sel.get(java.util.Calendar.DAY_OF_MONTH)
            )
            binding.tvStartDate.text = selectedStartDate
            binding.tvStartDate.setTextColor(getColor(R.color.gray_800))
            if (selectedEndDate.isEmpty() || selectedEndDate < selectedStartDate) {
                selectedEndDate = selectedStartDate
                binding.tvEndDate.text = selectedEndDate
                binding.tvEndDate.setTextColor(getColor(R.color.gray_800))
            }
        }
        datePicker.show(supportFragmentManager, "start_date")
    }

    private fun parseHM(timeStr: String): Pair<Int, Int> {
        val parts = timeStr.split(":")
        return if (parts.size == 2) {
            val h = parts[0].toIntOrNull() ?: 0
            val m = parts[1].toIntOrNull() ?: 0
            Pair(h, m)
        } else {
            Pair(0, 0)
        }
    }

    private fun selectAllTimePickerInput(
        picker: com.google.android.material.timepicker.MaterialTimePicker
    ) {
        mainHandler.post {
            val view = picker.view ?: return@post
            val editTexts = java.util.ArrayList<android.widget.EditText>()
            collectEditTexts(view, editTexts)
            editTexts.forEach { et ->
                et.requestFocus()
                et.selectAll()
            }
        }
    }

    private fun collectEditTexts(
        view: android.view.View,
        result: java.util.ArrayList<android.widget.EditText>
    ) {
        if (view is android.widget.EditText) {
            result.add(view)
            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectEditTexts(view.getChildAt(i), result)
            }
        }
    }

    private fun showStartTimePicker() {
        val (h, m) = parseHM(selectedStartTime)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
            .setHour(h)
            .setMinute(m)
            .setTitleText("选择开始时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            selectedStartTime = "%02d:%02d:00".format(picker.hour, picker.minute)
            binding.tvStartTime.text = selectedStartTime
            binding.tvStartTime.setTextColor(getColor(R.color.gray_800))
        }
        picker.show(supportFragmentManager, "start_time")
        selectAllTimePickerInput(picker)
    }

    private fun showEndDatePicker() {
        val cn = java.util.Locale("zh", "CN")
        val builder = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择结束日期 (最早等于开始日期)")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", cn)
        val startCal = java.util.Calendar.getInstance(cn)
        var hasValidStart = false
        if (selectedStartDate.isNotEmpty()) {
            try {
                val parsed = fmt.parse(selectedStartDate)
                if (parsed != null) {
                    startCal.time = parsed
                    hasValidStart = true
                }
            } catch (_: Exception) {}
        }
        val today = java.util.Calendar.getInstance(cn)
        val endCal = if (hasValidStart) {
            val c = java.util.Calendar.getInstance(cn).apply { time = startCal.time }
            c.add(java.util.Calendar.DAY_OF_MONTH, 2)
            if (c.timeInMillis > today.timeInMillis) today else c
        } else {
            today
        }
        if (startCal.timeInMillis > endCal.timeInMillis) {
            startCal.time = endCal.time
        }
        val constraints = com.google.android.material.datepicker.CalendarConstraints.Builder()
            .setStart(startCal.timeInMillis)
            .setEnd(endCal.timeInMillis)
            .build()
        var selection = if (selectedEndDate.isNotEmpty()) {
            try { fmt.parse(selectedEndDate)?.time ?: today.timeInMillis } catch (_: Exception) { today.timeInMillis }
        } else {
            today.timeInMillis
        }
        if (selection < startCal.timeInMillis) selection = startCal.timeInMillis
        if (selection > endCal.timeInMillis) selection = endCal.timeInMillis
        builder.setSelection(selection)
        builder.setCalendarConstraints(constraints)
        val datePicker = builder.build()
        datePicker.addOnPositiveButtonClickListener { sel ->
            val s = java.util.Calendar.getInstance(cn).apply { timeInMillis = sel }
            selectedEndDate = "%04d-%02d-%02d".format(
                s.get(java.util.Calendar.YEAR),
                s.get(java.util.Calendar.MONTH) + 1,
                s.get(java.util.Calendar.DAY_OF_MONTH)
            )
            binding.tvEndDate.text = selectedEndDate
            binding.tvEndDate.setTextColor(getColor(R.color.gray_800))
        }
        datePicker.show(supportFragmentManager, "end_date")
    }

    private fun showEndTimePicker() {
        val (h, m) = parseHM(selectedEndTime)
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
            .setHour(h)
            .setMinute(m)
            .setTitleText("选择结束时间")
            .build()
        picker.addOnPositiveButtonClickListener {
            selectedEndTime = "%02d:%02d:00".format(picker.hour, picker.minute)
            binding.tvEndTime.text = selectedEndTime
            binding.tvEndTime.setTextColor(getColor(R.color.gray_800))
        }
        picker.show(supportFragmentManager, "end_time")
        selectAllTimePickerInput(picker)
    }

    private fun doQueryHistory() {
        val deviceOnline = iotManager.isConnected() &&
            (System.currentTimeMillis() - iotManager.lastDevicePacketTimeMs) < deviceOfflineTimeoutMs
        if (!deviceOnline) return
        val tfState = iotManager.getTfState()
        if (tfState != 1) return
        if (selectedStartDate.isEmpty()) {
            Toast.makeText(this, "请先选择日期时间", Toast.LENGTH_SHORT).show()
            return
        }
        val endD = if (selectedEndDate.isEmpty()) selectedStartDate else selectedEndDate
        val cn = java.util.Locale("zh", "CN")
        val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", cn)
        val fullFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", cn)
        try {
            val startCal = java.util.Calendar.getInstance(cn)
            val endCal = java.util.Calendar.getInstance(cn)
            startCal.time = dateFmt.parse(selectedStartDate) ?: return
            endCal.time = dateFmt.parse(endD) ?: return
            val diffDays = ((endCal.timeInMillis - startCal.timeInMillis) / (24 * 3600 * 1000)).toInt()
            if (diffDays > 2) {
                Toast.makeText(this, "查询范围不能超过 2 天 (当前 ${diffDays + 1} 天)", Toast.LENGTH_LONG).show()
                return
            }
            val startTime = if (selectedStartTime.length == 5) "$selectedStartTime:00" else selectedStartTime
            val endTime = if (selectedEndTime.length == 5) "$selectedEndTime:00" else selectedEndTime
            val startMs = fullFmt.parse("$selectedStartDate $startTime")?.time ?: return
            val endMs = fullFmt.parse("$endD $endTime")?.time ?: return
            if (endMs <= startMs) {
                Toast.makeText(this, "结束时间必须晚于开始时间", Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {}
        binding.tvQueryStatus.visibility = View.VISIBLE
        val rangeLabel = buildString {
            append(selectedStartDate)
            if (selectedStartTime != "00:00") append(" $selectedStartTime")
            append(" ~ ")
            append(endD)
            if (selectedEndTime != "23:59") append(" $selectedEndTime")
        }
        binding.tvQueryStatus.text = "查询中 $rangeLabel ..."
        binding.tvQueryStatus.setTextColor(getColor(R.color.gray_600))
        binding.btnQuery.isEnabled = true
        binding.btnQuery.alpha = 1.0f
        binding.btnQuery.text = "取消"
        binding.progressQuery.visibility = View.VISIBLE
        binding.progressQuery.isIndeterminate = false
        binding.progressQuery.progress = 0
        iotManager.queryHistoryRange(selectedStartDate, selectedStartTime, endD, selectedEndTime)
    }

    private fun handleQueryResult(
        state: AliyunIotManager.QueryState,
        records: List<AliyunIotManager.HistoryRecord>,
        error: String?
    ) {
        runOnUiThread {
            when (state) {
                AliyunIotManager.QueryState.QUERYING -> {
                    chart1HistoryMode = true
                    chart2HistoryMode = true
                    val count = records.size
                    val dayPages = iotManager.getQueryDayPages()
                    val progressText = buildDayProgressText(dayPages)
                    binding.tvQueryStatus.visibility = View.VISIBLE
                    binding.tvQueryStatus.text = "$progressText, 累计 $count 个点"
                    binding.progressQuery.isIndeterminate = true
                    updateHistoryCharts(records)
                }
                AliyunIotManager.QueryState.COMPLETE -> {
                    chart1HistoryMode = true
                    chart2HistoryMode = true
                    val count = records.size
                    val deviceCount = iotManager.getQueryTotalCount()
                    val dayPages = iotManager.getQueryDayPages()
                    val countInfo = if (deviceCount > 0 && deviceCount != count) "$count/$deviceCount" else "$count"
                    val daysLabel = if (dayPages.isNotEmpty()) "(${dayPages.size} 天)" else ""
                    val endD = if (selectedEndDate.isNotEmpty()) selectedEndDate else selectedStartDate
                    val rangeLabel = buildString {
                        append(selectedStartDate)
                        if (selectedStartTime != "00:00:00") append(" $selectedStartTime")
                        append(" ~ ")
                        append(endD)
                        if (selectedEndTime != "23:59:59") append(" $selectedEndTime")
                    }
                    binding.tvQueryStatus.text = "完成 $countInfo 条 $daysLabel"
                    binding.tvQueryStatus.setTextColor(getColor(R.color.green_700))
                    binding.progressQuery.isIndeterminate = false
                    binding.progressQuery.max = count.coerceAtLeast(1)
                    binding.progressQuery.progress = count
                    binding.progressQuery.visibility = View.GONE
                    updateHistoryCharts(records)
                    binding.btnQuery.isEnabled = true
                    binding.btnQuery.alpha = 1.0f
                    binding.btnQuery.text = "查询"
                    appendLog("历史查询完成: $rangeLabel, 共 $countInfo 条$daysLabel")
                    if (deviceCount > 0 && count < deviceCount) {
                        appendLog("⚠ 丢包! 设备声明 $deviceCount 条, 实际收到 $count 条")
                    }
                    if (count == 0) {
                        Toast.makeText(this, "该时间段无数据", Toast.LENGTH_SHORT).show()
                    }
                }
                AliyunIotManager.QueryState.ERROR -> {
                    chart1HistoryMode = false
                    chart2HistoryMode = false
                    binding.tvQueryStatus.visibility = View.VISIBLE
                    binding.tvQueryStatus.text = "失败: ${error ?: "未知"}"
                    binding.tvQueryStatus.setTextColor(getColor(R.color.red_600))
                    binding.btnQuery.isEnabled = true
                    binding.btnQuery.alpha = 1.0f
                    binding.btnQuery.text = "重试"
                    binding.progressQuery.visibility = View.GONE
                    appendLog("历史查询失败: $selectedStartDate, ${error ?: "未知"}")
                    restoreChart1Realtime()
                    restoreChart2Realtime()
                }
            AliyunIotManager.QueryState.IDLE -> {
                chart1HistoryMode = false
                chart2HistoryMode = false
            }
        }
        }
    }

    private fun buildDayProgressText(dayPages: Map<String, AliyunIotManager.DayPageInfo>): String {
        if (dayPages.isEmpty()) return "查询中..."
        return dayPages.entries.joinToString(" ") { (date, info) ->
            val pageNum = info.page + 1
            if (info.totalPages > 0) "$date:$pageNum/${info.totalPages}" else "$date:$pageNum"
        }
    }

    private fun updateHistoryCharts(records: List<AliyunIotManager.HistoryRecord>) {
        updateHistoryChart1(records)
        updateHistoryChart2(records)
    }

    private fun updateHistoryChart1(records: List<AliyunIotManager.HistoryRecord>) {
        val latestTemp = records.lastOrNull()?.f1
        val latestText = if (latestTemp != null) String.format("%.1f°C", latestTemp) else "--"
        binding.tvChartTitle.text = "🌡 历史温度曲线"
        binding.tvChartTemp.text = latestText
        binding.tvChartTemp.setTextColor(getColor(R.color.blue_600))
        binding.btnClearChart.text = "恢复实时"
        binding.chartTemperature.setHistoryData(records, useF2 = false)
        val count = records.size
        binding.tvChartInfo.text = "$count 个点"
        binding.tvChartInfo.textSize = 13f
        binding.tvChartInfo.setTextColor(getColor(R.color.gray_400))
        binding.tvChartCurrent.text = "当前: $latestText"
    }

    private fun updateHistoryChart2(records: List<AliyunIotManager.HistoryRecord>) {
        val latestTemp = records.lastOrNull()?.f2
        val latestText = if (latestTemp != null) String.format("%.1f°C", latestTemp) else "--"
        binding.tvChart2Title.text = "🌡 历史温度曲线2"
        binding.tvChart2Temp.text = latestText
        binding.tvChart2Temp.setTextColor(getColor(R.color.green_600))
        binding.btnClearChart2.text = "恢复实时"
        binding.chartTemperature2.setHistoryData(records, useF2 = true)
        val count = records.size
        binding.tvChart2Info.text = "$count 个点"
        binding.tvChart2Info.textSize = 13f
        binding.tvChart2Info.setTextColor(getColor(R.color.gray_400))
        binding.tvChart2Current.text = "当前: $latestText"
    }

    private fun restoreChart1Realtime() {
        chart1HistoryMode = false
        binding.chartTemperature.timeAxisMode = false
        binding.btnClearChart.text = "清除"
        refreshChart()
        appendLog("曲线1已恢复实时")
    }

    private fun restoreChart2Realtime() {
        chart2HistoryMode = false
        binding.chartTemperature2.timeAxisMode = false
        binding.btnClearChart2.text = "清除"
        refreshChart2()
        appendLog("曲线2已恢复实时")
    }

    private fun resetQueryUI() {
        binding.tvQueryStatus.visibility = View.GONE
        binding.btnQuery.isEnabled = true
        binding.btnQuery.alpha = 1.0f
        binding.btnQuery.text = "查询"
        binding.progressQuery.visibility = View.GONE
        binding.progressQuery.progress = 0
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
                binding.tvStatus.text = "连接中..."
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
            binding.btnLight.text = "💡 开"
            binding.btnLight.backgroundTintList = ColorStateList.valueOf(getColor(R.color.yellow_500))
        } else {
            binding.btnLight.text = "💡 关"
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
            // 用户上次主动断开�?�?不自动重连，提示需手动点击连接
            if (iotManager.isManualDisconnected()) {
                appendLog("上次已主动断开，需手动点击连接")
            } else {
                iotManager.connect(config)
                appendLog("应用启动，自动连接设�?..")
            }
        }
    }

    private fun refreshChart() {
        if (chart1HistoryMode) {
            if (chart1ViewMode >= 1000) {
                binding.chartTemperature.showAll()
            } else {
                binding.chartTemperature.showLast(chart1ViewMode)
            }
            return
        }
        val allRecords = iotManager.getTemperatureHistoryRecords()
        val records = allRecords.takeLast(chart1ViewMode)
        val points = records.map { TemperatureChartView.Point(it.value, it.timestamp) }
        binding.chartTemperature.setPoints(points)
        val latest = allRecords.lastOrNull()?.let { String.format("%.1f°C", it.value) } ?: "--"
        val count = allRecords.size
        binding.tvChartTitle.text = "🌡 实时温度曲线"
        binding.tvChartTemp.text = latest
        binding.tvChartTemp.setTextColor(getColor(R.color.blue_600))
        binding.tvChartInfo.text = "$count 个点"
        binding.tvChartInfo.textSize = 13f
        binding.tvChartInfo.setTextColor(getColor(R.color.gray_400))
        binding.tvChartCurrent.text = "当前: $latest"
    }

    private fun refreshChart2() {
        if (chart2HistoryMode) {
            if (chart2ViewMode >= 1000) {
                binding.chartTemperature2.showAll()
            } else {
                binding.chartTemperature2.showLast(chart2ViewMode)
            }
            return
        }
        val allRecords = iotManager.getTemperature2HistoryRecords()
        val records = allRecords.takeLast(chart2ViewMode)
        val points = records.map { TemperatureChartView.Point(it.value, it.timestamp) }
        binding.chartTemperature2.setPoints(points)
        val latest = allRecords.lastOrNull()?.let { String.format("%.1f°C", it.value) } ?: "--"
        val count = allRecords.size
        binding.tvChart2Title.text = "🌡 实时温度曲线2"
        binding.tvChart2Temp.text = latest
        binding.tvChart2Temp.setTextColor(getColor(R.color.green_600))
        binding.tvChart2Info.text = "$count 个点"
        binding.tvChart2Info.textSize = 13f
        binding.tvChart2Info.setTextColor(getColor(R.color.gray_400))
        binding.tvChart2Current.text = "当前: $latest"
    }

    private fun parseAndUpdateStatus(payload: String) {
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
            // �?JSON 格式，原始数据已显示
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

    /** 设定值格式化：整数显示整数，浮点保留原始精度 */
    private fun formatSetPoint(v: Float): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.2f", v)
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
        if (key.equals("Dir", ignoreCase = true) || key.equals("方向", ignoreCase = true)) {
            return when {
                base.equals("ACK", ignoreCase = true) -> "ACK"
                base.equals("D>C", ignoreCase = true) -> "D>C"
                else -> "ACK"
            }
        }
        return if (isTemperatureKey(key)) "$base °C" else base
    }

    /**
     * 将字段名转为更友好的中文显示，未知字段保持原�?     */
    private fun formatKey(key: String): String {
        return when (key) {
            "temperature" -> "温度 (temperature)"
            "humidity" -> "湿度 (humidity)"
            "switch" -> "开�?(switch)"
            "power" -> "电源 (power)"
            "light" -> "�?(light)"
            else -> key
        }
    }

    private fun isTemperatureKey(key: String): Boolean {
        return key.equals("temperature", ignoreCase = true) ||
                key.equals("temp", ignoreCase = true)
    }

    private fun titleWithTemp(title: String, temp: String, colorRes: Int): CharSequence {
        val full = "$title   $temp"
        val ss = SpannableString(full)
        val start = full.length - temp.length
        ss.setSpan(ForegroundColorSpan(getColor(colorRes)), start, full.length, 0)
        return ss
    }

    private fun appendLog(msg: String) {
        val time = logTimeFmt.format(java.util.Date())
        val current = binding.tvLog.text.toString()
        val line = "[$time] $msg"
        // 最新日志显示在最上面
        val combined = if (current.isBlank() || current == "暂无日志") line else "$line\n$current"
        // 只保留最近 5 条
        val lines = combined.split("\n").take(5)
        // 保存外层 ScrollView 滚动位置，防止更新日志后页面跳转到底部
        val scrollView = binding.root as? android.widget.ScrollView
        val savedScrollY = scrollView?.scrollY ?: 0
        binding.tvLog.text = lines.joinToString("\n")
        // 立即恢复，并在下一帧再恢复一次（布局完成后）
        scrollView?.scrollTo(0, savedScrollY)
        scrollView?.post { scrollView.scrollTo(0, savedScrollY) }
    }
}