import json,struct,numpy as np
from scipy.spatial.transform import Rotation as Rot
ASSET='/home/claude/p/codigo/app/src/main/assets/w/'
def qm(q): return Rot.from_quat(np.asarray(q,float)/np.linalg.norm(q)).as_matrix()
g=json.load(open('/home/claude/build/m_g.json'))
onames=g['bones']['names']; opar=g['bones']['parent']; orest=np.array(g['bones']['rest'])
oQ=orest[:,:4]; oT=orest[:,4:7]; oN=len(onames)
drv=g['driven']
def fk(par,Q,T):
    N=len(par); WR=[None]*N; WP=[None]*N
    for k in range(N):
        R=qm(Q[k])
        if par[k]<0: WR[k]=R; WP[k]=np.array(T[k],float)
        else: WR[k]=WR[par[k]]@R; WP[k]=WP[par[k]]+WR[par[k]]@T[k]
    return WR,WP
b=open(ASSET+'rig.bin','rb').read();o=16
nN,nJ,npel,nb=struct.unpack_from('<4i',b,0)
npar=list(np.frombuffer(b,'<i4',nN,o));o+=4*nN
nT=np.frombuffer(b,'<f4',nN*3,o).reshape(-1,3).astype(float);o+=12*nN
nQc=np.frombuffer(b,'<f4',nN*4,o).reshape(-1,4).astype(float);o+=16*nN
nord=np.frombuffer(b,'<i4',nN,o);o+=4*nN
njn=np.frombuffer(b,'<i4',nJ,o);o+=4*nJ
nibm=np.frombuffer(b,'<f4',nJ*16,o).reshape(-1,16).astype(float);o+=64*nJ
nbn=list(np.frombuffer(b,'<i4',nb,o))
