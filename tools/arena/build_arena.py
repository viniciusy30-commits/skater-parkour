import numpy as np, struct, math
from PIL import Image

S = 38.0
CX, CZ, FY = 0.00024, 0.001, -0.2079
f = open('stage3.bin', 'rb')
nv, nt = np.frombuffer(f.read(8), dtype=np.int32)
P = np.frombuffer(f.read(nv * 12), dtype=np.float32).reshape(-1, 3).astype(np.float64)
C = np.frombuffer(f.read(nv * 12), dtype=np.float32).reshape(-1, 3).astype(np.float64)
I = np.frombuffer(f.read(nt * 12), dtype=np.int32).reshape(-1, 3).astype(np.int64)
P[:, 0] = (P[:, 0] - CX) * S; P[:, 1] = (P[:, 1] - FY) * S; P[:, 2] = (P[:, 2] - CZ) * S
print('verts', nv, 'tris', nt)

# ---- faces
a, b, c = P[I[:, 0]], P[I[:, 1]], P[I[:, 2]]
fn = np.cross(b - a, c - a); ar = np.linalg.norm(fn, axis=1); ok = ar > 1e-12
I = I[ok]; fn = fn[ok] / ar[ok, None]; ar = ar[ok]; nt = len(I)

# ---- suaviza cores (acaba com o facetado dos triângulos grandes)
E = np.concatenate([I[:, [0, 1]], I[:, [1, 2]], I[:, [2, 0]]])
E = np.concatenate([E, E[:, ::-1]])
for it in range(2):
    s = np.zeros_like(C); n = np.zeros(nv)
    for k in range(3):
        s[:, k] = np.bincount(E[:, 0], weights=C[E[:, 1], k], minlength=nv)
    n = np.bincount(E[:, 0], minlength=nv).astype(np.float64)
    m = np.where(n[:, None] > 0, s / np.maximum(n[:, None], 1), C)
    C = 0.5 * C + 0.5 * m

# ---- normais
vs = np.zeros((nv, 3))
for k in range(3):
    for j in range(3):
        vs[:, j] += np.bincount(I[:, k], weights=fn[:, j] * ar, minlength=nv)
vl = np.linalg.norm(vs, axis=1); vs = vs / np.maximum(vl[:, None], 1e-12)

# ---- oclusão ambiente a partir das caixas de colisão (sólido)
col = np.fromfile('arena_col.bin', dtype=np.float32)
nb = int(np.frombuffer(col[:1].tobytes(), dtype=np.int32)[0]); B = col[1:].reshape(-1, 6)
G = 0.3; ox, oy, oz = -38.0, -14.0, -38.0
gx, gy, gz = int(76 / G), int(40 / G), int(76 / G)
occ = np.zeros((gx, gy, gz), bool)
for bx in B:
    x0 = int((bx[0] - ox) / G); x1 = int(math.ceil((bx[3] - ox) / G)); y0 = int((bx[1] - oy) / G); y1 = int(math.ceil((bx[4] - oy) / G)); z0 = int((bx[2] - oz) / G); z1 = int(math.ceil((bx[5] - oz) / G))
    occ[max(0, x0):x1, max(0, y0):y1, max(0, z0):z1] = True
rng = np.random.default_rng(3)
D = 16
dirs = rng.normal(size=(D, 3)); dirs /= np.linalg.norm(dirs, axis=1, keepdims=True)
steps = np.array([0.5, 1.0, 1.7, 2.6, 3.8, 5.5, 8.0])
ao = np.ones(nv)
CH = 40000
for s0 in range(0, nv, CH):
    pp = P[s0:s0 + CH]; nn = vs[s0:s0 + CH]
    blocked = np.zeros(len(pp)); tot = np.zeros(len(pp))
    for d in dirs:
        dd = np.broadcast_to(d, nn.shape).copy()
        flip = (dd * nn).sum(1) < 0; dd[flip] *= -1
        w = np.clip((dd * nn).sum(1), 0.15, 1)
        hit = np.zeros(len(pp), bool)
        for st in steps:
            q = pp + nn * 0.45 + dd * st
            ix = ((q[:, 0] - ox) / G).astype(int); iy = ((q[:, 1] - oy) / G).astype(int); iz = ((q[:, 2] - oz) / G).astype(int)
            inb = (ix >= 0) & (ix < gx) & (iy >= 0) & (iy < gy) & (iz >= 0) & (iz < gz)
            h = np.zeros(len(pp), bool); h[inb] = occ[ix[inb], iy[inb], iz[inb]]
            hit |= h
        blocked += hit * w; tot += w
    ao[s0:s0 + CH] = 1 - blocked / np.maximum(tot, 1e-6)
ao = np.clip(ao, 0, 1)
ao = ao ** 0.8
print('AO médio', ao.mean())

# ---- normais com quinas vivas e vértices divididos
def octenc(n):
    l1 = np.abs(n).sum(1, keepdims=True); p = n[:, :2] / l1
    neg = n[:, 2] < 0
    px = np.where(neg, (1 - np.abs(p[:, 1])) * np.where(p[:, 0] >= 0, 1, -1), p[:, 0])
    py = np.where(neg, (1 - np.abs(p[:, 0])) * np.where(p[:, 1] >= 0, 1, -1), p[:, 1])
    return np.stack([px, py], 1)

