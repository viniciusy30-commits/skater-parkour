package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Malha do guerreiro (formato convertido do visualizador: posições/normais int16, índices de 32 bits). */
class WMesh(ctx: Context, file: String, skinned: Boolean) {
    val vao: Int; val n: Int
    val ctr = FloatArray(3); val ext = FloatArray(3)

    init {
        val bytes = Gl.readAsset(ctx, file)
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        h.int; val nv = h.int; n = h.int; val stride = h.int
        for (i in 0 until 3) ctr[i] = h.float
        for (i in 0 until 3) ext[i] = h.float
        val off = 40
        val vb = Gl.direct(nv * stride); vb.put(bytes, off, nv * stride); vb.flip()
        val ib = Gl.direct(n * 4); ib.put(bytes, off + nv * stride, n * 4); ib.flip()
        vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
        val vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferData(GL_ARRAY_BUFFER, nv * stride, vb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 4, GL_SHORT, true, stride, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_SHORT, true, stride, 8)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 2, GL_UNSIGNED_SHORT, true, stride, 16)
        if (skinned) {
            glEnableVertexAttribArray(3); glVertexAttribIPointer(3, 4, GL_UNSIGNED_BYTE, stride, 20)
            glEnableVertexAttribArray(4); glVertexAttribPointer(4, 4, GL_UNSIGNED_BYTE, true, stride, 24)
        }
        val ibo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo); glBufferData(GL_ELEMENT_ARRAY_BUFFER, n * 4, ib, GL_STATIC_DRAW)
        glBindVertexArray(0)
    }

    fun draw() { glBindVertexArray(vao); glDrawElements(GL_TRIANGLES, n, GL_UNSIGNED_INT, 0) }
}

/** Malha simples com cor por vértice (cenário e boneco). */
class CMesh {
    private val v = ArrayList<Float>()
    var vao = 0; var n = 0

