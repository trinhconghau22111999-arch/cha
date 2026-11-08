package com.desktopmini.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Mũi tên ← vẽ bằng nét trên Canvas, KHÔNG dùng ký tự "←" của font.
 * Font trên nhiều máy TV không có glyph này nên nút hiện ô trống / ký tự lạ;
 * vẽ hình học thì giống nhau trên mọi máy (Android 4.4 trở lên).
 */
class ArrowLeftDrawable(private val sizePx: Int, color: Int, strokePx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun draw(canvas: Canvas) {
        val r = bounds
        val w = r.width().toFloat()
        val h = r.height().toFloat()
        val cx = r.left + w / 2f
        val cy = r.top + h / 2f
        val half = minOf(w, h) * 0.38f     // nửa chiều dài mũi tên
        val head = half * 0.62f            // độ dài hai cánh đầu mũi tên
        val tipX = cx - half
        canvas.drawLine(cx + half, cy, tipX, cy, paint)          // thân
        val arrowHead = Path().apply {                           // đầu mũi tên
            moveTo(tipX + head, cy - head)
            lineTo(tipX, cy)
            lineTo(tipX + head, cy + head)
        }
        canvas.drawPath(arrowHead, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
