package com.example.smsforwarder.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** 首页转发趋势折线图：网格 + 面积 + 折线 + 圆点 + 首尾日期标签，无第三方依赖。 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6750A4")
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6750A4")
        style = Paint.Style.FILL
    }
    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A6750A4")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE7F5")
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A8A8E")
        textSize = sp(10f)
    }

    private var labels: List<String> = emptyList()
    private var values: List<Int> = emptyList()

    fun setData(labels: List<String>, values: List<Int>) {
        this.labels = labels
        this.values = values
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = dp(22f)
        val padRight = dp(10f)
        val padTop = dp(8f)
        val padBottom = dp(18f)
        val plotWidth = width - padLeft - padRight
        val plotHeight = height - padTop - padBottom
        if (plotWidth <= 0 || plotHeight <= 0) return

        for (i in 0..2) {
            val y = padTop + plotHeight * i / 2f
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
        }
        if (values.isEmpty()) return

        val maxValue = max(values.maxOrNull() ?: 1, 1)
        val count = values.size
        val stepX = if (count > 1) plotWidth / (count - 1f) else plotWidth / 2f
        fun xAt(index: Int): Float = if (count > 1) padLeft + stepX * index else padLeft + plotWidth / 2f
        fun yAt(value: Int): Float = padTop + plotHeight - plotHeight * (value.toFloat() / maxValue)

        val linePath = Path()
        val areaPath = Path()
        values.forEachIndexed { index, value ->
            val x = xAt(index)
            val y = yAt(value)
            if (index == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, padTop + plotHeight)
                areaPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
        }
        if (count > 1) {
            areaPath.lineTo(xAt(count - 1), padTop + plotHeight)
            areaPath.close()
            canvas.drawPath(areaPath, areaPaint)
        }
        canvas.drawPath(linePath, linePaint)

        values.forEachIndexed { index, value ->
            val x = xAt(index)
            val y = yAt(value)
            canvas.drawCircle(x, y, dp(3.4f), dotHaloPaint)
            canvas.drawCircle(x, y, dp(2.2f), dotPaint)
        }

        if (labels.isNotEmpty()) {
            val first = labels.first()
            canvas.drawText(first, padLeft, height - dp(4f), labelPaint)
            if (labels.size > 2) {
                val midIndex = labels.size / 2
                val midText = labels[midIndex]
                val midWidth = labelPaint.measureText(midText)
                val midX = xAt(midIndex)
                canvas.drawText(midText, min(midX - midWidth / 2f, width - padRight - midWidth), height - dp(4f), labelPaint)
            }
            val last = labels.last()
            val lastWidth = labelPaint.measureText(last)
            canvas.drawText(last, width - padRight - lastWidth, height - dp(4f), labelPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
