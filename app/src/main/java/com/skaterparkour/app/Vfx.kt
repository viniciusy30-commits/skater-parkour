package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Efeitos visuais do Guerreiro do Visualizador v99, portados LITERALMENTE: mesmos shaders, mesmas fórmulas,
 * mesmos números e o mesmo atlas de imagens (w/fx_atlas.jpg). Nada foi inventado.
 * Coordenadas: x e z do mundo; y "local" (chão = 0). A altura do chão (oy) é somada ao desenhar.
 */
private typealias V = FloatArray

private val PIF = 3.1415927f
private const val TWO_PI = 6.2831855f
private fun vadd(a: V, b: V): V = floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
private fun vsub(a: V, b: V): V = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun vmul(a: V, s: Float): V = floatArrayOf(a[0] * s, a[1] * s, a[2] * s)
private fun vlen(a: V): Float = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
private fun vcross(a: V, b: V): V = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun vnorm(a: V): V { var l = vlen(a); if (l < 1e-9f) l = 1f; return floatArrayOf(a[0] / l, a[1] / l, a[2] / l) }
private fun v3(x: Float, y: Float, z: Float): V = floatArrayOf(x, y, z)
private fun c4(r: Float, g: Float, b: Float, a: Float): V = floatArrayOf(r, g, b, a)
private fun cl(x: Float, a: Float, b: Float): Float = if (x < a) a else if (x > b) b else x
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
private fun sstep(a: Float, b: Float, x: Float): Float { val t = cl((x - a) / (b - a), 0f, 1f); return t * t * (3f - 2f * t) }

// ---- células do atlas (16 x 8) ----
private const val C_SWORD = 0; private const val C_CRIT = 24; private const val C_EXPL = 48
private const val C_SCORCH = 72; private const val C_TWIRL = 75; private const val C_SMOKE = 78
private const val C_STAR = 93; private const val C_MAGIC3 = 96; private const val C_RING = 97
private const val C_GLOW = 98; private const val C_LRING = 99; private const val C_SPARK = 100
private const val C_FLARE = 102; private const val C_MOTION = 104; private const val C_WISP = 106
private const val C_TRAIL87 = 107; private const val C_TRAIL63 = 108; private const val C_FLASH31 = 109
private const val C_FLARE21 = 110; private const val C_SMOKE24 = 111; private const val C_GMASK = 113

private const val MAXV = 40000
private class Bt { val a = FloatArray(13 * MAXV); var n = 0 }

private const val VS = "#version 300 es\n" +
    "layout(location=0) in vec3 aP;layout(location=1) in vec2 aU;layout(location=2) in vec4 aC;layout(location=3) in vec4 aR;\n" +
    "uniform mat4 uVP;out vec2 vUV;out vec4 vCol;out vec4 vPrm;out vec3 vW;\n" +
    "void main(){vUV=aU;vCol=aC;vPrm=aR;vW=aP;gl_Position=uVP*vec4(aP,1.);}\n"

private const val FS = "#version 300 es\n" +
    "precision highp float;\n" +
    "in vec2 vUV;in vec4 vCol;in vec4 vPrm;in vec3 vW;out vec4 o;\n" +
    "uniform float uTime;uniform vec3 uEye;uniform sampler2D uAtl;uniform vec2 uAG;\n" +
    "float ss(float a,float b,float x){float t=clamp((x-a)/(b-a),0.,1.);return t*t*(3.-2.*t);}\n" +
    "float h21(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}\n" +
    "float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h21(i),h21(i+vec2(1,0)),f.x),mix(h21(i+vec2(0,1)),h21(i+vec2(1,1)),f.x),f.y);}\n" +
    "float fbm(vec2 p){float a=.5,s=0.;for(int i=0;i<4;i++){s+=a*vn(p);p=p*2.03+17.1;a*=.5;}return s;}\n" +
    "void main(){\n" +
    " int k=int(vPrm.w+.5);float p=vPrm.x,sd=vPrm.y,lay=vPrm.z;vec2 uv=vUV;vec3 c=vCol.rgb;float A=vCol.a;\n" +
    " vec3 hot=mix(vec3(1.,.97,.88),c,.2);\n" +
    " vec4 r=vec4(0.);\n" +
    " if(k==0){float d=length(uv);float g=exp(-d*d*4.5)*ss(1.,.75,d);r=vec4(mix(c,hot,g*g*.7),A*g);}\n" +
    " else if(k==1){\n" +
    "  float s=uv.x,v=uv.y;float head=p*1.35;\n" +
    "  float rev=ss(head,head-.12,s);float tail=head-.62;float tl=ss(tail,tail+.32,s);\n" +
    "  float vis=rev*tl;float body=max(1.-pow(abs(v),1.5),0.);float core=exp(-v*v*10.);\n" +
    "  float n=vn(vec2(s*24.-p*16.,v*2.2+sd*9.));float streak=.5+1.0*n;\n" +
    "  float heat=ss(head-.5,head,s);float fade=1.-ss(.65,1.,p);\n" +
    "  vec3 col=mix(c*vec3(1.,.55,.4),c,ss(1.,.3,abs(v)));col=mix(col,hot,core*(.45+.55*heat));\n" +
    "  float a=lay<.5?(body*streak*1.15+core*.9):(body*body*.5);\n" +
    "  r=vec4(col,vis*fade*A*a);}\n" +
    " else if(k==2){\n" +
    "  float d=length(uv);float ang=atan(uv.y,uv.x);\n" +
    "  float e=1.-pow(1.-p,2.6);float front=.1+.88*e;front+=(vn(vec2(ang*5.+sd*10.,sd))-.5)*.08*(1.-p*.5);\n" +
    "  float th=mix(.11,.025,p);float dd=(d-front)/th;float band=exp(-dd*dd);\n" +
    "  band*=.65+.7*vn(vec2(ang*16.,sd*7.+p*3.));\n" +
    "  float trail=ss(front-.6,front,d)*(1.-step(front,d))*.32;\n" +
    "  float fade=1.-ss(.5,1.,p);\n" +
    "  vec3 col=mix(c,hot,clamp(band*band*1.2,0.,1.));\n" +
    "  r=vec4(col,(band*1.5+trail)*fade*A*ss(1.,.88,d));}\n" +
    " else if(k==3){\n" +
    "  float d=length(uv);float ang=atan(uv.y,uv.x);float n=5.+floor(sd*3.);\n" +
    "  float rays=pow(abs(cos(ang*n*.5+sd*6.)),14.)+.6*pow(abs(cos(ang*(n+3.)*.5+sd*3.)),34.);\n" +
    "  float star=rays*exp(-d*2.4)*(1.-d);float core=exp(-d*d*24.);\n" +
    "  float a=(star*1.3+core*1.2)*A*(1.-ss(.25,1.,p));\n" +
    "  r=vec4(mix(c,hot,clamp(core*1.5+star*.5,0.,1.)),a);}\n" +
    " else if(k==4){\n" +
    "  float x=uv.x,y=uv.y;float lx=1.-pow(abs(x),2.2);float wy=exp(-y*y*5.5);float a=lx*wy;\n" +
    "  float hh=exp(-y*y*30.)*lx;a*=.45+.55*ss(-1.,1.,x);\n" +
    "  r=vec4(mix(c,hot,hh*.9),a*A);}\n" +
    " else if(k==5){\n" +
    "  float d=length(uv);float n=fbm(uv*1.8+sd*13.+vec2(0.,p*.6));float m=n*(1.-d*.85);\n" +
    "  float a=ss(p*.8,p*.8+.25,m)*(1.-p)*A*ss(1.,.5,d);\n" +
    "  r=vec4(c*(.55+.7*n),a);}\n" +
    " else if(k==6){\n" +
    "  float d=length(uv);float edge=d+(fbm(uv*3.+sd*9.)-.5)*.75;\n" +
    "  float a=ss(1.,.78,edge)*(1.-ss(.6,1.,p));\n" +
    "  float n=fbm(uv*4.+sd*5.);vec3 col=mix(c*.5,c*1.25,n);\n" +
    "  float spec=pow(max(0.,1.-length(uv-vec2(-.25,.3))*2.),3.)*.45;\n" +
    "  r=vec4(col+spec,a*A);}\n" +
    " else if(k==7){\n" +
    "  float s=uv.x,v=uv.y;float front=p*7.;float reveal=ss(front,front-.15,s);\n" +
    "  float heat=pow(max(1.-p,0.),1.4);float core=exp(-v*v*7.);\n" +
    "  float fl=.8+.4*vn(vec2(s*30.,uTime*3.+sd*5.));\n" +
    "  vec3 col=mix(c,vec3(1.,.9,.6),core*heat);\n" +
    "  r=vec4(col,reveal*(core*1.4+exp(-v*v*2.)*.5)*heat*fl*A);}\n" +
    " else if(k==9){\n" +
    "  float u=uv.x,w=uv.y;\n" +
    "  float n=vn(vec2(u*46.+sd*20.+uTime*3.,w*1.6-p*3.));float n2=vn(vec2(u*14.-uTime*1.5,w*4.+sd));\n" +
    "  float band=pow(max(1.-w,0.),1.3);float bottom=exp(-w*8.);\n" +
    "  float a=(band*(.25+.9*n*n2*1.6)+bottom*.9)*A;\n" +
    "  r=vec4(mix(c,hot,bottom*.6+n*.2),a*ss(1.,.92,w));}\n" +
    " else if(k==10){\n" +
    "  float d=length(uv);float ang=atan(uv.y,uv.x);\n" +
    "  float ring=exp(-pow((d-.95)/.025,2.));float in2=exp(-pow((d-.62)/.014,2.));float in3=exp(-pow((d-.30)/.012,2.));\n" +
    "  float tt=fract((ang+uTime*.35)*14./6.2832);float tick=ss(.0,.08,tt)*ss(.45,.37,tt)*step(.76,d)*step(d,.9);\n" +
    "  float tt2=fract((ang-uTime*.5)*6./6.2832);float tick2=ss(.0,.06,tt2)*ss(.3,.24,tt2)*step(.40,d)*step(d,.55);\n" +
    "  float fillr=p;float fill=ss(fillr,fillr-.04,d)*(.14+.1*sin(uTime*12.));\n" +
    "  float ef=exp(-pow((d-fillr)/.025,2.))*1.3;\n" +
    "  float a=(ring*1.2+in2*.7+in3*.5+tick*.55+tick2*.45+fill+ef)*A*ss(1.03,.96,d);\n" +
    "  r=vec4(mix(c,hot,clamp(ring*.5+ef*.5,0.,1.)),a);}\n" +
    " else if(k==11){\n" +
    "  float s=uv.x,v=uv.y;float a=pow(s,1.7)*(1.-pow(abs(v),1.8));float core=exp(-v*v*10.)*s;\n" +
    "  float n=vn(vec2(s*18.-uTime*6.,v*3.+sd*5.));\n" +
    "  r=vec4(mix(c,hot,core*.8),(a*(.55+.8*n)+core*.8)*A);}\n" +
    " else if(k==12){\n" +
    "  float dist=length(vW-uEye);vec3 col=mix(c,vec3(.02,.02,.03),ss(14.,34.,dist));r=vec4(col,1.);}\n" +
    " else if(k==15){float d=length(uv);r=vec4(0.,0.,0.,exp(-d*d*3.)*A);}\n" +
    " else if(k==16){float cl=floor(p+.5);vec2 t=clamp(uv*.5+.5,0.,1.);vec2 ce=vec2(mod(cl,uAG.x),floor(cl/uAG.x));\n" +
    "  vec2 tc=(ce+.008+vec2(t.x,1.-t.y)*.984)/uAG;vec3 tx=texture(uAtl,tc).rgb;float L=max(tx.r,max(tx.g,tx.b));\n" +
    "  if(lay<.5)r=vec4(tx*c,A);\n" +
    "  else if(lay<1.5)r=vec4(c*(.75+.4*L),clamp(L*1.15,0.,1.)*A);\n" +
    "  else r=vec4(mix(c*L,vec3(L),L*L*.8)*1.35,A);}\n" +
    " o=r;}\n"

// ---- rastro dourado das lâminas (TRL do visualizador) ----
private const val TVS = "#version 300 es\n" +
    "layout(location=0) in vec3 aP;layout(location=1) in vec2 aU;layout(location=2) in vec4 aR;\n" +
    "uniform mat4 uVP;out vec2 vU;out vec4 vR;void main(){vU=aU;vR=aR;gl_Position=uVP*vec4(aP,1.);}\n"
private const val TFS = "#version 300 es\n" +
    "precision highp float;in vec2 vU;in vec4 vR;uniform float uG;uniform float uR;out vec4 o;\n" +
    "float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}\n" +
    "float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}\n" +
    "void mainX(){\n" +
    " float ag=vR.x,k=vR.y,seed=vR.z,s=vR.w,v=vU.y,u=vU.x;\n" +
    " if(uG>1.5){float x=abs(v*2.-1.);float core=pow(max(0.,1.-x),3.)*pow(max(0.,sin(3.14159*u)),.7);float I=core*(1.-ag);vec3 c=mix(vec3(1.,.55,.08),vec3(1.,.95,.7),core);o=vec4(c*I*1.7,1.);return;}\n" +
    " float life=pow(.5+.5*cos(3.14159*clamp(ag,0.,1.)),1.4);\n" +
    " float tail=smoothstep(0.,.65,s);\n" +
    " float head=pow(s,1.4);\n" +
    " float fa=life*tail*k;\n" +
    " if(uG>.5){float g=exp(-pow((v-.72)*2.2,2.))*fa*(.2+.7*head);vec3 c=mix(vec3(1.,.35,.03),vec3(1.,.7,.2),head);o=vec4(c*g*.35,1.);return;}\n" +
    " float fib=.93+.1*vn(vec2(u*.25+seed*9.,v*2.5));\n" +
    " float lines=0.;\n" +
    " float body=pow(v,1.3)*fib*.85;\n" +
    " float core=smoothstep(.6,.97,v)*(1.-smoothstep(.95,1.,v)*.85)*.7;\n" +
    " float rim=smoothstep(0.,.22,v)*(1.-smoothstep(.94,1.,v)*.5);\n" +
    " float I=(body*(.35+.9*head)+core*1.5*(.4+head)+lines*.45*head)*rim*fa;\n" +
    " float t=clamp(I*.7,0.,1.);\n" +
    " vec3 col=mix(vec3(.9,.25,.02),vec3(1.,.72,.16),smoothstep(0.,.45,t));col=mix(col,vec3(1.,.96,.8),smoothstep(.55,1.,t));\n" +
    " o=vec4(col*I*1.05,1.);}\n" +
    "void main(){mainX();float l=max(o.r,max(o.g,o.b));o.rgb=mix(o.rgb,vec3(l*1.1,l*.1+o.b*.3,l*.06+o.b*.25),uR);}\n"

