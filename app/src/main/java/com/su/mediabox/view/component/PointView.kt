package com.su.mediabox.view.component

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.su.mediabox.pluginapi.util.UIUtil.dp
import com.su.mediabox.util.getSpecSize

class PointView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {

    init {

    }

    private val paint = Paint().apply {
        color = Color.RED
        isAntiAlias = true
        isDither = true
        style = Paint.Style.FILL_AND_STROKE
    }

    private var pointSize = 8F.dp

    var pointColor: Int
        set(value) {
            paint.color = value
            invalidate()
        }
        get() = paint.color

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = pointSize / 2
        canvas.drawCircle(width / 2F, height / 2F, radius, paint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val default = 8.dp
        val width = getSpecSize(default, widthMeasureSpec)
        val height = getSpecSize(default, heightMeasureSpec)
        pointSize = (if (width < height) width else height) / 2F
        setMeasuredDimension(width, height)
    }

}