import numpy as np, struct, json
from common import *
d=open('/home/claude/build/b_g.dec','rb').read()
MAP=['pelvis','spine_01','spine_02','spine_03','neck_01','head','clavicle_l','upperarm_l','lowerarm_l','hand_l','clavicle_r','upperarm_r','lowerarm_r','hand_r','thigh_l','calf_l','foot_l','ball_l','thigh_r','calf_r','foot_r','ball_r']
oidx=[onames.index(n) for n in MAP]   # mesmos índices no novo (verificado)
assert oidx==nbn
W=Rot.from_euler('y',-90,degrees=True).as_matrix()
oWR,oWP=fk(opar,oQ,oT)
nWRc,nWPc=fk(npar,nQc,nT)
# repouso sem dedos curvados (a partir do ibm)
nWR0=[];nWP0=[]
for k in range(nN):
    m=np.linalg.inv(nibm[k].reshape(4,4).T); nWR0.append(m[:3,:3]); nWP0.append(m[:3,3])
CH={0:1,1:2,2:3,3:4,4:5,5:None,6:7,7:8,8:9,9:'kids',10:11,11:12,12:13,13:'kids',14:15,15:16,16:17,17:None,18:19,19:20,20:21,21:None}
def kids(P,par,node):
    c=[i for i in range(len(par)) if par[i]==node]; return np.mean([P[i] for i in c],0) if c else None
unit=lambda v:v/np.linalg.norm(v)
dold=[];sold=[];dnew=[];snew=[]
for bi in range(22):
    on=oidx[bi]; nw=nbn[bi]; tgt=CH[bi]
    if tgt is None:
        dO=oWP[on]-oWP[opar[on]]; dN=nWPc[nw]-nWPc[npar[nw]]
    elif tgt=='kids':
        dO=kids(oWP,opar,on)-oWP[on]; dN=kids(nWP0,npar,nw)-nWP0[nw]
    else:
        dO=oWP[oidx[tgt]]-oWP[on]; dN=nWP0[nbn[tgt]]-nWP0[nw]
    dO=unit(W@dO); dN=unit(dN)
    ref=np.array([0,0,1.]) if abs(dO[2])<0.8 and abs(dN[2])<0.8 else np.array([0,1.,0])
    dold.append(dO); dnew.append(dN)
    sold.append(unit(ref-ref@dO*dO)); snew.append(unit(ref-ref@dN*dN))
fr=lambda a,b:np.stack([a,b,np.cross(a,b)],1)
Fn=[fr(dnew[i],snew[i]) for i in range(22)]
FOOT={16,17,20,21}
drvset=list(drv)
def clip_q(c):
    nf=c['nf']; a=np.frombuffer(d,'<i2',nf*len(drv)*4,c['q']).reshape(nf,len(drv),4).astype(float)/32767.0
    return a
rootT=oT[0]; Rr_old=oWR[0]; Rr_new=nWRc[0]
ratio=((nWPc[nbn[0]][1]-nWPc[nbn[17]][1])/(oWP[oidx[0]][1]-oWP[oidx[17]][1]))
print('ratio',ratio)
def retarget(c):
    nf=c['nf']; a=clip_q(c)
    pt=np.frombuffer(d,'<f4',nf*3,c['pt']).reshape(nf,3).astype(float)
    # rotações mundiais antigas
    oR=[None]*oN; Rl=[None]*oN
    for n in range(oN):
        if n in drv:
            Rl[n]=Rot.from_quat(a[:,drv.index(n)]/np.linalg.norm(a[:,drv.index(n)],axis=-1,keepdims=True)).as_matrix()
        else: Rl[n]=np.broadcast_to(qm(oQ[n]),(nf,3,3))
        oR[n]=Rl[n] if opar[n]<0 else oR[opar[n]]@Rl[n]
    nR={}; outq=np.zeros((nf,22,4)); bone_of={nbn[i]:i for i in range(22)}
    for k in range(nN):
        Rrest=qm(nQc[k])
        if k in bone_of:
            i=bone_of[k]; on=oidx[i]
            D=W@(oR[on]@oWR[on].T)@W.T
            Ft=np.stack([D@dold[i],D@sold[i]],2)
            Ftf=np.concatenate([Ft,np.cross(Ft[:,:,0],Ft[:,:,1])[:,:,None]],2)
            R=Ftf@Fn[i].T
            nR[k]=R@nWRc[k]
            if i in FOOT: nR[k]=D@nWRc[k]
            loc=nR[k] if npar[k]<0 else np.transpose(nR[npar[k]],(0,2,1))@nR[k]
            outq[:,i]=Rot.from_matrix(loc).as_quat()
        else:
            nR[k]=np.broadcast_to(Rrest,(nf,3,3)) if npar[k]<0 else nR[npar[k]]@Rrest
    for f in range(1,nf):
        s=(outq[f]*outq[f-1]).sum(1)<0; outq[f][s]*=-1
    # pelve: posição mundial antiga -> nova (escala + giro), depois ajuste de altura pelos pés
    pw_old=oWP[0]+(Rr_old@pt.T).T
    # rotação local do pelve no mundo é irrelevante para a posição
    dsp=(pw_old-oWP[oidx[0]])
    pw=nWPc[nbn[0]]+ratio*(dsp@W.T)
    return outq,pw,nR
