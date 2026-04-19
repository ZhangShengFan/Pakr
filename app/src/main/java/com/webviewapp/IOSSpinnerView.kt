package com.webviewapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

class IOSSpinnerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val strokeWidth = 3f * dp
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = 3f * dp
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }

    private var angle = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val oval = RectF()

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            angle = (angle + 8f) % 360f
            invalidate()
            handler.postDelayed(this, 16L)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pad = strokeWidth / 2f + dp * 2
        oval.set(pad, pad, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)
        // 渐变圆弧：尾部透明 → 头部不透明
        for (i in 0..270 step 3) {
            val alpha = (i.toFloat() / 270f * 220f + 35f).toInt().coerceIn(0, 255)
            paint.alpha = alpha
            canvas.drawArc(oval, i.toFloat(), 3f, false, paint)
        }
        canvas.restore()
    }

    fun start() {
        if (running) return
        running = true
        handler.post(runnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }
}
