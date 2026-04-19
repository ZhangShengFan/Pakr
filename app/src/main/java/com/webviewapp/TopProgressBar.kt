package com.webviewapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class TopProgressBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.BLACK
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }
    private var progress = 0

    fun setBarColor(color: Int) {
        paint.color = color
        invalidate()
    }

    fun setProgress(value: Int) {
        progress = value.coerceIn(0, 100)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0) return
        val cap  = paint.strokeWidth / 2f
        val endX = (measuredWidth.toFloat() * progress / 100f).coerceAtLeast(cap)
        canvas.drawLine(cap, measuredHeight / 2f, endX, measuredHeight / 2f, paint)
    }
}