    private fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray) {
        v.add(x); v.add(y); v.add(z); v.add(nx); v.add(ny); v.add(nz); v.add(c[0]); v.add(c[1]); v.add(c[2])
    }

    private fun tri(a: FloatArray, b: FloatArray, c: FloatArray, col: FloatArray) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val wx = c[0] - a[0]; val wy = c[1] - a[1]; val wz = c[2] - a[2]
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
        vert(a[0], a[1], a[2], nx, ny, nz, col); vert(b[0], b[1], b[2], nx, ny, nz, col); vert(c[0], c[1], c[2], nx, ny, nz, col)
    }

    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, yaw: Float, col: FloatArray, tiltZ: Float = 0f) {
        val c = cos(yaw); val s = sin(yaw); val ct = cos(tiltZ); val st = sin(tiltZ)
        fun p(x: Float, y: Float, z: Float): FloatArray {
            val x1 = x * ct - y * st; val y1 = x * st + y * ct
            return floatArrayOf(cx + x1 * c + z * s, cy + y1, cz - x1 * s + z * c)
        }
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        val P = arrayOf(p(-hx, -hy, -hz), p(hx, -hy, -hz), p(hx, hy, -hz), p(-hx, hy, -hz), p(-hx, -hy, hz), p(hx, -hy, hz), p(hx, hy, hz), p(-hx, hy, hz))
        val F = arrayOf(intArrayOf(0, 3, 2, 1), intArrayOf(4, 5, 6, 7), intArrayOf(0, 4, 7, 3), intArrayOf(1, 2, 6, 5), intArrayOf(3, 7, 6, 2), intArrayOf(0, 1, 5, 4))
        for ((i, f) in F.withIndex()) {
            val sh = if (i == 4) 1.08f else if (i == 5) .7f else 1f
            val cc = floatArrayOf(col[0] * sh, col[1] * sh, col[2] * sh)
            tri(P[f[0]], P[f[1]], P[f[2]], cc); tri(P[f[0]], P[f[2]], P[f[3]], cc)
        }
    }

    fun cyl(cx: Float, y0: Float, cz: Float, r0: Float, r1: Float, h: Float, seg: Int, col: FloatArray, caps: Boolean = true, jitter: Float = 0f) {
        for (i in 0 until seg) {
            val a0 = i * 6.2832f / seg; val a1 = (i + 1) * 6.2832f / seg
            val j = if (jitter > 0f) 1f + ((i * 7919 % 13) / 13f - .5f) * jitter else 1f
            val cc = floatArrayOf(col[0] * j, col[1] * j, col[2] * j)
            val b0 = floatArrayOf(cx + cos(a0) * r0, y0, cz + sin(a0) * r0); val b1 = floatArrayOf(cx + cos(a1) * r0, y0, cz + sin(a1) * r0)
            val t0 = floatArrayOf(cx + cos(a0) * r1, y0 + h, cz + sin(a0) * r1); val t1 = floatArrayOf(cx + cos(a1) * r1, y0 + h, cz + sin(a1) * r1)
            // normais suaves no lado
            val n0x = cos(a0); val n0z = sin(a0); val n1x = cos(a1); val n1z = sin(a1)
            vert(b0[0], b0[1], b0[2], n0x, 0f, n0z, cc); vert(t1[0], t1[1], t1[2], n1x, 0f, n1z, cc); vert(b1[0], b1[1], b1[2], n1x, 0f, n1z, cc)
            vert(b0[0], b0[1], b0[2], n0x, 0f, n0z, cc); vert(t0[0], t0[1], t0[2], n0x, 0f, n0z, cc); vert(t1[0], t1[1], t1[2], n1x, 0f, n1z, cc)
            if (caps) {
                tri(floatArrayOf(cx, y0 + h, cz), t1, t0, cc)
                tri(floatArrayOf(cx, y0, cz), b0, b1, floatArrayOf(cc[0] * .6f, cc[1] * .6f, cc[2] * .6f))
            }
        }
    }

    fun sphere(cx: Float, cy: Float, cz: Float, r: Float, sy: Float, col: FloatArray, seg: Int = 14) {
        val rings = seg / 2
        for (i in 0 until rings) for (j in 0 until seg) {
            fun p(a: Int, b: Int): FloatArray {
                val th = a * PI.toFloat() / rings; val ph = b * 6.2832f / seg
                return floatArrayOf(sin(th) * cos(ph), cos(th), sin(th) * sin(ph))
            }
            val q = arrayOf(p(i, j), p(i + 1, j), p(i + 1, j + 1), p(i, j + 1))
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) {
                val n = q[k]
                vert(cx + n[0] * r, cy + n[1] * r * sy, cz + n[2] * r, n[0], n[1], n[2], col)
            }
        }
    }

    fun raw(a: FloatArray, b: FloatArray, c: FloatArray, col: FloatArray) = tri(a, b, c, col)

    fun upload() {
        val fb = Gl.floats(v.size); for (x in v) fb.put(x); fb.flip()
        n = v.size / 9
        vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
        val vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo); glBufferData(GL_ARRAY_BUFFER, v.size * 4, fb, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 36, 12)
        glEnableVertexAttribArray(2); glVertexAttribPointer(2, 3, GL_FLOAT, false, 36, 24)
        glBindVertexArray(0); v.clear()
    }

    fun draw() { glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES, 0, n) }
}

object Shaders {
    const val LIGHT = """
vec3 light(vec3 al, vec3 N, vec3 V, float metal, float shadow){
  vec3 L1=normalize(vec3(.55,.85,.45)), L2=normalize(vec3(-.7,.35,-.6));
  float d1=max(dot(N,L1),0.)*shadow, d2=max(dot(N,L2),0.);
  vec3 hemi=mix(vec3(.30,.27,.26),vec3(.58,.62,.72),N.y*.5+.5);
  vec3 c=al*(hemi+vec3(1.05,.93,.78)*d1*.85+vec3(.32,.42,.66)*d2*.35);
  vec3 H=normalize(L1+V); c+=vec3(1.,.9,.75)*pow(max(dot(N,H),0.),40.)*d1*(.05+metal);
  c+=vec3(.55,.45,.35)*pow(1.-max(dot(N,V),0.),3.)*.18;
  return c; }
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.47,.47,.52); return mix(c,f,smoothstep(28.,150.,d)*.85); }
"""

    const val SKIN_VS = """#version 300 es
precision highp float; precision highp int;
layout(location=0) in vec4 aPos; layout(location=1) in vec4 aNrm; layout(location=2) in vec2 aUV; layout(location=3) in uvec4 aJ; layout(location=4) in vec4 aW;
uniform sampler2D uBones; uniform vec3 uCtr; uniform vec3 uExt; uniform mat4 uVP; uniform mat4 uM;
out vec2 vUV; out vec3 vN; out vec3 vP;
mat4 B(uint i){int r=int(i);return mat4(texelFetch(uBones,ivec2(0,r),0),texelFetch(uBones,ivec2(1,r),0),texelFetch(uBones,ivec2(2,r),0),texelFetch(uBones,ivec2(3,r),0));}
void main(){ vec4 p=vec4(uCtr+aPos.xyz*uExt,1.); mat4 m=B(aJ.x)*aW.x+B(aJ.y)*aW.y+B(aJ.z)*aW.z+B(aJ.w)*aW.w;
 vec4 w=uM*(m*p); vP=w.xyz; vN=mat3(uM)*(mat3(m)*normalize(aNrm.xyz)); vUV=aUV; gl_Position=uVP*w; }"""

