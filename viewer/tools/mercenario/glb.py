import json,struct,numpy as np
CT={5120:'i1',5121:'u1',5122:'i2',5123:'u2',5125:'u4',5126:'f4'}
NC={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4,'MAT4':16}
class GLB:
    def __init__(s,path):
        d=open(path,'rb').read(); s.d=d
        cl,_=struct.unpack('<II',d[12:20]); s.j=json.loads(d[20:20+cl])
        o=20+cl; bl,_=struct.unpack('<II',d[o:o+8]); s.bin=d[o+8:o+8+bl]
    def bv(s,i):
        v=s.j['bufferViews'][i]; o=v.get('byteOffset',0); return s.bin[o:o+v['byteLength']]
    def acc(s,i):
        a=s.j['accessors'][i]; v=s.j['bufferViews'][a['bufferView']]
        n=NC[a['type']]; dt=np.dtype('<'+CT[a['componentType']])
        o=v.get('byteOffset',0)+a.get('byteOffset',0)
        st=v.get('byteStride') or n*dt.itemsize
        if st==n*dt.itemsize:
            arr=np.frombuffer(s.bin,dt,a['count']*n,o).reshape(a['count'],n)
        else:
            arr=np.stack([np.frombuffer(s.bin,dt,n,o+k*st) for k in range(a['count'])])
        arr=arr.copy()
        if a.get('normalized'):
            m={'i1':127,'u1':255,'i2':32767,'u2':65535}[CT[a['componentType']]]; arr=np.maximum(arr/m,-1) if CT[a['componentType']][0]=='i' else arr/m
        return arr
    def image(s,i): return s.bv(s.j['images'][i]['bufferView'])

def node_local(n):
    if 'matrix' in n: return np.array(n['matrix'],float).reshape(4,4).T
    t=np.array(n.get('translation',[0,0,0])); x,y,z,w=n.get('rotation',[0,0,0,1]); s=np.array(n.get('scale',[1,1,1]))
    R=np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])
    M=np.eye(4); M[:3,:3]=R*s; M[:3,3]=t; return M
def world_mats(j):
    nodes=j['nodes']; par={}
    for i,n in enumerate(nodes):
        for c in n.get('children',[]): par[c]=i
    W={}
    def wm(i):
        if i not in W: W[i]=(wm(par[i]) if i in par else np.eye(4))@node_local(nodes[i])
        return W[i]
    for i in range(len(nodes)): wm(i)
    return W,par
