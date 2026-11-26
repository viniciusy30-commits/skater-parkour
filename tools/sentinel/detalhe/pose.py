import sys,struct;sys.path.insert(0,'/home/claude/b')
import numpy as np
from scipy.spatial.transform import Rotation as Rot
b=open('/home/claude/b/rig.bin','rb').read();o=0
def rdI(n):
    global o;a=np.frombuffer(b,'<i4',n,o).copy();o+=4*n;return a
def rdF(n):
    global o;a=np.frombuffer(b,'<f4',n,o).copy();o+=4*n;return a
nN,nJ,pelN,nb=rdI(4);par=rdI(nN);T=rdF(nN*3).reshape(-1,3);Q=rdF(nN*4).reshape(-1,4);order=rdI(nN);jn=rdI(nJ);ibm=rdF(nJ*16).reshape(nJ,4,4);bn=rdI(nb)
def world(q=None,pel=None):
    q0=Q.copy();t0=T.copy()
    if q is not None:
        for k in range(nb):q0[bn[k]]=q[k]/np.linalg.norm(q[k])
        t0[pelN]=pel
    Wd=[None]*nN
    for n in order:
        M=np.eye(4);M[:3,:3]=Rot.from_quat(q0[n]/np.linalg.norm(q0[n])).as_matrix();M[:3,3]=t0[n];Wd[n]=M if par[n]<0 else Wd[par[n]]@M
    return Wd
d=open('/home/claude/b/anim.bin','rb').read();n=struct.unpack_from('<i',d,0)[0];p=4;clips={}
for i in range(n):
    l=struct.unpack_from('<H',d,p)[0];p+=2;nm=d[p:p+l].decode();p+=l;nf=struct.unpack_from('<i',d,p)[0];p+=4
    dt=np.dtype([('q','<i2',nb*4),('pel','<f4',3),('root','<f4',3)]);R=np.frombuffer(d,dt,nf,p);p+=nf*dt.itemsize;clips[nm]=(nf,R)
import re
print([k for k in clips if re.search('attack|run|jump|skill',k)][:25])
def SKpose(name,fr):
    nf,R=clips[name];f=min(fr,nf-1);Wi=world(R['q'][f].reshape(nb,4)/32767.0,R['pel'][f].copy())
    return np.array([Wi[jn[j]]@ibm[j].T for j in range(nJ)])
if __name__=='__main__':
    for nm,fr in (('c:attack_01',12),('c:move_run_f',6)):
        if nm in clips: np.save('sk_'+nm.replace(':','_')+'.npy',SKpose(nm,fr));print('ok',nm,clips[nm][0])