// ---- onda de choque (Vefects Easy Shockwaves) ----
private const val SVS = "#version 300 es\n" +
    "uniform mat4 uVP;uniform vec3 uC;uniform float uR,uRin;layout(location=0) in vec2 aP;out vec2 vUv;out vec3 vW;\n" +
    "void main(){float a=aP.x*6.2831853;float r=mix(uRin,1.,aP.y)*uR;vec3 w=uC+vec3(cos(a)*r,.02+aP.y*.0,sin(a)*r);vUv=vec2(aP.x*3.,aP.y);vW=w;gl_Position=uVP*vec4(w,1.);}\n"
private const val SFS = "#version 300 es\n" +
    "precision highp float;in vec2 vUv;in vec3 vW;out vec4 o;\n" +
    "uniform sampler2D tB,tN,tD,tM;uniform float uT,uOff,uEro,uEmi,uCA,uA,uPow,uMul;uniform vec3 uCol;\n" +
    "void main(){\n" +
    " vec2 uv=vUv;\n" +
    " vec2 dn=texture(tD,uT*vec2(.02,-.1)+uv).rg*2.-1.;\n" +
    " vec2 tu=uT*vec2(-.05,-.2)+uv;\n" +
    " vec2 bu=(dn-.5)*2.*.1+tu;\n" +
    " float ab=uCA*.001;\n" +
    " vec3 base=vec3(texture(tB,bu+ab).r,texture(tB,bu).r,texture(tB,bu-ab).r);\n" +
    " float nz=texture(tN,uT*vec2(-.07,-.3)+uv).r;\n" +
    " float m=texture(tM,vec2(.5,nz*uPow*.0+uv.y+uOff+nz*.35)).r;\n" +
    " float e=smoothstep(uEro,uEro+1.,m);\n" +
    " float it=clamp(pow(clamp(e,0.,1.),5.)*5.,0.,1.);\n" +
    " float edge=smoothstep(0.,.12,uv.y)*smoothstep(1.,.78,uv.y);\n" +
    " vec3 c=uCol*base*uEmi*it;float al=clamp(it*edge*uA,0.,1.);\n" +
    " o=vec4(c*al*1.6,al);}\n"


private class Pal(val c: V, val c2: V)

/** Efeitos 3D do combate: portagem literal do FX3 + rastro das lâminas + onda de choque do Visualizador. */
class Vfx {
    // ---------------------------------------------------------------- contexto (preenchido a cada quadro)
    private var oy = 0f
    private var yaw = 0f
    private val root = v3(0f, 0f, 0f)
    private val tips = arrayOf(v3(0f, 1f, 0f), v3(0f, 1f, 0f))      // centro da cabeça de cada machado [0]=esq [1]=dir
    private val blades = arrayOf(v3(0f, 0f, 0f), v3(0f, 0f, 0f))    // ponto mais baixo da lâmina
    private val grips = arrayOf(v3(0f, 1f, 0f), v3(0f, 1f, 0f))     // mão de cada machado
    private val trA = arrayOf(v3(0f, 0f, 0f), v3(0f, 0f, 0f))       // ponta de baixo da cabeça (rastro)
    private val trB = arrayOf(v3(0f, 0f, 0f), v3(0f, 0f, 0f))       // ponta de cima da cabeça (rastro)

    // pontos do machado no espaço do modelo (pose de repouso): [0]=esquerdo (hand_l), [1]=direito (hand_r)
    private val AX_A = arrayOf(floatArrayOf(0.6766f, 1.3288f, 0.2695f), floatArrayOf(-0.6489f, 1.391f, 0.2135f))
    private val AX_B = arrayOf(floatArrayOf(0.6469f, 1.3895f, 0.5239f), floatArrayOf(-0.6082f, 1.5117f, 0.4439f))
    private val AX_C = arrayOf(floatArrayOf(0.6582f, 1.3565f, 0.3846f), floatArrayOf(-0.6255f, 1.4454f, 0.3178f))
    private val AX_E1 = arrayOf(floatArrayOf(0.4707f, 1.3452f, 0.3388f), floatArrayOf(-0.4409f, 1.4044f, 0.2772f))
    private val AX_E2 = arrayOf(floatArrayOf(0.8457f, 1.3677f, 0.4304f), floatArrayOf(-0.81f, 1.4863f, 0.3583f))
    private val AX_G = arrayOf(floatArrayOf(0.6582f, 1.3565f, 0.3846f), floatArrayOf(-0.6655f, 1.342f, 0.1195f))
    private val JOINT = intArrayOf(15, 42)

    // ---- machado direito como malha 3D (arremessado na Skill 4): o Renderer lê axeHidden e axeMat
    private val axW = FloatArray(16)            // repouso -> mundo do machado direito na pose atual (charM * pele[42])
    private val axA0 = FloatArray(9)            // rotação do machado no instante do arremesso
    private val axRh = FloatArray(9)            // rotação ao fim do voo (cravado)
    private var axAx = floatArrayOf(1f, 0f, 0f) // eixo do giro (perpendicular à direção do voo)
    private var axE = 0f; private var axU = 0f
    private var axP = floatArrayOf(0f, 0f, 0f)  // centro da cabeça do machado (espaço Vfx)
    private var thrWall = 0f
    /** true enquanto o machado está fora da mão (voando, cravado ou voltando): o corpo é desenhado sem ele. */
    val axeHidden: Boolean get() = thrPh in 1..3
    /** repouso -> mundo do machado arremessado (coluna-maior), para usar como uM com ossos identidade. */
    val axeMat = FloatArray(16).also { M4.id(it) }
    private fun rotAx(ax: FloatArray, a: Float): FloatArray {
        val c = cos(a); val s = sin(a); val t = 1f - c; val x = ax[0]; val y = ax[1]; val z = ax[2]
        return floatArrayOf(t * x * x + c, t * x * y + s * z, t * x * z - s * y, t * x * y - s * z, t * y * y + c, t * y * z + s * x,
            t * x * z + s * y, t * y * z - s * x, t * z * z + c)
    }
    private fun mul3(A: FloatArray, B: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (c in 0 until 3) for (r in 0 until 3) o[c * 3 + r] = A[r] * B[c * 3] + A[3 + r] * B[c * 3 + 1] + A[6 + r] * B[c * 3 + 2]
        return o
    }
    private fun curA(): FloatArray = floatArrayOf(axW[0], axW[1], axW[2], axW[4], axW[5], axW[6], axW[8], axW[9], axW[10])
    private fun axeAng(): Float = if (thrPh == 1) axU * TWO_PI * 2.25f else if (thrPh == 3) -TWO_PI * 2f * (1f - axE) else 0f
    private fun buildAxe() {
        if (!axeHidden) return
        val R: FloatArray
        if (thrPh == 1) R = mul3(rotAx(axAx, axU * TWO_PI * 2.25f), axA0)
        else if (thrPh == 2) R = axRh
        else {
            val Rr = mul3(rotAx(axAx, -TWO_PI * 2f * (1f - axE)), curA()); val k = min(1f, axU * 4f)
            R = FloatArray(9) { i -> axRh[i] + (Rr[i] - axRh[i]) * k }
        }
        val C = AX_C[1]
        val px = axP[0]; val py = axP[1] + oy; val pz = axP[2]
        val rcx = R[0] * C[0] + R[3] * C[1] + R[6] * C[2]; val rcy = R[1] * C[0] + R[4] * C[1] + R[7] * C[2]; val rcz = R[2] * C[0] + R[5] * C[1] + R[8] * C[2]
        val m = axeMat
        m[0] = R[0]; m[1] = R[1]; m[2] = R[2]; m[3] = 0f; m[4] = R[3]; m[5] = R[4]; m[6] = R[5]; m[7] = 0f
        m[8] = R[6]; m[9] = R[7]; m[10] = R[8]; m[11] = 0f; m[12] = px - rcx; m[13] = py - rcy; m[14] = pz - rcz; m[15] = 1f
    }

    private val camE = v3(0f, 0f, 0f); private val camR = v3(1f, 0f, 0f); private val camU = v3(0f, 1f, 0f)
    private var tclock = 0f
    private var redT = 0f
    private var aimOn = false; private var aimY = 0f
    fun aimSet(on: Boolean, y: Float) { aimOn = on; aimY = y }

    private val palGold = Pal(c4(1f, .52f, .12f, 1f), c4(1f, .78f, .3f, 1f))
    private val palEarth = Pal(c4(1f, .42f, .1f, 1f), c4(1f, .7f, .25f, 1f))
    private val palUlt = Pal(c4(1f, .1f, .05f, 1f), c4(1f, .2f, .1f, 1f))
    private var pal: Pal = palGold
    fun setPal(n: String) { pal = if (n == "earth") palEarth else if (n == "ult") palUlt else palGold }
    private fun midc(): V = pal.c
    private var slt: ((V) -> V)? = null

    private fun W(x: Float, y: Float, z: Float): V = v3(x, y - oy, z)

    private val tmp3 = FloatArray(3)
    private fun bindToLocal(skin: FloatArray, j: Int, charM: FloatArray, p: FloatArray, out: V) {
        val o = j * 16
        val x = p[0]; val y = p[1]; val z = p[2]
        val mx = skin[o] * x + skin[o + 4] * y + skin[o + 8] * z + skin[o + 12]
        val my = skin[o + 1] * x + skin[o + 5] * y + skin[o + 9] * z + skin[o + 13]
        val mz = skin[o + 2] * x + skin[o + 6] * y + skin[o + 10] * z + skin[o + 14]
        M4.xf(charM, 0, mx, my, mz, tmp3, 0)
        out[0] = tmp3[0]; out[1] = tmp3[1] - oy; out[2] = tmp3[2]
    }

    /** Chamado depois de montar a pose: acha pelve, pontas e lâminas dos machados no mundo. */
    fun pose(skin: FloatArray, charM: FloatArray, pelX: Float, pelY: Float, pelZ: Float, oyIn: Float, yawIn: Float) {
        oy = oyIn; yaw = yawIn
        M4.xf(charM, 0, pelX, pelY, pelZ, tmp3, 0)
        root[0] = tmp3[0]; root[1] = 0f; root[2] = tmp3[2]
        M4.mul(axW, 0, charM, 0, skin, JOINT[1] * 16)
        val e1 = v3(0f, 0f, 0f); val e2 = v3(0f, 0f, 0f)
        for (k in 0 until 2) {
            val j = JOINT[k]
            bindToLocal(skin, j, charM, AX_C[k], tips[k])
            bindToLocal(skin, j, charM, AX_G[k], grips[k])
            bindToLocal(skin, j, charM, AX_A[k], trA[k])
            bindToLocal(skin, j, charM, AX_B[k], trB[k])
            bindToLocal(skin, j, charM, AX_E1[k], e1)
            bindToLocal(skin, j, charM, AX_E2[k], e2)
            var lo = trA[k]
            if (trB[k][1] < lo[1]) lo = trB[k]
            if (e1[1] < lo[1]) lo = e1
            if (e2[1] < lo[1]) lo = e2
            blades[k][0] = lo[0]; blades[k][1] = lo[1]; blades[k][2] = lo[2]
        }
    }

    // ---------------------------------------------------------------- motor de efeitos
    private abstract inner class FxO(var life: Float, var delay: Float = 0f) {
        var age = 0f
        open fun upd(dt: Float) {}
        open fun draw(pp: Float) {}
    }
    private val fxs = ArrayList<FxO>()
    private fun <T : FxO> add(o: T): T { o.age = 0f; fxs.add(o); return o }

    private val bAlpha = Bt(); private val bAdd = Bt()
    private val UVQ = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
    private val UP = v3(0f, 1f, 0f)

