import io,contextlib
with contextlib.redirect_stdout(io.StringIO()):
    exec(open('/home/claude/b/handfix_base.py').read())
    for sd in 'rl':
        GRIP[sd],cn,rad=close_hand(sd)
FINGK_=('thumb','index','middle','ring','pinky')
def curl(P0,N0,Jm,Wm):
    """fecha as maos no bind (mesma logica do add_weapon.py)"""
    Wm=Wm/np.maximum(Wm.sum(1,keepdims=True),1e-9)
    MB=np.einsum('vk,vkab->vab',Wm,SK[Jm])
    Pr=np.einsum('vab,vb->va',MB,np.c_[P0,np.ones(len(P0))])[:,:3]; Nr=np.einsum('vab,vb->va',MB[:,:3,:3],N0)
    Pn=Pr.copy(); Nn=Nr.copy()
    for side,lst in GRIP.items():
        M={j:G for j,G in lst}
        acc=np.zeros_like(Pr); accn=np.zeros_like(Nr); wsum=np.zeros(len(Pr))
        for k in range(4):
            for j,G in M.items():
                sel=(Jm[:,k]==j)&(Wm[:,k]>0)
                if not sel.any(): continue
                w=Wm[sel,k]; acc[sel]+=w[:,None]*((G[:3,:3]@Pn[sel].T).T+G[:3,3]); accn[sel]+=w[:,None]*(G[:3,:3]@Nn[sel].T).T; wsum[sel]+=w
        mv=wsum>0
        Pn=np.where(mv[:,None],acc+(1-wsum)[:,None]*Pn,Pn); Nn=np.where(mv[:,None],accn+(1-wsum)[:,None]*Nn,Nn)
    inv=np.linalg.inv(MB)
    P1=np.einsum('vab,vb->va',inv,np.c_[Pn,np.ones(len(Pn))])[:,:3]; N1=np.einsum('vab,vb->va',inv[:,:3,:3],Nn)
    return P1,N1/np.maximum(np.linalg.norm(N1,axis=1,keepdims=True),1e-9)
def rotm(a,th):
    a=unit(a); K=np.array([[0,-a[2],a[1]],[a[2],0,-a[0]],[-a[1],a[0],0]]); return np.eye(3)+np.sin(th)*K+(1-np.cos(th))*K@K
def roll_setup(Pbind,Nbind,Jm,Wm):
    Wm=Wm/np.maximum(Wm.sum(1,keepdims=True),1e-9)
    MB=np.einsum('vk,vkab->vab',Wm,SK[Jm]); Pr=np.einsum('vab,vb->va',MB,np.c_[Pbind,np.ones(len(Pbind))])[:,:3]; Nr=np.einsum('vab,vb->va',MB[:,:3,:3],Nbind)
    return MB,Pr,Nr,Wm
def roll(Pbind,Nbind,Jm,Wm,ang):
    MB,Pr,Nr,Wm=roll_setup(Pbind,Nbind,Jm,Wm); nv=len(Pr)
    for sd in 'rl':
        hj=names.index('hand_'+sd); wrist=Wd[jn[hj]][:3,3]; elbow=Wd[jn[names.index('lowerarm_'+sd)]][:3,3]; Ln=np.linalg.norm(wrist-elbow); f=unit(wrist-elbow)
        arm=set(i for i,nm in enumerate(names) if nm.endswith('_'+sd) and any(k in nm for k in ('lowerarm','hand','thumb','index','middle','ring','pinky')))
        wa=np.zeros(nv)
        for k in range(4): wa+=np.where(np.isin(Jm[:,k],list(arm)),Wm[:,k],0)
        s=(Pr-elbow)@f; t=np.clip(s/Ln,0,1); sm=t*t*(3-2*t); frac=wa*sm
        th=np.radians(ang[sd])*frac
        for v in np.where(frac>1e-4)[0]:
            Rv=rotm(f,th[v]); Pr[v]=wrist+Rv@(Pr[v]-wrist); Nr[v]=Rv@Nr[v]
    inv=np.linalg.inv(MB); P2=np.einsum('vab,vb->va',inv,np.c_[Pr,np.ones(nv)])[:,:3]; N2=np.einsum('vab,vb->va',inv[:,:3,:3],Nr)
    return P2,N2/np.maximum(np.linalg.norm(N2,axis=1,keepdims=True),1e-9)
