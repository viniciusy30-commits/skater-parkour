"""Le todos os .uasset de animacao do pacote (pasta 'Animations/DualBlade') e salva anims.pkl.
uso: extract_anims.py <pasta_do_pacote> <saida.pkl>"""
import sys, glob, pickle, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from uasset import read_anim
root = sys.argv[1].rstrip('/') + '/Animations/DualBlade/'
out = {}
for f in sorted(glob.glob(root + '**/*.uasset', recursive=True)):
    try:
        a = read_anim(f)
        out[f[len(root):]] = dict(nf=a['nf'], length=a['length'], fps=a['fps'], tracks={k: (v[0], v[1]) for k, v in a['tracks'].items()}, rate=a['rate'])
    except Exception as e:
        print('falhou', f, e)
pickle.dump(out, open(sys.argv[2], 'wb')); print(len(out), 'animacoes')
