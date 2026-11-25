import numpy as np, pickle, sys
from scipy.spatial.transform import Rotation as Rot
R=pickle.load(open('hero/out/ret.pkl','rb')); A=np.load('hero/out/arrays.npy',allow_pickle=True).item()
out=R['out']; newNode=R['newNode']; npar=R['npar']; nLt=R['nLt']; curlLq=R['curlLq']; ibm=R['ibm']
P,C,I,J,W=A['P'],A['C'],A['I'],A['J'],A['W'].astype(np.float64)/255
names={c[0]:i for i,c in enumerate(out)}
def pose(clip,frame):
    nm,nf,oq,pl,root=out[names[clip]]; f=min(frame,nf-1)
    Wm=[None]*80; Lq=curlLq.copy()
    for i,k in enumerate(newNode): Lq[k]=oq[f,i]
    for k in range(80):
        m=np.eye(4); m[:3,:3]=Rot.from_quat(Lq[k]/np.linalg.norm(Lq[k])).as_matrix(); m[:3,3]=nLt[k] if k!=newNode[0] else pl[f]
        Wm[k]=m if npar[k]<0 else Wm[npar[k]]@m
    SK=np.stack([Wm[k]@ibm[k].reshape(4,4).T for k in range(80)])
    Mx=np.zeros((len(P),4,4))
    for c in range(4): Mx+=W[:,c,None,None]*SK[J[:,c]]
    Ph=np.concatenate([P,np.ones((len(P),1))],1)
    return np.einsum('nij,nj->ni',Mx,Ph)[:,:3]
def dump(fn,PP):
    with open(fn,'wb') as o:
        np.array([len(PP),len(I)],'<i4').tofile(o); PP.astype('<f4').tofile(o); C.astype('<f4').tofile(o); I.astype('<i4').tofile(o)
if __name__=='__main__':
    clip=sys.argv[1]; fr=int(sys.argv[2]); fn=sys.argv[3]
    PP=pose(clip,fr); dump(fn,PP); print(clip,fr,PP.min(0).round(2),PP.max(0).round(2))
