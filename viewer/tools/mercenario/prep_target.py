import sys,glob,pickle,numpy as np
sys.path.insert(0,'/tmp/claude-0/-home-user-skater-parkour/19329d18-9da6-5d89-afba-e925a710541c/scratchpad/work')
from glb import *; from fkutil import mq
import scipy.sparse as sp
from scipy.spatial import cKDTree
U=glob.glob('/root/.claude/uploads/*/')[0]
rg=GLB(glob.glob(U+'2a2b7a8d*.glb')[0]); hq=GLB(glob.glob(U+'f9a601ca*.glb')[0])
j=rg.j; W,par=world_mats(j); nodes=j['nodes']; sk=j['skins'][0]['joints']
names=[nodes[i]['name'] for i in sk]
nidx={k:i for i,k in enumerate(sk)}
parent=[nidx.get(par.get(k,-1),-1) for k in sk]
# local rest TRS
loc=[node_local(nodes[k]) for k in sk]
rest=[]
for k,L in zip(sk,loc):
    R=L[:3,:3]; s=np.linalg.norm(R,axis=0); assert np.allclose(s,1,atol=1e-3),(nodes[k]['name'],s)
    rest.append((mq(R),L[:3,3].copy()))
# wpre = world matrix of root's parent
root_node=sk[0]; wpre=W[par[root_node]]
Wb=np.stack([W[k] for k in sk])   # world bind matrices
# rigged mesh world pos & weights
pr=j['meshes'][0]['primitives'][0]['attributes']
P=rg.acc(pr['POSITION']); P=(np.c_[P,np.ones(len(P))]@W[3].T)[:,:3]
J=rg.acc(pr['JOINTS_0']).astype(int); Wt=rg.acc(pr['WEIGHTS_0']).astype(float)
Wt/=np.maximum(Wt.sum(1,keepdims=True),1e-9)
Np=len(P); Wsp=sp.csr_matrix((Wt.ravel(),(np.repeat(np.arange(Np),4),J.ravel())),shape=(Np,len(sk)))
# hq
ph=hq.j['meshes'][0]['primitives'][0]; at=ph['attributes']
Q=hq.acc(at['POSITION']).astype(float); N=hq.acc(at['NORMAL']); UV=hq.acc(at['TEXCOORD_0']); idx=hq.acc(ph['indices']).ravel().astype(np.uint32)
tree=cKDTree(P); K=8
d,i=tree.query(Q,k=K)
sig=np.maximum(d[:,:1]*1.2,0.003)
w=np.exp(-(d/sig)**2)+1e-12
w/=w.sum(1,keepdims=True)
A=sp.csr_matrix((w.ravel(),(np.repeat(np.arange(len(Q)),K),i.ravel())),shape=(len(Q),Np))
WH=(A@Wsp).tocsr().toarray().astype(np.float32)
nB=len(sk)
ixn={n:i for i,n in enumerate(names)}
head_raw=WH[:,ixn['head']].copy()
# ---- 1) suavizacao dos pesos sobre a malha (vertices soldados por posicao: costuras de UV ficam iguais)
key=np.round(Q*1e5).astype(np.int64); _,inv=np.unique(key,axis=0,return_inverse=True); inv=inv.ravel(); G=inv.max()+1
tri3=idx.reshape(-1,3).astype(np.int64)
ea=np.r_[inv[tri3[:,0]],inv[tri3[:,1]],inv[tri3[:,2]]]; eb=np.r_[inv[tri3[:,1]],inv[tri3[:,2]],inv[tri3[:,0]]]
Adj=sp.coo_matrix((np.ones(len(ea)),(ea,eb)),shape=(G,G)).tocsr(); Adj=((Adj+Adj.T)>0).astype(np.float32); deg=np.maximum(np.asarray(Adj.sum(1)).ravel(),1)
cnt=np.bincount(inv,minlength=G).astype(np.float32); WG=np.zeros((G,nB),np.float32); np.add.at(WG,inv,WH); WG/=cnt[:,None]
for it in range(3): WG=0.5*WG+0.5*(Adj@WG)/deg[:,None]
WH=WG[inv]
def P_(n): return Wb[ixn[n]][:3,3]
def segd(p,a,b):
    ab=b-a; t=np.clip(((p-a)@ab)/(ab@ab),0,1); return np.linalg.norm(p-(a+t[:,None]*ab),axis=1)