    const val WEAP_VS = """#version 300 es
precision highp float;
layout(location=0) in vec4 aPos; layout(location=1) in vec4 aNrm; layout(location=2) in vec2 aUV;
uniform vec3 uCtr; uniform vec3 uExt; uniform mat4 uVP; uniform mat4 uM;
out vec2 vUV; out vec3 vN; out vec3 vP;
void main(){ vec4 w=uM*vec4(uCtr+aPos.xyz*uExt,1.); vP=w.xyz; vN=mat3(uM)*normalize(aNrm.xyz); vUV=aUV; gl_Position=uVP*w; }"""

    /** shader do personagem e dos machados — igual ao FS_L do visualizador HTML */
    const val TEX_FS = """#version 300 es
precision highp float;
in vec2 vUV;in vec3 vN;in vec3 vP;uniform sampler2D uTex;uniform vec3 uCam;uniform float uMetal;uniform float uRune,uTm,uRim;uniform vec3 uRC;
out vec4 o;
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.47,.47,.52); return mix(c,f,smoothstep(28.,150.,d)*.85); }
void main(){vec3 N=normalize(vN);if(!gl_FrontFacing)N=-N;vec3 V=normalize(uCam-vP);
 vec3 al=pow(texture(uTex,vUV).rgb,vec3(2.2));
 vec3 L1=normalize(vec3(.5,.9,.7)),L2=normalize(vec3(-.8,.3,-.5));
 float d1=max(dot(N,L1),0.),d2=max(dot(N,L2),0.);
 vec3 hemi=mix(vec3(.38,.36,.35),vec3(.62,.64,.70),N.y*.5+.5);
 vec3 c=al*(hemi+vec3(1.0,.96,.9)*d1*.75+vec3(.35,.42,.6)*d2*.3);
 vec3 H=normalize(L1+V);c+=vec3(.4)*pow(max(dot(N,H),0.),40.)*d1*(.06+uMetal);
 if(uRune>0.){vec3 t0=texture(uTex,vUV).rgb;float mx=max(t0.r,max(t0.g,t0.b));float rm=smoothstep(.04,.14,t0.r-t0.b)*smoothstep(.22,.42,mx);
  float pu=.75+.25*sin(uTm*9.+vP.y*6.);c=mix(c,uRC*dot(c,vec3(.33))*.4,rm*uRune*.8);c+=uRC*rm*uRune*pu*2.4;}
 c+=uRC*uRim*(.25+.75*pow(1.-max(dot(N,V),0.),2.))*(.8+.2*sin(uTm*14.));
 c=fogc(c,vP,uCam);
 o=vec4(pow(c,vec3(1./2.2)),1.);}"""

    const val COL_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 aP; layout(location=1) in vec3 aN; layout(location=2) in vec3 aC;
uniform mat4 uVP; uniform mat4 uM; out vec3 vN; out vec3 vP; out vec3 vC;
void main(){ vec4 w=uM*vec4(aP,1.); vP=w.xyz; vN=mat3(uM)*aN; vC=aC; gl_Position=uVP*w; }"""

    const val COL_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vP; in vec3 vC; uniform vec3 uCam; uniform vec3 uFlash; out vec4 o;
float h21(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
""" + LIGHT + """
void main(){ vec3 N=normalize(vN); if(!gl_FrontFacing) N=-N; vec3 V=normalize(uCam-vP);
 float g=.88+.24*h21(floor(vP.xz*9.)+floor(vP.y*14.));
 vec3 al=pow(vC,vec3(2.2))*g; vec3 c=light(al,N,V,0.,1.)+uFlash;
 c=fogc(c,vP,uCam); o=vec4(pow(c,vec3(1./2.2)),1.); }"""

    const val GROUND_VS = """#version 300 es
precision highp float; layout(location=0) in vec2 aP; uniform mat4 uVP; out vec3 vW;
void main(){ vW=vec3(aP.x*220.,0.,aP.y*220.); gl_Position=uVP*vec4(vW,1.); }"""

