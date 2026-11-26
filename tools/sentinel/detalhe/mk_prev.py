import sys,struct,base64,json;sys.path.insert(0,'/home/claude/b')
import numpy as np
from handfix import SK as SK0
import os
SK=np.load(os.environ['SKF']) if os.environ.get('SKF') else SK0
d='/home/claude/b/dec/out/'
b=open(d+'char.msh','rb').read();_,nv,ni,st=struct.unpack('<4i',b[:16]);ctr=np.frombuffer(b,'<f4',3,16);ext=np.frombuffer(b,'<f4',3,28)
dt=np.dtype([('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)])
R=np.frombuffer(b,dt,nv,40);idx=np.frombuffer(b,'<u4',ni,40+nv*28)
P=ctr+R['p'][:,:3]/32767.0*ext;N=R['n'][:,:3]/32767.0;met=R['n'][:,3]/32767.0;J=R['j'].astype(int);Wt=R['w']/255.0
M=np.einsum('vk,vkab->vab',Wt,SK[J]);Ps=np.einsum('vab,vb->va',M,np.c_[P,np.ones(nv)])[:,:3];Ns=np.einsum('vab,vb->va',M[:,:3,:3],N)
Ns/=np.linalg.norm(Ns,axis=1,keepdims=True)+1e-9
vb=np.zeros((nv,9),np.float32);vb[:,0:3]=Ps;vb[:,3:6]=Ns;vb[:,6:8]=R['uv']/65535.0;vb[:,8]=met
np.save('rest_pos.npy',Ps)
print('rest bbox',Ps.min(0),Ps.max(0))
html=open('tpl.html').read()
html=html.replace('%FS%',open('fs.glsl').read()).replace('%VB%',base64.b64encode(vb.tobytes()).decode()).replace('%IB%',base64.b64encode(idx.astype('<u4').tobytes()).decode())
html=html.replace('%ALB%',base64.b64encode(open(d+'char_atlas.jpg','rb').read()).decode()).replace('%NRM%',base64.b64encode(open(d+'char_nrm.png','rb').read()).decode())
open('prev.html','w').write(html)
