import sys; sys.path.insert(0,'/tmp/claude-0/-home-user-skater-parkour/19329d18-9da6-5d89-afba-e925a710541c/scratchpad/work')
import numpy as np
from retarget import *
from qr import *
def target_world(T,Q,PEL,f,extra_local=None):
    n=len(T.names); W=np.zeros((n,4,4)); dr={k:j for j,k in enumerate(T.driven)}
    for i,k in enumerate(T.names):
        p=T.par[i]
        if k in dr: R=qm(Q[f,dr[k]])
        else: R=T.restL[i]
        if extra_local and k in extra_local: R=R@extra_local[k]
        t=PEL[f] if k=='pelvis' else T.rest_t[i]
        L=np.eye(4); L[:3,:3]=R; L[:3,3]=t
        W[i]=(T.wpre if p<0 else W[p])@L
    return W
def skin_points(tp,T,W,Wb=None):
    Wb=tp['Wb'] if Wb is None else Wb
    S=np.einsum('nij,njk->nik',W,np.linalg.inv(Wb))
    Q=tp['Q']; J=tp['J']; Wt=tp['Wt'].astype(float)
    out=np.zeros_like(Q)
    for c in range(4):
        m=S[J[:,c]]; out+=Wt[:,c:c+1]*(np.einsum('nij,nj->ni',m[:,:3,:3],Q)+m[:,:3,3])
    return out
