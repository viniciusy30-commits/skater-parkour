import numpy as np,cv2
def tattoo_mask(img):
    f=img.astype(np.float32);r,g,b=f[...,0],f[...,1],f[...,2];lum=0.299*r+0.587*g+0.114*b
    skin=((r>185)&(r-b>28)&(lum>150)).astype(np.uint8)
    k=cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(15,15))
    closed=cv2.morphologyEx(skin,cv2.MORPH_CLOSE,k)
    dens=cv2.blur(skin.astype(np.float32),(21,21))
    ink=(closed>0)&(skin==0)&(dens>0.42)&(lum<175)&(lum>55)&((r-b)<75)
    # alpha suave pela escuridao
    a=np.clip((175-lum)/55,0,1)*ink
    a=cv2.GaussianBlur(a.astype(np.float32),(0,0),0.7)
    return a,skin
if __name__=='__main__':
    img=cv2.cvtColor(cv2.imread('/home/claude/w/img0.jpg'),cv2.COLOR_BGR2RGB)
    a,skin=tattoo_mask(img)
    out=img.astype(np.float32)
    red=np.array([196,26,34],np.float32)
    lum=(0.299*out[...,0]+0.587*out[...,1]+0.114*out[...,2])[...,None]
    tint=red*(0.55+0.65*np.clip(lum/150,0,1.2))
    out=out*(1-a[...,None])+tint*a[...,None]
    out=np.clip(out,0,255).astype(np.uint8)
    cv2.imwrite('/home/claude/w/tat_test.png',cv2.cvtColor(out,cv2.COLOR_RGB2BGR))
    cv2.imwrite('/home/claude/w/tat_crop.png',cv2.cvtColor(out[1300:1700,820:1320],cv2.COLOR_RGB2BGR))
    cv2.imwrite('/home/claude/w/tat_small.png',cv2.cvtColor(cv2.resize(out,(1024,1024),interpolation=cv2.INTER_AREA),cv2.COLOR_RGB2BGR))
