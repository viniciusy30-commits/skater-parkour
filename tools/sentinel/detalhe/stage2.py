import sys;sys.path.insert(0,'/home/claude/b')
import numpy as np,cv2
from scipy.ndimage import distance_transform_edt
import scipy.sparse as sp,scipy.sparse.csgraph as cg
from PIL import Image
from handfix import names
B=np.load('body.npz');P=B['P'];UV=B['UV'];I=B['I'];J=B['J'];W=B['W']
H=2048
img=cv2.cvtColor(cv2.imread('/home/claude/w/img0.jpg'),cv2.COLOR_BGR2RGB)
nrm=np.array(Image.open('/home/claude/w/img2.png').convert('RGB'))
mr=np.array(Image.open('/home/claude/w/img1.png').convert('RGB'))
# --- ilhas UV
n=len(P);e=np.concatenate([I[:,[0,1]],I[:,[1,2]],I[:,[2,0]]]);A=sp.coo_matrix((np.ones(len(e)),(e[:,0],e[:,1])),shape=(n,n));nc,lab=cg.connected_components(A,directed=False)
print('ilhas',nc)
tl=lab[I[:,0]]
pix=lambda t:np.round(UV[I[t]]*[H-1,H-1]).astype(np.int32)
labimg=np.full((H,H),-1,np.int32);cov=np.zeros((H,H),np.uint8)
hd=[i for i,nm in enumerate(names) if nm in('head','neck_01','neck_02') or nm.startswith(('hand_','thumb_','index_','middle_','ring_','pinky_'))]
hw=np.zeros(n)
for k in range(4): hw+=np.where(np.isin(J[:,k],hd),W[:,k],0)
headtri=hw[I].mean(1)>0.5
headm=np.zeros((H,H),np.uint8)
for t in range(len(I)):
    p=pix(t);cv2.fillConvexPoly(labimg,p,int(tl[t]));cv2.fillConvexPoly(cov,p,1)
    if headtri[t]: cv2.fillConvexPoly(headm,p,1)
headm=cv2.dilate(headm,np.ones((5,5),np.uint8))
f=img.astype(np.float32);r,g,b=f[...,0],f[...,1],f[...,2];lum=0.299*r+0.587*g+0.114*b
skin=((r>185)&(r-b>28)&(lum>150))
sk_isl=np.zeros(nc)
for i in range(nc):
    m=labimg==i
    if m.sum()>200: sk_isl[i]=skin[m].mean()
skinisl=np.isin(labimg,np.where(sk_isl>0.22)[0])
print('ilhas de pele',(sk_isl>0.22).sum(),'pixels',skinisl.sum())
ink=skinisl&~skin&(lum>62)&(lum<178)&((r-b)<44)&(headm==0)
a=np.clip((178-lum)/50,0,1)*ink
a=cv2.GaussianBlur(a.astype(np.float32),(0,0),0.6)*(headm==0)
red=np.array([150,22,30],np.float32)
tint=red*(0.80+0.30*np.clip(lum/140,0,1.2))[...,None]
out=f*(1-a[...,None])+tint*a[...,None]
odd=skinisl&~skin&(lum>=150)&(a<0.02)&(headm==0)
odd=cv2.dilate(odd.astype(np.uint8),np.ones((3,3),np.uint8))
print('manchas claras na pele',int(odd.sum()))
skin_only=np.clip(out,0,255).astype(np.uint8)
inp=cv2.inpaint(skin_only,odd,3,cv2.INPAINT_TELEA)
out=np.where(odd[...,None]>0,inp.astype(np.float32),out)
# 3) cabelo: texels claros (cor de pele) dentro dos triangulos de cabelo viram cabelo escuro
tc=UV[I].mean(1);cx=np.clip(np.round(tc[:,0]*(H-1)).astype(int),0,H-1);cy=np.clip(np.round(tc[:,1]*(H-1)).astype(int),0,H-1)
hairtri=headtri&(lum[cy,cx]<70)&(P[I][:,:,1].min(1)>1.5)
hairm=np.zeros((H,H),np.uint8)
for t in np.where(hairtri)[0]: cv2.fillConvexPoly(hairm,pix(t),1)
pale=(hairm>0)&(lum>70)
print('texels claros no cabelo',int(pale.sum()))
hair_dark=np.clip(f*(38.0/np.maximum(lum,1))[...,None],0,255)
out=np.where(pale[...,None],hair_dark,out)
# --- padding das bordas (cor da ilha vizinha mais proxima)
d,(iy,ix)=distance_transform_edt(cov==0,return_indices=True)
def pad(x):
    y=x[iy,ix];return y
mr=mr.copy();mr[...,2][skinisl]=0   # pele nao e metal (tira o brilho esbranquicado)
outp=pad(out);nrmp=pad(nrm);mrp=pad(mr)
cv2.imwrite('/home/claude/w/alb_top.png',cv2.cvtColor(np.clip(outp,0,255).astype(np.uint8),cv2.COLOR_RGB2BGR))
np.savez_compressed('tex_parts.npz',alb=np.clip(outp,0,255).astype(np.uint8),nrm=nrmp.astype(np.uint8),met=mrp[...,2].astype(np.uint8),cov=cov,head=headm)
cv2.imwrite('/home/claude/w/tat_crop2.png',cv2.cvtColor(np.clip(outp,0,255).astype(np.uint8)[1300:1700,820:1320],cv2.COLOR_RGB2BGR))
cv2.imwrite('/home/claude/w/tat_small2.png',cv2.cvtColor(cv2.resize(np.clip(outp,0,255).astype(np.uint8),(1024,1024),interpolation=cv2.INTER_AREA),cv2.COLOR_RGB2BGR))
print('ok')