dd=np.minimum.reduce([segd(Q,P_('thigh_'+s),P_('calf_'+s)) for s in 'lr']+[segd(Q,P_('calf_'+s),P_('foot_'+s)) for s in 'lr']+[segd(Q,P_('foot_'+s),P_('ball_'+s)) for s in 'lr'])
ss=lambda a,b,x: (lambda t: t*t*(3-2*t))(np.clip((x-a)/(b-a),0,1))
y=Q[:,1]
# ---- 2) sobretudo: so o CENTRO (entre as coxas) troca o corte seco esquerda/direita por transicao suave e prende parte a pelvis.
#         Calca e laterais do sobretudo continuam com os pesos das pernas.
iL,iR,iP=ixn['thigh_l'],ixn['thigh_r'],ixn['pelvis']
Ts=WH[:,iL]+WH[:,iR]
mc=ss(0.075,0.1,dd)*(1-ss(0.05,0.11,np.abs(Q[:,0]-0.01)))*ss(0.54,0.46,y)*(y>0.17)*(Ts>0.2)
xs=ss(-0.08,0.08,Q[:,0]-0.01)
Wm=WH.copy(); Wm[:,iL]=Ts*xs; Wm[:,iR]=Ts*(1-xs)      # mantem o total das coxas e o peso da pelvis: so mistura esquerda/direita
WH=(1-mc[:,None])*WH+mc[:,None]*Wm
print('vertices do centro do sobretudo ajustados:',int((mc>.3).sum()))
# ---- 2b) camadas: cada vertice do sobretudo herda o movimento da camada logo ABAIXO dele (calça/forro),
#          assim as camadas se movem juntas e uma nao fura a outra.
from scipy.spatial import cKDTree as _KD
Nrm=tp_N=N/np.maximum(np.linalg.norm(N,axis=1,keepdims=True),1e-9)
first=np.zeros(G,int); first[inv]=np.arange(len(Q))
Pu=Q[first]; Nu=Nrm[first]; Wu=WH[first].copy()
treeu=_KD(Pu); low=np.where(Pu[:,1]<0.58)[0]
newW=Wu.copy(); n_ch=0
cand=treeu.query_ball_point(Pu[low]-Nu[low]*0.014,0.016)
for k,i in enumerate(low):
    c=np.array(cand[k],int)
    if len(c)==0: continue
    dv=Pu[i]-Pu[c]; depth=dv@Nu[i]; perp=np.linalg.norm(dv-depth[:,None]*Nu[i],axis=1)
    ok=(depth>0.003)&(depth<0.03)&(perp<0.016)&((Nu[c]@Nu[i])>0.3)
    if ok.sum()==0: continue
    c=c[ok]; w=1/(0.004+perp[ok]); avg=(Wu[c]*w[:,None]).sum(0)/w.sum()
    newW[i]=0.85*avg+0.15*Wu[i]; n_ch+=1
print('vertices de sobretudo/forro herdando a camada de baixo:',n_ch)
WH=newW[inv]
# ---- 2c) camadas pareadas: o sobretudo tem camada externa e forro a poucos mm; cada par (normais opostas) usa o MESMO peso,
#          assim uma camada nunca vaza pela outra quando o corpo se mexe.
Wu=WH[first].copy(); Pu=Q[first]; Nu=Nrm[first]; treeu=_KD(Pu)
sel2=np.where(Pu[:,1]<0.95)[0]; nb=treeu.query_ball_point(Pu[sel2],0.012)
pi=[];pj=[];pw=[]
for k,i in enumerate(sel2):
    c=np.array(nb[k],int)
    c=c[(Nu[c]@Nu[i])<-0.25]
    if len(c): pi+= [i]*len(c); pj+=list(c)
pi=np.array(pi);pj=np.array(pj)
print('pares de camadas opostas:',len(pi))
Wp=sp.coo_matrix((np.ones(len(pi),np.float32),(pi,pj)),shape=(G,G)).tocsr()
degp=np.asarray(Wp.sum(1)).ravel()
for it in range(4):
    avg=(Wp@Wu)/np.maximum(degp,1)[:,None]
    has=(degp>0)[:,None]
    Wu=np.where(has,0.5*Wu+0.5*avg,Wu)
WH=Wu[inv]
# ---- 3) cabeca rigida: cranio, rosto, maxilar e QUEIXO 100% no osso da cabeca (corte logo abaixo do queixo, y~0.853)
x,z=Q[:,0],Q[:,2]
hm=(head_raw>=0.45)|((y>0.853)&(z>0.042)&(np.abs(x)<0.095)&(y<1.01))|(y>0.905)
hm&=(y>0.84)
WH[hm]=0; WH[hm,ixn['head']]=1
print('vertices 100% cabeca:',int(hm.sum()))
top=np.argsort(-WH,1)[:,:4]; tw=np.take_along_axis(WH,top,1); tw/=np.maximum(tw.sum(1,keepdims=True),1e-9)
top=np.argsort(-WH,1)[:,:4]; tw=np.take_along_axis(WH,top,1); tw/=np.maximum(tw.sum(1,keepdims=True),1e-9)
print('weights done; verts',len(Q),'mean max-weight',tw[:,0].mean())
pickle.dump(dict(names=names,parent=parent,rest=rest,wpre=wpre,Wb=Wb,Q=Q,N=N,UV=UV,idx=idx,J=top.astype(np.uint8),Wt=tw,tex=hq.image(0)),open('work/target.pkl','wb'))
print(len(names),'bones; pelvis y',Wb[1][:3,3],'wpre',np.round(wpre,3).tolist())
