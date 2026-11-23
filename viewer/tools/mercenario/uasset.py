"""Leitor minimo de .uasset (UE 4.27, assets de editor) para extrair animacoes brutas e esqueletos."""
import struct

class R:
    def __init__(s, d, o=0): s.d = d; s.o = o
    def i32(s): v = struct.unpack_from('<i', s.d, s.o)[0]; s.o += 4; return v
    def u32(s): v = struct.unpack_from('<I', s.d, s.o)[0]; s.o += 4; return v
    def i64(s): v = struct.unpack_from('<q', s.d, s.o)[0]; s.o += 8; return v
    def u16(s): v = struct.unpack_from('<H', s.d, s.o)[0]; s.o += 2; return v
    def u8(s): v = s.d[s.o]; s.o += 1; return v
    def f32(s): v = struct.unpack_from('<f', s.d, s.o)[0]; s.o += 4; return v
    def f64(s): v = struct.unpack_from('<d', s.d, s.o)[0]; s.o += 8; return v
    def raw(s, n): v = s.d[s.o:s.o + n]; s.o += n; return v
    def fstr(s):
        n = s.i32()
        if n == 0: return ''
        if n < 0:
            n = -n; v = s.d[s.o:s.o + n * 2 - 2].decode('utf-16le'); s.o += n * 2; return v
        v = s.d[s.o:s.o + n - 1].decode('latin1'); s.o += n; return v

class Pkg:
    def __init__(s, data):
        s.d = data; r = R(data)
        assert r.u32() == 0x9e2a83c1
        legacy = r.i32(); assert legacy == -7, legacy
        r.i32(); s.ver = r.i32(); r.i32()
        for _ in range(r.i32()): r.raw(20)
        s.total = r.i32(); r.fstr()
        s.pflags = r.u32()
        s.nname = r.i32(); s.oname = r.i32()
        if not (s.pflags & 0x80000000): r.fstr()
        r.i32(); r.i32()  # gatherable
        s.nexp = r.i32(); s.oexp = r.i32(); s.nimp = r.i32(); s.oimp = r.i32()
        # names
        r2 = R(data, s.oname); s.names = []
        for _ in range(s.nname):
            s.names.append(r2.fstr()); r2.raw(4)
        # imports
        r2 = R(data, s.oimp); s.imports = []
        for _ in range(s.nimp):
            cp = s.fname(r2); cn = s.fname(r2); outer = r2.i32(); on = s.fname(r2)
            if not (s.pflags & 0x80000000): r2.raw(8)
            s.imports.append(dict(cls=cn, name=on, outer=outer))
        # exports
        r2 = R(data, s.oexp); s.exports = []
        for _ in range(s.nexp):
            e = {}
            e['cls'] = r2.i32(); e['super'] = r2.i32(); r2.i32(); e['outer'] = r2.i32()
            e['name'] = s.fname(r2); r2.u32()
            e['size'] = r2.i64(); e['off'] = r2.i64()
            r2.raw(12 + 16 + 4 + 4 + 4 + 20)
            s.exports.append(e)
        for e in s.exports:
            c = e['cls']
            e['clsname'] = s.imports[-c - 1]['name'] if c < 0 else (s.exports[c - 1]['name'] if c > 0 else 'Class')

    def fname(s, r):
        i = r.i32(); n = r.i32()
        nm = s.names[i]
        return nm if n == 0 else '%s_%d' % (nm, n - 1)

    def props(s, r, end):
        """Le propriedades marcadas; retorna dict nome->valor(ou None) e deixa r.o apos o 'None'."""
        out = {}
        while r.o < end:
            name = s.fname(r)
            if name == 'None': break
            typ = s.fname(r); size = r.i32(); r.i32()  # array index
            sub = None
            if typ == 'StructProperty': sub = s.fname(r); r.raw(16)
            elif typ == 'BoolProperty': bval = bool(r.u8())
            elif typ in ('ByteProperty', 'EnumProperty'): sub = s.fname(r)
            elif typ == 'ArrayProperty': sub = s.fname(r)
            elif typ == 'SetProperty': sub = s.fname(r)
            elif typ == 'MapProperty': s.fname(r); s.fname(r)
            guid = r.u8()
            if guid: r.raw(16)
            start = r.o
            val = None
            if typ == 'BoolProperty': val = bval
            if typ == 'IntProperty': val = struct.unpack_from('<i', r.d, start)[0]
            elif typ == 'FloatProperty': val = struct.unpack_from('<f', r.d, start)[0]
            elif typ == 'NameProperty': val = s.fname(R(r.d, start))
            elif typ == 'StrProperty': val = R(r.d, start).fstr()
            elif typ == 'ArrayProperty':
                rr = R(r.d, start); n = rr.i32()
                if sub == 'NameProperty': val = [s.fname(rr) for _ in range(n)]
                elif sub == 'IntProperty': val = [rr.i32() for _ in range(n)]
                elif sub == 'FloatProperty': val = [rr.f32() for _ in range(n)]
                else: val = ('array', sub, n, start + 4, size - 4)
            elif typ == 'ByteProperty' and size == 1: val = r.d[start]
            elif typ == 'ByteProperty' and size == 8: val = s.fname(R(r.d, start))
            elif typ == 'EnumProperty': val = s.fname(R(r.d, start))
            elif typ == 'StructProperty': val = ('struct', sub, start, size)
            elif typ == 'ObjectProperty': val = R(r.d, start).i32()
            out[name] = val
            r.o = start + size
        return out

    def export_data(s, i):
        e = s.exports[i]
        return R(s.d, e['off']), e['off'] + e['size']


