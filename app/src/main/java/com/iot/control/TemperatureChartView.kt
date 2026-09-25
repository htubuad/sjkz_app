package com.iot.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TemperatureChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Point(val value: Float, val timestamp: Long = 0L)

    private val points = mutableListOf<Point>()
    private val maxPoints = 1000

    private var viewStart = 0f
    private var viewCount = maxPoints
    private var isAutoFollow = true
    private var isHistoryMode = false

    private val minViewCount = 10

    private var yZoomLevel = 1.0f
    private val yZoomMin = 0.05f
    private val yZoomMax = 64.0f

    var onDoubleTapCallback: (() -> Unit)? = null
    var timeAxisMode = false
    var showTooltipOnTouch = false
    var showViewHint = false
    var dragOnlyOnAxis = false
    var showYLabels = false
    var useFixedYRange = false
    var fixedYMin = -50f
    var fixedYMax = 400f

    var yAxisTextSize: Float
        get() = textPaint.textSize
        set(value) { textPaint.textSize = value; invalidate() }

    var yAxisTextColor: Int
        get() = textPaint.color
        set(value) { textPaint.color = value; invalidate() }

    var yAxisTextBold: Boolean
        get() = textPaint.isFakeBoldText
        set(value) { textPaint.isFakeBoldText = value; invalidate() }

    var showYMinorLabels = false
    var yAxisMinorTextColor = Color.parseColor("#A78BFA")

    var yAxisMinorTextSize: Float
        get() = minorTextPaint.textSize
        set(value) { minorTextPaint.textSize = value; invalidate() }

    var xAxisTextSize: Float
        get() = xTextPaint.textSize
        set(value) { xTextPaint.textSize = value; invalidate() }

    var xLabelGapMin: Float = 90f

    var paddingLeftPx: Float = -1f
    var paddingRightPx: Float = 30f

    var enableScaleGesture = true
    var enableDragGesture = true

    private fun getChartPaddingLeft(): Float {
        if (paddingLeftPx >= 0f) return paddingLeftPx
        return if (timeAxisMode) 110f else 90f
    }
    private fun getChartPaddingRight(): Float = paddingRightPx

    var tooltipTextSize: Float
        get() = tooltipTextPaint.textSize
        set(value) { tooltipTextPaint.textSize = value; invalidate() }

    var tooltipTimeSize: Float
        get() = tooltipTimePaint.textSize
        set(value) { tooltipTimePaint.textSize = value; invalidate() }

    var tooltipTimeFormatStr: String = "HH:mm:ss"
        set(value) {
            field = value
            tooltipTimeFormat = SimpleDateFormat(value, Locale.getDefault())
            invalidate()
        }

    private var tooltipTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var touchX = -1f
    private var touchY = -1f
    private var isTouching = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTapCallback?.invoke() ?: resetView()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (!enableDragGesture) return false
            if (dragOnlyOnAxis && e1 != null && !isInAxisZone(e1.y)) return false
            val paddingLeft = getChartPaddingLeft()
            val paddingRight = getChartPaddingRight()
            val chartWidth = width - paddingLeft - paddingRight
            if (chartWidth <= 0) return false
            val pixelsPerPoint = chartWidth / (viewCount - 1).coerceAtLeast(1)
            flingVelocity = -velocityX / pixelsPerPoint / 60f
            isAutoFollow = false
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                handleScale(detector.scaleFactor, detector.focusX)
                return true
            }
        })

    private var lastDragX = 0f
    private var isDragging = false
    private var flingVelocity = 0f
    private var isSnapping = false
    private var multiTouchActive = false
    private var lastTouchInAxis = false

    private val linePaint = Paint().apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#EEEEEE")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val minorGridPaint = Paint().apply {
        color = Color.parseColor("#F3F3F3")
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    }

    private val minorTextPaint = Paint().apply {
        color = Color.parseColor("#A78BFA")
        textSize = 18f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    private val zeroLinePaint = Paint().apply {
        color = Color.parseColor("#F87171")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#D1D5DB")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#6B7280")
        textSize = 26f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    private val xTextPaint = Paint().apply {
        color = Color.parseColor("#9CA3AF")
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val zeroLabelPaint = Paint().apply {
        color = Color.parseColor("#F87171")
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val pointPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointGlowPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pointRingPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val hintPaint = Paint().apply {
        color = Color.parseColor("#6366F1")
        textSize = 20f
        isAntiAlias = true
        textAlign = Paint.Align.RIGHT
    }

    private val hintBgPaint = Paint().apply {
        color = Color.parseColor("#336366F1")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val crosshairPaint = Paint().apply {
        color = Color.parseColor("#996366F1")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val tooltipBgPaint = Paint().apply {
        color = Color.parseColor("#E61F2937")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tooltipTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 26f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val tooltipTimePaint = Paint().apply {
        color = Color.parseColor("#9CA3AF")
        textSize = 22f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private var accentColor = Color.parseColor("#3B82F6")
    private var accentLight = Color.parseColor("#60A5FA")
    private var accentDark = Color.parseColor("#2563EB")

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setAccentColor(color: Int) {
        accentColor = color
        accentLight = adjustAlpha(color, 0.45f)
        accentDark = adjustAlpha(color, 1.0f)
        invalidate()
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    fun addPoint(value: Float, timestamp: Long = System.currentTimeMillis()) {
        isHistoryMode = false
        points.add(Point(value, timestamp))
        if (points.size > maxPoints) {
            points.removeAt(0)
            viewStart = max(0f, viewStart - 1f)
        }
        if (isAutoFollow) {
            val total = points.size
            viewStart = (total - viewCount).toFloat().coerceAtLeast(0f)
        }
        clampViewHard()
        invalidate()
    }

    fun setPoints(newPoints: List<Point>) {
        isHistoryMode = false
        points.clear()
        points.addAll(newPoints.takeLast(maxPoints))
        resetView()
    }

    fun setHistoryData(records: List<AliyunIotManager.HistoryRecord>, useF2: Boolean = false) {
        val fieldLabel = if (useF2) "f2" else "f1"
        val raw = records.mapIndexed { i, r ->
            val v = if (useF2) r.f2 else r.f1
            val ts = parseTimeToMillis(r.fullTime) ?: (i * 1000L)
            val safeV = if (v.isNaN() || v.isInfinite()) 0f else v
            Point(safeV, ts)
        }.filter { it.timestamp > 0 }
        val converted = raw.sortedBy { it.timestamp }
        if (converted.isNotEmpty()) {
            val minV = converted.minOf { it.value }
            val maxV = converted.maxOf { it.value }
            android.util.Log.d("TemperatureChart", "setHistoryData($fieldLabel): ${converted.size} points, range=[$minV, $maxV]")
            android.util.Log.d("TemperatureChart", "setHistoryData($fieldLabel): timeFirst=${converted.firstOrNull()?.timestamp} timeLast=${converted.lastOrNull()?.timestamp}")
        } else {
            android.util.Log.d("TemperatureChart", "setHistoryData($fieldLabel): 0 points (empty)")
        }
        isHistoryMode = true
        points.clear()
        points.addAll(converted)
        viewStart = 0f
        viewCount = converted.size.coerceIn(minViewCount, maxPoints)
        isAutoFollow = false
        clampViewHard()
        android.util.Log.d("TemperatureChart", "setHistoryData($fieldLabel): viewStart=$viewStart viewCount=$viewCount totalPoints=${points.size}")
        invalidate()
    }

    fun clear() {
        points.clear()
        resetView()
    }

    fun refresh() {
        invalidate()
    }

    fun showAll() {
        viewStart = 0f
        viewCount = points.size.coerceAtLeast(minViewCount)
        isAutoFollow = false
        clampViewHard()
        invalidate()
    }

    fun zoomYIn() {
        yZoomLevel = (yZoomLevel * 1.2f).coerceAtMost(yZoomMax)
        invalidate()
    }

    fun zoomYOut() {
        yZoomLevel = (yZoomLevel / 1.2f).coerceAtLeast(yZoomMin)
        invalidate()
    }

    fun resetYZoom() {
        yZoomLevel = 1.0f
        invalidate()
    }

    fun getYZoomLevel(): Float = yZoomLevel

    fun showLast(n: Int) {
        if (points.isEmpty()) return
        val upperBound = if (isHistoryMode) points.size else maxPoints
        val effective = n.coerceIn(minViewCount, upperBound)
        val count = min(effective, points.size)
        viewStart = (points.size - count).toFloat().coerceAtLeast(0f)
        viewCount = count
        isAutoFollow = false
        clampViewHard()
        invalidate()
    }

    fun getCurrentValue(): Float? = points.lastOrNull()?.value

    fun getLatestTimestamp(): Long = points.lastOrNull()?.timestamp ?: 0L

    fun getPointCount(): Int = points.size

    private val timeFormatterFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val timeFormatterTime = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val timeFormatterDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun parseTimeToMillis(time: String): Long? {
        if (time.isBlank()) return null
        return try {
            when {
                time.contains(" ") -> timeFormatterFull.parse(time)?.time
                time.length == 8 -> timeFormatterTime.parse(time)?.let { d ->
                    val now = System.currentTimeMillis()
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = now
                    val dateOnly = timeFormatterDate.format(Date(now))
                    val parsed = timeFormatterTime.parse(time)
                    if (parsed != null) {
                        val tcal = java.util.Calendar.getInstance()
                        tcal.timeInMillis = parsed.time
                        cal.set(java.util.Calendar.HOUR_OF_DAY, tcal.get(java.util.Calendar.HOUR_OF_DAY))
                        cal.set(java.util.Calendar.MINUTE, tcal.get(java.util.Calendar.MINUTE))
                        cal.set(java.util.Calendar.SECOND, tcal.get(java.util.Calendar.SECOND))
                        cal.timeInMillis
                    } else null
                }
                else -> timeFormatterDate.parse(time)?.time
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resetView() {
        isAutoFollow = true
        viewCount = maxPoints
        val maxStart = getMaxStart()
        viewStart = maxStart
        invalidate()
    }

    private fun getMaxStart(): Float {
        val total = points.size
        if (total == 0) return 0f
        val effectiveMinCount = min(minViewCount, total)
        val maxAllowedCount = if (isHistoryMode) total else maxPoints.coerceAtMost(total)
        viewCount = viewCount.coerceIn(effectiveMinCount, maxAllowedCount)
        return (total - viewCount).toFloat().coerceAtLeast(0f)
    }

    private fun clampViewHard() {
        val maxStart = getMaxStart()
        viewStart = viewStart.coerceIn(0f, maxStart)
    }

    private fun elasticClamp(offset: Float, boundary: Float): Float {
        val overshoot = offset - boundary
        if (abs(overshoot) <= 0f) return offset
        val attenuated = overshoot / (1f + abs(overshoot) * 0.15f)
        return boundary + attenuated
    }

    private fun applyElasticBounds() {
        val maxStart = getMaxStart()
        viewStart = elasticClamp(viewStart, 0f)
        viewStart = elasticClamp(viewStart, maxStart)
    }

    private fun snapToBounds() {
        isSnapping = true
        postInvalidateOnAnimation()
    }

    private fun isInAxisZone(y: Float): Boolean {
        val axisZoneHeight = 80f
        return y >= height - axisZoneHeight
    }

    private fun handleScale(factor: Float, focusX: Float) {
        val total = points.size
        if (total == 0) return
        isAutoFollow = false

        val oldCount = viewCount
        val effectiveMinCount = min(minViewCount, total)
        val newCount = (oldCount / factor).roundToInt().coerceIn(effectiveMinCount, maxPoints.coerceAtMost(total))

        if (newCount == oldCount) return

        val paddingLeft = getChartPaddingLeft()
        val paddingRight = getChartPaddingRight()
        val chartWidth = width - paddingLeft - paddingRight
        val relX = ((focusX - paddingLeft) / chartWidth).coerceIn(0f, 1f)

        val focusIndex = viewStart + relX * (oldCount - 1)
        viewStart = focusIndex - relX * (newCount - 1)
        viewCount = newCount
        clampViewHard()
        invalidate()
    }

    private fun handleDrag(dx: Float) {
        val total = points.size
        if (total == 0) return
        isAutoFollow = false
        flingVelocity = 0f
        isSnapping = false

        val paddingLeft = getChartPaddingLeft()
        val paddingRight = getChartPaddingRight()
        val chartWidth = width - paddingLeft - paddingRight
        if (chartWidth <= 0) return

        val pixelsPerPoint = chartWidth / (viewCount - 1).coerceAtLeast(1)
        val shift = -dx / pixelsPerPoint

        viewStart += shift
        clampViewHard()
        invalidate()
    }

    private fun findNearestPointIndex(touchX: Float): Int {
        val paddingLeft = getChartPaddingLeft()
        val paddingRight = getChartPaddingRight()
        val chartWidth = width - paddingLeft - paddingRight
        val total = points.size
        val n = min(viewCount, (total - viewStart).toInt())
        if (n <= 1) return viewStart.toDouble().roundToInt().coerceIn(0, total - 1)
        val relX = ((touchX - paddingLeft) / chartWidth).coerceIn(0f, 1f)
        return (viewStart + relX * (n - 1)).toDouble().roundToInt().coerceIn(0, total - 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> multiTouchActive = true
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) multiTouchActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> multiTouchActive = false
        }

        if (!multiTouchActive) {
            gestureDetector.onTouchEvent(event)
        }
        if (enableScaleGesture) scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDragX = event.x
                lastTouchInAxis = isInAxisZone(event.y)
                isDragging = true
                if (showTooltipOnTouch) {
                    isTouching = true
                    touchX = event.x
                    touchY = event.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (enableDragGesture && event.pointerCount == 1 && isDragging && !multiTouchActive) {
                    val allowDrag = !dragOnlyOnAxis || lastTouchInAxis
                    if (allowDrag) {
                        val dx = event.x - lastDragX
                        lastDragX = event.x
                        handleDrag(dx)
                    }
                }
                if (showTooltipOnTouch && isTouching && !multiTouchActive) {
                    touchX = event.x
                    touchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (showTooltipOnTouch && isTouching) {
                    isTouching = false
                }
                invalidate()
            }
        }
        return true
    }

    private fun computeYRange(values: List<Float>): Triple<Float, Float, Float> {
        var minV = values.minOrNull() ?: 0f
        var maxV = values.maxOrNull() ?: 10f
        val minSpan = 20f
        val mid = (minV + maxV) / 2f
        if (maxV - minV < minSpan) {
            minV = mid - minSpan / 2f
            maxV = mid + minSpan / 2f
        } else {
            val range = maxV - minV
            val pad = range * 0.2f
            minV -= pad
            maxV += pad
        }
        val niceStep = niceStep(maxV - minV)
        minV = kotlin.math.floor(minV / niceStep) * niceStep
        maxV = kotlin.math.ceil(maxV / niceStep) * niceStep
        return Triple(minV, maxV, niceStep)
    }

    private fun niceStep(range: Float): Float {
        val raw = range / 4f
        val exp = Math.pow(10.0, Math.floor(Math.log10(raw.toDouble()))).toFloat()
        val n = raw / exp
        val m: Float = when {
            n <= 1.5f -> 1f
            n <= 3f -> 2f
            n <= 7f -> 5f
            else -> 10f
        }
        return m * exp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.isNotEmpty()) {
            val maxStart = getMaxStart()

            if (flingVelocity != 0f) {
                viewStart += flingVelocity
                flingVelocity *= 0.94f
                clampViewHard()

                if (viewStart <= 0f || viewStart >= maxStart) {
                    flingVelocity = 0f
                }

                if (abs(flingVelocity) >= 0.02f) {
                    postInvalidateOnAnimation()
                } else {
                    flingVelocity = 0f
                }
            }
        }

        val paddingLeft = getChartPaddingLeft()
        val paddingRight = getChartPaddingRight()
        val paddingTop = 30f
        val paddingBottom = if (timeAxisMode) 60f else 55f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        if (chartWidth <= 0 || chartHeight <= 0) return

        if (points.isEmpty()) {
            canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + chartHeight, axisPaint)
            canvas.drawLine(paddingLeft, paddingTop + chartHeight, paddingLeft + chartWidth, paddingTop + chartHeight, axisPaint)
            drawEmptyHint(canvas, paddingLeft + chartWidth / 2f, paddingTop + chartHeight / 2f)
            return
        }

        val startInt = viewStart.toInt().coerceIn(0, points.size - 1)
        val fracOffset = viewStart - startInt
        val lastNeeded = min(kotlin.math.ceil(viewStart + viewCount).toInt(), points.size - 1)
        val endInt = lastNeeded + 1
        if (startInt >= endInt) {
            canvas.drawLine(paddingLeft, paddingTop, paddingLeft, paddingTop + chartHeight, axisPaint)
            canvas.drawLine(paddingLeft, paddingTop + chartHeight, paddingLeft + chartWidth, paddingTop + chartHeight, axisPaint)
            drawEmptyHint(canvas, paddingLeft + chartWidth / 2f, paddingTop + chartHeight / 2f)
            return
        }
        val visiblePoints = points.subList(startInt, endInt)
        if (visiblePoints.isEmpty()) return
        val values = visiblePoints.map { it.value }
        val dataMin = values.minOrNull() ?: 0f
        val dataMax = values.maxOrNull() ?: 1f
        val (a, b, _) = computeYRange(values)
        val baseMin = a
        val baseMax = b
        val centerY = (dataMin + dataMax) / 2f
        val halfRange = (baseMax - baseMin) / 2f / yZoomLevel
        var yMin = centerY - halfRange
        var yMax = centerY + halfRange
        if (useFixedYRange) {
            if (yMax - yMin > fixedYMax - fixedYMin) { yMin = fixedYMin; yMax = fixedYMax }
            else if (yMin < fixedYMin) { yMin = fixedYMin; yMax = fixedYMin + 2 * halfRange }
            else if (yMax > fixedYMax) { yMax = fixedYMax; yMin = fixedYMax - 2 * halfRange }
        }
        val stepY = niceStep(yMax - yMin)
        var yRange = yMax - yMin
        if (yRange <= 0.0001f) yRange = 1f

        drawGridAndAxes(canvas, paddingLeft, paddingTop, chartWidth, chartHeight, yMin, yMax, stepY, yRange)
        drawZeroLine(canvas, paddingLeft, paddingTop, chartWidth, chartHeight, yMin, yRange)
        if (showYLabels) drawYLabels(canvas, paddingLeft, paddingTop, chartHeight, yMin, yMax, stepY, yRange)

        val n = visiblePoints.size
        val xStep = if (viewCount > 0) chartWidth / viewCount.toFloat() else 0f

        val pts = visiblePoints.mapIndexed { i, p ->
            val x = if (n > 1) paddingLeft + (i - fracOffset) * xStep else paddingLeft + chartWidth / 2f
            val y = paddingTop + chartHeight - ((p.value - yMin) / yRange) * chartHeight
            x to y
        }

        drawGradientFill(canvas, pts, paddingTop + chartHeight)
        drawSmoothLine(canvas, pts)
        drawDataPoints(canvas, pts)
        if (timeAxisMode) {
            drawTimeXLabels(canvas, paddingLeft, paddingTop + chartHeight, chartWidth, visiblePoints, fracOffset)
        } else {
            drawXLabels(canvas, paddingLeft, paddingTop + chartHeight, chartWidth, visiblePoints.size, viewStart.toDouble().roundToInt(), fracOffset)
        }
        if (showViewHint) {
            drawViewHint(canvas, paddingLeft + chartWidth - 8f, paddingTop + 18f)
        }

        if (showTooltipOnTouch && isTouching && pts.isNotEmpty()) {
            drawCrosshairAndTooltip(canvas, paddingLeft, paddingTop, chartWidth, chartHeight, pts, visiblePoints, yMin, yRange)
        }
    }

    private fun drawCrosshairAndTooltip(
        canvas: Canvas,
        paddingLeft: Float, paddingTop: Float,
        chartWidth: Float, chartHeight: Float,
        pts: List<Pair<Float, Float>>,
        visiblePoints: List<Point>,
        yMin: Float, yRange: Float
    ) {
        val idx = (findNearestPointIndex(touchX) - viewStart).toDouble().roundToInt()
        if (idx !in pts.indices) return
        val (px, py) = pts[idx]
        val point = visiblePoints[idx]

        canvas.drawLine(px, paddingTop, px, paddingTop + chartHeight, crosshairPaint)
        canvas.drawLine(paddingLeft, py, paddingLeft + chartWidth, py, crosshairPaint)

        pointGlowPaint.color = adjustAlpha(accentColor, 0.5f)
        canvas.drawCircle(px, py, 14f, pointGlowPaint)
        pointPaint.color = accentDark
        canvas.drawCircle(px, py, 8f, pointPaint)
        canvas.drawCircle(px, py, 8f, pointRingPaint)

        val valueText = String.format("%.2f °C", point.value)
        val timeText = if (point.timestamp > 0) tooltipTimeFormat.format(Date(point.timestamp)) else "--:--:--"

        val valueWidth = tooltipTextPaint.measureText(valueText)
        val timeWidth = tooltipTimePaint.measureText(timeText)
        val maxTextW = max(valueWidth, timeWidth)
        val paddingH = 20f
        val paddingV = 14f
        val lineGap = 6f
        val boxW = maxTextW + paddingH * 2
        val lineH = tooltipTextPaint.textSize
        val timeH = tooltipTimePaint.textSize
        val boxH = paddingV * 2 + lineH + lineGap + timeH

        var boxX = px + 20f
        if (boxX + boxW > width - 20f) boxX = px - boxW - 20f
        if (boxX < 10f) boxX = 10f

        var boxY = py - boxH / 2f
        if (boxY < paddingTop + 6f) boxY = paddingTop + 6f
        if (boxY + boxH > height - 10f) boxY = height - boxH - 10f

        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)
        canvas.drawRoundRect(rect, 12f, 12f, tooltipBgPaint)

        val textX = boxX + paddingH
        val valueY = boxY + paddingV + lineH - 6f
        val timeY = valueY + lineGap + timeH

        tooltipTextPaint.color = Color.WHITE
        canvas.drawText(valueText, textX, valueY, tooltipTextPaint)
        tooltipTimePaint.color = Color.parseColor("#9CA3AF")
        canvas.drawText(timeText, textX, timeY, tooltipTimePaint)
    }

    private fun drawGridAndAxes(
        canvas: Canvas, left: Float, top: Float, w: Float, h: Float,
        yMin: Float, yMax: Float, stepY: Float, yRange: Float
    ) {
        var v = kotlin.math.ceil(yMin / stepY) * stepY
        while (v <= yMax) {
            val y = top + h - ((v - yMin) / yRange) * h
            canvas.drawLine(left, y, left + w, y, gridPaint)
            v += stepY
        }
        val minorCount = 4
        val minorStep = stepY / (minorCount + 1)
        var mv = kotlin.math.ceil(yMin / minorStep) * minorStep
        minorTextPaint.color = yAxisMinorTextColor
        while (mv <= yMax) {
            val majorV = kotlin.math.round(mv / stepY) * stepY
            if (abs(mv - majorV) > 0.001f) {
                val y = top + h - ((mv - yMin) / yRange) * h
                canvas.drawLine(left, y, left + w, y, minorGridPaint)
                if (showYMinorLabels) {
                    val label = formatTick(mv)
                    canvas.drawText(label, left - 12f, y + 8f, minorTextPaint)
                }
            }
            mv += minorStep
        }
        canvas.drawLine(left, top, left, top + h, axisPaint)
        canvas.drawLine(left, top + h, left + w, top + h, axisPaint)
    }

    private fun drawZeroLine(
        canvas: Canvas, left: Float, top: Float, w: Float, h: Float,
        yMin: Float, yRange: Float
    ) {
        val y = top + h - ((0f - yMin) / yRange) * h
        if (y in (top - 1f)..(top + h + 1f)) {
            canvas.drawLine(left, y, left + w, y, zeroLinePaint)
            canvas.drawText("0°C", left + 4f, y - 4f, zeroLabelPaint)
        }
    }

    private fun drawYLabels(
        canvas: Canvas, left: Float, top: Float, h: Float,
        yMin: Float, yMax: Float, stepY: Float, yRange: Float
    ) {
        var v = kotlin.math.ceil(yMin / stepY) * stepY
        while (v <= yMax) {
            val y = top + h - ((v - yMin) / yRange) * h
            val label = formatTick(v)
            canvas.drawText(label, left - 12f, y + 9f, textPaint)
            v += stepY
        }
    }

    private fun formatTick(v: Float): String {
        val i = v.toInt()
        return if (abs(v - i) < 0.001f) "${i}°" else String.format("%.1f°", v)
    }

    private fun drawXLabels(
        canvas: Canvas, left: Float, bottom: Float, w: Float, n: Int, offset: Int, fracOffset: Float = 0f
    ) {
        if (n <= 1) {
            canvas.drawText("${offset + 1}", left + w / 2f, bottom + 28f, xTextPaint)
            return
        }
        val maxLabels = (w / xLabelGapMin).toInt().coerceAtLeast(2)
        val step = max(1, n / maxLabels)
        val stepX = w / (n - 1).toFloat()
        for (i in 0 until n step step) {
            val x = left + (i - fracOffset) * stepX
            canvas.drawText("${offset + i + 1}", x, bottom + 28f, xTextPaint)
        }
        if ((n - 1) % step != 0 && w - (left + (n - 1 - fracOffset) * stepX) > xLabelGapMin / 2) {
            canvas.drawText("${offset + n}", left + w - fracOffset * stepX, bottom + 28f, xTextPaint)
        }
    }

    private fun drawTimeXLabels(
        canvas: Canvas, left: Float, bottom: Float, w: Float, visiblePoints: List<Point>, fracOffset: Float = 0f
    ) {
        val n = visiblePoints.size
        if (n == 0) return
        val labelGapMin = xLabelGapMin
        val maxLabels = (w / labelGapMin).toInt().coerceAtLeast(2)
        val step = max(1, n / maxLabels)
        val stepX = w / (n - 1).toFloat()
        val labelXs = mutableListOf<Float>()
        for (i in 0 until n step step) {
            val x = left + (i - fracOffset) * stepX
            labelXs.add(x)
            val t = visiblePoints[i].timestamp
            val label = if (t > 0) timeFormat.format(Date(t)) else "--:--:--"
            canvas.drawText(label, x, bottom + 30f, xTextPaint)
        }
        if ((n - 1) % step != 0 && (left + w) - labelXs.last() > labelGapMin / 2) {
            val t = visiblePoints.last().timestamp
            val label = if (t > 0) timeFormat.format(Date(t)) else "--:--:--"
            canvas.drawText(label, left + w - fracOffset * stepX, bottom + 30f, xTextPaint)
        }
    }

    private fun drawViewHint(canvas: Canvas, rightX: Float, topY: Float) {
        val total = points.size
        if (total == 0) return

        val text = if (timeAxisMode) {
            val firstIdx = viewStart.toDouble().roundToInt().coerceIn(0, total - 1)
            val lastIdx = min((viewStart + viewCount).toDouble().roundToInt(), total) - 1
            val firstTs = points[firstIdx].timestamp
            val lastTs = points[lastIdx.coerceIn(0, total - 1)].timestamp
            if (firstTs > 0 && lastTs > 0) {
                val s = timeFormat.format(Date(firstTs))
                val e = timeFormat.format(Date(lastTs))
                "$s ~ $e"
            } else {
                "${firstIdx + 1}-${lastIdx + 1} / $total"
            }
        } else {
            val startLabel = viewStart.toDouble().roundToInt() + 1
            val endLabel = min((viewStart + viewCount).toDouble().roundToInt(), total)
            if (isAutoFollow && viewCount >= total) {
                "全部 $total"
            } else {
                "$startLabel-$endLabel / $total"
            }
        }

        val width = hintPaint.measureText(text) + 16f
        canvas.drawRoundRect(
            rightX - width, topY - 18f,
            rightX, topY + 6f,
            8f, 8f, hintBgPaint
        )
        canvas.drawText(text, rightX - 8f, topY, hintPaint)
    }

    private fun drawGradientFill(
        canvas: Canvas, pts: List<Pair<Float, Float>>, bottomY: Float
    ) {
        if (pts.isEmpty()) return
        val path = Path()
        path.moveTo(pts.first().first, bottomY)
        path.lineTo(pts.first().first, pts.first().second)

        for (i in 0 until pts.size - 1) {
            val xc = (pts[i].first + pts[i + 1].first) / 2f
            path.cubicTo(xc, pts[i].second, xc, pts[i + 1].second, pts[i + 1].first, pts[i + 1].second)
        }
        path.lineTo(pts.last().first, bottomY)
        path.close()

        val shader = LinearGradient(
            0f, pts.minOf { it.second },
            0f, bottomY,
            adjustAlpha(accentColor, 0.38f),
            adjustAlpha(accentColor, 0.02f),
            Shader.TileMode.CLAMP
        )
        val fillPaint = Paint().apply {
            this.shader = shader
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(path, fillPaint)
    }

    private fun drawSmoothLine(
        canvas: Canvas, pts: List<Pair<Float, Float>>
    ) {
        if (pts.isEmpty()) return
        val path = Path()
        path.moveTo(pts.first().first, pts.first().second)

        for (i in 0 until pts.size - 1) {
            val xc = (pts[i].first + pts[i + 1].first) / 2f
            path.cubicTo(xc, pts[i].second, xc, pts[i + 1].second, pts[i + 1].first, pts[i + 1].second)
        }

        val shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            accentLight,
            accentDark,
            Shader.TileMode.CLAMP
        )
        linePaint.shader = shader
        canvas.drawPath(path, linePaint)
        linePaint.shader = null
    }

    private fun drawDataPoints(
        canvas: Canvas, pts: List<Pair<Float, Float>>
    ) {
        pts.forEachIndexed { i, (x, y) ->
            val isLast = i == pts.size - 1
            val radius = if (isLast) 10f else 4.5f

            if (isLast) {
                pointGlowPaint.color = adjustAlpha(accentColor, 0.38f)
                canvas.drawCircle(x, y, radius + 10f, pointGlowPaint)
                pointGlowPaint.color = adjustAlpha(accentColor, 0.56f)
                canvas.drawCircle(x, y, radius + 5f, pointGlowPaint)
            }

            pointPaint.color = if (isLast) accentDark else accentLight
            canvas.drawCircle(x, y, radius, pointPaint)
            canvas.drawCircle(x, y, radius, pointRingPaint)
        }
    }

    private fun drawEmptyHint(canvas: Canvas, cx: Float, cy: Float) {
        val paint = Paint().apply {
            color = Color.parseColor("#9CA3AF")
            textSize = 30f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("等待数据...", cx, cy, paint)
    }
}