"""Retarget das animacoes do pacote (UE, esqueleto de 72 ossos) para o rig Tripo (80 ossos) do Mercenario.
Metodo: frames anatomicos (aim + roll definido pela palma/pe/quadril) -> independe da pose de repouso (A vs T)."""
import numpy as np, pickle, glob, sys
sys.path.insert(0, '/tmp/claude-0/-home-user-skater-parkour/19329d18-9da6-5d89-afba-e925a710541c/scratchpad/work')
from fkutil import qm, mq, fk
from uasset import read_skeleton
from glb import GLB, world_mats

# UE (esq=+X, cima=+Z, frente=+Y)  ->  glTF (esq=+X, cima=+Y, frente=+Z)
M = np.array([[1, 0, 0], [0, 0, 1], [0, 1, 0]], float)

def unit(v):
    n = np.linalg.norm(v, axis=-1, keepdims=True); return v / np.maximum(n, 1e-9)

def frame(e1, e2):
    """colunas: e1, e2 ortogonalizado, e3. e1,e2: (...,3)"""
    e1 = unit(e1); e2 = e2 - (e2 * e1).sum(-1, keepdims=True) * e1; e2 = unit(e2); e3 = np.cross(e1, e2)
    return np.stack([e1, e2, e3], -1)

def slerp_R(Ra, Rb, u):
    qa = mq(Ra); qb = mq(Rb)
    if qa @ qb < 0: qb = -qb
    q = qa * (1 - u) + qb * u; q /= np.linalg.norm(q); return qm(q)

class Source:
    def __init__(self, skel_path):
        self.names, self.par, rest = read_skeleton(skel_path)
        self.ix = {n: i for i, n in enumerate(self.names)}
        self.rest_q = [np.array(r[0]) for r in rest]; self.rest_t = [np.array(r[1]) for r in rest]
        R = [qm(q) for q in self.rest_q]
        WR, WP = fk(self.names, self.par, R, self.rest_t)
        self.restWR = np.einsum('ij,njk,lk->nil', M, WR, M); self.restWP = WP @ M.T   # glTF
        self.pel_h = self.restWP[self.ix['pelvis'], 1]

    def pose(self, anim, f):
        """FK da pose do quadro f (espaco glTF). Ossos ausentes na faixa usam o repouso."""
        R = []; T = []
        for i, k in enumerate(self.names):
            tr = anim['tracks'].get(k)
            if k == 'root' and tr is not None: tr = (np.zeros((1, 3), 'f4'), tr[1])   # sem root motion
            if tr is None: R.append(qm(self.rest_q[i])); T.append(self.rest_t[i]); continue
            p, q = tr; R.append(qm(q[min(f, len(q) - 1)])); T.append(p[min(f, len(p) - 1)] if len(p) else self.rest_t[i])
        WR, WP = fk(self.names, self.par, R, T)
        return np.einsum('ij,njk,lk->nil', M, WR, M), WP @ M.T

def hand_normal(P, ix, s):
    """normal da palma (consistente entre esqueletos): (pinky-index) x (middle-hand), sinal pelo lado"""
    a = P[ix['pinky_01_' + s]] - P[ix['index_01_' + s]]; b = P[ix['middle_01_' + s]] - P[ix['hand_' + s]]
    n = unit(np.cross(a, b))
    return n

# ---------------------------------------------------------------- frames anatomicos
# Cada entrada: osso -> (tipo, args). Posicoes P (n,3) por indice de nome.
ARM = ['clavicle', 'upperarm', 'lowerarm', 'hand']
FING = ['index', 'middle', 'ring', 'pinky']

def anat_frames(P, ix, chest):
    """Retorna dict osso->3x3 (frame anatomico) para os ossos nao-folha comuns aos dois esqueletos."""
    g = lambda k: P[ix[k]]
    F = {}
    up = unit(g('neck_01') - g('pelvis'))
    lr = unit(g('thigh_l') - g('thigh_r'))
    F['pelvis'] = frame(up, lr)
    cl = unit(g('clavicle_l') - g('clavicle_r'))
    F['chest'] = frame(g('neck_01') - g(chest), cl)
    F['neck_01'] = frame(g('head') - g('neck_01'), cl)
    for s in 'lr':
        n = hand_normal(P, ix, s)
        F['clavicle_' + s] = frame(g('upperarm_' + s) - g('clavicle_' + s), up)
        F['upperarm_' + s] = frame(g('lowerarm_' + s) - g('upperarm_' + s), n)
        F['lowerarm_' + s] = frame(g('hand_' + s) - g('lowerarm_' + s), n)
        F['hand_' + s] = frame(g('middle_01_' + s) - g('hand_' + s), n)
        for fg in FING:
            F[fg + '_01_' + s] = frame(g(fg + '_02_' + s) - g(fg + '_01_' + s), n)
            F[fg + '_02_' + s] = frame(g(fg + '_03_' + s) - g(fg + '_02_' + s), n)
        F['thumb_01_' + s] = frame(g('thumb_02_' + s) - g('thumb_01_' + s), n)
        F['thumb_02_' + s] = frame(g('thumb_03_' + s) - g('thumb_02_' + s), n)
        fw = g('ball_' + s) - g('foot_' + s)
        F['thigh_' + s] = frame(g('calf_' + s) - g('thigh_' + s), fw)
        F['calf_' + s] = frame(g('foot_' + s) - g('calf_' + s), fw)
        F['foot_' + s] = frame(fw, g('foot_' + s) - g('calf_' + s))
    return F


