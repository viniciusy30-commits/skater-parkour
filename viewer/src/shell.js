// =====================================================================================
//  v99 — shell: seletor de personagem, carregamento/descarregamento total, interface nova
// =====================================================================================
(function(){
const $=id=>document.getElementById(id);
const _st=window.setTimeout.bind(window),_ct=window.clearTimeout.bind(window),_raf=window.requestAnimationFrame.bind(window);
const _ael=EventTarget.prototype.addEventListener,_rel=EventTarget.prototype.removeEventListener;
const sleep=ms=>new Promise(r=>_st(r,ms));
const nextPaint=()=>new Promise(r=>_raf(()=>_st(r,0)));

// ---------------------------------------------------------------- rastreamento de sessao
// Tudo que um personagem cria (timers, listeners, objetos WebGL, AudioContext, URLs, globais) e
// registrado na sessao ativa e destruido por completo ao trocar de personagem.
const SESS={cur:null};
class Session{constructor(id){this.id=id;this.dead=false;this.timers=new Set();this.listeners=[];this.gl=[];this.ctxs=[];this.urls=[];this.keys=new Set(Object.keys(window))}}
window.setTimeout=function(f,ms){const s=SESS.cur;if(!s||typeof f!=='function')return _st.apply(window,arguments);
  const a=Array.prototype.slice.call(arguments,2);const id=_st(function(){s.timers.delete(id);if(!s.dead)f.apply(this,a)},ms);s.timers.add(id);return id};
window.clearTimeout=function(id){const s=SESS.cur;if(s)s.timers.delete(id);return _ct(id)};
EventTarget.prototype.addEventListener=function(t,f,o){const s=SESS.cur;if(s&&!s.dead)s.listeners.push([this,t,f,o]);return _ael.call(this,t,f,o)};
{const AC=window.AudioContext||window.webkitAudioContext;
 if(AC){const W=function(){const c=new AC(...arguments);if(SESS.cur)SESS.cur.ctxs.push(c);return c};W.prototype=AC.prototype;window.AudioContext=W;window.webkitAudioContext=W}}

const cv=$('cv');
const gl=cv.getContext('webgl2',{antialias:true,alpha:false,depth:true,stencil:false,powerPreference:'high-performance'});
const GLC={},LIVE=new Set();
if(gl){for(const k of ['Buffer','Texture','VertexArray','Program','Shader','Framebuffer','Renderbuffer','Sampler','Query','TransformFeedback']){
  const mk='create'+k,dl='delete'+k;if(typeof gl[mk]!=='function')continue;GLC[k]=0;const om=gl[mk].bind(gl),od=gl[dl].bind(gl);
  gl[mk]=function(){const o=om.apply(null,arguments);if(o){GLC[k]++;LIVE.add(o);const s=SESS.cur;if(s)s.gl.push([k,o])}return o};
  gl[dl]=function(o){if(o&&LIVE.has(o)){LIVE.delete(o);GLC[k]--}return od(o)}}}
const rawDel=gl?{}:null;if(gl)for(const k in GLC)rawDel[k]=gl['delete'+k];

function disposeSession(S){
  if(!S)return;S.dead=true;
  for(const id of S.timers)_ct(id);S.timers.clear();
  for(const[t,ty,f,o]of S.listeners){try{_rel.call(t,ty,f,o)}catch(e){}}S.listeners.length=0;
  if(gl){try{gl.bindVertexArray(null);gl.bindBuffer(gl.ARRAY_BUFFER,null);gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,null);gl.useProgram(null);for(let u=15;u>=0;u--){gl.activeTexture(gl.TEXTURE0+u);gl.bindTexture(gl.TEXTURE_2D,null)}gl.activeTexture(gl.TEXTURE0)}catch(e){}
    for(const[k,o]of S.gl){if(LIVE.has(o))gl['delete'+k](o)}S.gl.length=0;try{gl.flush()}catch(e){}}
  for(const c of S.ctxs){try{c.close()}catch(e){}}S.ctxs.length=0;
  for(const u of S.urls){try{URL.revokeObjectURL(u)}catch(e){}}S.urls.length=0;
  for(const k of Object.keys(window)){if(!S.keys.has(k)){try{delete window[k]}catch(e){}}}
}

// ---------------------------------------------------------------- camera compartilhada
const CAM={yaw:.45,pitch:.12,dist:2.6,ty:.52,tx:0};
const INS={x:0,y:0,asp:.5,fit:2.5};
const LANDQ=matchMedia('(orientation:landscape) and (max-height:620px),(orientation:landscape) and (min-aspect-ratio:17/10)');
function computeInsets(){
  const W=innerWidth,H=innerHeight;
  if(document.body.classList.contains('game')){INS.x=0;INS.y=0;INS.asp=W/H;return}
  const t=$('top').getBoundingClientRect(),tr=$('transport').getBoundingClientRect(),sh=$('sheet').getBoundingClientRect();
  let top=Math.max(0,t.bottom-6),bottom=Math.min(H,tr.top+4);let right=0;
  if(LANDQ.matches){top=Math.max(0,t.bottom-20);bottom=Math.min(H,tr.top+16)}
  if(LANDQ.matches)right=Math.max(0,Math.min(W,W-sh.left));
  INS.y=2*(H/2-(top+bottom)/2)/H;INS.x=right/W;INS.asp=Math.max(.2,(W-right)/Math.max(120,bottom-top));
  // distancia que encaixa o personagem inteiro na area livre (fora das barras e da gaveta)
  const freeH=Math.max(170,bottom-top),freeW=Math.max(160,W-right);
  const Vneed=Math.min(6,Math.max(1.28*H/freeH,1.12*H/freeW));
  INS.fit=Vneed/(.5106*1.656);
}
const fitD=()=>1;                                  // zoom do usuario (1 = encaixe automatico)
const camDist=()=>document.body.classList.contains('game')?CAM.dist:CAM.dist*INS.fit;
function camReset(){computeInsets();Object.assign(CAM,{yaw:.45,pitch:.12,dist:1,ty:.52,tx:0})}
const clampZ=v=>document.body.classList.contains('game')?Math.max(.9,Math.min(7,v)):Math.max(.3,Math.min(3.2,v));
{const pts=new Map();let lastD=0;
 _ael.call(cv,'pointerdown',e=>{cv.setPointerCapture(e.pointerId);pts.set(e.pointerId,[e.clientX,e.clientY]);lastD=0});
 const up=e=>{pts.delete(e.pointerId);lastD=0};_ael.call(cv,'pointerup',up);_ael.call(cv,'pointercancel',up);
 _ael.call(cv,'pointermove',e=>{if(!pts.has(e.pointerId))return;const o=pts.get(e.pointerId);const dx=e.clientX-o[0],dy=e.clientY-o[1];pts.set(e.pointerId,[e.clientX,e.clientY]);
  if(pts.size===1){CAM.yaw-=dx*.008;CAM.pitch=Math.max(-.6,Math.min(1.35,CAM.pitch+dy*.006))}
  else if(pts.size===2){const a=[...pts.values()];const d=Math.hypot(a[0][0]-a[1][0],a[0][1]-a[1][1]);if(lastD)CAM.dist=clampZ(CAM.dist*lastD/d);lastD=d;CAM.ty=Math.max(0,Math.min(1.2,CAM.ty+dy*.0015))}});
 _ael.call(cv,'wheel',e=>{e.preventDefault();CAM.dist=clampZ(CAM.dist*Math.exp(e.deltaY*.001))},{passive:false})}

// ---------------------------------------------------------------- blocos comprimidos
async function unpackBlock(text,prog){
  const n=text.length,pad=text.endsWith('==')?2:text.endsWith('=')?1:0,u=new Uint8Array((n/4*3|0)-pad);let o=0;const CH=4*262144;
  for(let i=0;i<n;i+=CH){const bin=atob(text.slice(i,i+CH));for(let j=0;j<bin.length;j++)u[o++]=bin.charCodeAt(j);if(((i/CH)&3)===3){prog('Lendo dados…',.1+.25*i/n);await sleep(0)}}
  prog('Descomprimindo…',.4);
  if(typeof DecompressionStream!=='undefined'){try{return await new Response(new Blob([u]).stream().pipeThrough(new DecompressionStream('deflate'))).arrayBuffer()}catch(e){}}
  const r=inflateZ(u);return r.buffer.slice(r.byteOffset,r.byteOffset+r.length)}

// ---------------------------------------------------------------- MOTOR DO MERCENARIO
async function bootMerc(SH){
  const {gl,cv,man,buf,cam}=SH,prog=SH.prog;
  const U=(p,n)=>gl.getUniformLocation(p,n);
  function sh(t,s){const x=gl.createShader(t);gl.shaderSource(x,s);gl.compileShader(x);if(!gl.getShaderParameter(x,gl.COMPILE_STATUS))throw new Error(gl.getShaderInfoLog(x));return x}
  function prg(v,f){const p=gl.createProgram();gl.attachShader(p,sh(gl.VERTEX_SHADER,v));gl.attachShader(p,sh(gl.FRAGMENT_SHADER,f));gl.linkProgram(p);if(!gl.getProgramParameter(p,gl.LINK_STATUS))throw new Error(gl.getProgramInfoLog(p));return p}
  const PC=prg(VS_C,FS_L),PW=prg(VS_W,FS_L),PG=prg(VS_G,FS_G);
  async function tex(range){const b=new Blob([new Uint8Array(buf,range[0],range[1])],{type:'image/jpeg'});const bm=await createImageBitmap(b);
    const t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL,false);gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL,gl.NONE);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,bm);gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR_MIPMAP_LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.REPEAT);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.REPEAT);
    const an=gl.getExtension('EXT_texture_filter_anisotropic');if(an)gl.texParameterf(gl.TEXTURE_2D,an.TEXTURE_MAX_ANISOTROPY_EXT,4);bm.close&&bm.close();return t}
  function mkMesh(m,skinned){const vao=gl.createVertexArray();gl.bindVertexArray(vao);
    const vb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,vb);gl.bufferData(gl.ARRAY_BUFFER,new Uint8Array(buf,m.vb[0],m.vb[1]),gl.STATIC_DRAW);
    const st=skinned?28:20;
    gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,4,gl.SHORT,true,st,0);
    gl.enableVertexAttribArray(1);gl.vertexAttribPointer(1,4,gl.SHORT,true,st,8);
    gl.enableVertexAttribArray(2);gl.vertexAttribPointer(2,2,gl.UNSIGNED_SHORT,true,st,16);
    if(skinned){gl.enableVertexAttribArray(3);gl.vertexAttribIPointer(3,4,gl.UNSIGNED_BYTE,st,20);gl.enableVertexAttribArray(4);gl.vertexAttribPointer(4,4,gl.UNSIGNED_BYTE,true,st,24)}
    const ib=gl.createBuffer();gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint8Array(buf,m.ib[0],m.ib[1]),gl.STATIC_DRAW);
    gl.bindVertexArray(null);return{vao,n:m.ni}}
  prog('Montando…',.55);
  const mc=mkMesh(man.char,true),mw=man.weapons.map(w=>({...mkMesh(w,false),w}));
  prog('Texturas…',.75);
  const tc=await tex(man.char.tex),tw=await tex(man.weapons[0].tex);
  const gv=gl.createVertexArray();gl.bindVertexArray(gv);const gb=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,gb);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,1,1,-1,-1,1,1,-1,1]),gl.STATIC_DRAW);gl.enableVertexAttribArray(0);gl.vertexAttribPointer(0,2,gl.FLOAT,false,0,0);gl.bindVertexArray(null);
  // ---- esqueleto
  const NB=man.bones.names.length,par=man.bones.parent,rest=man.bones.rest,driven=man.driven,ND=driven.length,PEL=man.pelvis;
  const wpre=new Float32Array(man.bones.wpre);
  const order=[];{const seen=new Array(NB).fill(false);const vis=k=>{if(seen[k])return;if(par[k]>=0)vis(par[k]);seen[k]=true;order.push(k)};for(let k=0;k<NB;k++)vis(k)}
  const lm=new Float32Array(16),W=new Float32Array(NB*16),SK=new Float32Array(NB*16),INV=new Float32Array(NB*16),wm=new Float32Array(16);
  const bindQ=new Float32Array(NB*4),bindT=new Float32Array(NB*3);
  for(let k=0;k<NB;k++){for(let i=0;i<4;i++)bindQ[k*4+i]=rest[k][i];for(let i=0;i<3;i++)bindT[k*3+i]=rest[k][4+i]}
  function fk(Q,T,dst){for(const k of order){M4.qt(lm,Q.subarray(k*4,k*4+4),T.subarray(k*3,k*3+3));const p=par[k];M4.mul(dst.subarray(k*16,k*16+16),p<0?wpre:dst.subarray(p*16,p*16+16),lm)}}
  const W0=new Float32Array(NB*16);fk(bindQ,bindT,W0);for(let k=0;k<NB;k++)M4.inv(INV.subarray(k*16,k*16+16),W0.subarray(k*16,k*16+16));
  const boneTex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,boneTex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,4,NB,0,gl.RGBA,gl.FLOAT,null);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.NEAREST);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  // ---- clipes
  const clips=man.clips;const cdur=c=>Math.max((c.nf-1)/c.fps,1e-3);
  let cur=0,t=0,playing=true,loop=true,speed=1,showW=true,spin=false,restMode=false,prev=null,blend=1,blendDur=.22;
  const poseQ=new Float32Array(NB*4),poseT=new Float32Array(NB*3),sQ=new Float32Array(ND*4),sP=new Float32Array(3);
  function sample(c,tt,outQ,outP){const nf=c.nf,f=Math.min(tt*c.fps,nf-1),i0=Math.min(Math.floor(f),nf-1),i1=Math.min(i0+1,nf-1),a=f-i0;
    const qa=new Int16Array(buf,c.q+i0*ND*8,ND*4),qb=new Int16Array(buf,c.q+i1*ND*8,ND*4);
    for(let j=0;j<ND;j++){const x=qa[j*4]/32767,y=qa[j*4+1]/32767,z=qa[j*4+2]/32767,w=qa[j*4+3]/32767,x2=qb[j*4]/32767,y2=qb[j*4+1]/32767,z2=qb[j*4+2]/32767,w2=qb[j*4+3]/32767;
      const rx=x+(x2-x)*a,ry=y+(y2-y)*a,rz=z+(z2-z)*a,rw=w+(w2-w)*a,l=1/Math.hypot(rx,ry,rz,rw);outQ[j*4]=rx*l;outQ[j*4+1]=ry*l;outQ[j*4+2]=rz*l;outQ[j*4+3]=rw*l}
    const pa=new Float32Array(buf,c.pt+i0*12,3),pb=new Float32Array(buf,c.pt+i1*12,3);for(let i=0;i<3;i++)outP[i]=pa[i]+(pb[i]-pa[i])*a}
  function updatePose(){
    poseQ.set(bindQ);poseT.set(bindT);
    if(!restMode){const c=clips[cur];sample(c,t,sQ,sP);
      if(prev&&blend<1){const b=blend*blend*(3-2*blend),pc=clips[prev.ci];sample(pc,Math.min(prev.t0+blend*blendDur,cdur(pc)),prev.q,prev.pt);
        for(let j=0;j<ND;j++){const o=j*4,d=prev.q[o]*sQ[o]+prev.q[o+1]*sQ[o+1]+prev.q[o+2]*sQ[o+2]+prev.q[o+3]*sQ[o+3],s=d<0?-1:1;
          let x=prev.q[o]*s*(1-b)+sQ[o]*b,y=prev.q[o+1]*s*(1-b)+sQ[o+1]*b,z=prev.q[o+2]*s*(1-b)+sQ[o+2]*b,w=prev.q[o+3]*s*(1-b)+sQ[o+3]*b;const l=1/Math.hypot(x,y,z,w);
          sQ[o]=x*l;sQ[o+1]=y*l;sQ[o+2]=z*l;sQ[o+3]=w*l}
        for(let i=0;i<3;i++)sP[i]=prev.pt[i]*(1-b)+sP[i]*b}
      for(let j=0;j<ND;j++){const k=driven[j];for(let i=0;i<4;i++)poseQ[k*4+i]=sQ[j*4+i]}
      for(let i=0;i<3;i++)poseT[PEL*3+i]=sP[i]}
    fk(poseQ,poseT,W);
    for(let k=0;k<NB;k++)M4.mul(SK.subarray(k*16,k*16+16),W.subarray(k*16,k*16+16),INV.subarray(k*16,k*16+16));
    gl.bindTexture(gl.TEXTURE_2D,boneTex);gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,4,NB,gl.RGBA,gl.FLOAT,SK)}
  function setClip(i,keep){if(i===cur&&!keep)return;
    if(clips[cur]&&!restMode){prev={q:new Float32Array(sQ),pt:new Float32Array(sP),ci:cur,t0:t};blend=0}
    cur=i;t=0}
  const attM=man.weapons.map(w=>new Float32Array(w.m));
  function draw(){
    const dpr=Math.min(window.devicePixelRatio||1,2),w=Math.floor(cv.clientWidth*dpr),h=Math.floor(cv.clientHeight*dpr);if(cv.width!==w||cv.height!==h){cv.width=w;cv.height=h}
    gl.viewport(0,0,cv.width,cv.height);gl.clearColor(.035,.039,.055,1);gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT);gl.enable(gl.DEPTH_TEST);
    const P=M4.persp(.5,cv.width/cv.height,.2,40);P[9]=-SH.insetY();P[8]=SH.insetX();
    const DD=SH.dist()*1.656,cp=Math.cos(cam.pitch),eye=[cam.tx+Math.sin(cam.yaw)*cp*DD,cam.ty+Math.sin(cam.pitch)*DD,Math.cos(cam.yaw)*cp*DD];
    const V=M4.look(eye,[cam.tx,cam.ty,0],[0,1,0]),VP=M4.mul(new Float32Array(16),P,V);
    gl.disable(gl.CULL_FACE);gl.enable(gl.BLEND);gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(PG);gl.uniformMatrix4fv(U(PG,'uVP'),false,VP);gl.uniform2f(U(PG,'uSh'),W[PEL*16+12],W[PEL*16+14]);gl.uniform1f(U(PG,'uGrid'),1);gl.bindVertexArray(gv);gl.drawArrays(gl.TRIANGLES,0,6);gl.disable(gl.BLEND);
    gl.useProgram(PC);gl.uniformMatrix4fv(U(PC,'uVP'),false,VP);gl.uniform3fv(U(PC,'uCtr'),man.char.ctr);gl.uniform3fv(U(PC,'uExt'),man.char.ext);gl.uniform3fv(U(PC,'uCam'),eye);gl.uniform1f(U(PC,'uMetal'),0);gl.uniform1f(U(PC,'uTm'),0);gl.uniform1f(U(PC,'uRune'),0);gl.uniform1f(U(PC,'uRim'),0);   // mesmo visual do Guerreiro
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,boneTex);gl.uniform1i(U(PC,'uBones'),1);
    gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,tc);gl.uniform1i(U(PC,'uTex'),0);
    gl.bindVertexArray(mc.vao);gl.drawElements(gl.TRIANGLES,mc.n,gl.UNSIGNED_INT,0);
    if(showW){gl.useProgram(PW);gl.uniformMatrix4fv(U(PW,'uVP'),false,VP);gl.uniform3fv(U(PW,'uCam'),eye);gl.uniform1f(U(PW,'uMetal'),.35);gl.uniform1f(U(PW,'uRim'),0);gl.uniform1f(U(PW,'uTm'),0);gl.uniform1f(U(PW,'uRune'),0);
      gl.bindTexture(gl.TEXTURE_2D,tw);gl.uniform1i(U(PW,'uTex'),0);
      mw.forEach((m,i)=>{M4.mul(wm,W.subarray(m.w.bone*16,m.w.bone*16+16),attM[i]);gl.uniform3fv(U(PW,'uCtr'),m.w.ctr);gl.uniform3fv(U(PW,'uExt'),m.w.ext);gl.uniformMatrix4fv(U(PW,'uM'),false,wm);gl.bindVertexArray(m.vao);gl.drawElements(gl.TRIANGLES,m.n,gl.UNSIGNED_INT,0)})}
    gl.bindVertexArray(null)}
  let last=performance.now();
  function frame(now){const dt=Math.min((now-last)/1000,.1);last=now;const c=clips[cur],dur=cdur(c);
    if(playing){t+=dt*speed;if(t>=dur){if(loop)t%=dur;else t=dur}}
    if(blend<1)blend=Math.min(1,blend+dt/blendDur);
    if(spin)cam.yaw+=dt*.6;
    updatePose();draw()}
  const CATS=man.cats.filter(c=>clips.some(x=>x.cat===c));
  const API={id:'mercenario',title:'Mercenário',
    clips:clips.map(c=>({name:c.name,cat:c.cat,dur:cdur(c),nf:c.nf})),cats:CATS,kit:null,
    frame,state:()=>({cur,t,dur:cdur(clips[cur]),playing,loop,speed,skill:null}),
    setClip(i){setClip(i);playing=true},seek(f){t=f*cdur(clips[cur]);playing=false},setPlaying(b){if(b&&!loop&&t>=cdur(clips[cur])-1e-3)t=0;playing=b},setLoop(b){loop=b},setSpeed(x){speed=x},
    toggles:[
      {id:'w',label:'Armas',sub:'Mostrar as duas lâminas',icon:'i-swords',get:()=>showW,set:v=>{showW=v}},
      {id:'spin',label:'Girar câmera',sub:'Órbita automática em volta do personagem',icon:'i-rotate',get:()=>spin,set:v=>{spin=v}},
      {id:'tp',label:'Pose de repouso',sub:'Mostra o modelo parado, sem animação',icon:'i-person',get:()=>restMode,set:v=>{restMode=v}}],
    actions:[],
    dispose(){playing=false;prev=null}};
  return API}

