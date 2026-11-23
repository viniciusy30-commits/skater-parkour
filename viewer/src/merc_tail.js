  const attM=man.weapons.map(w=>new Float32Array(w.m));
  // =====================================================================================================
  //  KIT DO MERCENARIO — duelista agil e absurdamente veloz
  //  basico + 4 skills + ultimate, com rastros de lamina, imagens residuais, cortes no ar, particulas,
  //  aneis de choque, camera, camera lenta, alvo de treino e sons sintetizados.
  // =====================================================================================================
  const $e=id=>document.getElementById(id);
  const rnd=(a,b)=>a+Math.random()*(b-a),clp=(x,a,b)=>x<a?a:x>b?b:x,lerp=(a,b,u)=>a+(b-a)*u;
  const ss=(a,b,x)=>{const u=clp((x-a)/(b-a),0,1);return u*u*(3-2*u)};
  const eo=u=>1-Math.pow(1-clp(u,0,1),3),ei=u=>{u=clp(u,0,1);return u*u*u};
  const vadd=(a,b)=>[a[0]+b[0],a[1]+b[1],a[2]+b[2]],vsub=(a,b)=>[a[0]-b[0],a[1]-b[1],a[2]-b[2]],vmul=(a,s)=>[a[0]*s,a[1]*s,a[2]*s];
  const vdot=(a,b)=>a[0]*b[0]+a[1]*b[1]+a[2]*b[2],vlen=a=>Math.hypot(a[0],a[1],a[2]),vcross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
  const vnorm=a=>{const l=vlen(a)||1;return[a[0]/l,a[1]/l,a[2]/l]},vmix=(a,b,u)=>[lerp(a[0],b[0],u),lerp(a[1],b[1],u),lerp(a[2],b[2],u)];
  const CY=[.35,.86,1],WH=[1,1,1],VI=[.62,.42,1],MG=[1,.32,.78],GO=[1,.82,.4];
  // ---- ator (personagem) no mundo: posicao + giro; o rig anima "no lugar"
  const BASE={x:0,z:0};
  const act={x:0,y:0,z:0,yaw:0,vis:true},actM=new Float32Array(16),mA=new Float32Array(16);
  function buildAct(o,x,y,z,yaw){const c=Math.cos(yaw),s=Math.sin(yaw);o.fill(0);o[0]=c;o[2]=-s;o[5]=1;o[8]=s;o[10]=c;o[12]=x;o[13]=y;o[14]=z;o[15]=1;return o}
  const toW=(p,a=act)=>{const c=Math.cos(a.yaw),s=Math.sin(a.yaw);return[a.x+c*p[0]+s*p[2],a.y+p[1],a.z-s*p[0]+c*p[2]]};
  // ---- alvo de treino
  const TG={on:true,x:0,y:0,z:1.7,vy:0,tilt:0,tv:0,flash:0,jit:0,spin:0,vis:1,fade:0,air:false,hp:1};
  const tgPoint=()=>[TG.x,.6+TG.y,TG.z];
  // ---- camera / tela
  const CAMF={x:0,y:0,z:0,shake:0,zoom:1,zt:1,roll:0,yawk:0,dim:0,flash:0,flashC:'#fff'};
  const ts={v:1,target:1,hold:0};              // escala de tempo (camera lenta / parada de impacto)
  function slow(v,hold){ts.target=v;ts.hold=hold}
  function shake(a){CAMF.shake=Math.max(CAMF.shake,a)}
  function flash(a,col){CAMF.flash=Math.max(CAMF.flash,a);CAMF.flashC=col||'#fff'}
  // ---- sons sintetizados
  let ac=null,nb=null;
  function AC(){if(!ac){try{ac=new(window.AudioContext||window.webkitAudioContext)();const cp=ac.createDynamicsCompressor();cp.threshold.value=-12;cp.ratio.value=4;const g=ac.createGain();g.gain.value=.9;g.connect(cp);cp.connect(ac.destination);ac._m=g;
      nb=ac.createBuffer(1,ac.sampleRate*2,ac.sampleRate);const d=nb.getChannelData(0);for(let i=0;i<d.length;i++)d[i]=Math.random()*2-1}catch(e){ac=null}}
    if(ac&&ac.state==='suspended')ac.resume();return ac}
  function nz(d,type,f0,f1,q,pk,atk,dl){const a=AC();if(!a)return;const t=a.currentTime+(dl||0)+.005,s=a.createBufferSource();s.buffer=nb;s.loop=true;const f=a.createBiquadFilter();f.type=type;f.frequency.setValueAtTime(f0,t);f.frequency.exponentialRampToValueAtTime(Math.max(30,f1),t+d);f.Q.value=q;
    const g=a.createGain();g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(pk,t+atk);g.gain.exponentialRampToValueAtTime(.0001,t+d);s.connect(f);f.connect(g);g.connect(a._m);s.start(t,Math.random()*1.5);s.stop(t+d+.05)}
  function tn(d,type,f0,f1,pk,atk,dl){const a=AC();if(!a)return;const t=a.currentTime+(dl||0)+.005,o=a.createOscillator();o.type=type;o.frequency.setValueAtTime(f0,t);o.frequency.exponentialRampToValueAtTime(Math.max(20,f1),t+d);
    const g=a.createGain();g.gain.setValueAtTime(.0001,t);g.gain.exponentialRampToValueAtTime(pk,t+atk);g.gain.exponentialRampToValueAtTime(.0001,t+d);o.connect(g);g.connect(a._m);o.start(t);o.stop(t+d+.05)}
  let lastSfx=0;
  const SFX={
    swish(k=1){nz(.16,'bandpass',2200*k,500,1.6,.22,.03);nz(.1,'highpass',5000,2500,.7,.05,.02)},
    hit(k=1){tn(.14,'sine',190,55,.5*k,.003);nz(.09,'lowpass',3600,400,.9,.35*k,.002);nz(.05,'highpass',6000,3000,.8,.14*k,.001);tn(.3,'triangle',2800,2300,.05*k,.002)},
    ping(){tn(.5,'sine',2600,2500,.07,.002);tn(.4,'sine',3900,3800,.04,.002)},
    boom(k=1){tn(1.1*k,'sine',95,26,.95,.006);nz(1.2*k,'lowpass',900,60,.7,.6,.01);nz(.1,'bandpass',2000,700,1,.3,.002)},
    rise(d){nz(d,'bandpass',200,4200,2.2,.3,d*.85);tn(d,'sawtooth',90,720,.06,d*.9)},
    tick(f=1){tn(.06,'square',1500*f,1100*f,.05,.001);nz(.05,'highpass',7000,5000,.6,.06,.001)},
    shatter(){for(let i=0;i<9;i++)nz(.1+Math.random()*.15,'bandpass',3000+Math.random()*4000,1500,3,.12,.002,Math.random()*.22)},
    dash(){nz(.28,'bandpass',300,3800,1.2,.4,.06);tn(.2,'sawtooth',160,900,.05,.02)}
  };
  function sfxThrottle(f){const n=performance.now();if(n-lastSfx<45)return;lastSfx=n;f()}
  // ---- particulas (pontos brilhantes)
  const PT=[];
  function spark(p,v,life,size,col,a=1,g=-3,drag=1.6){if(PT.length>2400)PT.shift();PT.push({p:p.slice(),v:v.slice(),life,max:life,size,col,a,g,drag})}
  function burst(p,n,sp,size,col,life,g=-3,up=0,dir=null,spread=1){for(let i=0;i<n;i++){let d=[rnd(-1,1),rnd(-1,1),rnd(-1,1)];d=vnorm(d);if(dir){d=vnorm(vadd(vmul(d,spread),vnorm(dir)))}
      const s=sp*rnd(.3,1);spark(p,[d[0]*s,d[1]*s+up,d[2]*s],life*rnd(.5,1),size*rnd(.5,1.4),col,1,g)}}
  // ---- fitas (cortes no ar, aneis, pilares, linhas de velocidade)
  const RIB=[];
  function ribbon(o){o.age=0;o.hold=o.hold||0;o.col=o.col||CY;o.a=o.a==null?1:o.a;if(RIB.length>420)RIB.shift();RIB.push(o);return o}
  function line(A,B,w,life,col,opt={}){return ribbon({k:'line',A,B,w,life,col,grow:opt.grow==null?.05:opt.grow,hold:opt.hold||0,a:opt.a,flare:opt.flare||0})}
  function arc(C,R,ang,span,w,life,col,opt={}){return ribbon({k:'arc',C,R,ang,span,w,life,col,grow:opt.grow==null?.06:opt.grow,a:opt.a,hold:opt.hold||0})}
  function poly(P,w,life,col,o={}){return ribbon({k:'poly',P,fn:o.fn,w,life,col,grow:o.grow==null?.05:o.grow,hold:o.hold||0,a:o.a,wg:o.wg})}
  function ring(C,R0,R1,w,life,col,a=1){return ribbon({k:'ring',C,R0,R1,w,life,col,a})}
  // ---- numeros de dano
  let VPm=new Float32Array(16),camE=[0,1,3],camR=[1,0,0],camU=[0,1,0];
  const dmgEls=[];
  function proj(p){const x=VPm[0]*p[0]+VPm[4]*p[1]+VPm[8]*p[2]+VPm[12],y=VPm[1]*p[0]+VPm[5]*p[1]+VPm[9]*p[2]+VPm[13],w=VPm[3]*p[0]+VPm[7]*p[1]+VPm[11]*p[2]+VPm[15];if(w<=.01)return null;const r=cv.getBoundingClientRect();return[r.left+(x/w*.5+.5)*r.width,r.top+(1-(y/w*.5+.5))*r.height]}
  function dmg(n,p,cls){const s=proj(p);if(!s)return;const e=document.createElement('div');e.className='dmg'+(cls?' '+cls:'');e.textContent=String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g,'.');e.style.left=(s[0]+rnd(-18,18))+'px';e.style.top=s[1]+'px';document.body.appendChild(e);dmgEls.push(e);_dm(e);}
  function _dm(e){setTimeout(()=>{e.remove();const i=dmgEls.indexOf(e);if(i>=0)dmgEls.splice(i,1)},1000)}
  // ---- faixa de nome da habilidade
  function banner(n,tag,big){const b=$e('ban');if(!b)return;b.innerHTML='<small>'+tag+'</small><b>'+n+'</b>';b.className='';void b.offsetWidth;b.className='go'+(big?' big':'')}
  // ---- ghosts (imagens residuais): cada um guarda sua propria textura de ossos
  const GN=7,gTex=[],gUse=new Array(GN).fill(null);
  for(let i=0;i<GN;i++){const tx=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tx);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,4,NB,0,gl.RGBA,gl.FLOAT,null);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);gTex.push(tx)}
  const GH=[],gQ=new Float32Array(ND*4),gP=new Float32Array(3),gpQ=new Float32Array(NB*4),gpT=new Float32Array(NB*3),gW=new Float32Array(NB*16),gSK=new Float32Array(NB*16);
  function skFromClip(ci_,fr){const c=clips[ci_];sample(c,clp(fr,0,c.nf-1)/c.fps,gQ,gP);gpQ.set(bindQ);gpT.set(bindT);for(let j=0;j<ND;j++){const k=driven[j];for(let i=0;i<4;i++)gpQ[k*4+i]=gQ[j*4+i]}for(let i=0;i<3;i++)gpT[PEL*3+i]=gP[i];
    fk(gpQ,gpT,gW);for(let k=0;k<NB;k++)M4.mul(gSK.subarray(k*16,k*16+16),gW.subarray(k*16,k*16+16),INV.subarray(k*16,k*16+16));return gSK}
  function addGhost(skm,o){let idx=gUse.indexOf(null);if(idx<0){let oi=0,oa=-1;GH.forEach((g,i)=>{if(g.age>oa){oa=g.age;oi=i}});idx=GH[oi].tex;GH.splice(oi,1)}
    gUse[idx]=1;gl.bindTexture(gl.TEXTURE_2D,gTex[idx]);gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,4,NB,gl.RGBA,gl.FLOAT,skm);
    GH.push({tex:idx,age:0,life:(o.life||.3)*.8,col:o.col||CY,a:(o.a==null?.9:o.a)*.5,A:Object.assign({x:act.x,y:act.y,z:act.z,yaw:act.yaw},o.A||{}),to:o.to||null,fat:o.fat||0})}
  const ghostNow=(o)=>addGhost(SK,o);
  // ---- laminas: pontos de base/ponta no mundo + historico (rastro)
  const BL=[0,1].map(()=>({tip:[0,0,0],base:[0,0,0],pt:null,v:[0,0,0],sp:0,hist:[]}));
  const wmT=new Float32Array(16);
  function bladeUpdate(dt){for(let i=0;i<2;i++){const b=BL[i];M4.mul(wmT,W.subarray(mw[i].w.bone*16,mw[i].w.bone*16+16),attM[i]);
      const tip=toW([wmT[12]+wmT[4]*.62,wmT[13]+wmT[5]*.62,wmT[14]+wmT[6]*.62]),base=toW([wmT[12]+wmT[4]*.14,wmT[13]+wmT[5]*.14,wmT[14]+wmT[6]*.14]);
      if(b.pt&&dt>0){b.v=vmul(vsub(tip,b.pt),1/dt);b.sp=vlen(b.v)}else{b.v=[0,0,0];b.sp=0}
      b.pt=tip;b.tip=tip;b.base=base;for(const h of b.hist)h.age+=dt;
      const k=clp((b.sp-5)/24,0,1)*(trailBoost>0?1:1)+trailBoost;const kk=clp(k,0,1);
      if(dt>0&&kk>.04&&act.vis&&showW)b.hist.push({b:vmix(base,tip,.45),t:tip,age:0,k:kk});
      while(b.hist.length&&b.hist[0].age>trailLife*.7)b.hist.shift()}}
  let trailBoost=0,trailLife=.3,trailCol=CY;
  const tcol=i=>auraOn()?vmix(AUC[i],trailCol,.22):trailCol;
  // ---- reacao do alvo + impacto
  function hitFx(h,pw,o={}){const c=o.at||tgPoint();if(!o.col&&auraOn())o=Object.assign({},o,{col:vmix(AUC[h],[1,1,1],.15)});const v=BL[h].sp>3?vnorm(BL[h].v):[rnd(-1,1),rnd(-.4,.6),0];
    const ang=Math.atan2(vdot(v,camU),vdot(v,camR));
    if(!o.noArc){arc(c,.34*pw*(o.big||1),ang+Math.PI/2,2.2,.07*pw,.22,o.col||CY,{grow:.045});line(vadd(c,vmul(v,-.9*pw)),vadd(c,vmul(v,.9*pw)),.035*pw,.16,WH,{grow:.03})}
    burst(c,Math.round(10+10*pw),3.4*pw,.05,o.col||CY,.42,-5,.4,vmul(v,1.2));burst(c,5,5*pw,.03,WH,.28,-6,0,vmul(v,2));
    spark(c,[0,0,0],.09,.55*pw,WH,1,0,0);spark(c,[0,0,0],.18,.3*pw,o.col||CY,.8,0,0);
    TG.flash=1;TG.jit=Math.max(TG.jit,.5*pw);if(!o.noKnock){TG.tv+=vdot(v,[0,0,1])*2.6*pw+rnd(-.5,.5);TG.tilt=clp(TG.tilt,-.35,.35)}
    shake(.05*pw+(o.shake||0));SFX.hit(.6+.4*pw);
    if(o.dmg!==false){const n=(o.dmg||(180+Math.random()*120))*pw;dmg(n,[c[0],c[1]+.25,c[2]],pw>1.4?'crit':'')}}
  function shock(c,R,w,col,o={}){ring([c[0],.02,c[2]],.1,R,w,o.life||.65,col||CY,o.a||1);if(o.dust)burst([c[0],.05,c[2]],o.dust,R*1.6,.07,[.7,.85,1],.7,-1,.3);shake(o.sh||.2)}
  function cracks(c,n,len,col,life){for(let i=0;i<n;i++){const a=i/n*6.283+rnd(-.2,.2),L=len*rnd(.55,1);const p0=[c[0]+Math.cos(a)*.15,.015,c[2]+Math.sin(a)*.15],p1=[c[0]+Math.cos(a)*L,.015,c[2]+Math.sin(a)*L];
      ribbon({k:'line',A:p0,B:p1,w:.07,life,col,grow:.12,hold:life*.45,flat:true,a:.9})}}
  // ---- fita: geometria
  const RBUF=new Float32Array(9*6*20000);let rn=0;
  function rv(p,a,ac2,u,c){RBUF[rn++]=p[0];RBUF[rn++]=p[1];RBUF[rn++]=p[2];RBUF[rn++]=a;RBUF[rn++]=ac2;RBUF[rn++]=u;RBUF[rn++]=c[0];RBUF[rn++]=c[1];RBUF[rn++]=c[2]}
  function strip(P,Wd,Al,col,flat,glow){const n=P.length;if(n<2||rn>RBUF.length-9*6*(n+2))return;const L=[],R=[];
    for(let i=0;i<n;i++){const a=P[Math.max(0,i-1)],b=P[Math.min(n-1,i+1)];let T=vnorm(vsub(b,a)),s;if(flat)s=vcross(T,[0,1,0]);else s=vcross(T,vsub(camE,P[i]));let l=vlen(s);if(l<1e-5){s=camR;l=1}s=vmul(s,Wd[i]*.5/l);L.push(vadd(P[i],s));R.push(vsub(P[i],s))}
    const zo=glow?10:0;for(let i=0;i<n-1;i++){const u0=i/(n-1)+zo,u1=(i+1)/(n-1)+zo;
      rv(L[i],Al[i],0,u0,col);rv(R[i],Al[i],1,u0,col);rv(L[i+1],Al[i+1],0,u1,col);rv(R[i],Al[i],1,u0,col);rv(R[i+1],Al[i+1],1,u1,col);rv(L[i+1],Al[i+1],0,u1,col)}}
  function emit(P,Wd,Al,col,flat,wg){strip(P,Wd,Al,col,flat,false);if(wg)strip(P,Wd.map(x=>x*wg),Al.map(x=>x*.9),col,flat,true)}
  function buildRibbons(dt){rn=0;
    // rastros das laminas (faixa entre a base e a ponta, historico)
    for(let i=0;i<2;i++){const H=BL[i].hist;if(H.length<2)continue;for(const g of [0,1]){const n=H.length;const P=[],A=[],Cc=[];
        for(let j=0;j<n-1;j++){const a=H[j],b=H[j+1];const u0=clp(1-a.age/(trailLife*.7),0,1),u1=clp(1-b.age/(trailLife*.7),0,1);
          const w=g?1.35:1;const mid=(p,q)=>vmix(p,q,.5);const ma=mid(a.b,a.t),mb=mid(b.b,b.t);
          const ba=vadd(ma,vmul(vsub(a.b,ma),w)),ta=vadd(ma,vmul(vsub(a.t,ma),w)),bb=vadd(mb,vmul(vsub(b.b,mb),w)),tb=vadd(mb,vmul(vsub(b.t,mb),w));
          const al0=u0*u0*a.k*(g?.45:.8),al1=u1*u1*b.k*(g?.45:.8),z0=(1-u0)+(g?10:0),z1=(1-u1)+(g?10:0);
          const tc=tcol(i);rv(ba,al0,0,z0,tc);rv(ta,al0,1,z0,tc);rv(bb,al1,0,z1,tc);rv(ta,al0,1,z0,tc);rv(tb,al1,1,z1,tc);rv(bb,al1,0,z1,tc)}}}
    for(let q=RIB.length-1;q>=0;q--){const r=RIB[q];r.age+=dt;if(r.age>=r.life){RIB.splice(q,1);continue}
      const u=r.age/r.life,fadeU=r.age<r.hold?0:(r.age-r.hold)/Math.max(1e-3,r.life-r.hold);let al=r.a*Math.pow(1-fadeU,1.5)*(r.flare?1+r.flare*Math.sin(u*3.14):1);
      const N=r.k==='ring'?56:22,P=[],Wd=[],Al=[];
      if(r.k==='line'){const g=clp(r.age/Math.max(r.grow,1e-3),0,1);const eg=1-Math.pow(1-g,2);for(let i=0;i<=N;i++){const s=i/N;P.push(vmix(r.A,r.B,s*eg));const tp=Math.pow(Math.sin(clp(s,.001,.999)*3.14159),.7);Wd.push(r.w*tp*(1+u*.6)*(r.flare?1+r.flare*Math.sin(u*3.14):1));Al.push(al*tp)}emit(P,Wd,Al,r.col,r.flat,2.4)}
      else if(r.k==='arc'){const g=clp(r.age/Math.max(r.grow,1e-3),0,1);const eg=1-Math.pow(1-g,2);for(let i=0;i<=N;i++){const s=i/N*eg;const th=r.ang+r.span*(s-.5);P.push([r.C[0]+(Math.cos(th)*camR[0]+Math.sin(th)*camU[0])*r.R,r.C[1]+(Math.cos(th)*camR[1]+Math.sin(th)*camU[1])*r.R,r.C[2]+(Math.cos(th)*camR[2]+Math.sin(th)*camU[2])*r.R]);
          const tp=Math.pow(Math.sin(clp(i/N,.001,.999)*3.14159),.6);Wd.push(r.w*tp*(1+u*.5));Al.push(al*tp)}emit(P,Wd,Al,r.col,false,2.4)}
      else if(r.k==='poly'){const pts=r.fn?r.fn(r):r.P,n=pts.length,g=clp(r.age/Math.max(r.grow,1e-3),0,1),m=Math.max(2,Math.round(n*g));for(let i=0;i<m;i++){const tp=Math.pow(Math.sin(clp(i/(m-1),.001,.999)*3.14159),.6);P.push(pts[i]);Wd.push(r.w*tp);Al.push(al*tp)}emit(P,Wd,Al,r.col,false,r.wg||2.2)}
      else if(r.k==='ring'){const e=1-Math.pow(1-clp(u*1.15,0,1),2.4),R=lerp(r.R0,r.R1,e);for(let i=0;i<=N;i++){const th=i/N*6.2832;P.push([r.C[0]+Math.cos(th)*R,r.C[1],r.C[2]+Math.sin(th)*R]);Wd.push(r.w*(1-u*.7)+.02);Al.push(al*(1-u))}emit(P,Wd,Al,r.col,true,2.2)}}
    return rn/9}
  // ---- chao brilhante (marcas/rachaduras vem como fitas planas)
  // ---- programas extras
  const VS_RB=`#version 300 es
precision highp float;layout(location=0) in vec3 aP;layout(location=1) in vec3 aA;layout(location=2) in vec3 aC;uniform mat4 uVP;out vec3 vA;out vec3 vC;
void main(){vA=aA;vC=aC;gl_Position=uVP*vec4(aP,1.);}`;
  const FS_RB=`#version 300 es
precision highp float;in vec3 vA;in vec3 vC;out vec4 o;
void main(){bool glow=vA.z>5.;float u=glow?vA.z-10.:vA.z;float x=(vA.y-.5)*2.;vec3 c;float a;
  if(glow){a=vA.x*exp(-x*x*2.4)*.2;c=vC;}
  else{float h=exp(-x*x*3.2),core=exp(-x*x*38.);a=vA.x*(h*.62+core*1.1);c=mix(vC,vec3(1.),clamp(core*1.4+h*.18,0.,1.));}
  o=vec4(c*a*1.35,a);}`;
  const PRB=prg(VS_RB,FS_RB),PP=prg(VS_P,FS_P);
  const VS_GH=`#version 300 es
precision highp float;precision highp int;
layout(location=0) in vec4 aPos;layout(location=1) in vec4 aNrm;layout(location=3) in uvec4 aJ;layout(location=4) in vec4 aW;
uniform sampler2D uBones;uniform vec3 uCtr;uniform vec3 uExt;uniform mat4 uVP;uniform mat4 uAct;out vec3 vN;out vec3 vP;
mat4 B(uint i){int r=int(i);return mat4(texelFetch(uBones,ivec2(0,r),0),texelFetch(uBones,ivec2(1,r),0),texelFetch(uBones,ivec2(2,r),0),texelFetch(uBones,ivec2(3,r),0));}
void main(){vec4 p=vec4(uCtr+aPos.xyz*uExt,1.);mat4 m=B(aJ.x)*aW.x+B(aJ.y)*aW.y+B(aJ.z)*aW.z+B(aJ.w)*aW.w;vec4 w=uAct*(m*p);vP=w.xyz;vN=mat3(uAct)*mat3(m)*normalize(aNrm.xyz);gl_Position=uVP*w;}`;
  const FS_GH=`#version 300 es
precision highp float;in vec3 vN;in vec3 vP;uniform vec3 uCam;uniform vec3 uCol;uniform float uA;out vec4 o;
void main(){vec3 N=normalize(vN);if(!gl_FrontFacing)N=-N;vec3 V=normalize(uCam-vP);float f=1.-max(dot(N,V),0.);float r=pow(f,1.7);float a=uA*(.16+r*1.15);vec3 c=mix(uCol,vec3(1.),r*.55)*a;o=vec4(c,a);}`;
  const PGH=prg(VS_GH,FS_GH);
  // ---- boneco de treino (procedural)
  const VS_D=`#version 300 es
precision highp float;layout(location=0) in vec3 aP;layout(location=1) in vec3 aN;uniform mat4 uVP;uniform mat4 uM;out vec3 vN;out vec3 vP;
void main(){vec4 w=uM*vec4(aP,1.);vP=w.xyz;vN=mat3(uM)*aN;gl_Position=uVP*w;}`;
  const FS_D=`#version 300 es
precision highp float;in vec3 vN;in vec3 vP;uniform vec3 uCam;uniform vec3 uCol;uniform float uF;uniform float uAl;out vec4 o;
void main(){vec3 N=normalize(vN);vec3 V=normalize(uCam-vP);vec3 L=normalize(vec3(.5,.9,.7));float d=max(dot(N,L),0.);vec3 c=uCol*(.38+.72*d)+vec3(.16,.2,.28)*pow(1.-max(dot(N,V),0.),2.);c=mix(c,vec3(.7,.95,1.),uF);o=vec4(c,uAl);}`;
  const PD=prg(VS_D,FS_D);
  const dumV=[],dumI=[];
  function addCyl(a,b,r0,r1,seg){const base=dumV.length/6,ax=vnorm(vsub(b,a));let u=Math.abs(ax[1])<.9?vcross(ax,[0,1,0]):vcross(ax,[1,0,0]);u=vnorm(u);const v=vcross(ax,u);
    for(let k=0;k<2;k++){const c=k?b:a,r=k?r1:r0;for(let i=0;i<=seg;i++){const th=i/seg*6.2832,d=vadd(vmul(u,Math.cos(th)),vmul(v,Math.sin(th)));dumV.push(c[0]+d[0]*r,c[1]+d[1]*r,c[2]+d[2]*r,d[0],d[1],d[2])}}
    for(let i=0;i<seg;i++){const a0=base+i,b0=base+seg+1+i;dumI.push(a0,b0,a0+1,a0+1,b0,b0+1)}}
  function addSph(c,r,la,lo){const base=dumV.length/6;for(let i=0;i<=la;i++){const ph=i/la*3.14159;for(let j=0;j<=lo;j++){const th=j/lo*6.2832,d=[Math.sin(ph)*Math.cos(th),Math.cos(ph),Math.sin(ph)*Math.sin(th)];dumV.push(c[0]+d[0]*r,c[1]+d[1]*r,c[2]+d[2]*r,d[0],d[1],d[2])}}
    for(let i=0;i<la;i++)for(let j=0;j<lo;j++){const a0=base+i*(lo+1)+j,b0=a0+lo+1;dumI.push(a0,b0,a0+1,a0+1,b0,b0+1)}}
  addCyl([0,.01,0],[0,.04,0],.24,.2,24);addCyl([0,.04,0],[0,.86,0],.045,.045,14);addCyl([-.27,.7,0],[.27,.7,0],.035,.035,12);addCyl([0,.5,0],[0,.76,0],.09,.07,16);addSph([0,.93,0],.085,12,16);addSph([-.27,.7,0],.04,8,10);addSph([.27,.7,0],.04,8,10);
  const dumVao=gl.createVertexArray();gl.bindVertexArray(dumVao);const dvb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,dvb);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array(dumV),gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,24,0);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,3,gl.FLOAT,false,24,12);
  const dib=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,dib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint16Array(dumI),gl.STATIC_DRAW);const dumN=dumI.length;gl.bindVertexArray(null);
  const rbVao=gl.createVertexArray(),rbBuf=gl.createBuffer();gl.bindVertexArray(rbVao);gl.bindBuffer(gl.ARRAY_BUFFER,rbBuf);gl.bufferData(gl.ARRAY_BUFFER,RBUF.byteLength,gl.DYNAMIC_DRAW);
  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,36,0);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,3,gl.FLOAT,false,36,12);gl.enableVertexAttribArray(2);gl.vertexAttribPointer(2,3,gl.FLOAT,false,36,24);gl.bindVertexArray(null);
  const PBUF=new Float32Array(2400*8),ppVao=gl.createVertexArray(),ppBuf=gl.createBuffer();gl.bindVertexArray(ppVao);gl.bindBuffer(gl.ARRAY_BUFFER,ppBuf);gl.bufferData(gl.ARRAY_BUFFER,PBUF.byteLength,gl.DYNAMIC_DRAW);
  gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,3,gl.FLOAT,false,32,0);gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,1,gl.FLOAT,false,32,12);gl.enableVertexAttribArray(2);gl.vertexAttribPointer(2,4,gl.FLOAT,false,32,16);gl.bindVertexArray(null);
  // sobreposicoes de tela
  const dimEl=document.createElement('div');dimEl.id='dim';document.body.appendChild(dimEl);
  // =====================================================================================================
  //  habilidades
  // =====================================================================================================
  const ci=n=>{const i=clips.findIndex(c=>c.name===n);if(i<0)throw new Error('clip '+n);return i};
  const CI={};['Idle','4Combo A 1','4Combo A 2','4Combo A 3','4Combo A 4','Dash A','Rapid ALL','Upper ALL','Jump Spiral ALL','Skill G Finishing ALL','Skill A','Skill B','Skill C','Skill E ALL','Skill H','Skill J ALL'].forEach(n=>CI[n]=ci(n));
  let run=null,trailOn=false;
  const BQ={i:0,last:-9};
  const rr=(a,b)=>rnd(a,b);
  function randDir(){const a=rr(0,6.283),e=rr(-.5,.5);return[Math.cos(a)*Math.cos(e),Math.sin(e),Math.sin(a)*Math.cos(e)]}
  function slashRand(pw,col,o={}){const c=vadd(tgPoint(),[rr(-.14,.14),rr(-.2,.2),rr(-.1,.1)]);const a=rr(0,6.283);const R=rr(.2,.34)*pw;
    arc(c,R,a,rr(1.6,2.4),.05*pw,.17,col||CY,{grow:.03});const d=[Math.cos(a)*camR[0]+Math.sin(a)*camU[0],Math.cos(a)*camR[1]+Math.sin(a)*camU[1],Math.cos(a)*camR[2]+Math.sin(a)*camU[2]];
    line(vadd(c,vmul(d,-R*1.7)),vadd(c,vmul(d,R*1.7)),.03*pw,.13,WH,{grow:.025});burst(c,6,2.8*pw,.04,col||CY,.32,-4);spark(c,[0,0,0],.07,.34*pw,WH,1,0,0);
    TG.flash=Math.max(TG.flash,.42);TG.jit=Math.max(TG.jit,.25);TG.tv+=rr(-.5,.5)*.6;shake(.018);sfxThrottle(()=>SFX.hit(.45));if(o.num!==false)dmg(rr(35,75),[c[0],c[1]+.2,c[2]],'')}
  function skStep(fn){if(run)fn(run)}
  // ---- definicoes
  const DEF={};
  // BASICO: 4 golpes em cadeia (cada toque = proximo golpe)
  DEF.basic={nome:'Corte Relâmpago',tag:'Básico',
    seq:r=>{const n=['4Combo A 1','4Combo A 2','4Combo A 3','4Combo A 4'][r.part];return[{clip:CI[n],f0:0,f1:clips[CI[n]].nf-1,spd:1.55}]},
    ev:[],start(r){r.part=BQ.i;trailLife=.26;trailCol=CY;
      const P=[[{s:0,f:9,h:0,pw:1},{s:0,f:15,h:1,pw:.6,quiet:1}],[{s:0,f:6,h:1,pw:1.05}],[{s:0,f:7,h:0,pw:1.1}],[{s:0,f:4,h:0,pw:1.2,both:1},{s:0,f:14,h:0,pw:1.9,both:1,fin:1}]][r.part];
      r.ev=P.map(e=>({s:0,f:e.f,fn:()=>{if(e.quiet){SFX.swish(1.2);return}hitFx(e.h,e.pw,{col:r.part===3?[.7,.55,1]:CY});if(e.both){hitFx(1-e.h,e.pw,{col:CY,dmg:false});}
        if(e.fin){arc(tgPoint(),.7,.8,2.6,.1,.3,WH,{grow:.05});arc(tgPoint(),.7,-.8,2.6,.1,.3,CY,{grow:.05});flash(.18,'#bfeaff');slow(.2,.07);shake(.4);TG.tv+=3;burst(tgPoint(),30,5,.06,CY,.6,-5)}}}));
      if(r.part===0)SFX.swish(1);},
    tick(r,dt,f){const Z=Math.min((TG.on?TG.z:r.bz+1.7)-.95,r.bz+[.55,.7,.78,.78][r.part]);act.z+=(Z-act.z)*clp(dt*15,0,1)},end(r){BQ.last=performance.now()/1000;BQ.i=(BQ.i+1)%4}};
  // S1: INVESTIDA FANTASMA — atravessa o alvo em um piscar de olhos, deixando imagens residuais
  DEF.s1={nome:'Investida Fantasma',tag:'Skill 1',
    seq:()=>[{clip:CI['Dash A'],f0:0,f1:25,spd:1.12}],
    start(r){r.hit=false;r.gt=0;trailLife=.42;trailCol=CY;SFX.dash();banner(this.nome,this.tag);burst(vadd([act.x,.5,act.z],[0,0,0]),16,2.5,.05,CY,.35,0);ghostNow({life:.35,col:CY,a:.8})},
    ev:[],
    tick(r,dt,f){const Z0=r.bz,Z1=r.bz+3.45;
      if(f<2){trailBoost=0}else if(f<10){const u=ss(2,9.2,f);act.z=lerp(Z0,Z1,u);trailBoost=1;
        r.gt-=dt;if(r.gt<=0){r.gt=.022;ghostNow({life:.4,col:CY,a:.85});}
        for(let i=0;i<3;i++){const o=[rr(-.5,.5),rr(.1,.95),0];const p=toW(o);line([p[0],p[1],p[2]-rr(.2,.5)],[p[0],p[1],p[2]-rr(1.2,2.4)],.012,.18,i%2?WH:CY,{grow:.02})}
        if(!r.hit&&act.z>=TG.z-.2){r.hit=true;hitFx(0,1.9,{big:1.6,col:CY,shake:.25,dmg:900});hitFx(1,1.9,{big:1.6,col:WH,dmg:false});
          line([TG.x-1.6,.6,TG.z],[TG.x+1.6,.6,TG.z],.1,.38,WH,{grow:.03,flare:.7});line([TG.x-1.2,.1,TG.z-.1],[TG.x+1.2,1.1,TG.z+.1],.05,.3,CY,{grow:.03});line([TG.x+1.2,.1,TG.z-.1],[TG.x-1.2,1.1,TG.z+.1],.05,.3,CY,{grow:.03});
          flash(.22,'#cfefff');slow(.16,.09);TG.tv+=3.5;ring([TG.x,.03,TG.z],.1,2.2,.12,.45,CY);burst(tgPoint(),40,6,.06,CY,.6,-5);SFX.boom(.35)}}
      else{trailBoost=f<18?.25:0;act.z=Z1}},
    end(r){BASE.x=act.x;BASE.z=act.z;trailBoost=0}};
  // S2: TEMPESTADE DE ACO — rajada de dezenas de cortes em um segundo
  const RAPID_F=[5,7,9,11,12,14,15,18,18,20,21,23,24,26,27,29,30,32,33,35,36,38,39,41,42,44,45,47,48,50,51];
  DEF.s2={nome:'Tempestade de Aço',tag:'Skill 2',
    seq:()=>[{clip:CI['Rapid ALL'],f0:0,f1:84,spd:1.4}],appr:{f0:0,f1:5,r0:77,r1:83,d:1.0},
    start(r){r.gt=0;trailLife=.34;trailCol=CY;banner(this.nome,this.tag);SFX.rise(.5);trailBoost=.9;
      r.ev=RAPID_F.map((f,i)=>({s:0,f,fn:()=>{slashRand(i%5===0?1.25:.95,i%3===0?WH:(i%2?CY:VI))}}));
      r.ev.push({s:0,f:61,fn:()=>{hitFx(0,1.5,{big:1.3,col:CY,dmg:1500});hitFx(1,1.5,{big:1.3,col:WH,dmg:false})}});
      r.ev.push({s:0,f:63,fn:()=>{const c=tgPoint();arc(c,.95,.7,2.8,.13,.34,WH,{grow:.05});arc(c,.95,-.7,2.8,.13,.34,CY,{grow:.05});line([c[0]-1.8,c[1]-.8,c[2]],[c[0]+1.8,c[1]+.8,c[2]],.09,.4,WH,{flare:.8});line([c[0]+1.8,c[1]-.8,c[2]],[c[0]-1.8,c[1]+.8,c[2]],.09,.4,CY,{flare:.8});
        burst(c,70,7,.07,CY,.8,-5);burst(c,30,9,.05,WH,.5,-5);flash(.3,'#e3f6ff');slow(.12,.14);shake(.55);TG.tv+=4;ring([c[0],.03,c[2]],.1,3,.1,.55,CY);SFX.boom(.5);dmg(4999,[c[0],c[1]+.3,c[2]],'crit')}});},
    ev:[],
    tick(r,dt,f){if(f>5&&f<56){r.gt-=dt;if(r.gt<=0){r.gt=.045;ghostNow({life:.28,col:i3(),a:.5,A:{x:act.x+rr(-.22,.22),z:act.z+rr(-.05,.1),yaw:rr(-.25,.25)}})}
        TG.jit=Math.max(TG.jit,.35);if(Math.random()<.5){const p=[TG.x+rr(-.5,.5),rr(.1,1.1),TG.z+rr(-.4,.4)];spark(p,[rr(-1,1),rr(-1,1),rr(-3,-1)],.3,.05,CY,1,0)}}
      trailBoost=f>3&&f<60?.85:.2},
    end(r){trailBoost=0}};
  const i3=()=>[CY,VI,WH][(Math.random()*3)|0];
  // S3: CEIFA ASCENDENTE — lanca o alvo para o ar e o persegue na altura
  DEF.s3={nome:'Ceifa Ascendente',tag:'Skill 3',
    seq:()=>[{clip:CI['Upper ALL'],f0:0,f1:80,spd:1.3}],appr:{f0:0,f1:3,r0:70,r1:78,d:.95},
    start(r){r.launched=false;r.slam=false;r.gt=0;trailLife=.4;trailCol=CY;banner(this.nome,this.tag);SFX.rise(.35);trailBoost=.7;
      const rise=(pw,col)=>()=>{const c=tgPoint();arc([c[0],c[1]-.1,c[2]],.9*pw,Math.PI/2,2.5,.12*pw,.3,col,{grow:.05});line([c[0],0,c[2]],[c[0],c[1]+1.4*pw,c[2]],.06*pw,.22,WH,{grow:.05});burst([c[0],.1,c[2]],26,4*pw,.05,col,.55,-4,2.4);flash(.1,'#cfefff');shake(.2*pw)};
      const air=(h,pw,col)=>()=>{hitFx(h,pw,{at:tgPoint(),col,dmg:300+rr(0,200)})};
      r.ev=[{s:0,f:2,fn:()=>{hitFx(0,1.1,{col:CY});hitFx(1,1.1,{col:CY,dmg:false})}},
        {s:0,f:11,fn:()=>{rise(1.2,CY)();TG.vy=4.3;TG.air=true;r.launched=true;hitFx(1,1.5,{big:1.3,col:WH,dmg:700});SFX.boom(.4)}},
        {s:0,f:13,fn:()=>{rise(1.5,WH)();slow(.2,.07)}},
        {s:0,f:31,fn:air(0,1.15,CY)},{s:0,f:35,fn:air(1,1.2,VI)},{s:0,f:39,fn:()=>{air(0,1.5,WH)();TG.vy=3.3;TG.spin=14;flash(.14,'#cfefff')}},
        {s:0,f:46,fn:()=>{const c=tgPoint();hitFx(1,1.9,{big:1.6,col:CY,at:c,dmg:2200});arc(c,.8,-1,2.8,.12,.3,WH);TG.vy=-9;slow(.2,.08)}}]},
    ev:[],
    tick(r,dt,f){TG.spin*=Math.pow(.2,dt);if(f>=12&&f<44&&Math.random()<.9){const c=tgPoint();spark([c[0]+rr(-.3,.3),c[1]+rr(-.4,.4),c[2]+rr(-.2,.2)],[rr(-.5,.5),rr(.5,2),rr(-.5,.5)],.4,.045,CY,1,-1)}
      if(f>=12&&f<44){r.gt-=dt;if(r.gt<=0){r.gt=.05;ghostNow({life:.3,col:CY,a:.5})}}
      if(TG.air&&TG.y<=0&&TG.vy<0&&r.launched&&f>46&&!r.slam){r.slam=true;TG.air=false;shock([TG.x,0,TG.z],3.6,.14,CY,{dust:50,sh:.55});cracks([TG.x,0,TG.z],12,2.4,CY,.9);flash(.3,'#e3f6ff');SFX.boom(.8);slow(.18,.12);TG.vy=1.6;TG.tilt=.4;dmg(3500,[TG.x,.4,TG.z],'crit')}},
    end(r){trailBoost=0}};
  // S4: ESPIRAL CELESTE — ciclone aereo que arrasta o alvo e termina em mergulho devastador
  DEF.s4={nome:'Espiral Celeste',tag:'Skill 4',
    seq:()=>[{clip:CI['Jump Spiral ALL'],f0:0,f1:92,spd:1.45}],appr:{f0:0,f1:12,r0:84,r1:90,d:.75},
    start(r){r.gt=0;r.rg=0;r.slam=false;trailLife=.46;trailCol=VI;banner(this.nome,this.tag);SFX.rise(.6);trailBoost=1;
      const h=(f,hand,pw,col)=>({s:0,f,fn:()=>{hitFx(hand,pw,{col,dmg:260+rr(0,260)})}});
      r.ev=[h(4,0,1,CY),h(7,0,1.3,WH),h(20,0,1.2,CY),h(22,1,1.2,VI),h(35,1,1.3,CY),h(38,0,1.3,WH),h(41,1,1.3,VI),h(43,0,1.3,CY),h(47,1,1.4,WH),
        {s:0,f:61,fn:()=>{const c=[TG.x,.1,TG.z];hitFx(1,2.4,{big:2,col:WH,dmg:6500,at:[c[0],.5,c[2]]});shock(c,4.4,.16,CY,{dust:70,sh:.9});ring([c[0],.03,c[2]],.1,6,.1,.8,VI);cracks(c,16,3,CY,1.1);flash(.45,'#fff');slow(.14,.16);TG.vy=-10;TG.air=false;TG.tilt=.6;SFX.boom(1);
          for(let i=0;i<6;i++)line([c[0]+rr(-.4,.4),4,c[2]+rr(-.4,.4)],[c[0]+rr(-.4,.4),0,c[2]+rr(-.4,.4)],.05,.5,i%2?WH:CY,{hold:.05});}},
        h(85,0,1,CY),h(88,1,1,VI)]},
    ev:[],
    tick(r,dt,f){const py=W[PEL*16+13];
      if(f>=32&&f<52){TG.air=true;TG.vy=0;TG.y=lerp(TG.y,Math.max(.1,py*.55),clp(dt*10,0,1));TG.spin=18;TG.x*=Math.pow(.02,dt);
        r.rg-=dt;if(r.rg<=0){r.rg=.05;const y=py*.9+rr(-.4,.5);ring([act.x,Math.max(.05,y),act.z],.2,1.5,.04,.4,i3(),.9);burst([act.x,y,act.z],4,3,.04,CY,.4,0)}
        r.gt-=dt;if(r.gt<=0){r.gt=.04;ghostNow({life:.32,col:VI,a:.55})}}
      else if(f>=4&&f<32){r.gt-=dt;if(r.gt<=0){r.gt=.06;ghostNow({life:.3,col:CY,a:.45})}}
      trailBoost=f>3&&f<66?1:.2;if(f>=52&&f<61){TG.air=true;TG.vy=-1.2}},
    end(r){trailBoost=0;TG.spin=0}};
  // ULTIMATE: MIL LAMINAS — DANCA DO VAZIO
  const CUTS=14;
  // ---- ULTIMATE: convergencia (fogo+gelo) -> danca dos cortes (o proprio duelista surge ao redor) -> ascensao em helice -> mergulho -> impacto dividido
  const ULT_POSES=[['Skill A',8],['Skill B',8],['Skill C',8],['Skill E ALL',10],['Skill H',6],['Skill J ALL',8],['Rapid ALL',8],['Dash A',6]];
  const ULT_N=12,T_A=1.3,T_B1=T_A+2.15,T_C1=T_B1+.8,T_D1=T_C1+.5,T_IMP2=T_D1+.2;
  function helixPts(cx,cz,y0,y1,R,turns,ph){const P=[];for(let i=0;i<=26;i++){const s=i/26,th=ph+turns*6.2832*s,rr_=R*(1-.35*s);P.push([cx+Math.cos(th)*rr_,lerp(y0,y1,s),cz+Math.sin(th)*rr_])}return P}
  const helixPair=(life,fnc)=>{[FIRE_C,ICE_C].forEach((col,q)=>poly([[0,0,0],[0,0,1]],.05,life,col,{grow:.18,wg:2.6,fn:r=>fnc(r,q)}))};
  DEF.ult={nome:'Mil Lâminas: Dança do Vazio',tag:'ULTIMATE',custom:true,
    start(r){r.el=0;r.k=0;r.next=T_A+.1;r.gt=0;r.imp=false;r.lines=[];r.st=null;r.ph=0;trailLife=.4;trailCol=MG;banner(this.nome,this.tag,true);SFX.rise(1.2);SFX.boom(.35);trailBoost=.5;blendDur=.12;
      setClipRaw(CI['Skill G Finishing ALL'],0);flash(.2,'#ffd9a8');
      helixPair(T_A,(rr_,q)=>{const e=Math.min(run?run.el:0,T_A),u=e/T_A;return helixPts(act.x,act.z,0,.4+1.5*u,lerp(1.15,.32,u*u),2.2,e*7*(q?-1:1)+q*3.1416)});
      for(let i=0;i<3;i++)ring([act.x,.03,act.z],3.4+i*.6,.25,.045,T_A*.9+i*.1,i%2?ICE_C:FIRE_C,.7)},
    tick(r,dt){r.el+=dt;const e=r.el;
      if(e<T_A){const u=e/T_A;t=lerp(0,11,ss(0,1,u))/30;CAMF.dim=ss(0,.8,u)*.55;CAMF.zt=lerp(1,.9,u);CAMF.yawk=u*.25;trailBoost=.3;
        r.gt-=dt;if(r.gt<=0){r.gt=.045;const a=rr(0,6.283),R=rr(.8,1.8),sl=Math.random()<.5;spark([act.x+Math.cos(a)*R,rr(.05,1.4),act.z+Math.sin(a)*R],[-Math.cos(a)*2.2,rr(.2,1),-Math.sin(a)*2.2],.55,.03,sl?FIRE_C:ICE_C,1,-.2)}
        if(e>T_A-.12&&!r.fl){r.fl=1;flash(.35,'#ffffff');shock([act.x,0,act.z],3,.07,WH,{sh:.1})}}
      else if(e<T_B1){ // danca: o duelista surge ao redor do alvo e corta
        CAMF.dim=.62;CAMF.zt=lerp(.9,1.35,ss(T_A,T_B1,e));CAMF.yawk=lerp(.25,3.6,ss(T_A,T_B1,e));CAMF.roll=Math.sin(e*2.4)*.018;act.vis=true;
        const S=r.st;
        if(e>=r.next&&r.k<ULT_N){const k=r.k++,u=k/(ULT_N-1);r.next=e+lerp(.22,.085,u*u);
          const a=k*2.4+rr(-.3,.3),R=1.15,c=tgPoint(),hi=k%3===2?rr(.5,1.1):0;
          const from=[c[0]+Math.cos(a)*R,hi,c[2]+Math.sin(a)*R],to=[c[0]-Math.cos(a)*R*.95,hi*.4,c[2]-Math.sin(a)*R*.95];
          if(act.vis)ghostNow({life:.25,col:k%2?ICE_C:FIRE_C,a:.5});
          BL.forEach(b=>{b.pt=null;b.hist.length=0});
          const P=ULT_POSES[(k*3+1)%ULT_POSES.length];setClipRaw(CI[P[0]],P[1]);blendDur=.05;
          const yaw=Math.atan2(to[0]-from[0],to[2]-from[2]);
          r.st={k,from,to,yaw,e0:e,dur:Math.max(.16,r.next-e),f0:P[1],hit:false,c};
          burst(vadd(from,[0,.7,0]),10,2.4,.03,k%2?ICE_C:FIRE_C,.35,-1);SFX.swish(1+u*.6);if(k%2===0)SFX.tick(1+u*.5);
          // corte colorido: lua crescente + linha fina permanente
          const dir=vnorm(vsub(to,from)),ang=Math.atan2(vdot(dir,camU),vdot(dir,camR));const col=k%2?ICE_C:FIRE_C;
          arc(c,.8+u*.3,ang+Math.PI/2+rr(-.6,.6),2.4,.07,.3,col,{grow:.05});
          if(r.lines.length<9)r.lines.push(line(vadd(c,vmul(dir,-1.6)),vadd(c,vmul(dir,1.6)),.022,9,col,{grow:.07,a:.7}))}
        if(r.st){const q=r.st,w=clp((e-q.e0)/q.dur,0,1),m=ei(clp(w*1.6,0,1));act.x=lerp(q.from[0],q.to[0],m);act.z=lerp(q.from[2],q.to[2],m);act.y=lerp(q.from[1],q.to[1],m);act.yaw=q.yaw;t=(q.f0+(e-q.e0)*30*1.5)/30;trailBoost=1;
          if(!q.hit&&w>.3){q.hit=true;const h=q.k%2?0:1;TG.jit=.8;hitFx(h,.65+q.k*.03,{dmg:rr(120,260)*(1+q.k*.12),at:q.c});if(q.k===ULT_N-1)slow(.2,.35)}
          r.gt-=dt;if(r.gt<=0){r.gt=.03;ghostNow({life:.16,col:q.k%2?ICE_C:FIRE_C,a:.35})}}
        r.lines.forEach((l,i)=>{l.a=.55+.25*Math.sin(e*12+i*1.7)})}
      else if(e<T_C1){ // ascensao
        const u=(e-T_B1)/(T_C1-T_B1);CAMF.dim=.7;CAMF.zt=lerp(1.35,1.6,u);CAMF.yawk=lerp(3.6,6.2832,ss(0,1,u));CAMF.roll=0;
        if(!r.up){r.up=1;const c=tgPoint();BL.forEach(b=>{b.pt=null;b.hist.length=0});act.x=TG.x;act.z=TG.z-.5;act.yaw=0;act.vis=true;ghostNow({life:.3,col:WH,a:.5});flash(.15,'#ffffff');SFX.rise(.9);
          shock([act.x,0,act.z],2.6,.08,FIRE_C,{sh:.2});ring([act.x,.03,act.z],.2,3.6,.06,.7,ICE_C,.8);
          helixPair(T_C1-T_B1+.2,(rr_,q)=>helixPts(act.x,act.z,Math.max(0,act.y-2),act.y+.6,.6,2.6,(run?run.el:0)*10*(q?-1:1)+q*3.1416))}
        act.x=TG.x;act.z=TG.z-.5;act.y=4.4*ei(u)*(2-ei(u))*.5+4.4*.5*u;act.yaw=0;t=lerp(11,46,ss(0,1,u))/30;trailBoost=.8;
        r.gt-=dt;if(r.gt<=0){r.gt=.03;spark([act.x+rr(-.3,.3),act.y+rr(0,.8),act.z+rr(-.3,.3)],[rr(-.3,.3),-rr(1,2.5),rr(-.3,.3)],.6,.035,Math.random()<.5?FIRE_C:ICE_C,1,0)}}
      else if(e<T_D1){ // suspenso no ar: energia concentrada nas laminas
        const u=(e-T_C1)/(T_D1-T_C1);CAMF.dim=.78;CAMF.zt=1.6;act.y=4.4+Math.sin(e*9)*.03;act.x=TG.x;act.z=TG.z-.5;act.yaw=0;t=46/30;trailBoost=.2;
        if(!r.hang){r.hang=1;r.lines.forEach(l=>{l.a=1;l.w=.05});SFX.rise(.6);flash(.1,'#ffffff')}
        r.gt-=dt;if(r.gt<=0){r.gt=.02;for(let i=0;i<2;i++){const b=BL[i];if(b.pt)spark(vadd(b.tip,[rr(-.5,.5),rr(-.3,.5),rr(-.5,.5)]),[0,0,0],.28,.03,AUC[i],1,0,0)}}
        if(u>.3&&BL[0].pt&&BL[1].pt){/* brilho crescente nas pontas */const k=ss(.3,1,u);spark(BL[0].tip,[0,0,0],.05,.12*k,ICE_C,.9*k,0,0);spark(BL[1].tip,[0,0,0],.05,.12*k,FIRE_C,.9*k,0,0)}}
      else if(e<T_IMP2){ // mergulho
        const u=(e-T_D1)/(T_IMP2-T_D1);act.x=TG.x;act.z=TG.z-.5;act.yaw=0;act.y=lerp(4.4,0,u*u);t=lerp(46,54,u)/30;trailBoost=1;CAMF.zt=lerp(1.6,1.45,u);
        for(let i=0;i<2;i++){const b=BL[i];if(b.pt){const p=b.tip;line(p,[p[0]+rr(-.1,.1),p[1]+rr(1.2,2.4),p[2]+rr(-.1,.1)],.035,.14,AUC[i],{grow:.03})}}
        if(!r.pl){r.pl=1;SFX.rise(.25);SFX.dash()}}
      if(e>=T_IMP2&&!r.imp){r.imp=true;r.ie=e;act.y=0;act.x=TG.x;act.z=TG.z-.5;act.yaw=0;const c=[TG.x,.5,TG.z];
        flash(.38,'#fff4e6');shake(1.4);slow(.1,.55);CAMF.zt=1.6;CAMF.dim=0;CAMF.yawk=0;SFX.boom(1.5);SFX.shatter();SFX.ping();
        r.lines.forEach(l=>{l.life=1.4;l.age=0;l.hold=.2;l.w=.12;l.a=1;l.flare=1});
        // X: uma diagonal de fogo e outra de gelo
        line([c[0]-4.6,c[1]-3,c[2]],[c[0]+4.6,c[1]+3,c[2]],.17,1.4,FIRE_C,{grow:.07,hold:.2,flare:.5,a:.85});line([c[0]+4.6,c[1]-3,c[2]],[c[0]-4.6,c[1]+3,c[2]],.17,1.4,ICE_C,{grow:.07,hold:.2,flare:.5,a:.85});
        // dois pilares: gelo a esquerda, fogo a direita
        for(const [sx,col] of [[-.45,ICE_C],[.45,FIRE_C]]){line([c[0]+sx,-.2,c[2]],[c[0]+sx,8,c[2]],.3,1.4,col,{grow:.05,hold:.15,flare:.4,a:.65});line([c[0]+sx,-.2,c[2]],[c[0]+sx,8,c[2]],.08,1.4,WH,{grow:.05,hold:.25,a:.8})}
        ring([c[0],.03,c[2]],.1,8,.12,1.4,FIRE_C,.85);ring([c[0],.03,c[2]],.1,6,.1,1.2,ICE_C,.85);ring([c[0],.03,c[2]],.1,3.6,.08,1,WH,.8);ring([c[0],.03,c[2]],.1,10.5,.08,1.8,MG,.55);
        cracks([c[0],0,c[2]],14,5,FIRE_C,1.8);cracks([c[0],0,c[2]],12,4,ICE_C,1.6);
        burst(c,50,10,.05,WH,1,-5);burst(c,46,9,.05,FIRE_C,1.2,-3,2.5,[1,.3,0],1.2);burst(c,46,9,.05,ICE_C,1.3,-4,1.5,[-1,.3,0],1.2);burst(c,26,5,.1,[.5,.35,.22],1.6,-7,2.4);
        for(let i=0;i<26;i++){const x=c[0]+rr(-4,4),z=c[2]+rr(-3.5,3.5);line([x,rr(5,8),z],[x,rr(0,.4),z],.02,rr(.35,.7),x>c[0]?FIRE_C:ICE_C,{grow:.06,hold:.1,a:.75})}
        TG.vis=0;TG.fade=-1;dmg(99999,[c[0],c[1]+.5,c[2]],'huge');dmg(24999,[c[0]-.5,c[1]+.1,c[2]],'crit');dmg(18888,[c[0]+.5,c[1]+.9,c[2]],'crit')}
      if(r.imp){const q=e-r.ie;
        if(q<.35){t=lerp(54,66,ss(0,.35,q))/30}else t=lerp(66,80,ss(.35,1.2,q))/30;
        if(q>.45&&q<1.3){CAMF.zt=lerp(1.6,1,ss(.45,1.4,q))}CAMF.yawk=lerp(CAMF.yawk,0,clp(dt*1.5,0,1));
        if(q>1.05&&TG.fade<=0&&!r.resp){r.resp=1;TG.fade=1}
        r.gt-=dt;if(r.gt<=0&&q<1.4){r.gt=.05;const sx=Math.random()<.5;spark([TG.x+rr(-2.5,2.5),rr(0,.2),TG.z+rr(-2.5,2.5)],[rr(-.3,.3),rr(1.5,4),rr(-.3,.3)],1,.04,sx?FIRE_C:ICE_C,1,-1.2)}
        trailBoost=q<.8?1:0;if(q>1.9)r.done=true}}
    ,end(r){act.vis=true;act.x=BASE.x;act.y=0;act.z=BASE.z;act.yaw=0;CAMF.dim=0;CAMF.zt=1;CAMF.roll=0;CAMF.yawk=0;trailBoost=0;TG.vis=1;TG.fade=0;
      r.lines&&r.lines.forEach(l=>{l.life=Math.min(l.life,l.age+.3)})}};
  // ---------------------------------------------------------------------------------------------------
  function setClipRaw(i,f){if(clips[cur]&&!restMode){prev={q:new Float32Array(sQ),pt:new Float32Array(sP),ci:cur,t0:t};blend=0}cur=i;t=f/30}
  function startSkill(id){const def=DEF[id];if(!def)return;AC();
    if(run){const o=run;run=null;o.def.end&&o.def.end(o)}
    run={id,def,i:0,lf:-1,evd:new Set(),el:0,queued:false,bx:BASE.x,bz:BASE.z};if(!(id==='basic'&&act.z-BASE.z>.3&&BQ.i>0)){act.x=BASE.x;act.z=BASE.z}act.y=0;act.yaw=0;act.vis=true;
    {const nx=BASE.x,nz=BASE.z+1.7;if(Math.hypot(TG.x-nx,TG.z-nz)>.3){TG.vis=0;TG.fade=1}else TG.vis=1;TG.x=nx;TG.z=nz;TG.y=0;TG.vy=0;TG.air=false;TG.tilt=0;TG.tv=0}
    playing=true;loop=false;blendDur=.12;trailBoost=0;
    CAMF.zt=id==='ult'?1:1.22;
    def.start&&def.start.call(def,run);
    if(!def.custom){run.segs=def.seq(run);beginSeg(0)}}
  function beginSeg(i){const s=run.segs[i];run.i=i;run.lf=s.f0-1;run.evd=new Set();if(i===0&&run.id!=='basic'){setClipRaw(s.clip,s.f0)}else setClipRaw(s.clip,s.f0);blendDur=run.id==='basic'?.07:.1}
  function endSkill(){const r=run;if(!r)return;run=null;r.def.end&&r.def.end(r);
    if(r.id==='basic'&&r.queued){BQ.last=performance.now()/1000;startSkill('basic');return}
    if(act.z-BASE.z>.3&&r.id!=='ult'&&r.id!=='s1'){ghostNow({life:.3,col:VI,a:.6});burst([act.x,.5,act.z],12,3,.05,CY,.35,0)}
    act.x=BASE.x;act.y=0;act.z=BASE.z;act.yaw=0;act.vis=true;trailBoost=0;blendDur=.35;CAMF.zt=1;
    setClipRaw(CI.Idle,0);loop=true;playing=true;trailLife=.3;trailCol=CY}
  function useSkill(id){AC();if(id==='basic'&&run&&run.id==='basic'){run.queued=true;return}
    if(id==='basic'){const n=performance.now()/1000;if(n-BQ.last>1.6)BQ.i=0;startSkill('basic');return}
    startSkill(id)}
  // avanco do timeline da skill (chamado todo quadro, com dt ja escalado pelo tempo)
  function skillStep(dt){if(!run)return;const r=run,def=r.def;
    if(def.custom){def.tick.call(def,r,dt);if(r.done)endSkill();return}
    const s=r.segs[r.i];t+=dt*s.spd;let f=t*30;const f1=s.f1;
    if(def.ev&&def.ev.length||r.ev){const evs=r.ev||def.ev;for(const ev of evs){if(ev.s!==r.i)continue;if(ev.f>r.lf&&ev.f<=f&&!r.evd.has(ev)){r.evd.add(ev);ev.fn()}}}
    if(def.appr){const A=def.appr,Z=(TG.on?TG.z:r.bz+1.7)-A.d;let z;if(f<A.r0){z=r.bz+(Z-r.bz)*ss(A.f0,A.f1,f);if(f>A.f0+.5&&f<A.f1&&(r.ag=(r.ag||0)-dt)<=0){r.ag=.03;ghostNow({life:.3,col:CY,a:.55})}}else{z=r.bz+(Z-r.bz)*(1-ss(A.r0,A.r1,f));if(!r.retd){r.retd=1;ghostNow({life:.32,col:VI,a:.6});burst([act.x,.5,act.z],14,3,.05,CY,.35,0);SFX.dash()}}act.z=z}
    r.lf=f;def.tick&&def.tick.call(def,r,dt,f);
    if(f>=f1){if(r.i+1<r.segs.length)beginSeg(r.i+1);else endSkill()}}
  // alvo: fisica simples
  function targetStep(dt){TG.flash=Math.max(0,TG.flash-dt*7);TG.jit=Math.max(0,TG.jit-dt*3.2);
    if(TG.air||TG.y>0||TG.vy!==0){if(TG.air||TG.y>0)TG.vy-=9*dt;TG.y+=TG.vy*dt;if(TG.y<=0){TG.y=0;if(Math.abs(TG.vy)>2){TG.vy=-TG.vy*.28}else TG.vy=0}}
    TG.tv+=-TG.tilt*60*dt-TG.tv*6*dt;TG.tilt+=TG.tv*dt*.5;TG.tilt=clp(TG.tilt,-.7,.7);TG.spin+=0;
    if(TG.fade>0){TG.vis=Math.min(1,TG.vis+dt*1.2);if(TG.vis>=1)TG.fade=0}}
  // camera: segue o ator
  const foc={x:0,y:.5,z:0};
  function camStep(dt,dts){dts=dts==null?dt:dts;const aut=cam.auto!==false,ult=aut&&run&&run.id==='ult',mid=aut&&run&&TG.on&&!ult;const tx=ult?TG.x:(mid?(act.x+TG.x)*.5:act.x),tz=ult?TG.z*(run.el<1.4?.5:.7):(mid?(act.z+TG.z)*.5:act.z);
    const py=W[PEL*16+13];let ty=.52+clp((py-.45)*.55,-.1,1)+clp(act.y*.5,0,3);if(ult){ty=.62+clp(act.y*.78,0,3.6)}
    const k=clp(dt*7,0,1);foc.x+=(tx-foc.x)*k;foc.z+=(tz-foc.z)*k;foc.y+=(ty-foc.y)*k;
    CAMF.zoom+=(CAMF.zt-CAMF.zoom)*clp(dt*6,0,1);CAMF.shake*=Math.pow(.003,dts);CAMF.flash*=Math.pow(.0008,dt);CAMF.roll*=Math.pow(.05,dts);
    dimEl.style.opacity=String(clp(CAMF.dim,0,1));const fl=$e('flash');if(fl){fl.style.opacity=String(clp(CAMF.flash,0,1));fl.style.background=CAMF.flashC}}
  function partStep(dt){for(let i=PT.length-1;i>=0;i--){const p=PT[i];p.life-=dt;if(p.life<=0){PT.splice(i,1);continue}p.v[1]+=p.g*dt;const d=Math.exp(-p.drag*dt);p.v[0]*=d;p.v[1]*=d;p.v[2]*=d;p.p[0]+=p.v[0]*dt;p.p[1]+=p.v[1]*dt;p.p[2]+=p.v[2]*dt;if(p.p[1]<0){p.p[1]=0;p.v[1]*=-.3}}
    for(let i=GH.length-1;i>=0;i--){const g=GH[i];g.age+=dt;if(g.age>=g.life){gUse[g.tex]=null;GH.splice(i,1)}}}
  function trailGate(){return showW}
  // ---------------------------------------------------------------------------------------------------
  function draw(){
    const dpr=Math.min(window.devicePixelRatio||1,2),w=Math.floor(cv.clientWidth*dpr),h=Math.floor(cv.clientHeight*dpr);if(cv.width!==w||cv.height!==h){cv.width=w;cv.height=h}
    const dimK=clp(CAMF.dim,0,1);gl.viewport(0,0,cv.width,cv.height);{const B=SH.map().bg,k=1-.8*dimK;gl.clearColor(B[0]*k,B[1]*k,B[2]*k+.01*dimK,1)}gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.enable(gl.DEPTH_TEST);
    const P=M4.persp(SH.fov(),cv.width/cv.height,.1,80);P[9]=-SH.insetY();P[8]=SH.insetX();
    const au=cam.auto!==false;if(cam.follow!==false){cam.fx=foc.x;cam.fy=foc.y;cam.fz=foc.z}
    const DD=SH.dist()*1.656*(au?CAMF.zoom:1),sh=CAMF.shake,yk=au?CAMF.yawk:0;
    const tx=cam.tx+(cam.fx||0)+(sh?rnd(-sh,sh)*.05:0),ty=cam.ty+((cam.fy==null?.52:cam.fy)-.52)+(sh?rnd(-sh,sh)*.05:0),tz=(cam.fz||0)+(cam.tz||0)+(sh?rnd(-sh,sh)*.05:0);
    cam.pitch=Math.max(cam.pitch,SH.pmin(ty,DD));const cp=Math.cos(cam.pitch);
    const eye=[tx+Math.sin(cam.yaw+yk)*cp*DD,ty+Math.sin(cam.pitch)*DD,tz+Math.cos(cam.yaw+yk)*cp*DD];
    const V=M4.look(eye,[tx,ty,tz],[Math.sin(au?CAMF.roll:0),Math.cos(au?CAMF.roll:0),0]),VP=M4.mul(new Float32Array(16),P,V);VPm=VP;camE=eye;camR=[V[0],V[4],V[8]];camU=[V[1],V[5],V[9]];
    gl.disable(gl.CULL_FACE);gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(PG);gl.uniformMatrix4fv(U(PG,'uVP'),false,VP);gl.uniform2f(U(PG,'uSh'),act.x+W[PEL*16+12],act.z+W[PEL*16+14]);gl.uniform2f(U(PG,'uOff'),cam.fx||0,cam.fz||0);SH.mapApply(U,PG,1-.35*dimK);gl.bindVertexArray(gv);gl.drawArrays(gl.TRIANGLES,0,6);gl.disable(gl.BLEND);
    buildAct(actM,act.x,act.y,act.z,act.yaw);
    if(act.vis){
    for(let i=0;i<8;i++){const k=capIdx[i];capBuf[i*3]=W[k*16+12];capBuf[i*3+1]=W[k*16+13];capBuf[i*3+2]=W[k*16+14]}
    gl.useProgram(PC);gl.uniform3fv(U(PC,'uCap'),capBuf);gl.uniform3fv(U(PC,'uCapB'),capBind);gl.uniformMatrix4fv(U(PC,'uVP'),false,VP);gl.uniformMatrix4fv(U(PC,'uAct'),false,actM);gl.uniform3fv(U(PC,'uCtr'),man.char.ctr);gl.uniform3fv(U(PC,'uExt'),man.char.ext);gl.uniform3fv(U(PC,'uCam'),eye);gl.uniform1f(U(PC,'uMetal'),0);gl.uniform1f(U(PC,'uTm'),0);gl.uniform1f(U(PC,'uRune'),0);gl.uniform1f(U(PC,'uRim'),0);
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,boneTex);gl.uniform1i(U(PC,'uBones'),1);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,tc);gl.uniform1i(U(PC,'uTex'),0);
    gl.bindVertexArray(mc.vao);gl.drawElements(gl.TRIANGLES,mc.n,gl.UNSIGNED_INT,0);
    if(showW){gl.useProgram(PW);gl.uniformMatrix4fv(U(PW,'uVP'),false,VP);gl.uniform3fv(U(PW,'uCam'),eye);gl.uniform1f(U(PW,'uMetal'),.35);gl.uniform1f(U(PW,'uRim'),0);gl.uniform1f(U(PW,'uTm'),0);gl.uniform1f(U(PW,'uRune'),0);
      gl.bindTexture(gl.TEXTURE_2D,tw);gl.uniform1i(U(PW,'uTex'),0);
      mw.forEach((m,i)=>{M4.mul(wm,W.subarray(m.w.bone*16,m.w.bone*16+16),attM[i]);M4.mul(mA,actM,wm);gl.uniform3fv(U(PW,'uCtr'),m.w.ctr);gl.uniform3fv(U(PW,'uExt'),m.w.ext);gl.uniformMatrix4fv(U(PW,'uM'),false,mA);gl.bindVertexArray(m.vao);gl.drawElements(gl.TRIANGLES,m.n,gl.UNSIGNED_INT,0)})}}
    // alvo de treino
    if(TG.on&&TG.vis>0){gl.useProgram(PD);gl.uniformMatrix4fv(U(PD,'uVP'),false,VP);gl.uniform3fv(U(PD,'uCam'),eye);gl.uniform3f(U(PD,'uCol'),.55,.4,.26);gl.uniform1f(U(PD,'uF'),clp(TG.flash,0,1));gl.uniform1f(U(PD,'uAl'),1);
      const jx=TG.jit?rnd(-1,1)*TG.jit*.035:0;const c=Math.cos(TG.tilt),s=Math.sin(TG.tilt),sp=TG.spin?performance.now()/1000*TG.spin:0,cs=Math.cos(sp),sn=Math.sin(sp);
      // tilt em torno de X (base no chao) + giro em Y
      const M=new Float32Array(16);M[0]=cs;M[2]=-sn;M[4]=sn*s;M[5]=c;M[6]=cs*s;M[8]=sn*c;M[9]=-s;M[10]=cs*c;M[12]=TG.x+jx;M[13]=TG.y;M[14]=TG.z;M[15]=1;
      const sc=Math.max(.001,TG.vis);for(const i of [0,1,2,4,5,6,8,9,10])M[i]*=sc;
      gl.uniformMatrix4fv(U(PD,'uM'),false,M);gl.bindVertexArray(dumVao);gl.drawElements(gl.TRIANGLES,dumN,gl.UNSIGNED_SHORT,0)}
    // efeitos aditivos
    gl.enable(gl.BLEND);gl.blendFunc(gl.ONE,gl.ONE);gl.depthMask(false);
    if(GH.length){gl.useProgram(PGH);gl.uniformMatrix4fv(U(PGH,'uVP'),false,VP);gl.uniform3fv(U(PGH,'uCtr'),man.char.ctr);gl.uniform3fv(U(PGH,'uExt'),man.char.ext);gl.uniform3fv(U(PGH,'uCam'),eye);gl.uniform1i(U(PGH,'uBones'),1);gl.bindVertexArray(mc.vao);
      for(const g of GH){const u=g.age/g.life;const A=g.A;if(g.to){const e=g.fat?Math.min(1,u*1.25):u;buildAct(mA,lerp(A.x,g.to.x,e),lerp(A.y,g.to.y,e),lerp(A.z,g.to.z,e),A.yaw)}else buildAct(mA,A.x,A.y,A.z,A.yaw);
        gl.uniformMatrix4fv(U(PGH,'uAct'),false,mA);gl.uniform3fv(U(PGH,'uCol'),g.col);gl.uniform1f(U(PGH,'uA'),g.a*Math.pow(1-u,1.3));gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,gTex[g.tex]);gl.drawElements(gl.TRIANGLES,mc.n,gl.UNSIGNED_INT,0)}
      gl.activeTexture(gl.TEXTURE0)}
    const nq=buildRibbons(lastDt);if(nq>0){gl.useProgram(PRB);gl.uniformMatrix4fv(U(PRB,'uVP'),false,VP);gl.bindVertexArray(rbVao);gl.bindBuffer(gl.ARRAY_BUFFER,rbBuf);gl.bufferSubData(gl.ARRAY_BUFFER,0,RBUF,0,rn);gl.drawArrays(gl.TRIANGLES,0,nq)}
    if(PT.length){let n=0;for(const p of PT){const u=p.life/p.max;PBUF[n++]=p.p[0];PBUF[n++]=p.p[1];PBUF[n++]=p.p[2];PBUF[n++]=p.size*(.5+.5*u);PBUF[n++]=p.col[0];PBUF[n++]=p.col[1];PBUF[n++]=p.col[2];PBUF[n++]=p.a*u}
      gl.useProgram(PP);gl.uniformMatrix4fv(U(PP,'uVP'),false,VP);gl.uniform1f(U(PP,'uPx'),cv.height*1.95);gl.bindVertexArray(ppVao);gl.bindBuffer(gl.ARRAY_BUFFER,ppBuf);gl.bufferSubData(gl.ARRAY_BUFFER,0,PBUF,0,n);gl.drawArrays(gl.POINTS,0,n/8)}
    auDraw(VP);
    gl.depthMask(true);gl.disable(gl.BLEND);gl.bindVertexArray(null)}
  let last=performance.now(),lastDt=0;
  let frozen=false;
  function frame(now){const dt0=Math.min((now-last)/1000,.1);last=now;if(frozen)return;tick(dt0)}
  function tick(dt0,nodraw){
    // escala de tempo (camera lenta com retorno suave)
    if(ts.hold>0){ts.hold-=dt0}else ts.target=1;ts.v+=(ts.target-ts.v)*clp(dt0*(ts.target<ts.v?30:7),0,1);const dtS=dt0*ts.v,dt=dtS*speed;lastDt=dt;        // dt: tempo de simulacao (camera lenta E controle de velocidade)
    const c=clips[cur],dur=cdur(c);
    if(run){skillStep(dt)}
    else if(playing){t+=dtS*speed;if(t>=dur){if(loop)t%=dur;else t=dur}}
    if(blend<1)blend=Math.min(1,blend+dt/blendDur);
    if(spin)cam.yaw+=dt0*.6;
    updatePose();bladeUpdate(dt);targetStep(dt);partStep(dt);auraUpdate(dt);camStep(dt0,dt);if(!nodraw)draw()}
  const CATS=man.cats.filter(c=>clips.some(x=>x.cat===c));
  // ---------------------------------------------------------------------------------------------------
  // AURAS MAGICAS NAS LAMINAS  (texturas Hovl Studio: fumaca em flipbook 8x8, brilho, flare, ponto e cristais)
  //   lamina direita (indice 1) = FOGO   |   lamina esquerda (indice 0) = GELO
  const FIRE_C=[1,.42,.08],ICE_C=[.45,.86,1],AUC=[ICE_C,FIRE_C];
  let showAura=true;const auraOn=()=>showAura&&showW&&act.vis;
  const AU=[];let auT=0,auN=[0,0,0,0];
  const VS_AU=`#version 300 es
precision highp float;layout(location=0) in vec3 aP;layout(location=1) in vec2 aT;layout(location=2) in vec4 aC;layout(location=3) in vec2 aK;uniform mat4 uVP;out vec2 vT;out vec4 vC;out vec2 vK;
void main(){vT=aT;vC=aC;vK=aK;gl_Position=uVP*vec4(aP,1.);}`;
  const FS_AU=`#version 300 es
precision highp float;in vec2 vT;in vec4 vC;in vec2 vK;uniform sampler2D uF,uG,uS;out vec4 o;
vec2 cell(float f,vec2 t){float fi=floor(f);return (vec2(mod(fi,8.),floor(fi/8.))+clamp(t,.002,.998))/8.;}
void main(){float k=vK.x;float L;vec3 col=vC.rgb;
  if(k<.5){float f=min(vK.y,62.);float m=fract(f);L=mix(texture(uF,cell(f,vT)).r,texture(uF,cell(f+1.,vT)).r,m)*1.6;}
  else if(k<1.5){L=texture(uG,vT).r;L=L*L*1.1;}
  else if(k<2.5){L=texture(uG,vT).g;L*=1.3;}
  else if(k<3.5){L=texture(uG,vT).b;L*=1.2;}
  else{vec4 s=texture(uS,(vec2(mod(vK.y,4.),floor(vK.y/4.))+clamp(vT,.01,.99))/4.);L=dot(s.rgb,vec3(.35,.5,.15))*s.a*1.6;}
  float e=smoothstep(0.,.12,min(min(vT.x,vT.y),min(1.-vT.x,1.-vT.y)));
  o=vec4(col*L*vC.a*e,1.);}`;
  const PAU=prg(VS_AU,FS_AU);
  const AUMAX=1100,AUBUF=new Float32Array(AUMAX*6*11),auVao=gl.createVertexArray(),auBuf=gl.createBuffer();
  gl.bindVertexArray(auVao);gl.bindBuffer(gl.ARRAY_BUFFER,auBuf);gl.bufferData(gl.ARRAY_BUFFER,AUBUF.byteLength,gl.DYNAMIC_DRAW);
  [[0,3,0],[1,2,12],[2,4,20],[3,2,36]].forEach(([l,n,o])=>{gl.enableVertexAttribArray(l);gl.vertexAttribPointer(l,n,gl.FLOAT,false,44,o)});gl.bindVertexArray(null);
  let auTex=[null,null,null];
  async function auTexLoad(k,i){try{const bm=await createImageBitmap(new Blob([SH.bin('aura.'+k)],{type:'image/png'}));
      const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,false);gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL,gl.NONE);
      gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,bm);if(bm.close)bm.close();
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);auTex[i]=t}catch(e){console.warn('aura tex',k,e)}}
  await Promise.all([auTexLoad('flip',0),auTexLoad('glow',1),auTexLoad('shards',2)]);
  // intensidade: sobe com a velocidade da lamina e durante as skills
  const auInt=(i)=>{const b=BL[i];let k=1+clp(b.sp/30,0,1)*.5;if(run)k+=run.id==='ult'?.6:.2;return k};
  function auSpawn(i,kind,n){const b=BL[i];if(!b.pt)return;const fire=i===1;
    for(let q=0;q<n&&AU.length<AUMAX-2;q++){const s=Math.random(),a=lerp(.0,1,s);const an=vadd(vmul(b.base,1-s),vmul(b.tip,s));
      const bv=vmul(b.v,.07);const ang=Math.random()*6.283;const side=[Math.cos(ang),0,Math.sin(ang)];
      const P={k:kind,i,s,an,q:vadd(an,vmul(side,.012)),v:[0,0,0],age:0,life:.5,s0:.2,s1:.4,rot:rnd(0,6.28),rv:rnd(-1.5,1.5),f0:0,fps:60,a:1,w:1,fire};
      if(kind===0){ // chama / nevoa
        P.life=fire?rnd(.3,.5):rnd(.5,.8);P.s0=fire?rnd(.05,.08):rnd(.055,.085);P.s1=fire?rnd(.11,.17):rnd(.14,.21);P.f0=Math.floor(rnd(0,3))*8+Math.floor(rnd(0,2));P.fps=fire?58:40;P.w=.8;
        P.v=fire?[bv[0]+rnd(-.2,.2),rnd(.7,1.5),bv[2]+rnd(-.2,.2)]:[bv[0]+rnd(-.25,.25),rnd(-.5,.15),bv[2]+rnd(-.25,.25)];P.a=fire?.5:.4}
      else if(kind===3){ // brasas (fogo)
        P.life=rnd(.5,1.1);P.s0=rnd(.014,.026);P.s1=.004;P.v=[bv[0]+rnd(-.5,.5),rnd(.5,1.5),bv[2]+rnd(-.5,.5)];P.a=1.4;P.w=.2}
      else if(kind===2){ // faiscas de gelo (flare)
        P.life=rnd(.35,.7);P.s0=rnd(.03,.06);P.s1=.006;P.v=[bv[0]+rnd(-.3,.3),rnd(-.2,.5),bv[2]+rnd(-.3,.3)];P.a=1.2;P.rv=rnd(-3,3);P.w=.3}
      else if(kind===4){ // cristais de gelo
        P.life=rnd(.5,.9);P.s0=rnd(.02,.034);P.s1=P.s0*.4;P.f0=Math.floor(rnd(0,16));P.v=[bv[0]+rnd(-.45,.45),rnd(-.1,.6),bv[2]+rnd(-.45,.45)];P.a=1.1;P.rv=rnd(-4,4);P.w=.3}
      AU.push(P)}}
  function auraUpdate(dt){for(let i=AU.length-1;i>=0;i--){const p=AU[i];p.age+=dt;if(p.age>=p.life){AU.splice(i,1);continue}
      const u=p.age/p.life;if(p.k===4){p.v[1]-=1.2*dt}else if(p.k===3){p.v[1]+=.5*dt}
      const d=Math.exp(-1.3*dt);p.v[0]*=d;p.v[2]*=d;p.q[0]+=p.v[0]*dt;p.q[1]+=p.v[1]*dt;p.q[2]+=p.v[2]*dt;p.rot+=p.rv*dt}
    if(!(dt>0)||!auraOn()){return}
    for(let i=0;i<2;i++){const b=BL[i];if(!b.pt)continue;const I=auInt(i);const fire=i===1;
      auN[0]+=dt*(fire?34:28)*I;auN[1]+=dt*(fire?18:9)*I;auN[2]+=dt*(fire?0:14)*I;
      const nW=Math.floor(auN[0]);auN[0]-=nW;auSpawn(i,0,Math.min(nW,6));
      const nE=Math.floor(auN[1]);auN[1]-=nE;auSpawn(i,fire?3:2,Math.min(nE,5));
      if(!fire){const nC=Math.floor(auN[2]/6);if(nC>0){auN[2]-=nC*6;auSpawn(i,4,Math.min(nC,3))}}}}
  function auPush(n,p,sz,rot,col,a,kind,fr){const cr=Math.cos(rot)*sz,sr=Math.sin(rot)*sz;
    // cantos: (0,0)=topo-esq (em cima = +camU)
    const R=[camR[0]*cr+camU[0]*sr,camR[1]*cr+camU[1]*sr,camR[2]*cr+camU[2]*sr],Uu=[camU[0]*cr-camR[0]*sr,camU[1]*cr-camR[1]*sr,camU[2]*cr-camR[2]*sr];
    const cn=[[-1,1,0,0],[1,1,1,0],[-1,-1,0,1],[1,1,1,0],[1,-1,1,1],[-1,-1,0,1]];
    for(const c of cn){AUBUF[n++]=p[0]+R[0]*c[0]+Uu[0]*c[1];AUBUF[n++]=p[1]+R[1]*c[0]+Uu[1]*c[1];AUBUF[n++]=p[2]+R[2]*c[0]+Uu[2]*c[1];AUBUF[n++]=c[2];AUBUF[n++]=c[3];AUBUF[n++]=col[0];AUBUF[n++]=col[1];AUBUF[n++]=col[2];AUBUF[n++]=a;AUBUF[n++]=kind;AUBUF[n++]=fr}
    return n}
  const FIRE_G=[[1,.95,.6],[1,.55,.12],[.75,.12,.02]],ICE_G=[[.95,1,1],[.5,.88,1],[.2,.45,.95]];
  function auGrad(g,u){return u<.5?vmix(g[0],g[1],u*2):vmix(g[1],g[2],(u-.5)*2)}
  function auBuild(){let n=0;const now=performance.now()/1000;
    // brilho fixo das laminas (halo + luz na ponta), pulsando
    if(auraOn())for(let i=0;i<2;i++){const b=BL[i];if(!b.pt)continue;const fire=i===1,I=auInt(i),G=fire?FIRE_G:ICE_G,pu=.82+.18*Math.sin(now*(fire?19:9)+i*2);
      const mid=vmix(b.base,b.tip,.5);n=auPush(n,mid,.13*pu*(.85+.15*I),0,G[1],.14*I,1,0);n=auPush(n,b.tip,.08*pu,0,G[0],.2*Math.min(I,1.6),1,0);
      n=auPush(n,vmix(b.base,b.tip,.82),.06*pu,now*(fire?1:.6),G[0],.25*Math.min(I,1.6),2,0)}
    for(const p of AU){if(n>=AUBUF.length-66)break;const b=BL[p.i];const u=p.age/p.life,G=p.fire?FIRE_G:ICE_G;
      const an=vadd(vmul(b.base,1-p.s),vmul(b.tip,p.s));const fw=Math.max(0,1-u*p.w*2.2);const pos=vadd(p.q,vmul(vsub(an,p.an),fw));
      const fin=Math.min(1,p.age/.06),fo=Math.pow(1-u,p.k===0?1.2:1.6);let col,a=p.a*fin*fo,sz=lerp(p.s0,p.s1,Math.sqrt(u)),fr=0;
      if(p.k===0){col=auGrad(G,u);fr=p.f0+p.age*p.fps;a*=1.0}
      else if(p.k===3){col=auGrad(G,u*.6);sz=p.s0*(1-u)+.012}
      else if(p.k===2){col=G[0];a*=.6+.4*Math.sin(p.age*40)}
      else{col=vmix(G[0],G[1],.4);fr=p.f0}
      n=auPush(n,pos,sz,p.rot,col,a,p.k===3?3:(p.k===4?4:p.k),fr)}
    return n/11}
  function auDraw(VP){const nv=auBuild();if(nv<=0||!auTex[0])return;
    gl.useProgram(PAU);gl.uniformMatrix4fv(U(PAU,'uVP'),false,VP);
    for(let i=0;i<3;i++){gl.activeTexture(gl.TEXTURE2+i);gl.bindTexture(gl.TEXTURE_2D,auTex[i])}gl.uniform1i(U(PAU,'uF'),2);gl.uniform1i(U(PAU,'uG'),3);gl.uniform1i(U(PAU,'uS'),4);
    gl.bindVertexArray(auVao);gl.bindBuffer(gl.ARRAY_BUFFER,auBuf);gl.bufferSubData(gl.ARRAY_BUFFER,0,AUBUF,0,nv*11);gl.drawArrays(gl.TRIANGLES,0,nv);gl.activeTexture(gl.TEXTURE0)}
  const KIT=[
    {id:'basic',nome:'Corte Relâmpago',tag:'Básico',cd:0,custo:0,icon:'i-m-basic',desc:'Cadeia de 4 cortes cruzados em velocidade absurda. Cada toque é o próximo golpe; o quarto fecha em X, com explosão de energia e câmera lenta no impacto.'},
    {id:'s1',nome:'Investida Fantasma',tag:'Skill 1',cd:5,custo:25,icon:'i-m-dash',desc:'Some num piscar e atravessa o alvo com as duas lâminas abertas, deixando imagens residuais pelo caminho. O corte cruza o inimigo e ele termina onde o dash acabou: o personagem não volta ao ponto de partida (use Recentralizar em Ajustes).'},
    {id:'s2',nome:'Tempestade de Aço',tag:'Skill 2',cd:8,custo:40,icon:'i-m-storm',desc:'Rajada de mais de 30 cortes em menos de um segundo, vindos de todos os lados, com cópias residuais tremendo ao redor. Fecha com um X devastador.'},
    {id:'s3',nome:'Ceifa Ascendente',tag:'Skill 3',cd:10,custo:45,icon:'i-m-rise',desc:'Corte em lua crescente que lança o alvo ao céu. Ele o persegue no ar com três cortes, o lança de novo e o crava no chão com onda de choque e rachaduras.'},
    {id:'s4',nome:'Espiral Celeste',tag:'Skill 4',cd:12,custo:55,icon:'i-m-spiral',desc:'Salta e gira como um ciclone de lâminas, puxando o alvo para o redemoinho. Termina num mergulho que racha o chão, com anéis de choque e raios de luz caindo do alto.'},
    {id:'ult',nome:'Mil Lâminas: Dança do Vazio',tag:'ULTIMATE',cd:60,custo:100,ult:true,icon:'i-m-ult',desc:'As auras de fogo e gelo convergem em espiral. O duelista some e reaparece ao redor do alvo em 12 cortes cada vez mais rápidos, a câmera girando. Sobe numa hélice de fogo e gelo, paira e mergulha como um meteoro: o impacto se divide em pilar de gelo e pilar de fogo, com um X duplo e ondas de choque.'}];
  const API={id:'mercenario',title:'Mercenário',
    clips:clips.map(c=>({name:c.name,cat:c.cat,dur:cdur(c),nf:c.nf})),cats:CATS,kit:KIT,
    frame,state:()=>({cur,t,dur:cdur(clips[cur]),playing,loop,speed,skill:run?run.id:null}),
    useSkill,tick,freeze:v=>{frozen=v},
    setClip(i){if(run){const o=run;run=null;o.def.end&&o.def.end(o);act.x=BASE.x;act.y=0;act.z=BASE.z;act.vis=true}setClip(i);playing=true},seek(f){if(run){const o=run;run=null;o.def.end&&o.def.end(o);act.x=BASE.x;act.y=0;act.z=BASE.z;act.vis=true}t=f*cdur(clips[cur]);playing=false},
    setPlaying(b){if(b&&!loop&&t>=cdur(clips[cur])-1e-3)t=0;playing=b},setLoop(b){loop=b},setSpeed(x){speed=x},
    toggles:[
      {id:'w',label:'Armas',sub:'Mostrar as duas lâminas',icon:'i-swords',get:()=>showW,set:v=>{showW=v}},
      {id:'aura',label:'Auras mágicas',sub:'Fogo na lâmina direita e gelo na esquerda',icon:'i-swords',get:()=>showAura,set:v=>{showAura=v}},
      {id:'tg',label:'Alvo de treino',sub:'Boneco para receber os golpes das skills',icon:'i-target',get:()=>TG.on,set:v=>{TG.on=v}},
      {id:'spin',label:'Girar câmera',sub:'Órbita automática em volta do personagem',icon:'i-rotate',get:()=>spin,set:v=>{spin=v}},
      {id:'tp',label:'Pose de repouso',sub:'Mostra o modelo parado, sem animação',icon:'i-person',get:()=>restMode,set:v=>{restMode=v}}],
    actions:[{label:'Recentralizar personagem',icon:'i-rotate',wide:true,run(){if(run){const o=run;run=null;o.def.end&&o.def.end(o)}BASE.x=BASE.z=0;act.x=act.z=0;act.y=0;foc.x=foc.z=0;TG.x=0;TG.z=1.7}}],
    pos:()=>({x:act.x,z:act.z,bx:BASE.x,bz:BASE.z,tx:TG.x,tz:TG.z}),
    dispose(){playing=false;prev=null;run=null;AU.length=0;dimEl.remove();dmgEls.forEach(e=>e.remove());const fl=$e('flash');if(fl)fl.style.opacity='0';const b=$e('ban');if(b)b.className=''}};
  return API}
