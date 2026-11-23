package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import kotlin.math.*

/**
 * Efeitos visuais nativos: partículas com o atlas (fx.jpg 16x8), rastros das lâminas,
 * marcas no chão, indicador de mira, círculo de alerta e corrente.
 */
class Fx(ctx: Context) {
    companion object {
        // células do atlas
        const val SLASH = 0; const val CRIT = 24; const val EXPL = 48; const val TWIRL = 75; const val SMOKE = 78
        const val FLAME = 85; const val JET = 89; const val STAR = 93; const val RUNE = 96; const val RING = 97; const val GLOW = 98
        const val LRING = 99; const val SPARK = 100; const val FLARE = 102; const val BURST = 103; const val STREAK = 104
        const val WISP = 106; const val SHARD = 109; const val FLARE2 = 110; const val SMOKE2 = 111; const val ELLIPSE = 112; const val SOFT = 113

        // modos de desenho
        const val M_HOT = 0f; const val M_SMOKE = 1f; const val M_TRAIL = 2f; const val M_TINT = 3f; const val M_DARK = 4f; const val M_AIM = 5f; const val M_TELE = 6f

        val GOLD = floatArrayOf(1f, .72f, .26f)
        val GOLD2 = floatArrayOf(1f, .86f, .5f)
        val RED = floatArrayOf(1f, .16f, .07f)
        val RED2 = floatArrayOf(1f, .38f, .16f)
        val DUST = floatArrayOf(.55f, .46f, .36f)

    private val VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 aP; layout(location=1) in vec2 aUV; layout(location=2) in vec4 aC; layout(location=3) in vec2 aM;
uniform mat4 uVP; out vec2 vUV; out vec4 vC; out vec2 vM; out vec3 vW;
void main(){ vUV=aUV; vC=aC; vM=aM; vW=aP; gl_Position=uVP*vec4(aP,1.); }"""

    private val FS = """#version 300 es
precision highp float;
in vec2 vUV; in vec4 vC; in vec2 vM; in vec3 vW; uniform sampler2D uAtl; uniform float uTime; out vec4 o;
float h21(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}
vec3 atl(float cell){ vec2 t=clamp(vUV*.5+.5,0.,1.); vec2 ce=vec2(mod(cell,16.),floor(cell/16.));
  vec2 tc=(ce+.008+vec2(t.x,1.-t.y)*.984)/vec2(16.,8.); return texture(uAtl,tc).rgb; }
void main(){
  float m=vM.x; vec3 c=vC.rgb; float A=vC.a; vec4 r;
  if(m<.5){ vec3 tx=atl(vM.y); float L=max(tx.r,max(tx.g,tx.b)); r=vec4(mix(c*L,vec3(L),L*L*.7)*1.35,A); }
  else if(m<1.5){ vec3 tx=atl(vM.y); float L=max(tx.r,max(tx.g,tx.b)); r=vec4(c*(.75+.4*L),clamp(L*1.15,0.,1.)*A); }
  else if(m<2.5){ float v=vUV.y; float age=vUV.x;
     if(vM.y>.5){ float x=v*2.-1.; r=vec4(c*1.2,A*exp(-x*x*3.)); }
     else { float edge=pow(v,2.2); float core=exp(-pow((v-.93)/.07,2.));
     float n=.75+.25*vn(vec2(age*9.-uTime*2.,v*4.));
     r=vec4(mix(c,vec3(1.,.97,.85),core*.7)*1.3,A*(edge*.55+core*.9)*n*smoothstep(1.,.9,v)); } }
  else if(m<3.5){ vec3 tx=atl(vM.y); r=vec4(tx*c*1.6,A); }
  else if(m<4.5){ vec3 tx=atl(vM.y); float L=max(tx.r,max(tx.g,tx.b)); r=vec4(vec3(.02,.015,.012),L*A); }
  else if(m<5.5){ // mira retangular
     float x=abs(vUV.x), y=vUV.y*.5+.5; float len=vM.y;
     float border=smoothstep(.86,.97,x)*(1.-smoothstep(.97,1.,x));
     float fill=.10+.06*sin(y*len*3.-uTime*9.);
     float tip=smoothstep(.9,1.,y);
     float chev=step(fract(y*len*.9-uTime*1.5),.12)*(1.-x)*.35;
     r=vec4(c,(border*.9+fill+chev+tip*.25)*A*smoothstep(0.,.05,y)); }
  else { // círculo de alerta (Skill 3)
     float d=length(vUV); float p=vM.y;
     float ring=exp(-pow((d-.95)/.03,2.)); float fillr=p; float fill=smoothstep(fillr,fillr-.05,d)*.18;
     float ef=exp(-pow((d-fillr)/.03,2.));
     float ang=atan(vUV.y,vUV.x); float tick=step(.5,fract(ang*12./6.2832+uTime*.3))*exp(-pow((d-.8)/.02,2.))*.6;
     r=vec4(c,(ring+fill+ef+tick)*A*smoothstep(1.02,.97,d)); }
  o=r; }"""
    }

    enum class Orient { BILL, GROUND, AXIS, VBILL }

    class P {
        var on = false
        var o = Orient.BILL
        val pos = FloatArray(3); val vel = FloatArray(3)
        val ax = FloatArray(3); val ay = FloatArray(3)   // para AXIS
        var grav = 0f; var drag = 0f
        var s0 = 1f; var s1 = 1f; var sx = 1f            // tamanho inicial/final, esticamento x
        var rot = 0f; var rotV = 0f
        var life = 1f; var age = 0f; var delay = 0f
        var cell = 0; var cells = 1
        val c = FloatArray(3); var a = 1f
        var mode = M_HOT
        var fadeIn = .08f
        var follow: FloatArray? = null
    }

    private val QC = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
    private val ps = Array(1400) { P() }
    private var prog = 0
    private var vao = 0; private var vbo = 0
    private val FL = 11
    private val MAXV = 24000
    private val data = FloatArray(MAXV * FL)
    private val fb = Gl.floats(MAXV * FL)
    private var nAdd = 0; private var nAlpha = 0
    private val addV = FloatArray(MAXV * FL); private val alpV = FloatArray(MAXV * FL / 2)
    var tex = 0
    private var uVP = 0; private var uTime = 0
    var time = 0f

    // câmera (preenchido pelo renderer)
    val camR = FloatArray(3); val camU = FloatArray(3); val eye = FloatArray(3)

    // rastros das lâminas
    inner class Trail {
        val ax = FloatArray(64 * 3); val bx = FloatArray(64 * 3); val t = FloatArray(64)
        var n = 0
        var on = false
        val c = FloatArray(3)
        var w = 1f
        fun push(a: FloatArray, b: FloatArray, now: Float) {
            if (n == 64) { System.arraycopy(ax, 3, ax, 0, 63 * 3); System.arraycopy(bx, 3, bx, 0, 63 * 3); System.arraycopy(t, 1, t, 0, 63); n = 63 }
            for (i in 0 until 3) { ax[n * 3 + i] = a[i]; bx[n * 3 + i] = b[i] }
            t[n] = now; n++
        }
        fun prune(now: Float, maxAge: Float) {
            var k = 0
            while (k < n && now - t[k] > maxAge) k++
            if (k > 0) {
                System.arraycopy(ax, k * 3, ax, 0, (n - k) * 3); System.arraycopy(bx, k * 3, bx, 0, (n - k) * 3); System.arraycopy(t, k, t, 0, n - k); n -= k
            }
        }
    }
    val trails = arrayOf(Trail(), Trail())
    var trailAge = .2f

    // indicadores
    var aimOn = false; val aimFrom = FloatArray(3); var aimYaw = 0f; var aimLen = 10f; var aimW = 1.1f
    var teleOn = 0f; val telePos = FloatArray(3); var teleR = 2f; var teleP = 0f
    var chainOn = false; val chainA = FloatArray(3); val chainB = FloatArray(3); var chainA0 = 1f

    init {
        prog = Gl.program(VS, FS)
        uVP = glGetUniformLocation(prog, "uVP"); uTime = glGetUniformLocation(prog, "uTime")
        vao = Gl.gen(::glGenVertexArrays); vbo = Gl.gen(::glGenBuffers)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, MAXV * FL * 4, null, GL_DYNAMIC_DRAW)
        val st = FL * 4
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, st, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, st, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, st, 20)
        glEnableVertexAttribArray(3); glVertexAttribPointer(3, 2, GL_FLOAT, false, st, 36)
        glBindVertexArray(0)
        tex = Gl.texture(ctx, "w/fx.jpg", mip = true)
    }

    fun clear() { for (p in ps) p.on = false; for (t in trails) t.n = 0; aimOn = false; teleOn = 0f; chainOn = false }

    fun spawn(): P {
        for (p in ps) if (!p.on) { reset(p); p.on = true; return p }
        val p = ps[(Math.random() * ps.size).toInt()]; reset(p); p.on = true; return p
    }

    private fun reset(p: P) {
        p.o = Orient.BILL; p.vel.fill(0f); p.grav = 0f; p.drag = 0f; p.s0 = 1f; p.s1 = 1f; p.sx = 1f; p.rot = 0f; p.rotV = 0f
        p.life = 1f; p.age = 0f; p.delay = 0f; p.cell = 0; p.cells = 1; p.a = 1f; p.mode = M_HOT; p.fadeIn = .06f; p.follow = null
    }

    // ---------- atalhos de efeito ----------
    fun sprite(pos: FloatArray, cell: Int, cells: Int, size0: Float, size1: Float, life: Float, col: FloatArray, a: Float = 1f,
               mode: Float = M_HOT, o: Orient = Orient.BILL, delay: Float = 0f, rot: Float = rnd(0f, 6.28f)): P {
        val p = spawn()
        System.arraycopy(pos, 0, p.pos, 0, 3); p.cell = cell; p.cells = cells; p.s0 = size0; p.s1 = size1; p.life = life
        System.arraycopy(col, 0, p.c, 0, 3); p.a = a; p.mode = mode; p.o = o; p.delay = delay; p.rot = rot
        return p
    }

    /** Faíscas/pequenos fragmentos ao acertar (discretos). */
    fun hitSparks(at: FloatArray, col: FloatArray, n: Int = 8, power: Float = 1f) {
        for (i in 0 until n) {
            val p = sprite(at, SPARK, 1, .09f * power, .02f, rnd(.18f, .34f), col, 1f)
            val a = rnd(0f, 6.28f); val e = rnd(-.2f, 1f); val v = rnd(3f, 7f) * power
            p.vel[0] = cos(a) * v * .8f; p.vel[1] = e * v * .6f + 1.5f; p.vel[2] = sin(a) * v * .8f; p.grav = 9f; p.drag = 2f
        }
        sprite(at, FLARE2, 1, .5f * power, .9f * power, .16f, col, .8f)
    }

    /** Impacto no chão (dourado ou vermelho). big = 1 normal. */
    fun groundImpact(at: FloatArray, col: FloatArray, col2: FloatArray, big: Float) {
        val g = floatArrayOf(at[0], .03f, at[2])
        sprite(g, LRING, 1, .3f * big, 4.2f * big, .55f, col2, .95f, M_HOT, Orient.GROUND)
        sprite(g, RING, 1, .4f * big, 3.2f * big, .42f, col, .8f, M_HOT, Orient.GROUND, .04f)
        sprite(g, GLOW, 1, 2.2f * big, 3f * big, .5f, col, .9f, M_HOT, Orient.GROUND)
        sprite(floatArrayOf(at[0], .25f, at[2]), FLARE, 1, 1.4f * big, 2.6f * big, .22f, col2, 1f)
        // crateira escura que some devagar
        sprite(floatArrayOf(at[0], .02f, at[2]), SMOKE + 4, 1, 1.9f * big, 2.2f * big, 3.2f, floatArrayOf(0f, 0f, 0f), .75f, M_DARK, Orient.GROUND).fadeIn = .02f
        sprite(floatArrayOf(at[0], .025f, at[2]), GLOW, 1, 1.5f * big, 1.4f * big, 1.6f, col, .55f, M_HOT, Orient.GROUND)
        // explosão de energia
        for (i in 0 until 2) {
            val p = sprite(floatArrayOf(at[0] + rnd(-.2f, .2f), .5f * big, at[2] + rnd(-.2f, .2f)), EXPL, 20, 1.6f * big, 2.6f * big, .7f, col2, .9f, M_TINT, delay = i * .05f)
            p.vel[1] = 1.2f
        }
        // poeira
        for (i in 0 until (8 * big).toInt() + 4) {
            val a = i / (8f * big + 4f) * 6.28f + rnd(-.2f, .2f)
            val p = sprite(floatArrayOf(at[0] + cos(a) * .5f, .3f, at[2] + sin(a) * .5f), SMOKE + (i % 4), 1, .7f * big, 2.2f * big, rnd(.8f, 1.4f), DUST, .55f, M_SMOKE)
            val v = rnd(2.5f, 4.5f) * big; p.vel[0] = cos(a) * v; p.vel[2] = sin(a) * v; p.vel[1] = rnd(.3f, 1.2f); p.drag = 2.4f
        }
        // pedras
        for (i in 0 until (10 * big).toInt() + 4) {
            val p = sprite(floatArrayOf(at[0], .15f, at[2]), SHARD, 1, rnd(.12f, .26f) * big, .1f, rnd(.7f, 1.1f), floatArrayOf(.22f, .19f, .16f), 1f, M_SMOKE)
            val a = rnd(0f, 6.28f); val v = rnd(2f, 6f) * big
            p.vel[0] = cos(a) * v; p.vel[2] = sin(a) * v; p.vel[1] = rnd(3f, 7f) * sqrt(big); p.grav = 16f; p.rotV = rnd(-9f, 9f)
        }
        // brasas subindo
        for (i in 0 until (10 * big).toInt()) {
            val p = sprite(floatArrayOf(at[0] + rnd(-.8f, .8f) * big, .2f, at[2] + rnd(-.8f, .8f) * big), SPARK, 1, .07f, .02f, rnd(.5f, 1f), col2, 1f)
            p.vel[1] = rnd(2f, 5f); p.vel[0] = rnd(-1f, 1f); p.vel[2] = rnd(-1f, 1f); p.drag = 1.2f
        }
    }

    /** Lâmina em X que voa até o alvo. */
    fun xBlade(from: FloatArray, to: FloatArray, col: FloatArray, col2: FloatArray, dur: Float) {
        val dx = to[0] - from[0]; val dz = to[2] - from[2]; val L = hypot(dx, dz).coerceAtLeast(.01f)
        val fx = dx / L; val fz = dz / L
        // eixo lateral e vertical do plano da lâmina
        val rx = -fz; val rz = fx
        for (k in 0 until 2) {
            val ang = if (k == 0) .785f else -.785f
            val c = cos(ang); val s = sin(ang)
            for (layer in 0 until 3) {
                val p = spawn()
                p.o = Orient.AXIS
                System.arraycopy(from, 0, p.pos, 0, 3)
                p.vel[0] = fx * L / dur; p.vel[2] = fz * L / dur
                // ax = direção do corte (diagonal), ay = espessura (para frente)
                p.ax[0] = rx * c; p.ax[1] = s; p.ax[2] = rz * c
                p.ay[0] = fx; p.ay[1] = 0f; p.ay[2] = fz
                p.cell = if (layer == 2) GLOW else STREAK - 0 + 0; p.cells = 1
                if (layer == 0) { p.cell = SLASH + 8; p.mode = M_HOT }
                if (layer == 1) { p.cell = SOFT; p.mode = M_TINT }
                if (layer == 2) { p.cell = FLARE; p.mode = M_HOT }
                p.s0 = if (layer == 2) .8f else 1.15f; p.s1 = p.s0 * 1.25f; p.sx = if (layer == 2) 2.2f else 1.9f
                p.life = dur + .05f; p.fadeIn = .03f; p.rot = 0f
                System.arraycopy(if (layer == 0) col2 else col, 0, p.c, 0, 3)
                p.a = if (layer == 1) .55f else 1f
            }
        }
        // rastro de faíscas
        for (i in 0 until 10) {
            val u = i / 10f
            val p = sprite(floatArrayOf(from[0] + dx * u, from[1] + rnd(-.3f, .3f), from[2] + dz * u), SPARK, 1, .08f, .02f, .35f, col2, 1f, delay = u * dur)
            p.vel[1] = rnd(.5f, 1.5f); p.drag = 1f
        }
    }

    /** Marca do X no alvo (impacto da Skill 1). */
    fun xMark(at: FloatArray, faceYaw: Float, col: FloatArray, col2: FloatArray) {
        val fx = sin(faceYaw); val fz = cos(faceYaw)
        val rx = -fz; val rz = fx
        for (k in 0 until 2) {
            val ang = if (k == 0) .785f else -.785f
            val c = cos(ang); val s = sin(ang)
            for (layer in 0 until 2) {
                val p = spawn(); p.o = Orient.AXIS
                System.arraycopy(at, 0, p.pos, 0, 3)
                p.ax[0] = rx * c; p.ax[1] = s; p.ax[2] = rz * c
                p.ay[0] = rx * -s; p.ay[1] = c; p.ay[2] = rz * -s
                p.cell = if (layer == 0) STREAK else GLOW; p.mode = M_HOT
                p.s0 = if (layer == 0) .16f else .35f; p.s1 = p.s0 * .5f; p.sx = if (layer == 0) 11f else 5f
                p.life = .55f; p.fadeIn = .01f; p.rot = 1.5708f
                System.arraycopy(if (layer == 0) col2 else col, 0, p.c, 0, 3)
            }
        }
        sprite(at, FLARE, 1, 1.2f, 2.8f, .25f, col2, 1f)
        sprite(at, LRING, 1, .4f, 2.4f, .35f, col, .9f)
        hitSparks(at, col2, 14, 1.3f)
    }

    /** Onda 360° (contra-ataque da Skill 2). */
    fun counterWave(at: FloatArray, col: FloatArray, col2: FloatArray) {
        val g = floatArrayOf(at[0], .05f, at[2])
        sprite(g, LRING, 1, .5f, 7.5f, .6f, col2, 1f, M_HOT, Orient.GROUND)
        sprite(g, RING, 1, .5f, 6f, .5f, col, .9f, M_HOT, Orient.GROUND, .05f)
        sprite(floatArrayOf(at[0], .9f, at[2]), TWIRL, 3, 1.5f, 4.2f, .45f, col, .9f, M_HOT, Orient.GROUND)
        for (i in 0 until 24) {
            val a = i / 24f * 6.28f
            val p = sprite(floatArrayOf(at[0] + cos(a) * .6f, rnd(.3f, 1.3f), at[2] + sin(a) * .6f), SPARK, 1, .1f, .03f, .45f, col2, 1f)
            p.vel[0] = cos(a) * 9f; p.vel[2] = sin(a) * 9f; p.drag = 3f
        }
    }

    /** Clarão de bloqueio (aparar golpe). */
    fun deflect(at: FloatArray, col: FloatArray, col2: FloatArray) {
        sprite(at, CRIT + 4, 14, .7f, 1.6f, .3f, col2, 1f, M_TINT)
        sprite(at, FLARE, 1, 1.6f, .4f, .2f, col2, 1f)
        sprite(at, RING, 1, .3f, 1.8f, .25f, col, .9f)
        hitSparks(at, col2, 16, 1.2f)
    }

    /** Energia reunindo nas lâminas. */
    fun gather(at: FloatArray, col: FloatArray, k: Float) {
        if (Math.random() > .55 * k + .2) return
        val a = rnd(0f, 6.28f); val b = rnd(-1f, 1f); val r = rnd(.4f, .8f)
        val ox = cos(a) * r; val oy = b * r * .6f; val oz = sin(a) * r
        val p = sprite(floatArrayOf(at[0] + ox, at[1] + oy, at[2] + oz), SPARK, 1, .07f, .02f, .25f, col, 1f)
        p.vel[0] = -ox / .25f; p.vel[1] = -oy / .25f; p.vel[2] = -oz / .25f
    }

    /** Chamas e pilares da aura (chamado todo quadro durante a Ultimate). */
    fun auraTick(at: FloatArray, dt: Float, k: Float) {
        val n = (dt * 70f * k).toInt() + if (Math.random() < (dt * 70f * k) % 1f) 1 else 0
        for (i in 0 until n) {
            val a = rnd(0f, 6.28f); val r = rnd(.25f, .55f)
            val p = sprite(floatArrayOf(at[0] + cos(a) * r, rnd(0f, .4f), at[2] + sin(a) * r), JET, 4, rnd(.5f, .9f), rnd(.9f, 1.5f), rnd(.45f, .7f), if (Math.random() < .5) RED else RED2, .55f * k, M_TINT, Orient.VBILL, rot = 0f)
            p.vel[1] = rnd(1.2f, 2.6f); p.follow = at
        }
        if (Math.random() < dt * 8f * k) {
            val p = sprite(floatArrayOf(at[0], .9f, at[2]), STREAK, 1, 1.8f, 2.4f, .5f, RED, .35f * k, M_HOT, Orient.VBILL, rot = 0f)
            p.sx = .9f; p.follow = at
        }
        if (Math.random() < dt * 30f * k) {
            val p = sprite(floatArrayOf(at[0] + rnd(-.5f, .5f), rnd(.2f, 1.6f), at[2] + rnd(-.5f, .5f)), SPARK, 1, .07f, .02f, rnd(.6f, 1f), RED2, 1f)
            p.vel[1] = rnd(1.5f, 3f); p.follow = at
        }
    }

    fun auraBurst(at: FloatArray) {
        val g = floatArrayOf(at[0], .04f, at[2])
        sprite(g, LRING, 1, .5f, 6f, .7f, RED2, 1f, M_HOT, Orient.GROUND)
        sprite(g, GLOW, 1, 3f, 4f, .9f, RED, .8f, M_HOT, Orient.GROUND)
        sprite(floatArrayOf(at[0], 1f, at[2]), FLARE, 1, 2f, 4f, .3f, RED2, 1f)
        for (i in 0 until 30) {
            val a = rnd(0f, 6.28f)
            val p = sprite(floatArrayOf(at[0], rnd(.2f, 1.5f), at[2]), SPARK, 1, .1f, .03f, rnd(.5f, .9f), RED2, 1f)
            val v = rnd(3f, 8f); p.vel[0] = cos(a) * v; p.vel[2] = sin(a) * v; p.vel[1] = rnd(0f, 3f); p.drag = 2.5f
        }
    }

    fun stunStars(at: FloatArray) {
        for (i in 0 until 3) {
            val p = sprite(at, STAR, 1, .22f, .18f, 1.4f, GOLD2, .9f)
            p.vel[0] = cos(i * 2.1f) * .6f; p.vel[2] = sin(i * 2.1f) * .6f; p.drag = 3f; p.rotV = 3f
        }
    }

    // ---------- simulação ----------
    fun update(dt: Float) {
        time += dt
        for (p in ps) {
            if (!p.on) continue
            if (p.delay > 0f) { p.delay -= dt; continue }
            p.age += dt
            if (p.age >= p.life) { p.on = false; continue }
            val dk = exp(-p.drag * dt)
            p.vel[0] *= dk; p.vel[2] *= dk; p.vel[1] = p.vel[1] * dk - p.grav * dt
            p.pos[0] += p.vel[0] * dt; p.pos[1] += p.vel[1] * dt; p.pos[2] += p.vel[2] * dt
            if (p.grav > 0f && p.pos[1] < .03f) { p.pos[1] = .03f; p.vel[1] *= -.3f; p.vel[0] *= .6f; p.vel[2] *= .6f }
            p.rot += p.rotV * dt
        }
        for (t in trails) t.prune(time, trailAge)
    }

    // ---------- desenho ----------
    private fun put(buf: FloatArray, n: Int, x: Float, y: Float, z: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float, mode: Float, cell: Float): Int {
        if (n >= buf.size / FL) return n
        val i = n * FL
        buf[i] = x; buf[i + 1] = y; buf[i + 2] = z; buf[i + 3] = u; buf[i + 4] = v
        buf[i + 5] = r; buf[i + 6] = g; buf[i + 7] = b; buf[i + 8] = a; buf[i + 9] = mode; buf[i + 10] = cell
        return n + 1
    }

    private fun quad(add: Boolean, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float,
                     r: Float, g: Float, b: Float, a: Float, mode: Float, cell: Float) {
        val buf = if (add) addV else alpV
        var n = if (add) nAdd else nAlpha
        val c = QC
        for (k in 0 until 6) {
            val su = c[k * 2]; val sv = c[k * 2 + 1]
            n = put(buf, n, cx + ux * su + vx * sv, cy + uy * su + vy * sv, cz + uz * su + vz * sv, su, sv, r, g, b, a, mode, cell)
        }
        if (add) nAdd = n else nAlpha = n
    }

    private fun build() {
        nAdd = 0; nAlpha = 0
        val rx = camR[0]; val ry = camR[1]; val rz = camR[2]; val ux = camU[0]; val uy = camU[1]; val uz = camU[2]
        for (p in ps) {
            if (!p.on || p.delay > 0f) continue
            val u = p.age / p.life
            val s = lerpf(p.s0, p.s1, 1 - (1 - u) * (1 - u))
            val fi = if (p.fadeIn > 0f) clampf(p.age / p.fadeIn, 0f, 1f) else 1f
            val fo = 1f - smooth((u - .55f) / .45f)
            val a = p.a * fi * fo
            if (a <= .003f) continue
            val cell = (p.cell + minOf(p.cells - 1, (u * p.cells).toInt())).toFloat()
            val add = p.mode != M_SMOKE && p.mode != M_DARK
            var px = p.pos[0]; var py = p.pos[1]; var pz = p.pos[2]
            val f = p.follow
            if (f != null) { /* posição já é absoluta; nada */ }
            val cr = cos(p.rot); val sr = sin(p.rot)
            when (p.o) {
                Orient.BILL -> {
                    val ax = (rx * cr + ux * sr) * s * p.sx; val ay = (ry * cr + uy * sr) * s * p.sx; val az = (rz * cr + uz * sr) * s * p.sx
                    val bx = (-rx * sr + ux * cr) * s; val by = (-ry * sr + uy * cr) * s; val bz = (-rz * sr + uz * cr) * s
                    quad(add, px, py, pz, ax, ay, az, bx, by, bz, p.c[0], p.c[1], p.c[2], a, p.mode, cell)
                }
                Orient.VBILL -> {
                    // de pé, virado para a câmera só na horizontal
                    var hx = rx; var hz = rz; val hl = hypot(hx, hz).coerceAtLeast(1e-3f); hx /= hl; hz /= hl
                    quad(add, px, py + s * .5f, pz, hx * s * .5f * p.sx, 0f, hz * s * .5f * p.sx, 0f, s, 0f, p.c[0], p.c[1], p.c[2], a, p.mode, cell)
                }
                Orient.GROUND -> {
                    quad(add, px, py, pz, cr * s, 0f, sr * s, -sr * s, 0f, cr * s, p.c[0], p.c[1], p.c[2], a, p.mode, cell)
                }
                Orient.AXIS -> {
                    quad(add, px, py, pz, p.ax[0] * s * p.sx, p.ax[1] * s * p.sx, p.ax[2] * s * p.sx, p.ay[0] * s, p.ay[1] * s, p.ay[2] * s,
                        p.c[0], p.c[1], p.c[2], a, p.mode, cell)
                }
            }
        }
        // rastros
        for (t in trails) {
            if (t.n < 2) continue
            for (i in 0 until t.n - 1) {
                val a0 = 1f - (time - t.t[i]) / trailAge; val a1 = 1f - (time - t.t[i + 1]) / trailAge
                if (a1 <= 0f) continue
                val al0 = clampf(a0, 0f, 1f); val al1 = clampf(a1, 0f, 1f)
                val A = t.ax; val B = t.bx; val o0 = i * 3; val o1 = (i + 1) * 3
                // dois triângulos: (a0,b0,b1) (a0,b1,a1); v=0 lado interno, 1 lado da lâmina
                nAdd = put(addV, nAdd, A[o0], A[o0 + 1], A[o0 + 2], al0, 0f, t.c[0], t.c[1], t.c[2], al0 * al0 * t.w, M_TRAIL, 0f)
                nAdd = put(addV, nAdd, B[o0], B[o0 + 1], B[o0 + 2], al0, 1f, t.c[0], t.c[1], t.c[2], al0 * al0 * t.w, M_TRAIL, 0f)
                nAdd = put(addV, nAdd, B[o1], B[o1 + 1], B[o1 + 2], al1, 1f, t.c[0], t.c[1], t.c[2], al1 * al1 * t.w, M_TRAIL, 0f)
                nAdd = put(addV, nAdd, A[o0], A[o0 + 1], A[o0 + 2], al0, 0f, t.c[0], t.c[1], t.c[2], al0 * al0 * t.w, M_TRAIL, 0f)
                nAdd = put(addV, nAdd, B[o1], B[o1 + 1], B[o1 + 2], al1, 1f, t.c[0], t.c[1], t.c[2], al1 * al1 * t.w, M_TRAIL, 0f)
                nAdd = put(addV, nAdd, A[o1], A[o1 + 1], A[o1 + 2], al1, 0f, t.c[0], t.c[1], t.c[2], al1 * al1 * t.w, M_TRAIL, 0f)
            }
        }
        // corrente de energia
        if (chainOn) {
            val dx = chainB[0] - chainA[0]; val dy = chainB[1] - chainA[1]; val dz = chainB[2] - chainA[2]
            val L = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(.01f)
            val fx = dx / L; val fy = dy / L; val fz = dz / L
            // perpendicular horizontal
            var sx = -fz; var sz = fx; val sl = hypot(sx, sz).coerceAtLeast(1e-3f); sx /= sl; sz /= sl
            val links = (L / .2f).toInt().coerceIn(2, 80)
            for (i in 0 until links) {
                val u = (i + .5f) / links
                val cx = chainA[0] + dx * u; val cy = chainA[1] + dy * u; val cz = chainA[2] + dz * u
                val vert = i % 2 == 0
                val px = if (vert) 0f else sx; val py = if (vert) 1f else 0f; val pz = if (vert) 0f else sz
                val sh = .5f + .5f * sin(time * 18f - u * 20f)
                quad(true, cx, cy, cz, fx * .13f, fy * .13f, fz * .13f, px * .075f, py * .075f, pz * .075f,
                    GOLD2[0], GOLD2[1], GOLD2[2], chainA0 * (.75f + .25f * sh), M_HOT, RING.toFloat())
            }
            // brilho contínuo
            val ux2 = camU[0]; val uy2 = camU[1]; val uz2 = camU[2]
            val w = .16f
            quad(true, (chainA[0] + chainB[0]) / 2, (chainA[1] + chainB[1]) / 2, (chainA[2] + chainB[2]) / 2,
                dx / 2, dy / 2, dz / 2, ux2 * w, uy2 * w, uz2 * w, GOLD[0], GOLD[1], GOLD[2], .6f * chainA0, M_TRAIL, 1f)
        }
        // indicador de mira (Skill 4) — retângulo no chão estilo MOBA
        if (aimOn) {
            val fx = sin(aimYaw); val fz = cos(aimYaw)
            val cx = aimFrom[0] + fx * aimLen / 2; val cz = aimFrom[2] + fz * aimLen / 2
            quad(true, cx, .04f, cz, -fz * aimW / 2, 0f, fx * aimW / 2, fx * aimLen / 2, 0f, fz * aimLen / 2, GOLD[0], GOLD[1], GOLD[2], .9f, M_AIM, aimLen)
        }
        if (teleOn > 0f) {
            quad(true, telePos[0], .05f, telePos[2], teleR, 0f, 0f, 0f, 0f, teleR, GOLD[0], GOLD[1], GOLD[2], teleOn, M_TELE, teleP)
        }
    }

    fun draw(vp: FloatArray) {
        build()
        if (nAdd == 0 && nAlpha == 0) return
        glUseProgram(prog)
        glUniformMatrix4fv(uVP, 1, false, vp, 0)
        glUniform1f(uTime, time)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(prog, "uAtl"), 0)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnable(GL_BLEND); glDepthMask(false); glDisable(GL_CULL_FACE)
        if (nAlpha > 0) {
            fb.clear(); fb.put(alpV, 0, nAlpha * FL); fb.flip()
            glBufferSubData(GL_ARRAY_BUFFER, 0, nAlpha * FL * 4, fb)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glDrawArrays(GL_TRIANGLES, 0, nAlpha)
        }
        if (nAdd > 0) {
            fb.clear(); fb.put(addV, 0, nAdd * FL); fb.flip()
            glBufferSubData(GL_ARRAY_BUFFER, 0, nAdd * FL * 4, fb)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE)
            glDrawArrays(GL_TRIANGLES, 0, nAdd)
        }
        glDepthMask(true); glDisable(GL_BLEND); glBindVertexArray(0)
    }

}
