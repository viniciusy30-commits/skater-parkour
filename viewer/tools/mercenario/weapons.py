import sys,pickle,numpy as np; sys.path.insert(0,'/tmp/claude-0/-home-user-skater-parkour/19329d18-9da6-5d89-afba-e925a710541c/scratchpad/work')
from retarget import *
SW_LEN=0.74     # comprimento total da espada (altura do personagem = 1)
YG=0.84         # centro do cabo (fracao do comprimento 1.0 do modelo)
Bm=np.array([[0,0,1],[0,-1,0],[1,0,0]],float)
def blade_local(raw,k):
    P=raw['P']; L=raw['L']; sel=np.where(L==k)[0]
    p=P[sel]; tip=p[p[:,1]<0.05]; piv=np.array([np.median(tip[:,0]),YG,np.median(tip[:,2])])
    q=(p-piv)@Bm.T*SW_LEN
    nn=raw['N'][sel]@Bm.T
    remap=-np.ones(len(P),int); remap[sel]=np.arange(len(sel))
    tri=raw['tri']; m=np.isin(tri[:,0],sel); tri=remap[tri[m]]
    return dict(P=q,N=nn,UV=raw['UV'][sel],tri=tri)
def attach(S,T,side):
    """Retorna (Rw,pw) em coordenadas mundo do alvo (repouso) e a matriz local ao osso da mao."""
    s=side; wbn='%s_Hand_Weapon_Bone'%s.upper()
    ixs=S.ix; Fs=anat_frames(S.restWP,S.ix,'spine_03')['hand_'+s]
    Ws=S.restWR[ixs[wbn]]; Ph=S.restWP[ixs['hand_'+s]]; Pw=S.restWP[ixs[wbn]]
    # escala da mao
    ks=np.linalg.norm(S.restWP[ixs['middle_01_'+s]]-Ph); kt=np.linalg.norm(T.restWP[T.ix['middle_01_'+s]]-T.restWP[T.ix['hand_'+s]])
    Ft=T.F['hand_'+s]
    Rw=Ft@(Fs.T@Ws)
    pw=T.restWP[T.ix['hand_'+s]]+Ft@(Fs.T@(Pw-Ph)*(kt/ks))
    return Rw,pw,Fs,Ft
