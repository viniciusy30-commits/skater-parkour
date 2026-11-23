"""Gera o pacote do Mercenario (manifest.json + blob.bin) no mesmo formato do guerreiro v98."""
import sys, os, re, io, json, pickle, struct, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from retarget import *
from weapons import *
from PIL import Image

WORK = os.path.dirname(os.path.abspath(__file__))
SCR = os.path.dirname(WORK)

S = Source(SCR + '/all/Demo/Character/Grruzam_Character_Skeleton.uasset')
tp = pickle.load(open(WORK + '/target.pkl', 'rb')); T = Target(tp)
A = pickle.load(open(WORK + '/anims.pkl', 'rb'))
raw = pickle.load(open(WORK + '/blades_raw.pkl', 'rb'))
sFr = anat_frames(S.restWP, S.ix, 'spine_03')
compute_neutral(S, A)

# ------------------------------------------------------------------ selecao de clipes
CATS = ['Parado', 'Andar', 'Correr', 'Esquiva', 'Pulo', 'Agachar', 'Giros', 'Defesa', 'Ataques', 'Combos', 'Especiais', 'Dano', 'Queda', 'Morte']
def category(rel):
    d = rel.split('/')
    if d[0] == '1_Movements':
        s = d[1]
        if s.startswith('0__') or s.startswith('1__'): return 'Parado'
        if s.startswith('2__'): return 'Andar'
        if s.startswith('3__') or s.startswith('4__'): return 'Correr'
        if s.startswith('5__'): return 'Esquiva'
        if s.startswith('6__') or s.startswith('7__'): return 'Pulo'
        if s.startswith('8__'): return 'Agachar'
        if s.startswith('9__'): return 'Giros'
        if s.startswith('Blocking'): return 'Defesa'
    if d[0] == '2_Attacks':
        n = int(re.match(r'(\d+)__', d[1]).group(1))
        return 'Combos' if n <= 5 else 'Ataques'
    if d[0] == '3_Skills': return 'Especiais'
    if d[0] == '4_Damages':
        if d[1].startswith('6__'): return 'Queda'
        if d[1].startswith('7__'): return 'Morte'
        return 'Dano'
    raise ValueError(rel)

def base_name(fn):
    n = os.path.basename(fn)[:-7].replace('Dual_Blade_', '')
    n = re.sub(r'_ZeroHeight(_Feet|_Z0)?$', '', n); n = re.sub(r'_Feet$', '', n)
    var = 'plain'
    if n.endswith('_Root'): n = n[:-5]; var = 'root'
    n = n.replace('__Root', '')
    return n, var

def pick():
    groups = {}
    for rel, a in A.items():
        n, var = base_name(rel)
        if 'ZeroHeight' in rel or rel.endswith('_Feet.uasset'): var = 'zh'
        key = (category(rel), n)
        pri = {'plain': 0, 'root': 1, 'zh': 2}[var]
        # jump-attack / double jump: preferir a variante normal; _Feet e _Z0 so se nao houver outra
        if key not in groups or pri < groups[key][0]: groups[key] = (pri, rel)
    return {k: v[1] for k, v in groups.items()}

def pretty(n):
    n = n.replace('__', ' ').replace('_', ' ').replace('-', '-')
    n = re.sub(r'\bTo\b', '→', n)
    n = re.sub(r'\s+', ' ', n).strip()
    n = n.replace('Attack ', '').replace('Idle → Idle', 'Idle')
    return n

# ------------------------------------------------------------------ ground clamp
rs = np.random.RandomState(1)
Qb = tp['Q']
sub = np.unique(np.r_[np.where(Qb[:, 1] < 0.14)[0][::2], rs.choice(len(Qb), 3500, replace=False)])
tps = dict(tp); tps['Q'] = Qb[sub]; tps['J'] = tp['J'][sub]; tps['Wt'] = tp['Wt'][sub]
from viz import target_world, skin_points

def ground_offsets(Q, PEL, nf):
    low = np.zeros(nf)
    for f in range(nf):
        W = target_world(T, Q, PEL, f); P = skin_points(tps, T, W); low[f] = P[:, 1].min()
    off = np.maximum(0, -low)
    k = np.array([1, 2, 3, 2, 1], float); k /= k.sum()
    pad = np.r_[[off[0]] * 2, off, [off[-1]] * 2]
    return np.convolve(pad, k, 'valid')

# ------------------------------------------------------------------ retarget
sel = pick()
order = sorted(sel.items(), key=lambda kv: (CATS.index(kv[0][0]), kv[1]))
clips = []
for (cat, n), rel in order:
    a = A[rel]; nf = a['nf']
    Q, PEL = retarget_clip(S, T, a, sFr)
    off = ground_offsets(Q, PEL, nf)
    # y mundial += off  -> coordenada local do pai (root rotacionado): usa rootR^T
    dy = (T.restWR[0].T @ np.array([0, 1, 0.0]))
    PEL = PEL + off[:, None] * dy[None, :]
    clips.append(dict(name=pretty(n), cat=cat, nf=nf, fps=30.0, Q=Q, PEL=PEL.astype(np.float32), src=rel))
print('clips', len(clips))
pickle.dump(clips, open(WORK + '/merc_clips.pkl', 'wb'))

