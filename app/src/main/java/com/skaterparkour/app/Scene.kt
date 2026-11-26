package com.skaterparkour.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLUtils
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Malha do guerreiro (formato convertido do visualizador: posições/normais int16, índices de 32 bits). */
class WMesh(ctx: Context, file: String, skinned: Boolean, colored: Boolean = false) {
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
        // atributo 2: UV (u16 x2) na malha com textura; cor RGB + metal (u8 x4) na malha com cor por vértice
        glEnableVertexAttribArray(2)
        if (colored) glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, stride, 16)
        else glVertexAttribPointer(2, 2, GL_UNSIGNED_SHORT, true, stride, 16)
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
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.56,.64,.74); return mix(c,f,smoothstep(90.,700.,d)*.8); }
"""

    const val SKIN_VS = """#version 300 es
precision highp float; precision highp int;
layout(location=0) in vec4 aPos; layout(location=1) in vec4 aNrm; layout(location=2) in vec2 aUV; layout(location=3) in uvec4 aJ; layout(location=4) in vec4 aW;
uniform sampler2D uBones; uniform vec3 uCtr; uniform vec3 uExt; uniform mat4 uVP; uniform mat4 uM;
out vec2 vUV; out vec3 vN; out vec3 vP;
mat4 B(uint i){int r=int(i);return mat4(texelFetch(uBones,ivec2(0,r),0),texelFetch(uBones,ivec2(1,r),0),texelFetch(uBones,ivec2(2,r),0),texelFetch(uBones,ivec2(3,r),0));}
void main(){ vec4 p=vec4(uCtr+aPos.xyz*uExt,1.); mat4 m=B(aJ.x)*aW.x+B(aJ.y)*aW.y+B(aJ.z)*aW.z+B(aJ.w)*aW.w;
 vec4 w=uM*(m*p); vP=w.xyz; vN=mat3(uM)*(mat3(m)*normalize(aNrm.xyz)); vUV=aUV; gl_Position=uVP*w; }"""

    /** Esqueleto do guerreiro novo: igual ao SKIN_VS, mas o atributo 2 é a cor do vértice (RGB em gama 2.0, A = metal). */
    const val SKINC_VS = """#version 300 es
precision highp float; precision highp int;
layout(location=0) in vec4 aPos; layout(location=1) in vec4 aNrm; layout(location=2) in vec4 aCol; layout(location=3) in uvec4 aJ; layout(location=4) in vec4 aW;
uniform sampler2D uBones; uniform vec3 uCtr; uniform vec3 uExt; uniform mat4 uVP; uniform mat4 uM;
out vec4 vC; out vec3 vN; out vec3 vP;
mat4 B(uint i){int r=int(i);return mat4(texelFetch(uBones,ivec2(0,r),0),texelFetch(uBones,ivec2(1,r),0),texelFetch(uBones,ivec2(2,r),0),texelFetch(uBones,ivec2(3,r),0));}
void main(){ vec4 p=vec4(uCtr+aPos.xyz*uExt,1.); mat4 m=B(aJ.x)*aW.x+B(aJ.y)*aW.y+B(aJ.z)*aW.z+B(aJ.w)*aW.w;
 vec4 w=uM*(m*p); vP=w.xyz; vN=mat3(uM)*(mat3(m)*normalize(aNrm.xyz)); vC=aCol; gl_Position=uVP*w; }"""

    /** Guerreiro novo (corpo + machados): cor por vértice assada da textura original; o alfa guarda o quanto é metal. */
    const val CHAR_FS = """#version 300 es
precision highp float;
in vec4 vC; in vec3 vN; in vec3 vP; uniform vec3 uCam; out vec4 o;
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.56,.64,.74); return mix(c,f,smoothstep(90.,700.,d)*.8); }
void main(){ vec3 N=normalize(vN); if(!gl_FrontFacing) N=-N; vec3 V=normalize(uCam-vP);
 float met=vC.a; vec3 al=vC.rgb*vC.rgb;
 vec3 L1=normalize(vec3(.5,.9,.7)), L2=normalize(vec3(-.8,.3,-.5));
 float d1=max(dot(N,L1),0.), d2=max(dot(N,L2),0.);
 vec3 hemi=mix(vec3(.38,.36,.35),vec3(.62,.64,.70),N.y*.5+.5);
 vec3 c=al*(1.-.45*met)*(hemi+vec3(1.0,.96,.9)*d1*.75+vec3(.35,.42,.6)*d2*.3);
 vec3 H=normalize(L1+V); float sp=pow(max(dot(N,H),0.),36.);
 c+=mix(vec3(.4),al*1.6,met)*sp*d1*(.06+.9*met);
 vec3 R=reflect(-V,N); c+=al*met*mix(vec3(.10,.09,.09),vec3(.38,.44,.55),R.y*.5+.5)*.9;
 c=fogc(c,vP,uCam);
 o=vec4(pow(c,vec3(1./2.2)),1.); }"""

    /** Guerreiro com textura (atlas assado): atributo 2 = UV; o metal vem no 4o componente da normal. */
    const val SKINT_VS = """#version 300 es
