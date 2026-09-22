package com.iot.control

import android.graphics.Color
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChartFullscreenActivity : ComponentActivity() {

    private var chartIndex = 0
    private var isHistoryMode = false
    private var loadedCount = 0
    private lateinit var iotManager: AliyunIotManager
    private lateinit var chartView: TemperatureChartView
    private lateinit var tvFullCurrent: TextView
    private lateinit var tvFullTime: TextView

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chart_fullscreen)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        chartIndex = intent.getIntExtra("chart_index", 0)
        val chartColor = intent.getIntExtra("chart_color", Color.parseColor("#3B82F6"))
        isHistoryMode = intent.getBooleanExtra("is_history_mode", false)

        chartView = findViewById(R.id.full_chart)
        val btnBack: ImageButton = findViewById(R.id.btn_back)
        val btnYZoomOut: ImageButton = findViewById(R.id.btn_y_zoom_out)
        val btnYZoomIn: ImageButton = findViewById(R.id.btn_y_zoom_in)
        tvFullCurrent = findViewById(R.id.tv_full_current)
        tvFullTime = findViewById(R.id.tv_full_time)

        iotManager = (application as IoTApp).iotManager

        chartView.setAccentColor(chartColor)
        chartView.timeAxisMode = true
        chartView.showTooltipOnTouch = true
        chartView.showViewHint = false
        chartView.showYLabels = true
        chartView.useFixedYRange = true
        chartView.xAxisTextSize = 28f
        chartView.xLabelGapMin = 220f
        chartView.paddingLeftPx = 70f
        chartView.paddingRightPx = 0f
        chartView.tooltipTextSize = 36f
        chartView.tooltipTimeSize = 32f
        chartView.tooltipTimeFormatStr = "yyyy-MM-dd HH:mm:ss"
        chartView.dragOnlyOnAxis = true
        chartView.clear()

        if (isHistoryMode) {
            loadHistoryData()
        } else {
            val initialHistory = getCurrentHistory()
            val points = initialHistory.map { TemperatureChartView.Point(it.value, it.timestamp) }
            chartView.setPoints(points)
            loadedCount = initialHistory.size
            updateInfoPanel()
        }

        btnBack.setOnClickListener { finish() }

        btnYZoomIn.setOnClickListener { chartView.zoomYIn() }
        btnYZoomOut.setOnClickListener { chartView.zoomYOut() }
        btnYZoomOut.setOnLongClickListener {
            chartView.resetYZoom()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    private fun loadHistoryData() {
        val records = iotManager.getQueryBuffer()
        android.util.Log.d("ChartFullscreen", "loadHistoryData: ${records.size} records chartIndex=$chartIndex")
        chartView.setHistoryData(records, useF2 = (chartIndex == 1))
        chartView.showAll()
        updateInfoPanel()
    }

    private fun getCurrentHistory(): List<AliyunIotManager.TempRecord> {
        return when (chartIndex) {
            1 -> iotManager.getTemperature2HistoryRecords()
            else -> iotManager.getTemperatureHistoryRecords()
        }
    }

    private fun updateInfoPanel() {
        val value = chartView.getCurrentValue()
        tvFullCurrent.text = value?.let { String.format("%.1f°C", it) } ?: "--"
        val ts = chartView.getLatestTimestamp()
        tvFullTime.text = if (ts > 0) timeFormat.format(Date(ts)) else "--:--:--"
    }

    private val refreshAction = {
        val current = getCurrentHistory()
        val points = current.map { TemperatureChartView.Point(it.value, it.timestamp) }
        for (i in loadedCount until points.size) {
            chartView.addPoint(points[i].value, points[i].timestamp)
        }
        loadedCount = points.size
        updateInfoPanel()
    }

    override fun onResume() {
        super.onResume()
        if (isHistoryMode) return
        when (chartIndex) {
            1 -> iotManager.temperature2HistoryListener = refreshAction
            else -> iotManager.temperatureHistoryListener = refreshAction
        }
    }

    override fun onPause() {
        super.onPause()
        if (isHistoryMode) return
        when (chartIndex) {
            1 -> iotManager.temperature2HistoryListener = null
            else -> iotManager.temperatureHistoryListener = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
    }

    override fun onNavigateUp(): Boolean = false
}