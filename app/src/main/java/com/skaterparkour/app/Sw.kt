package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import kotlin.math.*

/** Onda de choque no chão (porte do swFire do HTML, texturas Vefects). Espaço "unidade". */
class Sw(ctx: Context) {
    private class W(val c: V, val R: Float, val life: Float, var age: Float, val col: V, val seed: Float, val pw: Float)
    private val L = ArrayList<W>()
    private val prog = Gl.program(VS, FS)
    private val vao: Int; private val n: Int
    private val tex = IntArray(4)
    private val names = arrayOf("tB", "tN", "tD", "tM")

    init {
        val NS = 96; val v = FloatArray(NS * 12); var k = 0
        for (i in 0 until NS) { val a = i.toFloat() / NS; val b = (i + 1f) / NS; for (x in floatArrayOf(a, 0f, b, 0f, b, 1f, a, 0f, b, 1f, a, 1f)) v[k++] = x }
        val fb = Gl.floats(v.size); fb.put(v); fb.flip()
        vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
        val vb = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vb); glBufferData(GL_ARRAY_BUFFER, v.size * 4, fb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0); glBindVertexArray(0)
        n = NS * 6
        val files = arrayOf("n3", "n1", "nn", "mk")
        for (i in 0 until 4) {
            tex[i] = Gl.texture(ctx, "w/sw_${files[i]}.png", mip = true, repeat = true)
            if (i == 3) { glBindTexture(GL_TEXTURE_2D, tex[i]); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE) }
        }
    }

    /** c e R em metros (como no HTML: swFire converte do quadro local) */
    fun fire(cM: V, Rm: Float, life: Float, col: V, pw: Float = 1f, delay: Float = 0f) {
        L.add(W(v3(cM[0] / Game.K, 0f, cM[2] / Game.K), Rm / Game.K, life, .07f - delay, col, Math.random().toFloat() * 10, pw))
    }

    fun tick(dt: Float) { for (s in L) s.age += dt; L.removeAll { it.age > it.life } }
    fun clear() = L.clear()

    fun draw(vpU: FloatArray) {
        if (L.isEmpty()) return
        glUseProgram(prog); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_DEPTH_TEST)
        for (i in 0 until 4) { glActiveTexture(GL_TEXTURE8 + i); glBindTexture(GL_TEXTURE_2D, tex[i]); glUniform1i(glGetUniformLocation(prog, names[i]), 8 + i) }
        glUniformMatrix4fv(glGetUniformLocation(prog, "uVP"), 1, false, vpU, 0)
        glBindVertexArray(vao)
        fun u(nm: String) = glGetUniformLocation(prog, nm)
        for (s in L) {
            if (s.age < 0) continue
            val q = s.age / s.life; val e = 1 - (1 - q).pow(4)
            for (Lr in 0 until (if (s.pw > 1.5f) 3 else 2)) {
                val k = if (Lr == 2) 1.18f else if (Lr == 1) .62f else 1f
                glUniform3f(u("uC"), s.c[0], 0f, s.c[2]); glUniform1f(u("uR"), s.R * k * (.4f + .75f * e)); glUniform1f(u("uRin"), .45f)
                glUniform1f(u("uT"), s.age + s.seed + Lr * 3.1f); glUniform1f(u("uOff"), -.15f + .2f * q)
                glUniform1f(u("uEro"), max(0f, (q - .35f) * 1.4f)); glUniform1f(u("uEmi"), (if (Lr != 0) 1.2f else 1.6f) * (.8f + .35f * s.pw)); glUniform1f(u("uCA"), if (Lr != 0) 2f else 6f)
                glUniform1f(u("uA"), (1 - q * q) * (if (Lr != 0) .7f else 1f) * (if (Lr == 2) .55f else 1f)); glUniform1f(u("uPow"), 1f); glUniform1f(u("uMul"), 1f)
                if (Lr != 0) glUniform3f(u("uCol"), 1f, .9f, .75f) else glUniform3f(u("uCol"), s.col[0], s.col[1], s.col[2])
                glDrawArrays(GL_TRIANGLES, 0, n)
            }
        }
        glBindVertexArray(0)
        for (i in 0 until 4) { glActiveTexture(GL_TEXTURE8 + i); glBindTexture(GL_TEXTURE_2D, 0) }
        glActiveTexture(GL_TEXTURE0); glDepthMask(true); glDisable(GL_BLEND)
    }

    companion object {
        private const val VS = """#version 300 es
uniform mat4 uVP;uniform vec3 uC;uniform float uR,uRin;layout(location=0) in vec2 aP;out vec2 vUv;out vec3 vW;
void main(){float a=aP.x*6.2831853;float r=mix(uRin,1.,aP.y)*uR;vec3 w=uC+vec3(cos(a)*r,.02,sin(a)*r);vUv=vec2(aP.x*3.,aP.y);vW=w;gl_Position=uVP*vec4(w,1.);}"""
        private const val FS = """#version 300 es
precision highp float;in vec2 vUv;in vec3 vW;out vec4 o;
uniform sampler2D tB,tN,tD,tM;uniform float uT,uOff,uEro,uEmi,uCA,uA,uPow,uMul;uniform vec3 uCol;
void main(){
 vec2 uv=vUv;
 vec2 dn=texture(tD,uT*vec2(.02,-.1)+uv).rg*2.-1.;
 vec2 tu=uT*vec2(-.05,-.2)+uv;
 vec2 bu=(dn-.5)*2.*.1+tu;
 float ab=uCA*.001;
 vec3 base=vec3(texture(tB,bu+ab).r,texture(tB,bu).r,texture(tB,bu-ab).r);
 float nz=texture(tN,uT*vec2(-.07,-.3)+uv).r;
 float m=texture(tM,vec2(.5,nz*uPow*.0+uv.y+uOff+nz*.35)).r;
 float e=smoothstep(uEro,uEro+1.,m);
 float it=clamp(pow(clamp(e,0.,1.),5.)*5.,0.,1.);
 float edge=smoothstep(0.,.12,uv.y)*smoothstep(1.,.78,uv.y);
 vec3 c=uCol*base*uEmi*it;float al=clamp(it*edge*uA,0.,1.);
 o=vec4(c*al*1.6,al);}"""
    }
}