cv = I.reshape(-1); cf = np.repeat(np.arange(nt), 3)
dot = (fn[cf] * vs[cv]).sum(1)
smooth = dot > math.cos(math.radians(38))
nrm = np.where(smooth[:, None], vs[cv], fn[cf])
oc = octenc(nrm)
q = np.clip(np.round(oc * 127), -127, 127).astype(np.int64)
key = cv * 65536 + (q[:, 0] + 128) * 256 + (q[:, 1] + 128)
uk, first, inv = np.unique(key, return_index=True, return_inverse=True)
nvn = len(uk); print('vértices após quinas', nvn)
vid = cv[first]
newI = inv.reshape(-1, 3)
VP = P[vid]; VQ = q[first]; VC = C[vid]; VA = ao[vid]

# ---- blocos por célula XZ (12 m) para corte por frustum
cen = VP[newI].mean(1)
CELL = 12.0
cx_ = np.floor((cen[:, 0] + 40) / CELL).astype(np.int64); cz_ = np.floor((cen[:, 2] + 40) / CELL).astype(np.int64)
cy_ = np.floor((cen[:, 1] + 14) / 12.0).astype(np.int64)
ck = (cx_ * 64 + cz_) * 8 + cy_
order = np.argsort(ck, kind='stable')
newI = newI[order]; ck = ck[order]
# reordena vértices pelo primeiro uso
flat = newI.reshape(-1)
_, fi = np.unique(flat, return_index=True)
ordv = flat[np.sort(fi)]
remap = np.empty(nvn, np.int64); remap[ordv] = np.arange(len(ordv))
newI = remap[newI]
VP = VP[ordv]; VQ = VQ[ordv]; VC = VC[ordv]; VA = VA[ordv]
bounds = np.flatnonzero(np.diff(ck)) + 1
starts = np.concatenate([[0], bounds]); ends = np.concatenate([bounds, [len(ck)]])
chunks = []
for s0, e0 in zip(starts, ends):
    vv = VP[newI[s0:e0].reshape(-1)]
    chunks.append((s0 * 3, (e0 - s0) * 3, vv.min(0), vv.max(0)))
print('blocos', len(chunks))

mn = VP.min(0); mx = VP.max(0); ctr = (mn + mx) / 2; ext = (mx - mn) / 2 * 1.0001
qp = np.clip(np.round((VP - ctr) / ext * 32767), -32767, 32767).astype(np.int16)
col8 = np.clip(np.round(np.sqrt(np.clip(VC, 0, 1)) * 255), 0, 255).astype(np.uint8)
ao8 = np.clip(np.round(VA * 255), 0, 255).astype(np.uint8)
vbuf = np.zeros((len(VP), 12), np.uint8)
vbuf[:, 0:6] = qp.view(np.uint8).reshape(-1, 6)
vbuf[:, 6:8] = VQ.astype(np.int8).view(np.uint8)
vbuf[:, 8:11] = col8; vbuf[:, 11] = ao8
with open('arena.msh', 'wb') as o:
    o.write(struct.pack('<4i', 0x41524E32, len(VP), newI.size, len(chunks)))
    o.write(struct.pack('<6f', *ctr, *ext))
    for (fi_, cnt, lo, hi) in chunks:
        o.write(struct.pack('<2i6f', fi_, cnt, *lo, *hi))
    o.write(vbuf.tobytes())
    o.write(newI.astype(np.uint32).tobytes())
print('arena.msh', len(VP), 'verts', newI.shape[0], 'tris', 'ctr', ctr, 'ext', ext)

# ---- textura de detalhe (tileável): R grão, G manchas, B veios, A rachaduras
N = 512
def fnoise(beta, seed):
    r = np.random.default_rng(seed)
    F = np.fft.fft2(r.normal(size=(N, N)))
    fx = np.fft.fftfreq(N)[:, None]; fy = np.fft.fftfreq(N)[None, :]
    f = np.sqrt(fx * fx + fy * fy); f[0, 0] = 1
    F = F / f ** beta; F[0, 0] = 0
    x = np.real(np.fft.ifft2(F)); x = (x - x.mean()) / x.std()
    return x
R = np.clip(0.5 + 0.18 * fnoise(1.4, 1), 0, 1)
Gc = np.clip(0.5 + 0.2 * fnoise(2.4, 2), 0, 1)
vn = fnoise(2.0, 3); Bc = np.clip(1 - np.abs(vn) * 2.2, 0, 1) ** 3
cr = fnoise(1.8, 4); Ac = np.clip(1 - np.abs(cr) * 3.0, 0, 1) ** 4
img = np.stack([R, Gc, np.maximum(Ac, 0.5 * Bc)], 2)
Image.fromarray((img * 255).astype(np.uint8), 'RGB').save('arena_detail.png', optimize=True)
print('ok')