def read_anim(path):
    """Retorna dict(name, nf, fps, length, tracks={bone:(pos[N,3] ou [1,3], rot[N,4 xyzw], ...)}, skeleton_ref)"""
    import numpy as np
    p = Pkg(open(path, 'rb').read())
    idx = [i for i, e in enumerate(p.exports) if e['clsname'] == 'AnimSequence'][0]
    r, end = p.export_data(idx)
    pr = p.props(r, end)
    r.o += 22
    n = r.i32()
    tracks = []
    for t in range(n):
        es = r.i32(); npk = r.i32(); pk = np.frombuffer(r.raw(12 * npk), '<f4').reshape(-1, 3) if npk else np.zeros((0, 3), 'f4')
        es = r.i32(); nrk = r.i32(); rk = np.frombuffer(r.raw(16 * nrk), '<f4').reshape(-1, 4) if nrk else np.zeros((0, 4), 'f4')
        es = r.i32(); nsk = r.i32(); sk = np.frombuffer(r.raw(12 * nsk), '<f4').reshape(-1, 3) if nsk else np.zeros((0, 3), 'f4')
        tracks.append((pk, rk, sk))
    names = pr['AnimationTrackNames']
    assert len(names) == n, (len(names), n)
    return dict(nf=pr.get('NumFrames'), length=pr.get('SequenceLength'), fps=pr.get('ImportResampleFramerate', 30),
                tracks=dict(zip(names, tracks)), props=pr, endpos=r.o, end=end,
                rate=pr.get('RateScale'))


def read_skeleton(path):
    """Retorna (names, parents, rest[(quat xyzw, pos, scale)])"""
    import numpy as np
    p = Pkg(open(path, 'rb').read())
    idx = [i for i, e in enumerate(p.exports) if e['clsname'] == 'Skeleton'][0]
    r, end = p.export_data(idx)
    p.props(r, end)
    r.o += 4                 # hasguid
    n = r.i32()              # bones
    names = []; parents = []
    for _ in range(n):
        nm = p.fname(r); par = r.i32(); r.fstr(); names.append(nm); parents.append(par)
    # nomes: 'ExportName' FString; apos: RefBonePose
    cnt = r.i32(); assert cnt == n, (cnt, n)
    rest = []
    for _ in range(n):
        q = struct.unpack_from('<4f', r.d, r.o); t = struct.unpack_from('<3f', r.d, r.o + 16); s = struct.unpack_from('<3f', r.d, r.o + 28)
        r.o += 40; rest.append((q, t, s))
    return names, parents, rest
