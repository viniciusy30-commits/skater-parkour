package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Boneco de treino + sangue (porte do BLD do HTML). Espaço "unidade" (metros / 1,8). */
class Bld(ctx: Context) {
    // estado do boneco
    var E = v3(0f, 0f, 0f)
    var dirH = floatArrayOf(0f, 1f)
    var tilt = 0f; var tv = 0f; var flash = 0f
    class Wound(val p: V, var w: Float, val d: V, val k: Float)
    val wounds = ArrayList<Wound>()
    var onImpact: ((V, V, Float) -> Unit)? = null

    private val cool = floatArrayOf(0f, 0f)
    private val G = 6.2f

    // malha
    private val eProg: Int; private val eVao: Int; private val eN: Int
    private val bProg: Int; private val bVao: Int; private val bVb: Int
    private val buf = FloatArray(9 * 6 * 3200); private var bn = 0
    private val fb = Gl.floats(9 * 6 * 3200)

    private class Sprite(val c: V, val d: V, val L: Float, var age: Float, val life: Float, val seed: Float, val a: Float)
    private class Drop(val type: Int, val p: V, var v: V, var age: Float, val life: Float, val w: Float, var len: Float, val sz: Float = 1f, var hang: Float = 0f, var sw: Float = 0f) { var dead = false }
    private class Decal(val x: Float, val z: Float, val r: Float, val sx: Float, val ang: Float, var age: Float, val seed: Float, val life: Float)
    private class Drip(val p: V, var t: Float, val dur: Float, var next: Float, val m: Float)
    private val newDrops = ArrayList<Drop>()
    private val sprites = ArrayList<Sprite>(); private val drops = ArrayList<Drop>(); private val decals = ArrayList<Decal>(); private val drips = ArrayList<Drip>()

