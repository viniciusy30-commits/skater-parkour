import numpy as np, struct, os, io, pickle
from scipy.spatial.transform import Rotation as Rot
from PIL import Image
exec(open('/home/claude/b/loadg.py').read())
exec(open('/home/claude/b/oldrig.py').read())
OUT='/home/claude/b/out2/'; os.makedirs(OUT,exist_ok=True)
NB=80
# ---------- transformação global: frente +X -> +Z, escala SC (pelve igual à do guerreiro)
BW=np.linalg.inv(IBM)
pelv=names.index('pelvis')
SC=float(oWP[obn[0]][1]/BW[pelv][1,3])
G=Rot.from_euler('y',-90,degrees=True).as_matrix()
print('SC',SC)
M=np.zeros((NB,4,4)); 
for k in range(NB):
    M[k]=np.eye(4); M[k][:3,:3]=G@BW[k][:3,:3]; M[k][:3,3]=SC*(G@BW[k][:3,3])
# ---------- hierarquia (juntas) em ordem BFS
nodeToJ={n:i for i,n in enumerate(JN)}
parJ=[-1]*NB
for n in range(len(N)):
    for c in N[n].get('children',[]):
        if c in nodeToJ and n in nodeToJ: parJ[nodeToJ[c]]=nodeToJ[n]
roots=[k for k in range(NB) if parJ[k]<0]; assert len(roots)==1,roots
bfs=[roots[0]]; 
for k in bfs:
    bfs+= [c for c in range(NB) if parJ[c]==k]
assert len(bfs)==NB
nodeOfJoint={j:i for i,j in enumerate(bfs)}   # junta glb -> nó novo
npar=[-1]*NB; nLt=np.zeros((NB,3)); nLq=np.zeros((NB,4)); nWR0=[None]*NB; nWP0=[None]*NB; nnames=[None]*NB
for i,j in enumerate(bfs):
    nnames[i]=names[j]; Rw=M[j][:3,:3]; tw=M[j][:3,3]; nWR0[i]=Rw; nWP0[i]=tw
    if parJ[j]<0: nLt[i]=tw; nLq[i]=Rot.from_matrix(Rw).as_quat()
    else:
        pi=nodeOfJoint[parJ[j]]; npar[i]=pi
        Rp=nWR0[pi]; nLt[i]=Rp.T@(tw-nWP0[pi]); nLq[i]=Rot.from_matrix(Rp.T@Rw).as_quat()
assert all(npar[k]<k for k in range(NB) if npar[k]>=0)
nid=lambda n:nnames.index(n)
ibm=np.zeros((NB,16),np.float32)   # por junta glb j (mesmo índice do JOINTS_0)
for j in range(NB): ibm[j]=np.linalg.inv(M[j]).T.reshape(-1)
jointNode=np.array([nodeOfJoint[j] for j in range(NB)],'<i4')
nWRc,nWPc,curlLq=nWR0,nWP0,nLq
# ---------- retarget (mesma lógica do tools/guerreiro/retarget.py, sem a curva dos dedos)
MAP=[('pelvis',0),('spine_01',1),('spine_02',2),('spine_03',3),('neck_01',4),('head',5),
     ('clavicle_l',6),('upperarm_l',7),('lowerarm_l',8),('hand_l',9),
     ('clavicle_r',10),('upperarm_r',11),('lowerarm_r',12),('hand_r',13),
     ('thigh_l',14),('calf_l',15),('foot_l',16),('ball_l',17),
     ('thigh_r',18),('calf_r',19),('foot_r',20),('ball_r',21)]
newNode=[nid(n) for n,_ in MAP]
CH={0:1,1:2,2:3,3:4,4:5,5:None,6:7,7:8,8:9,9:'kids',10:11,11:12,12:13,13:'kids',14:15,15:16,16:17,17:None,18:19,19:20,20:21,21:None}
def kids_pos(P_,par,node,n):
    c=[i for i in range(n) if par[i]==node]
    return np.mean([P_[i] for i in c],0) if c else None
