package com.skaterparkour.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Interface: joystick flutuante, botões de ação, teleporte entre estações, câmera por arrasto e pinça. */
@SuppressLint("ViewConstructor")
class Hud(ctx: Context, private val r: Renderer, private val onSettings: () -> Unit) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private fun d(v: Float) = v * dp

    private class Btn(val id: String, val label: String, var x: Float = 0f, var y: Float = 0f, var rad: Float = 0f, var held: Boolean = false)
    private val btns = listOf(
        Btn("jump", "PULAR"), Btn("crouch", "AGACHAR"), Btn("drop", "SOLTAR"),
        Btn("aim", "MIRAR"), Btn("emote", "EMOTE"), Btn("act", "AÇÃO"), Btn("die", "MORRER"),
        Btn("prev", "◀"), Btn("next", "▶"), Btn("gear", "⚙")
    )
    private val gold = 0xFFE9C47E.toInt()
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    private val role = HashMap<Int, String>()
    private val last = HashMap<Int, FloatArray>()
    private var joyId = -1; private val joyC = FloatArray(2); private val joyP = FloatArray(2)
    private var pinchD = 0f

    init { setWillNotDraw(false) }

    private fun layout() {
        val w = width.toFloat(); val h = height.toFloat()
        fun set(id: String, x: Float, y: Float, rad: Float) { btns.first { it.id == id }.apply { this.x = x; this.y = y; this.rad = rad } }
        set("jump", w - d(96f), h - d(92f), d(52f))
        set("crouch", w - d(214f), h - d(60f), d(40f))
        set("drop", w - d(168f), h - d(168f), d(36f))
        set("aim", w - d(300f), h - d(100f), d(34f))
        set("emote", w - d(60f), h - d(190f), d(30f))
        set("act", w - d(250f), h - d(176f), d(30f))
        set("die", w - d(300f), d(32f), d(24f))
        set("prev", w / 2 - d(120f), d(30f), d(20f))
        set("next", w / 2 + d(120f), d(30f), d(20f))
        set("gear", w - d(34f), d(32f), d(18f))
    }

    private fun hit(x: Float, y: Float) = btns.firstOrNull { hypot(x - it.x, y - it.y) < it.rad * 1.2f }

    private fun press(b: Btn) {
        b.held = true
        when (b.id) {
            "jump" -> { Input.jumpHeld = true; Input.press("jump") }
            "crouch" -> { Input.crouchHeld = true; Input.press("crouch") }
            "drop" -> Input.press("drop")
            "aim" -> Input.aimMode = !Input.aimMode
            "emote" -> Input.press("emote")
            "act" -> Input.press("act")
            "die" -> Input.press("die")
            "prev" -> Input.press("prev")
            "next" -> Input.press("next")
            "gear" -> onSettings()
        }
    }

    private fun release(b: Btn) {
        b.held = false
        if (b.id == "jump") Input.jumpHeld = false
        if (b.id == "crouch") Input.crouchHeld = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                last[id] = floatArrayOf(x, y)
                val b = hit(x, y)
                if (b != null) { role[id] = "btn:" + b.id; press(b) }
                else if (joyId == -1 && x < width * .5f && y > height * .25f) {
                    role[id] = "joy"; joyId = id; joyC[0] = x; joyC[1] = y; joyP[0] = x; joyP[1] = y
                } else {
                    role[id] = "cam"
                    if (role.values.count { it == "cam" } == 2) pinchD = pinchDist(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val cams = ArrayList<Int>()
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                    when (role[id]) {
                        "joy" -> {
                            var dx = x - joyC[0]; var dy = y - joyC[1]; val l = hypot(dx, dy); val m = d(62f)
                            if (l > m) { joyC[0] += dx * (1 - m / l); joyC[1] += dy * (1 - m / l); dx = x - joyC[0]; dy = y - joyC[1] }
                            joyP[0] = x; joyP[1] = y
                            Input.jx = dx / m; Input.jy = -dy / m
                        }
                        "cam" -> cams.add(id)
                    }
                }
                if (cams.size >= 2) {
                    val nd = pinchDist(e)
                    if (pinchD > 0f && nd > 0f) Input.addZoom(nd / pinchD)
                    pinchD = nd
                } else if (cams.size == 1) {
                    val id = cams[0]; val i = e.findPointerIndex(id); val p = last[id]
                    if (i >= 0 && p != null) { val k = .0052f * Input.sens; Input.addCam((e.getX(i) - p[0]) * k, (e.getY(i) - p[1]) * k) }
                }
                for (i in 0 until e.pointerCount) last[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) (0 until e.pointerCount).map { e.getPointerId(it) } else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    if (id == joyId) { joyId = -1; Input.jx = 0f; Input.jy = 0f }
                    role[id]?.let { ro -> if (ro.startsWith("btn:")) btns.firstOrNull { it.id == ro.substring(4) }?.let { release(it) } }
                    role.remove(id); last.remove(id)
                }
                pinchD = 0f
            }
        }
        return true
    }

    private fun pinchDist(e: MotionEvent): Float {
        val cams = role.filterValues { it == "cam" }.keys.toList()
        if (cams.size < 2) return 0f
        val a = e.findPointerIndex(cams[0]); val b = e.findPointerIndex(cams[1])
        if (a < 0 || b < 0) return 0f
        return hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
    }

    override fun onDraw(c: Canvas) { try { draw0(c) } catch (_: Throwable) { postInvalidateOnAnimation() } }

    private fun draw0(c: Canvas) {
        layout()
        val g = r.game; val err = r.error
        if (err != null || g == null || r.loadMsg.isNotEmpty()) {
            c.drawColor(0xFF0E0C0B.toInt())
            pText.color = gold; pText.textSize = d(26f)
            c.drawText("SKATER PARKOUR", width / 2f, height / 2f - d(10f), pText)
            pText.textSize = d(13f); pText.color = 0xCCFFFFFF.toInt()
            c.drawText((err ?: r.loadMsg).take(140), width / 2f, height / 2f + d(22f), pText)
            postInvalidateOnAnimation(); return
        }
        if (g.underwater) { pFill.color = 0x4A1F6FB0; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
        // estação atual
        pText.textSize = d(13f); pText.color = 0xFFFFFFFF.toInt()
        c.drawText(g.stationName(), width / 2f, d(36f), pText)
        pText.textSize = d(10f); pText.color = 0xCCE9C47E.toInt()
        c.drawText("${g.station + 1}/${TestMap.stations.size}", width / 2f, d(50f), pText)
        // depuração
        pText.textAlign = Paint.Align.LEFT; pText.textSize = d(10f); pText.color = 0xCCFFFFFF.toInt()
        c.drawText(g.status(), d(12f), d(18f), pText)
        if (g.info().isNotEmpty()) c.drawText(g.info(), d(12f), d(32f), pText)
        pText.textAlign = Paint.Align.CENTER
        r.lastErr?.let { pText.textSize = d(9f); pText.color = 0xFFFF8080.toInt(); c.drawText(it.take(120), width / 2f, height - d(8f), pText) }
        drawJoy(c)
        for (b in btns) drawBtn(c, b, g)
        postInvalidateOnAnimation()
    }

    private fun drawJoy(c: Canvas) {
        val on = joyId != -1
        val cx = if (on) joyC[0] else d(130f); val cy = if (on) joyC[1] else height - d(110f)
        pFill.color = if (on) 0x38000000 else 0x20000000; c.drawCircle(cx, cy, d(62f), pFill)
        pStroke.color = if (on) 0x88E9C47E.toInt() else 0x40E9C47E; pStroke.strokeWidth = d(1.5f); c.drawCircle(cx, cy, d(62f), pStroke)
        val kx = if (on) cx + clampf(joyP[0] - cx, -d(62f), d(62f)) else cx
        val ky = if (on) cy + clampf(joyP[1] - cy, -d(62f), d(62f)) else cy
        pFill.color = if (on) 0xCCE9C47E.toInt() else 0x55E9C47E; c.drawCircle(kx, ky, d(24f), pFill)
    }

    private fun drawBtn(c: Canvas, b: Btn, g: Game) {
        val rad = b.rad * (if (b.held) .93f else 1f)
        val big = b.rad > d(30f)
        val dim = b.id == "drop" && g.player.st != Player.St.HANG && g.player.st != Player.St.BEAM
        val on = b.id == "aim" && Input.aimMode
        pFill.color = if (b.held || on) 0xD0584020.toInt() else if (dim) 0x70100D0B else 0xB0201A14.toInt()
        c.drawCircle(b.x, b.y, rad, pFill)
        pStroke.strokeWidth = d(2f); pStroke.color = if (dim) 0x66594633 else gold
        c.drawCircle(b.x, b.y, rad, pStroke)
        pText.color = if (dim) 0x88E9C47E.toInt() else gold
        pText.textSize = if (big) rad * .34f else rad * .55f
        if (b.label.length <= 2) pText.textSize = rad * 1.0f
        c.drawText(b.label, b.x, b.y + pText.textSize * .34f, pText)
    }
}
