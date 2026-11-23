package com.skaterparkour.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * Interface do jogo (estilo MOBA/MMO, limpa): joystick flutuante, barra de habilidades em arco
 * com recarga radial, quadro do alvo no topo, números de dano. Também trata câmera e zoom.
 */
@SuppressLint("ViewConstructor")
class Hud(ctx: Context, private val r: Renderer, private val onSettings: () -> Unit) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private fun d(v: Float) = v * dp

    private class Btn(val id: String, var x: Float = 0f, var y: Float = 0f, var rad: Float = 0f, val cd: Int = -1, var pressT: Long = 0)
    private val btns = listOf(
        Btn("basic", cd = 0), Btn("s1", cd = 1), Btn("s2", cd = 2), Btn("s3", cd = 3), Btn("s4", cd = 4), Btn("ult", cd = 5),
        Btn("roll"), Btn("jump"), Btn("lock"), Btn("nocd"), Btn("gear")
    )
    private val gold = 0xFFE9C47E.toInt(); private val goldD = 0xFF8A6A35.toInt()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val pIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val vp = FloatArray(16)

    // toques
    private val role = HashMap<Int, String>()
    private val down = HashMap<Int, FloatArray>()
    private var joyId = -1; private val joyC = FloatArray(2); private val joyP = FloatArray(2)
    private var pinchD = 0f
    private var s4Id = -1

    init { setWillNotDraw(false) }

    private fun layout() {
        val w = width.toFloat(); val h = height.toFloat()
        val bx = w - d(112f); val by = h - d(100f)
        fun set(id: String, x: Float, y: Float, rad: Float) { btns.first { it.id == id }.apply { this.x = x; this.y = y; this.rad = rad } }
        set("basic", bx, by, d(50f))
        val R = d(122f)
        val ang = floatArrayOf(190f, 152f, 114f, 76f)
        val ids = arrayOf("s1", "s2", "s3", "s4")
        for (i in 0 until 4) { val a = Math.toRadians(ang[i].toDouble()); set(ids[i], bx + cos(a).toFloat() * R, by - sin(a).toFloat() * R, d(34f)) }
        set("ult", bx - d(232f), by - d(60f), d(38f))
        set("roll", bx - d(214f), by + d(44f), d(27f))
        set("jump", bx + d(72f), by + d(42f), d(25f))
        set("gear", w - d(34f), d(32f), d(18f))
        set("nocd", w - d(80f), d(32f), d(18f))
        set("lock", w - d(126f), d(32f), d(18f))
    }

    // ------------------------------------------------------------- toque
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val g = r.game
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                down[id] = floatArrayOf(x, y, x, y)
                val b = btns.firstOrNull { hypot(x - it.x, y - it.y) < it.rad * 1.18f }
                if (b != null) {
                    role[id] = "btn:" + b.id; b.pressT = System.currentTimeMillis()
                    when (b.id) {
                        "s4" -> { s4Id = id; Input.aimActive = false; Input.aimHeld = true; Input.press("s4down") }
                        "gear" -> onSettings()
                        "basic", "s1", "s2", "s3", "ult", "roll", "jump", "lock", "nocd" -> Input.press(b.id)
                    }
                } else if (joyId == -1 && x < width * .45f && y > height * .3f) {
                    role[id] = "joy"; joyId = id; joyC[0] = x; joyC[1] = y; joyP[0] = x; joyP[1] = y
                } else {
                    role[id] = "cam"
                    val cams = role.filterValues { it == "cam" }.keys
                    if (cams.size == 2) pinchD = pinchDist(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val cams = ArrayList<Int>()
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                    val dn = down[id] ?: continue
                    when (role[id]) {
                        "joy" -> {
                            var dx = x - joyC[0]; var dy = y - joyC[1]; val l = hypot(dx, dy); val m = d(62f)
                            if (l > m) { // a base acompanha o dedo
                                joyC[0] += dx * (1 - m / l); joyC[1] += dy * (1 - m / l); dx = x - joyC[0]; dy = y - joyC[1]
                            }
                            joyP[0] = x; joyP[1] = y
                            Input.jx = dx / m; Input.jy = -dy / m
                        }
                        "cam" -> {
                            cams.add(id)
                        }
                        "btn:s4" -> {
                            val b = btns.first { it.id == "s4" }
                            val dx = x - b.x; val dy = y - b.y; val l = hypot(dx, dy)
                            if (l > d(14f)) { Input.aimX = dx / l; Input.aimY = -dy / l; Input.aimActive = true }
                        }
                    }
                }
                if (cams.size >= 2) {
                    val nd = pinchDist(e)
                    if (pinchD > 0f && nd > 0f) Input.addZoom(nd / pinchD)
                    pinchD = nd
                } else if (cams.size == 1) {
                    val id = cams[0]; val i = e.findPointerIndex(id); val dn = down[id]!!
                    val x = e.getX(i); val y = e.getY(i)
                    val k = .0052f * Input.sens
                    Input.addCam((x - dn[2]) * k, (y - dn[3]) * k)
                }
                for (i in 0 until e.pointerCount) { val id = e.getPointerId(i); down[id]?.let { it[2] = e.getX(i); it[3] = e.getY(i) } }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ids = if (e.actionMasked == MotionEvent.ACTION_CANCEL) (0 until e.pointerCount).map { e.getPointerId(it) } else listOf(e.getPointerId(e.actionIndex))
                for (id in ids) {
                    if (id == joyId) { joyId = -1; Input.jx = 0f; Input.jy = 0f }
                    if (id == s4Id) { s4Id = -1; Input.aimHeld = false; Input.press("s4up"); Input.aimActive = false }
                    role.remove(id); down.remove(id)
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

    // ------------------------------------------------------------- desenho
    override fun onDraw(c: Canvas) {
        layout()
        val g = r.game
        val err = r.error
        if (err != null || g == null || r.loadMsg.isNotEmpty()) {
            c.drawColor(0xFF0E0C0B.toInt())
            pText.color = gold; pText.textSize = d(30f)
            c.drawText("GUERREIRO", width / 2f, height / 2f - d(10f), pText)
            pText.textSize = d(14f); pText.color = 0xCCFFFFFF.toInt()
            c.drawText(err ?: r.loadMsg, width / 2f, height / 2f + d(22f), pText)
            postInvalidateOnAnimation(); return
        }
        drawOverlay(c, g)
        drawTarget(c, g)
        drawTexts(c, g)
        drawJoy(c)
        r.lastErr?.let { pText.textSize = d(9f); pText.color = 0xFFFF8080.toInt(); c.drawText(it.take(120), width / 2f, height - d(8f), pText) }
        for (b in btns) drawBtn(c, b, g)
        postInvalidateOnAnimation()
    }

    private var vigShader: RadialGradient? = null
    private var vigW = 0
    /** clarão vermelho e vinheta da Ultimate (flash/vig do HTML) */
    private fun drawOverlay(c: Canvas, g: Game) {
        val fa = min(.4f, g.flashA * .6f)
        if (fa > .01f) { pFill.shader = null; pFill.color = Color.argb((fa * 255).toInt(), 255, 42, 26); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
        val v = g.vigA * g.vigA * (3 - 2 * g.vigA)
        if (v > .01f) {
            if (vigShader == null || vigW != width) {
                vigW = width
                vigShader = RadialGradient(width / 2f, height / 2f, hypot(width.toFloat(), height.toFloat()) * .55f,
                    intArrayOf(0x00000000, 0x00000000, 0xC8780000.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            }
            pFill.shader = vigShader; pFill.alpha = (v * 255).toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill); pFill.shader = null; pFill.alpha = 255
        }
    }

    private fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= .05f) return false
        out[0] = (cx / cw * .5f + .5f) * width; out[1] = (1 - (cy / cw * .5f + .5f)) * height
        return true
    }

    private fun drawTarget(c: Canvas, g: Game) {
        val w = d(250f); val h = d(15f); val x = width / 2f - w / 2; val y = d(26f)
        pText.textSize = d(13f); pText.color = 0xFFF1E3C4.toInt()
        c.drawText("Boneco de Treino", width / 2f, y - d(6f), pText)
        pFill.color = 0xB0100C0A.toInt(); c.drawRoundRect(x - d(2f), y - d(2f), x + w + d(2f), y + h + d(2f), d(4f), d(4f), pFill)
        val hp = clampf(g.dHp / 100f, 0f, 1f)
        pFill.shader = LinearGradient(x, y, x, y + h, 0xFFC8362A.toInt(), 0xFF6E1410.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(x, y, x + w * hp, y + h, d(3f), d(3f), pFill); pFill.shader = null
        pFill.color = 0x30FFFFFF; c.drawRect(x, y, x + w * hp, y + h * .42f, pFill)
        pStroke.color = goldD; pStroke.strokeWidth = d(1.4f)
        c.drawRoundRect(x - d(2f), y - d(2f), x + w + d(2f), y + h + d(2f), d(4f), d(4f), pStroke)
        pText.textSize = d(10f); pText.color = 0xFFFFFFFF.toInt()
        c.drawText("${(hp * 100).roundToInt()}%", width / 2f, y + h - d(3.5f), pText)
        if (g.dummyStunned()) { pText.color = gold; c.drawText("ATORDOADO", width / 2f, y + h + d(14f), pText) }
    }

    private val tp = FloatArray(2)
    private fun drawTexts(c: Canvas, g: Game) {
        r.projected(vp)
        val list = synchronized(g.texts) { g.texts.map { it } }
        for (t in list) {
            if (!project(t.x, t.y + t.t * .9f, t.z, tp)) continue
            val a = (1f - smooth((t.t - .6f) / .5f))
            val pop = 1f + .5f * (1f - smooth(t.t / .12f))
            pText.textSize = d(if (t.crit) 26f else 18f) * pop
            pText.color = 0xFF000000.toInt(); pText.alpha = (a * 200).toInt()
            c.drawText(t.v.toString() + if (t.crit) "!" else "", tp[0] + d(1.5f), tp[1] + d(1.5f), pText)
            pText.color = t.col; pText.alpha = (a * 255).toInt()
            c.drawText(t.v.toString() + if (t.crit) "!" else "", tp[0], tp[1], pText)
        }
        pText.alpha = 255
    }

    private fun drawJoy(c: Canvas) {
        val cx: Float; val cy: Float
        val on = joyId != -1
        if (on) { cx = joyC[0]; cy = joyC[1] } else { cx = d(130f); cy = height - d(110f) }
        pFill.color = if (on) 0x38000000 else 0x20000000
        c.drawCircle(cx, cy, d(62f), pFill)
        pStroke.color = if (on) 0x88E9C47E.toInt() else 0x40E9C47E; pStroke.strokeWidth = d(1.5f)
        c.drawCircle(cx, cy, d(62f), pStroke)
        val kx = if (on) cx + clampf(joyP[0] - cx, -d(62f), d(62f)) else cx
        val ky = if (on) cy + clampf(joyP[1] - cy, -d(62f), d(62f)) else cy
        pFill.color = if (on) 0xCCE9C47E.toInt() else 0x55E9C47E
        c.drawCircle(kx, ky, d(24f), pFill)
    }

    private fun drawBtn(c: Canvas, b: Btn, g: Game) {
        val small = b.id == "gear" || b.id == "lock" || b.id == "nocd"
        val pressed = System.currentTimeMillis() - b.pressT < 120 || (b.id == "s4" && s4Id != -1)
        val rad = b.rad * (if (pressed) .93f else 1f)
        val isUlt = b.id == "ult"
        val accent = if (isUlt) 0xFFFF5A3C.toInt() else gold
        val active = (b.id == "lock" && g.lock) || (b.id == "nocd" && g.noCd)
        // fundo
        pFill.shader = RadialGradient(b.x, b.y - rad * .3f, rad * 1.2f, if (isUlt) 0xE0401310.toInt() else 0xD8241D17.toInt(), 0xE00C0A09.toInt(), Shader.TileMode.CLAMP)
        if (small) { pFill.shader = null; pFill.color = if (active) 0xCC3A2C17.toInt() else 0x88100D0B.toInt() }
        c.drawCircle(b.x, b.y, rad, pFill); pFill.shader = null
        // ícone
        val left = if (b.cd >= 0) g.cdLeft(b.cd) else 0f
        val ready = left <= 0f
        pIcon.color = if (ready) accent else 0xFF7A6E60.toInt()
        pIcon.strokeWidth = rad * .085f
        icon(c, b.id, b.x, b.y, rad * .55f, active)
        // recarga radial
        if (!ready && b.cd >= 0) {
            val frac = left / Game.CD[b.cd]
            pFill.color = 0xA0000000.toInt()
            c.drawArc(b.x - rad, b.y - rad, b.x + rad, b.y + rad, -90f, -360f * frac, true, pFill)
            if (left >= .95f || b.cd == 0) {
                pText.textSize = rad * .62f; pText.color = 0xFFFFFFFF.toInt()
                val s = if (left >= 1f) left.toInt().plus(1).toString() else String.format("%.1f", left)
                if (b.cd != 0) c.drawText(s, b.x, b.y + rad * .22f, pText)
            }
        }
        // aro
        pStroke.strokeWidth = if (small) d(1.2f) else d(2.2f)
        pStroke.color = if (ready && !small) accent else if (active) gold else 0x99594633.toInt()
        c.drawCircle(b.x, b.y, rad, pStroke)
        if (!small && ready) {
            pStroke.strokeWidth = d(1f); pStroke.color = 0x40FFFFFF
            c.drawCircle(b.x, b.y, rad - d(3.5f), pStroke)
        }
        // Skill 4: indicador de arrasto
        if (b.id == "s4" && s4Id != -1 && Input.aimActive) {
            pStroke.color = 0xAAE9C47E.toInt(); pStroke.strokeWidth = d(2f)
            c.drawCircle(b.x, b.y, rad * 2.2f, pStroke)
            pFill.color = 0xDDE9C47E.toInt()
            c.drawCircle(b.x + Input.aimX * rad * 2.2f, b.y - Input.aimY * rad * 2.2f, d(9f), pFill)
        }
    }

    private fun icon(c: Canvas, id: String, x: Float, y: Float, s: Float, active: Boolean) {
        path.reset()
        when (id) {
            "basic" -> { // dois machados cruzados
                for (k in listOf(-1f, 1f)) {
                    c.drawLine(x - s * .8f * k, y + s * .85f, x + s * .55f * k, y - s * .6f, pIcon)
                    path.reset()
                    val hx = x + s * .45f * k; val hy = y - s * .5f
                    path.moveTo(hx - s * .05f * k, hy - s * .42f)
                    path.quadTo(hx + s * .55f * k, hy - s * .1f, hx + s * .25f * k, hy + s * .45f)
                    c.drawPath(path, pIcon)
                }
            }
            "s1" -> { c.drawLine(x - s, y - s, x + s, y + s, pIcon); c.drawLine(x + s, y - s, x - s, y + s, pIcon)
                pIcon.strokeWidth *= .5f; c.drawCircle(x, y, s * .25f, pIcon); pIcon.strokeWidth *= 2f }
            "s2" -> { path.moveTo(x, y - s); path.lineTo(x + s * .85f, y - s * .6f); path.quadTo(x + s * .8f, y + s * .5f, x, y + s)
                path.quadTo(x - s * .8f, y + s * .5f, x - s * .85f, y - s * .6f); path.close(); c.drawPath(path, pIcon)
                c.drawLine(x, y - s * .55f, x, y + s * .55f, pIcon) }
            "s3" -> { c.drawLine(x - s, y + s * .7f, x + s, y + s * .7f, pIcon)
                for (k in -2..2) { val a = -PI / 2 + k * .45; c.drawLine(x + cos(a).toFloat() * s * .35f, y + s * .5f + sin(a).toFloat() * s * .35f, x + cos(a).toFloat() * s * 1.05f, y + s * .5f + sin(a).toFloat() * s * 1.1f, pIcon) } }
            "s4" -> { pIcon.strokeWidth *= .8f
                c.drawOval(x - s * .95f, y + s * .05f, x - s * .1f, y + s * .55f, pIcon)
                c.drawOval(x - s * .35f, y - s * .25f, x + s * .5f, y + s * .25f, pIcon)
                c.drawLine(x + s * .4f, y - s * .2f, x + s, y - s * .85f, pIcon)
                c.drawLine(x + s, y - s * .85f, x + s * .45f, y - s * .85f, pIcon); c.drawLine(x + s, y - s * .85f, x + s, y - s * .3f, pIcon)
                pIcon.strokeWidth /= .8f }
            "ult" -> { // chama
                path.moveTo(x, y - s * 1.05f); path.quadTo(x + s * .9f, y - s * .1f, x + s * .5f, y + s * .7f)
                path.quadTo(x, y + s * 1.05f, x - s * .5f, y + s * .7f); path.quadTo(x - s * .9f, y - s * .1f, x - s * .1f, y - s * .5f)
                path.quadTo(x - s * .05f, y - s * .1f, x + s * .15f, y); path.quadTo(x + s * .15f, y - s * .55f, x, y - s * 1.05f)
                c.drawPath(path, pIcon) }
            "roll" -> { c.drawArc(x - s, y - s, x + s, y + s, 200f, 270f, false, pIcon)
                c.drawLine(x + s * .1f, y - s, x - s * .35f, y - s * 1.2f, pIcon); c.drawLine(x + s * .1f, y - s, x - s * .25f, y - s * .6f, pIcon) }
            "jump" -> { c.drawLine(x, y + s, x, y - s, pIcon); c.drawLine(x, y - s, x - s * .6f, y - s * .35f, pIcon); c.drawLine(x, y - s, x + s * .6f, y - s * .35f, pIcon) }
            "lock" -> { pIcon.color = if (active) gold else 0xAAE9C47E.toInt()
                c.drawCircle(x, y, s * .7f, pIcon); c.drawLine(x, y - s * 1.1f, x, y - s * .35f, pIcon); c.drawLine(x, y + s * 1.1f, x, y + s * .35f, pIcon)
                c.drawLine(x - s * 1.1f, y, x - s * .35f, y, pIcon); c.drawLine(x + s * 1.1f, y, x + s * .35f, y, pIcon) }
            "nocd" -> { pIcon.color = if (active) gold else 0xAAE9C47E.toInt()
                path.moveTo(x, y); path.cubicTo(x - s * .4f, y - s * .7f, x - s * 1.2f, y - s * .5f, x - s * 1.1f, y)
                path.cubicTo(x - s * 1.2f, y + s * .5f, x - s * .4f, y + s * .7f, x, y)
                path.cubicTo(x + s * .4f, y - s * .7f, x + s * 1.2f, y - s * .5f, x + s * 1.1f, y)
                path.cubicTo(x + s * 1.2f, y + s * .5f, x + s * .4f, y + s * .7f, x, y); c.drawPath(path, pIcon) }
            "gear" -> { pIcon.color = 0xAAE9C47E.toInt(); c.drawCircle(x, y, s * .45f, pIcon)
                for (k in 0 until 8) { val a = k * PI / 4; c.drawLine(x + cos(a).toFloat() * s * .7f, y + sin(a).toFloat() * s * .7f, x + cos(a).toFloat() * s * 1.05f, y + sin(a).toFloat() * s * 1.05f, pIcon) } }
        }
    }
}
