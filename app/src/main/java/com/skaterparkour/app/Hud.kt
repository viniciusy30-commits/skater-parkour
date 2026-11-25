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
        Btn("prev", "◀"), Btn("next", "▶"), Btn("gear", "⚙"),
        Btn("atk", "ATQ"), Btn("s1", "1"), Btn("s2", "2"), Btn("s3", "3"), Btn("s4", "4"), Btn("ult", "ULT"), Btn("dash", "ESQ"), Btn("mode", "MODO")
    )
    private val cbIds = setOf("atk", "s1", "s2", "s3", "s4", "ult", "dash")
    private val pkIds = setOf("jump", "crouch", "drop", "aim", "emote", "act", "die")
    private fun vis(b: Btn): Boolean = if (b.id in pkIds || b.id == "mode") false else true
    private val rf = RectF()
    private val tp = FloatArray(2)
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
        set("mode", w - d(84f), d(32f), d(24f))
        set("atk", w - d(96f), h - d(92f), d(52f))
        set("s1", w - d(214f), h - d(62f), d(33f))
        set("s2", w - d(196f), h - d(142f), d(33f))
        set("s3", w - d(148f), h - d(206f), d(33f))
        set("s4", w - d(80f), h - d(232f), d(33f))
        set("ult", w - d(290f), h - d(110f), d(36f))
        set("dash", w - d(36f), h - d(168f), d(28f))
    }

    private fun hit(x: Float, y: Float) = btns.firstOrNull { vis(it) && hypot(x - it.x, y - it.y) < it.rad * 1.2f }

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
            "atk", "s1", "s2", "s3", "s4", "ult", "dash" -> Input.press(b.id)
            "mode" -> { Input.combatMode = !Input.combatMode; Input.jumpHeld = false; Input.crouchHeld = false; Input.aimMode = false }
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
        c.drawText("${g.station + 1}/${g.stations.size}", width / 2f, d(50f), pText)
        // depuração
        pText.textAlign = Paint.Align.LEFT; pText.textSize = d(10f); pText.color = 0xCCFFFFFF.toInt()
        c.drawText(g.status(), d(12f), d(18f), pText)
        if (g.info().isNotEmpty()) c.drawText(g.info(), d(12f), d(32f), pText)
        pText.textAlign = Paint.Align.CENTER
        drawBars(c, g)
        drawWorld(c, g)
        r.lastErr?.let { pText.textSize = d(9f); pText.color = 0xFFFF8080.toInt(); c.drawText(it.take(120), width / 2f, height - d(8f), pText) }
        drawJoy(c)
        for (b in btns) if (vis(b)) drawBtn(c, b, g)
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
        val lab = if (b.id == "mode") (if (Input.combatMode) "PARK" else "LUTA") else b.label
        c.drawText(lab, b.x, b.y + pText.textSize * .34f, pText)
        val ci = when (b.id) { "s1" -> 0; "s2" -> 1; "s3" -> 2; "s4" -> 3; "ult" -> 4; "dash" -> 5; else -> -1 }
        if (ci >= 0) {
            val fr = g.combat.cdFrac(ci)
            rf.set(b.x - rad, b.y - rad, b.x + rad, b.y + rad)
            if (fr > 0f) { pFill.color = 0xB0000000.toInt(); c.drawArc(rf, -90f, 360f * fr, true, pFill) }
            else if (!g.combat.canCast(ci)) { pFill.color = 0x70000000; c.drawCircle(b.x, b.y, rad, pFill) }
        }
    }

    private fun withA(col: Int, a: Float): Int = ((clampf(a, 0f, 1f) * 255f).toInt() shl 24) or (col and 0x00FFFFFF)

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, col: Int) {
        pFill.color = 0x90000000.toInt(); c.drawRect(x, y, x + w, y + h, pFill)
        pFill.color = col; c.drawRect(x, y, x + w * clampf(frac, 0f, 1f), y + h, pFill)
        pStroke.color = 0x88E9C47E.toInt(); pStroke.strokeWidth = d(1f); c.drawRect(x, y, x + w, y + h, pStroke)
    }

    private fun drawBars(c: Canvas, g: Game) {
        val cb = g.combat
        bar(c, d(12f), d(42f), d(150f), d(9f), cb.hp / cb.maxHp, 0xFFC8362A.toInt())
        bar(c, d(12f), d(55f), d(150f), d(9f), cb.mana / cb.maxMana, 0xFF3B7BE0.toInt())
    }

    private fun proj(m: FloatArray, x: Float, y: Float, z: Float): Boolean {
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw < 0.2f) return false
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        tp[0] = (cx / cw * 0.5f + 0.5f) * width; tp[1] = (0.5f - cy / cw * 0.5f) * height
        return true
    }

    /** Anel no chão (elipse em perspectiva) com raio [rad] metros. */
    private fun groundRing(c: Canvas, m: FloatArray, x: Float, y: Float, z: Float, rad: Float, col: Int, wd: Float) {
        if (!proj(m, x, y, z)) return
        val cx = tp[0]; val cy = tp[1]
        if (!proj(m, x + rad, y, z)) return
        val rx = abs(tp[0] - cx)
        if (!proj(m, x, y, z + rad)) return
        val ry = abs(tp[1] - cy)
        rf.set(cx - rx, cy - ry, cx + rx, cy + ry)
        pStroke.color = col; pStroke.strokeWidth = wd
        c.drawOval(rf, pStroke)
    }

    private fun drawWorld(c: Canvas, g: Game) {
        val m = r.vpOut; val cb = g.combat; val f = cb.foe; val pl = g.player
        // inimigo: anel no chão, nome e barra de vida
        if (f.state != 5) {
            groundRing(c, m, f.x, f.y + 0.03f, f.z, 0.9f, 0xAAE0402A.toInt(), d(2.5f))
            if (proj(m, f.x, f.y + 2.3f, f.z)) {
                val bx = tp[0]; val by = tp[1]
                pText.textSize = d(10f); pText.color = 0xFFFFD0C8.toInt()
                c.drawText("GUERREIRO SOMBRA", bx, by - d(6f), pText)
                bar(c, bx - d(40f), by - d(2f), d(80f), d(7f), f.hp / Foe.MAXHP, 0xFFD23B2E.toInt())
            }
        }
        // aura da ultimate
        if (cb.ultT > 0f) {
            val pulse = 0.55f + 0.35f * sin(g.time * 9f)
            groundRing(c, m, pl.x, pl.y + 0.03f, pl.z, 1.1f + 0.15f * sin(g.time * 7f), withA(0xFFFF2A1A.toInt(), pulse), d(4f))
            groundRing(c, m, pl.x, pl.y + 0.03f, pl.z, 1.7f, withA(0xFFFF7A2A.toInt(), pulse * 0.6f), d(2f))
        }
        // efeitos
        for (e in cb.fx) {
            val p = clampf(e.age / e.life, 0f, 1f)
            when (e.kind) {
                0 -> groundRing(c, m, e.x, e.y + 0.05f, e.z, e.r * smooth(p) + 0.1f, withA(e.color, 1f - p), d(5f) * (1f - p) + d(1f))
                2 -> {
                    groundRing(c, m, e.x, e.y + 0.05f, e.z, e.r, withA(e.color, 0.9f - 0.4f * p), d(3f))
                    groundRing(c, m, e.x, e.y + 0.05f, e.z, e.r * (0.4f + 0.3f * sin(p * 20f)), withA(e.color, 0.7f), d(2f))
                }
                else -> if (proj(m, e.x, e.y, e.z)) {
                    val s = d(46f) * (0.7f + 0.5f * smooth(p * 2f))
                    pStroke.color = withA(e.color, 1f - p * p); pStroke.strokeWidth = d(5f) * (1f - 0.5f * p)
                    pStroke.strokeCap = Paint.Cap.ROUND
                    c.drawLine(tp[0] - s, tp[1] - s, tp[0] + s, tp[1] + s, pStroke)
                    c.drawLine(tp[0] + s, tp[1] - s, tp[0] - s, tp[1] + s, pStroke)
                    pStroke.strokeCap = Paint.Cap.BUTT
                }
            }
        }
        // números de dano
        for (p in cb.pops) {
            if (proj(m, p.x, p.y + p.age * 0.9f, p.z)) {
                pText.textSize = d(19f); pText.color = withA(p.color, 1f - clampf((p.age - 0.6f) / 0.5f, 0f, 1f))
                c.drawText(p.text, tp[0], tp[1], pText)
            }
        }
        // clarão de tela
        if (cb.flash > 0f) { pFill.color = withA(cb.flashColor, cb.flash * 0.7f); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
        if (cb.hp <= 0f) {
            pText.textSize = d(30f); pText.color = 0xFFE0402A.toInt()
            c.drawText("DERROTADO", width / 2f, height / 2f, pText)
        }
    }
}
