package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.*
import kotlin.random.Random

// ---- células do atlas (16 colunas x 8 linhas, 2048x1024) ----
private const val C_SWORD = 0; private const val C_SWORD_N = 24
private const val C_CRIT = 24; private const val C_CRIT_N = 24
private const val C_EXPL = 48; private const val C_EXPL_N = 24
private const val C_SCORCH = 72
private const val C_SMOKE = 78; private const val C_SMOKE_N = 5
private const val C_FIRE = 83; private const val C_FLAME = 85
private const val C_STAR = 93; private const val C_MAGIC = 96
private const val C_RING = 97; private const val C_GLOW = 98; private const val C_LRING = 99
private const val C_SPARK = 100; private const val C_FLARE = 102; private const val C_BURST = 103
private const val C_TRAIL63 = 108; private const val C_FLARE21 = 110; private const val C_GMASK = 113

// modo de mistura: 0 aditivo colorido, 1 transparência (fumaça, chamuscado), 2 aditivo quente (branco no centro)
private const val M_ADD = 0; private const val M_ALPHA = 1; private const val M_HOT = 2
// tipo de quadrado: 0 de frente para a câmera, 1 deitado no chão, 2 faixa esticada
private const val K_BILL = 0; private const val K_GROUND = 1; private const val K_STREAK = 2
// curva de opacidade ao longo da vida
private const val E_DEF = 0; private const val E_FADE = 1; private const val E_FADE2 = 2; private const val E_SMOKE = 3
private const val E_SLASH = 4; private const val E_BELL = 5; private const val E_EXPL = 6; private const val E_SCORCH = 7
private const val E_HOLD = 8

private const val MAXS = 900          // sprites vivos ao mesmo tempo
private const val MAXQ = 2600         // quadrados por quadro (sprites + corrente + aura)
private const val STRIDE = 11         // x y z u v r g b a célula modo
private const val TAU = 6.2831855f
private const val PIF = 3.1415927f

private const val VS_SRC = "#version 300 es\n" +
    "layout(location=0) in vec3 aP; layout(location=1) in vec2 aU; layout(location=2) in vec4 aC; layout(location=3) in vec2 aR;\n" +
    "uniform mat4 uVP; out vec2 vUV; out vec4 vCol; out vec2 vR;\n" +
    "void main(){ vUV=aU; vCol=aC; vR=aR; gl_Position=uVP*vec4(aP,1.); }\n"

private const val FS_SRC = "#version 300 es\n" +
    "precision highp float;\n" +
    "in vec2 vUV; in vec4 vCol; in vec2 vR; out vec4 o;\n" +
    "uniform sampler2D uAtl; uniform vec2 uAG;\n" +
    "void main(){\n" +
    " float cl=floor(vR.x+.5); vec2 t=clamp(vUV*.5+.5,0.,1.);\n" +
    " vec2 ce=vec2(mod(cl,uAG.x),floor(cl/uAG.x));\n" +
    " vec2 tc=(ce+.008+vec2(t.x,1.-t.y)*.984)/uAG;\n" +
    " vec3 tx=texture(uAtl,tc).rgb; float L=max(tx.r,max(tx.g,tx.b));\n" +
    " vec3 c=vCol.rgb; float A=vCol.a; vec4 r;\n" +
    " if(vR.y<.5) r=vec4(tx*c,A);\n" +
    " else if(vR.y<1.5) r=vec4(c*(.75+.4*L),clamp(L*1.15,0.,1.)*A);\n" +
    " else r=vec4(mix(c*L,vec3(L),L*L*.8)*1.35,A);\n" +
    " o=r;\n" +
    "}\n"

/**
 * Efeitos visuais 3D do combate (portados do visualizador): impactos, ondas de choque,
 * explosões, aura da Ultimate, feixe de luz, corrente da Skill 4 e estrelas de atordoamento.
 * Tudo é feito de quadrados com imagens do atlas `w/fx_atlas.jpg`, desenhados por cima da cena.
 */
class Vfx {
    class Spr(var cell: Int) {
        var x = 0f; var y = 0f; var z = 0f
        var vx = 0f; var vy = 0f; var vz = 0f
        var grav = 0f; var dr = 0f
        var rot = 0f; var rotv = 0f
        var nf = 1
        var mode = M_ADD
        var kind = K_BILL
        var env = E_DEF
        var s0 = 1f; var s1 = 1f; var asp = 1f
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        var life = 1f; var delay = 0f; var age = 0f
        var easeOut = false
        var fixedDir = false
        var dirx = 0f; var diry = 1f; var dirz = 0f
    }