def foot_min(nR_pose_q,pw):
    pass
def build(c):
    outq,pw,_=retarget(c)
    nf=len(pw)
    # FK nova para achar a altura dos pés e corrigir y da pelve
    def feet_y(outq,pw):
        ys=np.zeros((nf,4))
        for f in range(nf):
            Q=nQc.copy()
            for i in range(22): Q[nbn[i]]=outq[f,i]
            T=nT.copy()
            tl=(pw[f]-nWPc[npar[nbn[0]]])@nWRc[npar[nbn[0]]]
            T[nbn[0]]=tl
            _,WP=fk(npar,Q,T)
            ys[f]=[WP[nbn[16]][1],WP[nbn[17]][1],WP[nbn[20]][1],WP[nbn[21]][1]]
        return ys
    ys=feet_y(outq,pw)
    # alvo: pé mais baixo antigo (relativo ao repouso) * ratio
    tgt=np.zeros(nf)
    fa=np.frombuffer(d,'<i2',nf*len(drv)*4,c['q']).reshape(nf,len(drv),4).astype(float)/32767.0
    pt=np.frombuffer(d,'<f4',nf*3,c['pt']).reshape(nf,3).astype(float)
    for f in range(nf):
        Q=oQ.copy()
        for j,n in enumerate(drv): Q[n]=fa[f,j]/np.linalg.norm(fa[f,j])
        T=oT.copy(); T[1]=pt[f]
        _,WP=fk(opar,Q,T)
        oy=min(WP[oidx[16]][1],WP[oidx[17]][1],WP[oidx[20]][1],WP[oidx[21]][1])
        tgt[f]=oy
    oy_rest=min(oWP[oidx[i]][1] for i in (16,17,20,21)); ny_rest=min(nWPc[nbn[i]][1] for i in (16,17,20,21))
    want=ny_rest+ratio*(tgt-oy_rest)
    corr=want-ys.min(1)
    corr=np.convolve(np.pad(corr,(2,2),mode='edge'),np.ones(5)/5,mode='valid')
    pw=pw.copy(); pw[:,1]+=corr
    return outq,pw
# raiz: separa deslocamento x,z da pelve em 'root'
def split_root(pw):
    d0=pw-pw[0]; root=np.zeros_like(pw); root[:,0]=d0[:,0]; root[:,2]=d0[:,2]
    pw2=pw.copy(); pw2[:,0]=pw[0,0]; pw2[:,2]=pw[0,2]
    return pw2,root
out=[]
for c in g['clips']:
    q,pw=build(c); pw2,root=split_root(pw)
    par0=nbn[0]; tl=(pw2-nWPc[npar[par0]])@nWRc[npar[par0]]
    out.append(('c:'+c['name'],c['nf'],q,tl,root))
import pickle; pickle.dump(out,open('hero/c_out.pkl','wb'))
print(len(out))
for nm,nf,q,tl,root in out:
    if nm in('c:idle_00','c:attack_03','c:Combo_04','c:throw','c:defence','c:attack_05','c:buff_01'):
        print(nm,nf,'pel0',np.round(tl[0],2),'rootz',np.round(root[:,2].min(),2),np.round(root[:,2].max(),2),'x',np.round(root[:,0].min(),2),np.round(root[:,0].max(),2))
