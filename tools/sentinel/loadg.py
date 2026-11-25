import json,struct,numpy as np,io
G='/home/claude/ironbound.glb'
b=open(G,'rb').read(); cl,ct=struct.unpack_from('<II',b,12); J=json.loads(b[20:20+cl]); o=20+cl+8; BIN=b[o:]
def acc(i):
    a=J['accessors'][i]; bv=J['bufferViews'][a['bufferView']]
    n={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}[a['type']]
    dt={5126:'<f4',5125:'<u4',5123:'<u2',5121:'u1'}[a['componentType']]
    off=bv.get('byteOffset',0)+a.get('byteOffset',0)
    return np.frombuffer(BIN,dt,a['count']*n,off).reshape(a['count'],n)
def image(k):
    bv=J['bufferViews'][J['images'][k]['bufferView']]; off=bv.get('byteOffset',0)
    return BIN[off:off+bv['byteLength']]
N=J['nodes']; JN=J['skins'][0]['joints']
P=acc(0).astype(np.float64); NR=acc(1).astype(np.float64); UV=acc(2).astype(np.float64)
IDX=acc(3).reshape(-1,3).astype(np.int64); W=acc(4).astype(np.float64); JJ=acc(5).astype(np.int64)
IBM=acc(6).reshape(-1,4,4).transpose(0,2,1)  # column-major -> matrices
names=[N[i].get('name') for i in JN]