def unit(v): return v/np.linalg.norm(v)
dold=[];dnew=[];sold=[]; snew=[]
for bi,(nn,_) in enumerate(MAP):
    on=obn[bi]; nw=nid(nn); tgt=CH[bi]
    if tgt is None:
        dO=oWP[on]-oWP[opar[on]]; dN=nWPc[nw]-nWPc[npar[nw]]
    elif tgt=='kids':
        dO=kids_pos(oWP,opar,on,nN)-oWP[on]; dN=kids_pos(nWP0,npar,nw,NB)-nWP0[nw]
    else:
        dO=oWP[obn[tgt]]-oWP[on]; dN=nWP0[nid(MAP[tgt][0])]-nWP0[nw]
    dO=unit(dO); dN=unit(dN)
    ref=np.array([0,0,1.]) if abs(dO[2])<0.8 and abs(dN[2])<0.8 else np.array([0,1.,0])
    sO=unit(ref-ref@dO*dO); sN=unit(ref-ref@dN*dN)
    dold.append(dO);dnew.append(dN);sold.append(sO);snew.append(sN)
def frame(d,s): return np.stack([d,s,np.cross(d,s)],1)
Fnew=[frame(dnew[i],snew[i]) for i in range(22)]
ab=open(ASSET+'anim.bin','rb').read(); p=0
ncl=struct.unpack_from('<i',ab,p)[0]; p+=4
rec=np.dtype([('q','<i2',(22,4)),('pel','<f4',3),('root','<f4',3)])
clips=[]
for _ in range(ncl):
    l=struct.unpack_from('<H',ab,p)[0]; p+=2; nm=ab[p:p+l].decode(); p+=l
    nf=struct.unpack_from('<i',ab,p)[0]; p+=4
    a=np.frombuffer(ab,dtype=rec,count=nf,offset=p).copy(); p+=nf*rec.itemsize
    clips.append((nm,nf,a['q'].astype(np.float64)/32767.0,a['pel'].copy(),a['root'].copy()))
print('clips',ncl,sum(c[1] for c in clips))
def quat_mats(q): return Rot.from_quat(q/np.linalg.norm(q,axis=-1,keepdims=True)).as_matrix()

# ======================= RETARGET v2 =======================
import sys
CLAV=os.environ.get('CLAV','dir')
OFFSET=nWPc[nid('pelvis')]-oWP[obn[0]]
# ---- punho fechado relaxado (baked no repouso local dos dedos)
curl=curlLq.copy()
down=np.array([0,-1.,0])
CURL={'_metacarpal':0,'_01':50,'_02':70,'_03':45}
for k in range(NB):
    nmk=nnames[k]
    if not any(nmk.startswith(f) for f in ('index_','middle_','ring_','pinky_','thumb_')) or 'metacarpal' in nmk: continue
    ang=None
    if nmk.startswith('thumb_'): ang={'01':10,'02':22,'03':28}[nmk.split('_')[1]]
    else: ang=CURL['_'+nmk.split('_')[1]]
    ch=[c for c in range(NB) if npar[c]==k]
    if ch: d=nWP0[ch[0]]-nWP0[k]
    else: d=nWP0[k]-nWP0[npar[k]]
    d=d/np.linalg.norm(d); a=np.cross(d,down); a/=np.linalg.norm(a)
    Aloc=nWR0[k].T@a
    curl[k]=(Rot.from_quat(curlLq[k])*Rot.from_rotvec(Aloc*np.radians(ang))).as_quat()
curlLq=curl
EXTRA=['spine_04','spine_05','neck_02',
 'lowerarm_twist_01_l','lowerarm_twist_02_l','upperarm_twist_01_l','upperarm_twist_02_l',
 'lowerarm_twist_01_r','lowerarm_twist_02_r','upperarm_twist_01_r','upperarm_twist_02_r',
 'calf_twist_01_l','calf_twist_02_l','thigh_twist_01_l','thigh_twist_02_l',
 'calf_twist_01_r','calf_twist_02_r','thigh_twist_01_r','thigh_twist_02_r']
BONES=[n for n,_ in MAP]+EXTRA
newNode=[nid(n) for n in BONES]
NBN=len(BONES); bi_of={n:i for i,n in enumerate(BONES)}
DIRB={'upperarm_l','lowerarm_l','hand_l','upperarm_r','lowerarm_r','hand_r','thigh_l','calf_l','thigh_r','calf_r'}
if CLAV=='dir': DIRB|={'clavicle_l','clavicle_r'}
def rv_clamp(rv,maxdeg):
    n=np.linalg.norm(rv,axis=1,keepdims=True); f=np.minimum(1,np.radians(maxdeg)/np.maximum(n,1e-9)); return rv*f
