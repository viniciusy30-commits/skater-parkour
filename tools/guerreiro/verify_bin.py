import numpy as np, struct, sys
from scipy.spatial.transform import Rotation as Rot
D='hero/out/'
# --- rig.bin como o RigData.kt lê
b=open(D+'rig.bin','rb').read(); o=0
def I(n):
    global o; a=np.frombuffer(b,'<i4',n,o).copy(); o+=4*n; return a
def F(n):
    global o; a=np.frombuffer(b,'<f4',n,o).copy(); o+=4*n; return a
nN,nJ,pel,nb=I(4); par=I(nN); T=F(nN*3).reshape(-1,3); Q=F(nN*4).reshape(-1,4); order=I(nN); jn=I(nJ); ibm=F(nJ*16).reshape(-1,16); bn=I(nb)
assert o==len(b), (o,len(b)); assert nb==22
assert all(par[k]<k for k in range(nN) if par[k]>=0), 'ordem de pais'
print('rig ok',nN,nJ,pel,nb)
# --- anim.bin como o ClipSet.kt lê
ab=open(D+'anim.bin','rb').read(); p=0
n=struct.unpack_from('<i',ab,p)[0]; p+=4; clips={}
rec=np.dtype([('q','<i2',(22,4)),('pel','<f4',3),('root','<f4',3)])
for _ in range(n):
    l=struct.unpack_from('<H',ab,p)[0]; p+=2; nm=ab[p:p+l].decode(); p+=l
    nf=struct.unpack_from('<i',ab,p)[0]; p+=4
    clips[nm]=np.frombuffer(ab,rec,nf,p); p+=nf*rec.itemsize
assert p==len(ab); print('anim ok',n)
# --- char.msh como o WMesh.kt lê
mb=open(D+'char.msh','rb').read(); _,nv,ni,stride=struct.unpack_from('<4i',mb,0); ctr=np.frombuffer(mb,'<f4',3,16); ext=np.frombuffer(mb,'<f4',3,28)
assert stride==28 and len(mb)==40+nv*28+ni*4
vt=np.frombuffer(mb,np.dtype([('p','<i2',4),('n','<i2',4),('c','u1',4),('j','u1',4),('w','u1',4)]),nv,40)
pos=ctr+np.maximum(vt['p'][:,:3]/32767.,-1)*ext
nrm=vt['n'][:,:3]/32767.; print('normais |n| médio',np.linalg.norm(nrm,axis=1).mean())
def solve(clip,frame):
    c=clips[clip][frame]; Wm=[None]*nN; bone={bn[j]:j for j in range(22)}
    for k in order:
        q=Q[k] if k not in bone else c['q'][bone[k]]/32767.
        t=T[k] if k!=pel else c['pel']
        m=np.eye(4); m[:3,:3]=Rot.from_quat(q/np.linalg.norm(q)).as_matrix(); m[:3,3]=t
        Wm[k]=m if par[k]<0 else Wm[par[k]]@m
    return np.stack([Wm[jn[j]]@ibm[j].reshape(4,4).T for j in range(nJ)])
for clip,fr in [('m:Run_F',8),('m:Stand_Relaxed_Idle',0),('p:Hanging_Idle',10),('p:Landing_Roll_A_L',15)]:
    SK=solve(clip,fr); w=vt['w']/255.; j=vt['j']
    Mx=sum(w[:,c,None,None]*SK[j[:,c]] for c in range(4))
    out=np.einsum('nij,nj->ni',Mx,np.concatenate([pos,np.ones((nv,1))],1))[:,:3]
    print(clip,fr,'bbox',out.min(0).round(2),out.max(0).round(2),'| pés y min',out[:,1].min().round(3))