# ---------------------------------------------------------------- alvo (Tripo)
SPINE_T = ['spine_01', 'spine_02', 'spine_03', 'spine_04', 'spine_05']
LEAF = {}   # osso_alvo -> chave do frame do pai (para folhas)
for s in 'lr':
    LEAF['head'] = 'neck_01'
    for fg in FING + ['thumb']:
        LEAF[fg + '_03_' + s] = fg + '_02_' + s
    LEAF['ball_' + s] = 'foot_' + s
DIRECT = ['neck_01', 'pelvis']
for s in 'lr':
    DIRECT += ['clavicle_' + s, 'upperarm_' + s, 'lowerarm_' + s, 'hand_' + s, 'thigh_' + s, 'calf_' + s, 'foot_' + s]
    for fg in FING:
        DIRECT += [fg + '_01_' + s, fg + '_02_' + s]
    DIRECT += ['thumb_01_' + s, 'thumb_02_' + s]

class Target:
    def __init__(self, tp):
        self.names = tp['names']; self.par = tp['parent']; self.ix = {n: i for i, n in enumerate(self.names)}
        Wb = tp['Wb']; self.wpre = tp['wpre']; self.restWR = Wb[:, :3, :3].copy(); self.restWP = Wb[:, :3, 3].copy()
        self.rest_q = [r[0] for r in tp['rest']]; self.rest_t = [r[1] for r in tp['rest']]
        self.restL = [qm(q) for q in self.rest_q]
        self.F = anat_frames(self.restWP, self.ix, 'spine_05')
        # alturas relativas para repartir a coluna
        y0 = self.restWP[self.ix['pelvis'], 1]; y1 = self.restWP[self.ix['neck_01'], 1]
        self.u = {k: np.clip((self.restWP[self.ix[k], 1] - y0) / (y1 - y0), 0, 1) for k in SPINE_T}
        Fp, Fc = self.F['pelvis'], self.F['chest']
        self.Fk = {}
        for k in SPINE_T: self.Fk[k] = slerp_R(Fp, Fc, self.u[k])
        self.Fk['neck_02'] = self.F['neck_01']
        # offsets constantes O_t = F_t^-1 * Wt_rest
        self.O = {}
        for k in DIRECT: self.O[k] = self.F[k].T @ self.restWR[self.ix[k]]
        for k in SPINE_T + ['neck_02']: self.O[k] = self.Fk[k].T @ self.restWR[self.ix[k]]
        for k, pk in LEAF.items(): self.O[k] = self.F[pk].T @ self.restWR[self.ix[k]]
        self.driven = [k for k in self.names if k in self.O]
        self.rootW = np.eye(4)
        self.leg = self.restWP[self.ix['pelvis'], 1] - self.restWP[self.ix['foot_l'], 1]

def retarget_clip(S, T, anim, sFr):
    """anim: dict tracks; retorna (q[nf,nd,4] xyzw locais, pel[nf,3] translacao local do pelvis, driven names)"""
    nf = anim['nf']
    ndr = len(T.driven)
    Q = np.zeros((nf, ndr, 4), np.float32); PEL = np.zeros((nf, 3), np.float32)
    Wroot = T.wpre  # root (rest) tem rotacao propria; usamos o repouso do root
    rootR = T.restWR[0]; rootP = T.restWP[0]
    scale = T.leg / (S.restWP[S.ix['pelvis'], 1] - S.restWP[S.ix['foot_l'], 1])
    Fs_rest = sFr
    prevR = {}
    for f in range(nf):
        WRs, WPs = S.pose(anim, f)
        Fs = anat_frames(WPs, S.ix, 'spine_03')
        Wt = {}
        for k in DIRECT: Wt[k] = Fs[k] @ T.O[k]
        for k in SPINE_T: Wt[k] = slerp_R(Fs['pelvis'], Fs['chest'], T.u[k]) @ T.O[k]
        Wt['neck_02'] = Fs['neck_01'] @ T.O['neck_02']
        for k, pk in LEAF.items():
            Dl = WRs[S.ix[k]] @ S.restWR[S.ix[k]].T          # delta mundial da folha
            Wt[k] = (Dl @ Fs_rest[pk]) @ T.O[k]
        # rotacoes locais (ossos nao dirigidos seguem o pai com o repouso local)
        WR = [None] * len(T.names)
        for i, k in enumerate(T.names):
            p = T.par[i]
            if k in Wt: WR[i] = Wt[k]
            elif p < 0: WR[i] = T.restWR[i]
            else: WR[i] = WR[p] @ T.restL[i]
        for j, k in enumerate(T.driven):
            i = T.ix[k]; p = T.par[i]
            L = WR[p].T @ WR[i]
            Q[f, j] = mq(L)
        # translacao do pelvis (sem root motion)
        pel_s = WPs[S.ix['pelvis']].copy()
        pel_rest = S.restWP[S.ix['pelvis']]
        # remove deslocamento do root da anim (root motion): subtrai posicao do root
        pel_world = T.restWP[T.ix['pelvis']] + (pel_s - pel_rest) * scale
        # coordenadas locais no pai (root): rootR^T (p - rootP)
        PEL[f] = T.restWR[0].T @ (pel_world - T.restWP[0])
    # continuidade de sinal dos quaternios
    for j in range(ndr):
        for f in range(1, nf):
            if Q[f, j] @ Q[f - 1, j] < 0: Q[f, j] = -Q[f, j]
    return Q, PEL
