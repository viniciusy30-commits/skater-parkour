import numpy as np, json, struct, os, pickle
from scipy.spatial.transform import Rotation as Rot
ASSET='../../codigo/app/src/main/assets/w/'
SC=2.0
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
M=json.load(open('meta.json')); S=M['secs']; raw=open('blob.bin','rb').read()
def sec(n,dt): a,l=S[n]; return np.frombuffer(raw[a:a+l],dtype=dt)
npar=M['par']; names=M['names']; NB=80
nLq=sec('Lq','<f4').reshape(-1,4).copy(); nLt=sec('Lt','<f4').reshape(-1,3).copy()*SC
nid=lambda n:names.index(n)
def fk_new(Lq, hook=None):
    WR=[None]*NB; WP=[None]*NB
    for k in range(NB):
        R=qm(Lq[k]); t=nLt[k]
        if npar[k]<0: WR[k]=R; WP[k]=t.copy()
        else: WR[k]=WR[npar[k]]@R; WP[k]=WP[npar[k]]+WR[npar[k]]@t
        if hook: hook(k,WR)
    return WR,WP
nWR0,nWP0=fk_new(nLq)
ax=M['axe']; curlLq=nLq.copy()
def hook(k,WR):
    for sd in ('r','l'):
        C=ax['curl'][sd]
        for ch in C['bones']:
            for j,kk in enumerate(ch):
                if kk==k:
                    a=np.array(C['ax']); a=WR[C['hand']]@a; a/=np.linalg.norm(a); th=np.radians(ax['ang'][j])
                    WR[k]=Rot.from_rotvec(a*th).as_matrix()@WR[k]
nWRc,nWPc=fk_new(nLq,hook)
for k in range(NB):
    if npar[k]>=0:
        loc=nWRc[npar[k]].T@nWRc[k]
        if not np.allclose(loc,qm(nLq[k]),atol=1e-6): curlLq[k]=Rot.from_matrix(loc).as_quat()
    else: curlLq[k]=Rot.from_matrix(nWRc[k]).as_quat()
# ibm = inversa do mundo de repouso (ESCALADO) -- ver nota: usa o repouso ORIGINAL (sem dedos curvados), igual à malha
ibm=np.zeros((NB,16),np.float32)
for k in range(NB):
    m=np.eye(4); m[:3,:3]=nWR0[k]; m[:3,3]=nWP0[k]
    ibm[k]=np.linalg.inv(m).T.reshape(-1)
MAP=[('pelvis',0),('spine_01',1),('spine_02',2),('spine_03',3),('neck_01',4),('head',5),
     ('clavicle_l',6),('upperarm_l',7),('lowerarm_l',8),('hand_l',9),
     ('clavicle_r',10),('upperarm_r',11),('lowerarm_r',12),('hand_r',13),
     ('thigh_l',14),('calf_l',15),('foot_l',16),('ball_l',17),
     ('thigh_r',18),('calf_r',19),('foot_r',20),('ball_r',21)]
newNode=[nid(n) for n,_ in MAP]
CH={0:1,1:2,2:3,3:4,4:5,5:None,6:7,7:8,8:9,9:'kids',10:11,11:12,12:13,13:'kids',14:15,15:16,16:17,17:None,18:19,19:20,20:21,21:None}
def kids_pos(P,par,node,N):
    c=[i for i in range(N) if par[i]==node]
    return np.mean([P[i] for i in c],0) if c else None
def unit(v): return v/np.linalg.norm(v)
dold=[];dnew=[];sold=[];snew=[]
for bi,(nn,_) in enumerate(MAP):
    on=obn[bi]; nw=nid(nn)
    tgt=CH[bi]
    if tgt is None:
        dO=oWP[on]-oWP[opar[on]]; dN=nWPc[nw]-nWPc[npar[nw]]
    elif tgt=='kids':
        kp=kids_pos(oWP,opar,on,nN); dO=kp-oWP[on]
        kn=kids_pos(nWP0,npar,nw,NB); dN=kn-nWP0[nw]
    else:
        dO=oWP[obn[tgt]]-oWP[on]; dN=nWP0[nid(MAP[tgt][0])]-nWP0[nw]
    dO=unit(dO); dN=unit(dN)
    ref=np.array([0,0,1.]) if abs(dO[2])<0.8 and abs(dN[2])<0.8 else np.array([0,1.,0])
    sO=unit(ref-ref@dO*dO); sN=unit(ref-ref@dN*dN)
    dold.append(dO);dnew.append(dN);sold.append(sO);snew.append(sN)
