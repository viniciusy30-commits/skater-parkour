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
# ---- 1) suavizacao dos pesos sobre a malha (vertices soldados por posicao: costuras de UV ficam iguais)
key=np.round(Q*1e5).astype(np.int64); _,inv=np.unique(key,axis=0,return_inverse=True); inv=inv.ravel(); G=inv.max()+1
tri3=idx.reshape(-1,3).astype(np.int64)
ea=np.r_[inv[tri3[:,0]],inv[tri3[:,1]],inv[tri3[:,2]]]; eb=np.r_[inv[tri3[:,1]],inv[tri3[:,2]],inv[tri3[:,0]]]
Adj=sp.coo_matrix((np.ones(len(ea)),(ea,eb)),shape=(G,G)).tocsr(); Adj=((Adj+Adj.T)>0).astype(np.float32); deg=np.maximum(np.asarray(Adj.sum(1)).ravel(),1)
cnt=np.bincount(inv,minlength=G).astype(np.float32); WG=np.zeros((G,nB),np.float32); np.add.at(WG,inv,WH); WG/=cnt[:,None]
for it in range(3): WG=0.5*WG+0.5*(Adj@WG)/deg[:,None]
WH=WG[inv]
# ---- 2) sobretudo: peso suave esquerda/direita (sem corte seco no meio), preso principalmente a pelvis
ixn={n:i for i,n in enumerate(names)}
def P_(n): return Wb[ixn[n]][:3,3]
def segd(p,a,b):
    ab=b-a; t=np.clip(((p-a)@ab)/(ab@ab),0,1); return np.linalg.norm(p-(a+t[:,None]*ab),axis=1)
dd=np.minimum.reduce([segd(Q,P_('thigh_'+s),P_('calf_'+s)) for s in 'lr']+[segd(Q,P_('calf_'+s),P_('foot_'+s)) for s in 'lr']+[segd(Q,P_('foot_'+s),P_('ball_'+s)) for s in 'lr'])
ss=lambda a,b,x: (lambda t: t*t*(3-2*t))(np.clip((x-a)/(b-a),0,1))
y=Q[:,1]
kc=ss(0.068,0.098,dd)*ss(0.56,0.46,y)*(y>0.17)
t=np.clip((0.57-y)/0.34,0,1); wT=0.68*ss(0,1,t)
xs=ss(-0.075,0.075,Q[:,0]-0.01)          # 1 = lado esquerdo (+x)
Wc=np.zeros_like(WH); Wc[:,ixn['pelvis']]=1-wT; Wc[:,ixn['thigh_l']]=wT*xs; Wc[:,ixn['thigh_r']]=wT*(1-xs)
WH=(1-kc[:,None])*WH+kc[:,None]*Wc
print('vertices de sobretudo reponderados:',int((kc>.5).sum()))
top=np.argsort(-WH,1)[:,:4]; tw=np.take_along_axis(WH,top,1); tw/=np.maximum(tw.sum(1,keepdims=True),1e-9)
print('weights done; verts',len(Q),'mean max-weight',tw[:,0].mean())
pickle.dump(dict(names=names,parent=parent,rest=rest,wpre=wpre,Wb=Wb,Q=Q,N=N,UV=UV,idx=idx,J=top.astype(np.uint8),Wt=tw,tex=hq.image(0)),open('work/target.pkl','wb'))
print(len(names),'bones; pelvis y',Wb[1][:3,3],'wpre',np.round(wpre,3).tolist())
