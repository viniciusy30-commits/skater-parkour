import numpy as np, struct
from scipy.spatial.transform import Rotation as Rot
ASSET='/home/claude/p/codigo/app/src/main/assets/w/'
b=open(ASSET+'rig.bin','rb').read(); o=0
def rdI(n):
    global o; a=np.frombuffer(b,dtype='<i4',count=n,offset=o).copy(); o+=4*n; return a
def rdF(n):
    global o; a=np.frombuffer(b,dtype='<f4',count=n,offset=o).copy(); o+=4*n; return a
nN,nJ,opel,nb=rdI(4)
opar=rdI(nN); oT=rdF(nN*3).reshape(-1,3); oQ=rdF(nN*4).reshape(-1,4); oord=rdI(nN); ojn=rdI(nJ); oibm=rdF(nJ*16); obn=rdI(nb)
def qm(q): return Rot.from_quat(q/np.linalg.norm(q)).as_matrix()
oWR=[None]*nN; oWP=[None]*nN
for n in oord:
    R=qm(oQ[n]); t=oT[n]
    if opar[n]<0: oWR[n]=R; oWP[n]=t.copy()
    else: oWR[n]=oWR[opar[n]]@R; oWP[n]=oWP[opar[n]]+oWR[opar[n]]@t