    private fun vtx(B: Bt, p: V, u: Float, v: Float, c: V, r: V) {
        if (B.n >= MAXV) return
        val a = B.a; val i = B.n * 13
        a[i] = p[0]; a[i + 1] = p[1] + oy; a[i + 2] = p[2]; a[i + 3] = u; a[i + 4] = v
        a[i + 5] = c[0]; a[i + 6] = c[1]; a[i + 7] = c[2]; a[i + 8] = c[3]
        a[i + 9] = r[0]; a[i + 10] = r[1]; a[i + 11] = r[2]; a[i + 12] = r[3]
        B.n++
    }
    private fun quad(B: Bt, p0: V, p1: V, p2: V, p3: V, uv: FloatArray, c: V, r: V) {
        vtx(B, p0, uv[0], uv[1], c, r); vtx(B, p1, uv[2], uv[3], c, r); vtx(B, p2, uv[4], uv[5], c, r)
        vtx(B, p0, uv[0], uv[1], c, r); vtx(B, p2, uv[4], uv[5], c, r); vtx(B, p3, uv[6], uv[7], c, r)
    }
    private fun billboard(B: Bt, c: V, w: Float, h: Float, rot: Float, col: V, pr: V) {
        val cs = cos(rot); val sn = sin(rot)
        val r = vadd(vmul(camR, cs), vmul(camU, sn)); val u = vsub(vmul(camU, cs), vmul(camR, sn))
        val R = vmul(r, w); val U = vmul(u, h)
        quad(B, vsub(vsub(c, R), U), vsub(vadd(c, R), U), vadd(vadd(c, R), U), vadd(vsub(c, R), U), UVQ, col, pr)
    }
    private fun streak(B: Bt, c: V, dir: V, len: Float, wid: Float, col: V, pr: V) {
        val ax = vnorm(dir); val view = vnorm(vsub(c, camE))
        val sd = vmul(vnorm(vcross(ax, view)), wid)
        val A = vmul(ax, len)
        quad(B, vsub(vsub(c, A), sd), vsub(vadd(c, A), sd), vadd(vadd(c, A), sd), vadd(vsub(c, A), sd), UVQ, col, pr)
    }
    private fun ground(B: Bt, c: V, R: Float, col: V, pr: V, y: Float) {
        quad(B, v3(c[0] - R, y, c[2] - R), v3(c[0] + R, y, c[2] - R), v3(c[0] + R, y, c[2] + R), v3(c[0] - R, y, c[2] + R), UVQ, col, pr)
    }
    private fun cylinder(B: Bt, c: V, r0: Float, r1: Float, h: Float, seg: Int, col: V, pr: V) {
        val rows = 5
        for (j in 0 until rows) {
            val w0 = j.toFloat() / rows; val w1 = (j + 1).toFloat() / rows
            val ra = lerp(r0, r1, w0); val rb = lerp(r0, r1, w1)
            for (i in 0 until seg) {
                val a0 = i.toFloat() / seg * TWO_PI; val a1 = (i + 1).toFloat() / seg * TWO_PI
                quad(B,
                    v3(c[0] + cos(a0) * ra, c[1] + w0 * h, c[2] + sin(a0) * ra), v3(c[0] + cos(a1) * ra, c[1] + w0 * h, c[2] + sin(a1) * ra),
                    v3(c[0] + cos(a1) * rb, c[1] + w1 * h, c[2] + sin(a1) * rb), v3(c[0] + cos(a0) * rb, c[1] + w1 * h, c[2] + sin(a0) * rb),
                    floatArrayOf(i.toFloat() / seg, w0, (i + 1).toFloat() / seg, w0, (i + 1).toFloat() / seg, w1, i.toFloat() / seg, w1), col, pr)
            }
        }
    }

    // ---------------------------------------------------------------- partículas (part) e sprites do atlas (spr)
    private val ENV0: (Float) -> Float = { q -> min(1f, q * 10f) * (1f - q * q) }
    private val ENVS: (Float) -> Float = { q -> min(1f, q * 12f) * (1f - q * q) }
    private val EN_FADE: (Float) -> Float = { q -> 1f - q }
    private val EN_SIN: (Float) -> Float = { q -> sin(PIF * q) }
    private val EN_ONE: (Float) -> Float = { _ -> 1f }
    private val easeO: (Float) -> Float = { q -> 1f - (1f - q) * (1f - q) * (1f - q) }

    private inner class Part(var p: V, var v: V, val g: Float, val dr: Float, lf: Float, dl: Float, val k: Int, val mode: Int,
                             val alpha: Boolean, val size: Float, val size1: Float, val wid: Float, val stretch: Float, val asp: Float,
                             val env: (Float) -> Float, val col: V, var rot: Float) : FxO(lf, dl) {
        val seed = Math.random().toFloat()
        override fun upd(dt: Float) {
            val d = exp(-dr * dt)
            v[1] = v[1] - g * dt; v[0] = v[0] * d; v[1] = v[1] * d; v[2] = v[2] * d
            for (i in 0 until 3) p[i] = p[i] + v[i] * dt
        }
        override fun draw(pp: Float) {
            val c = c4(col[0], col[1], col[2], col[3] * env(pp)); val pr = c4(pp, seed, 0f, k.toFloat())
            val s = lerp(size, size1, pp); val B = if (alpha) bAlpha else bAdd
            if (mode == 0) billboard(B, p, s, s * asp, rot, c, pr)
            else { val sp = vlen(v); streak(B, p, if (sp > .01f) v else UP, max(s, sp * stretch), if (wid > 0f) wid else s * .15f, c, pr) }
        }
    }
    private val BB = 0; private val ST = 1
    private fun part(p: V, v: V = v3(0f, 0f, 0f), g: Float = 0f, dr: Float = 0f, life: Float, k: Int = 0, mode: Int = 0,
                     alpha: Boolean = false, size: Float, size1: Float = size, wid: Float = -1f, stretch: Float = .05f, asp: Float = 1f,
                     env: (Float) -> Float = ENV0, col: V): Part =
        add(Part(p.copyOf(), v.copyOf(), g, dr, life, 0f, k, mode, alpha, size, size1, wid, stretch, asp, env, col, rnd(0f, 6.28f)))

    private inner class Spr(var p: V, val v: V, val g: Float, val dr: Float, lf: Float, dl: Float, val c0: Int, val nf: Int, val m: Int,
                            val s: Float, val s1: Float, val ease: ((Float) -> Float)?, var rot: Float, val rotv: Float, val col: V,
                            val env: (Float) -> Float, val gd: Boolean, val gy: Float, val st: Boolean, var dir: V?, val asp: Float,
                            val at: ((Spr) -> V)?, val tick: ((Spr, Float) -> Unit)?) : FxO(lf, dl) {
        override fun upd(dt: Float) {
            val d = exp(-dr * dt)
            v[1] = v[1] - g * dt
            for (i in 0 until 3) { v[i] = v[i] * d; p[i] = p[i] + v[i] * dt }
            rot += rotv * dt
            val tk = tick
            if (tk != null) tk(this, dt)
        }
        override fun draw(pp: Float) {
            if (pp >= 1f) return
            val cell = c0 + (if (nf > 1) min(nf - 1, floor(pp * nf).toInt()) else 0)
            val es = ease
            val sz = lerp(s, s1, if (es != null) es(pp) else pp); val a = col[3] * env(pp)
            if (a < .003f) return
            val cc = c4(col[0], col[1], col[2], a); val pr = c4(cell.toFloat(), 0f, m.toFloat(), 16f)
            val L = if (m == 1) bAlpha else bAdd
            val atf = at
            val pos = if (atf != null) atf(this) else p
            if (gd) {
                val A = v3(cos(rot) * sz, 0f, sin(rot) * sz); val Bv = v3(-sin(rot) * sz, 0f, cos(rot) * sz); val c = v3(pos[0], gy, pos[2])
                quad(L, vsub(vsub(c, A), Bv), vsub(vadd(c, A), Bv), vadd(vadd(c, A), Bv), vadd(vsub(c, A), Bv), UVQ, cc, pr)
            } else if (st) {
                val dd = dir
                val d = if (dd != null) dd else (if (vlen(v) > .01f) v else UP)
                streak(L, pos, d, sz * (if (asp > 0f) asp else 3f), sz, cc, pr)
            } else billboard(L, pos, sz, sz * (if (asp > 0f) asp else 1f), rot, cc, pr)
        }
    }
    private fun spr(p: V, life: Float, c0: Int, s: Float, s1: Float = s, v: V = v3(0f, 0f, 0f), g: Float = 0f, dr: Float = 0f, delay: Float = 0f,
                    nf: Int = 1, m: Int = 0, ease: ((Float) -> Float)? = null, rot: Float = rnd(0f, 6.28f), rotv: Float = 0f,
                    col: V = c4(1f, 1f, 1f, 1f), env: (Float) -> Float = ENVS, gd: Boolean = false, gy: Float = .02f, st: Boolean = false,
                    dir: V? = null, asp: Float = 0f, at: ((Spr) -> V)? = null, tick: ((Spr, Float) -> Unit)? = null): Spr {
        val tf = slt
        val cc = if (tf != null) tf(col) else col
        return add(Spr(p.copyOf(), v.copyOf(), g, dr, life, delay, c0, nf, m, s, s1, ease, rot, rotv, cc, env, gd, gy, st, dir, asp, at, tick))
    }
    private fun later(d: Float, fn: () -> Unit) {
        add(object : FxO(d + .05f) {
            var done = false
            override fun upd(dt: Float) { if (!done && age >= d) { done = true; fn() } }
        })
    }

    // ================================================================ EFEITOS (cópia do FX3 do visualizador)
    private fun dustFx(c: V, s: Float, n: Int) {
        for (i in 0 until n) {
            val a = rnd(0f, 6.28f); val r = rnd(.2f, .6f) * s
            part(p = v3(c[0] + cos(a) * r, .12f, c[2] + sin(a) * r), v = v3(cos(a) * rnd(1.2f, 2.8f) * s, rnd(.3f, 1f), sin(a) * rnd(1.2f, 2.8f) * s),
                dr = 1.6f, life = rnd(.9f, 1.5f), k = 5, alpha = true, size = rnd(.45f, .8f) * s, size1 = rnd(1f, 1.6f) * s, env = EN_ONE,
                col = c4(.5f, .42f, .34f, .8f))
        }
    }

    // impacto (risco de luz + faíscas em cone + brasas + fumaça)
    private fun impactFx(h: V, s: Float, dir: V?) {
        val c = midc(); val c2 = pal.c2
        val d = if (dir != null) vnorm(v3(dir[0], 0f, dir[2])) else vnorm(vsub(h, v3(root[0], h[1], root[2])))
        val sd = vnorm(vcross(d, UP))
        add(object : FxO(.16f) {
            override fun draw(pp: Float) {
                val q = age / life; if (q > 1f) return
                val L = (1.1f + 1.4f * q) * s; val w = .09f * s * (1f - q)
                streak(bAdd, h, sd, L, w * 2.4f, c4(c[0], c[1], c[2], .7f * (1f - q)), c4(0f, 0f, 0f, 4f))
                streak(bAdd, h, sd, L * .8f, w, c4(1f, .97f, .9f, 1f - q), c4(0f, 0f, 0f, 4f))
            }
        })
        val n = Math.round(16f * s)
        for (i in 0 until n) {
            val a = rnd(-1.1f, 1.1f); val e = rnd(-.3f, .9f); val sp = rnd(5f, 13f) * sqrt(s)
            val v = vadd(vadd(vmul(d, cos(a) * sp), vmul(sd, sin(a) * sp)), v3(0f, e * sp * .7f, 0f))
            part(p = h, v = v, g = 9f, dr = 2.6f, life = rnd(.25f, .55f), k = 4, mode = ST, size = .32f, wid = .028f, stretch = .05f,
                col = if (i % 3 != 0) c4(c2[0], c2[1], c2[2], 1f) else c4(1f, .95f, .85f, 1f))
        }
        for (i in 0 until Math.round(7f * s)) {
            part(p = vadd(h, v3(rnd(-.2f, .2f), rnd(-.2f, .2f), rnd(-.2f, .2f))), v = v3(rnd(-1.4f, 1.4f) + d[0] * 1.5f, rnd(.6f, 2.6f), rnd(-1.4f, 1.4f) + d[2] * 1.5f),
                g = -.6f, dr = 1.4f, life = rnd(.7f, 1.4f), k = 0, size = rnd(.05f, .1f), col = c4(1f, .5f, .15f, 1f),
                env = { q -> (1f - q) * (.6f + .4f * sin(q * 40f)) })
        }
        for (i in 0 until 3) {
            part(p = vadd(h, vmul(d, .2f)), v = v3(d[0] * rnd(.5f, 1.5f) + rnd(-.4f, .4f), rnd(.2f, .8f), d[2] * rnd(.5f, 1.5f) + rnd(-.4f, .4f)),
                dr = 1.8f, life = rnd(.6f, 1f), k = 5, alpha = true, size = rnd(.35f, .5f) * s, size1 = rnd(.9f, 1.3f) * s, env = EN_ONE,
                col = c4(.18f, .15f, .14f, .45f))
        }
    }

    // ----- mancha de queimado no chão
    private fun scorchS(c: V, R: Float, n: Int, life: Float) {
        for (i in 0 until n) {
            val a = rnd(0f, 6.28f); val r = if (i > 0) rnd(.2f, .6f) * R else 0f
            spr(p = v3(c[0] + cos(a) * r, 0f, c[2] + sin(a) * r), gd = true, gy = .013f + i * .0006f, life = life, c0 = C_SCORCH + (i % 3), m = 1,
                s = R * (if (i > 0) rnd(.45f, .7f) else 1f), rot = rnd(0f, 6.28f), col = c4(.03f, .02f, .02f, .95f),
                env = { q -> min(1f, q * 30f) * (1f - sstep(.7f, 1f, q)) })
        }
        spr(p = v3(c[0], 0f, c[2]), gd = true, gy = .022f, life = 1.6f, c0 = C_SCORCH, m = 0, s = R * .9f,
            col = if (pal === palUlt) c4(1f, .06f, .03f, .9f) else c4(1f, .35f, .08f, .9f), env = { q -> 1f - sstep(0f, 1f, q) })
    }