// ---------------------------------------------------------------- personagens
const CHARS={
  guerreiro:{id:'guerreiro',nome:'Guerreiro',icon:'i-axe',man:'m_g',block:'b_g',boot:()=>bootWarrior},
  mercenario:{id:'mercenario',nome:'Mercenário',icon:'i-swords',man:'m_m',block:'b_m',boot:()=>bootMerc}
};
let ENG=null,CURID=null,BUSY=false;

function loading(on,name,icon,t,f,sub){
  const L=$('load');if(on){L.classList.remove('off');L.querySelector('.em use').setAttribute('href','#'+icon);$('lname').textContent=name||''}else L.classList.add('off');
  if(t!=null)$('lt').textContent=t;if(f!=null)$('bar').firstChild.style.width=(f*100)+'%';if(sub!=null)$('ls').textContent=sub}

async function disposeEngine(){
  const S=SESS.cur;
  if(ENG){UI.unbind();try{ENG.dispose&&ENG.dispose()}catch(e){console.error(e)}}
  ENG=null;
  if(S){disposeSession(S);SESS.cur=null}
  document.body.classList.remove('game','playing');
  const lg=$('legacy');lg.querySelectorAll('button,input,select').forEach(e=>{e.onclick=e.onchange=e.oninput=null});
  for(const id of ['kit','kinfo','chips','list'])$(id).textContent='';const extra=$('panel').querySelectorAll('[id=enm]');extra.forEach(e=>e.remove());
  for(const id of ['gExit','gCD','gDum','title']){const e=$(id);if(e)e.onclick=null}
}

