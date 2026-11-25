# Passo 3: gira o punho (torcao do antebraco) para o machado ficar de pe na mao.
#   python3 roll_hands.py   (le/escreve assets/w/char.msh; rode DEPOIS de add_weapon.py)
# A torcao e "assada" no bind: cresce de 0 no cotovelo a 100% no pulso e vale 100% na mao, nos dedos e na arma.
# O angulo de cada mao e escolhido para o eixo do machado ficar o mais vertical possivel na pose parada (Idle).
import numpy as np, struct, json, os, sys
from scipy.spatial.transform import Rotation as Rot
HERE=os.path.dirname(os.path.abspath(__file__)); ASSET=os.environ.get('ASSET',os.path.abspath(HERE+'/../../app/src/main/assets/w/'))+'/'; OUT=os.environ.get('OUT',ASSET)
FWD=float(os.environ.get('FWD','15'))   # inclinacao da cabeca do machado para a frente, em graus
b=open(ASSET+'rig.bin','rb').read(); o=0
def rdI(n):
    global o; a=np.frombuffer(b,'<i4',n,o).copy(); o+=4*n; return a
def rdF(n):
    global o; a=np.frombuffer(b,'<f4',n,o).copy(); o+=4*n; return a
nN,nJ,pelN,nb=rdI(4); par=rdI(nN); T=rdF(nN*3).reshape(-1,3); Q=rdF(nN*4).reshape(-1,4); order=rdI(nN); jn=rdI(nJ); ibm=rdF(nJ*16).reshape(nJ,4,4); bn=rdI(nb)
g=open(HERE+'/ironbound.glb','rb').read(); cl,_=struct.unpack_from('<II',g,12); GJ=json.loads(g[20:20+cl]); names=[GJ['nodes'][i].get('name') for i in GJ['skins'][0]['joints']]
def world(q=None,pel=None):
    q0=Q.copy(); t0=T.copy()
    if q is not None:
        for k in range(nb): q0[bn[k]]=q[k]/np.linalg.norm(q[k])
        t0[pelN]=pel
    Wd=[None]*nN
    for n in order:
        M=np.eye(4); M[:3,:3]=Rot.from_quat(q0[n]/np.linalg.norm(q0[n])).as_matrix(); M[:3,3]=t0[n]; Wd[n]=M if par[n]<0 else Wd[par[n]]@M
    return Wd
Wr=world(); SK=np.array([Wr[jn[j]]@ibm[j].T for j in range(nJ)]); J_=lambda nm: names.index(nm)
# pose parada: primeiro quadro de c:attack_01 (= Idle do visualizador)
d=open(ASSET+'anim.bin','rb').read(); n=struct.unpack_from('<i',d,0)[0]; p=4; pose0=None
for i in range(n):
    l=struct.unpack_from('<H',d,p)[0]; p+=2; nm=d[p:p+l].decode(); p+=l; nf=struct.unpack_from('<i',d,p)[0]; p+=4
    dt=np.dtype([('q','<i2',nb*4),('pel','<f4',3),('root','<f4',3)]); R=np.frombuffer(d,dt,nf,p); p+=nf*dt.itemsize
    if nm=='c:attack_01': pose0=(R['q'][0].reshape(nb,4)/32767.0,R['pel'][0].copy()); break
