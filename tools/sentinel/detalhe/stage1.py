import sys,struct;sys.path.insert(0,'/home/claude/b')
import numpy as np
from scipy.spatial import cKDTree
from collections import defaultdict
raw=open('out100k.bin','rb').read();nv,nt=struct.unpack('<ii',raw[:8]);o=8
A=np.frombuffer(raw,'<f8',nv*8,o).reshape(nv,8);o+=nv*64
I=np.frombuffer(raw,'<i4',nt*3,o).reshape(nt,3).copy();o+=nt*12;src=np.frombuffer(raw,'<i4',nv,o)
Pp=A[:,0:3].copy();UV=A[:,3:5].copy();Nn=A[:,5:8].copy()
print('verts',nv,'tris',nt)
L=np.load('/home/claude/w/lowapp.npz');LJ=L['j'][:24411].astype(np.int64);LW=L['w'][:24411].astype(np.float64)/255
lp=np.load('/home/claude/b/Qlp.npy');lidx=L['idx'][:18961].astype(np.int64)
fn=np.cross(lp[lidx[:,1]]-lp[lidx[:,0]],lp[lidx[:,2]]-lp[lidx[:,0]]);fn/=np.linalg.norm(fn,axis=1,keepdims=True)+1e-12
usedv=np.unique(lidx);tree=cKDTree(lp[usedv]);inc=defaultdict(list)
for ti,t in enumerate(lidx):
    for v in t:inc[v].append(ti)
MAXI=48
def closest(p,a,b,c):
    ab=b-a;ac=c-a;ap=p-a
    d1=(ab*ap).sum(-1);d2=(ac*ap).sum(-1);bp=p-b;d3=(ab*bp).sum(-1);d4=(ac*bp).sum(-1);cp=p-c;d5=(ab*cp).sum(-1);d6=(ac*cp).sum(-1)
    vc=d1*d4-d3*d2;vb=d5*d2-d1*d6;va=d3*d6-d5*d4
    den=1/np.where(np.abs(va+vb+vc)<1e-30,1e-30,va+vb+vc);v=vb*den;w=vc*den;u=1-v-w
    outside=(u<0)|(v<0)|(w<0)
    def seg(p0,e):return np.clip(((p-p0)*e).sum(-1)/np.maximum((e*e).sum(-1),1e-30),0,1)
    cands=[]
    for (p0,p1,ia,ib) in ((a,b,0,1),(b,c,1,2),(c,a,2,0)):
        t=seg(p0,p1-p0);q=p0+(p1-p0)*t[:,None];d=((p-q)**2).sum(-1);bar=np.zeros((len(p),3));bar[:,ia]=1-t;bar[:,ib]=t;cands.append((d,bar))
    dI=((p-(u[:,None]*a+v[:,None]*b+w[:,None]*c))**2).sum(-1);D_=np.where(outside,np.inf,dI);B_=np.stack([u,v,w],1)
    for d,bar in cands:
        m=d<D_;D_=np.where(m,d,D_);B_=np.where(m[:,None],bar,B_)
    return D_,B_
outJ=np.zeros((nv,4),np.int64);outW=np.zeros((nv,4))
CH=8000
for s0 in range(0,nv,CH):
    q=Pp[s0:s0+CH];hn=Nn[s0:s0+CH];n=len(q)
    _,nn=tree.query(q,k=8);cand=np.full((n,MAXI),-1,np.int64)
    for i in range(n):
        l=[]
        for v in usedv[nn[i]]:l.extend(inc[v])
        l=list(dict.fromkeys(l))[:MAXI];cand[i,:len(l)]=l
    valid=cand>=0;ct=np.where(valid,cand,0)
    a=lp[lidx[ct,0]];b=lp[lidx[ct,1]];c=lp[lidx[ct,2]];pp=np.repeat(q[:,None,:],MAXI,1).reshape(-1,3)
    d,bar=closest(pp,a.reshape(-1,3),b.reshape(-1,3),c.reshape(-1,3));d=d.reshape(n,MAXI);bar=bar.reshape(n,MAXI,3)
    ndot=(fn[ct]*hn[:,None,:]).sum(-1);score=np.sqrt(np.maximum(d,0))+0.015*(1-np.maximum(ndot,-1));score=np.where(valid,score,np.inf)
    bi=score.argmin(1);ar=np.arange(n);tri=ct[ar,bi];bb=bar[ar,bi];vs=lidx[tri]
    J12=np.concatenate([LJ[vs[:,k]] for k in range(3)],1);W12=np.concatenate([LW[vs[:,k]]*bb[:,k:k+1] for k in range(3)],1)
    for i in range(12):
        for j in range(i+1,12):
            m=(J12[:,i]==J12[:,j])&(W12[:,j]>0)&(W12[:,i]>0);W12[:,i]=np.where(m,W12[:,i]+W12[:,j],W12[:,i]);W12[:,j]=np.where(m,0,W12[:,j])
    o_=np.argsort(-W12,1)[:,:4];J4=np.take_along_axis(J12,o_,1);W4=np.take_along_axis(W12,o_,1);W4/=np.maximum(W4.sum(1,keepdims=True),1e-9);J4=np.where(W4>0,J4,0)
    outJ[s0:s0+n]=J4;outW[s0:s0+n]=W4
key=np.round(Pp*1e5).astype(np.int64);_,first,inv=np.unique(key,axis=0,return_index=True,return_inverse=True);inv=inv.reshape(-1)
rep=first[inv];nd=(rep!=np.arange(nv)).sum();outJ=outJ[rep];outW=outW[rep];print('vertices de costura igualados',nd)
print('weights done',outW.sum(1).min(),outJ.max())
from handfix import curl,roll
P1,N1=curl(Pp,Nn,outJ,outW)
P2,N2=roll(P1,N1,outJ,outW,{'r':56.0,'l':-41.0})
print('hand motion max',np.linalg.norm(P2-Pp,axis=1).max())
np.savez('body.npz',P=P2,N=N2,UV=UV,I=I,J=outJ,W=outW,Pbind=Pp,src=src)
