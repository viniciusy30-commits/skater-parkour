import numpy as np, struct
A=np.load('hero/out/arrays.npy',allow_pickle=True).item()
P,C,I,J,W,Mt,N=A['P'],A['C'],A['I'],A['J'],A['W'],A['Mt'],A['N']
lo=P.min(0); hi=P.max(0); ctr=(lo+hi)/2; ext=(hi-lo)/2*1.001
nv=len(P)
q=np.clip(np.round((P-ctr)/ext*32767),-32767,32767).astype('<i2')
nq=np.clip(np.round(N*32767),-32767,32767).astype('<i2')
rec=np.zeros(nv,dtype=[('p','<i2',4),('n','<i2',4),('c','u1',4),('j','u1',4),('w','u1',4)])
rec['p'][:,:3]=q; rec['n'][:,:3]=nq
col=np.clip(np.sqrt(np.clip(C,0,1))*255+.5,0,255).astype(np.uint8)   # C é linear -> gama 2.0
rec['c'][:,:3]=col; rec['c'][:,3]=np.clip(Mt*255+.5,0,255).astype(np.uint8)
rec['j']=J; rec['w']=W
assert rec.itemsize==28, rec.itemsize
idx=I.astype('<u4').reshape(-1)
with open('hero/out/char.msh','wb') as f:
    f.write(struct.pack('<4i',0,nv,len(idx),28)); f.write(struct.pack('<3f',*ctr)); f.write(struct.pack('<3f',*ext))
    f.write(rec.tobytes()); f.write(idx.tobytes())
print('ctr',ctr.round(3),'ext',ext.round(3),'nv',nv,'idx',len(idx), 'max joint',J.max(), 'wsum ok',(W.astype(int).sum(1)==255).all())