    // ----- pancada da Ultimate no chão (só vermelho)
    private fun ultSlam(c: V, P: Float) {
        val g = v3(c[0], 0f, c[2])
        spr(p = g, gd = true, gy = .026f, life = 1.1f * P, c0 = C_GMASK, s = 1.2f * P, s1 = 2.2f * P, ease = easeO, col = c4(1f, .05f, .02f, .7f), env = { q -> 1f - sstep(.2f, 1f, q) })
        spr(p = g, gd = true, gy = .03f, life = .5f, c0 = C_GMASK, s = .5f * P, s1 = .9f * P, col = c4(1f, .25f, .18f, .5f), env = EN_FADE)
        scorchS(c, 1.1f * P, 3, 5f)
        spr(p = g, gd = true, gy = .04f, life = .55f, c0 = C_RING, s = .3f, s1 = 3f * P, ease = easeO, rot = rnd(0f, 6f), col = c4(1f, .08f, .04f, 1f), env = { q -> 1f - q * q })
        spr(p = g, gd = true, gy = .045f, life = .8f, delay = .06f, c0 = C_LRING, s = .3f, s1 = 2.4f * P, ease = easeO, rotv = 3f, col = c4(1f, .15f, .08f, .8f), env = EN_FADE)
        spr(p = g, gd = true, gy = .05f, life = .45f, c0 = C_TWIRL + 2, s = .6f * P, s1 = 1.8f * P, rotv = -9f, col = c4(1f, .12f, .06f, .9f), env = EN_FADE)
        val pm = min(P, 1.6f)
        // riscos de energia correndo pelo chão
        val nr = Math.round(10f * pm)
        for (i in 0 until nr) {
            val a = i.toFloat() / nr * 6.283f + rnd(-.2f, .2f); val sp = rnd(5f, 9f) * P
            spr(p = v3(c[0] + cos(a) * .3f, .06f, c[2] + sin(a) * .3f), v = v3(cos(a) * sp, 0f, sin(a) * sp), dr = 4f, life = rnd(.3f, .45f), st = true, c0 = C_TRAIL63, m = 2,
                s = .07f * P, asp = 6f, col = c4(1f, .1f, .06f, 1f), env = EN_FADE)
        }
        if (slt == null) {
            // espinhos de sangue subindo
            val ns = Math.round(7f * pm)
            for (i in 0 until ns) {
                val a = rnd(0f, 6.28f); val r = rnd(.3f, 1.3f) * P
                spr(p = v3(c[0] + cos(a) * r, .2f, c[2] + sin(a) * r), v = v3(0f, rnd(6f, 10f) * sqrt(P), 0f), dr = 3f, life = rnd(.3f, .45f), delay = rnd(0f, .08f), st = true, c0 = C_TRAIL63, m = 2,
                    s = rnd(.07f, .12f) * P, asp = rnd(6f, 9f), col = c4(1f, .12f, .08f, .65f), env = { q -> sin(PIF * min(1f, q * 1.4f)) })
            }
            for (i in 0 until Math.round(3f * pm)) {
                val a = rnd(0f, 6.28f); val r = rnd(.2f, .8f) * P
                spr(p = v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), v = v3(cos(a) * .8f, rnd(3f, 5f), sin(a) * .8f), dr = 1.5f, life = rnd(.4f, .6f), st = true, c0 = C_TRAIL87, m = 2,
                    s = rnd(.2f, .3f) * P, asp = 2.6f, col = c4(1f, .08f, .06f, .6f), env = EN_SIN)
            }
        }
        // estilhaços e brasas
        for (i in 0 until Math.round(10f * pm)) {
            val a = rnd(0f, 6.28f); val sp = rnd(3f, 6f) * P
            spr(p = v3(c[0], .25f, c[2]), v = v3(cos(a) * sp, rnd(2f, 5f), sin(a) * sp), g = 7f, dr = 1.2f, life = rnd(.4f, .7f), st = true, c0 = C_FLASH31, m = 2,
                s = rnd(.15f, .22f), asp = 1.5f, col = c4(1f, .3f, .26f, 1f), env = EN_FADE)
        }
        for (i in 0 until Math.round(24f * P)) {
            val a = rnd(0f, 6.28f); val sp = rnd(1f, 4f) * P
            spr(p = v3(c[0] + cos(a) * .4f, .15f, c[2] + sin(a) * .4f), v = v3(cos(a) * sp, rnd(1.5f, 4f), sin(a) * sp), g = 2f, dr = 1f, life = rnd(.6f, 1.2f), c0 = C_GLOW,
                s = rnd(.05f, .09f), col = c4(1f, .05f, .02f, 1f), env = EN_FADE)
        }
        // fumaça vermelha baixa abrindo
        for (i in 0 until Math.round(7f * P)) {
            val a = i / 7f * 6.283f
            spr(p = v3(c[0] + cos(a) * .5f, .3f, c[2] + sin(a) * .5f), v = v3(cos(a) * 2.4f * P, .3f, sin(a) * 2.4f * P), dr = 2f, life = rnd(.8f, 1.1f), c0 = C_SMOKE24, m = 1,
                s = .6f * P, s1 = 1.3f * P, ease = easeO, rotv = rnd(-1f, 1f), col = c4(.45f, 0f, 0f, .6f), env = EN_SIN)
        }
        if (P > 1.9f) {
            spr(p = v3(c[0], .05f, c[2]), life = .7f, c0 = C_MOTION, s = 1.6f, s1 = 2.2f, rot = 0f, asp = 1.4f, col = c4(1f, .1f, .05f, .9f), env = EN_FADE,
                at = { sp -> v3(c[0], 1.4f * min(1f, sp.age * 6f), c[2]) })
        }
    }

    // ----- camada destrutiva extra da pancada
    private fun ultSlamBig(c: V, P: Float) {
        ultSlam(c, P)
        val tf = slt
        val K1 = if (tf != null) tf(c4(1f, .06f, .03f, 1f)) else c4(1f, .06f, .03f, 1f)
        val pm = min(P, 1.6f)
        add(object : FxO(.55f) {
            override fun draw(pp: Float) {
                val q = age / life; if (q > 1f) return
                val e = 1f - (1f - q).pow(2.4f)
                cylinder(bAdd, v3(c[0], 0f, c[2]), .3f + 1.9f * P * e, .2f + 1.4f * P * e, (.6f + 1.6f * e) * P * .8f, 32, c4(K1[0], K1[1], K1[2], .7f * (1f - q)), c4(q, .4f, 0f, 9f))
            }
        })
        add(object : FxO(.4f, .05f) {
            override fun draw(pp: Float) {
                val q = (age - delay) / life; if (q < 0f || q > 1f) return
                ground(bAdd, c, 2.6f * P, c4(K1[0], K1[1], K1[2], .6f), c4(q, .3f, 0f, 2f), .02f)
            }
        })
        if (tf == null) {
            for (i in 0 until Math.round(3f * pm)) {
                val a = rnd(0f, 6.28f); val r = rnd(0f, .7f) * P
                spr(p = v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), v = v3(cos(a) * .6f, rnd(7f, 11f) * sqrt(P), sin(a) * .6f), dr = 2.6f, life = rnd(.45f, .65f), st = true, c0 = C_TRAIL87, m = 2,
                    s = rnd(.3f, .45f) * P * .7f, asp = 3.2f, col = c4(1f, .07f, .04f, .5f), env = { q -> sin(PIF * min(1f, q * 1.3f)) })
            }
            for (i in 0 until Math.round(3f * pm)) {
                val a = rnd(0f, 6.28f); val r = rnd(.6f, 1.6f) * P
                spr(p = v3(c[0] + cos(a) * r, .08f, c[2] + sin(a) * r), gd = true, gy = .06f, life = .22f, delay = rnd(0f, .15f), c0 = C_SPARK + (i % 2), m = 2,
                    s = rnd(.7f, 1.1f) * P * .6f, rot = rnd(0f, 6.28f), col = c4(1f, .15f, .1f, 1f), env = EN_FADE)
            }
            for (i in 0 until Math.round(3f * P)) {
                val a = rnd(0f, 6.28f)
                spr(p = v3(c[0] + cos(a) * .5f, rnd(.4f, 1.4f), c[2] + sin(a) * .5f), life = .16f, delay = rnd(0f, .12f), c0 = C_SPARK + (i % 2), m = 2,
                    s = rnd(.6f, .9f) * P * .6f, rot = rnd(0f, 6.28f), col = c4(1f, .15f, .1f, 1f), env = EN_FADE)
            }
        }
        // nuvem de poeira abrindo em anel
        val n12 = Math.round(12f * pm)
        for (i in 0 until n12) {
            val a = i.toFloat() / n12 * 6.283f + rnd(-.1f, .1f); val sp = rnd(3f, 5f) * P
            spr(p = v3(c[0] + cos(a) * .6f, .25f, c[2] + sin(a) * .6f), v = v3(cos(a) * sp, rnd(.3f, 1.2f), sin(a) * sp), dr = 2.4f, life = rnd(1f, 1.5f), c0 = C_SMOKE + (i % 5), m = 1,
                s = .5f * P, s1 = 1.2f * P, ease = easeO, rotv = rnd(-1f, 1f), col = c4(.28f, .03f, .03f, .7f), env = { q -> min(1f, q * 8f) * (1f - q) })
        }
        // brasas que sobem devagar
        for (i in 0 until Math.round(20f * P)) {
            val a = rnd(0f, 6.28f); val r = rnd(.2f, 1.6f) * P
            spr(p = v3(c[0] + cos(a) * r, .1f, c[2] + sin(a) * r), v = v3(rnd(-.3f, .3f), rnd(.6f, 2f), rnd(-.3f, .3f)), life = rnd(1f, 2f), delay = rnd(0f, .3f), c0 = C_GLOW,
                s = rnd(.04f, .08f), col = c4(1f, .08f, .02f, 1f), env = { q -> (1f - q) * (.6f + .4f * sin(q * 30f)) })
        }
    }

    private fun goldT(col: V): V {
        val m = max(col[0], max(col[1], col[2]))
        if (m < .1f) return col
        if (m < .55f) return c4(.17f, .13f, .09f, col[3] * .8f)
        return c4(1f, min(1f, .4f + col[1] * 1.2f), min(1f, .05f + col[2] * .5f), col[3] * .7f)
    }
    private fun earthT(col: V): V {
        val m = max(col[0], max(col[1], col[2]))
        if (m < .1f) return col
        if (m < .55f) return c4(.2f, .15f, .1f, col[3] * .85f)
        return c4(1f, min(1f, .3f + col[1] * 1.2f), min(1f, .04f + col[2] * .5f), col[3] * .7f)
    }
    private fun tintSlam(c: V, P: Float, T: (V) -> V) {
        slt = T
        try { ultSlamBig(c, P) } finally { slt = null }
    }

    // ----- Aura de sangue (Hovl Studio, Character Auras 3): segue o personagem
    fun aura(life: Float) {
        val fi: (Float) -> Float = { q -> sstep(0f, .05f, q) * (1f - sstep(.92f, 1f, q)) }
        spr(p = v3(0f, 0f, 0f), at = { _ -> root }, gd = true, gy = .03f, life = life, c0 = C_GMASK, s = 1.5f, col = c4(1f, .07f, 0f, .75f),
            env = { q -> fi(q) * (.8f + .2f * sin(q * life * 9f)) })
        spr(p = v3(0f, 0f, 0f), at = { _ -> root }, gd = true, gy = .034f, life = life, c0 = C_GMASK, s = .8f, col = c4(1f, .25f, .15f, .6f), env = fi)
        add(object : FxO(life) {
            var aTr = 0f; var aLn = 0f; var aFs = 0f; var aFl = 0f; var aSm = 0f; var aSp = 0f
            override fun upd(dt: Float) {
                val q = age / life; if (q > .93f) return
                val c = root
                fun R(a: Float, r: Float, y: Float): V = v3(c[0] + cos(a) * r, y, c[2] + sin(a) * r)
                aTr += dt * 22f
                while (aTr > 1f) {
                    aTr -= 1f
                    val a = rnd(0f, 6.28f); val e = rnd(-.3f, .9f); val p = R(a, .25f * cos(e), 1f + .45f * sin(e)); val sp = rnd(1.8f, 2.6f)
                    spr(p = p, v = v3(cos(a) * sp * .55f, sp, sin(a) * sp * .55f), dr = .5f, life = rnd(.28f, .36f), st = true, c0 = C_TRAIL87, m = 2, s = rnd(.16f, .24f), asp = 2.4f,
                        col = c4(1f, .12f, .1f, 1f), env = EN_SIN)
                }
                aLn += dt * 30f
                while (aLn > 1f) {
                    aLn -= 1f
                    val a = rnd(0f, 6.28f); val p = R(a, rnd(.25f, .6f), rnd(.1f, 2.1f))
                    spr(p = p, v = v3(0f, rnd(2.5f, 4f), 0f), life = .25f, st = true, c0 = C_TRAIL63, m = 2, s = rnd(.06f, .1f), asp = rnd(4f, 7f), col = c4(1f, .11f, .11f, 1f), env = EN_SIN)
                }
                aFs += dt * 10f
                while (aFs > 1f) {
                    aFs -= 1f
                    val a = rnd(0f, 6.28f); val p = R(a, rnd(.2f, .5f), rnd(.4f, 2f))
                    spr(p = p, v = v3(cos(a) * .6f, rnd(1.5f, 2.5f), sin(a) * .6f), life = .3f, st = true, c0 = C_FLASH31, m = 2, s = rnd(.22f, .3f), asp = 1.6f, col = c4(1f, .4f, .36f, 1f), env = EN_FADE)
                }
                aFl += dt * 5f
                while (aFl > 1f) {
                    aFl -= 1f
                    val a = rnd(0f, 6.28f)
                    spr(p = R(a, rnd(.1f, .45f), rnd(.3f, 1.9f)), life = .2f, c0 = C_FLARE21, s = rnd(.8f, 1.3f), rot = rnd(0f, 6.28f), col = c4(1f, .34f, .29f, .9f), env = EN_SIN)
                }
                aSm += dt * 6f
                while (aSm > 1f) {
                    aSm -= 1f
                    val a = rnd(0f, 6.28f)
                    spr(p = R(a, rnd(.1f, .4f), rnd(.4f, 1.6f)), v = v3(0f, .5f, 0f), life = .7f, c0 = C_SMOKE24, m = 1, s = .8f, s1 = 1.2f, rotv = rnd(-1f, 1f), col = c4(.5f, 0f, 0f, .55f), env = EN_SIN)
                }
                aSp += dt * 14f
                while (aSp > 1f) {
                    aSp -= 1f
                    val a = rnd(0f, 6.28f)
                    spr(p = R(a, rnd(.3f, .7f), rnd(.1f, 1.8f)), v = v3(rnd(-.4f, .4f), rnd(.5f, 1.4f), rnd(-.4f, .4f)), life = .5f, c0 = C_GLOW, s = .07f, col = c4(1f, 0f, .01f, 1f), env = EN_FADE)
                }
            }
        })
    }

    // ----- Skill 1: X detalhado voando até o alvo (xBlade2)
    private class Gh(val p: V, val r: Float, var a: Float)

    fun xBlade(tx: Float, ty: Float, tz: Float, T: Float) {
        val fl0 = v3(sin(yaw), 0f, cos(yaw))
        val ay = cl((tips[0][1] + tips[1][1]) / 2f, .7f, 1.3f)
        val from = v3(root[0] + fl0[0] * .35f, ay, root[2] + fl0[2] * .35f)
        xBlade2(from, W(tx, ty, tz), T)
    }
    private fun xBlade2(from: V, to: V, T: Float) {
        val d0 = vsub(to, from); val fl = vnorm(v3(d0[0], 0f, d0[2])); val rr = v3(fl[2], 0f, -fl[0]); val up = UP
        val T87 = C_TRAIL87; val T63 = C_TRAIL63; val GM = C_GMASK; val F21 = C_FLARE21; val SPK = C_SPARK
        val ghosts = ArrayList<Gh>()
        fun armsAt(r: Float): Array<V> {
            val a0 = .785f + r; val a1 = -.785f + r
            return arrayOf(vadd(vmul(rr, cos(a0)), vmul(up, sin(a0))), vadd(vmul(rr, cos(a1)), vmul(up, sin(a1))))
        }
        fun strip(B: Bt, p: V, a: V, L: Float, h: Float, col: V, cell: Int, taper: Float) {
            val wv = vnorm(vcross(a, fl)); val N = 10
            for (i in 0 until N) {
                val s0 = i.toFloat() / N * 2f - 1f; val s1 = (i + 1).toFloat() / N * 2f - 1f
                val t0 = (1f - s0 * s0).pow(taper); val t1 = (1f - s1 * s1).pow(taper)
                val P0 = vadd(p, vmul(a, s0 * L)); val P1 = vadd(p, vmul(a, s1 * L))
                val h0 = vmul(wv, h * max(t0, .08f)); val h1 = vmul(wv, h * max(t1, .08f))
                quad(B, vsub(P0, h0), vsub(P1, h1), vadd(P1, h1), vadd(P0, h0), floatArrayOf(s0, -1f, s1, -1f, s1, 1f, s0, 1f), col, c4(cell.toFloat(), 0f, 2f, 16f))
            }
        }
        fun X(B: Bt, p: V, roll: Float, L: Float, H: Float, A: Float) {
            for (a in armsAt(roll)) {
                strip(B, p, a, L * 1.05f, H * 3f, c4(1f, .4f, .06f, .2f * A), T87, .5f)
                strip(B, p, a, L, H * 1.3f, c4(1f, .58f, .12f, .6f * A), T87, .45f)
                strip(B, p, a, L, H, c4(1f, .76f, .24f, A), T87, .4f)
                strip(B, p, a, L * .97f, H * .42f, c4(1f, .93f, .62f, A), T63, .35f)
                strip(B, p, a, L * 1.12f, H * .11f, c4(1f, 1f, .86f, .95f * A), T63, .3f)
            }
            for (a in armsAt(roll)) for (s in floatArrayOf(-1f, 1f)) {
                val tp = vadd(p, vmul(a, s * L * 1.02f))
                billboard(B, tp, .2f, .2f, 0f, c4(1f, .5f, .1f, .5f * A), c4(GM.toFloat(), 0f, 2f, 16f))
            }
            billboard(B, p, .8f, .8f, 0f, c4(1f, .55f, .12f, .5f * A), c4(GM.toFloat(), 0f, 2f, 16f))
        }
        add(object : FxO(T + .02f) {
            var pp: V? = null; var roll = 0f; var gh = 0f; var acc = 0f; var sk = 0f
            override fun upd(dt: Float) {
                val q = min(1f, age / T)
                val p = vadd(from, vmul(d0, q)); pp = p; roll = q * .45f
                gh += dt
                if (gh > .025f) { gh = 0f; ghosts.add(Gh(p.copyOf(), roll, 0f)); if (ghosts.size > 5) ghosts.removeAt(0) }
                for (g in ghosts) g.a += dt
                acc += dt * 70f
                while (acc > 1f) {
                    acc -= 1f
                    val ar = armsAt(roll)[if (Math.random() < .5) 0 else 1]; val ps = vadd(p, vmul(ar, rnd(-1f, 1f) * .9f))
                    if (Math.random() < .55) spr(p = ps, v = vadd(vmul(fl, -rnd(.6f, 1.8f)), v3(0f, rnd(0f, .5f), 0f)), dr = 2f, life = rnd(.35f, .65f), c0 = C_GLOW, s = rnd(.025f, .05f), col = c4(1f, .62f, .15f, 1f), env = EN_FADE)
                    else spr(p = ps, v = vmul(fl, -rnd(.3f, .9f)), life = rnd(.35f, .55f), c0 = C_WISP, m = 2, s = rnd(.1f, .18f), s1 = rnd(.3f, .45f), rotv = rnd(-2f, 2f), col = c4(1f, .6f, .15f, .2f), env = EN_SIN)
                }
                sk += dt * 16f
                while (sk > 1f) {
                    sk -= 1f
                    val ar = armsAt(roll)[if (Math.random() < .5) 0 else 1]; val ps = vadd(p, vmul(ar, rnd(-.9f, .9f)))
                    spr(p = ps, life = .07f, c0 = SPK + (if (Math.random() < .5) 0 else 1), m = 2, s = rnd(.16f, .26f), rot = rnd(0f, 6.28f), col = c4(1f, .75f, .3f, .9f), env = EN_FADE)
                }
            }
            override fun draw(pg: Float) {
                val p = pp; if (p == null || age > T) return
                val fl2 = 1f + .05f * sin(age * 80f)
                for (g in ghosts) { val a = max(0f, .32f - g.a * 2.2f); if (a > .02f) X(bAdd, g.p, g.r, .85f, .22f, a) }
                X(bAdd, p, roll, .95f * fl2, .26f, 1f)
                for (a in armsAt(roll)) for (s in floatArrayOf(-1f, 1f))
                    billboard(bAdd, vadd(p, vmul(a, s * .97f)), .26f, .26f, age * 10f * s, c4(1f, .82f, .45f, .9f), c4(F21.toFloat(), 0f, 2f, 16f))
                billboard(bAdd, p, .45f, .45f, age * 7f, c4(1f, .85f, .5f, .9f), c4(F21.toFloat(), 0f, 2f, 16f))
            }
        })
        later(T) {
            val r0 = .45f
            add(object : FxO(.55f) {
                override fun draw(pg: Float) {
                    val q = age / life; if (q > 1f) return
                    val e = 1f - (1f - q).pow(3f); val A = if (q < .15f) 1f else (1f - (q - .15f) / .85f).pow(1.5f)
                    X(bAdd, to, r0, .95f + e * .4f, .26f * (1f + e * .35f), A)
                }
            })
            for (i in 0 until 10) {
                val ar = armsAt(r0)[i % 2]; val ps = vadd(to, vmul(ar, rnd(-.9f, .9f)))
                spr(p = ps, v = v3(rnd(-.2f, .2f), rnd(.2f, .6f), rnd(-.2f, .2f)), delay = rnd(.1f, .3f), life = rnd(.5f, .8f), c0 = C_WISP, m = 2, s = rnd(.12f, .2f), s1 = rnd(.35f, .5f),
                    rotv = rnd(-2f, 2f), col = c4(1f, .6f, .15f, .18f), env = EN_SIN)
            }
        }
    }

    // ----- Skill 2: aura de guarda nas lâminas, faísca de aparo e onda dourada 360°
    fun guardAura(life: Float) {
        val GM = C_GMASK; val F21 = C_FLARE21
        add(object : FxO(life) {
            override fun draw(pp: Float) {
                val q = age / life; if (q > 1f) return
                val A = min(1f, age / .1f) * (1f - sstep(.75f, 1f, q)); val T = age
                for (k in 0 until 2) {
                    val tp = tips[k]
                    billboard(bAdd, tp, .42f, .42f, 0f, c4(1f, .5f, .1f, .5f * A), c4(GM.toFloat(), 0f, 2f, 16f))
                    billboard(bAdd, tp, .26f, .26f, T * 7f * (if (k == 1) 1f else -1f), c4(1f, .85f, .45f, .85f * A), c4(F21.toFloat(), 0f, 2f, 16f))
                }
            }
        })
    }
    fun deflect() {
        val p = v3((tips[0][0] + tips[1][0]) / 2f, (tips[0][1] + tips[1][1]) / 2f, (tips[0][2] + tips[1][2]) / 2f)
        spr(p = p, life = .12f, c0 = C_FLARE21, s = .5f, s1 = .9f, rot = rnd(0f, 6f), col = c4(1f, .9f, .6f, 1f), env = EN_FADE)
        spr(p = p, life = .35f, c0 = C_RING, s = .1f, s1 = .9f, ease = easeO, col = c4(1f, .75f, .3f, .95f), env = { q -> 1f - q * q })
        spr(p = p, life = .4f, c0 = C_GMASK, s = .3f, s1 = .8f, col = c4(1f, .55f, .12f, .6f), env = EN_FADE)
        for (i in 0 until 16) {
            val a = rnd(0f, 6.28f); val e = rnd(-.4f, .9f)
            spr(p = p, v = v3(cos(a) * rnd(3f, 7f), e * 5f + 1f, sin(a) * rnd(3f, 7f)), g = 8f, dr = 1.6f, life = rnd(.3f, .55f), st = true, c0 = C_FLASH31, m = 2,
                s = rnd(.1f, .16f), asp = 1.6f, col = c4(1f, .8f, .35f, 1f), env = EN_FADE)
        }
        for (i in 0 until 10) {
            val a = rnd(0f, 6.28f)
            spr(p = p, v = v3(cos(a) * rnd(4f, 8f), rnd(-1f, 3f), sin(a) * rnd(4f, 8f)), dr = 5f, life = rnd(.15f, .25f), st = true, c0 = C_TRAIL63, m = 2,
                s = .05f, asp = 7f, col = c4(1f, .85f, .45f, .9f), env = EN_FADE)
        }
        for (i in 0 until 14) {
            spr(p = vadd(p, v3(rnd(-.2f, .2f), rnd(-.2f, .2f), rnd(-.2f, .2f))), v = v3(rnd(-1f, 1f), rnd(.3f, 1.8f), rnd(-1f, 1f)), life = rnd(.5f, 1f), c0 = C_GLOW,
                s = rnd(.03f, .05f), col = c4(1f, .62f, .15f, 1f), env = EN_FADE)
        }
    }
    fun counterWave() {
        val c = v3(root[0], 0f, root[2])
        tintSlam(v3(c[0], 0f, c[2]), .75f, { v -> goldT(v) })
        val T87 = C_TRAIL87; val T63 = C_TRAIL63
        add(object : FxO(.42f) {
            override fun draw(pp: Float) {
                val q = age / life; if (q > 1f) return
                val e = 1f - (1f - q).pow(3f); val R = .6f + 2.4f * e; val A = (1f - q).pow(1.3f); val N = 48
                for (lay in 0 until 3) {
                    val y = .95f
                    val h = if (lay == 0) .3f else if (lay == 1) .14f else .05f
                    val col = if (lay == 0) c4(1f, .5f, .1f, .4f * A) else if (lay == 1) c4(1f, .75f, .25f, A) else c4(1f, .95f, .7f, A)
                    val cell = if (lay == 2) T63 else T87
                    for (i in 0 until N) {
                        val a0 = i.toFloat() / N * TWO_PI + q * 2f; val a1 = (i + 1).toFloat() / N * TWO_PI + q * 2f
                        val P0 = v3(c[0] + cos(a0) * R, y, c[2] + sin(a0) * R); val P1 = v3(c[0] + cos(a1) * R, y, c[2] + sin(a1) * R)
                        val u = (i % 12).toFloat() / 12f; val u1 = ((i % 12) + 1).toFloat() / 12f; val hv = v3(0f, h * (1f - q * .6f), 0f)
                        quad(bAdd, vsub(P0, hv), vsub(P1, hv), vadd(P1, hv), vadd(P0, hv), floatArrayOf(u * 2f - 1f, -1f, u1 * 2f - 1f, -1f, u1 * 2f - 1f, 1f, u * 2f - 1f, 1f), col, c4(cell.toFloat(), 0f, 2f, 16f))
                    }
                }
            }
        })
        for (i in 0 until 20) {
            val a = i / 20f * TWO_PI + rnd(-.1f, .1f)
            spr(p = v3(c[0] + cos(a) * .7f, .95f, c[2] + sin(a) * .7f), v = v3(cos(a) * rnd(6f, 10f), rnd(-.5f, 1.5f), sin(a) * rnd(6f, 10f)), dr = 4f, life = rnd(.2f, .35f), st = true, c0 = T63, m = 2,
                s = .06f, asp = 8f, col = c4(1f, .8f, .35f, .9f), env = EN_FADE)
        }
    }

    // ----- Skill 3: pancada de terra (skTimed do visualizador) e Ultimate (ultC3)
    private fun blCenter(): V = v3((blades[0][0] + blades[1][0]) / 2f, 0f, (blades[0][2] + blades[1][2]) / 2f)
    private fun blFar(): Boolean = hypot(blades[0][0] - blades[1][0], blades[0][2] - blades[1][2]) > .9f
    fun s3Slam() {
        val P = .62f
        if (blFar()) {
            tintSlam(v3(blades[0][0], 0f, blades[0][2]), P * .75f, { v -> earthT(v) })
            tintSlam(v3(blades[1][0], 0f, blades[1][2]), P * .75f, { v -> earthT(v) })
        } else tintSlam(blCenter(), P, { v -> earthT(v) })
        swFire(blCenter(), .9f + P * 1.3f, .6f, floatArrayOf(1f, .5f, .15f), 1.2f + P * .4f, 0f)
    }
    fun ultHit(i: Int, fin: Boolean) {
        val c = blCenter(); val far = blFar()
        val a = blades[0]; val b = blades[1]
        if (fin) {
            if (far) { ultSlamBig(v3(a[0], 0f, a[2]), 2f * .72f); ultSlamBig(v3(b[0], 0f, b[2]), 2f * .72f) } else ultSlamBig(c, 2f)
            swFire(c, 13f, 1.7f, floatArrayOf(1f, .06f, .03f), 3f, 0f)
            swFire(c, 8.5f, 1.2f, floatArrayOf(1f, .14f, .06f), 2.2f, .12f)
            swFire(c, 5f, .8f, floatArrayOf(1f, .35f, .25f), 1.6f, .25f)
        } else {
            val P = 1.35f + i * .15f
            if (far) { ultSlamBig(v3(a[0], 0f, a[2]), P * .72f); ultSlamBig(v3(b[0], 0f, b[2]), P * .72f) } else ultSlamBig(c, P)
            swFire(c, 3.2f + i * .6f, .7f, floatArrayOf(1f, .08f, .04f), 1.4f + i * .3f, 0f)
        }
    }
    fun impact(x: Float, y: Float, z: Float, s: Float, dx: Float, dz: Float) { impactFx(W(x, y, z), s, v3(dx, 0f, dz)) }
    fun dust(x: Float, z: Float, s: Float, n: Int) { dustFx(v3(x, 0f, z), s, n) }

    // ================================================================ Skill 4: arremesso, corrente e atordoamento
    private var thrPh = 0; private var thrT = 0f; private var thrTh = 0f; private var thrFlyQ = 0f
    private var thrHitOk = false
    private var thrP0 = v3(0f, 0f, 0f); private var thrTg = v3(0f, 0f, 0f); private var thrPos = v3(0f, 1f, 0f)
    private val TFLY = .3f; private val THOLD = .12f; private val TPULL = .5f

    private fun thrSpark(p: V) {
        spr(p = vadd(p, v3(rnd(-.12f, .12f), rnd(-.12f, .12f), rnd(-.12f, .12f))), v = v3(rnd(-.4f, .4f), rnd(0f, .8f), rnd(-.4f, .4f)), dr = 2f, life = rnd(.3f, .55f), c0 = C_GLOW,
            s = rnd(.025f, .05f), col = c4(1f, .62f, .15f, 1f), env = EN_FADE)
        spr(p = p, life = .12f, c0 = C_GMASK, s = .32f, col = c4(1f, .55f, .12f, .35f), env = EN_FADE)
    }
    private fun thrHit(p: V) {
        spr(p = p, life = .35f, c0 = C_GMASK, s = .3f, s1 = .9f, ease = easeO, col = c4(1f, .55f, .12f, .6f), env = EN_FADE)
        spr(p = p, life = .4f, c0 = C_RING, s = .15f, s1 = .8f, ease = easeO, col = c4(1f, .7f, .25f, .7f), env = EN_FADE)
        for (i in 0 until 6) spr(p = p, v = v3(rnd(-1f, 1f), rnd(-.5f, 1f), rnd(-1f, 1f)), dr = 2f, life = rnd(.5f, .8f), c0 = C_SMOKE + (i % 5), m = 1, s = .15f, s1 = .4f,
            col = c4(.25f, .2f, .15f, .4f), env = EN_SIN)
    }
    private fun thrCatch(p: V) {
        spr(p = p, life = .3f, c0 = C_RING, s = .1f, s1 = .6f, ease = easeO, col = c4(1f, .7f, .25f, .7f), env = EN_FADE)
        spr(p = p, life = .35f, c0 = C_GMASK, s = .4f, s1 = .2f, col = c4(1f, .6f, .15f, .6f), env = EN_FADE)
    }

    fun stun(f: Foe, life: Float) {
        val F21 = C_FLARE21; val GM = C_GMASK
        add(object : FxO(life) {
            override fun draw(pp: Float) {
                val q = age / life; if (q > 1f) return
                val A = min(1f, q * 8f) * (1f - sstep(.8f, 1f, q)); val h = v3(f.x, f.y + 1.75f - oy, f.z)
                for (i in 0 until 4) {
                    val a = age * 5f + i * PIF / 2f
                    val p = v3(h[0] + cos(a) * .32f, h[1] + .12f * sin(a * 2f), h[2] + sin(a) * .32f)
                    billboard(bAdd, p, .13f, .13f, age * 6f, c4(1f, .85f, .4f, A), c4(F21.toFloat(), 0f, 2f, 16f))
                    billboard(bAdd, p, .12f, .12f, 0f, c4(1f, .6f, .15f, .5f * A), c4(GM.toFloat(), 0f, 2f, 16f))
                }
            }
        })
    }

    private fun axeBlade(B: Bt, p: V, th: Float, A: Float, fl: V) {
        val T87 = C_TRAIL87; val T63 = C_TRAIL63
        val rr = vnorm(v3(fl[2], 0f, -fl[0])); val up = UP
        val hd = vadd(vmul(rr, cos(th)), vmul(up, sin(th))); val ed = vadd(vmul(rr, -sin(th)), vmul(up, cos(th)))
        for (sg in floatArrayOf(-1f, 1f)) {
            val hc = vadd(p, vmul(hd, sg * .26f)); val N = 12
            val P = Array(N) { i -> val s = i.toFloat() / (N - 1) * 2f - 1f; vadd(vadd(hc, vmul(ed, s * .27f)), vmul(hd, sg * .12f * (1f - s * s))) }
            for (lay in 0 until 3) {
                val h = if (lay == 0) .2f else if (lay == 1) .09f else .035f
                val col = if (lay == 0) c4(1f, .42f, .06f, .3f * A) else if (lay == 1) c4(1f, .76f, .24f, A) else c4(1f, .95f, .7f, A)
                val cell = if (lay == 2) T63 else T87
                for (i in 0 until N - 1) {
                    val s0 = i.toFloat() / (N - 1) * 2f - 1f; val s1 = (i + 1).toFloat() / (N - 1) * 2f - 1f
                    val t0 = (1f - s0 * s0).pow(.5f); val t1 = (1f - s1 * s1).pow(.5f)
                    val tg = vsub(P[i + 1], P[i]); val w = vnorm(vcross(tg, fl))
                    val h0 = vmul(w, h * max(t0, .1f)); val h1 = vmul(w, h * max(t1, .1f))
                    quad(B, vsub(P[i], h0), vsub(P[i + 1], h1), vadd(P[i + 1], h1), vadd(P[i], h0), floatArrayOf(s0, -1f, s1, -1f, s1, 1f, s0, 1f), col, c4(cell.toFloat(), 0f, 2f, 16f))
                }
            }
        }
        val hw = vmul(vnorm(vcross(hd, fl)), .025f)
        quad(B, vsub(vsub(p, vmul(hd, .34f)), hw), vsub(vadd(p, vmul(hd, .34f)), hw), vadd(vadd(p, vmul(hd, .34f)), hw), vadd(vsub(p, vmul(hd, .34f)), hw), UVQ, c4(1f, .7f, .25f, .8f * A), c4(T63.toFloat(), 0f, 2f, 16f))
    }

    private fun chain2() {
        val RG = C_RING; val T63 = C_TRAIL63; val T87 = C_TRAIL87; val GM = C_GMASK; val F21 = C_FLARE21; val MG = C_MAGIC3
        add(object : FxO(9f) {
            var off = 0f; var lastPh = 1; var endT = 0f; var shk = 0f; var pulse = -1f
            override fun upd(dt: Float) {
                val ph = thrPh; val a = grips[1]; val b = thrPos; val L = vlen(vsub(b, a))
                if (ph == 3) off -= dt * L / max(.05f, TPULL) * 1.05f
                if (ph == 2 && lastPh == 1) { shk = 1f; pulse = 0f }
                if (pulse >= 0f) { pulse += dt * 3.2f; if (pulse > 1.2f) pulse = -1f }
                shk = max(0f, shk - dt * 4f)
                if (ph >= 4 || ph == 0) { endT += dt; if (endT > .15f) life = 0f }
                lastPh = ph
                if (ph in 1..3 && Math.random() < dt * (if (ph == 3) 45f else 20f)) {
                    val s = Math.random().toFloat(); val p = vadd(a, vmul(vsub(b, a), s))
                    spr(p = p, v = v3(rnd(-.4f, .4f), if (ph == 3) rnd(-.6f, .2f) else rnd(-.2f, .6f), rnd(-.4f, .4f)), g = if (ph == 3) 2f else 0f, dr = 1.5f, life = rnd(.3f, .6f), c0 = C_GLOW,
                        s = rnd(.02f, .04f), col = c4(1f, .65f, .2f, 1f), env = EN_FADE)
                }
            }
            override fun draw(pp: Float) {
                val ph = thrPh; val a = grips[1]; val b = thrPos; val d = vsub(b, a); val L = vlen(d)
                if (L < .05f) return
                val u = vmul(d, 1f / L); val T = age
                val A = if (ph >= 4 || ph == 0) max(0f, 1f - endT / .15f) else 1f
                val side = vnorm(vcross(u, UP)); val upv = vcross(side, u)
                var sag = 0f; var wav = 0f
                if (ph == 1) { val q = cl(thrFlyQ, 0f, 1f); sag = .18f * (1f - q); wav = .14f * (1f - q) }
                val vib = shk * .07f * sin(T * 80f) + (if (ph == 3) .015f * sin(T * 60f) else 0f)
                fun at(s: Float): V {
                    val e = sin(PIF * s)
                    return vadd(vadd(vadd(a, vmul(d, s)), vmul(upv, -sag * e + vib * e)), vmul(side, wav * e * sin(s * PIF * 3f - T * 16f)))
                }
                val N = 24
                val P = Array(N + 1) { i -> at(i.toFloat() / N) }
                val view = vnorm(vsub(vmul(vadd(a, b), .5f), camE)); val wd = vnorm(vcross(u, view))
                fun rib(w: Float, col: V, cell: Int) {
                    for (i in 0 until N) {
                        val s0 = i.toFloat() / N; val s1 = (i + 1).toFloat() / N; val W0 = vmul(wd, w)
                        quad(bAdd, vsub(P[i], W0), vsub(P[i + 1], W0), vadd(P[i + 1], W0), vadd(P[i], W0), floatArrayOf(s0 * 2f - 1f, -1f, s1 * 2f - 1f, -1f, s1 * 2f - 1f, 1f, s0 * 2f - 1f, 1f), col, c4(cell.toFloat(), 0f, 2f, 16f))
                    }
                }
                rib(.12f, c4(1f, .45f, .08f, .28f * A), T87); rib(.045f, c4(1f, .8f, .35f, .55f * A), T63)
                for (ph0 in floatArrayOf(0f, PIF)) {
                    val H = Array(N + 1) { i ->
                        val s = i.toFloat() / N; val ang = s * L * 9f - T * 14f + ph0; val r = .07f * (.6f + .4f * sin(PIF * s))
                        vadd(P[i], vadd(vmul(side, cos(ang) * r), vmul(upv, sin(ang) * r)))
                    }
                    for (i in 0 until N) {
                        val Wd = vmul(wd, .012f)
                        quad(bAdd, vsub(H[i], Wd), vsub(H[i + 1], Wd), vadd(H[i + 1], Wd), vadd(H[i], Wd), UVQ, c4(1f, .85f, .5f, .75f * A), c4(T63.toFloat(), 0f, 2f, 16f))
                    }
                }
                val sp = .12f; val n = floor(L / sp).toInt(); val o = ((off % (2f * sp)) + 2f * sp) % (2f * sp)
                for (i in -1..n + 1) {
                    val dist = i * sp + o
                    if (dist < 0f || dist > L) continue
                    val s = dist / L; val Pp = at(s)
                    val kk = Math.round((dist - o) / sp) + Math.round(off / sp); val odd = ((kk % 2) + 2) % 2 == 1
                    val nrm = if (odd) side else upv
                    val w = vnorm(vcross(u, nrm)); val Lh = vmul(u, .07f); val W2 = vmul(w, .042f)
                    val glow = if (pulse >= 0f) exp(-((s - pulse) * 6f).pow(2f)) else 0f
                    val cA = (.95f + glow) * A
                    quad(bAdd, vsub(vsub(Pp, Lh), W2), vsub(vadd(Pp, Lh), W2), vadd(vadd(Pp, Lh), W2), vadd(vsub(Pp, Lh), W2), UVQ, c4(1f, .72f + .2f * glow, .28f + .4f * glow, cA), c4(RG.toFloat(), 0f, 2f, 16f))
                    if (glow > .05f) billboard(bAdd, Pp, .12f * glow, .12f * glow, 0f, c4(1f, .85f, .5f, glow * A), c4(GM.toFloat(), 0f, 2f, 16f))
                }
                fun seal(p: V, r: Float, rot: Float) {
                    billboard(bAdd, p, r, r, rot, c4(1f, .7f, .25f, .75f * A), c4(MG.toFloat(), 0f, 2f, 16f))
                    billboard(bAdd, p, r * .9f, r * .9f, 0f, c4(1f, .5f, .1f, .45f * A), c4(GM.toFloat(), 0f, 2f, 16f))
                }
                seal(a, .2f, T * 5f); seal(b, .26f + .08f * shk, -T * 6f)
                billboard(bAdd, b, .18f, .18f, T * 9f, c4(1f, .9f, .6f, .9f * A), c4(F21.toFloat(), 0f, 2f, 16f))
            }
        })
    }

    /** Skill 4: o machado direito é arremessado, a corrente liga à mão, puxa de volta. */
    fun throwStart(fx: Float, fz: Float, hit: Boolean) {
        val f0 = v3(sin(yaw), 0f, cos(yaw))
        thrP0 = tips[1].copyOf(); thrHitOk = hit
        if (hit) {
            var dx = fx - root[0]; var dz = fz - root[2]; val n = max(.001f, hypot(dx, dz)); dx /= n; dz /= n
            thrTg = v3(fx - dx * .1f, .5f, fz - dz * .1f)
        } else thrTg = v3(root[0] + f0[0] * 10f, .5f, root[2] + f0[2] * 10f)
        thrPos = thrP0.copyOf(); thrPh = 1; thrT = 0f; thrFlyQ = 0f
        System.arraycopy(curA(), 0, axA0, 0, 9); axP = thrP0.copyOf(); axU = 0f; axE = 0f; thrWall = 0f
        chain2()
        val fl = vnorm(vsub(thrTg, thrP0)); val flh = vnorm(v3(fl[0], 0f, fl[2]))
        axAx = floatArrayOf(flh[2], 0f, -flh[0])
        val GM = C_GMASK; val F21 = C_FLARE21
        add(object : FxO(6f) {
            override fun draw(pp: Float) {
                if (thrPh == 0 || thrPh >= 4) { life = 0f; return }
                val th = axeAng()
                if (thrPh != 2) for (k in 4 downTo 1) axeBlade(bAdd, thrPos, th - k * .45f, .16f * (1f - k / 5f), flh)
                billboard(bAdd, thrPos, .4f, .4f, 0f, c4(1f, .55f, .12f, .4f), c4(GM.toFloat(), 0f, 2f, 16f))
                billboard(bAdd, thrPos, .22f, .22f, th, c4(1f, .85f, .5f, .8f), c4(F21.toFloat(), 0f, 2f, 16f))
            }
        })
    }
    private fun thrTick(adv: Float) {
        if (thrPh == 0 || thrPh >= 4) return
        thrT += adv
        if (thrPh == 1) {
            val u = min(1f, thrT / TFLY); thrFlyQ = u
            thrPos = v3(thrP0[0] + (thrTg[0] - thrP0[0]) * u, thrP0[1] + (thrTg[1] - thrP0[1]) * u + sin(PIF * u) * .12f, thrP0[2] + (thrTg[2] - thrP0[2]) * u)
            if (Math.random() < .9) thrSpark(thrPos)
            axP = thrPos; axU = u
            if (u >= 1f) { System.arraycopy(mul3(rotAx(axAx, TWO_PI * 2.25f), axA0), 0, axRh, 0, 9); thrPh = 2; thrTh = thrT; thrHit(thrPos) }
        } else if (thrPh == 2) {
            if (thrT - thrTh >= THOLD) thrPh = 3
        } else if (thrPh == 3) {
            val u = min(1f, (thrT - thrTh - THOLD) / TPULL); val e = u * u * (3f - 2f * u); val Ph = grips[1]
            thrPos = v3(thrTg[0] + (Ph[0] - thrTg[0]) * e, thrTg[1] + (Ph[1] - thrTg[1]) * e + sin(PIF * e) * .1f, thrTg[2] + (Ph[2] - thrTg[2]) * e)
            // a malha volta para a posição real da cabeça do machado na mão (sem salto ao reencaixar)
            val Th = tips[1]
            axE = e; axU = u
            axP = v3(thrTg[0] + (Th[0] - thrTg[0]) * e, thrTg[1] + (Th[1] - thrTg[1]) * e + sin(PIF * e) * .1f, thrTg[2] + (Th[2] - thrTg[2]) * e)
            if (Math.random() < .7) thrSpark(thrPos)
            if (u >= 1f) { thrPh = 4; thrCatch(Ph.copyOf()) }
        }
        if (thrPh in 1..3 && thrT > 3f) thrPh = 4   // segurança: nunca deixa o machado sumido
        if (thrPh == 2) axP = thrPos
        buildAxe()
    }

    // ================================================================ rastro dourado das lâminas (TRL) e faíscas
    private class Smp(val a: V, val b: V, var age: Float, val k: Float)
    private class Spk(val p: V, val v: V, var age: Float, val life: Float, val len: Float, val w: Float)
    private val trails = arrayOf(ArrayList<Smp>(), ArrayList<Smp>())
    private val prevB = arrayOfNulls<V>(2)
    private val swOn = BooleanArray(2); private val swPk = FloatArray(2); private val swKd = FloatArray(2)
    private val sparks = ArrayList<Spk>()
    private val TL = .55f
    private val trBuf = FloatArray(9 * 6 * 6000)

    private fun trailTick(ad: Float, atk: Boolean, clipChanged: Boolean) {
        if (clipChanged || ad < -1e-4f || ad > .25f) {
            for (t in trails) t.clear()
            prevB[0] = null; prevB[1] = null
            for (s in 0 until 2) { swOn[s] = false; swPk[s] = 0f; swKd[s] = 0f }
        }
        for (s in 0 until 2) {
            val tr = trails[s]
            for (q in tr) q.age += max(ad, 0f)
            while (tr.size > 0 && tr[0].age > TL) tr.removeAt(0)
            if (!atk || !(ad > 0f)) continue
            val pt = prevB[s]
            var sp = 0f
            if (pt != null) sp = vlen(vsub(trB[s], pt)) / ad / 1.8f
            if (!swOn[s] && sp > 2.4f && sp >= swPk[s]) swOn[s] = true
            if (swOn[s]) { swPk[s] = max(swPk[s], sp); if (sp < swPk[s] * .62f) swOn[s] = false }
            else if (sp < 1.3f) swPk[s] = 0f
            val kt = if (swOn[s]) cl((sp - 1.4f) / 2.6f, 0f, 1f) else 0f
            swKd[s] += (kt - swKd[s]) * (if (kt > swKd[s]) .55f else .22f)
            if (swKd[s] > .015f) tr.add(Smp(trA[s].copyOf(), trB[s].copyOf(), 0f, swKd[s]))
            prevB[s] = trB[s].copyOf()
        }
    }
    private fun trailUpd(dt: Float) {
        for (s in sparks) {
            s.age += dt
            for (k in 0 until 3) s.p[k] = s.p[k] + s.v[k] * dt
            s.v[1] = s.v[1] - 2.4f * dt
            val d = exp(-dt * 3f); s.v[0] = s.v[0] * d; s.v[2] = s.v[2] * d
        }
        var i = sparks.size - 1
        while (i >= 0) { if (sparks[i].age >= sparks[i].life) sparks.removeAt(i); i-- }
        if (dt <= 0f) return
        for (tr in trails) {
            val n = tr.size
            if (n < 2) continue
            val q = tr[n - 1]
            if (q.age > .02f || q.k < .35f) continue
            val b = q.b; val b0 = tr[n - 2].b
            val mv = vsub(b, b0); var ml = vlen(mv); if (ml < 1e-6f) ml = 1f
            val cnt = 1 + (if (Math.random() < q.k) 1 else 0)
            for (c in 0 until cnt) {
                val sp = .8f + Math.random().toFloat() * 1.8f
                if (sparks.size > 400) break
                sparks.add(Spk(v3(b[0] + rnd(-.015f, .015f), b[1] + rnd(-.015f, .015f), b[2] + rnd(-.015f, .015f)),
                    v3(-mv[0] / ml * sp * .6f + rnd(-.8f, .8f), -mv[1] / ml * sp * .6f + rnd(0f, 1.2f), -mv[2] / ml * sp * .6f + rnd(-.8f, .8f)),
                    0f, .2f + Math.random().toFloat() * .3f, .05f + Math.random().toFloat() * .11f, .006f + Math.random().toFloat() * .008f))
            }
        }
    }

    private fun cr(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return .5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
    }
    private fun cr3(a: V, b: V, c: V, d: V, t: Float): V = v3(cr(a[0], b[0], c[0], d[0], t), cr(a[1], b[1], c[1], d[1], t), cr(a[2], b[2], c[2], d[2], t))

    private class Sam(val A: ArrayList<V>, val B: ArrayList<V>, val ag: ArrayList<Float>, val k: ArrayList<Float>)
    private val SUB = 8
    private fun samples(tr: ArrayList<Smp>): Sam? {
        val n = tr.size
        if (n < 2) return null
        val A = ArrayList<V>(); val B = ArrayList<V>(); val AG = ArrayList<Float>(); val K = ArrayList<Float>()
        for (q in tr) {
            val m = v3((q.a[0] + q.b[0]) / 2f, (q.a[1] + q.b[1]) / 2f, (q.a[2] + q.b[2]) / 2f)
            A.add(v3(m[0] + (q.a[0] - m[0]) * 1.2f, m[1] + (q.a[1] - m[1]) * 1.2f, m[2] + (q.a[2] - m[2]) * 1.2f))
            B.add(v3(m[0] + (q.b[0] - m[0]) * 1.7f, m[1] + (q.b[1] - m[1]) * 1.7f, m[2] + (q.b[2] - m[2]) * 1.7f))
            AG.add(q.age); K.add(min(1f, q.k * 1.6f))
        }
        val S = Sam(ArrayList(), ArrayList(), ArrayList(), ArrayList())
        for (i in 0 until n - 1) {
            val i0 = max(0, i - 1); val i3 = min(n - 1, i + 2)
            for (j in 0 until SUB) {
                val t = j.toFloat() / SUB
                S.A.add(cr3(A[i0], A[i], A[i + 1], A[i3], t)); S.B.add(cr3(B[i0], B[i], B[i + 1], B[i3], t))
                S.ag.add(AG[i] + (AG[i + 1] - AG[i]) * t); S.k.add(K[i] + (K[i + 1] - K[i]) * t)
            }
        }
        S.A.add(A[n - 1]); S.B.add(B[n - 1]); S.ag.add(AG[n - 1]); S.k.add(K[n - 1])
        return S
    }
    private fun build(S: Sam, seed: Float, wA: Float, wB: Float, off: Int): Int {
        val N = S.A.size; var n = off
        val u = FloatArray(N)
        for (i in 1 until N) { val a = S.A[i]; val b = S.A[i - 1]; u[i] = u[i - 1] + vlen(vsub(a, b)) * 6f }
        fun vv(i: Int, v: Int) {
            val a = S.A[i]; val b = S.B[i]
            val m0 = (a[0] + b[0]) / 2f; val m1 = (a[1] + b[1]) / 2f; val m2 = (a[2] + b[2]) / 2f
            val ex = if (v == 1) wB else wA; val e = if (v == 1) b else a
            trBuf[n++] = m0 + (e[0] - m0) * ex; trBuf[n++] = m1 + (e[1] - m1) * ex + oy; trBuf[n++] = m2 + (e[2] - m2) * ex
            trBuf[n++] = u[i]; trBuf[n++] = v.toFloat(); trBuf[n++] = min(1f, S.ag[i] / TL); trBuf[n++] = S.k[i]; trBuf[n++] = seed; trBuf[n++] = i.toFloat() / (N - 1)
        }
        var i = 0
        while (i < N - 1 && n < trBuf.size - 60) {
            vv(i, 0); vv(i, 1); vv(i + 1, 1); vv(i, 0); vv(i + 1, 1); vv(i + 1, 0)
            i++
        }
        return n
    }
    private fun trailDraw(vp: FloatArray) {
        val rows = arrayOf(samples(trails[0]), samples(trails[1]))
        if (rows[0] == null && rows[1] == null && sparks.isEmpty()) return
        glUseProgram(tProg)
        glUniformMatrix4fv(tuVP, 1, false, vp, 0); glUniform1f(tuR, redT)
        glBindVertexArray(tVao); glBindBuffer(GL_ARRAY_BUFFER, tVbo)
        glEnable(GL_DEPTH_TEST); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE)
        for (pass in intArrayOf(1, 0)) {
            var n = 0
            for (i in 0 until 2) { val S = rows[i]; if (S != null) n = build(S, if (i == 1) .71f else .29f, if (pass == 1) 1.9f else 1f, if (pass == 1) 1.8f else 1f, n) }
            if (n > 0) { glUniform1f(tuG, pass.toFloat()); upT(n); glDrawArrays(GL_TRIANGLES, 0, n / 9) }
        }
        if (sparks.isNotEmpty()) {
            var n = 0
            val co = floatArrayOf(0f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, 1f, 0f, 0f, -1f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f)
            for (s in sparks) {
                if (n > trBuf.size - 60) break
                var L = vlen(s.v); if (L < 1e-6f) L = 1f
                val d = v3(s.v[0] / L, s.v[1] / L, s.v[2] / L); val vw = vsub(camE, s.p)
                val sd = vcross(d, vw); var sl = vlen(sd); if (sl < 1e-6f) sl = 1f
                val ln = s.len * (1f - s.age / s.life * .5f); val pr = s.age / s.life
                for (c in 0 until 6) {
                    val cx = co[c * 4]; val cy = co[c * 4 + 1]; val u = co[c * 4 + 2]; val v = co[c * 4 + 3]
                    val a = cx * ln; val b = cy * s.w / sl
                    trBuf[n++] = s.p[0] + d[0] * a + sd[0] * b; trBuf[n++] = s.p[1] + d[1] * a + sd[1] * b + oy; trBuf[n++] = s.p[2] + d[2] * a + sd[2] * b
                    trBuf[n++] = u; trBuf[n++] = v; trBuf[n++] = pr; trBuf[n++] = 1f; trBuf[n++] = 0f; trBuf[n++] = 0f
                }
            }
            glUniform1f(tuG, 2f); upT(n); glDrawArrays(GL_TRIANGLES, 0, n / 9)
        }
        glBindVertexArray(0); glDepthMask(true); glDisable(GL_BLEND)
    }
    private fun upT(n: Int) {
        tFb.clear(); tFb.put(trBuf, 0, n); tFb.flip()
        glBufferData(GL_ARRAY_BUFFER, n * 4, tFb, GL_DYNAMIC_DRAW)
    }

    // ================================================================ onda de choque (Vefects Easy Shockwaves)
    private class Sw(val cx: Float, val cz: Float, val R: Float, val life: Float, var age: Float, val col: V, val seed: Float, val pw: Float)
    private val swl = ArrayList<Sw>()
    private fun swFire(c: V, R: Float, life: Float, col: V, pw: Float, delay: Float) {
        swl.add(Sw(c[0], c[2], R, life, .07f - delay, col.copyOf(), Math.random().toFloat() * 10f, pw))
    }
    private fun swTick(dt: Float) {
        var i = swl.size - 1
        while (i >= 0) { swl[i].age += dt; if (swl[i].age > swl[i].life) swl.removeAt(i); i-- }
    }
    private fun swDraw(vp: FloatArray) {
        if (swl.isEmpty() || sProg == 0) return
        glUseProgram(sProg)
        glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE); glDepthMask(false); glDisable(GL_CULL_FACE); glEnable(GL_DEPTH_TEST)
        for (i in 0 until 4) { glActiveTexture(GL_TEXTURE8 + i); glBindTexture(GL_TEXTURE_2D, swTex[i]); glUniform1i(suTex[i], 8 + i) }
        glUniformMatrix4fv(suVP, 1, false, vp, 0)
        glBindVertexArray(sVao)
        for (s in swl) {
            if (s.age < 0f) continue
            val q = s.age / s.life; val e = 1f - (1f - q).pow(4f)
            val layers = if (s.pw > 1.5f) 3 else 2
            for (L in 0 until layers) {
                val k = if (L == 2) 1.18f else (if (L == 1) .62f else 1f)
                glUniform3f(suC, s.cx, oy, s.cz); glUniform1f(suR, s.R * k * (.4f + .75f * e)); glUniform1f(suRin, .45f)
                glUniform1f(suT, s.age + s.seed + L * 3.1f); glUniform1f(suOff, -.15f + .2f * q)
                glUniform1f(suEro, max(0f, (q - .35f) * 1.4f)); glUniform1f(suEmi, (if (L > 0) 1.2f else 1.6f) * (.8f + .35f * s.pw)); glUniform1f(suCA, if (L > 0) 2f else 6f)
                glUniform1f(suA, (1f - q * q) * (if (L > 0) .7f else 1f) * (if (L == 2) .55f else 1f)); glUniform1f(suPow, 1f); glUniform1f(suMul, 1f)
                glUniform3f(suCol, if (L > 0) 1f else s.col[0], if (L > 0) .9f else s.col[1], if (L > 0) .75f else s.col[2])
                glDrawArrays(GL_TRIANGLES, 0, 96 * 6)
            }
        }
        glBindVertexArray(0)
        for (i in 0 until 4) { glActiveTexture(GL_TEXTURE8 + i); glBindTexture(GL_TEXTURE_2D, 0) }
        glActiveTexture(GL_TEXTURE0); glDepthMask(true); glDisable(GL_BLEND)
    }

    // ================================================================ GL
    private var ready = false
    private var prog = 0; private var vao = 0; private var vbo = 0; private var atlTex = 0
    private var uVP = 0; private var uTime = 0; private var uEye = 0; private var uAtl = 0; private var uAG = 0
    private var tProg = 0; private var tVao = 0; private var tVbo = 0; private var tuVP = 0; private var tuG = 0; private var tuR = 0
    private var sProg = 0; private var sVao = 0
    private var suVP = 0; private var suC = 0; private var suR = 0; private var suRin = 0; private var suT = 0; private var suOff = 0; private var suEro = 0
    private var suEmi = 0; private var suCA = 0; private var suA = 0; private var suPow = 0; private var suMul = 0; private var suCol = 0
    private val suTex = IntArray(4); private val swTex = IntArray(4)
    private lateinit var fb: java.nio.FloatBuffer
    private lateinit var tFb: java.nio.FloatBuffer

    private fun procTex(kind: Int): Int {
        val S = 256
        val lat = FloatArray(32 * 32 * 2)
        val rg = java.util.Random(1234L + kind * 77L)
        for (i in lat.indices) lat[i] = rg.nextFloat()
        fun vn(x: Float, y: Float, ch: Int, f: Int): Float {
            val fx = x * f; val fy = y * f
            val ix = floor(fx).toInt(); val iy = floor(fy).toInt(); val tx = fx - ix; val ty = fy - iy
            val sx = tx * tx * (3f - 2f * tx); val sy = ty * ty * (3f - 2f * ty)
            fun h(a: Int, b: Int): Float = lat[(((b % f) + f) % f * 32 + ((a % f) + f) % f) * 2 + ch]
            val a0 = h(ix, iy) + (h(ix + 1, iy) - h(ix, iy)) * sx; val a1 = h(ix, iy + 1) + (h(ix + 1, iy + 1) - h(ix, iy + 1)) * sx
            return a0 + (a1 - a0) * sy
        }
        val bb = ByteBuffer.allocateDirect(S * S * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until S) for (x in 0 until S) {
            val u = x.toFloat() / S; val v = y.toFloat() / S
            var r: Float; var g: Float
            if (kind == 3) { val t = exp(-((v - .55f) / .28f) * ((v - .55f) / .28f)); r = t; g = t }
            else if (kind == 2) { r = vn(u, v, 0, 8); g = vn(u, v, 1, 8) }
            else { val f1 = if (kind == 0) 8 else 16; r = (vn(u, v, 0, f1) * .6f + vn(u, v, 1, f1 * 2) * .4f); g = r }
            bb.put((cl(r, 0f, 1f) * 255f).toInt().toByte()); bb.put((cl(g, 0f, 1f) * 255f).toInt().toByte()); bb.put((cl(r, 0f, 1f) * 255f).toInt().toByte()); bb.put(255.toByte())
        }
        bb.position(0)
        val t = Gl.gen(::glGenTextures)
        glBindTexture(GL_TEXTURE_2D, t)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, S, S, 0, GL_RGBA, GL_UNSIGNED_BYTE, bb)
        glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, if (kind == 3) GL_CLAMP_TO_EDGE else GL_REPEAT)
        return t
    }
    private fun loadSw(ctx: Context, name: String, kind: Int): Int {
        try {
            val t = Gl.texture(ctx, "w/swt_$name.png", true, true)
            if (kind == 3) { glBindTexture(GL_TEXTURE_2D, t); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE) }
            return t
        } catch (e: Throwable) { return procTex(kind) }
    }

    fun initGl(ctx: Context) {
        try {
            atlTex = Gl.texture(ctx, "w/fx_atlas.jpg", true, false)
            prog = Gl.program(VS, FS)
            uVP = glGetUniformLocation(prog, "uVP"); uTime = glGetUniformLocation(prog, "uTime"); uEye = glGetUniformLocation(prog, "uEye")
            uAtl = glGetUniformLocation(prog, "uAtl"); uAG = glGetUniformLocation(prog, "uAG")
            vao = Gl.gen(::glGenVertexArrays); glBindVertexArray(vao)
            vbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 52, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 52, 12)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 52, 20)
            glEnableVertexAttribArray(3); glVertexAttribPointer(3, 4, GL_FLOAT, false, 52, 36)
            glBindVertexArray(0)
            fb = Gl.floats(13 * MAXV)
            tProg = Gl.program(TVS, TFS)
            tuVP = glGetUniformLocation(tProg, "uVP"); tuG = glGetUniformLocation(tProg, "uG"); tuR = glGetUniformLocation(tProg, "uR")
            tVao = Gl.gen(::glGenVertexArrays); glBindVertexArray(tVao)
            tVbo = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, tVbo)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 36, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, false, 36, 12)
            glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, false, 36, 20)
            glBindVertexArray(0)
            tFb = Gl.floats(trBuf.size)
            ready = true
        } catch (t: Throwable) { Log.e("Vfx", "init", t) }
        try {
            sProg = Gl.program(SVS, SFS)
            suVP = glGetUniformLocation(sProg, "uVP"); suC = glGetUniformLocation(sProg, "uC"); suR = glGetUniformLocation(sProg, "uR"); suRin = glGetUniformLocation(sProg, "uRin")
            suT = glGetUniformLocation(sProg, "uT"); suOff = glGetUniformLocation(sProg, "uOff"); suEro = glGetUniformLocation(sProg, "uEro"); suEmi = glGetUniformLocation(sProg, "uEmi")
            suCA = glGetUniformLocation(sProg, "uCA"); suA = glGetUniformLocation(sProg, "uA"); suPow = glGetUniformLocation(sProg, "uPow"); suMul = glGetUniformLocation(sProg, "uMul")
            suCol = glGetUniformLocation(sProg, "uCol")
            val nm = arrayOf("tB", "tN", "tD", "tM"); val fl = arrayOf("n3", "n1", "nn", "mk")
            for (i in 0 until 4) { suTex[i] = glGetUniformLocation(sProg, nm[i]); swTex[i] = loadSw(ctx, fl[i], i) }
            val NS = 96
            val vb = FloatArray(NS * 12); var o = 0
            for (i in 0 until NS) {
                val a = i.toFloat() / NS; val b = (i + 1).toFloat() / NS
                val q = floatArrayOf(a, 0f, b, 0f, b, 1f, a, 0f, b, 1f, a, 1f)
                for (z in q) vb[o++] = z
            }
            sVao = Gl.gen(::glGenVertexArrays); glBindVertexArray(sVao)
            val sb = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, sb)
            val f = Gl.floats(vb.size); f.put(vb); f.flip()
            glBufferData(GL_ARRAY_BUFFER, vb.size * 4, f, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0)
            glBindVertexArray(0)
        } catch (t: Throwable) { Log.e("Vfx", "initSw", t); sProg = 0 }
    }

    // ================================================================ por quadro
    /** dt = tempo real; adv = tempo de animação (0 no hitstop); atk = está golpeando; red = buff da Ultimate ligado. */
    fun tick(dt: Float, adv: Float, atk: Boolean, clipChanged: Boolean, red: Boolean) {
        tclock += dt
        redT += ((if (red) 1f else 0f) - redT) * min(1f, dt * (if (red) 5f else 1.5f))
        trailTick(adv, atk, clipChanged)
        trailUpd(adv)
        swTick(dt)
        thrTick(adv)
        if (thrPh in 1..3) { thrWall += dt; if (thrWall > 4f) thrPh = 4 } else thrWall = 0f
        var i = fxs.size - 1
        while (i >= 0) {
            val f = fxs[i]
            f.age += dt
            if (f.age >= f.delay) f.upd(dt)
            if (f.age >= f.delay + f.life) fxs.removeAt(i)
            i--
        }
    }

    fun draw(vp: FloatArray, view: FloatArray, eye: FloatArray) {
        if (!ready) return
        camR[0] = view[0]; camR[1] = view[4]; camR[2] = view[8]
        camU[0] = view[1]; camU[1] = view[5]; camU[2] = view[9]
        camE[0] = eye[0]; camE[1] = eye[1] - oy; camE[2] = eye[2]
        bAlpha.n = 0; bAdd.n = 0
        for (f in fxs) {
            if (f.age >= f.delay) {
                try { f.draw(min(1f, (f.age - f.delay) / (if (f.life == 0f) 1f else f.life))) } catch (e: Throwable) { }
            }
        }
        if (aimOn) {
            val d = v3(sin(aimY), 0f, cos(aimY)); val sd = v3(cos(aimY), 0f, -sin(aimY)); val pu = .6f + .25f * sin(tclock * 8f)
            val a0 = v3(root[0] + d[0] * .8f, 0f, root[2] + d[2] * .8f); val a1 = v3(root[0] + d[0] * 10f, 0f, root[2] + d[2] * 10f)
            val w = .22f
            quad(bAdd, vsub(a0, vmul(sd, w)), vadd(a0, vmul(sd, w)), vadd(a1, vmul(sd, w)), vsub(a1, vmul(sd, w)), UVQ, c4(1f, .75f, .25f, pu), c4(C_TRAIL63.toFloat(), 0f, 2f, 16f))
            ground(bAdd, a1, .9f, c4(1f, .75f, .25f, pu), c4(C_RING.toFloat(), 0f, 2f, 16f), .05f)
            // corrente mágica girando em torno do braço/machado enquanto segura o botão
            try {
                val g = grips[1]; val tp = tips[1]
                val ax = vnorm(vsub(tp, g)); val s1 = vnorm(vcross(ax, UP)); val u1 = vcross(s1, ax)
                val link = c4(1f, .75f, .3f, .95f)
                for (i in 0 until 14) {
                    val f = i / 13f; val ang = f * 6.28f * 2.2f + tclock * 9f; val r = .2f - .06f * f
                    val c = vadd(vadd(g, vmul(ax, -.35f + f * 1.1f)), vadd(vmul(s1, cos(ang) * r), vmul(u1, sin(ang) * r)))
                    billboard(bAdd, c, .07f, .07f, ang, link, c4(C_RING.toFloat(), 0f, 2f, 16f))
                    billboard(bAdd, c, .16f, .16f, 0f, c4(1f, .55f, .12f, .3f), c4(C_GMASK.toFloat(), 0f, 2f, 16f))
                }
                billboard(bAdd, tp, .45f, .45f, 0f, c4(1f, .6f, .15f, pu * .6f), c4(C_GMASK.toFloat(), 0f, 2f, 16f))
            } catch (e: Throwable) { }
        }
        glUseProgram(prog)
        glUniformMatrix4fv(uVP, 1, false, vp, 0); glUniform1f(uTime, tclock); glUniform3f(uEye, camE[0], camE[1], camE[2])
        glActiveTexture(GL_TEXTURE6); glBindTexture(GL_TEXTURE_2D, atlTex); glUniform1i(uAtl, 6); glUniform2f(uAG, 16f, 8f)
        glActiveTexture(GL_TEXTURE0)
        glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE); glDepthMask(false); glEnable(GL_BLEND)
        pass(bAlpha, true); pass(bAdd, false)
        glBindVertexArray(0)
        try { trailDraw(vp) } catch (e: Throwable) { Log.e("Vfx", "trail", e) }
        try { swDraw(vp) } catch (e: Throwable) { Log.e("Vfx", "sw", e) }
        glDepthMask(true); glDisable(GL_BLEND)
    }
    private fun pass(B: Bt, alpha: Boolean) {
        if (B.n == 0) return
        glBlendFunc(GL_SRC_ALPHA, if (alpha) GL_ONE_MINUS_SRC_ALPHA else GL_ONE)
        fb.clear(); fb.put(B.a, 0, B.n * 13); fb.flip()
        glBufferData(GL_ARRAY_BUFFER, B.n * 13 * 4, fb, GL_DYNAMIC_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, B.n)
    }
}
