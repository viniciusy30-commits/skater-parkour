package com.skaterparkour.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Joystick desenhado no próprio app. */
class JoystickView(context: Context) : View(context) {
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 138, 61) }
    private var kx = 0f
    private var ky = 0f
    private var pointer = -1
    private var back: ValueAnimator? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = width * 0.32f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointer == -1) {
                    back?.cancel()
                    pointer = e.getPointerId(e.actionIndex)
                    move(e, e.actionIndex, cx, cy, maxR)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointer)
                if (i >= 0) move(e, i, cx, cy, maxR)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP || e.getPointerId(e.actionIndex) == pointer) {
                    pointer = -1
                    release()
                }
            }
        }
        return true
    }

    private fun move(e: MotionEvent, i: Int, cx: Float, cy: Float, maxR: Float) {
        var dx = e.getX(i) - cx
        var dy = e.getY(i) - cy
        val len = sqrt(dx * dx + dy * dy)
        if (len > maxR) {
            dx = dx / len * maxR
            dy = dy / len * maxR
        }
        kx = dx
        ky = dy
        Input.jx = dx / maxR
        Input.jy = -dy / maxR
        invalidate()
    }

    private fun release() {
        Input.jx = 0f
        Input.jy = 0f
        val sx = kx
        val sy = ky
        back = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                kx = sx * f
                ky = sy * f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, width * 0.46f, basePaint)
        c.drawCircle(cx, cy, width * 0.46f, ringPaint)
        c.drawCircle(cx + kx, cy + ky, width * 0.19f, knobPaint)
    }
}

/** Botão redondo com ícone desenhado (pular, engrenagem, recomeçar). */
class IconButton(context: Context, private val kind: Int) : View(context) {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 20, 28, 40) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255); style = Paint.Style.STROKE
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 138, 61) }
    var onClick: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                if (kind == JUMP) Input.jump = true
            }
            MotionEvent.ACTION_UP -> {
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                if (kind != JUMP) {
                    val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
                    if (inside) onClick?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        val r = w * 0.48f
        ring.strokeWidth = w * 0.035f
        ink.strokeWidth = w * 0.075f
        c.drawCircle(cx, cy, r, if (kind == JUMP) accent else bg)
        c.drawCircle(cx, cy, r, ring)
        when (kind) {
            JUMP -> {
                val p = Path()
                p.moveTo(cx - w * 0.2f, cy + w * 0.02f)
                p.lineTo(cx, cy - w * 0.18f)
                p.lineTo(cx + w * 0.2f, cy + w * 0.02f)
                p.moveTo(cx - w * 0.2f, cy + w * 0.2f)
                p.lineTo(cx, cy)
                p.lineTo(cx + w * 0.2f, cy + w * 0.2f)
                c.drawPath(p, ink)
            }
            GEAR -> {
                val rr = w * 0.17f
                c.drawCircle(cx, cy, rr, ink)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val x1 = cx + (cos(a) * (rr + w * 0.02f)).toFloat()
                    val y1 = cy + (sin(a) * (rr + w * 0.02f)).toFloat()
                    val x2 = cx + (cos(a) * (rr + w * 0.12f)).toFloat()
                    val y2 = cy + (sin(a) * (rr + w * 0.12f)).toFloat()
                    c.drawLine(x1, y1, x2, y2, ink)
                }
            }
            RESET -> {
                val rr = w * 0.22f
                val rect = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
                c.drawArc(rect, -60f, 290f, false, ink)
                val ax = cx + (cos(Math.toRadians(-60.0)) * rr).toFloat()
                val ay = cy + (sin(Math.toRadians(-60.0)) * rr).toFloat()
                val p = Path()
                p.moveTo(ax + w * 0.1f, ay - w * 0.02f)
                p.lineTo(ax, ay)
                p.lineTo(ax + w * 0.02f, ay - w * 0.12f)
                c.drawPath(p, ink)
            }
        }
    }

    companion object {
        const val JUMP = 0
        const val GEAR = 1
        const val RESET = 2
    }
}
