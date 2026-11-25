package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2

/** Mapa principal: a arena grega (colosseu de mármore). Colisão em caixas + malha de 400 mil triângulos. */
object Arena {
    val stations = ArrayList<Station>()

    private fun st(name: String, x: Float, z: Float, y: Float) {
        // olha para o centro da arena
        val yaw = if (x * x + z * z < 1f) 0f else atan2(-x, -z)
        stations.add(Station(name, x, z, yaw, y + 0.05f))
    }

    fun build(ctx: Context): World {
        val w = World()
        stations.clear()
        val b = ByteBuffer.wrap(Gl.readAsset(ctx, "w/arena_col.bin")).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.int
        for (i in 0 until n) {
            val x0 = b.float; val y0 = b.float; val z0 = b.float; val x1 = b.float; val y1 = b.float; val z1 = b.float
            w.add(x0, y0, z0, x1, y1, z1, 0, "arena")
        }
        st("Centro da arena", 0f, 0f, 0f)
        st("Arena: lado leste", 9f, 0f, 0f)
        st("Arena: lado oeste", -9f, 0f, 0f)
        st("Arena: lado norte", 0f, 9f, 0f)
        st("Arena: lado sul", 0f, -9f, 0f)
        st("Arquibancada", 0f, 24f, 2.5f)
        st("Topo do coliseu", 0f, -32f, 22.7f)
        return w
    }
}

/** Desenha a malha da arena em blocos, com corte pelo campo de visão. Formato: ver tools/build_arena.py. */
class ArenaMesh(ctx: Context) {
    private val vao: Int
    private val prog: Int
    private val tex: Int
    private val ctr = FloatArray(3); private val ext = FloatArray(3)
    private val nCh: Int
    private val first: IntArray; private val count: IntArray
    private val bb: FloatArray   // 6 por bloco: min xyz, max xyz
    private val pl = FloatArray(24)
    private val uVP: Int; private val uCtr: Int; private val uExt: Int; private val uCam: Int; private val uD: Int

    init {
        val bytes = Gl.readAsset(ctx, "w/arena.msh")
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        h.int; val nv = h.int; val ni = h.int; nCh = h.int
        for (i in 0 until 3) ctr[i] = h.float
        for (i in 0 until 3) ext[i] = h.float
        first = IntArray(nCh); count = IntArray(nCh); bb = FloatArray(nCh * 6)
        for (c in 0 until nCh) {
            first[c] = h.int; count[c] = h.int
            for (k in 0 until 6) bb[c * 6 + k] = h.float
        }
        val off = h.position()
        val vb = Gl.direct(nv * 12); vb.put(bytes, off, nv * 12); vb.flip()
        val ib = Gl.direct(ni * 4); ib.put(bytes, off + nv * 12, ni * 4); ib.flip()
        vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
        val vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferData(GL_ARRAY_BUFFER, nv * 12, vb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_SHORT, true, 12, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_BYTE, true, 12, 6)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, 12, 8)
        val ibo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, ni * 4, ib, GL_STATIC_DRAW)
        glBindVertexArray(0)
        tex = Gl.texture(ctx, "w/arena_detail.png", true, true)
        prog = Gl.program(VS, FS)
        uVP = glGetUniformLocation(prog, "uVP"); uCtr = glGetUniformLocation(prog, "uCtr"); uExt = glGetUniformLocation(prog, "uExt")
        uCam = glGetUniformLocation(prog, "uCam"); uD = glGetUniformLocation(prog, "uD")
    }

    private fun planes(m: FloatArray) {
        // linhas da matriz (coluna-maior): r_i = m[i], m[4+i], m[8+i], m[12+i]
        for (p in 0 until 6) {
            val ax = p / 2; val sg = if (p % 2 == 0) 1f else -1f
            for (k in 0 until 4) pl[p * 4 + k] = m[k * 4 + 3] + sg * m[k * 4 + ax]
        }
    }

    private fun visible(c: Int): Boolean {
        val o = c * 6
        for (p in 0 until 6) {
            val a = pl[p * 4]; val b = pl[p * 4 + 1]; val cc = pl[p * 4 + 2]; val d = pl[p * 4 + 3]
            val x = if (a >= 0f) bb[o + 3] else bb[o]
            val y = if (b >= 0f) bb[o + 4] else bb[o + 1]
            val z = if (cc >= 0f) bb[o + 5] else bb[o + 2]
            if (a * x + b * y + cc * z + d < 0f) return false
        }
        return true
    }

    fun draw(vp: FloatArray, eye: FloatArray) {
        planes(vp)
        glUseProgram(prog)
        glUniformMatrix4fv(uVP, 1, false, vp, 0)
        glUniform3fv(uCtr, 1, ctr, 0); glUniform3fv(uExt, 1, ext, 0); glUniform3fv(uCam, 1, eye, 0)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex); glUniform1i(uD, 0)
        glBindVertexArray(vao)
        var s = -1; var n = 0
        for (c in 0 until nCh) {
            if (!visible(c)) continue
            if (s >= 0 && first[c] == s + n) { n += count[c]; continue }
            if (s >= 0) glDrawElements(GL_TRIANGLES, n, GL_UNSIGNED_INT, s * 4)
            s = first[c]; n = count[c]
        }
        if (s >= 0) glDrawElements(GL_TRIANGLES, n, GL_UNSIGNED_INT, s * 4)
        glBindVertexArray(0)
    }

    companion object {
        const val VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 aP; layout(location=1) in vec2 aE; layout(location=2) in vec4 aC;
uniform mat4 uVP; uniform vec3 uCtr; uniform vec3 uExt;
out vec3 vP; out vec3 vN; out vec3 vC; out float vA;
void main(){ vec3 p=uCtr+aP*uExt; vP=p;
 vec3 n=vec3(aE.x,aE.y,1.-abs(aE.x)-abs(aE.y));
 if(n.z<0.){ n.xy=(1.-abs(n.yx))*vec2(n.x>=0.?1.:-1., n.y>=0.?1.:-1.); }
 vN=normalize(n); vC=aC.rgb; vA=aC.a; gl_Position=uVP*vec4(p,1.); }"""

        val FS = """#version 300 es
precision highp float;
in vec3 vP; in vec3 vN; in vec3 vC; in float vA; uniform vec3 uCam; uniform sampler2D uD; out vec4 o;
""" + Shaders.LIGHT + """
void main(){
  vec3 N=normalize(vN); vec3 V=normalize(uCam-vP); if(dot(N,V)<0.) N=-N;
  vec3 aN=abs(N);
  vec2 uv = aN.y>max(aN.x,aN.z) ? vP.xz : (aN.x>aN.z ? vP.zy : vP.xy);
  vec3 d1=texture(uD,uv*.31).rgb; vec3 d2=texture(uD,uv*1.9+vec2(.37,.11)).rgb;
  float g=(.84+.32*d2.r)*(.88+.24*d1.g)*(1.-.40*d1.b);
  vec3 al=vC*vC*g;
  vec3 c=light(al,N,V,0.,1.);
  c*=mix(.42,1.,vA);
  c=fogc(c,vP,uCam);
  c=c/(1.+c*.12)*1.08;
  o=vec4(pow(c,vec3(1./2.2)),1.); }"""
    }
}