# ------------------------------------------------------------------ meshes
def quant_mesh(P, N, UV, tri, J=None, W=None, RW=None):
    mn = P.min(0); mx = P.max(0); ctr = (mn + mx) / 2; ext = np.maximum((mx - mn) / 2, 1e-6) * 1.0005
    st = 28 if J is not None else 20
    n = len(P); vb = np.zeros((n, st), np.uint8)
    pos = np.zeros((n, 4), '<i2'); pos[:, :3] = np.round((P - ctr) / ext * 32767)
    if RW is not None: pos[:, 3] = np.round(np.clip(RW / 0.12, 0, 1) * 32767)
    nr = np.zeros((n, 4), '<i2'); nn = N / np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-9); nr[:, :3] = np.round(nn * 32767)
    uv = np.clip(np.round(UV * 65535), 0, 65535).astype('<u2')
    vb[:, 0:8] = pos.view(np.uint8).reshape(n, 8); vb[:, 8:16] = nr.view(np.uint8).reshape(n, 8); vb[:, 16:20] = uv.view(np.uint8).reshape(n, 4)
    if J is not None:
        w8 = np.round(W * 255).astype(int)
        d = 255 - w8.sum(1); w8[np.arange(n), w8.argmax(1)] += d
        vb[:, 20:24] = J.astype(np.uint8); vb[:, 24:28] = w8.astype(np.uint8)
    ib = tri.astype('<u4').ravel()
    return dict(vb=vb.tobytes(), ib=ib.tobytes(), nv=n, ni=len(ib), ctr=ctr.tolist(), ext=ext.tolist())

def jpeg_resize(b, size, q=88):
    im = Image.open(io.BytesIO(b)).convert('RGB')
    if im.size[0] != size: im = im.resize((size, size), Image.LANCZOS)
    o = io.BytesIO(); im.save(o, 'JPEG', quality=q, optimize=True, progressive=False); return o.getvalue()

def _segd(p, a, b):
    ab = b - a; t = np.clip(((p - a) @ ab) / (ab @ ab), 0, 1); return np.linalg.norm(p - (a + t[:, None] * ab), axis=1)
_P = lambda n: tp['Wb'][T.ix[n]][:3, 3]
_dd = np.minimum.reduce([_segd(tp['Q'], _P(a_), _P(b_)) for sd in 'lr' for a_, b_ in ((('thigh_' + sd), ('calf_' + sd)), (('calf_' + sd), ('foot_' + sd)), (('foot_' + sd), ('ball_' + sd)))])
RW = np.where(tp['Q'][:, 1] < 0.6, np.minimum(_dd, 0.12), 0.0)
char = quant_mesh(tp['Q'], tp['N'], tp['UV'], tp['idx'].reshape(-1, 3), tp['J'], tp['Wt'], RW)
blades = {s: quant_mesh(*(lambda B: (B['P'], B['N'], B['UV'], B['tri']))(blade_local(raw, k)))
          for s, k in (('l', 0), ('r', 1))}
char_tex = tp['tex']
blade_tex = jpeg_resize(raw['img'], 2048, 90)

# ------------------------------------------------------------------ ataches
att = {}
for s in 'lr':
    Rw, pw, Fs, Ft = attach(S, T, s)
    Wr = np.eye(4); Wr[:3, :3] = Rw; Wr[:3, 3] = pw
    hi = T.ix['hand_' + s]
    m = np.linalg.inv(tp['Wb'][hi]) @ Wr
    att[s] = dict(bone=hi, m=m.T.ravel().tolist())     # coluna-maior

# ------------------------------------------------------------------ blob
blob = io.BytesIO()
def put(b, align=4):
    while blob.tell() % align: blob.write(b'\0')
    o = blob.tell(); blob.write(b); return [o, len(b)]
man = {}
man['bones'] = dict(names=T.names, parent=[int(p) for p in T.par],
                    rest=[[float(x) for x in T.rest_q[i]] + [float(x) for x in T.rest_t[i]] for i in range(len(T.names))],
                    wpre=T.wpre.T.ravel().tolist())
drv = [T.ix[k] for k in T.driven]; man['driven'] = drv; man['pelvis'] = T.ix['pelvis']
c = dict(nv=char['nv'], ni=char['ni'], ctr=char['ctr'], ext=char['ext'])
c['vb'] = put(char['vb']); c['ib'] = put(char['ib']); c['tex'] = put(char_tex); man['char'] = c
man['weapons'] = []
wt = put(blade_tex)
for s in 'lr':
    b = blades[s]; w = dict(side=s, nv=b['nv'], ni=b['ni'], ctr=b['ctr'], ext=b['ext'], bone=att[s]['bone'], m=att[s]['m'])
    w['vb'] = put(b['vb']); w['ib'] = put(b['ib']); w['tex'] = wt; man['weapons'].append(w)
man['cap'] = [[T.ix[n] for n in ('thigh_' + sd, 'calf_' + sd, 'foot_' + sd, 'ball_' + sd)] for sd in 'lr']
man['cats'] = CATS
man['clips'] = []
for cl in clips:
    pt = put(cl['PEL'].astype('<f4').tobytes(), 4)[0]
    qq = np.round(cl['Q'] / np.maximum(np.linalg.norm(cl['Q'], axis=2, keepdims=True), 1e-9) * 32767).astype('<i2')
    q = put(qq.tobytes(), 4)[0]
    man['clips'].append(dict(name=cl['name'], cat=cl['cat'], nf=cl['nf'], fps=cl['fps'], pt=pt, q=q))
open(WORK + '/merc_manifest.json', 'w').write(json.dumps(man, separators=(',', ':')))
open(WORK + '/merc_blob.bin', 'wb').write(blob.getvalue())
print('blob MB %.1f  char vb %.1f ib %.1f tex %.1f  blades tex %.2f' % (blob.tell() / 1e6, len(char['vb']) / 1e6, len(char['ib']) / 1e6, len(char_tex) / 1e6, len(blade_tex) / 1e6))
print('driven', len(drv), 'clips', len(man['clips']))