    private val list = ArrayList<Spr>()
    private var time = 0f
    private var px = 0f; private var py = 0f; private var pz = 0f; private var pyaw = 0f

    // aura da Ultimate
    private var auraLv = 0f; private var auraAcc = 0f; private var wispAcc = 0f
    private var gatherT = 0f; private var gatherAcc = 0f
    // corrente da Skill 4
    private var chainT = -1f; private var chainHit = false; private var chainFoe: Foe? = null
    private val cp = FloatArray(6)
    // estrelas de atordoamento
    private var stunT = 0f; private var stunDur = 1f; private var stunFoe: Foe? = null

    // GL
    private var prog = 0; private var vao = 0; private var vbo = 0; private var tex = 0
    private var uVP = 0; private var uAtl = 0; private var uAG = 0
    private var ready = false
    private val buf = FloatArray(MAXQ * 6 * STRIDE)
    private var fb: FloatBuffer? = null
    private var nv = 0
    private val camR = FloatArray(3); private val camU = FloatArray(3); private val camE = FloatArray(3)

    fun initGl(ctx: Context) {
        try {
            tex = Gl.texture(ctx, "w/fx_atlas.jpg", true, false)
            prog = Gl.program(VS_SRC, FS_SRC)
            uVP = glGetUniformLocation(prog, "uVP"); uAtl = glGetUniformLocation(prog, "uAtl"); uAG = glGetUniformLocation(prog, "uAG")
            vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
            vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glBufferData(GL_ARRAY_BUFFER, MAXQ * 6 * STRIDE * 4, null, GL_DYNAMIC_DRAW)
            val st = STRIDE * 4
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, st, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, st, 12)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, st, 20)
            glEnableVertexAttribArray(3); glVertexAttribPointer(3, 2, GL_FLOAT, false, st, 36)
            glBindVertexArray(0)
            fb = Gl.floats(MAXQ * 6 * STRIDE)
            ready = true
        } catch (t: Throwable) {
            Log.e("Vfx", "init", t)
        }
    }

    // ------------------------------------------------------------------------------------ utilidades
    private fun sstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    private fun chan(c: Int, i: Int): Float = ((c shr (16 - 8 * i)) and 0xFF) / 255f

    private fun spr(cell: Int, x: Float, y: Float, z: Float, life: Float, s0: Float, s1: Float,
                    cr: Float, cg: Float, cb: Float, ca: Float, mode: Int, env: Int): Spr {
        val s = Spr(cell)
        s.x = x; s.y = y; s.z = z; s.life = max(0.01f, life); s.s0 = s0; s.s1 = s1
        s.cr = cr; s.cg = cg; s.cb = cb; s.ca = ca; s.mode = mode; s.env = env
        s.rot = rnd(0f, TAU)
        if (list.size < MAXS) list.add(s)
        return s
    }

    private fun env(e: Int, q: Float): Float = when (e) {
        E_FADE -> 1f - q
        E_FADE2 -> 1f - q * q
        E_SMOKE -> min(1f, q * 6f) * (1f - q)
        E_SLASH -> 1f - sstep(0.65f, 1f, q)
        E_BELL -> sstep(0f, 0.12f, q) * (1f - sstep(0.4f, 1f, q))
        E_EXPL -> 1f - sstep(0.75f, 1f, q)
        E_SCORCH -> min(1f, q * 30f) * (1f - sstep(0.7f, 1f, q))
        E_HOLD -> sstep(0f, 0.08f, q) * (1f - sstep(0.7f, 1f, q))
        else -> min(1f, q * 12f) * (1f - q * q)
    }

    // ------------------------------------------------------------------------------------ emissores
    /** Pancada no inimigo: clarão, anel, estrela, fagulhas, lâmina de corte e sangue. */
    fun hit(x: Float, y: Float, z: Float, s: Float, big: Boolean, red: Boolean) {
        val cr = 1f; val cg = if (red) 0.14f else 0.75f; val cb = if (red) 0.06f else 0.35f
        val c2g = if (red) 0.24f else 0.5f; val c2b = if (red) 0.1f else 0.15f
        spr(C_FLARE, x, y, z, 0.14f, 0.9f * s, 1.5f * s, 1f, 1f, 1f, 1f, M_ADD, E_FADE)
        val b = spr(C_BURST, x, y, z, 0.28f, 0.4f * s, 1.5f * s, 1f, c2g, c2b, 0.9f, M_ADD, E_FADE2); b.easeOut = true
        spr(C_STAR + 2, x, y, z, 0.2f, 0.7f * s, 1.1f * s, cr, cg, cb, 0.85f, M_ADD, E_FADE)
        val sl = spr(C_SWORD, x, y, z, 0.32f, 1.5f * s, 1.75f * s, cr, cg, cb, 1f, M_HOT, E_SLASH); sl.nf = C_SWORD_N
        if (big) {
            val ch = spr(C_CRIT, x, y, z, 0.42f, 1.0f * s, 1.25f * s, 1f, 1f, 1f, 1f, M_ADD, E_SLASH); ch.nf = C_CRIT_N
            val mg = spr(C_MAGIC, x, y, z, 0.3f, 0.5f * s, 1.6f * s, 1f, 0.55f, 0.3f, 0.9f, M_ADD, E_FADE); mg.easeOut = true
        }
        // fagulhas
        val ns = if (big) 9 else 5
        for (i in 0 until ns) {
            val sp = spr(C_SPARK + i % 2, x, y, z, rnd(0.2f, 0.38f), rnd(0.12f, 0.2f) * s, 0.05f * s, 1f, c2g + 0.3f, c2b + 0.1f, 1f, M_ADD, E_FADE)
            sp.vx = rnd(-4f, 4f); sp.vy = rnd(-1f, 4f); sp.vz = rnd(-4f, 4f); sp.grav = 6f; sp.dr = 1.5f
        }
        // sangue
        val nb = if (big) 11 else 6
        for (i in 0 until nb) {
            val d = spr(C_GLOW, x, y, z, rnd(0.5f, 0.9f), rnd(0.07f, 0.12f) * (0.8f + s * 0.4f), 0.04f, 0.62f, 0.03f, 0.05f, 0.95f, M_ALPHA, E_FADE2)
            d.vx = rnd(-2.6f, 2.6f); d.vy = rnd(1f, 3.8f); d.vz = rnd(-2.6f, 2.6f); d.grav = 9f
        }
        val sm = spr(C_SMOKE + Random.nextInt(C_SMOKE_N), x, y - 0.1f, z, 0.6f, 0.2f * s, 0.5f * s, 0.25f, 0.22f, 0.2f, 0.4f, M_ALPHA, E_SMOKE)
        sm.vy = 0.6f; sm.dr = 1.3f; sm.easeOut = true
    }

    /** "X" de corte da Skill 1: estrela vermelha gigante + duas lâminas cruzadas. */
    fun cross(x: Float, y: Float, z: Float, s: Float, red: Boolean) {
        val cg = if (red) 0.15f else 0.72f; val cb = if (red) 0.06f else 0.3f
        val ch = spr(C_CRIT, x, y, z, 0.5f, 1.7f * s, 2.1f * s, 1f, 1f, 1f, 1f, M_ADD, E_SLASH); ch.nf = C_CRIT_N
        for (i in 0 until 2) {
            val sl = spr(C_SWORD, x, y, z, 0.4f, 2.0f * s, 2.5f * s, 1f, cg, cb, 1f, M_HOT, E_SLASH)
            sl.nf = C_SWORD_N; sl.rot = if (i == 0) 0.7f else 2.4f; sl.delay = i * 0.04f
        }
        spr(C_FLARE, x, y, z, 0.2f, 1.6f * s, 2.6f * s, 1f, 0.8f, 0.7f, 1f, M_ADD, E_FADE)
    }

    /** Explosão de fogo (Rompe-Terra e pancada final da Ultimate). */
    fun blast(x: Float, y: Float, z: Float, s: Float) {
        expl(x, y + 1.35f * s, z, 0.95f * s, 0f)
        expl(x + 0.7f * s, y + 0.9f * s, z + 0.3f * s, 0.6f * s, 0.08f)
        expl(x - 0.7f * s, y + 0.9f * s, z + 0.2f * s, 0.6f * s, 0.13f)
        for (i in 0 until 9) {
            val a = rnd(0f, TAU); val sp = rnd(0.4f, 1.6f) * s
            val sm = spr(C_SMOKE + i % C_SMOKE_N, x + cos(a) * 0.3f * s, y + 0.5f * s, z + sin(a) * 0.3f * s, rnd(1.1f, 1.9f), rnd(0.5f, 0.8f) * s, rnd(1.3f, 1.9f) * s, 0.2f, 0.17f, 0.15f, 0.7f, M_ALPHA, E_SMOKE)
            sm.vx = cos(a) * sp; sm.vz = sin(a) * sp; sm.vy = rnd(0.5f, 1.7f); sm.dr = 1.3f; sm.rotv = rnd(-0.8f, 0.8f); sm.easeOut = true
        }
    }

    /** Rompe-Terra: explosão no chão + onda + chamuscado. */
    fun earthBlast(x: Float, y: Float, z: Float) {
        blast(x, y, z, 1f)
        shock(x, y, z, 4.4f, 0.5f, 0xFFFF7A2A.toInt(), true)
    }

    private fun expl(x: Float, y: Float, z: Float, s: Float, delay: Float) {
        val e = spr(C_EXPL, x, y, z, 0.9f, 1.5f * s, 1.9f * s, 1f, 1f, 1f, 1f, M_ADD, E_EXPL)
        e.nf = C_EXPL_N; e.easeOut = true; e.rot = rnd(-0.3f, 0.3f); e.delay = delay
        val f = spr(C_FLARE, x, y, z, 0.16f, 1.6f * s, 2.4f * s, 1f, 0.9f, 0.75f, 1f, M_ADD, E_FADE)
        f.delay = delay
    }

    /** Onda de choque deitada no chão (anel que se expande), com poeira. */
    fun shock(x: Float, y: Float, z: Float, rad: Float, life: Float, color: Int, big: Boolean) {
        val cr = chan(color, 0); val cg = chan(color, 1); val cb = chan(color, 2)
        val yy = y + 0.07f
        val a = spr(C_RING, x, yy, z, life, 0.5f, rad, cr, cg, cb, 0.95f, M_ADD, E_FADE)
        a.kind = K_GROUND; a.easeOut = true
        val b = spr(C_LRING, x, yy + 0.01f, z, life * 0.85f, 0.3f, rad * 0.85f, cr, cg * 0.8f, cb * 0.8f, 0.85f, M_ADD, E_FADE)
        b.kind = K_GROUND; b.easeOut = true; b.delay = 0.04f
        val c = spr(C_GMASK, x, yy - 0.02f, z, life * 0.7f, rad * 0.9f, rad * 0.55f, cr, cg * 0.6f, cb * 0.6f, 0.55f, M_ADD, E_FADE)
        c.kind = K_GROUND
        val n = if (big) 14 else 7
        for (i in 0 until n) {
            val ang = i.toFloat() / n * TAU + rnd(-0.2f, 0.2f)
            val d = rad * 0.35f
            val sm = spr(C_SMOKE + i % C_SMOKE_N, x + cos(ang) * d, yy + 0.15f, z + sin(ang) * d, rnd(0.7f, 1.2f), rad * 0.12f, rad * 0.28f, 0.3f, 0.26f, 0.22f, 0.55f, M_ALPHA, E_SMOKE)
            sm.vx = cos(ang) * rad * 1.4f; sm.vz = sin(ang) * rad * 1.4f; sm.vy = rnd(0.3f, 0.9f); sm.dr = 3f; sm.rotv = rnd(-0.8f, 0.8f); sm.easeOut = true
        }
        if (big) {
            for (i in 0 until 4) {
                val ang = rnd(0f, TAU); val d = if (i == 0) 0f else rnd(0.2f, 0.6f) * rad
                val sz = rad * (if (i == 0) 1f else rnd(0.45f, 0.7f)) * 0.8f
                val sc = spr(C_SCORCH + i % 3, x + cos(ang) * d, y + 0.012f + i * 0.0006f, z + sin(ang) * d, 6f, sz, sz, 0.03f, 0.02f, 0.02f, 0.95f, M_ALPHA, E_SCORCH)
                sc.kind = K_GROUND
            }
            val g = spr(C_SCORCH, x, y + 0.022f, z, 1.6f, rad * 0.7f, rad * 0.7f, 1f, 0.1f, 0.04f, 0.8f, M_ADD, E_FADE)
            g.kind = K_GROUND
        }
    }

    /** Selo de aviso no chão (Rompe-Terra, antes da pancada). */
    fun seal(x: Float, y: Float, z: Float, rad: Float, life: Float) {
        val a = spr(C_LRING, x, y + 0.06f, z, life, rad, rad, 1f, 0.5f, 0.15f, 0.9f, M_ADD, E_HOLD)
        a.kind = K_GROUND; a.rotv = 3f
        val b = spr(C_RING, x, y + 0.05f, z, life, rad * 0.55f, rad * 0.55f, 1f, 0.7f, 0.25f, 0.8f, M_ADD, E_HOLD)
        b.kind = K_GROUND; b.rotv = -2f
    }

    /** Brilho de energia sendo puxada para o corpo (início da Ultimate). */
    fun gather(dur: Float) { gatherT = dur; gatherAcc = 0f }

    /** Rugido da Ultimate: feixe de luz vertical, onda vermelha e clarão. */
    fun roar(x: Float, y: Float, z: Float) {
        val b1 = spr(C_GMASK, x, y + 4.2f, z, 1.0f, 0.95f, 0.95f, 1f, 0.12f, 0.05f, 0.9f, M_ADD, E_BELL)
        b1.kind = K_STREAK; b1.asp = 6.5f; b1.fixedDir = true; b1.dirx = 0f; b1.diry = 1f; b1.dirz = 0f
        val b2 = spr(C_GMASK, x, y + 4.2f, z, 0.8f, 0.3f, 0.3f, 1f, 0.8f, 0.7f, 1f, M_ADD, E_BELL)
        b2.kind = K_STREAK; b2.asp = 20f; b2.fixedDir = true; b2.dirx = 0f; b2.diry = 1f; b2.dirz = 0f
        shock(x, y, z, 4.5f, 0.7f, 0xFFFF2A1A.toInt(), false)
        spr(C_FLARE, x, y + 1.1f, z, 0.35f, 1.5f, 3.6f, 1f, 0.3f, 0.15f, 1f, M_ADD, E_FADE)
        for (i in 0 until 26) {
            val a = rnd(0f, TAU); val sp = rnd(1.5f, 4.5f)
            val e = spr(C_GLOW, x, y + 0.4f, z, rnd(0.7f, 1.4f), rnd(0.08f, 0.15f), 0.03f, 1f, 0.12f, 0.05f, 1f, M_ADD, E_FADE)
            e.vx = cos(a) * sp; e.vz = sin(a) * sp; e.vy = rnd(1f, 4.5f); e.grav = 2f; e.dr = 0.8f
        }
    }

    /** Começo do arremesso da corrente (Skill 4). */
    fun chainStart(foe: Foe, willHit: Boolean) { chainT = 0f; chainHit = willHit; chainFoe = foe }

    /** Estrelas girando sobre a cabeça do inimigo atordoado. */
    fun stun(foe: Foe, dur: Float) { stunFoe = foe; stunT = dur; stunDur = dur }

    private fun spawnFlame() {
        val ang = rnd(0f, TAU); val rr = rnd(0.4f, 1.1f)
        val fl = Random.nextBoolean()
        val cell = if (fl) C_FLAME + Random.nextInt(2) else C_FIRE + Random.nextInt(2)
        val hot = Random.nextFloat() < 0.35f
        val s = spr(cell, px + cos(ang) * rr, py + rnd(0f, 0.6f), pz + sin(ang) * rr, rnd(0.55f, 1.0f), rnd(0.4f, 0.7f), rnd(0.15f, 0.28f),
            1f, if (hot) 0.55f else 0.13f, if (hot) 0.12f else 0.04f, if (hot) 0.85f else 0.8f, M_ADD, E_DEF)
        s.vx = cos(ang) * 0.2f; s.vz = sin(ang) * 0.2f; s.vy = rnd(1.8f, 3.2f); s.dr = 0.6f
        s.asp = if (fl) 1.6f else 1f
        s.rot = if (fl) rnd(-0.3f, 0.3f) else rnd(0f, TAU)
        s.rotv = if (fl) 0f else rnd(-2f, 2f)
    }

    private fun spawnWisp() {
        val ang = rnd(0f, TAU); val rr = rnd(0.3f, 1.2f)
        val s = spr(C_GLOW, px + cos(ang) * rr, py + rnd(0.1f, 1.2f), pz + sin(ang) * rr, rnd(0.8f, 1.5f), rnd(0.07f, 0.13f), 0.03f, 1f, 0.1f, 0.05f, 1f, M_ADD, E_FADE)
        s.vy = rnd(1f, 2.5f); s.vx = rnd(-0.3f, 0.3f); s.vz = rnd(-0.3f, 0.3f)
    }

    private fun spawnGather() {
        val ang = rnd(0f, TAU); val el = rnd(-0.4f, 0.9f); val rr = rnd(2.4f, 3.6f)
        val sx = px + cos(ang) * rr * cos(el); val sy = py + 1.0f + sin(el) * rr; val sz = pz + sin(ang) * rr * cos(el)
        val life = rnd(0.35f, 0.5f)
        val s = spr(C_GLOW, sx, sy, sz, life, rnd(0.1f, 0.18f), 0.03f, 1f, 0.12f, 0.05f, 1f, M_ADD, E_DEF)
        s.vx = (px - sx) / life; s.vy = (py + 1.0f - sy) / life; s.vz = (pz - sz) / life
    }

    // ------------------------------------------------------------------------------------ atualização
    fun update(dt: Float, x: Float, y: Float, z: Float, yaw: Float, ult: Boolean) {
        time += dt; px = x; py = y; pz = z; pyaw = yaw
        val tg = if (ult) 1f else 0f
        auraLv = if (tg > auraLv) min(tg, auraLv + dt / 0.35f) else max(tg, auraLv - dt / 1.0f)
        if (auraLv > 0.05f) {
            auraAcc += dt * 34f * auraLv
            var n = 0
            while (auraAcc >= 1f && n < 6) { auraAcc -= 1f; n++; spawnFlame() }
            wispAcc += dt * 16f * auraLv
            n = 0
            while (wispAcc >= 1f && n < 4) { wispAcc -= 1f; n++; spawnWisp() }
        } else { auraAcc = 0f; wispAcc = 0f }
        if (gatherT > 0f) {
            gatherT -= dt; gatherAcc += dt * 80f
            var n = 0
            while (gatherAcc >= 1f && n < 8) { gatherAcc -= 1f; n++; spawnGather() }
        }
        if (stunT > 0f) stunT -= dt
        if (chainT >= 0f) {
            chainT += dt
            val life = if (chainHit) 1.1f else 0.55f
            if (chainT > life) chainT = -1f
            else if (chainGeom() && Random.nextFloat() < dt * 32f) {
                val k = Random.nextFloat()
                val s = spr(C_GLOW, cp[0] + (cp[3] - cp[0]) * k, cp[1] + (cp[4] - cp[1]) * k, cp[2] + (cp[5] - cp[2]) * k, rnd(0.3f, 0.6f), rnd(0.03f, 0.06f), 0.01f, 1f, 0.65f, 0.2f, 1f, M_ADD, E_FADE)
                s.vx = rnd(-0.5f, 0.5f); s.vy = rnd(-0.6f, 0.6f); s.vz = rnd(-0.5f, 0.5f); s.grav = 2f; s.dr = 1.5f
            }
        }
        var i = list.size - 1
        while (i >= 0) {
            val s = list[i]
            if (s.delay > 0f) {
                s.delay -= dt
            } else {
                s.age += dt
                val d = exp(-s.dr * dt)
                s.vy -= s.grav * dt
                s.vx *= d; s.vy *= d; s.vz *= d
                s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt
                s.rot += s.rotv * dt
            }
            if (s.age >= s.life) list.removeAt(i)
            i--
        }
    }

    /** Pontas da corrente em [cp]: mão do herói e ponta (que viaja, gruda no inimigo e some). */
    private fun chainGeom(): Boolean {
        if (chainT < 0f) return false
        val fx = sin(pyaw); val fz = cos(pyaw)
        val ax = px + fx * 0.45f; val ay = py + 1.15f; val az = pz + fz * 0.45f
        val f = chainFoe
        val tx: Float; val ty: Float; val tz: Float
        if (chainHit && f != null) { tx = f.x; ty = f.y + 1.2f; tz = f.z }
        else { tx = px + fx * 14f; ty = py + 1.1f; tz = pz + fz * 14f }
        val k: Float = if (chainT < 0.25f) { val q = chainT / 0.25f; 1f - (1f - q) * (1f - q) }
            else if (chainHit) 1f
            else max(0f, 1f - (chainT - 0.25f) / 0.25f)
        cp[0] = ax; cp[1] = ay; cp[2] = az
        cp[3] = ax + (tx - ax) * k; cp[4] = ay + (ty - ay) * k; cp[5] = az + (tz - az) * k
        return k > 0.02f
    }

    // ------------------------------------------------------------------------------------ desenho
    private fun vtx(x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float, cell: Float, mode: Float) {
        var o = nv * STRIDE
        buf[o++] = x; buf[o++] = y; buf[o++] = z; buf[o++] = u; buf[o++] = v
        buf[o++] = r; buf[o++] = g; buf[o++] = b; buf[o++] = a; buf[o++] = cell; buf[o] = mode
        nv++
    }

    private fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float,
                     cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
                     r: Float, g: Float, b: Float, a: Float, cell: Int, mode: Int) {
        if ((nv + 6) * STRIDE > buf.size) return
        val ce = cell.toFloat(); val m = mode.toFloat()
        vtx(ax, ay, az, -1f, -1f, r, g, b, a, ce, m)
        vtx(bx, by, bz, 1f, -1f, r, g, b, a, ce, m)
        vtx(cx, cy, cz, 1f, 1f, r, g, b, a, ce, m)
        vtx(ax, ay, az, -1f, -1f, r, g, b, a, ce, m)
        vtx(cx, cy, cz, 1f, 1f, r, g, b, a, ce, m)
        vtx(dx, dy, dz, -1f, 1f, r, g, b, a, ce, m)
    }

    private fun bill(x: Float, y: Float, z: Float, w: Float, h: Float, rot: Float, r: Float, g: Float, b: Float, a: Float, cell: Int, mode: Int) {
        val cs = cos(rot); val sn = sin(rot)
        val rx = (camR[0] * cs + camU[0] * sn) * w; val ry = (camR[1] * cs + camU[1] * sn) * w; val rz = (camR[2] * cs + camU[2] * sn) * w
        val ux = (camU[0] * cs - camR[0] * sn) * h; val uy = (camU[1] * cs - camR[1] * sn) * h; val uz = (camU[2] * cs - camR[2] * sn) * h
        quad(x - rx - ux, y - ry - uy, z - rz - uz, x + rx - ux, y + ry - uy, z + rz - uz,
            x + rx + ux, y + ry + uy, z + rz + uz, x - rx + ux, y - ry + uy, z - rz + uz, r, g, b, a, cell, mode)
    }

    private fun ground(x: Float, y: Float, z: Float, s: Float, rot: Float, r: Float, g: Float, b: Float, a: Float, cell: Int, mode: Int) {
        val ca = cos(rot) * s; val sa = sin(rot) * s
        quad(x - ca + sa, y, z - sa - ca, x + ca + sa, y, z + sa - ca,
            x + ca - sa, y, z + sa + ca, x - ca - sa, y, z - sa + ca, r, g, b, a, cell, mode)
    }

    private fun streak(x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, len: Float, wid: Float, r: Float, g: Float, b: Float, a: Float, cell: Int, mode: Int) {
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        if (dl < 1e-4f) return
        val ax = dx / dl; val ay = dy / dl; val az = dz / dl
        val vx = x - camE[0]; val vy = y - camE[1]; val vz = z - camE[2]
        var sx = ay * vz - az * vy; var sy = az * vx - ax * vz; var sz = ax * vy - ay * vx
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-5f) return
        sx = sx / sl * wid; sy = sy / sl * wid; sz = sz / sl * wid
        val lx = ax * len; val ly = ay * len; val lz = az * len
        quad(x - lx - sx, y - ly - sy, z - lz - sz, x + lx - sx, y + ly - sy, z + lz - sz,
            x + lx + sx, y + ly + sy, z + lz + sz, x - lx + sx, y - ly + sy, z - lz + sz, r, g, b, a, cell, mode)
    }

    private fun emit(sp: Spr) {
        val q = sp.age / sp.life
        if (q >= 1f) return
        val cell = sp.cell + (if (sp.nf > 1) min(sp.nf - 1, floor(q * sp.nf).toInt()) else 0)
        val k = if (sp.easeOut) 1f - (1f - q) * (1f - q) * (1f - q) else q
        val s = sp.s0 + (sp.s1 - sp.s0) * k
        val a = sp.ca * env(sp.env, q)
        if (a < 0.003f) return
        when (sp.kind) {
            K_GROUND -> ground(sp.x, sp.y, sp.z, s, sp.rot, sp.cr, sp.cg, sp.cb, a, cell, sp.mode)
            K_STREAK -> {
                var dx = 0f; var dy = 1f; var dz = 0f
                if (sp.fixedDir) { dx = sp.dirx; dy = sp.diry; dz = sp.dirz }
                else if (abs(sp.vx) + abs(sp.vy) + abs(sp.vz) > 0.01f) { dx = sp.vx; dy = sp.vy; dz = sp.vz }
                streak(sp.x, sp.y, sp.z, dx, dy, dz, s * sp.asp, s, sp.cr, sp.cg, sp.cb, a, cell, sp.mode)
            }
            else -> bill(sp.x, sp.y, sp.z, s, s * sp.asp, sp.rot, sp.cr, sp.cg, sp.cb, a, cell, sp.mode)
        }
    }

    private fun emitAura() {
        if (auraLv <= 0.02f) return
        val pulse = 0.8f + 0.2f * sin(time * 10f)
        ground(px, py + 0.05f, pz, 2.7f, 0f, 1f, 0.08f, 0.03f, 0.4f * auraLv * pulse, C_GMASK, M_ADD)
        ground(px, py + 0.06f, pz, 2.2f, time * 2.5f, 1f, 0.18f, 0.05f, 0.95f * auraLv * pulse, C_LRING, M_ADD)
        ground(px, py + 0.07f, pz, 1.75f, -time * 1.5f, 1f, 0.3f, 0.06f, 0.7f * auraLv, C_RING, M_ADD)
        bill(px, py + 1.0f, pz, 1.5f, 2.0f, 0f, 1f, 0.12f, 0.05f, 0.3f * auraLv * pulse, C_GMASK, M_ADD)
    }

    private fun emitChain() {
        if (!chainGeom()) return
        val life = if (chainHit) 1.1f else 0.55f
        val a = min(1f, chainT * 12f) * (1f - sstep(life - 0.25f, life, chainT))
        val dx = cp[3] - cp[0]; val dy = cp[4] - cp[1]; val dz = cp[5] - cp[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 0.05f) return
        val ux = dx / len; val uy = dy / len; val uz = dz / len
        val n = max(2, (len / 0.11f).toInt())
        val sag = 0.18f * (1f - min(1f, chainT * 3f))
        val elev = atan2(uy, hypot(ux, uz))
        for (i in 0..n) {
            val s = i.toFloat() / n
            val x = cp[0] + dx * s; val y = cp[1] + dy * s - sag * sin(PIF * s); val z = cp[2] + dz * s
            bill(x, y, z, 0.11f, 0.075f, (if (i % 2 == 1) PIF / 2f else 0f) + elev, 1f, 0.72f, 0.25f, 0.95f * a, C_RING, M_ADD)
        }
        streak((cp[0] + cp[3]) * 0.5f, (cp[1] + cp[4]) * 0.5f, (cp[2] + cp[5]) * 0.5f, ux, uy, uz, len * 0.5f, 0.07f, 1f, 0.6f, 0.15f, 0.4f * a, C_TRAIL63, M_ADD)
        bill(cp[3], cp[4], cp[5], 0.5f, 0.5f, 0f, 1f, 0.55f, 0.12f, 0.6f * a, C_GMASK, M_ADD)
        bill(cp[0], cp[1], cp[2], 0.3f, 0.3f, 0f, 1f, 0.55f, 0.12f, 0.5f * a, C_GMASK, M_ADD)
    }

    private fun emitStun() {
        val f = stunFoe
        if (stunT <= 0f || f == null) return
        val a = min(1f, (stunDur - stunT) * 8f) * min(1f, stunT * 3f)
        for (i in 0 until 4) {
            val ang = time * 5f + i * PIF / 2f
            val x = f.x + cos(ang) * 0.38f; val y = f.y + 2.15f + 0.12f * sin(ang * 2f); val z = f.z + sin(ang) * 0.38f
            bill(x, y, z, 0.17f, 0.17f, time * 6f, 1f, 0.85f, 0.4f, a, C_FLARE21, M_ADD)
            bill(x, y, z, 0.16f, 0.16f, 0f, 1f, 0.6f, 0.15f, 0.5f * a, C_GMASK, M_ADD)
        }
    }

    /** Desenha tudo por cima da cena (sem teste de profundidade, para o corpo grande não esconder os efeitos). */
    fun draw(vp: FloatArray, view: FloatArray, eye: FloatArray) {
        if (!ready) return
        val f = fb ?: return
        camR[0] = view[0]; camR[1] = view[4]; camR[2] = view[8]
        camU[0] = view[1]; camU[1] = view[5]; camU[2] = view[9]
        camE[0] = eye[0]; camE[1] = eye[1]; camE[2] = eye[2]
        nv = 0
        for (s in list) if (s.mode == M_ALPHA && s.delay <= 0f) emit(s)
        val nAlpha = nv
        for (s in list) if (s.mode != M_ALPHA && s.delay <= 0f) emit(s)
        emitAura(); emitChain(); emitStun()
        if (nv == 0) return
        f.clear(); f.put(buf, 0, nv * STRIDE); f.flip()
        glUseProgram(prog)
        glUniformMatrix4fv(uVP, 1, false, vp, 0)
        glActiveTexture(GL_TEXTURE5); glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(uAtl, 5); glUniform2f(uAG, 16f, 8f)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, nv * STRIDE * 4, f, GL_DYNAMIC_DRAW)
        glDisable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_BLEND)
        if (nAlpha > 0) { glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDrawArrays(GL_TRIANGLES, 0, nAlpha) }
        if (nv > nAlpha) { glBlendFunc(GL_SRC_ALPHA, GL_ONE); glDrawArrays(GL_TRIANGLES, nAlpha, nv - nAlpha) }
        glBindVertexArray(0)
        glDisable(GL_BLEND); glDepthMask(true); glEnable(GL_DEPTH_TEST)
        glActiveTexture(GL_TEXTURE0)
    }
}