async function loadChar(id){
  if(BUSY||CURID===id)return;BUSY=true;const C=CHARS[id];
  $('chars').querySelectorAll('.char').forEach(b=>{b.disabled=true});
  loading(true,C.nome,C.icon,'Carregando…',.04,'');await nextPaint();await sleep(60);
  await disposeEngine();CURID=null;await sleep(30);
  const S=new Session(id);SESS.cur=S;
  const prog=(t,f)=>{loading(true,C.nome,C.icon,t,f)};
  try{
    prog('Lendo dados…',.08);
    const man=JSON.parse($(C.man).textContent);
    const buf=await unpackBlock($(C.block).textContent,prog);
    prog('Montando…',.5);
    const ex=man.extra||{};
    const SH={gl,cv,man,buf,cam:CAM,prog,fitD,dist:camDist,insetX:()=>INS.x,insetY:()=>INS.y,alive:()=>!S.dead,
      keys:p=>Object.keys(ex).filter(k=>k.startsWith(p)),
      extra:k=>buf.slice(ex[k][0],ex[k][0]+ex[k][1]),
      bin:k=>new Uint8Array(buf,ex[k][0],ex[k][1]),
      url:(k,type)=>{const u=URL.createObjectURL(new Blob([new Uint8Array(buf,ex[k][0],ex[k][1])],{type}));S.urls.push(u);return u}};
    camReset();
    const eng=await C.boot()(SH);
    if(S.dead)throw new Error('cancelado');
    ENG=eng;CURID=id;UI.bind(eng,C);
    window.__V99dbg={eng:()=>ENG,id:()=>CURID};
    prog('Pronto',1);await sleep(120);
  }catch(e){
    console.error(e);loading(true,C.nome,C.icon,'Erro ao carregar',1,String(e&&e.message||e));
    disposeSession(S);SESS.cur=null;ENG=null;CURID=null;BUSY=false;
    $('chars').querySelectorAll('.char').forEach(b=>{b.disabled=false;b.classList.remove('on')});return}
  loading(false);BUSY=false;
  $('chars').querySelectorAll('.char').forEach(b=>{b.disabled=false;b.classList.toggle('on',b.dataset.id===id)});
}