Wi=world(*pose0)
m=open(ASSET+'char.msh','rb').read(); nv,ni,st=struct.unpack_from('<3i',m,4); ctr=np.array(struct.unpack_from('<3f',m,16)); ext=np.array(struct.unpack_from('<3f',m,28))
dtv=np.dtype([('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)]); R0=np.frombuffer(m,dtv,nv,40).copy(); tri=m[40+nv*st:]
P=ctr+R0['p'][:,:3]/32767.0*ext; N=R0['n'][:,:3]/32767.0; Jm=R0['j'].astype(int); Wm=R0['w'].astype(float)/255.0; Wm/=np.maximum(Wm.sum(1,keepdims=True),1e-9)
MB=np.einsum('vk,vkab->vab',Wm,SK[Jm]); Pr=np.einsum('vab,vb->va',MB,np.c_[P,np.ones(nv)])[:,:3]; Nr=np.einsum('vab,vb->va',MB[:,:3,:3],N)   # espaco de repouso
unit=lambda v: v/np.linalg.norm(v)
def rotm(a,th): a=unit(a); K=np.array([[0,-a[2],a[1]],[a[2],0,-a[0]],[-a[1],a[0],0]]); return np.eye(3)+np.sin(th)*K+(1-np.cos(th))*K@K
nv0=int(os.environ.get('NV0','24411')); up=np.array([0,1,0.]); ang={}
for sd in 'rl':
    hj=J_('hand_'+sd); wrist=Wr[jn[hj]][:3,3]; elbow=Wr[jn[J_('lowerarm_'+sd)]][:3,3]; L=np.linalg.norm(wrist-elbow); f=unit(wrist-elbow)
    arm={J_(x+sd) for x in ()}; arm=set(i for i,nm in enumerate(names) if nm.endswith('_'+sd) and any(k in nm for k in ('lowerarm','hand','thumb','index','middle','ring','pinky')))
    wa=np.zeros(nv)
    for k in range(4): wa+=np.where(np.isin(Jm[:,k],list(arm)),Wm[:,k],0)
    s=(Pr-elbow)@f; t=np.clip(s/L,0,1); sm=t*t*(3-2*t); frac=wa*sm; frac[nv0:]=np.where(Jm[nv0:,0]==hj,1.0,0.0)   # arma: 100% na mao a que pertence
    wpn=np.where(Jm[:,0]==hj,1,0)*(np.arange(nv)>=nv0)
    X=Pr[wpn>0]; c=X.mean(0); u_,s_,vt=np.linalg.svd(X-c,full_matrices=False); ax=vt[0]
    proj=(X-c)@ax; hd=np.abs(proj.max())>np.abs(proj.min()); sp=lambda sel: np.linalg.norm(np.cross(X[sel]-c,ax),axis=1).mean()
    ax=ax if sp(proj>0)>sp(proj<0) else -ax                           # cabo -> cabeca (a cabeca tem mais volume)
    Mi=Wi[jn[hj]]@np.linalg.inv(Wr[jn[hj]]); fwd=unit(np.cross(Wi[jn[J_('hand_l')]][:3,3]-Wi[jn[J_('hand_r')]][:3,3],up)); 
    face=unit(Wi[jn[J_('head')]][:3,3]-Wi[jn[J_('spine_01')]][:3,3])
    best=None
    for deg in np.arange(-180,180,1.0):
        a=Mi[:3,:3]@rotm(f,np.radians(deg))@ax; cosv=a@up; err=np.degrees(np.arccos(np.clip(cosv,-1,1)))
        if best is None or err<best[0]: best=(err,deg,a)
    ang[sd]=best[1]; print(sd,'giro do punho',best[1],'graus -> eixo a',round(best[0],1),'graus da vertical')
    th=np.radians(best[1])*frac; 
    for v in np.where(frac>1e-4)[0]:
        Rv=rotm(f,th[v]); Pr[v]=wrist+Rv@(Pr[v]-wrist); Nr[v]=Rv@Nr[v]
inv=np.linalg.inv(MB); P2=np.einsum('vab,vb->va',inv,np.c_[Pr,np.ones(nv)])[:,:3]; N2=np.einsum('vab,vb->va',inv[:,:3,:3],Nr); N2/=np.maximum(np.linalg.norm(N2,axis=1,keepdims=True),1e-9)
lo=P2.min(0); hi=P2.max(0); c2=(lo+hi)/2; e2=(hi-lo)/2*1.001
R0['p'][:,:3]=np.clip(np.round((P2-c2)/e2*32767),-32767,32767); R0['n'][:,:3]=np.clip(np.round(N2*32767),-32767,32767)
os.makedirs(OUT,exist_ok=True)
with open(OUT+'char.msh','wb') as fo: fo.write(m[:4]+struct.pack('<3i',nv,ni,st)[0:0]+m[4:16]); fo.write(struct.pack('<3f',*c2)); fo.write(struct.pack('<3f',*e2)); fo.write(R0.tobytes()); fo.write(tri)
print('ok')
