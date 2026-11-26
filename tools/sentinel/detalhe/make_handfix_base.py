# gera handfix_base.py a partir de ../add_weapon.py (linhas 13-40 e 60-118)
L=open('/home/claude/proj/codigo/tools/sentinel/add_weapon.py').read().split('\n')
g=lambda a,b:'\n'.join(L[a-1:b])
hdr="""import numpy as np, struct, json, io, os, sys
from scipy.spatial.transform import Rotation as Rot
HERE='/home/claude/b'; ASSET='/home/claude/b/'
HANDS='rl'; FLIP=1; CURL=1; SC=0.45; TILT=12.0; HS=1.0; GRIP_R=0.7; YG=-0.43
"""
open('/home/claude/b/handfix_base.py','w').write(hdr+g(13,40)+'\n'+g(60,118)+'\n')
