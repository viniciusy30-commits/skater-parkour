import numpy as np, subprocess, sys, math
import numpy as np, subprocess, sys, math
rng=np.random.default_rng(7)
mesh='stage1.bin'
f=open(mesh,'rb'); nv,nt=np.frombuffer(f.read(8),dtype=np.int32)
P=np.frombuffer(f.read(nv*12),dtype=np.float32).reshape(-1,3); f.read(nv*12)
I=np.frombuffer(f.read(nt*12),dtype=np.int32).reshape(-1,3)
a,b,c=P[I[:,0]],P[I[:,1]],P[I[:,2]]
n=np.cross(b-a,c-a); ar=np.linalg.norm(n,axis=1)+1e-30; n/=ar[:,None]
cen=(a+b+c)/3
up=(np.abs(n[:,1])>0.8)&(ar>2e-8)
S=1/38.0   # 1 m em unidades
vis=np.zeros(nt,bool)
# semente: piso da arena
seeds=[]
for r in np.linspace(0,0.40,5):
    for k in range(int(max(1,r*60))):
        t=2*math.pi*k/max(1,int(r*60)); seeds.append((r*math.cos(t),-0.195,r*math.sin(t)))
cand_used=np.zeros(nt,bool)
def run(points):
    L=[]
    for (x,y,z) in points:
        for yaw in range(6):
            yw=rng.uniform(0,2*math.pi); pi=rng.uniform(-0.4,0.3)
            e=(x,y+rng.uniform(1.6,6.0)*S,z)
            L.append("%f %f %f %f %f %f"%(e+(e[0]+math.cos(yw)*math.cos(pi),e[1]+math.sin(pi),e[2]+math.sin(yw)*math.cos(pi))))
    open('views.txt','w').write("\n".join(L))
    subprocess.run(['./rast',mesh,'vis','views.txt','vis.u8','400','225'],check=True,stderr=subprocess.DEVNULL)
    return np.fromfile('vis.u8',dtype=np.uint8).astype(bool)
vis|=run(seeds)
print('seed',vis.sum(),flush=True)
for it in range(int(sys.argv[1])):
    cand=np.where(up&vis&~cand_used)[0]
    if len(cand)==0: break
    pick=rng.choice(cand,size=min(len(cand),int(sys.argv[2])),replace=False)
    cand_used[pick]=True
    # marca também vizinhos aproximados para não repetir a mesma região
    pts=cen[pick]
    v=run(pts)
    vis|=v
    print('passo',it,'visíveis',vis.sum(),'de',nt,flush=True)
np.save('vis_flags.npy',vis)
