import sys,base64,numpy as np
d=np.load('/home/claude/b/dec/hp_app.npz');B=np.load('/home/claude/b/dec/body.npz')
def build(P,N,UV,I,name):
    nv=len(P);vb=np.zeros((nv,9),np.float32);vb[:,0:3]=P;vb[:,3:6]=N;vb[:,6]=UV[:,0];vb[:,7]=UV[:,1]*0.5;vb[:,8]=1
    html=open('tpl.html').read()
    o='/home/claude/b/dec/out/'
    html=html.replace('%FS%',open('fs.glsl').read()).replace('%VB%',base64.b64encode(vb.tobytes()).decode()).replace('%IB%',base64.b64encode(I.reshape(-1).astype('<u4').tobytes()).decode())
    html=html.replace('%ALB%',base64.b64encode(open(o+'char_atlas.jpg','rb').read()).decode()).replace('%NRM%',base64.b64encode(open(o+'char_nrm.png','rb').read()).decode())
    open(name,'w').write(html)
build(d['P'],d['N'],d['UV'],d['IDX'],'cmp_orig.html')
build(B['Pbind'],B['N'],B['UV'],B['I'],'cmp_dec.html')
