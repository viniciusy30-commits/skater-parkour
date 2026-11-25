import json, numpy as np, re, base64, sys
from PIL import Image
Image.MAX_IMAGE_PIXELS=None
B='/home/claude/build/'
s=open('/mnt/user-data/uploads/warlord_v20.html',encoding='utf-8').read()
i=s.index('const META=')+11
M,e=json.JSONDecoder().raw_decode(s[i:])
json.dump(M,open(B+'meta.json','w'))
i=s.index('const B64="')+11; j=s.index('"',i)
raw=base64.b64decode(s[i:j]); open(B+'blob.bin','wb').write(raw); print('blob',len(raw))
S=M['secs']
def sec(n,dt): a,l=S[n]; return np.frombuffer(raw[a:a+l],dtype=dt)
i=s.index('const IMG=[')+10; j=s.index('];',i)
imgs=re.findall(r'"([A-Za-z0-9+/=]+)"',s[i:j+1]); print(len(imgs))
mimes=['jpg','png','jpg','jpg','jpg','jpg']
H=B+'hero/'
for k,b in enumerate(imgs):
    open(f'{H}img{k}.{mimes[k]}','wb').write(base64.b64decode(b))
pos=sec('pos','<f4').reshape(-1,3); uv=sec('uv','<u2').reshape(-1,2)/65535.0
jnt=sec('jnt','u1').reshape(-1,4); wgt=sec('wgt','u1').reshape(-1,4)
idx=sec('idx','<u4'); idx2=sec('idx2','<u4'); n=len(pos); nm=M['axe']['nmain']
def tex(k): return np.asarray(Image.open(f'{H}img{k}.{mimes[k]}').convert('RGB'),dtype=np.float32)/255.
def sample(t,uvs):
    Hh,W,_=t.shape; u=uvs[:,0]*W-0.5; v=uvs[:,1]*Hh-0.5
    x0=np.floor(u).astype(int); y0=np.floor(v).astype(int); fx=(u-x0)[:,None]; fy=(v-y0)[:,None]
    g=lambda x,y:t[np.mod(y,Hh),np.mod(x,W)]
    return (g(x0,y0)*(1-fx)+g(x0+1,y0)*fx)*(1-fy)+(g(x0,y0+1)*(1-fx)+g(x0+1,y0+1)*fx)*fy
for part,(a,b,tb,tr) in {'body':(0,nm,0,1),'axe':(nm,n,3,4)}.items():
    c=sample(tex(tb),uv[a:b])**2.2
    rm=sample(tex(tr),uv[a:b])
    col=(np.sqrt(np.clip(c,0,1))*255+.5).astype(np.uint8); met=(np.clip(rm[:,2],0,1)*255+.5).astype(np.uint8)
    col.tofile(f'{H}{part}_col.u8'); met.tofile(f'{H}{part}_met.u8')
    pos[a:b].astype('<f4').tofile(f'{H}{part}_pos.f32')
    np.concatenate([jnt[a:b],wgt[a:b]],1).astype(np.uint8).tofile(f'{H}{part}_jw.u8')
    print(part,col.mean(0),met.mean())
(idx-0).astype('<u4').tofile(H+'body_idx.u32'); (idx2-nm).astype('<u4').tofile(H+'axe_idx.u32')
print(idx.max()<nm, idx2.min()>=nm, 'tris',len(idx)//3,len(idx2)//3)