    const val GROUND_FS = """#version 300 es
precision highp float;
in vec3 vW; uniform vec3 uCam; uniform vec4 uSh[3]; out vec4 o;
float h21(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}
float fbm(vec2 p){float s=0.,a=.5;for(int i=0;i<4;i++){s+=a*vn(p);p*=2.03;a*=.5;}return s;}
""" + LIGHT + """
void main(){
  vec2 p=vW.xz; float r=length(p); float n=fbm(p*.35), n2=fbm(p*2.1), n3=vn(p*9.);
  // grama
  vec3 grass=mix(vec3(.17,.22,.10),vec3(.30,.33,.15),n)*(.8+.35*n2);
  grass=mix(grass,vec3(.36,.33,.20),smoothstep(.55,.8,fbm(p*.12+7.))*.6);
  // terra batida da área de treino
  vec3 dirt=mix(vec3(.36,.29,.21),vec3(.47,.39,.29),n2)*(.85+.25*n3);
  dirt=mix(dirt,vec3(.30,.24,.18),smoothstep(.45,.7,fbm(p*.8+3.))*.5);
  float edge=11.5+ (n-.5)*1.6;
  float inA=smoothstep(edge+.6,edge-.6,r);
  vec3 col=mix(grass,dirt,inA);
  // anel de pedras
  float ring=smoothstep(.55,.4,abs(r-9.6));
  float ang=atan(p.y,p.x); vec2 st=vec2(ang*9.6/0.55,(r-9.6)/.45);
  vec2 cell=floor(st); vec2 f=fract(st)-.5; float gap=smoothstep(.5,.42,max(abs(f.x),abs(f.y)));
  vec3 stone=vec3(.46,.45,.43)*(.75+.4*h21(cell))*(.85+.2*n3);
  col=mix(col,mix(vec3(.14,.13,.12),stone,gap),ring);
  // linhas de marcação no centro
  float mk=exp(-pow((r-3.)/.05,2.))*.35+exp(-pow((r-6.2)/.04,2.))*.22;
  col=mix(col,vec3(.62,.55,.42),mk*inA);
  // sombras (personagem, boneco)
  float sh=1.;
  for(int i=0;i<3;i++){ vec4 s=uSh[i]; if(s.z<=0.) continue; float d=length(p-s.xy)/s.z; sh*=1.-s.w*smoothstep(1.,.15,d); }
  vec3 N=vec3(0,1,0); vec3 V=normalize(uCam-vW);
  vec3 c=light(pow(col,vec3(2.2)),N,V,0.,1.)*sh;
  c=fogc(c,vW,uCam);
  o=vec4(pow(c,vec3(1./2.2)),1.); }"""

    const val SKY_VS = """#version 300 es
precision highp float; layout(location=0) in vec2 aP; out vec2 vP; void main(){ vP=aP; gl_Position=vec4(aP,.9999,1.); }"""

    const val SKY_FS = """#version 300 es
precision highp float; in vec2 vP; uniform mat4 uIVP; uniform vec3 uCam; out vec4 o;
float h21(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}
void main(){ vec4 w=uIVP*vec4(vP,1.,1.); vec3 d=normalize(w.xyz/w.w-uCam);
  float y=d.y; vec3 hor=vec3(.47,.47,.52), zen=vec3(.16,.24,.40), warm=vec3(.95,.62,.38);
  vec3 c=mix(hor,zen,smoothstep(0.,.55,y));
  vec3 sd=normalize(vec3(.55,.25,.45)); float sun=max(dot(d,sd),0.);
  c+=warm*pow(sun,6.)*.45+vec3(1.,.9,.7)*pow(sun,300.)*1.5;
  vec2 cp=d.xz/max(y+.15,.05); float cl=smoothstep(.55,.9,vn(cp*1.3)*.6+vn(cp*3.1)*.4)*smoothstep(0.,.25,y);
  c=mix(c,vec3(.85,.80,.78),cl*.35);
  c=mix(c,hor*.8,smoothstep(.0,-.2,y));
  o=vec4(c,1.); }"""
}

/** Cenário: céu, campo de treino, cerca, tochas, montanhas. */
class Arena {
    val torches = ArrayList<FloatArray>()
    private lateinit var props: CMesh
    private var progC = 0; private var progG = 0; private var progS = 0
    private var gVao = 0; private var sVao = 0

