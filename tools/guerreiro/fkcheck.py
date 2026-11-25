import numpy as np, struct, sys
from scipy.spatial.transform import Rotation as Rot
def loadrig(path):
    b=open(path,'rb').read(); o=0
    def I(n):
        nonlocal o; a=np.frombuffer(b,'<i4',n,o).copy(); o+=4*n; return a
    def F(n):
        nonlocal o; a=np.frombuffer(b,'<f4',n,o).copy(); o+=4*n; return a
    nN,nJ,pel,nb=I(4); par=I(nN); T=F(nN*3).reshape(-1,3); Q=F(nN*4).reshape(-1,4); order=I(nN)
    return dict(nN=nN,pel=pel,par=par,T=T,Q=Q,order=order,jn=I(nJ),ibm=F(nJ*16).reshape(-1,16),bn=I(nb))
def loadanim(path):
    ab=open(path,'rb').read(); p=0; n=struct.unpack_from('<i',ab,p)[0]; p+=4; clips={}
    rec=np.dtype([('q','<i2',(22,4)),('pel','<f4',3),('root','<f4',3)])
    for _ in range(n):
        l=struct.unpack_from('<H',ab,p)[0]; p+=2; nm=ab[p:p+l].decode(); p+=l
        nf=struct.unpack_from('<i',ab,p)[0]; p+=4; clips[nm]=np.frombuffer(ab,rec,nf,p); p+=nf*rec.itemsize
    return clips
def fk(rig,c):  # c: record array [nf]; devolve posições de mundo dos 22 ossos [nf,22,3]
    nf=len(c); bone={rig['bn'][j]:j for j in range(22)}; WR={}; WP={}
    for k in rig['order']:
        if k in bone: R=Rot.from_quat(c['q'][:,bone[k]]/32767.).as_matrix()
        else: R=np.broadcast_to(Rot.from_quat(rig['Q'][k]).as_matrix(),(nf,3,3))
        t=c['pel'] if k==rig['pel'] else np.broadcast_to(rig['T'][k],(nf,3))
        p=rig['par'][k]
        if p<0: WR[k]=R; WP[k]=t
        else: WR[k]=WR[p]@R; WP[k]=WP[p]+np.einsum('nij,nj->ni',WR[p],t)
    return np.stack([WP[rig['bn'][j]] for j in range(22)],1)
if __name__=='__main__':
    A='/home/claude/work/codigo/app/src/main/assets/w/'
    ro=loadrig(A+'rig.bin'); co=loadanim(A+'anim.bin'); rn=loadrig('hero/out/rig.bin'); cn=loadanim('hero/out/anim.bin')
    print('clip | min y ossos pé/bola (antigo)  (novo) | y pelve média (antigo)(novo)')
    for nm in ['m:Stand_Relaxed_Idle','m:Run_F','m:Crouch_Idle','m:Walk_F','p:Balance_Idle','p:Hanging_Idle','m:Run_F_Jump','p:Landing_Roll_A_L']:
        if nm not in co: continue
        a=fk(ro,co[nm]); b=fk(rn,cn[nm])
        f=[16,17,20,21]
        print(f'{nm:24s} {a[:,f,1].min():7.3f} {b[:,f,1].min():7.3f} | pelve {a[:,0,1].mean():.3f} {b[:,0,1].mean():.3f} | pé médio {a[:,f,1].mean():.3f} {b[:,f,1].mean():.3f}')
