import json, numpy as np
from PIL import Image
Image.MAX_IMAGE_PIXELS=None
B='/home/claude/build/'; H=B+'hero/'
M=json.load(open(B+'meta.json')); raw=open(B+'blob.bin','rb').read(); S=M['secs']
def sec(n,dt): a,l=S[n]; return np.frombuffer(raw[a:a+l],dtype=dt)
uv=sec('uv','<u2').reshape(-1,2)/65535.0
n=len(uv); nm=M['axe']['nmain']
mimes=['jpg','png','jpg','jpg','jpg','jpg']
def img(k): return Image.open(f'{H}img{k}.{mimes[k]}').convert('RGB')
for part,(a,b,tb,tr) in {'body':(0,nm,0,1),'axe':(nm,n,3,4)}.items():
    uv[a:b].astype('<f4').tofile(f'{H}{part}_uv.f32')
    t=np.asarray(img(tb),dtype=np.float32)/255.
    c=(np.clip(t,0,1)**1.1*255+.5).astype(np.uint8)
    with open(f'{H}{part}_tcol.raw','wb') as f: np.array([c.shape[1],c.shape[0]],'<i4').tofile(f); c.tofile(f)
    r=np.asarray(img(tr))[:,:,2]
    with open(f'{H}{part}_tmet.raw','wb') as f: np.array([r.shape[1],r.shape[0]],'<i4').tofile(f); r.astype(np.uint8).tofile(f)
    print(part,c.shape,r.shape,uv[a:b].min(0),uv[a:b].max(0))