    init {
        val b = ByteBuffer.wrap(Gl.readAsset(ctx, "w/dummy.bin")).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.int; val ni = b.int
        val vbuf = Gl.direct(n * 24); vbuf.put(b.array(), 8, n * 24); vbuf.flip()
        val ibuf = Gl.direct(ni * 2); ibuf.put(b.array(), 8 + n * 24, ni * 2); ibuf.flip()
        eProg = Gl.program(EVS, EFS)
        eVao = Gl.gen(::glGenVertexArrays); glBindVertexArray(eVao)
        val vb = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vb); glBufferData(GL_ARRAY_BUFFER, n * 24, vbuf, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
        val ib = Gl.gen(::glGenBuffers); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ib); glBufferData(GL_ELEMENT_ARRAY_BUFFER, ni * 2, ibuf, GL_STATIC_DRAW)
        eN = ni; glBindVertexArray(0)
        bProg = Gl.program(VS, FS)
        bVao = Gl.gen(::glGenVertexArrays); glBindVertexArray(bVao)
        bVb = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, bVb); glBufferData(GL_ARRAY_BUFFER, buf.size * 4, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 36, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 36, 20)
        glBindVertexArray(0)
    }

    private fun Rr(y: Float): Float = when { y < .3f -> .07f; y < .75f -> .18f; y < .84f -> .08f; y < 1.02f -> .115f; else -> 0f }

    fun clear() { sprites.clear(); drops.clear(); decals.clear(); drips.clear(); tilt = 0f; tv = 0f; flash = 0f; wounds.clear() }

    private fun decal(x: Float, z: Float, r: Float, sx: Float, ang: Float, delay: Float = 0f) {
        if (decals.size > 110) decals.removeAt(0)
        decals.add(Decal(x, z, r, sx, ang, .07f - delay, Math.random().toFloat() * 10, 16f))
    }
    private fun land(p: V, v: V, w: Float) {
        val sp = hypot(v[0], v[2]); val ang = atan2(v[2], v[0]); val r = clampf(w * rnd(1.6f, 2.6f) + .008f, .012f, .075f)
        decal(p[0], p[2], r, 1 + min(1.6f, sp * .35f), ang)
        if (vlen(v) > 2 && Math.random() < .5) decal(p[0] + v[0] * .03f, p[2] + v[2] * .03f, r * .35f, 1.8f, ang, .02f)
    }
    private fun splashCrown(x: Float, z: Float, n: Int, s: Float) {
        for (i in 0 until n) { val a = rnd(0f, PIf * 2); val sp = rnd(.25f, .7f) * s; newDrops.add(Drop(0, v3(x, .006f, z), v3(cos(a) * sp, rnd(.5f, 1.1f) * s, sin(a) * sp), 0f, rnd(.2f, .4f), .004f, .02f)) }
    }

    fun upd(dt: Float) {
        if (dt > 1 / 58f) { val n = ceil(dt * 58).toInt(); val h = dt / n; repeat(n) { upd(h) }; return }
        tv += (-70 * tilt - 8 * tv) * dt; tilt += tv * dt; tilt = clampf(tilt, -.3f, .3f); flash *= exp(-dt * 7)
        for (w in wounds) w.w = max(0f, w.w - dt * .012f)
        for (s in sprites) s.age += dt; sprites.removeAll { it.age >= it.life }
        for (d in decals) d.age += dt; decals.removeAll { it.age >= it.life }
        for (d in drops) {
            d.age += dt
            if (d.type == 1) {
                if (d.hang > 0) { d.hang -= dt; d.sw = min(1f, d.sw + dt * 2.5f); if (d.hang <= 0) d.v = v3(0f, -.15f, 0f); continue }
                d.v[1] -= G * dt; for (k in 0 until 3) d.p[k] += d.v[k] * dt; d.len = clampf(-d.v[1] * .032f, .02f, .13f)
                if (d.p[1] <= .006f) { d.dead = true; val r = rnd(.016f, .034f) * d.sz; decal(d.p[0], d.p[2], r, 1f, 0f); splashCrown(d.p[0], d.p[2], 3, .8f) }
                continue
            }
            d.v[1] -= G * dt; val dr = exp(-dt * 1.1f); d.v[0] *= dr; d.v[2] *= dr; for (k in 0 until 3) d.p[k] += d.v[k] * dt
            if (d.p[1] <= .006f) { d.dead = true; if (d.type == 2) land(d.p, d.v, d.w) }
        }
        drops.removeAll { it.dead || it.age >= it.life + (if (it.type == 1) 9 else 0) }
        if (newDrops.isNotEmpty()) { drops.addAll(newDrops); newDrops.clear() }
        for (s in drips) {
            s.t += dt; s.next -= dt
            if (s.t < s.dur && s.next <= 0) {
                s.next = .17f + s.t * .2f + Math.random().toFloat() * .15f; val y = s.p[1] - .14f * (1 - exp(-s.t * .9f))
                drops.add(Drop(1, v3(s.p[0] + rnd(-.008f, .008f), y, s.p[2] + rnd(-.008f, .008f)), v3(0f, 0f, 0f), 0f, 1f, rnd(.009f, .014f) * s.m, .012f, s.m, rnd(.12f, .42f), 0f))
            }
        }
        drips.removeAll { it.t >= it.dur }
    }

    // ---------- acerto ----------
    private fun inside(p: V): Boolean {
        val dx = p[0] - E[0]; val dz = p[2] - E[2]; val y = p[1]
        if (y < .04f || y > 1.04f) return false
        return hypot(dx, dz) < Rr(min(y, 1f)) + .07f
    }
    private fun lp(a: V, b: V, t: Float) = v3(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
    private fun qpt(Q: Array<V>, a: Float, b: Float) = lp(lp(Q[0], Q[1], b), lp(Q[2], Q[3], b), a)
    private fun qc(Q: Array<V>) = v3((Q[0][0] + Q[1][0] + Q[2][0] + Q[3][0]) / 4, (Q[0][1] + Q[1][1] + Q[2][1] + Q[3][1]) / 4, (Q[0][2] + Q[1][2] + Q[2][2] + Q[3][2]) / 4)

    fun hitTest(s: Int, P: Array<V>, Pp: Array<V>?, k: Float, ad: Float, sp: Float) {
        if (Pp == null || !(ad > 0)) return
        cool[s] -= ad; if (cool[s] > 0 || sp < 1.8f) return
        var cx = 0f; var cy = 0f; var cz = 0f; var nh = 0
        for (i in 0..8) { val t = i / 8f; for (ai in 0..3) for (bi in 0..4) { val p = lp(qpt(Pp, ai / 3f, bi / 4f), qpt(P, ai / 3f, bi / 4f), t); if (inside(p)) { cx += p[0]; cy += p[1]; cz += p[2]; nh++ } } }
        if (nh == 0) return
        val c = v3(cx / nh, cy / nh, cz / nh)
        val A = qc(Pp); val B = qc(P); val mv = v3((B[0] - A[0]) / ad, (B[1] - A[1]) / ad, (B[2] - A[2]) / ad)
        cool[s] = .3f
        onHit(c, vnorm(mv), clampf(.4f + min(1f, sp / 6) * .6f, .4f, 1f))
    }

    fun onHit(c: V, d0: V, k: Float) {
        onImpact?.invoke(c, d0, k)
        var hx = c[0] - E[0]; var hz = c[2] - E[2]; val hl = hypot(hx, hz).let { if (it == 0f) 1f else it }; hx /= hl; hz /= hl
        val rr = max(.11f, min(Rr(min(c[1], 1f)).let { if (it == 0f) .18f else it }, .18f))
        val sp0 = v3(E[0] + hx * rr, c[1], E[2] + hz * rr)
        val d = vnorm(v3(d0[0], d0[1] + .1f, d0[2]))
        val dH = vnorm(v3(d[0], 0f, d[2])); tv += (.7f + 1.3f * k); dirH = floatArrayOf(dH[0], dH[2]); flash = min(.5f, .2f + k * .3f)
        wounds.add(Wound(sp0.copyOf(), 1f, d.copyOf(), k)); if (wounds.size > 4) wounds.removeAt(0)
        val sd = Math.random().toFloat() * 50
        sprites.add(Sprite(sp0.copyOf(), d, .3f + .25f * k, 0f, .55f, sd, .7f))
        sprites.add(Sprite(vadd(sp0, vmul(d, .01f)), vnorm(v3(d[0] + rnd(-.4f, .4f), d[1] + rnd(-.1f, .3f), d[2] + rnd(-.4f, .4f))), .18f + .15f * k, -.04f, .45f, sd + 17, .55f))
        fun cone(m: Float) = vnorm(v3(d[0] + rnd(-m, m), d[1] + rnd(-m * .6f, m * .8f), d[2] + rnd(-m, m)))
        for (i in 0 until (7 + 13 * k).roundToInt()) { val dd = cone(.38f); val v = rnd(1.6f, 5.8f) * (.55f + k * .6f); drops.add(Drop(2, vadd(sp0, vmul(dd, .01f)), v3(dd[0] * v, dd[1] * v + .35f, dd[2] * v), 0f, 1.5f, rnd(.0022f, .005f), rnd(.012f, .04f))) }
        for (i in 0 until (3 + 5 * k).roundToInt()) { val dd = cone(.5f); val v = rnd(1.2f, 4f) * (.55f + k * .6f); drops.add(Drop(2, vadd(sp0, vmul(dd, .02f)), v3(dd[0] * v, dd[1] * v + .4f, dd[2] * v), 0f, 1.5f, rnd(.005f, .009f), rnd(.03f, .07f))) }
        for (i in 0 until (1 + 2 * k).roundToInt()) { val dd = cone(.6f); val v = rnd(.8f, 2.6f) * (.6f + k * .5f); drops.add(Drop(2, vadd(sp0, vmul(dd, .02f)), v3(dd[0] * v, dd[1] * v + .3f, dd[2] * v), 0f, 1.4f, rnd(.009f, .015f), rnd(.04f, .08f))) }
        for (i in 0 until (2 + 3 * k).roundToInt()) { val dd = vnorm(v3(-d[0] * .3f + rnd(-1f, 1f) * .6f, rnd(.2f, 1f), -d[2] * .3f + rnd(-1f, 1f) * .6f)); val v = rnd(.5f, 1.7f); drops.add(Drop(2, sp0.copyOf(), v3(dd[0] * v, dd[1] * v, dd[2] * v), 0f, 1.2f, rnd(.003f, .007f), rnd(.02f, .045f))) }
        drips.add(Drip(v3(E[0] + hx * .2f, c[1], E[2] + hz * .2f), 0f, 1.6f + k, .4f, .6f + k * .3f))
        decal(E[0] + dH[0] * .3f, E[2] + dH[2] * .3f, .05f + .04f * k, 1.4f, atan2(dH[2], dH[0]), .22f)
    }

    // ---------- desenho ----------
    private val M = FloatArray(16); private val W = FloatArray(16); private val D = FloatArray(16)
    fun drawEnemy(vpU: FloatArray, eyeU: V) {
        val a = tilt; val dx = dirH[0]; val dz = dirH[1]; val nx = dz; val nz = -dx; val c = cos(a); val s = sin(a); val t = 1 - c
        M.fill(0f)
        M[0] = c + nx * nx * t; M[1] = nz * s; M[2] = nx * nz * t; M[4] = -nz * s; M[5] = c; M[6] = nx * s; M[8] = nx * nz * t; M[9] = -nx * s; M[10] = c + nz * nz * t
        M[12] = E[0]; M[13] = E[1]; M[14] = E[2]; M[15] = 1f
        glUseProgram(eProg)
        glUniformMatrix4fv(glGetUniformLocation(eProg, "uVP"), 1, false, vpU, 0); glUniformMatrix4fv(glGetUniformLocation(eProg, "uM"), 1, false, M, 0)
        glUniform3fv(glGetUniformLocation(eProg, "uCam"), 1, eyeU, 0); glUniform1f(glGetUniformLocation(eProg, "uFl"), flash)
        W.fill(0f); D.fill(0f)
        wounds.forEachIndexed { i, w -> W[i * 4] = w.p[0]; W[i * 4 + 1] = w.p[1]; W[i * 4 + 2] = w.p[2]; W[i * 4 + 3] = w.w; D[i * 4] = w.d[0]; D[i * 4 + 1] = w.d[1]; D[i * 4 + 2] = w.d[2]; D[i * 4 + 3] = w.k }
        glUniform4fv(glGetUniformLocation(eProg, "uW"), 4, W, 0); glUniform4fv(glGetUniformLocation(eProg, "uD"), 4, D, 0)
        glEnable(GL_DEPTH_TEST); glDepthMask(true); glDisable(GL_BLEND); glDisable(GL_CULL_FACE)
        glBindVertexArray(eVao); glDrawElements(GL_TRIANGLES, eN, GL_UNSIGNED_SHORT, 0); glBindVertexArray(0)
    }

    private fun V(x: Float, y: Float, z: Float, u: Float, v: Float, a: Float, b: Float, c: Float, d: Float) {
        buf[bn++] = x; buf[bn++] = y; buf[bn++] = z; buf[bn++] = u; buf[bn++] = v; buf[bn++] = a; buf[bn++] = b; buf[bn++] = c; buf[bn++] = d
    }
    private fun Q(p0: V, p1: V, p2: V, p3: V, uv: FloatArray, a: Float, b: Float, c: Float, d: Float) {
        if (bn > buf.size - 60) return
        V(p0[0], p0[1], p0[2], uv[0], uv[1], a, b, c, d); V(p1[0], p1[1], p1[2], uv[2], uv[3], a, b, c, d); V(p2[0], p2[1], p2[2], uv[4], uv[5], a, b, c, d)
        V(p0[0], p0[1], p0[2], uv[0], uv[1], a, b, c, d); V(p2[0], p2[1], p2[2], uv[4], uv[5], a, b, c, d); V(p3[0], p3[1], p3[2], uv[6], uv[7], a, b, c, d)
    }
    private fun ad3(a: V, b: V, s: Float) = v3(a[0] + b[0] * s, a[1] + b[1] * s, a[2] + b[2] * s)
    private fun flush() {
        if (bn == 0) return
        fb.clear(); fb.put(buf, 0, bn); fb.flip(); glBufferData(GL_ARRAY_BUFFER, bn * 4, fb, GL_STREAM_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, bn / 9); bn = 0
    }
    private val UVA = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f); private val UVB = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f); private val UVS = floatArrayOf(-1f, 0f, 1f, 0f, 1f, 1f, -1f, 1f)

    fun drawBlood(vpU: FloatArray, R: V, U: V, eye: V) {
        glUseProgram(bProg); glUniformMatrix4fv(glGetUniformLocation(bProg, "uVP"), 1, false, vpU, 0)
        glBindVertexArray(bVao); glBindBuffer(GL_ARRAY_BUFFER, bVb)
        glEnable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        bn = 0; val Y = .004f
        run { val r = .34f; Q(v3(E[0] - r, Y, E[2] - r), v3(E[0] + r, Y, E[2] - r), v3(E[0] + r, Y, E[2] + r), v3(E[0] - r, Y, E[2] + r), UVA, 1f, 0f, 0f, 3f) }
        for (d in decals) {
            if (d.age < 0) continue
            val g = min(1f, d.age / .14f); val e = 1 - (1 - g).pow(3); val fade = if (d.age > d.life - 4) (d.life - d.age) / 4 else 1f
            val rr = d.r * (.35f + .65f * e); val ca = cos(d.ang); val sa = sin(d.ang)
            val ax0 = ca * rr * d.sx; val ax1 = sa * rr * d.sx; val ay0 = -sa * rr; val ay1 = ca * rr
            fun pt(u: Float, v: Float) = v3(d.x + ax0 * u + ay0 * v, Y + .0005f, d.z + ax1 * u + ay1 * v)
            Q(pt(-1f, -1f), pt(1f, -1f), pt(1f, 1f), pt(-1f, 1f), UVA, max(0f, fade) * .95f, d.seed, 0f, 2f)
        }
        flush()
        for (d in drops) {
            val L0 = vlen(d.v)
            if (d.type == 1 && d.hang > 0) {
                val w = d.w * (.5f + .7f * d.sw); val ln = w * (1.4f + d.sw * 1.4f)
                Q(v3(d.p[0] - R[0] * w, d.p[1] + ln, d.p[2] - R[2] * w), v3(d.p[0] - R[0] * w, d.p[1] - ln, d.p[2] - R[2] * w), v3(d.p[0] + R[0] * w, d.p[1] - ln, d.p[2] + R[2] * w), v3(d.p[0] + R[0] * w, d.p[1] + ln, d.p[2] + R[2] * w), UVB, 1f, 0f, 0f, 1f)
                continue
            }
            val l = if (L0 == 0f) 1f else L0
            val vv = if (d.type == 1) v3(0f, -1f, 0f) else v3(d.v[0] / l, d.v[1] / l, d.v[2] / l)
            val vw = vsub(eye, d.p); val sd = vnorm(vcross(vv, vw))
            val ln = if (d.type == 1) d.len else min(d.len, .012f + L0 * .012f); val w = d.w
            val a = if (d.type == 1) 1f else min(1f, (d.life - d.age) * 3 + .2f)
            Q(ad3(ad3(d.p, vv, -ln), sd, -w), ad3(d.p, sd, -w), ad3(d.p, sd, w), ad3(ad3(d.p, vv, -ln), sd, w), UVB, a, 0f, 0f, 1f)
        }
        flush()
        for (s in sprites) {
            if (s.age < 0) continue
            val p = min(1f, s.age / s.life)
            var dx = s.d[0] * R[0] + s.d[1] * R[1] + s.d[2] * R[2]; var dy = s.d[0] * U[0] + s.d[1] * U[1] + s.d[2] * U[2]
            var ln = hypot(dx, dy); if (ln < .3f) { dx = 0f; dy = 1f; ln = 1f }; dx /= ln; dy /= ln
            val up = v3(R[0] * dx + U[0] * dy, R[1] * dx + U[1] * dy, R[2] * dx + U[2] * dy)
            val sd = v3(-R[0] * dy + U[0] * dx, -R[1] * dy + U[1] * dx, -R[2] * dy + U[2] * dx)
            val L = s.L; val hw = L * .65f; val vw = vnorm(vsub(eye, s.c)); val o = ad3(s.c, vw, .07f)
            Q(ad3(o, sd, -hw), ad3(o, sd, hw), ad3(ad3(o, sd, hw), up, L), ad3(ad3(o, sd, -hw), up, L), UVS, s.a, s.seed, p, 0f)
        }
        flush()
        glBindVertexArray(0); glDepthMask(true); glDisable(GL_BLEND)
    }

    companion object {
        private const val EVS = """#version 300 es
layout(location=0) in vec3 aP;layout(location=1) in vec3 aN;uniform mat4 uVP;uniform mat4 uM;out vec3 vP;out vec3 vN;out float vY;
void main(){vec4 w=uM*vec4(aP,1.);vP=w.xyz;vN=mat3(uM)*aN;vY=aP.y;gl_Position=uVP*w;}"""
        private const val EFS = """#version 300 es
precision highp float;in vec3 vP;in vec3 vN;in float vY;uniform vec3 uCam;uniform float uFl;uniform vec4 uW[4];uniform vec4 uD[4];out vec4 o;
float hh(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
void main(){vec3 N=normalize(vN);vec3 L=normalize(vec3(.45,.85,.35));float d=max(dot(N,L),0.);vec3 V=normalize(uCam-vP);
 vec3 alb=vec3(.62,.5,.36)*(.94+.06*sin(vY*90.));
 float st=0.,gs=0.,wet=0.;vec2 tn=normalize(vec2(-N.z,N.x)+1e-4);
 for(int i=0;i<4;i++){float w=uW[i].w;if(w<=0.)continue;vec3 dd=vP-uW[i].xyz;vec3 dr=uD[i].xyz;float len=.07+.1*uD[i].w;
  float sg=clamp(dot(dd,dr),-len,len);float dl=length(dd-dr*sg);
  float gash=smoothstep(.02,.004,dl)*smoothstep(len*1.15,len*.7,abs(dot(dd,dr)));
  float blot=smoothstep(.1,.015,length(vec3(dd.x,dd.y*1.5,dd.z)))*.5;
  float lat=dot(dd.xz,tn),dy=-dd.y,drip=0.;
  if(dy>0.){for(int j=0;j<3;j++){float c=(hh(vec2(float(j),float(i)))-.5)*.11;float ln=.06+.2*hh(vec2(float(j)+3.,float(i)));
    drip=max(drip,smoothstep(.008,.002,abs(lat-c))*smoothstep(0.,.015,dy)*smoothstep(ln,ln*.4,dy));}}
  st=max(st,max(blot,drip*.9)*w);gs=max(gs,gash*w);wet=max(wet,max(blot,drip)*w);}
 vec3 bl=mix(vec3(.2,.005,.012),vec3(.07,0.,.004),gs);
 alb=mix(alb,bl,clamp(st*.9+gs,0.,.97));
 float rim=pow(1.-max(dot(N,V),0.),3.);
 vec3 c=alb*(.32+.85*d)+vec3(.35,.28,.2)*rim*.3;
 c+=vec3(1.,.75,.6)*uFl*.35;
 c+=vec3(.9,.55,.5)*pow(max(dot(reflect(-L,N),V),0.),40.)*wet*.7;
 o=vec4(c,1.);}"""
        private const val VS = """#version 300 es
layout(location=0) in vec3 aP;layout(location=1) in vec2 aU;layout(location=2) in vec4 aR;
uniform mat4 uVP;out vec2 vU;out vec4 vR;void main(){vU=aU;vR=aR;gl_Position=uVP*vec4(aP,1.);}"""
        private const val FS = """#version 300 es
precision highp float;in vec2 vU;in vec4 vR;out vec4 o;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}
void main(){float kind=vR.w,al=vR.x,seed=vR.y,pg=vR.z;
 if(kind<.5){
  vec2 p=vec2(vU.x*.65,vU.y);float r=length(p),an=atan(p.x,p.y);
  float g=1.-pow(1.-clamp(pg*3.5,0.,1.),3.);
  float N=30.,a=an*N+seed*13.;float id=floor(a);
  float h1=h(vec2(id,seed)),h2=h(vec2(id*1.7,seed+3.)),h3=h(vec2(id+9.,seed+7.));
  float aw=a+.5*sin(r*11.+id*2.3)*r;float f=fract(aw);float across=abs(f-.5)*2.;
  float len=(.22+.78*h2*h2)*g*(1.-abs(an)*.5);
  float taper=pow(clamp(1.-r/max(len,.01),0.,1.),.7);
  float hw=(.12+.4*h3)*taper;
  float stk=step(.28,h1)*smoothstep(hw,hw*.45,across)*step(r,len)*smoothstep(.0,.05,r);
  vec2 q=vec2(an*52.,r*40.);vec2 cl=floor(q);vec2 fr=fract(q)-.5;float k1=h(cl+seed*3.1),k2=h(cl*1.3+4.);
  vec2 off=vec2(h(cl+1.7)-.5,h(cl+5.3)-.5)*.5;float rad=.1+.22*k2;float dd=length(vec2(fr.x-off.x,(fr.y-off.y)*.55));
  float dots=step(.68,k1)*smoothstep(rad,rad*.55,dd)*smoothstep(.12,.4,r)*step(r,g*1.05);
  float core=smoothstep(.17,.02,r)*(.55+.45*vn(p*10.+seed*5.));
  float mist=vn(p*6.+seed*4.)*smoothstep(.95,.1,r)*smoothstep(.8,.0,abs(an))*.14*g;
  float m=max(max(stk,dots),max(core,mist));
  float fade=1.-smoothstep(.55,1.,pg);
  float th=clamp(.55*(1.-r)+.45*h1,0.,1.);
  vec3 c=mix(vec3(.12,0.,.004),vec3(.44,.01,.018),th);
  c+=vec3(.55,.2,.18)*pow(1.-across,5.)*stk*.16;
  float a2=m*al*fade*.92;o=vec4(c*a2,a2);return;}
 if(kind<1.5){
  float u=vU.x,x=abs(vU.y*2.-1.);float wd=mix(.2,1.,pow(u,.6));
  float hd=clamp((u-.78)/.22,0.,1.);wd*=sqrt(max(0.,1.-hd*hd));
  float m=smoothstep(wd,wd*.6,x)*smoothstep(0.,.12,u)*al;
  vec3 c=mix(vec3(.18,0.,.006),vec3(.5,.015,.02),smoothstep(1.,0.,x));
  c+=vec3(.9,.5,.45)*smoothstep(.22,.0,length(vec2((u-.68)*1.,(x-.35)*1.6)))*.35;
  o=vec4(c*m,m);return;}
 if(kind<2.5){
  vec2 p=vU;float r=length(p),an=atan(p.y,p.x);float e=1.+.2*sin(an*5.+seed*6.)+.13*sin(an*9.-seed*3.)+.1*vn(vec2(an*2.,seed*9.));
  float m=smoothstep(e,e-.14,r);float sat=vn(p*3.+seed*7.);
  vec3 c=mix(vec3(.1,0.,.004),vec3(.3,.006,.012),smoothstep(.1,1.,r)*.8+.2*sat);
  float sp=pow(max(0.,1.-length(p-vec2(-.28,.3))*1.8),3.);c+=vec3(.7,.4,.38)*sp*.3;
  float a=m*al*(.86+.14*sat);o=vec4(c*a,a);return;}
 float r=length(vU);float a=smoothstep(1.,.1,r)*al*.5;o=vec4(0.,0.,0.,a);}"""
    }
}
