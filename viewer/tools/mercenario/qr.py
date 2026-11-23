import numpy as np, io
from PIL import Image
def tex_colors(img_bytes, uv, flip=False):
    im=Image.open(io.BytesIO(img_bytes)).convert('RGB'); a=np.asarray(im); h,w,_=a.shape
    u=np.clip(uv[:,0],0,1); v=np.clip(uv[:,1],0,1)
    return a[np.clip((v*(h-1)).astype(int),0,h-1), np.clip((u*(w-1)).astype(int),0,w-1)]
def render(P, C, view='front', size=700, ext=None, center=None, ps=2, bg=(30,32,38), extra=None):
    """P Nx3 (y up, +Z forward). view: front (camera at +Z), back, left (camera at +X), right, top"""
    P=np.asarray(P,float)
    if view=='front': R=np.array([[1,0,0],[0,1,0],[0,0,1]]); flipx=True
    X={'front':(-P[:,0],P[:,1],P[:,2]),'back':(P[:,0],P[:,1],-P[:,2]),'left':(P[:,2],P[:,1],-P[:,0]),'right':(-P[:,2],P[:,1],P[:,0]),'top':(-P[:,0],-P[:,2],P[:,1])}[view]
    x,y,z=X
    if center is None: center=((x.min()+x.max())/2,(y.min()+y.max())/2)
    if ext is None: ext=max(x.max()-x.min(),y.max()-y.min())*0.55
    sx=((x-center[0])/ext*0.5+0.5)*size; sy=(0.5-(y-center[1])/ext*0.5)*size
    img=np.empty((size,size,3),np.uint8); img[:]=bg; zb=np.full((size,size),-1e9)
    order=np.argsort(z)  # far first -> near overwrite... use zbuffer anyway
    ix=sx.astype(int); iy=sy.astype(int)
    for dx in range(-(ps//2),ps-(ps//2)):
        for dy in range(-(ps//2),ps-(ps//2)):
            jx=ix+dx; jy=iy+dy; ok=(jx>=0)&(jx<size)&(jy>=0)&(jy<size)
            jx=jx[ok]; jy=jy[ok]; zz=z[ok]; cc=C[ok]
            if len(zz)==0: continue
            o=np.argsort(zz)  # ascending: later writes win => nearest
            # emulate zbuffer: keep max z per pixel
            key=jy*size+jx; idx=np.lexsort((zz,key)); key=key[idx]; zz=zz[idx]; cc=cc[idx]
            last=np.r_[key[1:]!=key[:-1],True]
            k=key[last]; yy=k//size; xx=k%size
            upd=zz[last]>zb[yy,xx]
            img[yy[upd],xx[upd]]=cc[last][upd]; zb[yy[upd],xx[upd]]=zz[last][upd]
    return img
def sheet(imgs, path, cols=None):
    cols=cols or len(imgs); rows=(len(imgs)+cols-1)//cols; h,w,_=imgs[0].shape
    S=np.zeros((rows*h,cols*w,3),np.uint8)
    for i,im in enumerate(imgs): S[(i//cols)*h:(i//cols+1)*h,(i%cols)*w:(i%cols+1)*w]=im
    Image.fromarray(S).save(path)
