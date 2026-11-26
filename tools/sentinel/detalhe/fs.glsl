#version 300 es
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
 o=vec4(pow(c,vec3(1./2.2)),1.); }