precision highp float; precision highp int;
layout(location=0) in vec4 aPos; layout(location=1) in vec4 aNrm; layout(location=2) in vec2 aUV; layout(location=3) in uvec4 aJ; layout(location=4) in vec4 aW;
uniform sampler2D uBones; uniform vec3 uCtr; uniform vec3 uExt; uniform mat4 uVP; uniform mat4 uM;
out vec2 vUV; out float vMet; out vec3 vN; out vec3 vP;
mat4 B(uint i){int r=int(i);return mat4(texelFetch(uBones,ivec2(0,r),0),texelFetch(uBones,ivec2(1,r),0),texelFetch(uBones,ivec2(2,r),0),texelFetch(uBones,ivec2(3,r),0));}
void main(){ vec4 p=vec4(uCtr+aPos.xyz*uExt,1.); mat4 m=B(aJ.x)*aW.x+B(aJ.y)*aW.y+B(aJ.z)*aW.z+B(aJ.w)*aW.w;
 vec4 w=uM*(m*p); vP=w.xyz; vN=mat3(uM)*(mat3(m)*normalize(aNrm.xyz)); vMet=aNrm.w; vUV=aUV; gl_Position=uVP*w; }"""

    /** Guerreiro com textura do modelo original: cor (atlas) + normal map (RG) e metal (B) no segundo atlas, mesma iluminação de antes. */
    const val CHAR_TFS = """#version 300 es
precision highp float;
in vec2 vUV; in float vMet; in vec3 vN; in vec3 vP; uniform sampler2D uTex; uniform sampler2D uNrm; uniform vec3 uCam; out vec4 o;
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.56,.64,.74); return mix(c,f,smoothstep(90.,700.,d)*.8); }
void main(){
 vec3 Ng=normalize(vN); vec3 V=normalize(uCam-vP);
 vec4 nm=texture(uNrm,vUV);
 vec2 xy=nm.rg*2.-1.; vec3 tn=vec3(xy,sqrt(max(1.-dot(xy,xy),0.)));
 vec2 uvn=vec2(vUV.x,vUV.y*2.);
 vec3 dp1=dFdx(vP),dp2=dFdy(vP); vec2 du1=dFdx(uvn),du2=dFdy(uvn);
 vec3 p2=cross(dp2,Ng), p1=cross(Ng,dp1);
 vec3 T=p2*du1.x+p1*du2.x; vec3 Bv=p2*du1.y+p1*du2.y;
 float iv=inversesqrt(max(max(dot(T,T),dot(Bv,Bv)),1e-20));
 vec3 N=normalize(T*iv*tn.x-Bv*iv*tn.y+Ng*tn.z);
 if(!gl_FrontFacing) N=-N;
 float met=clamp(vMet*nm.b,0.,1.); vec3 tc=texture(uTex,vUV).rgb; vec3 al=tc*tc;
 vec3 L1=normalize(vec3(.5,.9,.7)), L2=normalize(vec3(-.8,.3,-.5));
 float d1=max(dot(N,L1),0.), d2=max(dot(N,L2),0.);
 vec3 hemi=mix(vec3(.38,.36,.35),vec3(.62,.64,.70),N.y*.5+.5);
 vec3 c=al*(1.-.45*met)*(hemi+vec3(1.0,.96,.9)*d1*.75+vec3(.35,.42,.6)*d2*.3);
 vec3 H=normalize(L1+V); float sp=pow(max(dot(N,H),0.),36.);
 c+=mix(vec3(.4),al*1.6,met)*sp*d1*(.06+.9*met);
 vec3 R=reflect(-V,N); c+=al*met*mix(vec3(.10,.09,.09),vec3(.38,.44,.55),R.y*.5+.5)*.9;
 c=fogc(c,vP,uCam);
 o=vec4(pow(c,vec3(1./2.2)),1.); }"""

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
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.56,.64,.74); return mix(c,f,smoothstep(90.,700.,d)*.8); }
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

    const val MAP_VS = """#version 300 es
