// Gerador de atlas + assador de textura do guerreiro.
// A malha reduzida (qemw) não tem UV; aqui cada par de triângulos vizinhos vira um bloco retangular de um atlas,
// e a cor/metal de cada texel é amostrada da malha original (alta resolução, com a textura de verdade) no ponto mais próximo.
// uso: bake A gutter maxBlock out_prefix  N_PARTES  [por parte: oPos.f32 oUV.f32 oIdx.u32 col.raw met.raw dec.bin]
//   col.raw: int w,h + RGB8; met.raw: int w,h + gray8; dec.bin: saída do qemw (nv nt P C I JW met)
// saída: out_prefix_col.raw / _met.raw (A*A) e out_prefix_part<k>.bin (n corners: int vid,float u,float v ; depois triângulos int x3)
#include <bits/stdc++.h>
using namespace std;
typedef array<double, 3> V3;
static inline V3 sub(const V3 &a, const V3 &b) { return {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
static inline V3 add(const V3 &a, const V3 &b) { return {a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
static inline V3 mul(const V3 &a, double s) { return {a[0] * s, a[1] * s, a[2] * s}; }
static inline double dot(const V3 &a, const V3 &b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
static inline V3 cross(const V3 &a, const V3 &b) { return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
static inline double len(const V3 &a) { return sqrt(dot(a, a)); }

struct Tex { int w = 0, h = 0; vector<unsigned char> d; };
static bool loadTex(const char *fn, Tex &t, int ch) {
    FILE *f = fopen(fn, "rb"); if (!f) return false; int hd[2]; if (fread(hd, 4, 2, f) != 2) return false; t.w = hd[0]; t.h = hd[1];
    t.d.resize((size_t)t.w * t.h * ch); size_t r = fread(t.d.data(), 1, t.d.size(), f); fclose(f); return r == t.d.size();
}
static void sampleTex(const Tex &t, int ch, double u, double v, double *o) {
    u -= floor(u); v -= floor(v);
    double x = u * t.w - 0.5, y = v * t.h - 0.5; int x0 = (int)floor(x), y0 = (int)floor(y); double fx = x - x0, fy = y - y0;
    for (int k = 0; k < ch; k++) {
        auto g = [&](int xx, int yy) { xx = ((xx % t.w) + t.w) % t.w; yy = ((yy % t.h) + t.h) % t.h; return (double)t.d[((size_t)yy * t.w + xx) * ch + k]; };
        o[k] = (g(x0, y0) * (1 - fx) + g(x0 + 1, y0) * fx) * (1 - fy) + (g(x0, y0 + 1) * (1 - fx) + g(x0 + 1, y0 + 1) * fx) * fy;
    }
}

// ponto mais próximo no triângulo (Ericson) com baricêntricas
static V3 closestTri(const V3 &p, const V3 &a, const V3 &b, const V3 &c, double *bw) {
    V3 ab = sub(b, a), ac = sub(c, a), ap = sub(p, a);
    double d1 = dot(ab, ap), d2 = dot(ac, ap);
    if (d1 <= 0 && d2 <= 0) { bw[0] = 1; bw[1] = 0; bw[2] = 0; return a; }
    V3 bp = sub(p, b); double d3 = dot(ab, bp), d4 = dot(ac, bp);
    if (d3 >= 0 && d4 <= d3) { bw[0] = 0; bw[1] = 1; bw[2] = 0; return b; }
    double vc = d1 * d4 - d3 * d2;
    if (vc <= 0 && d1 >= 0 && d3 <= 0) { double v = d1 / (d1 - d3); bw[0] = 1 - v; bw[1] = v; bw[2] = 0; return add(a, mul(ab, v)); }
    V3 cp = sub(p, c); double d5 = dot(ab, cp), d6 = dot(ac, cp);
    if (d6 >= 0 && d5 <= d6) { bw[0] = 0; bw[1] = 0; bw[2] = 1; return c; }
    double vb = d5 * d2 - d1 * d6;
    if (vb <= 0 && d2 >= 0 && d6 <= 0) { double w = d2 / (d2 - d6); bw[0] = 1 - w; bw[1] = 0; bw[2] = w; return add(a, mul(ac, w)); }
    double va = d3 * d6 - d5 * d4;
    if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) { double w = (d4 - d3) / ((d4 - d3) + (d5 - d6)); bw[0] = 0; bw[1] = 1 - w; bw[2] = w; return add(b, mul(sub(c, b), w)); }
    double den = 1.0 / (va + vb + vc); double v = vb * den, w = vc * den;
    bw[0] = 1 - v - w; bw[1] = v; bw[2] = w; return add(a, add(mul(ab, v), mul(ac, w)));
}

struct Part {
    vector<V3> oP; vector<array<float, 2>> oUV; vector<array<int, 3>> oT; Tex col, met;
    V3 lo, hi; double cell = 0.006; int gx, gy, gz; vector<int> cs, ci;
    vector<V3> dP; vector<array<int, 3>> dT;
    int cidx(int x, int y, int z) const { return (x * gy + y) * gz + z; }
    void clampCell(const V3 &p, int *c) const {
        c[0] = min(gx - 1, max(0, (int)floor((p[0] - lo[0]) / cell))); c[1] = min(gy - 1, max(0, (int)floor((p[1] - lo[1]) / cell))); c[2] = min(gz - 1, max(0, (int)floor((p[2] - lo[2]) / cell)));
    }
    void buildGrid() {
        lo = {1e30, 1e30, 1e30}; hi = {-1e30, -1e30, -1e30};
        for (auto &p : oP) for (int k = 0; k < 3; k++) { lo[k] = min(lo[k], p[k]); hi[k] = max(hi[k], p[k]); }
        gx = (int)((hi[0] - lo[0]) / cell) + 1; gy = (int)((hi[1] - lo[1]) / cell) + 1; gz = (int)((hi[2] - lo[2]) / cell) + 1;
        size_t nc = (size_t)gx * gy * gz; cs.assign(nc + 1, 0);
        for (int pass = 0; pass < 2; pass++) {
            vector<int> fillp; if (pass == 1) { for (size_t i = 0; i < nc; i++) cs[i + 1] += cs[i]; ci.resize(cs[nc]); fillp.assign(cs.begin(), cs.end() - 1); }
            for (size_t t = 0; t < oT.size(); t++) {
                V3 mn = {1e30, 1e30, 1e30}, mx = {-1e30, -1e30, -1e30};
                for (int k = 0; k < 3; k++) for (int d = 0; d < 3; d++) { mn[d] = min(mn[d], oP[oT[t][k]][d]); mx[d] = max(mx[d], oP[oT[t][k]][d]); }
                int a[3], b[3]; clampCell(mn, a); clampCell(mx, b);
                for (int x = a[0]; x <= b[0]; x++) for (int y = a[1]; y <= b[1]; y++) for (int z = a[2]; z <= b[2]; z++) {
                    size_t c = cidx(x, y, z); if (pass == 0) cs[c + 1]++; else ci[fillp[c]++] = (int)t;
                }
            }
        }
    }
    // triângulo original mais próximo; nrm = normal do triângulo decimado (para evitar camadas de trás)
    bool closest(const V3 &p, const V3 &nrm, int &bt, double *bbw) const {
        int c[3]; clampCell(p, c); double best = 1e30; bt = -1; double bw[3];
        const int MAXR = 14;
        for (int r = 0; r <= MAXR; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
                if (max(abs(dx), max(abs(dy), abs(dz))) != r) continue;
                int x = c[0] + dx, y = c[1] + dy, z = c[2] + dz; if (x < 0 || y < 0 || z < 0 || x >= gx || y >= gy || z >= gz) continue;
                size_t cc = cidx(x, y, z);
                for (int i = cs[cc]; i < cs[cc + 1]; i++) {
                    int t = ci[i]; const V3 &a = oP[oT[t][0]], &b = oP[oT[t][1]], &d = oP[oT[t][2]];
                    V3 q = closestTri(p, a, b, d, bw); V3 df = sub(p, q); double d2 = dot(df, df);
                    // penaliza triângulos de frente oposta (camadas de trás: capa, cabelo)
                    V3 n = cross(sub(b, a), sub(d, a)); double nl = len(n);
                    if (nl > 1e-18) { double al = fabs(dot(n, nrm)) / nl; if (al < 0.3) d2 += 4e-6 * (0.3 - al); }
                    if (d2 < best) { best = d2; bt = t; bbw[0] = bw[0]; bbw[1] = bw[1]; bbw[2] = bw[2]; }
                }
            }
            if (bt >= 0 && best <= (r * cell) * (r * cell)) break;
        }
        return bt >= 0;
    }
};

struct Block { int part; int quad; int w, h; int ox, oy; };
struct Quad { int part; int v[4]; int tri[2]; int nt; double fw, fh; };  // v: a, s0, s1, d  (d = -1 se triângulo único)

int main(int argc, char **argv) {
    if (argc < 6) { fprintf(stderr, "uso\n"); return 1; }
    int A = atoi(argv[1]); int G = atoi(argv[2]); int MAXB = atoi(argv[3]); string pre = argv[4]; int NP = atoi(argv[5]);
    vector<Part> parts(NP);
    for (int k = 0; k < NP; k++) {
        const char *fpos = argv[6 + k * 6], *fuv = argv[7 + k * 6], *fidx = argv[8 + k * 6], *fcol = argv[9 + k * 6], *fmet = argv[10 + k * 6], *fdec = argv[11 + k * 6];
        Part &P = parts[k];
        { FILE *f = fopen(fpos, "rb"); fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET); int n = sz / 12; vector<float> t(n * 3); if (fread(t.data(), 4, n * 3, f) != (size_t)n * 3) return 2; fclose(f); P.oP.resize(n); for (int i = 0; i < n; i++) P.oP[i] = {t[3 * i], t[3 * i + 1], t[3 * i + 2]}; }
        { FILE *f = fopen(fuv, "rb"); int n = P.oP.size(); P.oUV.resize(n); if (fread(P.oUV.data(), 8, n, f) != (size_t)n) return 2; fclose(f); }
        { FILE *f = fopen(fidx, "rb"); fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET); int n = sz / 12; vector<int> t(n * 3); if (fread(t.data(), 4, n * 3, f) != (size_t)n * 3) return 2; fclose(f); P.oT.resize(n); for (int i = 0; i < n; i++) P.oT[i] = {t[3 * i], t[3 * i + 1], t[3 * i + 2]}; }
        if (!loadTex(fcol, P.col, 3) || !loadTex(fmet, P.met, 1)) { fprintf(stderr, "textura\n"); return 3; }
        { FILE *f = fopen(fdec, "rb"); int hd[2]; if (fread(hd, 4, 2, f) != 2) return 2; int nv = hd[0], nt = hd[1]; vector<float> pp(nv * 3), cc(nv * 3); vector<int> ii(nt * 3);
          if (fread(pp.data(), 4, nv * 3, f) != (size_t)nv * 3 || fread(cc.data(), 4, nv * 3, f) != (size_t)nv * 3 || fread(ii.data(), 4, nt * 3, f) != (size_t)nt * 3) return 2; fclose(f);
          P.dP.resize(nv); for (int i = 0; i < nv; i++) P.dP[i] = {pp[3 * i], pp[3 * i + 1], pp[3 * i + 2]}; P.dT.resize(nt); for (int i = 0; i < nt; i++) P.dT[i] = {ii[3 * i], ii[3 * i + 1], ii[3 * i + 2]}; }
        P.buildGrid(); fprintf(stderr, "parte %d: original %zu verts %zu tris, grade %dx%dx%d, decimada %zu tris\n", k, P.oP.size(), P.oT.size(), P.gx, P.gy, P.gz, P.dT.size());
    }
    // ---- pares de triângulos
    vector<Quad> quads;
    for (int k = 0; k < NP; k++) {
        Part &P = parts[k]; int nt = P.dT.size();
        map<pair<int, int>, vector<int>> em;
        for (int t = 0; t < nt; t++) for (int j = 0; j < 3; j++) { int a = P.dT[t][j], b = P.dT[t][(j + 1) % 3]; em[{min(a, b), max(a, b)}].push_back(t); }
        struct Ed { double l; int a, b, t0, t1; bool lon; };
        vector<Ed> eds;
        auto isLongest = [&](int t, int a, int b) { double la = len(sub(P.dP[a], P.dP[b])); for (int j = 0; j < 3; j++) { int x = P.dT[t][j], y = P.dT[t][(j + 1) % 3]; if (len(sub(P.dP[x], P.dP[y])) > la + 1e-15) return false; } return true; };
        for (auto &e : em) if (e.second.size() == 2) { Ed d; d.a = e.first.first; d.b = e.first.second; d.l = len(sub(P.dP[d.a], P.dP[d.b])); d.t0 = e.second[0]; d.t1 = e.second[1]; d.lon = isLongest(d.t0, d.a, d.b) || isLongest(d.t1, d.a, d.b); eds.push_back(d); }
        sort(eds.begin(), eds.end(), [](const Ed &x, const Ed &y) { if (x.lon != y.lon) return x.lon > y.lon; return x.l > y.l; });
        vector<char> used(nt, 0);
        auto apex = [&](int t, int a, int b) { for (int j = 0; j < 3; j++) if (P.dT[t][j] != a && P.dT[t][j] != b) return P.dT[t][j]; return -1; };
        for (auto &e : eds) {
            if (used[e.t0] || used[e.t1]) continue; used[e.t0] = used[e.t1] = 1;
            Quad q; q.part = k; q.nt = 2; q.tri[0] = e.t0; q.tri[1] = e.t1;
            int a = apex(e.t0, e.a, e.b), d = apex(e.t1, e.a, e.b); int s0 = e.a, s1 = e.b;
            double w = (len(sub(P.dP[a], P.dP[s0])) + len(sub(P.dP[s1], P.dP[d]))) / 2, h = (len(sub(P.dP[a], P.dP[s1])) + len(sub(P.dP[s0], P.dP[d]))) / 2;
            if (h > w) { swap(s0, s1); swap(w, h); }
            q.v[0] = a; q.v[1] = s0; q.v[2] = s1; q.v[3] = d; q.fw = w; q.fh = h; quads.push_back(q);
        }
        for (int t = 0; t < nt; t++) if (!used[t]) {
            Quad q; q.part = k; q.nt = 1; q.tri[0] = t; q.tri[1] = -1; int a = P.dT[t][0], s0 = P.dT[t][1], s1 = P.dT[t][2];
            double w = len(sub(P.dP[a], P.dP[s0])), h = len(sub(P.dP[a], P.dP[s1])); if (h > w) { swap(s0, s1); swap(w, h); }
            q.v[0] = a; q.v[1] = s0; q.v[2] = s1; q.v[3] = -1; q.fw = w; q.fh = h; quads.push_back(q);
        }
    }
    size_t singles = 0; for (auto &q : quads) singles += (q.nt == 1); fprintf(stderr, "blocos %zu (triângulos sozinhos: %zu)\n", quads.size(), singles);
    // ---- empacotamento (prateleiras); busca o maior tamanho de texel que cabe
    vector<Block> blocks(quads.size());
    auto pack = [&](double ts) -> bool {
        for (size_t i = 0; i < quads.size(); i++) {
            blocks[i].part = quads[i].part; blocks[i].quad = (int)i;
            blocks[i].w = min(MAXB, max(2, (int)lround(quads[i].fw / ts))); blocks[i].h = min(MAXB, max(2, (int)lround(quads[i].fh / ts)));
        }
        vector<int> ord(blocks.size()); iota(ord.begin(), ord.end(), 0);
        sort(ord.begin(), ord.end(), [&](int x, int y) { if (blocks[x].h != blocks[y].h) return blocks[x].h > blocks[y].h; return blocks[x].w > blocks[y].w; });
        int x = 0, y = 0, sh = 0;
        for (int id : ord) {
            int bw = blocks[id].w + 2 * G, bh = blocks[id].h + 2 * G;
            if (x + bw > A) { x = 0; y += sh; sh = 0; }
            if (sh == 0) sh = bh;
            if (y + bh > A) return false;
            blocks[id].ox = x; blocks[id].oy = y; x += bw;
        }
        return true;
    };
    double lo = 1e-5, hi = 0.2;
    for (int it = 0; it < 40; it++) { double mid = sqrt(lo * hi); if (pack(mid)) hi = mid; else lo = mid; }
    double ts = hi * 1.0005; if (!pack(ts)) { ts = hi * 1.02; pack(ts); }
    long used = 0; for (auto &b : blocks) used += (long)(b.w + 2 * G) * (b.h + 2 * G);
    fprintf(stderr, "texel = %.5f (unidade do modelo; x2 = %.3f cm), uso do atlas %.1f%%\n", ts, ts * 2 * 100, 100.0 * used / ((double)A * A));
    // ---- assa
    vector<unsigned char> acol((size_t)A * A * 3, 128), amet((size_t)A * A, 0);
    long done = 0, miss = 0;
    for (size_t bi = 0; bi < blocks.size(); bi++) {
        Block &B = blocks[bi]; Quad &Q = quads[B.quad]; Part &P = parts[Q.part];
        V3 pa = P.dP[Q.v[0]], ps0 = P.dP[Q.v[1]], ps1 = P.dP[Q.v[2]], pd = Q.v[3] >= 0 ? P.dP[Q.v[3]] : V3{0, 0, 0};
        V3 n1 = cross(sub(ps0, pa), sub(ps1, pa)); { double l = len(n1); if (l > 1e-20) n1 = mul(n1, 1 / l); }
        V3 n2 = n1; if (Q.v[3] >= 0) { n2 = cross(sub(pd, ps0), sub(ps1, ps0)); double l = len(n2); if (l > 1e-20) n2 = mul(n2, 1 / l); }
        for (int j = -G; j < B.h + G; j++) for (int i = -G; i < B.w + G; i++) {
            double u = (i + 0.5) / B.w, v = (j + 0.5) / B.h; V3 p, nn;
            if (Q.v[3] < 0 || u + v <= 1.0) { double la = 1 - u - v, l0 = u, l1 = v; p = add(add(mul(pa, la), mul(ps0, l0)), mul(ps1, l1)); nn = n1; }
            else { double l0 = 1 - v, l1 = 1 - u, ld = u + v - 1; p = add(add(mul(ps0, l0), mul(ps1, l1)), mul(pd, ld)); nn = n2; }
            int bt; double bw[3]; double rgb[3] = {128, 128, 128}, mt = 0;
            if (P.closest(p, nn, bt, bw)) {
                double uu = 0, vv = 0; for (int c = 0; c < 3; c++) { uu += bw[c] * P.oUV[P.oT[bt][c]][0]; vv += bw[c] * P.oUV[P.oT[bt][c]][1]; }
                sampleTex(P.col, 3, uu, vv, rgb); sampleTex(P.met, 1, uu, vv, &mt);
            } else miss++;
            size_t px = (size_t)(B.oy + G + j) * A + (B.ox + G + i);
            for (int c = 0; c < 3; c++) acol[px * 3 + c] = (unsigned char)min(255.0, max(0.0, rgb[c] + 0.5)); amet[px] = (unsigned char)min(255.0, max(0.0, mt + 0.5));
            done++;
        }
        if ((bi & 0x3fff) == 0) fprintf(stderr, "bloco %zu/%zu\n", bi, blocks.size());
    }
    fprintf(stderr, "texels %ld, sem correspondência %ld\n", done, miss);
    { FILE *f = fopen((pre + "_col.raw").c_str(), "wb"); int hd[2] = {A, A}; fwrite(hd, 4, 2, f); fwrite(acol.data(), 1, acol.size(), f); fclose(f); }
    { FILE *f = fopen((pre + "_met.raw").c_str(), "wb"); int hd[2] = {A, A}; fwrite(hd, 4, 2, f); fwrite(amet.data(), 1, amet.size(), f); fclose(f); }
    // ---- vértices e triângulos por parte
    for (int k = 0; k < NP; k++) {
        vector<int> vid; vector<float> uv; vector<int> tri;
        for (size_t bi = 0; bi < blocks.size(); bi++) {
            Block &B = blocks[bi]; Quad &Q = quads[B.quad]; if (Q.part != k) continue;
            Part &P = parts[k]; int base = (int)vid.size();
            const double cu[4] = {0, 1, 0, 1}, cv[4] = {0, 0, 1, 1};
            int nvq = Q.v[3] >= 0 ? 4 : 3;
            for (int c = 0; c < nvq; c++) {
                vid.push_back(Q.v[c]);
                uv.push_back((float)((B.ox + G + cu[c] * B.w) / A)); uv.push_back((float)((B.oy + G + cv[c] * B.h) / A));
            }
            // cantos do bloco: 0=a (0,0), 1=s0 (1,0), 2=s1 (0,1), 3=d (1,1). Mantém a ordem original dos triângulos.
            auto corner = [&](int v) { for (int c = 0; c < nvq; c++) if (Q.v[c] == v) return base + c; return -1; };
            for (int t = 0; t < Q.nt; t++) { auto &T = P.dT[Q.tri[t]]; tri.push_back(corner(T[0])); tri.push_back(corner(T[1])); tri.push_back(corner(T[2])); }
        }
        FILE *f = fopen((pre + "_part" + to_string(k) + ".bin").c_str(), "wb"); int hd[2] = {(int)vid.size(), (int)(tri.size() / 3)}; fwrite(hd, 4, 2, f);
        fwrite(vid.data(), 4, vid.size(), f); fwrite(uv.data(), 4, uv.size(), f); fwrite(tri.data(), 4, tri.size(), f); fclose(f);
        fprintf(stderr, "parte %d: %zu vértices, %zu triângulos\n", k, vid.size(), tri.size() / 3);
    }
    return 0;
}