def RR(rv): return Rot.from_rotvec(rv)
# twist: pai -> filho, osso de torção -> fração
TW={ # twist bone : (parent bone, child bone, fraction)
 'lowerarm_twist_02_l':('lowerarm_l','hand_l',1/3),'lowerarm_twist_01_l':('lowerarm_l','hand_l',2/3),
 'lowerarm_twist_02_r':('lowerarm_r','hand_r',1/3),'lowerarm_twist_01_r':('lowerarm_r','hand_r',2/3),
 'upperarm_twist_01_l':('upperarm_l','lowerarm_l',1/3),'upperarm_twist_02_l':('upperarm_l','lowerarm_l',2/3),
 'upperarm_twist_01_r':('upperarm_r','lowerarm_r',1/3),'upperarm_twist_02_r':('upperarm_r','lowerarm_r',2/3),
 'calf_twist_02_l':('calf_l','foot_l',1/3),'calf_twist_01_l':('calf_l','foot_l',2/3),
 'calf_twist_02_r':('calf_r','foot_r',1/3),'calf_twist_01_r':('calf_r','foot_r',2/3),
 'thigh_twist_01_l':('thigh_l','calf_l',1/3),'thigh_twist_02_l':('thigh_l','calf_l',2/3),
 'thigh_twist_01_r':('thigh_r','calf_r',1/3),'thigh_twist_02_r':('thigh_r','calf_r',2/3)}
def retarget(nf,q,pel):
    oR={}
    for n in oord:
        bi=np.where(obn==n)[0]
        Rl=quat_mats(q[:,bi[0]]) if len(bi) else np.broadcast_to(qm(oQ[n]),(nf,3,3))
        oR[n]=Rl if opar[n]<0 else oR[opar[n]]@Rl
    D={}
    for i in range(22):
        on=obn[i]; D[MAP[i][0]]=oR[on]@oWR[on].T          # delta mundial do osso antigo
    if LOCO:   # corrida sem rebolado: amortece giro (yaw) e inclinação lateral (roll) da pelve
        e=Rot.from_matrix(D['pelvis']).as_euler('YXZ')
        e[:,0]=np.unwrap(e[:,0]); e[:,2]=np.unwrap(e[:,2])
        for ci,fc in ((0,0.25),(2,0.25),(1,0.6)):
            e[:,ci]=e[:,ci].mean()+(e[:,ci]-e[:,ci].mean())*fc
        D['pelvis']=Rot.from_euler('YXZ',e).as_matrix()
    tgt={}                                                   # rotação mundial alvo por nome
    for i,(nn,_) in enumerate(MAP):
        k=nid(nn)
        if nn in DIRB:
            Dd=D[nn]; Ft=np.stack([Dd@dold[i],Dd@sold[i]],2)
            Ftf=np.concatenate([Ft,np.cross(Ft[:,:,0],Ft[:,:,1])[:,:,None]],2)
            tgt[nn]=(Ftf@Fnew[i].T)@nWRc[k]
        else: tgt[nn]=D[nn]@nWRc[k]
    # coluna: torção/flexão do quadril ao peito distribuída por igual
    Dp=Rot.from_matrix(D['pelvis']); Dc=Rot.from_matrix(D['spine_03'])
    rv=(Dp.inv()*Dc).as_rotvec()
    for nm_,fr in (('spine_01',.2),('spine_02',.4),('spine_03',.6),('spine_04',.8),('spine_05',1.0)):
        Dk=(Dp*RR(rv*fr)).as_matrix(); tgt[nm_]=Dk@nWRc[nid(nm_)]
    # pescoço e cabeça: seguem o peito e herdam só parte do movimento antigo
    Dh=Rot.from_matrix(D['head']); rh=rv_clamp(0.5*(Dc.inv()*Dh).as_rotvec(),35)
    # olhar para frente: corrige o cabeceio (pitch) da cabeça no mundo, mantendo a guinada
    Dhw=(Dc*RR(rh)).as_matrix()
    fw=Dhw@np.array([0,0,1.]); tg=fw.copy(); tg[:,1]=np.minimum(fw[:,1],0)*0.15+np.maximum(fw[:,1],0)
    tg/=np.linalg.norm(tg,axis=1,keepdims=True)
    ax_=np.cross(fw,tg); sn=np.linalg.norm(ax_,axis=1,keepdims=True); cs=(fw*tg).sum(1)
    ang_=np.arctan2(sn[:,0],cs); ax_=ax_/np.maximum(sn,1e-9)
    Rc=RR(ax_*ang_[:,None])
    Dnew=Rc*Rot.from_matrix(Dhw)
    rh=rv_clamp((Dc.inv()*Dnew).as_rotvec(),75)
    for nm_,fr in (('neck_01',.35),('neck_02',.7),('head',1.0)):
        Dk=(Dc*RR(rh*fr)).as_matrix(); tgt[nm_]=Dk@nWRc[nid(nm_)]
    # ossos de torção
    for tb,(pb,cb,fr) in TW.items():
        kp=nid(pb); kc=nid(cb); kt=nid(tb)
        Rp=tgt[pb]; Rc=tgt[cb]
        relpose=np.transpose(Rp,(0,2,1))@Rc
        relrest=nWRc[kp].T@nWRc[kc]
        Qd=Rot.from_matrix(relpose@relrest.T).as_quat()      # x y z w, no referencial do pai
        # eixo do osso no referencial do pai (repouso)
        dirw=nWPc[kc]-nWPc[kp]; ax=nWRc[kp].T@(dirw/np.linalg.norm(dirw))
        s=np.einsum('ni,i->n',Qd[:,:3],ax); th=2*np.arctan2(s,Qd[:,3])
        th=(th+np.pi)%(2*np.pi)-np.pi
        Tq=RR(ax[None,:]*(th*fr)[:,None]).as_matrix()
        tgt[tb]=Rp@Tq@qm(curlLq[kt])
    # monta rotações locais
    nR={}; outq=np.zeros((nf,NBN,4))
    for k in range(NB):
        nmk=nnames[k]
        if nmk in tgt: nR[k]=tgt[nmk]
        else:
            Rl_rest=qm(curlLq[k]); nR[k]=np.broadcast_to(Rl_rest,(nf,3,3)) if npar[k]<0 else nR[npar[k]]@Rl_rest
        if nmk in bi_of:
            Pw=nR[npar[k]] if npar[k]>=0 else None
            loc=nR[k] if Pw is None else np.transpose(Pw,(0,2,1))@nR[k]
            outq[:,bi_of[nmk]]=Rot.from_matrix(loc).as_quat()
    for f in range(1,nf):
        s=(outq[f]*outq[f-1]).sum(1)<0; outq[f][s]*=-1
    po=opar[opel]; tw=oWP[po]+pel@oWR[po].T+OFFSET
    pp=npar[nid('pelvis')]; Rr=nWRc[pp]; tl=(tw-nWPc[pp])@Rr
    return outq,tl
