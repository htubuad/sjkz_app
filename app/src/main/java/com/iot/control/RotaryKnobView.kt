package com.iot.control

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 模拟旋钮控件
 *
 * 圆形旋钮，可拖动选择数值。
 * 默认范围 0~100，可设置 min/max/step。
 * 角度范围：从 -135° 到 +135°（共270°旋转范围）。
 * 所有尺寸根据 View 大小自适应缩放，支持小尺寸（如 140dp）。
 */
class RotaryKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 数值范围
    var min: Float = 0f
        set(value) { field = value; updateAngleFromValue(); invalidate() }
    var max: Float = 100f
        set(value) { field = value; updateAngleFromValue(); invalidate() }
    var step: Float = 1f
        set(value) { field = value; invalidate() }

    /** 当前值 */
    var value: Float = 25f
        set(value) {
            val clamped = value.coerceIn(min, max)
            val stepped = if (step > 0f) {
                min + (Math.round((clamped - min) / step).toFloat()) * step
            } else clamped
            field = stepped.coerceIn(min, max)
            updateAngleFromValue()
            invalidate()
            onValueChanged?.invoke(field)
        }

    /** 值变化回调（拖拽中实时触发，但不下发） */
    var onValueChanged: ((Float) -> Unit)? = null

    /** 松手时回调（仅松手时触发下发） */
    var onValueFinalized: ((Float) -> Unit)? = null

    // 起始角度（度数），底部为起点
    private val startAngle = 135f      // 起始角度（-150°方向）
    private val sweepAngle = 240f     // 可旋转总角度

    // 画笔
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F3F4F6")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E5E7EB")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#3B82F6")
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1F2937")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937")
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textAlign = Paint.Align.CENTER
    }

    // 拖拽状态
    private var isDragging = false
    private var dragStartAngle: Float = 0f
    private var dragStartValue: Float = 0f
    // 上次拖拽的角度，用于增量累加（防止拖出边界后继续累加）
    private var lastDragAngle: Float = 0f

    init {
        updateAngleFromValue()
        isClickable = true
        isFocusable = true
    }

    private fun updateAngleFromValue() {
        val range = (max - min).coerceAtLeast(0.0001f)
        val ratio = ((value - min) / range).coerceIn(0f, 1f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = 140
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f
        val scale = minOf(width, height) / 240f

        canvas.drawCircle(cx, cy, radius, bgPaint)

        val strokeWidth = 14f * scale
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        val inset = strokeWidth / 2f
        val rect = RectF(
            cx - radius + inset, cy - radius + inset,
            cx + radius - inset, cy + radius - inset
        )
        canvas.drawArc(rect, 90f + (startAngle / 2f), sweepAngle, false, trackPaint)

        val range = (max - min).coerceAtLeast(0.0001f)
        val ratio = ((value - min) / range).coerceIn(0f, 1f)
        canvas.drawArc(rect, 90f + (startAngle / 2f), sweepAngle * ratio, false, progressPaint)

        val angleRad = Math.toRadians((90f + (startAngle / 2f) + sweepAngle * ratio).toDouble())
        val indicatorRadius = radius * 0.7f
        val indicatorX = (cx + indicatorRadius * Math.cos(angleRad)).toFloat()
        val indicatorY = (cy + indicatorRadius * Math.sin(angleRad)).toFloat()
        canvas.drawCircle(indicatorX, indicatorY, 10f * scale, indicatorPaint)

        textPaint.textSize = 36f * scale
        unitPaint.textSize = 16f * scale
        val displayValue = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        val textBaseline = cy + textPaint.textSize / 3f
        canvas.drawText(displayValue, cx, textBaseline, textPaint)
        canvas.drawText("°C", cx, textBaseline + unitPaint.textSize * 1.2f, unitPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val cx = width / 2f
        val cy = height / 2f
        val x = event.x - cx
        val y = event.y - cy

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val dist = Math.sqrt((x * x + y * y).toDouble())
                val radius = minOf(width, height) / 2f * 0.85f
                if (dist > radius * 1.2f) return false
                isDragging = true
                dragStartAngle = getTouchAngle(x, y)
                lastDragAngle = dragStartAngle
                dragStartValue = value
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val currentAngle = getTouchAngle(x, y)
                // 计算与上一帧的角度增量（处理跨越0°）
                var delta = currentAngle - lastDragAngle
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                lastDragAngle = currentAngle
                // 角度增量转值增量
                val valueDelta = (delta / sweepAngle) * (max - min)
                val newValue = value + valueDelta
                // 关键：到达边界后保持不变，超出范围的增量被丢弃
                if (newValue < min) {
                    // 已在最小值，只有正方向增量才会生效
                    if (valueDelta > 0) value = min
                    // valueDelta <= 0 时忽略，保持 min 不变
                } else if (newValue > max) {
                    if (valueDelta < 0) value = max
                } else {
                    value = newValue
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    // 仅松手时触发下发
                    onValueFinalized?.invoke(value)
                    performClick()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchAngle(x: Float, y: Float): Float {
        var angle = Math.toDegrees(Math.atan2(y.toDouble(), x.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        return angle
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
