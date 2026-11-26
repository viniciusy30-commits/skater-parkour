import numpy as np,struct,cv2
from PIL import Image
B=np.load('body.npz');P=B['P'];N=B['N'];UV=B['UV'];I=B['I'];J=B['J'];W=B['W']
nv=len(P)
cur=open('/home/claude/proj/codigo/app/src/main/assets/w/char.msh','rb').read()
_,cnv,cni,st=struct.unpack('<4i',cur[:16]);cctr=np.frombuffer(cur,'<f4',3,16);cext=np.frombuffer(cur,'<f4',3,28)
dt=np.dtype([('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)])
R=np.frombuffer(cur,dt,cnv,40);cidx=np.frombuffer(cur,'<u4',cni,40+cnv*28).reshape(-1,3).astype(np.int64)
NB0=24411
wpos=cctr+R['p'][NB0:,:3].astype(np.float64)/32767*cext
wI=cidx[18961:]-NB0+nv
allP=np.concatenate([P,wpos]);lo=allP.min(0);hi=allP.max(0);ctr=(lo+hi)/2;ext=(hi-lo)/2*1.001
tot=nv+len(wpos)
r=np.zeros(tot,dt)
q=lambda x:np.clip(np.round(x),-32767,32767).astype('<i2')
r['p'][:nv,:3]=q((P-ctr)/ext*32767);r['n'][:nv,:3]=q(N*32767);r['n'][:nv,3]=32767
uvq=np.stack([UV[:,0],UV[:,1]*0.5],1);r['uv'][:nv]=np.clip(np.round(uvq*65535),0,65535).astype('<u2')
# pesos 4 influencias soma 255
Wo=W/np.maximum(W.sum(1,keepdims=True),1e-9)*255;Wq=np.floor(Wo).astype(np.int64);Wq[np.arange(nv),0]+=255-Wq.sum(1)
r['j'][:nv]=J.astype(np.uint8);r['w'][:nv]=Wq.astype(np.uint8)
# armas (copiadas) com pos requantizada para o novo ctr/ext
r['p'][nv:,:3]=q((wpos-ctr)/ext*32767);r['n'][nv:]=R['n'][NB0:];r['uv'][nv:]=R['uv'][NB0:];r['j'][nv:]=R['j'][NB0:];r['w'][nv:]=R['w'][NB0:]
idx=np.concatenate([I.reshape(-1,3).astype(np.int64),wI]).reshape(-1)
with open('out/char.msh','wb') as f:
    f.write(struct.pack('<4i',0,tot,len(idx),28));f.write(struct.pack('<3f',*ctr));f.write(struct.pack('<3f',*ext));f.write(r.tobytes());f.write(idx.astype('<u4').tobytes())
print('verts',tot,'tris',len(idx)//3,'body tris',len(I),'size MB',(40+tot*28+len(idx)*4)/1e6,'ctr',ctr,'ext',ext)
T=np.load('tex_parts.npz')
curat=np.array(Image.open('/home/claude/proj/codigo/app/src/main/assets/w/char_atlas.jpg').convert('RGB'))
at=np.zeros((4096,2048,3),np.uint8);at[:2048]=T['alb'];at[2048:]=curat[2048:]
Image.fromarray(at).save('out/char_atlas.jpg',quality=94,subsampling=0)
nm=np.zeros((4096,2048,3),np.uint8);nm[:2048,:,0]=T['nrm'][...,0];nm[:2048,:,1]=T['nrm'][...,1];nm[:2048,:,2]=T['met']
nm[2048:]=[128,128,255]
Image.fromarray(nm).save('out/char_nrm.png',optimize=True)
import os;print({f:os.path.getsize('out/'+f)/1e6 for f in os.listdir('out')})