out=[]
for nm,nf,q,pel,root in clips:
    import re as _re; LOCO=bool(_re.search(r'run|jog|walk|sprint|move_',nm,_re.I)); print('LOCO',nm) if LOCO and nm.startswith('c:move_run') else None
    oq,pl=retarget(nf,q,pel); out.append((nm,nf,oq,pl,root))
# ---- pesos: cabeça rígida (queixo/mandíbula não acompanham o pescoço)
hj=names.index('head'); 
rad=np.hypot(P[:,0]+0.012,P[:,2])
rigid=(P[:,1]>=0.89)|((P[:,1]>=0.865)&(rad>0.05))
W=W.copy(); JJ=JJ.copy()
W[rigid]=0; JJ[rigid]=0; W[rigid,0]=1.0; JJ[rigid,0]=hj

# ---- cintura: coxa não puxa a barriga (evita afinar/esticar o tronco quando agacha)
THIGH=[names.index(n) for n in names if n.startswith('thigh')]
pelj=names.index('pelvis'); s1j=names.index('spine_01'); s2j=names.index('spine_02')
Y0,Y1=0.53,0.60
fy=np.clip((P[:,1]-Y0)/(Y1-Y0),0,1)          # 0 = coxa livre, 1 = coxa zerada
W2=W.copy(); JJ2=JJ.copy()
for v in np.where(fy>0)[0]:
    d={}
    for j,w in zip(JJ[v],W[v]):
        if w>0: d[j]=d.get(j,0)+w
    mv=0
    for j in list(d):
        if j in THIGH:
            r=d[j]*fy[v]; d[j]-=r; mv+=r
    if mv>0:
        t=np.clip((P[v,1]-0.53)/0.09,0,1)   # pelve em baixo, coluna em cima
        d[pelj]=d.get(pelj,0)+mv*(1-t); d[s1j]=d.get(s1j,0)+mv*t*0.6; d[s2j]=d.get(s2j,0)+mv*t*0.4
    it=sorted(((w,j) for j,w in d.items() if w>1e-6),reverse=True)[:4]
    tot=sum(w for w,_ in it)
    W2[v]=0; JJ2[v]=0
    for k,(w,j) in enumerate(it): W2[v,k]=w/tot; JJ2[v,k]=j
