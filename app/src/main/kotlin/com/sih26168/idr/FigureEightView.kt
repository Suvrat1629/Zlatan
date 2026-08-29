package com.sih26168.idr

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** Animated figure-8 (lemniscate) diagram — shows the motion pattern to rotate the phone
 *  through during magnetometer calibration. Purely illustrative; carries no sensor data. */
class FigureEightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        color = 0x33808080
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val path = Path()
    private var progress = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    fun setAccentColor(color: Int) {
        markerPaint.color = color
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        path.reset()
        val cx = w / 2f
        val cy = h / 2f
        val a = minOf(w, h) * 0.42f
        val steps = 120
        for (i in 0..steps) {
            val t = (i / steps.toFloat()) * (2 * Math.PI).toFloat()
            val x = cx + a * cos(t)
            val y = cy + a * sin(t) * cos(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, pathPaint)

        val cx = width / 2f
        val cy = height / 2f
        val a = minOf(width, height) * 0.42f
        val t = progress * (2 * Math.PI).toFloat()
        val x = cx + a * cos(t)
        val y = cy + a * sin(t) * cos(t)
        canvas.drawCircle(x, y, a * 0.07f, markerPaint)
    }
}
