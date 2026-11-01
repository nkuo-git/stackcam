package com.nick.stackcam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/** ImageView with pinch zoom, double-tap zoom and panning; plays nicely inside a ViewPager2. */
class ZoomImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : AppCompatImageView(ctx, attrs) {

    var onSingleTap: (() -> Unit)? = null

    private val base = Matrix()     // fit-center
    private val user = Matrix()     // zoom/pan on top of base
    private val drawMatrix = Matrix()
    private var scale = 1f
    private val maxScale = 6f
    private var animator: ValueAnimator? = null

    init { scaleType = ScaleType.MATRIX }

    val isZoomed get() = scale > 1.02f

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    fun resetZoom() {
        animator?.cancel()
        user.reset(); scale = 1f
        computeBase(); apply()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBase(); apply()
    }

    private fun computeBase() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        base.setRectToRect(RectF(0f, 0f, dw, dh), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
    }

    private fun apply() {
        clamp()
        drawMatrix.set(base); drawMatrix.postConcat(user)
        imageMatrix = drawMatrix
    }

    private fun imageRect(): RectF? {
        val d = drawable ?: return null
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        val m = Matrix(base); m.postConcat(user); m.mapRect(r)
        return r
    }

    /** Keeps the image filling the view when zoomed and centred when it's smaller than the view. */
    private fun clamp() {
        val r = imageRect() ?: return
        var dx = 0f; var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left
        else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top
        else if (r.bottom < height) dy = height - r.bottom
        user.postTranslate(dx, dy)
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val target = (scale * f).coerceIn(1f, maxScale)
        val real = target / scale
        scale = target
        user.postScale(real, real, fx, fy)
        apply()
    }

    private fun animateTo(target: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val start = scale
        animator = ValueAnimator.ofFloat(start, target).apply {
            duration = 220
            addUpdateListener { zoomBy((it.animatedValue as Float) / scale, fx, fy) }
            start()
        }
    }

    /** Can the (zoomed) image still pan in this horizontal direction? */
    private fun canPan(dx: Float): Boolean {
        val r = imageRect() ?: return false
        return if (dx > 0) r.right > width + 1f else r.left < -1f
    }

    override fun canScrollHorizontally(direction: Int): Boolean = isZoomed && canPan(direction.toFloat())

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onSingleTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateTo(if (isZoomed) 1f else 2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed) return false
            if (e2.pointerCount == 1 && !canPan(dx)) {
                // At the edge: let the pager take over to swipe to the next photo.
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            user.postTranslate(-dx, -dy)
            apply()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(isZoomed)
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

}