def frame(d,s): return np.stack([d,s,np.cross(d,s)],1)
Fnew_rest=[frame(dnew[i],snew[i]) for i in range(22)]
Fold_rest=[frame(dold[i],sold[i]) for i in range(22)]
ab=open(ASSET+'anim.bin','rb').read(); p=0
ncl=struct.unpack_from('<i',ab,p)[0]; p+=4
clips=[]
for _ in range(ncl):
    l=struct.unpack_from('<H',ab,p)[0]; p+=2; nm=ab[p:p+l].decode(); p+=l
    nf=struct.unpack_from('<i',ab,p)[0]; p+=4
    rec=np.dtype([('q','<i2',(22,4)),('pel','<f4',3),('root','<f4',3)])
    a=np.frombuffer(ab,dtype=rec,count=nf,offset=p).copy(); p+=nf*rec.itemsize
    clips.append((nm,nf,a['q'].astype(np.float64)/32767.0,a['pel'].copy(),a['root'].copy()))
print('clips',ncl,sum(c[1] for c in clips),'frames')
def quat_mats(q):
    q=q/np.linalg.norm(q,axis=-1,keepdims=True); return Rot.from_quat(q).as_matrix()
oPelRestWorld=oWP[obn[0]]; nPelRestWorld=nWPc[nid('pelvis')]
offset=nPelRestWorld-oPelRestWorld
FOOT={16,17,20,21}
def retarget(nf,q,pel):
    oR={}
    for n in oord:
        bi=np.where(obn==n)[0]
        Rl=quat_mats(q[:,bi[0]]) if len(bi) else np.broadcast_to(qm(oQ[n]),(nf,3,3))
        oR[n]=Rl if opar[n]<0 else oR[opar[n]]@Rl
    nR={}; outq=np.zeros((nf,22,4))
    bone_of={newNode[i]:i for i in range(22)}
    for k in range(NB):
        Rl_rest=qm(curlLq[k])
        if k in bone_of:
            i=bone_of[k]; on=obn[i]
            D=oR[on]@oWR[on].T
            Ft=np.stack([D@dold[i],D@sold[i]],2)
            Ftf=np.concatenate([Ft,np.cross(Ft[:,:,0],Ft[:,:,1])[:,:,None]],2)
            R=Ftf@Fnew_rest[i].T
            nR[k]=R@nWRc[k]
            if i in FOOT: nR[k]=D@nWRc[k]   # pé e dedos: o osso antigo aponta mais para baixo no repouso; usar a rotação relativa ao repouso
            Pw=nR[npar[k]] if npar[k]>=0 else None
            loc=nR[k] if Pw is None else np.transpose(Pw,(0,2,1))@nR[k]
            outq[:,i]=Rot.from_matrix(loc).as_quat()
        else:
            nR[k]=(np.broadcast_to(Rl_rest,(nf,3,3)) if npar[k]<0 else nR[npar[k]]@Rl_rest)
    for f in range(1,nf):
        s=(outq[f]*outq[f-1]).sum(1)<0; outq[f][s]*=-1
    tw=pel+offset
    Rr=nWRc[npar[nid('pelvis')]]; tl=(tw-nWPc[npar[nid('pelvis')]])@Rr
    return outq,tl
out=[]
for nm,nf,q,pel,root in clips:
    oq,pl=retarget(nf,q,pel); out.append((nm,nf,oq,pl,root))
order=list(range(NB))
os.makedirs('hero/out',exist_ok=True)
with open('hero/out/rig.bin','wb') as f:
    f.write(struct.pack('<4i',NB,NB,nid('pelvis'),22))
    f.write(np.array(npar,'<i4').tobytes()); f.write(nLt.astype('<f4').tobytes()); f.write(curlLq.astype('<f4').tobytes())
    f.write(np.array(order,'<i4').tobytes()); f.write(np.arange(NB,dtype='<i4').tobytes()); f.write(ibm.astype('<f4').tobytes())
    f.write(np.array(newNode,'<i4').tobytes())
with open('hero/out/anim.bin','wb') as f:
    f.write(struct.pack('<i',len(out)))
    for nm,nf,oq,pl,root in out:
        e=nm.encode(); f.write(struct.pack('<H',len(e))+e+struct.pack('<i',nf))
        qi=np.clip(np.round(oq*32767),-32767,32767).astype('<i2')
        for fr in range(nf):
            f.write(qi[fr].tobytes()); f.write(pl[fr].astype('<f4').tobytes()); f.write(root[fr].astype('<f4').tobytes())
pickle.dump(dict(out=out,newNode=newNode,npar=npar,nLt=nLt,curlLq=curlLq,ibm=ibm),open('hero/out/ret.pkl','wb'))
print('ok', os.path.getsize('hero/out/anim.bin'), os.path.getsize('hero/out/rig.bin'))
