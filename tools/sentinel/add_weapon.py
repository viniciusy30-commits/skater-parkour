# Passo 2: coloca a arma na(s) mao(s) do guerreiro. Gera assets/w/char.msh e assets/w/char_atlas.jpg (rig.bin e anim.bin nao mudam).
#   python3 add_weapon.py            (HANDS=r so mao direita | HANDS=l so esquerda | padrao rl = um machado em cada mao)
# A arma e fixa (peso 100%) no osso da mao, entao segue todas as 602 animacoes. Entrada: weapon/ (malha reduzida + texturas).
import numpy as np, struct, json, io, os, sys
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from recolor import tattoo_red, axe_to_char
from scipy.optimize import minimize
from PIL import Image
from scipy.spatial.transform import Rotation as Rot
HERE=os.path.dirname(os.path.abspath(__file__)); WD=HERE+'/weapon/'
ASSET=os.environ.get('ASSET',os.path.abspath(HERE+'/../../app/src/main/assets/w/'))+'/'
OUT=os.environ.get('OUT',ASSET)
HANDS=os.environ.get('HANDS','rl')
FLIP=int(os.environ.get('FLIP','1'))           # 1 = gira a arma 180 graus em volta do cabo (lamina grande na frente do golpe)
GRIP_R=float(os.environ.get('GRIP_R','0.70'))  # afina o cabo (fator do raio) para a mao fechar de verdade
CURL=int(os.environ.get('CURL','1'))           # 1 = fecha os dedos em volta do cabo
SC=float(os.environ.get('SC','0.45'))        # escala do machado (original tem 1,9 de altura -> 0,86 m)
YG=float(os.environ.get('YG','-0.43'))       # ponto do cabo (no modelo da arma) que fica dentro do punho
TILT=float(os.environ.get('TILT','12'))      # inclinacao da cabeca em direcao ao pulso (graus)
# ---------- esqueleto (rig.bin ATUAL, so repouso) e nomes das juntas
b=open(ASSET+'rig.bin','rb').read(); o=0
def rdI(n):
    global o; a=np.frombuffer(b,'<i4',n,o).copy(); o+=4*n; return a
def rdF(n):
    global o; a=np.frombuffer(b,'<f4',n,o).copy(); o+=4*n; return a
nN,nJ,pelN,nb=rdI(4); par=rdI(nN); T=rdF(nN*3).reshape(-1,3); Q=rdF(nN*4).reshape(-1,4); order=rdI(nN); jn=rdI(nJ)
g=open(HERE+'/ironbound.glb','rb').read(); cl,_=struct.unpack_from('<II',g,12); GJ=json.loads(g[20:20+cl])
names=[GJ['nodes'][i].get('name') for i in GJ['skins'][0]['joints']]
Wd=[None]*nN
for n in order:
    M=np.eye(4); M[:3,:3]=Rot.from_quat(Q[n]/np.linalg.norm(Q[n])).as_matrix(); M[:3,3]=T[n]
    Wd[n]=M if par[n]<0 else Wd[par[n]]@M
ibm=rdF(nJ*16).reshape(nJ,4,4); SK=np.array([Wd[jn[j]]@ibm[j].T for j in range(nJ)])      # SK[j]: bind -> repouso
HS=float(os.environ.get('HS','1.0'))        # escala da mao (punho maior = o cabo original cabe sem afinar demais)
FINGK=('thumb','index','middle','ring','pinky')
def gp(nm):
    p=Wd[jn[names.index(nm)]][:3,3]
    if nm[-2:] in ('_r','_l') and any(k in nm for k in FINGK): w=Wd[jn[names.index('hand_'+nm[-1])]][:3,3]; return w+HS*(p-w)
    return p
