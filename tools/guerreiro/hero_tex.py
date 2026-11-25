# Monta o char.msh COM UV (atlas assado) a partir de: body76.bin/axe6.bin (qemw) e atlas_part0/1.bin (bake)
import numpy as np, json, struct
SC=2.0
M=json.load(open('meta.json')); PREL=np.array(M['axe']['pre']).reshape(4,4).T
def load(fn):
    b=open(fn,'rb').read(); nv,nt=struct.unpack('<2i',b[:8]); o=8
    P=np.frombuffer(b,'<f4',nv*3,o).reshape(-1,3).astype(np.float64); o+=nv*12
    o+=nv*12
    I=np.frombuffer(b,'<i4',nt*3,o).reshape(-1,3).astype(np.int64); o+=nt*12
    JW=np.frombuffer(b,'u1',nv*8,o).reshape(-1,8); o+=nv*8
    Mt=np.frombuffer(b,'<f4',nv,o).astype(np.float64)
    return P,I,JW,Mt
def loadatlas(fn):
    b=open(fn,'rb').read(); nc,nt=struct.unpack('<2i',b[:8]); o=8
    vid=np.frombuffer(b,'<i4',nc,o).astype(np.int64); o+=nc*4
    uv=np.frombuffer(b,'<f4',nc*2,o).reshape(-1,2).astype(np.float64); o+=nc*8
    T=np.frombuffer(b,'<i4',nt*3,o).reshape(-1,3).astype(np.int64)
    return vid,uv,T
bP,bI,bJW,bM=load('hero/body76.bin'); aP,aI,aJW,aM=load('hero/axe6.bin')
aPl=(PREL[:3,:3]@aP.T).T+PREL[:3,3]
b0=loadatlas('hero/atlas_part0.bin'); b1=loadatlas('hero/atlas_part1.bin')
def build(P,I,JW,Mt,at,joint=None):
    vid,uv,T=at
    # normais suaves por vértice decimado (média ponderada pela área), depois copiadas aos cantos
    a,b,c=P[I[:,0]],P[I[:,1]],P[I[:,2]]; fn=np.cross(b-a,c-a)
    N=np.zeros_like(P)
    for k in range(3):
        for d in range(3): N[:,d]+=np.bincount(I[:,k],weights=fn[:,d],minlength=len(P))
    N=N/np.maximum(np.linalg.norm(N,axis=1),1e-12)[:,None]
    J=JW[vid,:4].astype(np.uint8).copy(); W=JW[vid,4:].astype(np.uint8).copy()
    if joint is not None: J[:]=0; J[:,0]=joint; W[:]=0; W[:,0]=255
    return P[vid]*SC,N[vid],uv,J,W,Mt[vid],T
parts=[build(bP,bI,bJW,bM,b0),build(aP,aI,aJW,aM,b1,42),build(aPl,aI,aJW,aM,b1,15)]
Pp=[];Np=[];Up=[];Jp=[];Wp=[];Mp=[];Ip=[];off=0
for p,n,uv,j,w,m,t in parts:
    Pp.append(p);Np.append(n);Up.append(uv);Jp.append(j);Wp.append(w);Mp.append(m);Ip.append(t+off);off+=len(p)
P=np.concatenate(Pp);N=np.concatenate(Np);UV=np.concatenate(Up);J=np.concatenate(Jp);W=np.concatenate(Wp);Mt=np.concatenate(Mp);I=np.concatenate(Ip)
Wf=W.astype(np.float64); s=Wf.sum(1,keepdims=True); s[s==0]=1; Wf=Wf/s*255
Wi=np.floor(Wf).astype(np.int64); r=255-Wi.sum(1); Wi[np.arange(len(Wi)),Wf.argmax(1)]+=r; W=Wi.astype(np.uint8)
nbv=len(parts[0][0]); outward=P[:nbv]-np.array([0,0.9,0])
print('orientação média das normais (corpo):',np.sign((N[:nbv]*outward).sum(1)).mean())
lo=P.min(0); hi=P.max(0); ctr=(lo+hi)/2; ext=(hi-lo)/2*1.001; nv=len(P)
q=np.clip(np.round((P-ctr)/ext*32767),-32767,32767).astype('<i2'); nq=np.clip(np.round(N*32767),-32767,32767).astype('<i2')
rec=np.zeros(nv,dtype=[('p','<i2',4),('n','<i2',4),('uv','<u2',2),('j','u1',4),('w','u1',4)])
rec['p'][:,:3]=q; rec['n'][:,:3]=nq; rec['n'][:,3]=np.clip(Mt*32767+.5,0,32767).astype('<i2')
rec['uv']=np.clip(np.round(UV*65535),0,65535).astype('<u2'); rec['j']=J; rec['w']=W
assert rec.itemsize==28
idx=I.astype('<u4').reshape(-1)
import os; os.makedirs('hero/out',exist_ok=True)
with open('hero/out/char.msh','wb') as f:
    f.write(struct.pack('<4i',0,nv,len(idx),28)); f.write(struct.pack('<3f',*ctr)); f.write(struct.pack('<3f',*ext)); f.write(rec.tobytes()); f.write(idx.tobytes())
print('ctr',ctr.round(3),'ext',ext.round(3),'nv',nv,'tris',len(idx)//3,'maxJ',J.max(),'wsum ok',(W.astype(int).sum(1)==255).all())