W=W2; JJ=JJ2
with open(OUT+'rig.bin','wb') as f:
    f.write(struct.pack('<4i',NB,NB,nid('pelvis'),NBN))
    f.write(np.array(npar,'<i4').tobytes()); f.write(nLt.astype('<f4').tobytes()); f.write(curlLq.astype('<f4').tobytes())
    f.write(np.arange(NB,dtype='<i4').tobytes()); f.write(jointNode.tobytes()); f.write(ibm.astype('<f4').tobytes())
    f.write(np.array(newNode,'<i4').tobytes())
with open(OUT+'anim.bin','wb') as f:
    f.write(struct.pack('<i',len(out)))
    for nm,nf,oq,pl,root in out:
        e=nm.encode(); f.write(struct.pack('<H',len(e))+e+struct.pack('<i',nf))
        qi=np.clip(np.round(oq*32767),-32767,32767).astype('<i2')
        for fr in range(nf):
            f.write(qi[fr].tobytes()); f.write(pl[fr].astype('<f4').tobytes()); f.write(root[fr].astype('<f4').tobytes())
# ---------- malha + textura
Pn=(SC*(G@P.T)).T; Nn=(G@NR.T).T
Nn/=np.maximum(np.linalg.norm(Nn,axis=1),1e-9)[:,None]
tex_rm=np.array(Image.open(io.BytesIO(image(1))).convert('RGB')).astype(np.float64)/255
H,Wd=tex_rm.shape[:2]
def samp(uv):
    x=np.clip(uv[:,0]*(Wd-1),0,Wd-1); y=np.clip(uv[:,1]*(H-1),0,H-1)
    x0=np.floor(x).astype(int); y0=np.floor(y).astype(int); x1=np.minimum(x0+1,Wd-1); y1=np.minimum(y0+1,H-1); fx=(x-x0)[:,None]; fy=(y-y0)[:,None]
    return (tex_rm[y0,x0]*(1-fx)*(1-fy)+tex_rm[y0,x1]*fx*(1-fy)+tex_rm[y1,x0]*(1-fx)*fy+tex_rm[y1,x1]*fx*fy)
met=samp(UV)[:,2]
# 4 influências com soma 255
order=np.argsort(-W,axis=1); Wi=np.take_along_axis(W,order,1); Ji=np.take_along_axis(JJ,order,1)
Wf=Wi/np.maximum(Wi.sum(1,keepdims=True),1e-9)*255; Wq=np.floor(Wf).astype(np.int64); Wq[np.arange(len(Wq)),0]+=255-Wq.sum(1)
lo=Pn.min(0); hi=Pn.max(0); ctr=(lo+hi)/2; ext=(hi-lo)/2*1.001; nv=len(Pn)
q=np.clip(np.round((Pn-ctr)/ext*32767),-32767,32767).astype('<i2'); nq=np.clip(np.round(Nn*32767),-32767,32767).astype('<i2')
r=np.zeros(nv,dtype=[('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)])
r['p'][:,:3]=q; r['n'][:,:3]=nq; r['n'][:,3]=np.clip(met*32767+.5,0,32767).astype('<i2')
r['uv']=np.clip(np.round(UV*65535),0,65535).astype('<u2'); r['j']=Ji.astype(np.uint8); r['w']=Wq.astype(np.uint8)
assert r.itemsize==28
idx=IDX.astype('<u4').reshape(-1)
with open(OUT+'char.msh','wb') as f:
    f.write(struct.pack('<4i',0,nv,len(idx),28)); f.write(struct.pack('<3f',*ctr)); f.write(struct.pack('<3f',*ext)); f.write(r.tobytes()); f.write(idx.tobytes())
bc=Image.open(io.BytesIO(image(0))).convert('RGB'); print('basecolor',bc.size)
if max(bc.size)>2048: bc=bc.resize((2048,2048),Image.LANCZOS)
bc.save(OUT+'char_atlas.jpg',quality=92)
pickle.dump(dict(out=out,newNode=newNode,npar=npar,nLt=nLt,nLq=curlLq,nWR0=nWR0,nWP0=nWP0,jointNode=jointNode,ibm=ibm,Pn=Pn,UV=UV,Ji=Ji,Wq=Wq,IDX=IDX,SC=SC),open(OUT+'ret.pkl','wb'))
print('ctr',ctr.round(3),'ext',ext.round(3),'nv',nv,'tris',len(idx)//3,'height',hi[1],'met mean',met.mean().round(3))
for fn in os.listdir(OUT): print(fn,os.path.getsize(OUT+fn))