unit=lambda v: v/np.linalg.norm(v)
# ---------- malha base (guerreiro) ja existente
m=open(WD+'char_base.msh','rb').read(); nv0,ni0,st=struct.unpack_from('<3i',m,4); ctr0=np.array(struct.unpack_from('<3f',m,16)); ext0=np.array(struct.unpack_from('<3f',m,28))
R0=np.frombuffer(m,np.dtype([('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)]),nv0,40)
P0=ctr0+R0['p'][:,:3]/32767.0*ext0; N0=R0['n'][:,:3]/32767.0; MET0=R0['n'][:,3]/32767.0; UV0=R0['uv']/65535.0
I0=np.frombuffer(m,'<u4',ni0,40+nv0*st).astype(np.int64).reshape(-1,3)
# ---------- machado reduzido, posto na mao direita (espaco de repouso = espaco do bind)
AP=np.fromfile(WD+'axe_P.f32','<f4').reshape(-1,3).astype(np.float64); AUV=np.fromfile(WD+'axe_UV.f32','<f4').reshape(-1,2).astype(np.float64); AI=np.fromfile(WD+'axe_I.u32','<u4').reshape(-1,3).astype(np.int64)
def smooth_normals(P,I):
    fn=np.cross(P[I[:,1]]-P[I[:,0]],P[I[:,2]]-P[I[:,0]]); k=np.round(P*1e5).astype(np.int64); _,inv=np.unique(k,axis=0,return_inverse=True); inv=inv.reshape(-1)
    acc=np.zeros((inv.max()+1,3))
    for c in range(3): np.add.at(acc,inv[I[:,c]],fn)
    N=acc[inv]; return N/np.maximum(np.linalg.norm(N,axis=1,keepdims=True),1e-12)
AXC=np.array([0.04,0.0,0.0])                                                  # eixo real do cabo no modelo (fica 0.04 fora da origem)
def squeeze(P):
    P=P.copy(); d=P-np.array([AXC[0],0,0]); r=np.hypot(d[:,0],d[:,2])
    sy=np.clip((P[:,1]+0.66)/0.06,0,1)*np.clip((-0.18-P[:,1])/0.06,0,1); sy=sy*sy*(3-2*sy)    # so na regiao do cabo (y -0.60..-0.24)
    sr=np.clip((0.15-r)/0.05,0,1); sr=sr*sr*(3-2*sr)                                          # nao mexe nas lâminas/guardas (r grande)
    k=1-(1-GRIP_R)*sy*sr; P[:,0]=AXC[0]+d[:,0]*k; P[:,2]=d[:,2]*k; return P
AN=smooth_normals(AP,AI)
def frame(sd):
    """centro c, eixo do cabo h e base Rm do machado para a mao sd ('r'/'l'), a partir das juntas dessa mao."""
    hand=gp('hand_'+sd); f=unit(gp('middle_01_'+sd)-hand); kn=unit(gp('index_01_'+sd)-gp('pinky_01_'+sd))
    fing=np.mean([gp(n+'_'+sd) for n in ('index_02','middle_02','pinky_02','index_03','middle_03','pinky_03','index_01','middle_01','pinky_01')],0)
    c=0.5*fing+0.25*(gp('middle_metacarpal_'+sd)+gp('middle_01_'+sd))      # centro do tunel do punho
    t=np.radians(TILT); h=unit(np.cos(t)*kn-np.sin(t)*f)                   # eixo do cabo: linha dos nos dos dedos, cabeca para o lado do indicador
    # recentra o cabo no centro de curvatura natural dos dedos (ajuste de circulo no plano perpendicular ao cabo)
    u=unit(np.cross(h,[0,1,0])); w=np.cross(h,u); cs=[]
    for fn in ('index','middle','ring','pinky'):
        p=[gp(f'{fn}_{k}_{sd}') for k in ('01','02','03')]; p.append(p[2]+(p[2]-p[1])*0.9)
        q=np.array([((x-c)@u,(x-c)@w) for x in p]); A=np.c_[2*q,np.ones(4)]; s_=np.linalg.lstsq(A,(q**2).sum(1),rcond=None)[0]; cs.append(s_[:2])
    m_=np.mean(cs,0); c=c+m_[0]*u+m_[1]*w
    bx=unit(f-(f@h)*h); bz=np.cross(bx,h)
    if FLIP: bx,bz=-bx,-bz                                                  # rotacao de 180 graus em torno do cabo
    return c,h,np.stack([bx,h,bz],1)                                        # laminas ao longo do antebraco
# ---------- mao fechando: primeiro fecha os dedos (curva suave, igual em todos), depois ajusta o cabo ao oco do punho
def rotm(a,th):
    a=unit(a); K=np.array([[0,-a[2],a[1]],[a[2],0,-a[0]],[-a[1],a[0],0]]); return np.eye(3)+np.sin(th)*K+(1-np.cos(th))*K@K
def rot_about(a,th,p):
    M=np.eye(4); M[:3,:3]=rotm(a,th); M[:3,3]=p-M[:3,:3]@p; return M
FT=0.0085                                                           # meia espessura do dedo
E_F=np.radians([float(x) for x in os.environ.get('CURL_F','20,30,15').split(',')])      # flexao EXTRA dos 4 dedos (somada ao punho relaxado do rig)
E_T=np.radians([float(x) for x in os.environ.get('CURL_T','20,30,20').split(',')])      # flexao extra do polegar
FR={sd:frame(sd) for sd in 'rl'}
GRIP={}; HRs=[]; IDX={}
def close_hand(sd):
    cc,hh,Rm_=FR[sd]; u=unit(np.cross(hh,[0,1,0])); w=np.cross(hh,u); out=[]; allp=[]
    palm=gp('middle_metacarpal_'+sd)
    def chain(ch,E):
        p=[gp(n) for n in ch]; tip=p[2]+(p[2]-p[1])*0.9; mids=[(p[0]+p[1])/2,(p[1]+p[2])/2,(p[2]+tip)/2]
        best=None
        for sg in (1,-1):
            G=np.eye(4); Gs=[]
            for i in range(3): G=G@rot_about(hh,sg*E[i],p[i]); Gs.append(G.copy())
            ap=lambda M,x:(M@np.append(x,1))[:3]
            tp=ap(Gs[2],tip); dpalm=np.linalg.norm(tp-(cc if ch[0].startswith('thumb') else palm))
            if best is None or dpalm<best[0]: best=(dpalm,Gs,[ap(Gs[1],p[2]),tp,ap(Gs[0],mids[0]),ap(Gs[1],mids[1]),ap(Gs[2],mids[2])])
        return best[1],best[2]
    for fn in ('index','middle','ring','pinky'):
        ch=[f'{fn}_{k}_{sd}' for k in ('01','02','03')]; Gs,pts=chain(ch,E_F); allp+=pts
        if fn=='index': pp_=[gp(n) for n in ch]; IDX[sd]=((Gs[0]@np.append(pp_[1],1))[:3],(Gs[1]@np.append(pp_[2],1))[:3])
        for n,Gk in zip(ch,Gs): out.append((names.index(n),Gk))
    ch=[f'thumb_{k}_{sd}' for k in ('01','02','03')]
    pt=[gp(n) for n in ch]; tip=pt[2]+(pt[2]-pt[1])*0.9
    i2=IDX[sd][0]; i3=IDX[sd][1]; rad_=(0.5*(i2+i3)-cc); rad_=rad_-(rad_@hh)*hh; rad_=unit(rad_)
    tgt=0.5*(i2+i3)+rad_*(FT*2.0+0.004)                                    # ponta do polegar pousa por cima dos dedos
    ax_=unit(np.cross(tip-pt[0],tgt-pt[0])); bestT=None
    for sg in (1,-1):
        for phi in np.radians(np.arange(0,230,2.0)):
            G=np.eye(4); Gs_=[]
            for i,wt in enumerate((0.3,0.4,0.3)): G=G@rot_about(ax_,sg*phi*wt,pt[i]); Gs_.append(G.copy())
            d_=np.linalg.norm((Gs_[2]@np.append(tip,1))[:3]-tgt)
            if bestT is None or d_<bestT[0]: bestT=(d_,Gs_)
    Gs=bestT[1]
    for n,Gk in zip(ch,Gs): out.append((names.index(n),Gk))
    # circulo ajustado aos dedos fechados (plano perpendicular ao cabo): centro e raio do oco do punho
    q=np.array([((x-cc)@u,(x-cc)@w) for x in allp]); A=np.c_[2*q,np.ones(len(q))]; sol=np.linalg.lstsq(A,(q**2).sum(1),rcond=None)[0]
    ctr2=sol[:2]; rad=np.sqrt(sol[2]+ctr2@ctr2)
    return out,cc+ctr2[0]*u+ctr2[1]*w,rad
if CURL:
    for sd in 'rl':
        GRIP[sd],cn,rad=close_hand(sd); FR[sd]=(cn,FR[sd][1],FR[sd][2]); HRs.append(rad-FT)
        print('  mao',sd,'oco do punho: raio do cabo ideal',round(rad-FT,4),'m')
    HR=float(np.clip(np.mean(HRs),0.016,0.045))
    GRIP_R=HR/(0.085*SC)
else: HR=0.085*GRIP_R*SC
print('  raio do cabo',round(HR,4),'m  (fator',round(GRIP_R,2),')')
AP=squeeze(AP); AN=smooth_normals(AP,AI)
c,h,Rm=FR['r']
PR=(Rm@(SC*(AP-np.array([AXC[0],YG,0]))).T).T+c; NR=(Rm@AN.T).T
cL,hL,RmL=FR['l']; ZF=np.array([1,1,-1])                                    # mao esquerda: machado espelhado (z invertido no modelo), no referencial da propria mao
PL=(RmL@(SC*(AP*ZF-np.array([AXC[0],YG,0]))).T).T+cL; NL_=(RmL@(AN*ZF).T).T
mimg=np.array(Image.open(WD+'axe_metal.png').convert('L')).astype(np.float64)/255; mh,mw=mimg.shape
def met(uv): return mimg[np.clip(uv[:,1]*(mh-1),0,mh-1).astype(int),np.clip(uv[:,0]*(mw-1),0,mw-1).astype(int)]

if CURL:
    for k_ in [k for k in GRIP if k not in HANDS]: GRIP.pop(k_)
    Jm=R0['j'].astype(int); Wm=R0['w'].astype(np.float64)/255.0
    Wm=Wm/np.maximum(Wm.sum(1,keepdims=True),1e-9)
    MB=np.einsum('vk,vkab->vab',Wm,SK[Jm])
    ph=np.c_[P0,np.ones(len(P0))]; Pr=np.einsum('vab,vb->va',MB,ph)[:,:3]; Nr=np.einsum('vab,vb->va',MB[:,:3,:3],N0)
    for sd_ in 'rl':
        hset=[i for i,nm in enumerate(names) if nm.endswith('_'+sd_) and any(k in nm for k in FINGK+('hand',))]
        wh=np.zeros(len(Pr))
        for k in range(4): wh+=np.where(np.isin(Jm[:,k],hset),Wm[:,k],0)
        wr=Wd[jn[names.index('hand_'+sd_)]][:3,3]; Pr=wr+(1+(HS-1)*wh)[:,None]*(Pr-wr)
    Pn=Pr.copy(); Nn=Nr.copy()
    for side,lst in GRIP.items():
        M={j:G for j,G in lst}
        acc=np.zeros_like(Pr); accn=np.zeros_like(Nr); wsum=np.zeros(len(Pr))
        for k in range(4):
            for j,G in M.items():
                sel=(Jm[:,k]==j)&(Wm[:,k]>0)
                if not sel.any(): continue
                w=Wm[sel,k]; acc[sel]+=w[:,None]*((G[:3,:3]@Pn[sel].T).T+G[:3,3]); accn[sel]+=w[:,None]*(G[:3,:3]@Nn[sel].T).T; wsum[sel]+=w
        mv=wsum>0
        Pn=np.where(mv[:,None],acc+(1-wsum)[:,None]*Pn,Pn); Nn=np.where(mv[:,None],accn+(1-wsum)[:,None]*Nn,Nn)
    inv=np.linalg.inv(MB)
    P0=np.einsum('vab,vb->va',inv,np.c_[Pn,np.ones(len(Pn))])[:,:3]; N0=np.einsum('vab,vb->va',inv[:,:3,:3],Nn)
    N0=N0/np.maximum(np.linalg.norm(N0,axis=1,keepdims=True),1e-9)
    print('maos fechadas:',list(GRIP))
parts=[]
if 'r' in HANDS: parts.append((PR,NR,AI,names.index('hand_r')))
if 'l' in HANDS: parts.append((PL,NL_,AI[:,[0,2,1]],names.index('hand_l')))   # espelhado, enrolamento invertido
PL=[P0];NL=[N0];UL=[UV0*np.array([1,.5])];IL=[I0];JL=[R0['j'].astype(np.int64)];WL=[R0['w'].astype(np.int64)];ML=[MET0]; off=nv0
for P_,N_,I_,hj in parts:
    n=len(P_); PL.append(P_);NL.append(N_);UL.append(np.stack([AUV[:,0],.5+.5*AUV[:,1]],1));IL.append(I_+off); off+=n
    j=np.zeros((n,4),np.int64); j[:,0]=hj; w=np.zeros((n,4),np.int64); w[:,0]=255; JL.append(j);WL.append(w);ML.append(met(AUV))
P=np.concatenate(PL);N=np.concatenate(NL);UV=np.concatenate(UL);I=np.concatenate(IL).reshape(-1);J=np.concatenate(JL);Wq=np.concatenate(WL);MT=np.concatenate(ML)
nv=len(P); assert nv<65536,'mais de 65535 vertices: reduza TRIS em prep_axe.py'
lo=P.min(0);hi=P.max(0);ctr=(lo+hi)/2;ext=(hi-lo)/2*1.001
r=np.zeros(nv,dtype=[('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)])
r['p'][:,:3]=np.clip(np.round((P-ctr)/ext*32767),-32767,32767); r['n'][:,:3]=np.clip(np.round(N*32767),-32767,32767); r['n'][:,3]=np.clip(MT*32767+.5,0,32767)
r['uv']=np.clip(np.round(UV*65535),0,65535); r['j']=J; r['w']=Wq
os.makedirs(OUT,exist_ok=True)
with open(OUT+'char.msh','wb') as fo:
    fo.write(struct.pack('<4i',0,nv,len(I),28)); fo.write(struct.pack('<3f',*ctr)); fo.write(struct.pack('<3f',*ext)); fo.write(r.tobytes()); fo.write(I.astype('<u4').tobytes())
# ---------- atlas 2048x4096: guerreiro em cima, arma embaixo
cimg=np.array(Image.open(WD+'char_atlas_base.jpg').convert('RGB')); aimg=np.array(Image.open(WD+'axe_base.jpg').convert('RGB').resize((2048,2048),Image.LANCZOS))
import cv2
hd=[names.index(n) for n in names if n in ('head','neck_01','neck_02')]
hv=np.zeros(nv0,bool)
for k_ in range(4): hv|=np.isin(R0['j'][:,k_].astype(int),hd)&(R0['w'][:,k_]>100)
ex=np.zeros(cimg.shape[:2],np.uint8); H_,W_=ex.shape
for tri in I0[hv[I0].all(1)]:
    cv2.fillPoly(ex,[np.round(UV0[tri]*[W_-1,H_-1]).astype(np.int32)],1)
cimg2,_=tattoo_red(cimg,ex)                    # tatuagens vermelhas (cor das runas)
aimg2,_=axe_to_char(aimg,cimg)              # metal preto e dourado iguais aos da roupa
at=Image.new('RGB',(2048,4096)); at.paste(Image.fromarray(cimg2),(0,0)); at.paste(Image.fromarray(aimg2),(0,2048))
at.save(OUT+'char_atlas.jpg',quality=94,subsampling=0)
print('maos',HANDS,'| vertices',nv,'(guerreiro',nv0,') triangulos',len(I)//3,'| atlas 2048x4096 | altura da arma',round(1.9*SC,2),'m')
