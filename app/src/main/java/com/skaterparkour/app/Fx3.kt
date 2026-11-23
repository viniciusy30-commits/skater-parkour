package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import kotlin.math.*

/**
 * Porte nativo do sistema de efeitos FX3 do visualizador HTML (mesmo shader, mesmas funções e números).
 * Coordenadas em metros, no mundo.
 */
typealias V = FloatArray

fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
fun vadd(a: V, b: V) = v3(a[0] + b[0], a[1] + b[1], a[2] + b[2])
fun vsub(a: V, b: V) = v3(a[0] - b[0], a[1] - b[1], a[2] - b[2])
fun vmul(a: V, s: Float) = v3(a[0] * s, a[1] * s, a[2] * s)
fun vdot(a: V, b: V) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
fun vlen(a: V) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
fun vcross(a: V, b: V) = v3(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
fun vnorm(a: V): V { val l = vlen(a).let { if (it == 0f) 1f else it }; return v3(a[0] / l, a[1] / l, a[2] / l) }
fun sstep(a: Float, b: Float, x: Float): Float { val t = clampf((x - a) / (b - a), 0f, 1f); return t * t * (3 - 2 * t) }
const val PIf = PI.toFloat()

class Fx3(ctx: Context) {
    // ---------- paletas ----------
    class Pal(val c: V, val c2: V)
    val PALS = mapOf(
        "gold" to Pal(v3(1f, .52f, .12f), v3(1f, .78f, .3f)),
        "steel" to Pal(v3(.55f, .8f, 1f), v3(1f, .5f, .15f)),
        "earth" to Pal(v3(1f, .42f, .1f), v3(1f, .7f, .25f)),
        "ult" to Pal(v3(1f, .1f, .05f), v3(1f, .2f, .1f))
    )
    var pal = PALS["gold"]!!
    fun setPal(n: String) { pal = PALS[n] ?: PALS["gold"]!! }
    fun MIDC() = pal.c

    // contexto (preenchido pelo jogo)
    var root = v3(0f, 0f, 0f)
    var yaw = 0f

    // câmera
    val camR = v3(1f, 0f, 0f); val camU = v3(0f, 1f, 0f); val eye = v3(0f, 0f, 0f)

    // ---------- buffers ----------
    class Buf(maxV: Int) { val a = FloatArray(13 * maxV); var n = 0; val max = maxV }
    val solid = Buf(4000); val alpha = Buf(40000); val add = Buf(60000)

    private var prog = 0; private var vao = 0; private var vbo = 0
    private var uVP = 0; private var uTime = 0; private var uEye = 0; private var uAtl = 0; private var uAG = 0
    private val fb = Gl.floats(13 * 60000)
    var atl = 0
    var time = 0f

    // ---------- efeitos ----------
    open inner class Eff {
        var age = 0f; var delay = 0f; var life = 1f
        open fun upd(dt: Float) {}
        open fun draw(p: Float) {}
    }
    private val fxs = ArrayList<Eff>()
    private val pend = ArrayList<Eff>()
    fun addE(e: Eff): Eff { pend.add(e); return e }
    fun alive() = fxs.isNotEmpty() || pend.isNotEmpty()
    fun reset() { fxs.clear(); pend.clear() }

    init {
        prog = Gl.program(VS, FS)
        uVP = glGetUniformLocation(prog, "uVP"); uTime = glGetUniformLocation(prog, "uTime"); uEye = glGetUniformLocation(prog, "uEye")
        uAtl = glGetUniformLocation(prog, "uAtl"); uAG = glGetUniformLocation(prog, "uAG")
        vao = Gl.gen(::glGenVertexArrays); vbo = Gl.gen(::glGenBuffers)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glBufferData(GL_ARRAY_BUFFER, 13 * 60000 * 4, null, GL_DYNAMIC_DRAW)
        val offs = intArrayOf(0, 3, 5, 9); val ns = intArrayOf(3, 2, 4, 4)
        for (i in 0 until 4) { glEnableVertexAttribArray(i); glVertexAttribPointer(i, ns[i], GL_FLOAT, false, 52, offs[i] * 4) }
        glBindVertexArray(0)
        atl = Gl.texture(ctx, "w/fx.jpg", mip = true)
    }

    // ---------- primitivas (sem alocação) ----------
    private fun vtx(B: Buf, x: Float, y: Float, z: Float, u: Float, v: Float, c: V, r: V) {
        if (B.n >= B.max) return
        val a = B.a; val i = B.n * 13
        a[i] = x; a[i + 1] = y; a[i + 2] = z; a[i + 3] = u; a[i + 4] = v
        a[i + 5] = c[0]; a[i + 6] = c[1]; a[i + 7] = c[2]; a[i + 8] = c[3]
        a[i + 9] = r[0]; a[i + 10] = r[1]; a[i + 11] = r[2]; a[i + 12] = r[3]
        B.n++
    }

    private val q = FloatArray(12)
    private val UVQ = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
    /** quad com cantos em q[0..11] */
    private fun quadQ(B: Buf, uv: FloatArray, c: V, r: V) {
        vtx(B, q[0], q[1], q[2], uv[0], uv[1], c, r); vtx(B, q[3], q[4], q[5], uv[2], uv[3], c, r); vtx(B, q[6], q[7], q[8], uv[4], uv[5], c, r)
        vtx(B, q[0], q[1], q[2], uv[0], uv[1], c, r); vtx(B, q[6], q[7], q[8], uv[4], uv[5], c, r); vtx(B, q[9], q[10], q[11], uv[6], uv[7], c, r)
    }

    /** centro c, eixos A (meia-largura) e Bv (meia-altura): cantos c-A-B, c+A-B, c+A+B, c-A+B */
    private fun quadAB(B: Buf, cx: Float, cy: Float, cz: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, uv: FloatArray, c: V, r: V) {
        q[0] = cx - ax - bx; q[1] = cy - ay - by; q[2] = cz - az - bz
        q[3] = cx + ax - bx; q[4] = cy + ay - by; q[5] = cz + az - bz
        q[6] = cx + ax + bx; q[7] = cy + ay + by; q[8] = cz + az + bz
        q[9] = cx - ax + bx; q[10] = cy - ay + by; q[11] = cz - az + bz
        quadQ(B, uv, c, r)
    }

    fun quad4(B: Buf, p0: V, p1: V, p2: V, p3: V, uv: FloatArray, c: V, r: V) {
        q[0] = p0[0]; q[1] = p0[1]; q[2] = p0[2]; q[3] = p1[0]; q[4] = p1[1]; q[5] = p1[2]
        q[6] = p2[0]; q[7] = p2[1]; q[8] = p2[2]; q[9] = p3[0]; q[10] = p3[1]; q[11] = p3[2]
        quadQ(B, uv, c, r)
    }

    fun billboard(B: Buf, c: V, w: Float, h: Float, rot: Float, col: V, pr: V) {
        val cs = cos(rot); val sn = sin(rot)
        val rx = camR[0] * cs + camU[0] * sn; val ry = camR[1] * cs + camU[1] * sn; val rz = camR[2] * cs + camU[2] * sn
        val ux = camU[0] * cs - camR[0] * sn; val uy = camU[1] * cs - camR[1] * sn; val uz = camU[2] * cs - camR[2] * sn
        quadAB(B, c[0], c[1], c[2], rx * w, ry * w, rz * w, ux * h, uy * h, uz * h, UVQ, col, pr)
    }

    fun streak(B: Buf, c: V, dir: V, len: Float, wid: Float, col: V, pr: V) {
        var l = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]); if (l == 0f) l = 1f
        val ax = dir[0] / l; val ay = dir[1] / l; val az = dir[2] / l
        var vx = c[0] - eye[0]; var vy = c[1] - eye[1]; var vz = c[2] - eye[2]
        var vl = sqrt(vx * vx + vy * vy + vz * vz); if (vl == 0f) vl = 1f; vx /= vl; vy /= vl; vz /= vl
        var sx = ay * vz - az * vy; var sy = az * vx - ax * vz; var sz = ax * vy - ay * vx
        var sl = sqrt(sx * sx + sy * sy + sz * sz); if (sl == 0f) sl = 1f
        sx = sx / sl * wid; sy = sy / sl * wid; sz = sz / sl * wid
        // cantos: c-A-sd, c+A-sd, c+A+sd, c-A+sd
        quadAB(B, c[0], c[1], c[2], ax * len, ay * len, az * len, sx, sy, sz, UVQ, col, pr)
    }

    fun ground(B: Buf, c: V, R: Float, col: V, pr: V, y0: Float = .02f) {
        val y = y0
        q[0] = c[0] - R; q[1] = y; q[2] = c[2] - R; q[3] = c[0] + R; q[4] = y; q[5] = c[2] - R
        q[6] = c[0] + R; q[7] = y; q[8] = c[2] + R; q[9] = c[0] - R; q[10] = y; q[11] = c[2] + R
        quadQ(B, UVQ, col, pr)
    }

    private val uvC = FloatArray(8)
    fun cylinder(B: Buf, c: V, r0: Float, r1: Float, h: Float, seg: Int, col: V, pr: V) {
        val rows = 5
        for (j in 0 until rows) {
            val w0 = j.toFloat() / rows; val w1 = (j + 1f) / rows; val ra = lerpf(r0, r1, w0); val rb = lerpf(r0, r1, w1)
            for (i in 0 until seg) {
                val a0 = i.toFloat() / seg * 2 * PIf; val a1 = (i + 1f) / seg * 2 * PIf
                q[0] = c[0] + cos(a0) * ra; q[1] = c[1] + w0 * h; q[2] = c[2] + sin(a0) * ra
                q[3] = c[0] + cos(a1) * ra; q[4] = c[1] + w0 * h; q[5] = c[2] + sin(a1) * ra
                q[6] = c[0] + cos(a1) * rb; q[7] = c[1] + w1 * h; q[8] = c[2] + sin(a1) * rb
                q[9] = c[0] + cos(a0) * rb; q[10] = c[1] + w1 * h; q[11] = c[2] + sin(a0) * rb
                uvC[0] = i.toFloat() / seg; uvC[1] = w0; uvC[2] = (i + 1f) / seg; uvC[3] = w0; uvC[4] = (i + 1f) / seg; uvC[5] = w1; uvC[6] = i.toFloat() / seg; uvC[7] = w1
                quadQ(B, uvC, col, pr)
            }
        }
    }

    // ---------- partículas procedurais (part) ----------
    inner class Part(p: V) : Eff() {
        val p = p.copyOf(); var v = v3(0f, 0f, 0f); var g = 0f; var dr = 0f; var rot = rnd(0f, 6.28f); var rotv = 0f; val seed = Math.random().toFloat()
        var layer = add; var mode = 0 /*0 bb, 1 st, 2 gd*/; var k = 0f; var size = 1f; var size1 = Float.NaN
        var env: (Float) -> Float = { q -> min(1f, q * 10) * (1 - q * q) }
        var A = 1f; var stretch = .05f; var wid = Float.NaN; var col = floatArrayOf(1f, 1f, 1f, 1f); var asp = 1f; var lay = 0f
        private val cc = FloatArray(4); private val pr = FloatArray(4)
        override fun upd(dt: Float) {
            val d = exp(-dr * dt); v[1] -= g * dt; v[0] *= d; v[1] *= d; v[2] *= d
            for (i in 0 until 3) p[i] += v[i] * dt; rot += rotv * dt
        }
        override fun draw(q: Float) {
            cc[0] = col[0]; cc[1] = col[1]; cc[2] = col[2]; cc[3] = col[3] * A * env(q)
            pr[0] = q; pr[1] = seed; pr[2] = lay; pr[3] = k
            val s1 = if (size1.isNaN()) size else size1
            val s = lerpf(size, s1, q)
            when (mode) {
                0 -> billboard(layer, p, s, s * asp, rot, cc, pr)
                1 -> { val sp = vlen(v); streak(layer, p, if (sp > .01f) v else v3(0f, 1f, 0f), max(s, sp * stretch), if (wid.isNaN()) s * .15f else wid, cc, pr) }
                else -> ground(layer, p, s, cc, pr, .012f + Math.random().toFloat() * .0005f)
            }
        }
    }

    fun part(p: V, life: Float, k: Int, size: Float, col: V, f: Part.() -> Unit = {}): Part {
        val o = Part(p); o.life = life; o.k = k.toFloat(); o.size = size; o.col = col; o.f(); addE(o); return o
    }

    // ---------- sprites do atlas (spr) ----------
    private var SLT: ((V) -> V)? = null
    val easeO: (Float) -> Float = { q -> 1 - (1 - q).pow(3) }

    inner class Spr(p: V) : Eff() {
        var p = p.copyOf(); var v = v3(0f, 0f, 0f); var g = 0f; var dr = 0f; var rot = rnd(0f, 6.28f); var rotv = 0f
        var nf = 1; var m = 0; var col = floatArrayOf(1f, 1f, 1f, 1f); var s = 1f; var s1 = Float.NaN; var c0 = 0
        var env: (Float) -> Float = { q -> min(1f, q * 12) * (1 - q * q) }
        var ease: ((Float) -> Float)? = null
        var gd = false; var gy = .02f; var st = false; var dir: V? = null; var asp = Float.NaN
        var at: (Spr.() -> V)? = null
        var tick: ((Spr, Float) -> Unit)? = null
        private val cc = FloatArray(4); private val pr = FloatArray(4)
        override fun upd(dt: Float) {
            val d = exp(-dr * dt); v[1] -= g * dt
            for (i in 0 until 3) { v[i] *= d; p[i] += v[i] * dt }
            rot += rotv * dt; tick?.invoke(this, dt)
        }
        override fun draw(q: Float) {
            if (q >= 1f) return
            val cell = c0 + if (nf > 1) min(nf - 1, floor(q * nf).toInt()) else 0
            val sEnd = if (s1.isNaN()) s else s1
            val ss = lerpf(s, sEnd, ease?.invoke(q) ?: q)
            val a = col[3] * env(q); if (a < .003f) return
            cc[0] = col[0]; cc[1] = col[1]; cc[2] = col[2]; cc[3] = a
            pr[0] = cell.toFloat(); pr[1] = 0f; pr[2] = m.toFloat(); pr[3] = 16f
            val L = if (m == 1) alpha else add
            val pp = at?.invoke(this) ?: p
            if (gd) {
                val ax = cos(rot) * ss; val az = sin(rot) * ss; val bx = -sin(rot) * ss; val bz = cos(rot) * ss
                quadAB(L, pp[0], gy, pp[2], ax, 0f, az, bx, 0f, bz, UVQ, cc, pr)
            } else if (st) {
                val d = dir ?: if (vlen(v) > .01f) v else v3(0f, 1f, 0f)
                streak(L, pp, d, ss * (if (asp.isNaN()) 3f else asp), ss, cc, pr)
            } else billboard(L, pp, ss, ss * (if (asp.isNaN()) 1f else asp), rot, cc, pr)
        }
    }

    fun spr(p: V, life: Float, c0: Int, s: Float, col: V, f: Spr.() -> Unit = {}): Spr {
        val o = Spr(p); o.life = life; o.c0 = c0; o.s = s; o.col = col; o.f()
        SLT?.let { o.col = it(o.col) }
        addE(o); return o
    }

    // células
    companion object {
        const val SWORD = 0; const val CRIT = 24; const val EXPL = 48; const val SCORCH = 72; const val TWIRL = 75; const val SMOKE = 78
        const val FIRE = 83; const val FLAME = 85; const val MUZZLE = 89; const val STAR = 93; const val MAGIC3 = 96; const val RING = 97; const val GLOW = 98
        const val LRING = 99; const val SPARK = 100; const val FLARE = 102; const val BURST = 103; const val MOTION = 104; const val AURA = 105; const val WISP = 106
        const val T87 = 107; const val T63 = 108; const val F31 = 109; const val F21 = 110; const val SMOKE24 = 111; const val GLOW2 = 112; const val GM = 113

        private const val VS = """#version 300 es
layout(location=0) in vec3 aP;layout(location=1) in vec2 aU;layout(location=2) in vec4 aC;layout(location=3) in vec4 aR;
uniform mat4 uVP;out vec2 vUV;out vec4 vCol;out vec4 vPrm;out vec3 vW;
void main(){vUV=aU;vCol=aC;vPrm=aR;vW=aP;gl_Position=uVP*vec4(aP,1.);}"""
        private const val FS = """#version 300 es
precision highp float;
in vec2 vUV;in vec4 vCol;in vec4 vPrm;in vec3 vW;out vec4 o;
uniform float uTime;uniform vec3 uEye;uniform sampler2D uAtl;uniform vec2 uAG;
float ss(float a,float b,float x){float t=clamp((x-a)/(b-a),0.,1.);return t*t*(3.-2.*t);}
float h21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}
float fbm(vec2 p){float a=.5,s=0.;for(int i=0;i<4;i++){s+=a*vn(p);p=p*2.03+17.1;a*=.5;}return s;}
void main(){
 int k=int(vPrm.w+.5);float p=vPrm.x,sd=vPrm.y,lay=vPrm.z;vec2 uv=vUV;vec3 c=vCol.rgb;float A=vCol.a;
 vec3 hot=mix(vec3(1.,.97,.88),c,.2);
 vec4 r=vec4(0.);
 if(k==0){float d=length(uv);float g=exp(-d*d*4.5)*ss(1.,.75,d);r=vec4(mix(c,hot,g*g*.7),A*g);}
 else if(k==2){
  float d=length(uv);float ang=atan(uv.y,uv.x);
  float e=1.-pow(1.-p,2.6);float front=.1+.88*e;front+=(vn(vec2(ang*5.+sd*10.,sd))-.5)*.08*(1.-p*.5);
  float th=mix(.11,.025,p);float dd=(d-front)/th;float band=exp(-dd*dd);
  band*=.65+.7*vn(vec2(ang*16.,sd*7.+p*3.));
  float trail=ss(front-.6,front,d)*(1.-step(front,d))*.32;
  float fade=1.-ss(.5,1.,p);
  vec3 col=mix(c,hot,clamp(band*band*1.2,0.,1.));
  r=vec4(col,(band*1.5+trail)*fade*A*ss(1.,.88,d));}
 else if(k==4){
  float x=uv.x,y=uv.y;float lx=1.-pow(abs(x),2.2);float wy=exp(-y*y*5.5);float a=lx*wy;
  float hh=exp(-y*y*30.)*lx;a*=.45+.55*ss(-1.,1.,x);
  r=vec4(mix(c,hot,hh*.9),a*A);}
 else if(k==5){
  float d=length(uv);float n=fbm(uv*1.8+sd*13.+vec2(0.,p*.6));float m=n*(1.-d*.85);
  float a=ss(p*.8,p*.8+.25,m)*(1.-p)*A*ss(1.,.5,d);
  r=vec4(c*(.55+.7*n),a);}
 else if(k==9){
  float u=uv.x,w=uv.y;
  float n=vn(vec2(u*46.+sd*20.+uTime*3.,w*1.6-p*3.));float n2=vn(vec2(u*14.-uTime*1.5,w*4.+sd));
  float band=pow(max(1.-w,0.),1.3);float bottom=exp(-w*8.);
  float a=(band*(.25+.9*n*n2*1.6)+bottom*.9)*A;
  r=vec4(mix(c,hot,bottom*.6+n*.2),a*ss(1.,.92,w));}
 else if(k==16){float cl=floor(p+.5);vec2 t=clamp(uv*.5+.5,0.,1.);vec2 ce=vec2(mod(cl,uAG.x),floor(cl/uAG.x));
  vec2 tc=(ce+.008+vec2(t.x,1.-t.y)*.984)/uAG;vec3 tx=texture(uAtl,tc).rgb;float L=max(tx.r,max(tx.g,tx.b));
  if(lay<.5)r=vec4(tx*c,A);
  else if(lay<1.5)r=vec4(c*(.75+.4*L),clamp(L*1.15,0.,1.)*A);
  else r=vec4(mix(c*L,vec3(L),L*L*.8)*1.35,A);}
 o=r;}"""
    }

    fun later(d: Float, fn: () -> Unit) {
        addE(object : Eff() {
            var done = false
            init { life = d + .05f }
            override fun upd(dt: Float) { if (!done && age >= d) { done = true; fn() } }
        })
    }

    // ===================== efeitos usados pelo kit =====================

    /** v75: impacto do golpe (risco de luz, faíscas em cone, brasas, fumaça) */
    fun impactFx(h: V, s0: Float, dir: V?) {
        val s = if (s0 == 0f) 1f else s0
        val c = MIDC(); val c2 = pal.c2
        val d = if (dir != null) vnorm(v3(dir[0], 0f, dir[2])) else vnorm(vsub(h, v3(root[0], h[1], root[2])))
        val sd = vnorm(vcross(d, v3(0f, 1f, 0f)))
        addE(object : Eff() {
            init { life = .16f }
            val cA = FloatArray(4); val cB = FloatArray(4); val pr = floatArrayOf(0f, 0f, 0f, 4f)
            override fun draw(p: Float) {
                val qq = age / life; if (qq > 1) return
                val L = (1.1f + 1.4f * qq) * s; val w = .09f * s * (1 - qq)
                cA[0] = c[0]; cA[1] = c[1]; cA[2] = c[2]; cA[3] = .7f * (1 - qq)
                cB[0] = 1f; cB[1] = .97f; cB[2] = .9f; cB[3] = 1 - qq
                streak(add, h, sd, L, w * 2.4f, cA, pr); streak(add, h, sd, L * .8f, w, cB, pr)
            }
        })
        val n = (16 * s).roundToInt()
        for (i in 0 until n) {
            val a = rnd(-1.1f, 1.1f); val e = rnd(-.3f, .9f); val sp = rnd(5f, 13f) * sqrt(s)
            val v = vadd(vadd(vmul(d, cos(a) * sp), vmul(sd, sin(a) * sp)), v3(0f, e * sp * .7f, 0f))
            part(h, rnd(.25f, .55f), 4, .32f, if (i % 3 != 0) floatArrayOf(c2[0], c2[1], c2[2], 1f) else floatArrayOf(1f, .95f, .85f, 1f)) {
                this.v = v; g = 9f; dr = 2.6f; mode = 1; wid = .028f; stretch = .05f
            }
        }
        for (i in 0 until (7 * s).roundToInt()) {
            part(vadd(h, v3(rnd(-.2f, .2f), rnd(-.2f, .2f), rnd(-.2f, .2f))), rnd(.7f, 1.4f), 0, rnd(.05f, .1f), floatArrayOf(1f, .5f, .15f, 1f)) {
                v = v3(rnd(-1.4f, 1.4f) + d[0] * 1.5f, rnd(.6f, 2.6f), rnd(-1.4f, 1.4f) + d[2] * 1.5f); g = -.6f; dr = 1.4f
                env = { q -> (1 - q) * (.6f + .4f * sin(q * 40)) }
            }
        }
        for (i in 0 until 3) {
            part(vadd(h, vmul(d, .2f)), rnd(.6f, 1f), 5, rnd(.35f, .5f) * s, floatArrayOf(.18f, .15f, .14f, .45f)) {
                v = v3(d[0] * rnd(.5f, 1.5f) + rnd(-.4f, .4f), rnd(.2f, .8f), d[2] * rnd(.5f, 1.5f) + rnd(-.4f, .4f)); dr = 1.8f
                layer = alpha; size1 = rnd(.9f, 1.3f) * s; env = { 1f }
            }
        }
    }

    fun dustFx(c: V, s: Float, n: Int) {
        for (i in 0 until n) {
            val a = rnd(0f, 6.28f); val r = rnd(.2f, .6f) * s
            part(v3(c[0] + cos(a) * r, .12f, c[2] + sin(a) * r), rnd(.9f, 1.5f), 5, rnd(.45f, .8f) * s, floatArrayOf(.5f, .42f, .34f, .8f)) {
                v = v3(cos(a) * rnd(1.2f, 2.8f) * s, rnd(.3f, 1f), sin(a) * rnd(1.2f, 2.8f) * s); dr = 1.6f; layer = alpha; size1 = rnd(1f, 1.6f) * s; env = { 1f }
            }
        }
    }

    fun scorchS(c: V, R: Float, n: Int, life: Float) {
        for (i in 0 until n) {
            val a = rnd(0f, 6.28f); val r = if (i != 0) rnd(.2f, .6f) * R else 0f
            spr(v3(c[0] + cos(a) * r, 0f, c[2] + sin(a) * r), life, SCORCH + (i % 3), R * (if (i != 0) rnd(.45f, .7f) else 1f), floatArrayOf(.03f, .02f, .02f, .95f)) {
                gd = true; gy = .013f + i * .0006f; m = 1; rot = rnd(0f, 6.28f); env = { q -> min(1f, q * 30) * (1 - sstep(.7f, 1f, q)) }
            }
        }
        spr(v3(c[0], 0f, c[2]), 1.6f, SCORCH, R * .9f, if (pal === PALS["ult"]) floatArrayOf(1f, .06f, .03f, .9f) else floatArrayOf(1f, .35f, .08f, .9f)) {
            gd = true; gy = .022f; m = 0; env = { q -> 1 - sstep(0f, 1f, q) }
        }
    }

    /** v79: impacto da Ultimate no chão */
    fun ultSlam(c: V, P0: Float) {
        val P = if (P0 == 0f) 1f else P0
        val gp = v3(c[0], 0f, c[2]); val mP = min(P, 1.6f)
        spr(gp, 1.1f * P, GM, 1.2f * P, floatArrayOf(1f, .05f, .02f, .7f)) { gd = true; gy = .026f; s1 = 2.2f * P; ease = easeO; env = { q -> 1 - sstep(.2f, 1f, q) } }
        spr(gp, .5f, GM, .5f * P, floatArrayOf(1f, .25f, .18f, .5f)) { gd = true; gy = .03f; s1 = .9f * P; env = { q -> 1 - q } }
        scorchS(c, 1.1f * P, 3, 5f)
        spr(gp, .55f, RING, .3f, floatArrayOf(1f, .08f, .04f, 1f)) { gd = true; gy = .04f; s1 = 3 * P; ease = easeO; rot = rnd(0f, 6f); env = { q -> 1 - q * q } }
        spr(gp, .8f, LRING, .3f, floatArrayOf(1f, .15f, .08f, .8f)) { gd = true; gy = .045f; delay = .06f; s1 = 2.4f * P; ease = easeO; rotv = 3f; env = { q -> 1 - q } }
        spr(gp, .45f, TWIRL + 2, .6f * P, floatArrayOf(1f, .12f, .06f, .9f)) { gd = true; gy = .05f; s1 = 1.8f * P; rotv = -9f; env = { q -> 1 - q } }
        val nr = (10 * mP).roundToInt()
        for (i in 0 until nr) {
            val a = i.toFloat() / nr * 6.283f + rnd(-.2f, .2f); val sp = rnd(5f, 9f) * P
            spr(v3(c[0] + cos(a) * .3f, .06f, c[2] + sin(a) * .3f), rnd(.3f, .45f), T63, .07f * P, floatArrayOf(1f, .1f, .06f, 1f)) {
                v = v3(cos(a) * sp, 0f, sin(a) * sp); dr = 4f; st = true; m = 2; asp = 6f; env = { q -> 1 - q }
            }
        }
        val ns = if (SLT != null) 0 else (7 * mP).roundToInt()
        for (i in 0 until ns) {
            val a = rnd(0f, 6.28f); val r = rnd(.3f, 1.3f) * P
            spr(v3(c[0] + cos(a) * r, .2f, c[2] + sin(a) * r), rnd(.3f, .45f), T63, rnd(.07f, .12f) * P, floatArrayOf(1f, .12f, .08f, .65f)) {
                v = v3(0f, rnd(6f, 10f) * sqrt(P), 0f); dr = 3f; delay = rnd(0f, .08f); st = true; m = 2; asp = rnd(6f, 9f); env = { q -> sin(PIf * min(1f, q * 1.4f)) }
            }
        }
        if (SLT == null) for (i in 0 until (3 * mP).roundToInt()) {
            val a = rnd(0f, 6.28f); val r = rnd(.2f, .8f) * P
            spr(v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), rnd(.4f, .6f), T87, rnd(.2f, .3f) * P, floatArrayOf(1f, .08f, .06f, .6f)) {
                v = v3(cos(a) * .8f, rnd(3f, 5f), sin(a) * .8f); dr = 1.5f; st = true; m = 2; asp = 2.6f; env = { q -> sin(PIf * q) }
            }
        }
        for (i in 0 until (10 * mP).roundToInt()) {
            val a = rnd(0f, 6.28f); val sp = rnd(3f, 6f) * P
            spr(v3(c[0], .25f, c[2]), rnd(.4f, .7f), F31, rnd(.15f, .22f), floatArrayOf(1f, .3f, .26f, 1f)) {
                v = v3(cos(a) * sp, rnd(2f, 5f), sin(a) * sp); g = 7f; dr = 1.2f; st = true; m = 2; asp = 1.5f; env = { q -> 1 - q }
            }
        }
        for (i in 0 until (24 * P).roundToInt()) {
            val a = rnd(0f, 6.28f); val sp = rnd(1f, 4f) * P
            spr(v3(c[0] + cos(a) * .4f, .15f, c[2] + sin(a) * .4f), rnd(.6f, 1.2f), GLOW, rnd(.05f, .09f), floatArrayOf(1f, .05f, .02f, 1f)) {
                v = v3(cos(a) * sp, rnd(1.5f, 4f), sin(a) * sp); g = 2f; dr = 1f; env = { q -> 1 - q }
            }
        }
        for (i in 0 until (7 * P).roundToInt()) {
            val a = i / 7f * 6.283f
            spr(v3(c[0] + cos(a) * .5f, .3f, c[2] + sin(a) * .5f), rnd(.8f, 1.1f), SMOKE24, .6f * P, floatArrayOf(.45f, 0f, 0f, .6f)) {
                v = v3(cos(a) * 2.4f * P, .3f, sin(a) * 2.4f * P); dr = 2f; m = 1; s1 = 1.3f * P; ease = easeO; rotv = rnd(-1f, 1f); env = { q -> sin(PIf * q) }
            }
        }
        if (P > 1.9f) {
            spr(v3(c[0], .05f, c[2]), .7f, MOTION, 1.6f, floatArrayOf(1f, .1f, .05f, .9f)) {
                s1 = 2.2f; rot = 0f; asp = 1.4f; env = { q -> 1 - q }; at = { v3(c[0], 1.4f * min(1f, age * 6), c[2]) }
            }
        }
    }

    /** v80: camada destrutiva extra */
    fun ultSlamBig(c: V, P: Float) {
        ultSlam(c, P)
        val K1 = SLT?.invoke(floatArrayOf(1f, .06f, .03f, 1f)) ?: floatArrayOf(1f, .06f, .03f, 1f)
        addE(object : Eff() {
            init { life = .55f }
            val cc = FloatArray(4); val pr = floatArrayOf(0f, .4f, 0f, 9f)
            override fun draw(p: Float) {
                val qq = age / life; if (qq > 1) return; val e = 1 - (1 - qq).pow(2.4f)
                cc[0] = K1[0]; cc[1] = K1[1]; cc[2] = K1[2]; cc[3] = .7f * (1 - qq); pr[0] = qq
                cylinder(add, v3(c[0], 0f, c[2]), .3f + 1.9f * P * e, .2f + 1.4f * P * e, (.6f + 1.6f * e) * P * .8f, 32, cc, pr)
            }
        })
        addE(object : Eff() {
            init { life = .4f; delay = .05f }
            val cc = FloatArray(4); val pr = floatArrayOf(0f, .3f, 0f, 2f)
            override fun draw(p: Float) {
                val qq = (age - delay) / life; if (qq < 0 || qq > 1) return
                cc[0] = K1[0]; cc[1] = K1[1]; cc[2] = K1[2]; cc[3] = .6f; pr[0] = qq
                ground(add, c, 2.6f * P, cc, pr)
            }
        })
        val mP = min(P, 1.6f)
        if (SLT == null) {
            for (i in 0 until (3 * mP).roundToInt()) {
                val a = rnd(0f, 6.28f); val r = rnd(0f, .7f) * P
                spr(v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), rnd(.45f, .65f), T87, rnd(.3f, .45f) * P * .7f, floatArrayOf(1f, .07f, .04f, .5f)) {
                    v = v3(cos(a) * .6f, rnd(7f, 11f) * sqrt(P), sin(a) * .6f); dr = 2.6f; st = true; m = 2; asp = 3.2f; env = { q -> sin(PIf * min(1f, q * 1.3f)) }
                }
            }
            for (i in 0 until (3 * mP).roundToInt()) {
                val a = rnd(0f, 6.28f); val r = rnd(.6f, 1.6f) * P
                spr(v3(c[0] + cos(a) * r, .08f, c[2] + sin(a) * r), .22f, SPARK + (i % 2), rnd(.7f, 1.1f) * P * .6f, floatArrayOf(1f, .15f, .1f, 1f)) {
                    gd = true; gy = .06f; delay = rnd(0f, .15f); m = 2; rot = rnd(0f, 6.28f); env = { q -> 1 - q }
                }
            }
            for (i in 0 until (3 * P).roundToInt()) {
                val a = rnd(0f, 6.28f)
                spr(v3(c[0] + cos(a) * .5f, rnd(.4f, 1.4f), c[2] + sin(a) * .5f), .16f, SPARK + (i % 2), rnd(.6f, .9f) * P * .6f, floatArrayOf(1f, .15f, .1f, 1f)) {
                    delay = rnd(0f, .12f); m = 2; rot = rnd(0f, 6.28f); env = { q -> 1 - q }
                }
            }
        }
        val nn = (12 * mP).roundToInt()
        for (i in 0 until nn) {
            val a = i.toFloat() / nn * 6.283f + rnd(-.1f, .1f); val sp = rnd(3f, 5f) * P
            spr(v3(c[0] + cos(a) * .6f, .25f, c[2] + sin(a) * .6f), rnd(1f, 1.5f), SMOKE + (i % 5), .5f * P, floatArrayOf(.28f, .03f, .03f, .7f)) {
                v = v3(cos(a) * sp, rnd(.3f, 1.2f), sin(a) * sp); dr = 2.4f; m = 1; s1 = 1.2f * P; ease = easeO; rotv = rnd(-1f, 1f); env = { q -> min(1f, q * 8) * (1 - q) }
            }
        }
        for (i in 0 until (20 * P).roundToInt()) {
            val a = rnd(0f, 6.28f); val r = rnd(.2f, 1.6f) * P
            spr(v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), rnd(1f, 2f), GLOW, rnd(.04f, .08f), floatArrayOf(1f, .08f, .02f, 1f)) {
                v = v3(rnd(-.3f, .3f), rnd(.6f, 2f), rnd(-.3f, .3f)); delay = rnd(0f, .3f); env = { q -> (1 - q) * (.6f + .4f * sin(q * 30)) }
            }
        }
    }

    val goldT: (V) -> V = { col ->
        val m = max(col[0], max(col[1], col[2]))
        if (m < .1f) col else if (m < .55f) floatArrayOf(.17f, .13f, .09f, col[3] * .8f) else floatArrayOf(1f, min(1f, .4f + col[1] * 1.2f), min(1f, .05f + col[2] * .5f), col[3] * .7f)
    }
    val earthT: (V) -> V = { col ->
        val m = max(col[0], max(col[1], col[2]))
        if (m < .1f) col else if (m < .55f) floatArrayOf(.2f, .15f, .1f, col[3] * .85f) else floatArrayOf(1f, min(1f, .3f + col[1] * 1.2f), min(1f, .04f + col[2] * .5f), col[3] * .7f)
    }
    fun tintSlam(c: V, P: Float, T: (V) -> V) { SLT = T; try { ultSlamBig(c, P) } finally { SLT = null } }

    /** v86: X detalhado (Skill 1) */
    fun xBlade2(from: V, to: V, T: Float) {
        val d0 = vsub(to, from); val fl = vnorm(v3(d0[0], 0f, d0[2])); val rr = v3(fl[2], 0f, -fl[0]); val up = v3(0f, 1f, 0f)
        fun armsAt(r: Float) = arrayOf(.785f + r, -.785f + r).map { a -> vadd(vmul(rr, cos(a)), vmul(up, sin(a))) }
        class Gh(val p: V, val r: Float, var a: Float)
        val ghosts = ArrayList<Gh>()
        val uv = FloatArray(8); val pr = floatArrayOf(0f, 0f, 2f, 16f)
        fun strip(p: V, a: V, L: Float, h: Float, col: V, cell: Int, taper: Float) {
            val wv = vnorm(vcross(a, fl)); val N = 10; pr[0] = cell.toFloat()
            for (i in 0 until N) {
                val s0 = i.toFloat() / N * 2 - 1; val s1 = (i + 1f) / N * 2 - 1
                val t0 = (1 - s0 * s0).coerceAtLeast(0f).pow(taper); val t1 = (1 - s1 * s1).coerceAtLeast(0f).pow(taper)
                val h0 = h * max(t0, .08f); val h1 = h * max(t1, .08f)
                val P0x = p[0] + a[0] * s0 * L; val P0y = p[1] + a[1] * s0 * L; val P0z = p[2] + a[2] * s0 * L
                val P1x = p[0] + a[0] * s1 * L; val P1y = p[1] + a[1] * s1 * L; val P1z = p[2] + a[2] * s1 * L
                q[0] = P0x - wv[0] * h0; q[1] = P0y - wv[1] * h0; q[2] = P0z - wv[2] * h0
                q[3] = P1x - wv[0] * h1; q[4] = P1y - wv[1] * h1; q[5] = P1z - wv[2] * h1
                q[6] = P1x + wv[0] * h1; q[7] = P1y + wv[1] * h1; q[8] = P1z + wv[2] * h1
                q[9] = P0x + wv[0] * h0; q[10] = P0y + wv[1] * h0; q[11] = P0z + wv[2] * h0
                uv[0] = s0; uv[1] = -1f; uv[2] = s1; uv[3] = -1f; uv[4] = s1; uv[5] = 1f; uv[6] = s0; uv[7] = 1f
                quadQ(add, uv, col, pr)
            }
        }
        val gmPr = floatArrayOf(GM.toFloat(), 0f, 2f, 16f); val f21Pr = floatArrayOf(F21.toFloat(), 0f, 2f, 16f)
        fun X(p: V, roll: Float, L: Float, H: Float, A: Float) {
            for (a in armsAt(roll)) {
                strip(p, a, L * 1.05f, H * 3, floatArrayOf(1f, .4f, .06f, .2f * A), T87, .5f)
                strip(p, a, L, H * 1.3f, floatArrayOf(1f, .58f, .12f, .6f * A), T87, .45f)
                strip(p, a, L, H, floatArrayOf(1f, .76f, .24f, A), T87, .4f)
                strip(p, a, L * .97f, H * .42f, floatArrayOf(1f, .93f, .62f, A), T63, .35f)
                strip(p, a, L * 1.12f, H * .11f, floatArrayOf(1f, 1f, .86f, .95f * A), T63, .3f)
            }
            for (a in armsAt(roll)) for (s in intArrayOf(-1, 1)) billboard(add, vadd(p, vmul(a, s * L * 1.02f)), .2f, .2f, 0f, floatArrayOf(1f, .5f, .1f, .5f * A), gmPr)
            billboard(add, p, .8f, .8f, 0f, floatArrayOf(1f, .55f, .12f, .5f * A), gmPr)
        }
        addE(object : Eff() {
            var p: V? = null; var roll = 0f; var gh = 0f; var acc = 0f; var sk = 0f
            init { life = T + .02f }
            override fun upd(dt: Float) {
                val qq = min(1f, age / T); p = vadd(from, vmul(d0, qq)); roll = qq * .45f
                val pp0 = p!!
                gh += dt; if (gh > .025f) { gh = 0f; ghosts.add(Gh(pp0.copyOf(), roll, 0f)); if (ghosts.size > 5) ghosts.removeAt(0) }
                for (g in ghosts) g.a += dt
                acc += dt * 70
                while (acc > 1) {
                    acc--
                    val ar = armsAt(roll)[if (Math.random() < .5) 0 else 1]; val pp = vadd(pp0, vmul(ar, rnd(-1f, 1f) * .9f))
                    if (Math.random() < .55) spr(pp, rnd(.35f, .65f), GLOW, rnd(.025f, .05f), floatArrayOf(1f, .62f, .15f, 1f)) { v = vadd(vmul(fl, -rnd(.6f, 1.8f)), v3(0f, rnd(0f, .5f), 0f)); dr = 2f; env = { q -> 1 - q } }
                    else spr(pp, rnd(.35f, .55f), WISP, rnd(.1f, .18f), floatArrayOf(1f, .6f, .15f, .2f)) { v = vmul(fl, -rnd(.3f, .9f)); m = 2; s1 = rnd(.3f, .45f); rotv = rnd(-2f, 2f); env = { q -> sin(PIf * q) } }
                }
                sk += dt * 16
                while (sk > 1) {
                    sk--
                    val ar = armsAt(roll)[if (Math.random() < .5) 0 else 1]; val pp = vadd(pp0, vmul(ar, rnd(-.9f, .9f)))
                    spr(pp, .07f, SPARK + (if (Math.random() < .5) 0 else 1), rnd(.16f, .26f), floatArrayOf(1f, .75f, .3f, .9f)) { m = 2; rot = rnd(0f, 6.28f); env = { q -> 1 - q } }
                }
            }
            override fun draw(pq: Float) {
                val pp = p ?: return; if (age > T) return
                val fl2 = 1 + .05f * sin(age * 80)
                for (g in ghosts) { val a = max(0f, .32f - g.a * 2.2f); if (a > .02f) X(g.p, g.r, .85f, .22f, a) }
                X(pp, roll, .95f * fl2, .26f, 1f)
                for (a in armsAt(roll)) for (s in intArrayOf(-1, 1)) billboard(add, vadd(pp, vmul(a, s * .97f)), .26f, .26f, age * 10 * s, floatArrayOf(1f, .82f, .45f, .9f), f21Pr)
                billboard(add, pp, .45f, .45f, age * 7, floatArrayOf(1f, .85f, .5f, .9f), f21Pr)
            }
        })
        later(T) {
            val r0 = .45f
            addE(object : Eff() {
                init { life = .55f }
                override fun draw(p: Float) {
                    val qq = age / life; if (qq > 1) return
                    val e = 1 - (1 - qq).pow(3); val A = if (qq < .15f) 1f else (1 - (qq - .15f) / .85f).pow(1.5f)
                    X(to, r0, .95f + e * .4f, .26f * (1 + e * .35f), A)
                }
            })
            for (i in 0 until 10) {
                val ar = armsAt(r0)[i % 2]; val pp = vadd(to, vmul(ar, rnd(-.9f, .9f)))
                spr(pp, rnd(.5f, .8f), WISP, rnd(.12f, .2f), floatArrayOf(1f, .6f, .15f, .18f)) { v = v3(rnd(-.2f, .2f), rnd(.2f, .6f), rnd(-.2f, .2f)); delay = rnd(.1f, .3f); m = 2; s1 = rnd(.35f, .5f); rotv = rnd(-2f, 2f); env = { q -> sin(PIf * q) } }
            }
        }
    }

    /** brilho das lâminas na guarda (Skill 2) */
    fun guardAura(life0: Float, tipsF: (Int) -> V) {
        val gm = floatArrayOf(GM.toFloat(), 0f, 2f, 16f); val f21 = floatArrayOf(F21.toFloat(), 0f, 2f, 16f)
        addE(object : Eff() {
            init { life = life0 }
            override fun draw(p: Float) {
                val qq = age / life; if (qq > 1) return
                val A = min(1f, age / .1f) * (1 - sstep(.75f, 1f, qq))
                for (k in 0 until 2) {
                    val tp = tipsF(k)
                    billboard(add, tp, .42f, .42f, 0f, floatArrayOf(1f, .5f, .1f, .5f * A), gm)
                    billboard(add, tp, .26f, .26f, age * 7 * (if (k == 1) 1 else -1), floatArrayOf(1f, .85f, .45f, .85f * A), f21)
                }
            }
        })
    }

    fun deflectFx(p: V) {
        spr(p, .12f, F21, .5f, floatArrayOf(1f, .9f, .6f, 1f)) { s1 = .9f; rot = rnd(0f, 6f); env = { q -> 1 - q } }
        spr(p, .35f, RING, .1f, floatArrayOf(1f, .75f, .3f, .95f)) { s1 = .9f; ease = easeO; env = { q -> 1 - q * q } }
        spr(p, .4f, GM, .3f, floatArrayOf(1f, .55f, .12f, .6f)) { s1 = .8f; env = { q -> 1 - q } }
        for (i in 0 until 16) {
            val a = rnd(0f, 6.28f); val e = rnd(-.4f, .9f)
            spr(p, rnd(.3f, .55f), F31, rnd(.1f, .16f), floatArrayOf(1f, .8f, .35f, 1f)) { v = v3(cos(a) * rnd(3f, 7f), e * 5 + 1, sin(a) * rnd(3f, 7f)); g = 8f; dr = 1.6f; st = true; m = 2; asp = 1.6f; env = { q -> 1 - q } }
        }
        for (i in 0 until 10) {
            val a = rnd(0f, 6.28f)
            spr(p, rnd(.15f, .25f), T63, .05f, floatArrayOf(1f, .85f, .45f, .9f)) { v = v3(cos(a) * rnd(4f, 8f), rnd(-1f, 3f), sin(a) * rnd(4f, 8f)); dr = 5f; st = true; m = 2; asp = 7f; env = { q -> 1 - q } }
        }
        for (i in 0 until 14) spr(vadd(p, v3(rnd(-.2f, .2f), rnd(-.2f, .2f), rnd(-.2f, .2f))), rnd(.5f, 1f), GLOW, rnd(.03f, .05f), floatArrayOf(1f, .62f, .15f, 1f)) { v = v3(rnd(-1f, 1f), rnd(.3f, 1.8f), rnd(-1f, 1f)); env = { q -> 1 - q } }
    }

    /** v91: onda do contra-ataque (Skill 2) */
    fun counterWave(c: V) {
        tintSlam(v3(c[0], 0f, c[2]), .75f, goldT)
        val uv = FloatArray(8); val pr = FloatArray(4)
        addE(object : Eff() {
            init { life = .42f }
            override fun draw(p: Float) {
                val qq = age / life; if (qq > 1) return
                val e = 1 - (1 - qq).pow(3); val R = .6f + 2.4f * e; val A = (1 - qq).pow(1.3f); val N = 48
                val layers = arrayOf(
                    Triple(.3f, floatArrayOf(1f, .5f, .1f, .4f * A), T87),
                    Triple(.14f, floatArrayOf(1f, .75f, .25f, A), T87),
                    Triple(.05f, floatArrayOf(1f, .95f, .7f, A), T63))
                for ((h, col, cell) in layers) {
                    pr[0] = cell.toFloat(); pr[1] = 0f; pr[2] = 2f; pr[3] = 16f
                    val hv = h * (1 - qq * .6f); val y = .95f
                    for (i in 0 until N) {
                        val a0 = i.toFloat() / N * PIf * 2 + qq * 2; val a1 = (i + 1f) / N * PIf * 2 + qq * 2
                        val x0 = c[0] + cos(a0) * R; val z0 = c[2] + sin(a0) * R; val x1 = c[0] + cos(a1) * R; val z1 = c[2] + sin(a1) * R
                        q[0] = x0; q[1] = y - hv; q[2] = z0; q[3] = x1; q[4] = y - hv; q[5] = z1; q[6] = x1; q[7] = y + hv; q[8] = z1; q[9] = x0; q[10] = y + hv; q[11] = z0
                        val u = (i % 12) / 12f; val u1 = ((i % 12) + 1) / 12f
                        uv[0] = u * 2 - 1; uv[1] = -1f; uv[2] = u1 * 2 - 1; uv[3] = -1f; uv[4] = u1 * 2 - 1; uv[5] = 1f; uv[6] = u * 2 - 1; uv[7] = 1f
                        quadQ(add, uv, col, pr)
                    }
                }
            }
        })
        for (i in 0 until 20) {
            val a = i / 20f * PIf * 2 + rnd(-.1f, .1f)
            spr(v3(c[0] + cos(a) * .7f, .95f, c[2] + sin(a) * .7f), rnd(.2f, .35f), T63, .06f, floatArrayOf(1f, .8f, .35f, .9f)) { v = v3(cos(a) * rnd(6f, 10f), rnd(-.5f, 1.5f), sin(a) * rnd(6f, 10f)); dr = 4f; st = true; m = 2; asp = 8f; env = { q -> 1 - q } }
        }
    }

    /** Aura de sangue (Ultimate) — recriação do prefab "Blood aura" */
    fun bloodAura(life0: Float) {
        val fi: (Float) -> Float = { q -> sstep(0f, .05f, q) * (1 - sstep(.92f, 1f, q)) }
        spr(v3(0f, 0f, 0f), life0, GM, 1.5f, floatArrayOf(1f, .07f, 0f, .75f)) { at = { root }; gd = true; gy = .03f; env = { q -> fi(q) * (.8f + .2f * sin(q * life0 * 9)) } }
        spr(v3(0f, 0f, 0f), life0, GM, .8f, floatArrayOf(1f, .25f, .15f, .6f)) { at = { root }; gd = true; gy = .034f; env = fi }
        addE(object : Eff() {
            var tr = 0f; var ln = 0f; var fs = 0f; var fl = 0f; var sm = 0f; var sp = 0f
            init { life = life0 }
            fun R(a: Float, r: Float, y: Float) = v3(root[0] + cos(a) * r, y, root[2] + sin(a) * r)
            override fun upd(dt: Float) {
                val qq = age / life; if (qq > .93f) return
                tr += dt * 22
                while (tr > 1) {
                    tr--; val a = rnd(0f, 6.28f); val e = rnd(-.3f, .9f); val p = R(a, .25f * cos(e), 1.0f + .45f * sin(e)); val s = rnd(1.8f, 2.6f)
                    spr(p, rnd(.28f, .36f), T87, rnd(.16f, .24f), floatArrayOf(1f, .12f, .1f, 1f)) { v = v3(cos(a) * s * .55f, s, sin(a) * s * .55f); dr = .5f; st = true; m = 2; asp = 2.4f; env = { q -> sin(PIf * q) } }
                }
                ln += dt * 30
                while (ln > 1) {
                    ln--; val a = rnd(0f, 6.28f); val p = R(a, rnd(.25f, .6f), rnd(.1f, 2.1f))
                    spr(p, .25f, T63, rnd(.06f, .1f), floatArrayOf(1f, .11f, .11f, 1f)) { v = v3(0f, rnd(2.5f, 4f), 0f); st = true; m = 2; asp = rnd(4f, 7f); env = { q -> sin(PIf * q) } }
                }
                fs += dt * 10
                while (fs > 1) {
                    fs--; val a = rnd(0f, 6.28f); val p = R(a, rnd(.2f, .5f), rnd(.4f, 2f))
                    spr(p, .3f, F31, rnd(.22f, .3f), floatArrayOf(1f, .4f, .36f, 1f)) { v = v3(cos(a) * .6f, rnd(1.5f, 2.5f), sin(a) * .6f); st = true; m = 2; asp = 1.6f; env = { q -> 1 - q } }
                }
                fl += dt * 5
                while (fl > 1) {
                    fl--; val a = rnd(0f, 6.28f)
                    spr(R(a, rnd(.1f, .45f), rnd(.3f, 1.9f)), .2f, F21, rnd(.8f, 1.3f), floatArrayOf(1f, .34f, .29f, .9f)) { rot = rnd(0f, 6.28f); env = { q -> sin(PIf * q) } }
                }
                sm += dt * 6
                while (sm > 1) {
                    sm--; val a = rnd(0f, 6.28f)
                    spr(R(a, rnd(.1f, .4f), rnd(.4f, 1.6f)), .7f, SMOKE24, .8f, floatArrayOf(.5f, 0f, 0f, .55f)) { v = v3(0f, .5f, 0f); m = 1; s1 = 1.2f; rotv = rnd(-1f, 1f); env = { q -> sin(PIf * q) } }
                }
                sp += dt * 14
                while (sp > 1) {
                    sp--; val a = rnd(0f, 6.28f)
                    spr(R(a, rnd(.3f, .7f), rnd(.1f, 1.8f)), .5f, GLOW, .07f, floatArrayOf(1f, 0f, .01f, 1f)) { v = v3(rnd(-.4f, .4f), rnd(.5f, 1.4f), rnd(-.4f, .4f)); env = { q -> 1 - q } }
                }
            }
        })
    }

    // ---- Skill 4 (machado arremessado) ----
    fun thrSpark(p: V) {
        spr(vadd(p, v3(rnd(-.12f, .12f), rnd(-.12f, .12f), rnd(-.12f, .12f))), rnd(.3f, .55f), GLOW, rnd(.025f, .05f), floatArrayOf(1f, .62f, .15f, 1f)) { v = v3(rnd(-.4f, .4f), rnd(0f, .8f), rnd(-.4f, .4f)); dr = 2f; env = { q -> 1 - q } }
        spr(p, .12f, GM, .32f, floatArrayOf(1f, .55f, .12f, .35f)) { env = { q -> 1 - q } }
    }
    fun thrHit(p: V) {
        spr(p, .35f, GM, .3f, floatArrayOf(1f, .55f, .12f, .6f)) { s1 = .9f; ease = easeO; env = { q -> 1 - q } }
        spr(p, .4f, RING, .15f, floatArrayOf(1f, .7f, .25f, .7f)) { s1 = .8f; ease = easeO; env = { q -> 1 - q } }
        for (i in 0 until 6) spr(p, rnd(.5f, .8f), SMOKE + (i % 5), .15f, floatArrayOf(.25f, .2f, .15f, .4f)) { v = v3(rnd(-1f, 1f), rnd(-.5f, 1f), rnd(-1f, 1f)); dr = 2f; m = 1; s1 = .4f; env = { q -> sin(PIf * q) } }
    }
    fun thrCatch(p: V) {
        spr(p, .3f, RING, .1f, floatArrayOf(1f, .7f, .25f, .7f)) { s1 = .6f; ease = easeO; env = { q -> 1 - q } }
        spr(p, .35f, GM, .4f, floatArrayOf(1f, .6f, .15f, .6f)) { s1 = .2f; env = { q -> 1 - q } }
    }
    fun stunFx(head: () -> V, life0: Float) {
        val f21 = floatArrayOf(F21.toFloat(), 0f, 2f, 16f); val gm = floatArrayOf(GM.toFloat(), 0f, 2f, 16f)
        addE(object : Eff() {
            init { life = life0 }
            override fun draw(p: Float) {
                val qq = age / life; if (qq > 1) return
                val A = min(1f, qq * 8) * (1 - sstep(.8f, 1f, qq)); val h = head()
                for (i in 0 until 4) {
                    val a = age * 5 + i * PIf / 2
                    val pp = v3(h[0] + cos(a) * .32f, h[1] + .12f * sin(a * 2), h[2] + sin(a) * .32f)
                    billboard(add, pp, .13f, .13f, age * 6, floatArrayOf(1f, .85f, .4f, A), f21)
                    billboard(add, pp, .12f, .12f, 0f, floatArrayOf(1f, .6f, .15f, .5f * A), gm)
                }
            }
        })
    }

    /** Corrente de energia (Skill 4): fases ph() 1 voo, 2 cravado, 3 puxando, 4/0 fim */
    fun chain2(getA: () -> V, getB: () -> V, ph: () -> Int, flyQ: () -> Float, tpull: Float) {
        val RG = RING; val MG = MAGIC3
        addE(object : Eff() {
            var off = 0f; var lastPh = 1; var endT = 0f; var shk = 0f; var pulse = -1f
            init { life = 9f }
            val prT87 = floatArrayOf(T87.toFloat(), 0f, 2f, 16f); val prT63 = floatArrayOf(T63.toFloat(), 0f, 2f, 16f)
            val prRG = floatArrayOf(RG.toFloat(), 0f, 2f, 16f); val prGM = floatArrayOf(GM.toFloat(), 0f, 2f, 16f)
            val prMG = floatArrayOf(MG.toFloat(), 0f, 2f, 16f); val prF21 = floatArrayOf(F21.toFloat(), 0f, 2f, 16f)
            val uv = FloatArray(8)
            override fun upd(dt: Float) {
                val p = ph(); val a = getA(); val b = getB(); val L = vlen(vsub(b, a))
                if (p == 3) off -= dt * L / max(.05f, tpull) * 1.05f
                if (p == 2 && lastPh == 1) { shk = 1f; pulse = 0f }
                if (pulse >= 0) { pulse += dt * 3.2f; if (pulse > 1.2f) pulse = -1f }
                shk = max(0f, shk - dt * 4)
                if (p >= 4 || p == 0) { endT += dt; if (endT > .15f) life = 0f }
                lastPh = p
                if (p in 1..3 && Math.random() < dt * (if (p == 3) 45 else 20)) {
                    val s = Math.random().toFloat(); val pp = vadd(a, vmul(vsub(b, a), s))
                    spr(pp, rnd(.3f, .6f), GLOW, rnd(.02f, .04f), floatArrayOf(1f, .65f, .2f, 1f)) { v = v3(rnd(-.4f, .4f), if (p == 3) rnd(-.6f, .2f) else rnd(-.2f, .6f), rnd(-.4f, .4f)); g = if (p == 3) 2f else 0f; dr = 1.5f; env = { q -> 1 - q } }
                }
            }
            override fun draw(pq: Float) {
                val p = ph(); val a = getA(); val b = getB(); val d = vsub(b, a); val L = vlen(d); if (L < .05f) return
                val u = vmul(d, 1 / L); val T = age; val A = if (p >= 4 || p == 0) max(0f, 1 - endT / .15f) else 1f
                val side = vnorm(vcross(u, v3(0f, 1f, 0f))); val upv = vcross(side, u)
                var sag = 0f; var wav = 0f
                if (p == 1) { val qq = flyQ(); sag = .18f * (1 - qq); wav = .14f * (1 - qq) }
                val vib = shk * .07f * sin(T * 80) + (if (p == 3) .015f * sin(T * 60) else 0f)
                fun at(s: Float): V {
                    val e = sin(PIf * s)
                    return vadd(vadd(vadd(a, vmul(d, s)), vmul(upv, -sag * e + vib * e)), vmul(side, wav * e * sin(s * PIf * 3 - T * 16)))
                }
                val N = 24; val P = Array(N + 1) { at(it.toFloat() / N) }
                val view = vnorm(vsub(vmul(vadd(a, b), .5f), eye)); val wd = vnorm(vcross(u, view))
                fun rib(w: Float, col: V, pr: V) {
                    for (i in 0 until N) {
                        val s0 = i.toFloat() / N; val s1 = (i + 1f) / N
                        q[0] = P[i][0] - wd[0] * w; q[1] = P[i][1] - wd[1] * w; q[2] = P[i][2] - wd[2] * w
                        q[3] = P[i + 1][0] - wd[0] * w; q[4] = P[i + 1][1] - wd[1] * w; q[5] = P[i + 1][2] - wd[2] * w
                        q[6] = P[i + 1][0] + wd[0] * w; q[7] = P[i + 1][1] + wd[1] * w; q[8] = P[i + 1][2] + wd[2] * w
                        q[9] = P[i][0] + wd[0] * w; q[10] = P[i][1] + wd[1] * w; q[11] = P[i][2] + wd[2] * w
                        uv[0] = s0 * 2 - 1; uv[1] = -1f; uv[2] = s1 * 2 - 1; uv[3] = -1f; uv[4] = s1 * 2 - 1; uv[5] = 1f; uv[6] = s0 * 2 - 1; uv[7] = 1f
                        quadQ(add, uv, col, pr)
                    }
                }
                rib(.12f, floatArrayOf(1f, .45f, .08f, .28f * A), prT87); rib(.045f, floatArrayOf(1f, .8f, .35f, .55f * A), prT63)
                for (ph0 in floatArrayOf(0f, PIf)) {
                    val H = Array(N + 1) { i ->
                        val s = i.toFloat() / N; val ang = s * L * 9 - T * 14 + ph0; val r = .07f * (.6f + .4f * sin(PIf * s))
                        vadd(P[i], vadd(vmul(side, cos(ang) * r), vmul(upv, sin(ang) * r)))
                    }
                    val col = floatArrayOf(1f, .85f, .5f, .75f * A); val w = .012f
                    for (i in 0 until N) {
                        q[0] = H[i][0] - wd[0] * w; q[1] = H[i][1] - wd[1] * w; q[2] = H[i][2] - wd[2] * w
                        q[3] = H[i + 1][0] - wd[0] * w; q[4] = H[i + 1][1] - wd[1] * w; q[5] = H[i + 1][2] - wd[2] * w
                        q[6] = H[i + 1][0] + wd[0] * w; q[7] = H[i + 1][1] + wd[1] * w; q[8] = H[i + 1][2] + wd[2] * w
                        q[9] = H[i][0] + wd[0] * w; q[10] = H[i][1] + wd[1] * w; q[11] = H[i][2] + wd[2] * w
                        quadQ(add, UVQ, col, prT63)
                    }
                }
                val sp = .12f; val n = floor(L / sp).toInt(); val o = ((off % (2 * sp)) + 2 * sp) % (2 * sp)
                for (i in -1..n + 1) {
                    val dist = i * sp + o; if (dist < 0 || dist > L) continue
                    val s = dist / L; val Pp = at(s)
                    val kk = ((dist - o) / sp).roundToInt() + (off / sp).roundToInt(); val odd = ((kk % 2) + 2) % 2 == 1
                    val nrm = if (odd) side else upv; val w = vnorm(vcross(u, nrm))
                    val glow = if (pulse >= 0) exp(-((s - pulse) * 6).pow(2)) else 0f; val cA = (.95f + glow) * A
                    quadAB(add, Pp[0], Pp[1], Pp[2], u[0] * .07f, u[1] * .07f, u[2] * .07f, w[0] * .042f, w[1] * .042f, w[2] * .042f, UVQ, floatArrayOf(1f, .72f + .2f * glow, .28f + .4f * glow, cA), prRG)
                    if (glow > .05f) billboard(add, Pp, .12f * glow, .12f * glow, 0f, floatArrayOf(1f, .85f, .5f, glow * A), prGM)
                }
                fun seal(pp: V, r: Float, rot: Float) {
                    billboard(add, pp, r, r, rot, floatArrayOf(1f, .7f, .25f, .75f * A), prMG)
                    billboard(add, pp, r * .9f, r * .9f, 0f, floatArrayOf(1f, .5f, .1f, .45f * A), prGM)
                }
                seal(a, .2f, T * 5); seal(b, .26f + .08f * shk, -T * 6)
                billboard(add, b, .18f, .18f, T * 9, floatArrayOf(1f, .9f, .6f, .9f * A), prF21)
            }
        })
    }

    /** indicador de mira da Skill 4 (pontos no chão + anel no fim) */
    fun aimFx(on: () -> Boolean) {
        val gm = floatArrayOf(GM.toFloat(), 0f, 2f, 16f); val rg = floatArrayOf(RING.toFloat(), 0f, 2f, 16f); val mg = floatArrayOf(MAGIC3.toFloat(), 0f, 2f, 16f)
        addE(object : Eff() {
            init { life = 9f }
            override fun draw(p: Float) {
                if (!on()) { life = 0f; return }
                val T = age; val len = 9f; val N = 22; val c = root; val fx = sin(yaw); val fz = cos(yaw)
                for (i in 0 until N) {
                    val s0 = i.toFloat() / N; val pp = v3(c[0] + fx * (.6f + s0 * len), 0f, c[2] + fz * (.6f + s0 * len))
                    val a = (.45f + .4f * sin(T * 10 - i * .7f)) * (1 - s0 * .5f)
                    ground(add, pp, .16f, floatArrayOf(1f, .72f, .25f, a), gm, .06f)
                }
                val e = v3(c[0] + fx * (.6f + len), 0f, c[2] + fz * (.6f + len))
                ground(add, e, .7f + .08f * sin(T * 8), floatArrayOf(1f, .7f, .25f, .85f), rg, .07f)
                ground(add, e, .5f, floatArrayOf(1f, .6f, .15f, .6f), mg, .08f)
            }
        })
    }

    // tochas do campo
    fun torch(p: V, dt: Float) {
        if (Math.random() < dt * 26) {
            val fl = Math.random() < .5
            spr(v3(p[0] + rnd(-.06f, .06f), p[1], p[2] + rnd(-.06f, .06f)), rnd(.45f, .8f), if (fl) FLAME + (if (Math.random() < .5) 0 else 1) else FIRE + (Math.random() * 2).toInt(),
                rnd(.18f, .3f), if (Math.random() < .35) floatArrayOf(1f, .55f, .12f, .85f) else floatArrayOf(1f, .32f, .06f, .8f)) {
                v = v3(rnd(-.05f, .05f), rnd(.5f, .9f), rnd(-.05f, .05f)); dr = .6f; s1 = rnd(.06f, .12f); rot = if (fl) rnd(-.3f, .3f) else rnd(0f, 6.28f); asp = if (fl) 1.6f else 1f
                env = { q -> min(1f, q * 8) * (1 - q) }
            }
        }
        if (Math.random() < dt * 3) spr(v3(p[0], p[1] + .3f, p[2]), 1f, GLOW, .035f, floatArrayOf(1f, .6f, .2f, 1f)) { v = v3(rnd(-.2f, .2f), rnd(.6f, 1.4f), rnd(-.2f, .2f)); env = { q -> 1 - q } }
    }

    // ---------- ciclo ----------
    fun upd(dt: Float) {
        time += dt
        if (pend.isNotEmpty()) { fxs.addAll(pend); pend.clear() }
        var i = fxs.size - 1
        while (i >= 0) {
            val f = fxs[i]
            f.age += dt
            if (f.age >= f.delay) f.upd(dt)
            if (f.age >= f.delay + f.life) fxs.removeAt(i)
            i--
        }
        if (pend.isNotEmpty()) { fxs.addAll(pend); pend.clear() }
    }

    fun draw(vp: FloatArray, tm: Float) {
        solid.n = 0; alpha.n = 0; add.n = 0
        for (f in fxs) if (f.age >= f.delay) { try { f.draw(min(1f, (f.age - f.delay) / (if (f.life == 0f) 1f else f.life))) } catch (_: Exception) {} }
        if (alpha.n == 0 && add.n == 0) return
        glUseProgram(prog)
        glUniformMatrix4fv(uVP, 1, false, vp, 0); glUniform1f(uTime, tm); glUniform3fv(uEye, 1, eye, 0)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, atl); glUniform1i(uAtl, 0); glUniform2f(uAG, 16f, 8f)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE)
        fun pass(B: Buf, blend: Int) {
            if (B.n == 0) return
            glDepthMask(false); glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, if (blend == 1) GL_ONE_MINUS_SRC_ALPHA else GL_ONE)
            fb.clear(); fb.put(B.a, 0, B.n * 13); fb.flip()
            glBufferData(GL_ARRAY_BUFFER, B.n * 13 * 4, fb, GL_STREAM_DRAW)
            glDrawArrays(GL_TRIANGLES, 0, B.n)
        }
        pass(alpha, 1); pass(add, 2)
        glDepthMask(true); glDisable(GL_BLEND); glBindVertexArray(0)
    }
}
