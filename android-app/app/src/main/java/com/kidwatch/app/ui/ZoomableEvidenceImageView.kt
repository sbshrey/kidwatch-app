package com.kidwatch.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.min

class ZoomableEvidenceImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var normalizedScale = 1f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = drawMatrix
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetZoom() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post { resetZoom() }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(normalizedScale > 1f)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && normalizedScale > 1f) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        drawMatrix.postTranslate(dx, dy)
                        fixTranslation()
                        imageMatrix = drawMatrix
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun resetZoom() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val drawableWidth = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val drawableHeight = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val baseScale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * baseScale) / 2f
        val dy = (viewHeight - drawableHeight * baseScale) / 2f

        drawMatrix.reset()
        drawMatrix.postScale(baseScale, baseScale)
        drawMatrix.postTranslate(dx, dy)
        normalizedScale = 1f
        imageMatrix = drawMatrix
    }

    private fun applyZoom(targetScale: Float, focusX: Float, focusY: Float) {
        val clampedTarget = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val scaleFactor = clampedTarget / normalizedScale
        drawMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY)
        normalizedScale = clampedTarget
        fixTranslation()
        imageMatrix = drawMatrix
    }

    private fun fixTranslation() {
        val rect = currentImageRect() ?: return
        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= width) {
            deltaX = width / 2f - rect.centerX()
        } else {
            if (rect.left > 0f) deltaX = -rect.left
            if (rect.right < width) deltaX = width - rect.right
        }

        if (rect.height() <= height) {
            deltaY = height / 2f - rect.centerY()
        } else {
            if (rect.top > 0f) deltaY = -rect.top
            if (rect.bottom < height) deltaY = height - rect.bottom
        }

        drawMatrix.postTranslate(deltaX, deltaY)
    }

    private fun currentImageRect(): RectF? {
        val drawable = drawable ?: return null
        val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        return rect
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyZoom(
                targetScale = normalizedScale * detector.scaleFactor,
                focusX = detector.focusX,
                focusY = detector.focusY
            )
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (normalizedScale > DEFAULT_DOUBLE_TAP_SCALE) {
                resetZoom()
            } else {
                applyZoom(DEFAULT_DOUBLE_TAP_SCALE, e.x, e.y)
            }
            return true
        }
    }

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 4.5f
        private const val DEFAULT_DOUBLE_TAP_SCALE = 2.5f
    }
}
