package com.skaterparkour.app

import android.opengl.GLES30.*
import kotlin.math.*

/** Rastro das lâminas (porte do TRL do HTML). Espaço "unidade" (metros / 1,8). */
class Trl {
    class Row(val P: Array<V>, var age: Float, val k: Float)
    val trail = arrayOf(ArrayList<Row>(), ArrayList<Row>())   // 0 = esquerda, 1 = direita
    var TL = .55f
    var red = 0f
    val eye = v3(0f, 0f, 0f)

    private class Spk(val p: V, val v: V, var age: Float, val life: Float, val len: Float, val w: Float)
    private val sparks = ArrayList<Spk>()
    private val buf = FloatArray(9 * 6 * 1800)
    private val fb = Gl.floats(9 * 6 * 1800)
    private val prog = Gl.program(VS, FS)
    private val uVP = glGetUniformLocation(prog, "uVP"); private val uG = glGetUniformLocation(prog, "uG"); private val uR = glGetUniformLocation(prog, "uR")
    private val vao = Gl.gen(::glGenVertexArrays); private val vb = Gl.gen(::glGenBuffers)
    private val SUB = 8

    init {
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vb)
        glBufferData(GL_ARRAY_BUFFER, buf.size * 4, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 36, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 36, 20)
        glBindVertexArray(0)
    }

    private fun cr(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return .5f * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
    }
    private fun cr3(a: V, b: V, c: V, d: V, t: Float) = v3(cr(a[0], b[0], c[0], d[0], t), cr(a[1], b[1], c[1], d[1], t), cr(a[2], b[2], c[2], d[2], t))

    private class S(val A: ArrayList<V> = ArrayList(), val B: ArrayList<V> = ArrayList(), val ag: ArrayList<Float> = ArrayList(), val k: ArrayList<Float> = ArrayList())

    private fun samples(tr: List<Row>): S? {
        val n = tr.size; if (n < 2) return null
        val A = ArrayList<V>(); val B = ArrayList<V>(); val AG = ArrayList<Float>(); val K = ArrayList<Float>()
        for (q in tr) {
            val p = q.P
            val a0 = v3((p[0][0] + p[2][0]) / 2, (p[0][1] + p[2][1]) / 2, (p[0][2] + p[2][2]) / 2)
            val b0 = v3((p[1][0] + p[3][0]) / 2, (p[1][1] + p[3][1]) / 2, (p[1][2] + p[3][2]) / 2)
            val m = v3((a0[0] + b0[0]) / 2, (a0[1] + b0[1]) / 2, (a0[2] + b0[2]) / 2)
            A.add(v3(m[0] + (a0[0] - m[0]) * 1.2f, m[1] + (a0[1] - m[1]) * 1.2f, m[2] + (a0[2] - m[2]) * 1.2f))
            B.add(v3(m[0] + (b0[0] - m[0]) * 1.7f, m[1] + (b0[1] - m[1]) * 1.7f, m[2] + (b0[2] - m[2]) * 1.7f))
            AG.add(q.age); K.add(min(1f, q.k * 1.6f))
        }
        val s = S()
        for (i in 0 until n - 1) {
            val i0 = max(0, i - 1); val i3 = min(n - 1, i + 2)
            for (j in 0 until SUB) {
                val t = j.toFloat() / SUB
                s.A.add(cr3(A[i0], A[i], A[i + 1], A[i3], t)); s.B.add(cr3(B[i0], B[i], B[i + 1], B[i3], t))
                s.ag.add(AG[i] + (AG[i + 1] - AG[i]) * t); s.k.add(K[i] + (K[i + 1] - K[i]) * t)
            }
        }
        s.A.add(A[n - 1]); s.B.add(B[n - 1]); s.ag.add(AG[n - 1]); s.k.add(K[n - 1])
        return s
    }

    private fun build(S: S, seed: Float, wA: Float, wB: Float, off: Int): Int {
        val N = S.A.size; var n = off
        val u = FloatArray(N)
        for (i in 1 until N) { val a = S.A[i]; val b = S.A[i - 1]; u[i] = u[i - 1] + sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2)) * 6 }
        fun V(i: Int, v: Int) {
            val a = S.A[i]; val b = S.B[i]
            val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2; val mz = (a[2] + b[2]) / 2
            val ex = if (v == 1) wB else wA; val e = if (v == 1) b else a
            buf[n++] = mx + (e[0] - mx) * ex; buf[n++] = my + (e[1] - my) * ex; buf[n++] = mz + (e[2] - mz) * ex
            buf[n++] = u[i]; buf[n++] = v.toFloat(); buf[n++] = min(1f, S.ag[i] / TL); buf[n++] = S.k[i]; buf[n++] = seed; buf[n++] = i.toFloat() / (N - 1)
        }
        var i = 0
        while (i < N - 1 && n < buf.size - 60) { V(i, 0); V(i, 1); V(i + 1, 1); V(i, 0); V(i + 1, 1); V(i + 1, 0); i++ }
        return n
    }

    fun upd(dt: Float) {
        for (s in sparks) { s.age += dt; for (k in 0 until 3) s.p[k] += s.v[k] * dt; s.v[1] -= 2.4f * dt; val d = exp(-dt * 3); s.v[0] *= d; s.v[2] *= d }
        sparks.removeAll { it.age >= it.life }
        if (dt <= 0f) return
        for (tr in trail) {
            val n = tr.size; if (n < 2) continue
            val q = tr[n - 1]; if (q.age > .02f || q.k < .35f) continue
            val p = q.P; val b = v3((p[1][0] + p[3][0]) / 2, (p[1][1] + p[3][1]) / 2, (p[1][2] + p[3][2]) / 2)
            val pr = tr[n - 2].P; val b0 = v3((pr[1][0] + pr[3][0]) / 2, (pr[1][1] + pr[3][1]) / 2, (pr[1][2] + pr[3][2]) / 2)
            val mv = vsub(b, b0); val ml = vlen(mv).let { if (it == 0f) 1f else it }
            val cnt = 1 + if (Math.random() < q.k) 1 else 0
            for (i in 0 until cnt) {
                val sp = .8f + Math.random().toFloat() * 1.8f
                sparks.add(Spk(v3(b[0] + (rnd(-.5f, .5f)) * .03f, b[1] + rnd(-.5f, .5f) * .03f, b[2] + rnd(-.5f, .5f) * .03f),
                    v3(-mv[0] / ml * sp * .6f + rnd(-.5f, .5f) * 1.6f, -mv[1] / ml * sp * .6f + rnd(0f, 1.2f), -mv[2] / ml * sp * .6f + rnd(-.5f, .5f) * 1.6f),
                    0f, .2f + rnd(0f, .3f), .05f + rnd(0f, .11f), .006f + rnd(0f, .008f)))
            }
        }
    }

    fun clear() { sparks.clear(); trail[0].clear(); trail[1].clear() }

    fun draw(vpU: FloatArray) {
        val rows = arrayOf(samples(trail[0]), samples(trail[1]))
        if (rows[0] == null && rows[1] == null && sparks.isEmpty()) return
        glUseProgram(prog); glUniformMatrix4fv(uVP, 1, false, vpU, 0); glUniform1f(uR, red)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vb)
        glEnable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE)
        for (pass in intArrayOf(1, 0)) {
            var n = 0
            rows.forEachIndexed { i, s -> if (s != null) n = build(s, if (i == 1) .71f else .29f, if (pass == 1) 1.9f else 1f, if (pass == 1) 1.8f else 1f, n) }
            if (n > 0) { glUniform1f(uG, pass.toFloat()); up(n); glDrawArrays(GL_TRIANGLES, 0, n / 9) }
        }
        if (sparks.isNotEmpty()) {
            var n = 0
            val co = arrayOf(floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(1f, -1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 0f), floatArrayOf(0f, -1f, 0f, 1f), floatArrayOf(1f, 1f, 1f, 0f), floatArrayOf(0f, 1f, 0f, 0f))
            for (s in sparks) {
                if (n > buf.size - 60) break
                val L = vlen(s.v).let { if (it == 0f) 1f else it }; val d = v3(s.v[0] / L, s.v[1] / L, s.v[2] / L)
                val vw = vsub(eye, s.p); val sd = vcross(d, vw); val sl = vlen(sd).let { if (it == 0f) 1f else it }
                val ln = s.len * (1 - s.age / s.life * .5f); val pr = s.age / s.life
                for (c in co) {
                    val a = c[0] * ln; val b = c[1] * s.w / sl
                    buf[n++] = s.p[0] + d[0] * a + sd[0] * b; buf[n++] = s.p[1] + d[1] * a + sd[1] * b; buf[n++] = s.p[2] + d[2] * a + sd[2] * b
                    buf[n++] = c[2]; buf[n++] = c[3]; buf[n++] = pr; buf[n++] = 1f; buf[n++] = 0f; buf[n++] = 0f
                }
            }
            glUniform1f(uG, 2f); up(n); glDrawArrays(GL_TRIANGLES, 0, n / 9)
        }
        glBindVertexArray(0); glDepthMask(true); glDisable(GL_BLEND)
    }

    private fun up(n: Int) { fb.clear(); fb.put(buf, 0, n); fb.flip(); glBufferData(GL_ARRAY_BUFFER, n * 4, fb, GL_STREAM_DRAW) }

    companion object {
        private const val VS = """#version 300 es
layout(location=0) in vec3 aP;layout(location=1) in vec2 aU;layout(location=2) in vec4 aR;
uniform mat4 uVP;out vec2 vU;out vec4 vR;void main(){vU=aU;vR=aR;gl_Position=uVP*vec4(aP,1.);}"""
        private const val FS = """#version 300 es
precision highp float;in vec2 vU;in vec4 vR;uniform float uG;uniform float uR;out vec4 o;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}
void mainX(){
 float ag=vR.x,k=vR.y,seed=vR.z,s=vR.w,v=vU.y,u=vU.x;
 if(uG>1.5){float x=abs(v*2.-1.);float core=pow(max(0.,1.-x),3.)*pow(max(0.,sin(3.14159*u)),.7);float I=core*(1.-ag);vec3 c=mix(vec3(1.,.55,.08),vec3(1.,.95,.7),core);o=vec4(c*I*1.7,1.);return;}
 float life=pow(.5+.5*cos(3.14159*clamp(ag,0.,1.)),1.4);
 float tail=smoothstep(0.,.65,s);
 float head=pow(s,1.4);
 float fa=life*tail*k;
 if(uG>.5){float g=exp(-pow((v-.72)*2.2,2.))*fa*(.2+.7*head);vec3 c=mix(vec3(1.,.35,.03),vec3(1.,.7,.2),head);o=vec4(c*g*.35,1.);return;}
 float fib=.93+.1*vn(vec2(u*.25+seed*9.,v*2.5));
 float body=pow(v,1.3)*fib*.85;
 float core=smoothstep(.6,.97,v)*(1.-smoothstep(.95,1.,v)*.85)*.7;
 float rim=smoothstep(0.,.22,v)*(1.-smoothstep(.94,1.,v)*.5);
 float I=(body*(.35+.9*head)+core*1.5*(.4+head))*rim*fa;
 float t=clamp(I*.7,0.,1.);
 vec3 col=mix(vec3(.9,.25,.02),vec3(1.,.72,.16),smoothstep(0.,.45,t));col=mix(col,vec3(1.,.96,.8),smoothstep(.55,1.,t));
 o=vec4(col*I*1.05,1.);}
void main(){mainX();float l=max(o.r,max(o.g,o.b));o.rgb=mix(o.rgb,vec3(l*1.1,l*.1+o.b*.3,l*.06+o.b*.25),uR);}"""
    }
}
