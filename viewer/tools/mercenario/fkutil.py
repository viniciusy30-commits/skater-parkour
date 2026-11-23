import numpy as np
def qm(q):
    x,y,z,w=q; n=np.sqrt(x*x+y*y+z*z+w*w); x,y,z,w=x/n,y/n,z/n,w/n
    return np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])
def mq(R):
    t=np.trace(R)
    if t>0:
        s=np.sqrt(t+1)*2; w=s/4; x=(R[2,1]-R[1,2])/s; y=(R[0,2]-R[2,0])/s; z=(R[1,0]-R[0,1])/s
    elif R[0,0]>R[1,1] and R[0,0]>R[2,2]:
        s=np.sqrt(1+R[0,0]-R[1,1]-R[2,2])*2; w=(R[2,1]-R[1,2])/s; x=s/4; y=(R[0,1]+R[1,0])/s; z=(R[0,2]+R[2,0])/s
    elif R[1,1]>R[2,2]:
        s=np.sqrt(1+R[1,1]-R[0,0]-R[2,2])*2; w=(R[0,2]-R[2,0])/s; x=(R[0,1]+R[1,0])/s; y=s/4; z=(R[1,2]+R[2,1])/s
    else:
        s=np.sqrt(1+R[2,2]-R[0,0]-R[1,1])*2; w=(R[1,0]-R[0,1])/s; x=(R[0,2]+R[2,0])/s; y=(R[1,2]+R[2,1])/s; z=s/4
    q=np.array([x,y,z,w]); return q/np.linalg.norm(q)
def fk(names,parents,rots,poss):
    """rots: dict name->3x3 or list; returns world R[n,3,3], P[n,3]"""
    n=len(names); WR=np.zeros((n,3,3)); WP=np.zeros((n,3))
    for i in range(n):
        p=parents[i]
        if p<0: WR[i]=rots[i]; WP[i]=poss[i]
        else: WR[i]=WR[p]@rots[i]; WP[i]=WR[p]@poss[i]+WP[p]
    return WR,WP