    fun init() {
        progC = Gl.program(Shaders.COL_VS, Shaders.COL_FS)
        progG = Gl.program(Shaders.GROUND_VS, Shaders.GROUND_FS)
        progS = Gl.program(Shaders.SKY_VS, Shaders.SKY_FS)
        val q = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        val fb = Gl.floats(12); fb.put(q); fb.flip()
        for (k in 0 until 2) {
            val vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
            val vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo); fb.position(0); glBufferData(GL_ARRAY_BUFFER, 48, fb, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0)
            if (k == 0) gVao = vao else sVao = vao
        }
        glBindVertexArray(0)

        val m = CMesh()
        val wood = floatArrayOf(.40f, .27f, .16f); val woodD = floatArrayOf(.28f, .19f, .11f)
        // cerca de madeira
        val R = 15.5f; val nPost = 40
        for (i in 0 until nPost) {
            val a = i * 6.2832f / nPost
            if (i % 10 == 0) continue // entradas
            val x = cos(a) * R; val z = sin(a) * R
            m.box(x, .7f, z, .2f, 1.4f, .2f, -a, woodD)
            val a2 = (i + 1) * 6.2832f / nPost
            if ((i + 1) % 10 == 0) continue
            val x2 = cos(a2) * R; val z2 = sin(a2) * R
            val mx = (x + x2) / 2; val mz = (z + z2) / 2; val len = hypot(x2 - x, z2 - z)
            val yaw = atan2(x2 - x, z2 - z)
            m.box(mx, 1.05f, mz, .08f, .12f, len, yaw, wood)
            m.box(mx, .55f, mz, .08f, .12f, len, yaw, wood)
        }
        // tochas
        for (i in 0 until 6) {
            val a = i * 6.2832f / 6 + .26f
            val x = cos(a) * 10.6f; val z = sin(a) * 10.6f
            m.cyl(x, 0f, z, .09f, .07f, 1.9f, 8, woodD)
            m.cyl(x, 1.9f, z, .16f, .2f, .22f, 8, floatArrayOf(.18f, .16f, .15f))
            torches.add(floatArrayOf(x, 2.18f, z))
        }
        // suportes de armas e barris
        for (i in 0 until 3) {
            val a = 2.3f + i * .32f; val x = cos(a) * 13f; val z = sin(a) * 13f
            m.cyl(x, 0f, z, .32f, .32f, .85f, 12, floatArrayOf(.36f, .25f, .15f), jitter = .2f)
            m.cyl(x, .2f, z, .335f, .335f, .06f, 12, floatArrayOf(.2f, .2f, .22f), caps = false)
            m.cyl(x, .62f, z, .335f, .335f, .06f, 12, floatArrayOf(.2f, .2f, .22f), caps = false)
        }
        run {
            val a = -1.9f; val x = cos(a) * 12.8f; val z = sin(a) * 12.8f; val yaw = -a + 1.57f
            m.box(x, .9f, z, 2.4f, .12f, .12f, yaw, wood); m.box(x, 1.5f, z, 2.4f, .12f, .12f, yaw, wood)
            m.box(x + cos(yaw) * 1.1f, .85f, z - sin(yaw) * 1.1f, .14f, 1.7f, .14f, yaw, woodD)
            m.box(x - cos(yaw) * 1.1f, .85f, z + sin(yaw) * 1.1f, .14f, 1.7f, .14f, yaw, woodD)
            for (k in 0 until 4) {
                val o = -.75f + k * .5f
                m.box(x + cos(yaw) * o, 1.2f, z - sin(yaw) * o, .05f, 1.4f, .05f, yaw, floatArrayOf(.5f, .5f, .52f), .25f)
            }
        }
        // pedras espalhadas
        for (i in 0 until 26) {
            val a = i * 2.39996f; val r = 17f + (i * 37 % 23)
            m.sphere(cos(a) * r, -.1f, sin(a) * r, .5f + (i % 5) * .25f, .55f, floatArrayOf(.36f, .35f, .33f), 8)
        }
        // montanhas ao longe
        val NM = 72; val RM = 150f
        fun hgt(i: Int): Float { val a = i * 6.2832f / NM; return 14f + 10f * sin(a * 3f + 1f) + 7f * sin(a * 7f + 2f) + 4f * sin(a * 13f) }
        for (i in 0 until NM) {
            val a0 = i * 6.2832f / NM; val a1 = (i + 1) * 6.2832f / NM
            val h0 = hgt(i); val h1 = hgt(i + 1)
            val c = floatArrayOf(.25f, .28f, .33f); val cT = floatArrayOf(.33f, .35f, .40f)
            val b0 = floatArrayOf(cos(a0) * RM, -2f, sin(a0) * RM); val b1 = floatArrayOf(cos(a1) * RM, -2f, sin(a1) * RM)
            val t0 = floatArrayOf(cos(a0) * (RM + 25f), h0, sin(a0) * (RM + 25f)); val t1 = floatArrayOf(cos(a1) * (RM + 25f), h1, sin(a1) * (RM + 25f))
            m.raw(b0, t0, t1, cT); m.raw(b0, t1, b1, c)
        }
        m.upload(); props = m
    }

    fun draw(vp: FloatArray, ivp: FloatArray, eye: FloatArray, shadows: FloatArray) {
        // céu
        glDepthMask(false)
        glUseProgram(progS)
        glUniformMatrix4fv(glGetUniformLocation(progS, "uIVP"), 1, false, ivp, 0)
        glUniform3fv(glGetUniformLocation(progS, "uCam"), 1, eye, 0)
        glBindVertexArray(sVao); glDrawArrays(GL_TRIANGLES, 0, 6)
        glDepthMask(true)
        // chão
        glUseProgram(progG)
        glUniformMatrix4fv(glGetUniformLocation(progG, "uVP"), 1, false, vp, 0)
        glUniform3fv(glGetUniformLocation(progG, "uCam"), 1, eye, 0)
        glUniform4fv(glGetUniformLocation(progG, "uSh"), 3, shadows, 0)
        glBindVertexArray(gVao); glDrawArrays(GL_TRIANGLES, 0, 6)
        // objetos
        drawCol(props, vp, eye, M4.id(), null)
    }

    fun drawCol(m: CMesh, vp: FloatArray, eye: FloatArray, model: FloatArray, flash: FloatArray?) {
        glUseProgram(progC)
        glUniformMatrix4fv(glGetUniformLocation(progC, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(progC, "uM"), 1, false, model, 0)
        glUniform3fv(glGetUniformLocation(progC, "uCam"), 1, eye, 0)
        glUniform3fv(glGetUniformLocation(progC, "uFlash"), 1, flash ?: floatArrayOf(0f, 0f, 0f), 0)
        m.draw()
    }

    companion object {
        /** Boneco de treino: base, poste, corpo de palha com cordas, braços e cabeça. */
        fun dummyMesh(): CMesh {
            val m = CMesh()
            val wood = floatArrayOf(.42f, .29f, .17f); val woodD = floatArrayOf(.27f, .18f, .11f)
            val sack = floatArrayOf(.70f, .58f, .40f); val rope = floatArrayOf(.33f, .25f, .16f); val straw = floatArrayOf(.83f, .70f, .38f)
            m.cyl(0f, 0f, 0f, .46f, .42f, .12f, 16, woodD)
            m.cyl(0f, .12f, 0f, .075f, .07f, 1.55f, 10, wood)
            m.cyl(0f, .78f, 0f, .25f, .27f, .3f, 14, sack, jitter = .12f)
            m.cyl(0f, 1.08f, 0f, .27f, .24f, .32f, 14, sack, jitter = .12f)
            m.sphere(0f, .78f, 0f, .25f, .25f, sack)
            m.cyl(0f, .9f, 0f, .278f, .278f, .045f, 14, rope, caps = false)
            m.cyl(0f, 1.2f, 0f, .262f, .262f, .045f, 14, rope, caps = false)
            m.box(0f, 1.32f, 0f, 1.25f, .11f, .11f, 0f, wood)
            m.sphere(.66f, 1.32f, 0f, .1f, 1f, straw, 8); m.sphere(-.66f, 1.32f, 0f, .1f, 1f, straw, 8)
            m.cyl(0f, 1.38f, 0f, .09f, .07f, .1f, 8, rope)
            m.sphere(0f, 1.62f, 0f, .2f, 1.08f, sack)
            m.box(.07f, 1.66f, .18f, .06f, .025f, .02f, 0f, floatArrayOf(.12f, .08f, .06f), .5f)
            m.box(-.07f, 1.66f, .18f, .06f, .025f, .02f, 0f, floatArrayOf(.12f, .08f, .06f), -.5f)
            m.box(0f, 1.55f, .185f, .1f, .02f, .02f, 0f, floatArrayOf(.12f, .08f, .06f))
            m.upload()
            return m
        }
    }
}
