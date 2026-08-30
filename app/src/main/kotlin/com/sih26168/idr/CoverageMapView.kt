package com.sih26168.idr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.sih26168.idr.core.nav.MagnetometerCalibrator

/**
 * Live gap map for magnetometer calibration — a grid of cells, one per direction bin, lit up
 * as that direction gets sampled. Real calibration tools (e.g. MotionCal) show a live 3D
 * point cloud so the user can see exactly which part of the sphere is still empty and aim
 * there; a single "62%" progress number gives no sense of where to rotate next. This is a
 * simpler 2D (azimuth x elevation) stand-in for the same idea — cheap to render, no 3D
 * projection needed, but still shows *where* the gaps are instead of just how many remain.
 */
class CoverageMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val cols = MagnetometerCalibrator.GRID_AZIMUTH_BINS
    private val rows = MagnetometerCalibrator.GRID_ELEVATION_BINS

    private val coveredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val uncoveredPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
        color = 0x33808080
    }

    private var grid = BooleanArray(cols * rows)

    fun setAccentColor(color: Int) {
        coveredPaint.color = color
        invalidate()
    }

    fun setGrid(newGrid: BooleanArray) {
        grid = newGrid
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cellW = width / cols.toFloat()
        val cellH = height / rows.toFloat()
        val pad = minOf(cellW, cellH) * 0.1f
        val radius = minOf(cellW, cellH) * 0.15f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val left = col * cellW + pad
                val top = row * cellH + pad
                val right = (col + 1) * cellW - pad
                val bottom = (row + 1) * cellH - pad
                val paint = if (grid.getOrElse(row * cols + col) { false }) coveredPaint else uncoveredPaint
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
            }
        }
    }
}