// ---------------------------------------------------------------- interface
const norm=s=>s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g,'');
const SPEEDS=[.25,.5,1,1.5,2];
const fmtX=v=>(v===1?'1':String(v))+'×';
const useHref=(el,id)=>{el.querySelector('use').setAttribute('href','#'+id)};
const UI={
  eng:null,tab:'anim',cat:'Tudo',q:'',rows:[],cache:{},drag:false,tglEls:[],kitEls:{},
  init(){
    const body=document.body;
    this.setOpen=v=>{body.classList.toggle('open',v);if(v)this.revealCur()};
    $('fold').onclick=()=>this.setOpen(!body.classList.contains('open'));
    $('opener').onclick=()=>this.setOpen(true);$('shut').onclick=()=>this.setOpen(false);
    document.querySelectorAll('.tab').forEach(b=>b.onclick=()=>{const t=b.dataset.tab;if(this.tab===t&&body.classList.contains('open')&&!matchMedia('(orientation:landscape)').matches){this.setOpen(false);return}this.showTab(t);this.setOpen(true)});
    $('chars').querySelectorAll('.char').forEach(b=>b.onclick=()=>loadChar(b.dataset.id));
    $('bPlay').onclick=()=>{const e=this.eng;if(!e)return;const s=e.state();e.setPlaying(!s.playing)};
    $('bLoop').onclick=()=>{const e=this.eng;if(!e)return;e.setLoop(!e.state().loop)};
    $('bSpd').onclick=()=>{const e=this.eng;if(!e)return;const i=SPEEDS.indexOf(e.state().speed);e.setSpeed(SPEEDS[(i+1)%SPEEDS.length])};
    $('bCam').onclick=()=>camReset();
    $('bPrev').onclick=()=>this.step(-1);$('bNext').onclick=()=>this.step(1);
    const sb=$('seekbar');sb.addEventListener('pointerdown',()=>{this.drag=true});const un=()=>{this.drag=false};sb.addEventListener('pointerup',un);sb.addEventListener('pointercancel',un);sb.addEventListener('change',un);
    sb.addEventListener('input',()=>{if(!this.eng)return;this.eng.seek(sb.value/1000);sb.style.setProperty('--p',sb.value/10+'%')});
    const q=$('q');q.addEventListener('input',()=>{this.q=norm(q.value.trim());$('q').closest('.search').classList.toggle('has',!!q.value);this.filter()});
    $('qx').onclick=()=>{q.value='';q.dispatchEvent(new Event('input'));q.focus()};
    // dica: tocar duas vezes na tela reseta a camera
    cv.addEventListener('dblclick',()=>{if(!document.body.classList.contains('game'))camReset()});
  },
  showTab(t){this.tab=t;document.querySelectorAll('.tab').forEach(b=>b.classList.toggle('on',b.dataset.tab===t));
    const id={anim:'pAnim',skills:'pSkills',cfg:'pCfg'}[t];document.querySelectorAll('.pane').forEach(p=>{const on=p.id===id;if(on&&!p.classList.contains('on')){p.classList.add('in');_st(()=>p.classList.remove('in'),400)}p.classList.toggle('on',on)});
    if(t==='anim')this.revealCur()},
  bind(eng,C){
    this.eng=eng;this.cat='Tudo';this.q='';this.cache={};$('q').value='';$('q').closest('.search').classList.remove('has');
    // chips
    const cb=$('chipbar');cb.textContent='';const cnt={};eng.clips.forEach(c=>cnt[c.cat]=(cnt[c.cat]||0)+1);
    const mk=(name,n)=>{const b=document.createElement('button');b.className='chip2'+(name==='Tudo'?' on':'');b.innerHTML=`${name}<i>${n}</i>`;b.onclick=()=>{this.cat=name;[...cb.children].forEach(x=>x.classList.toggle('on',x===b));this.filter();$('animlist').scrollTop=0};cb.appendChild(b)};
    mk('Tudo',eng.clips.length);eng.cats.forEach(c=>mk(c,cnt[c]||0));
    // lista
    const L=$('animlist');L.textContent='';this.rows=eng.clips.map((c,i)=>{const b=document.createElement('button');b.className='row';b.dataset.i=i;
      b.innerHTML=`<span class="eq"><i></i><i></i><i></i></span><span class="nm">${c.name.replace(/</g,'&lt;')}<span class="ct">${c.cat}</span></span><span class="mt">${c.dur.toFixed(2)}s</span>`;
      b.onclick=()=>{eng.setClip(i)};b.dataset.k=norm(c.name+' '+c.cat);L.appendChild(b);return b});
    this.empty=document.createElement('div');this.empty.className='empty';this.empty.textContent='Nenhuma animação encontrada';this.empty.hidden=true;L.appendChild(this.empty);
    // skills
    const hasKit=!!eng.kit;document.querySelector('.tab[data-tab="skills"]').hidden=!hasKit;
    const kg=$('kitgrid');kg.textContent='';this.kitEls={};
    if(hasKit){eng.kit.forEach(s=>{const b=document.createElement('button');b.className='sk'+(s.ult?' ult':'');b.dataset.id=s.id;
      b.innerHTML=`<span class="ic"><svg class="i"><use href="#${s.icon}"/></svg></span><b>${s.nome}</b><small>${s.cd?`<svg class="i"><use href="#i-clock"/></svg>${s.cd}s`:s.tag}</small>`;
      b.onclick=()=>{eng.useSkill(s.id);this.showInfo(s)};kg.appendChild(b);this.kitEls[s.id]=b});
      $('skinfo').innerHTML='<b>Habilidades</b>Toque numa habilidade para ver a animação com os efeitos.'}
    // ajustes
    const cfg=$('cfg');cfg.textContent='';this.tglEls=[];
    const sec=t=>{const d=document.createElement('div');d.className='sec';d.textContent=t;cfg.appendChild(d)};
    sec('Exibição');
    eng.toggles.forEach(t=>{const b=document.createElement('button');b.className='sw';b.innerHTML=`<svg class="i"><use href="#${t.icon}"/></svg><span>${t.label}<small>${t.sub||''}</small></span><i class="tg2"></i>`;
      b.onclick=()=>{t.set(!t.get());this.syncToggles()};cfg.appendChild(b);this.tglEls.push([t,b])});
    sec('Velocidade');
    const sg=document.createElement('div');sg.className='seg';SPEEDS.forEach(v=>{const b=document.createElement('button');b.textContent=fmtX(v);b.dataset.v=v;b.onclick=()=>eng.setSpeed(v);sg.appendChild(b)});cfg.appendChild(sg);this.segEl=sg;
    sec('Câmera');
    {const b=document.createElement('button');b.className='act';b.innerHTML='<svg class="i"><use href="#i-cam"/></svg>Resetar câmera';b.onclick=()=>camReset();cfg.appendChild(b)}
    if(eng.actions&&eng.actions.length){sec('Ações');const g=document.createElement('div');g.className='grid2';eng.actions.forEach(a=>{const b=document.createElement('button');b.className='act'+(a.tone==='red'?' red':'');
        const lab=()=>a.dyn?a.dyn():a.label;b.innerHTML=`<svg class="i"><use href="#${a.icon}"/></svg><span>${lab()}</span>`;b.onclick=()=>{a.run();_st(()=>{b.lastChild.textContent=lab()},60)};if(a.wide)b.style.gridColumn='1/-1';g.appendChild(b)});cfg.appendChild(g)}
    {const v=document.createElement('div');v.className='ver';v.textContent=`${C.nome.toUpperCase()} · VISUALIZADOR V99`;cfg.appendChild(v)}
    this.showTab('anim');this.filter();this.cur=-1;this.sync(true);
    useHref($('fold'),'i-chev');
  },
  unbind(){this.eng=null;this.rows=[];this.tglEls=[];this.kitEls={};$('animlist').textContent='';$('chipbar').textContent='';$('kitgrid').textContent='';$('cfg').textContent='';$('nowName').textContent='—';$('nowMeta').textContent=''},
  filter(){let n=0;for(const r of this.rows){const c=this.eng.clips[+r.dataset.i];const ok=(this.cat==='Tudo'||c.cat===this.cat)&&(!this.q||r.dataset.k.includes(this.q));r.hidden=!ok;if(ok)n++;
      r.querySelector('.ct').style.display=this.cat==='Tudo'?'block':'none'}
    if(this.empty)this.empty.hidden=n>0},
  visible(){return this.rows.filter(r=>!r.hidden)},
  step(d){const e=this.eng;if(!e)return;const v=this.visible();if(!v.length)return;let k=v.findIndex(r=>+r.dataset.i===e.state().cur);k=k<0?0:(k+d+v.length)%v.length;e.setClip(+v[k].dataset.i);this.revealCur()},
  revealCur(){const r=this.rows[this.cur];if(r&&!r.hidden&&this.tab==='anim')_st(()=>r.scrollIntoView({block:'nearest',behavior:'smooth'}),60)},
  showInfo(s){$('skinfo').innerHTML=`<b>${s.nome}</b><div class="tags"><span class="tg">${s.tag}</span>${s.cd?`<span class="tg">Recarga ${s.cd}s</span>`:'<span class="tg">Sem recarga</span>'}${s.custo?`<span class="tg">Custo ${s.custo}</span>`:''}</div>${s.desc}`},
  syncToggles(){for(const[t,b]of this.tglEls){const v=!!t.get();if(b._v!==v){b._v=v;b.classList.toggle('on',v)}}},
  sync(force){const e=this.eng;if(!e)return;const s=e.state(),C=this.cache;
    if(s.cur!==this.cur||force){const c=e.clips[s.cur];this.cur=s.cur;this.rows.forEach((r,i)=>r.classList.toggle('on',i===s.cur));$('nowName').textContent=c.name;$('nowMeta').textContent=`${c.cat} · ${c.nf} quadros · ${c.dur.toFixed(2)}s`;$('tDur').textContent=c.dur.toFixed(2)+'s';this.revealCur()}
    if(s.playing!==C.playing||force){C.playing=s.playing;document.body.classList.toggle('playing',s.playing);useHref($('bPlay'),s.playing?'i-pause':'i-play')}
    if(s.loop!==C.loop||force){C.loop=s.loop;$('bLoop').classList.toggle('on',s.loop)}
    if(s.speed!==C.speed||force){C.speed=s.speed;$('bSpd').textContent=fmtX(s.speed);if(this.segEl)[...this.segEl.children].forEach(b=>b.classList.toggle('on',+b.dataset.v===s.speed))}
    const p=s.dur>0?Math.min(1,s.t/s.dur):0,pv=Math.round(p*1000);
    if(!this.drag&&pv!==C.pv){C.pv=pv;const sb=$('seekbar');sb.value=pv;sb.style.setProperty('--p',pv/10+'%')}
    const tn=s.t.toFixed(2)+'s';if(tn!==C.tn){C.tn=tn;$('tNow').textContent=tn}
    if(s.skill!==C.skill||force){C.skill=s.skill;for(const id in this.kitEls)this.kitEls[id].classList.toggle('on',id===s.skill)}
    if(!(this._f=(this._f||0)+1)%6||force)this.syncToggles();
  }
};
UI.init();

function loop(now){_raf(loop);computeInsets();if(ENG&&!BUSY){try{ENG.frame(now)}catch(err){if(!loop.err){loop.err=1;console.error(err)}}UI.sync()}}
window.__V99={GLC:()=>Object.assign({},GLC),live:()=>LIVE.size,sess:()=>SESS.cur&&{timers:SESS.cur.timers.size,listeners:SESS.cur.listeners.length,gl:SESS.cur.gl.length,ctxs:SESS.cur.ctxs.length,urls:SESS.cur.urls.length},
  load:loadChar,cur:()=>CURID,busy:()=>BUSY,eng:()=>ENG,probe:()=>{window.__wr=ENG&&new WeakRef(ENG)}};
if(!gl){loading(true,'','i-axe','Seu navegador não suporta WebGL2.',0)}
else{camReset();_raf(loop);loadChar('guerreiro')}
})();