precision highp float;
layout(location=0) in vec3 aP; layout(location=1) in vec4 aN; layout(location=2) in vec2 aUV;
uniform mat4 uVP; out vec2 vUV; out vec3 vN; out vec3 vP;
void main(){ vP=aP; vN=aN.xyz; vUV=aUV; gl_Position=uVP*vec4(aP,1.); }"""

    /** Pintura por projeção no mundo: o formato do mapa não muda, só a textura (pedra/terra/rocha do Z!MOBA). */
    const val MAP_FS = """#version 300 es
precision highp float;
in vec2 vUV; in vec3 vN; in vec3 vP; uniform sampler2D uT0; uniform sampler2D uT1; uniform vec3 uCam; uniform vec4 uSh[3];
uniform float uMask; uniform float uOrig; uniform vec2 uK; uniform vec3 uTint; uniform float uSoft; out vec4 o;
""" + LIGHT + """
void main(){
  vec3 N=normalize(vN); if(!gl_FrontFacing) N=-N; vec3 V=normalize(uCam-vP);
  vec3 rgb;
  if(uOrig>.5){ vec4 t=texture(uT0,vUV); if(uMask>.5 && t.a<.5) discard; rgb=t.rgb; }
  else if(uSoft>0.){
    vec3 w=pow(abs(N),vec3(uSoft)); w/=w.x+w.y+w.z;
    vec3 c3=texture(uT0,vP.zy*uK.y).rgb*w.x+texture(uT0,vP.xz*uK.y).rgb*w.y+texture(uT0,vP.xy*uK.y).rgb*w.z;
    float lum2=dot(texture(uT0,vP.xz*uK.y*.113+vec2(.2,.6)).rgb,vec3(.333));
    rgb=c3*(.7+.8*lum2)*uTint;
  } else {
    vec2 pt=vP.xz*uK.x;
    vec3 top=texture(uT0,pt).rgb;
    float lum=dot(texture(uT0,pt*.137+vec2(.37,.11)).rgb,vec3(.333));
    top*=.62+.95*lum;
    float wx=pow(abs(N.x),6.), wz=pow(abs(N.z),6.);
    vec3 sd=(texture(uT1,vP.zy*uK.y).rgb*wx+texture(uT1,vP.xy*uK.y).rgb*wz)/(wx+wz+1e-4);
    float tw=smoothstep(.45,.8,N.y);
    rgb=mix(sd,top,tw)*uTint;
  }
  float sh=1.;
  if(N.y>.6 && abs(vP.y)<.7){ for(int i=0;i<3;i++){ vec4 s=uSh[i]; if(s.z<=0.) continue; float d=length(vP.xz-s.xy)/s.z; sh*=1.-s.w*smoothstep(1.,.15,d); } }
  vec3 al=pow(rgb,vec3(2.2));
  vec3 c=light(al,N,V,0.,1.)*sh;
  c=mix(c,vec3(.56,.64,.74)*.9,smoothstep(-4.,-70.,vP.y)*.55);
  c=fogc(c,vP,uCam);
  c=c/(1.+c*.12)*1.08;
  o=vec4(pow(c,vec3(1./2.2)),1.); }"""

    const val SKY_VS = """#version 300 es
precision highp float; layout(location=0) in vec2 aP; out vec2 vP; void main(){ vP=aP; gl_Position=vec4(aP,.9999,1.); }"""

    const val SKY_FS = """#version 300 es
precision highp float; in vec2 vP; uniform mat4 uIVP; uniform vec3 uCam; out vec4 o;
float h21(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}
void main(){ vec4 w=uIVP*vec4(vP,1.,1.); vec3 d=normalize(w.xyz/w.w-uCam);
  float y=d.y; vec3 hor=vec3(.56,.64,.74), zen=vec3(.16,.24,.40), warm=vec3(.95,.62,.38);
  vec3 c=mix(hor,zen,smoothstep(0.,.55,y));
  vec3 sd=normalize(vec3(.55,.25,.45)); float sun=max(dot(d,sd),0.);
  c+=warm*pow(sun,6.)*.45+vec3(1.,.9,.7)*pow(sun,300.)*1.5;
  vec2 cp=d.xz/max(y+.15,.05); float cl=smoothstep(.55,.9,vn(cp*1.3)*.6+vn(cp*3.1)*.4)*smoothstep(0.,.25,y);
  c=mix(c,vec3(.85,.80,.78),cl*.35);
  c=mix(c,hor*.8,smoothstep(.0,-.2,y));
  o=vec4(c,1.); }"""
}

