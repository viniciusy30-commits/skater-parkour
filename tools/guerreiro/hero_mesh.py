import numpy as np, json, struct
SC=2.0
M=json.load(open('meta.json')); PREL=np.array(M['axe']['pre']).reshape(4,4).T
def load(fn):
    b=open(fn,'rb').read(); nv,nt=struct.unpack('<2i',b[:8]); o=8
    P=np.frombuffer(b,'<f4',nv*3,o).reshape(-1,3).astype(np.float64); o+=nv*12
    C=np.frombuffer(b,'<f4',nv*3,o).reshape(-1,3).astype(np.float64); o+=nv*12
    I=np.frombuffer(b,'<i4',nt*3,o).reshape(-1,3).astype(np.int64); o+=nt*12
    JW=np.frombuffer(b,'u1',nv*8,o).reshape(-1,8); o+=nv*8
    Mt=np.frombuffer(b,'<f4',nv,o).astype(np.float64)
    return P,C,I,JW,Mt
bP,bC,bI,bJW,bM=load('hero/body_dec.bin'); aP,aC,aI,aJW,aM=load('hero/axe_dec.bin')
aPl=(PREL[:3,:3]@aP.T).T+PREL[:3,3]
def part(P,C,I,JW,Mt,joint=None):
    J=JW[:,:4].astype(np.uint8).copy(); W=JW[:,4:].astype(np.uint8).copy()
    if joint is not None: J[:]=0; J[:,0]=joint; W[:]=0; W[:,0]=255
    return P*SC,C,I,J,W,Mt
parts=[part(bP,bC,bI,bJW,bM),part(aP,aC,aI,aJW,aM,42),part(aPl,aC,aI,aJW,aM,15)]
P=[];C=[];I=[];J=[];W=[];Mt=[];off=0
for p,c,i,j,w,m in parts:
    P.append(p);C.append(c);I.append(i+off);J.append(j);W.append(w);Mt.append(m);off+=len(p)
P=np.concatenate(P);C=np.concatenate(C);I=np.concatenate(I);J=np.concatenate(J);W=np.concatenate(W);Mt=np.concatenate(Mt)
Wf=W.astype(np.float64); s=Wf.sum(1,keepdims=True); s[s==0]=1; Wf=Wf/s*255
Wi=np.floor(Wf).astype(np.int64); r=255-Wi.sum(1); Wi[np.arange(len(Wi)),Wf.argmax(1)]+=r
W=Wi.astype(np.uint8)
# machado esquerdo é espelhado (det PREL = +1 mas a malha é a mesma): conferir orientação dos triângulos depois
a,b,c=P[I[:,0]],P[I[:,1]],P[I[:,2]]; fn=np.cross(b-a,c-a)
N=np.zeros_like(P)
for k in range(3):
    for d in range(3): N[:,d]+=np.bincount(I[:,k],weights=fn[:,d],minlength=len(P))
L=np.linalg.norm(N,axis=1); N=N/np.maximum(L[:,None],1e-12)
nbv=len(parts[0][0]); cen=np.array([0,0.9,0]); outward=(P[:nbv]-cen)
print('orientação média das normais (corpo):',np.sign((N[:nbv]*outward).sum(1)).mean())
np.save('hero/out/arrays.npy',dict(P=P,C=C,I=I,J=J,W=W,Mt=Mt,N=N,nb=nbv,na=len(parts[1][0])),allow_pickle=True)
print('verts',len(P),'tris',len(I))
