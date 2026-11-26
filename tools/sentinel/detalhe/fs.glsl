#version 300 es
precision highp float;
in vec2 vUV; in float vMet; in vec3 vN; in vec3 vP; uniform sampler2D uTex; uniform sampler2D uNrm; uniform sampler2D uFx; uniform sampler2D uMouth; uniform vec3 uCam; uniform float uTm; uniform float uBuff; out vec4 o;
vec3 fogc(vec3 c, vec3 P, vec3 E){ float d=length(P-E); vec3 f=vec3(.56,.64,.74); return mix(c,f,smoothstep(90.,700.,d)*.8); }
void mouthShape(vec2 q, float s, out vec3 col, out float al){
 float hw=.0182+.0158*s; float u=q.x/hw; float lift=.0100*s; float yc=lift*u*u;
 float op=.0150*s*pow(clamp(1.-u*u,0.,1.),.75);
 float inx=1.-smoothstep(.93,1.02,abs(u));
 float th=.0008+.0004*s;
 float lineU=(1.-smoothstep(th*.7,th*1.5,abs(q.y-yc)))*inx;
 float lineL=(1.-smoothstep(th*.7,th*1.5,abs(q.y-(yc-op))))*inx*smoothstep(.02,.12,s);
 float ins=step(q.y,yc-th*.5)*step(yc-op+th*.5,q.y)*inx;
 float depth=clamp((yc-q.y)/max(op,1e-5),0.,1.);
 float teeth=ins*(1.-smoothstep(.46,.54,depth));
 float gap=smoothstep(.045,.085,abs(fract(u*5.5+.5)-.5));
 vec3 toothc=vec3(.97,.93,.88)*(.55+.45*gap);
 col=mix(vec3(.30,.02,.04),toothc,teeth);
 float ln=max(lineU,lineL);
 for(int k=0;k<2;k++){ float sg=k==0?-1.:1.; vec2 cp=vec2(sg*hw*1.09,lift+.0008); float d=length(q-cp);
  float arc=(1.-smoothstep(.0005,.0012,abs(d-.0035)))*step(-.0008,(q.x-cp.x)*sg)*smoothstep(.35,.8,s); ln=max(ln,arc); }
 vec3 lc=mix(vec3(.45,.30,.29),vec3(.13,.045,.05),smoothstep(0.,.4,s));
 col=mix(col,lc,ln);
 al=max(ins*smoothstep(.03,.15,s),ln);
}
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
 float met=clamp(vMet*nm.b,0.,1.); vec3 tc=texture(uTex,vUV).rgb;
 float bf=clamp(uBuff,0.,1.);
 float bodyP=step(vUV.y,.4999), axeP=1.-bodyP;
 vec4 fx=texture(uFx,uvn); vec3 mo=texture(uMouth,uvn).rgb;
 float tat=fx.r*bodyP, eyeM=fx.g*bodyP;
 // boca: sorriso desenhado por cima (morph com o buff)
 float vb=smoothstep(.55,.9,mo.b)*bodyP;
 vec2 q=(mo.rg*2.-1.)*.08;
 float sm=smoothstep(.12,.92,bf);
 vec3 mc; float ma; mouthShape(q,sm,mc,ma);
 float fl=vb*(1.-smoothstep(.75,1.,length(q/vec2(.046,.025))));
 tc=mix(tc,vec3(.9725,.8,.7216),fl);
 tc=mix(tc,mc,ma*vb);
 // olhos: vermelhos por inteiro
 float eE=smoothstep(.08,.85,bf);
 tc=mix(tc,vec3(.75,.0,.02)*(.4+.7*dot(tc,vec3(.33))),eyeM*eE);
 vec3 al=tc*tc;
 // runas do machado: pretas apagadas (o dourado some)
 float gm=axeP*smoothstep(.05,.15,tc.r-tc.b)*smoothstep(.12,.35,tc.g/max(tc.r,.01))*smoothstep(.16,.34,tc.r);
 al=mix(al,vec3(.03,.026,.028),gm*.92);
 vec3 L1=normalize(vec3(.5,.9,.7)), L2=normalize(vec3(-.8,.3,-.5));
 float d1=max(dot(N,L1),0.), d2=max(dot(N,L2),0.);
 vec3 hemi=mix(vec3(.38,.36,.35),vec3(.62,.64,.70),N.y*.5+.5);
 vec3 c=al*(1.-.45*met)*(hemi+vec3(1.0,.96,.9)*d1*.75+vec3(.35,.42,.6)*d2*.3);
 vec3 H=normalize(L1+V); float sp=pow(max(dot(N,H),0.),36.);
 c+=mix(vec3(.4),al*1.6,met)*sp*d1*(.06+.9*met);
 vec3 R=reflect(-V,N); c+=al*met*mix(vec3(.10,.09,.09),vec3(.38,.44,.55),R.y*.5+.5)*.9;
 // fúria carmesim: runas, tatuagens e olhos acendem aos poucos
 vec3 CR=vec3(1.,.004,.012);
 float hh=fract(sin(dot(floor(vUV*230.),vec2(12.9898,78.233)))*43758.5453);
 float ig=smoothstep(hh*.45,hh*.45+.55,bf);
 float pu=.78+.22*sin(uTm*7.+vP.y*5.+hh*6.);
 c+=CR*tat*ig*pu*1.25;
 c+=CR*eyeM*eE*1.15;
 float h2=fract(sin(dot(floor(vUV*1400.),vec2(12.9898,78.233)))*43758.5453);
 float pr=.5+.5*sin(uTm*6.+vP.x*9.+vP.y*7.+vP.z*5.);
 float tw=pow(max(0.,sin(6.2832*(h2+uTm*.55))),18.);
 float ig2=smoothstep(0.,.8,bf);
 c+=CR*gm*ig2*(.7+.5*pr)*1.45+vec3(1.,.05,.06)*gm*ig2*tw*.9;
 c=fogc(c,vP,uCam);
 o=vec4(pow(c,vec3(1./2.2)),1.); }
