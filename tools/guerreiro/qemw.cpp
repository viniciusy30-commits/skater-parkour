// Simplificador QEM (Garland-Heckbert) com cor por vértice.
// uso: qem modo alvo_tris in_pos in_idx in_col out
//  modo 0: entrada crua (pos.f32 / idx.u32 / col.u8): solda posições idênticas
//  modo 1: entrada no formato do próprio programa (out de uma rodada anterior)
#include <bits/stdc++.h>
using namespace std;
typedef unsigned long long u64;

struct Q10 { double q[10]; };
static inline void qadd(Q10 &a, const Q10 &b) { for (int i = 0; i < 10; i++) a.q[i] += b.q[i]; }
static inline void qplane(Q10 &k, double a, double b, double c, double d, double w) {
    k.q[0] = a * a * w; k.q[1] = a * b * w; k.q[2] = a * c * w; k.q[3] = a * d * w;
    k.q[4] = b * b * w; k.q[5] = b * c * w; k.q[6] = b * d * w;
    k.q[7] = c * c * w; k.q[8] = c * d * w; k.q[9] = d * d * w;
}
static inline double qeval(const Q10 &k, const double *p) {
    double x = p[0], y = p[1], z = p[2];
    return k.q[0] * x * x + 2 * k.q[1] * x * y + 2 * k.q[2] * x * z + 2 * k.q[3] * x
         + k.q[4] * y * y + 2 * k.q[5] * y * z + 2 * k.q[6] * y
         + k.q[7] * z * z + 2 * k.q[8] * z + k.q[9];
}

int NV, NT;
vector<array<double, 3>> P;
vector<array<int, 3>> T;
vector<char> tdead, vdead, vbnd;
vector<vector<int>> vf;
vector<Q10> Q;
vector<array<double, 3>> csum;
vector<double> cw;
vector<int> vver;
vector<array<unsigned char, 8>> JW; vector<double> msum;

struct E { float cost; int a, b; unsigned va, vb; double p[3]; };
struct Cmp { bool operator()(const E &x, const E &y) const { return x.cost > y.cost; } };

static inline double dist2(const double *a, const double *b) { double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return x * x + y * y + z * z; }

// melhor posição para colapsar a-b. retorna custo
static double bestPos(int a, int b, double *out) {
    Q10 s = Q[a]; qadd(s, Q[b]);
    const double *pa = P[a].data(), *pb = P[b].data();
    double mid[3] = {(pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, (pa[2] + pb[2]) / 2};
    double len2 = dist2(pa, pb);
    double best = 1e300; double cand[3];
    // ótimo
    double A[3][3] = {{s.q[0], s.q[1], s.q[2]}, {s.q[1], s.q[4], s.q[5]}, {s.q[2], s.q[5], s.q[7]}};
    double rhs[3] = {-s.q[3], -s.q[6], -s.q[8]};
    double det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) - A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0]) + A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0]);
    double scale = fabs(A[0][0]) + fabs(A[1][1]) + fabs(A[2][2]);
    if (scale > 0 && fabs(det) > 1e-9 * scale * scale * scale) {
        double inv[3][3];
        inv[0][0] = (A[1][1] * A[2][2] - A[1][2] * A[2][1]) / det; inv[0][1] = (A[0][2] * A[2][1] - A[0][1] * A[2][2]) / det; inv[0][2] = (A[0][1] * A[1][2] - A[0][2] * A[1][1]) / det;
        inv[1][0] = (A[1][2] * A[2][0] - A[1][0] * A[2][2]) / det; inv[1][1] = (A[0][0] * A[2][2] - A[0][2] * A[2][0]) / det; inv[1][2] = (A[0][2] * A[1][0] - A[0][0] * A[1][2]) / det;
        inv[2][0] = (A[1][0] * A[2][1] - A[1][1] * A[2][0]) / det; inv[2][1] = (A[0][1] * A[2][0] - A[0][0] * A[2][1]) / det; inv[2][2] = (A[0][0] * A[1][1] - A[0][1] * A[1][0]) / det;
        for (int i = 0; i < 3; i++) cand[i] = inv[i][0] * rhs[0] + inv[i][1] * rhs[1] + inv[i][2] * rhs[2];
        // só aceita se ficar perto da aresta
        if (dist2(cand, mid) <= 1.5 * len2) { double c = qeval(s, cand); if (c < best) { best = c; out[0] = cand[0]; out[1] = cand[1]; out[2] = cand[2]; } }
    }
    double ca = qeval(s, pa); if (ca < best) { best = ca; out[0] = pa[0]; out[1] = pa[1]; out[2] = pa[2]; }
    double cb = qeval(s, pb); if (cb < best) { best = cb; out[0] = pb[0]; out[1] = pb[1]; out[2] = pb[2]; }
    double cm = qeval(s, mid); if (cm < best) { best = cm; out[0] = mid[0]; out[1] = mid[1]; out[2] = mid[2]; }
    if (best < 0) best = 0;
    return best + 1e-6 * len2 * len2;
}

static inline void triN(const double *a, const double *b, const double *c, double *n) {
    double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
    n[0] = uy * wz - uz * wy; n[1] = uz * wx - ux * wz; n[2] = ux * wy - uy * wx;
}

// colapsar a e b em p é válido? (sem inversão de faces, sem tocar topologia)
static bool valid(int a, int b, const double *p, int &nShared) {
    nShared = 0;
    static vector<int> na, nb;
    na.clear(); nb.clear();
    for (int f : vf[a]) { bool hb = false; for (int k = 0; k < 3; k++) if (T[f][k] == b) hb = true; if (hb) nShared++; for (int k = 0; k < 3; k++) if (T[f][k] != a) na.push_back(T[f][k]); }
    for (int f : vf[b]) { for (int k = 0; k < 3; k++) if (T[f][k] != b) nb.push_back(T[f][k]); }
    sort(na.begin(), na.end()); na.erase(unique(na.begin(), na.end()), na.end());
    sort(nb.begin(), nb.end()); nb.erase(unique(nb.begin(), nb.end()), nb.end());
    int common = 0; size_t i = 0, j = 0;
    while (i < na.size() && j < nb.size()) { if (na[i] == nb[j]) { common++; i++; j++; } else if (na[i] < nb[j]) i++; else j++; }
    // 'common' conta a e b entre si (b está em na, a em nb): descontar
    // na contém b; nb contém a; a interseção não os contém (a∉na, b∉nb).
    if (nShared < 1 || nShared > 2) return false;
    if (common != nShared) return false;
    if (vbnd[a] && vbnd[b] && nShared == 2) return false;  // ligaria duas bordas por dentro
    for (int side = 0; side < 2; side++) {
        int v = side ? b : a;
        for (int f : vf[v]) {
            bool hasA = false, hasB = false;
            for (int k = 0; k < 3; k++) { if (T[f][k] == a) hasA = true; if (T[f][k] == b) hasB = true; }
            if (hasA && hasB) continue;
            double n0[3], n1[3]; double *q[3];
            const double *pp[3], *pq[3];
            for (int k = 0; k < 3; k++) { pp[k] = P[T[f][k]].data(); pq[k] = (T[f][k] == v) ? p : pp[k]; }
            triN(pp[0], pp[1], pp[2], n0); triN(pq[0], pq[1], pq[2], n1);
            double l0 = sqrt(n0[0] * n0[0] + n0[1] * n0[1] + n0[2] * n0[2]), l1 = sqrt(n1[0] * n1[0] + n1[1] * n1[1] + n1[2] * n1[2]);
            if (l1 < 1e-14) return false;
            if (l0 > 1e-14 && (n0[0] * n1[0] + n0[1] * n1[1] + n0[2] * n1[2]) < 0.25 * l0 * l1) return false;
            (void)q;
        }
    }
    return true;
}

int main(int argc, char **argv) {
    if (argc < 7) { fprintf(stderr, "uso\n"); return 1; }
    int mode = atoi(argv[1]); long target = atol(argv[2]);
    const char *fpos = argv[3], *fidx = argv[4], *fcol = argv[5], *fout = argv[6];
    vector<array<float, 3>> rawP; vector<unsigned> rawI; vector<array<unsigned char, 3>> rawC;
    vector<int> remap;
    if (mode == 0) {
        FILE *f = fopen(fpos, "rb"); fseek(f, 0, SEEK_END); long sz = ftell(f); fseek(f, 0, SEEK_SET);
        int n = sz / 12; rawP.resize(n); fread(rawP.data(), 12, n, f); fclose(f);
        f = fopen(fidx, "rb"); fseek(f, 0, SEEK_END); sz = ftell(f); fseek(f, 0, SEEK_SET);
        rawI.resize(sz / 4); fread(rawI.data(), 4, rawI.size(), f); fclose(f);
        f = fopen(fcol, "rb"); rawC.resize(n); fread(rawC.data(), 3, n, f); fclose(f);
        vector<array<unsigned char, 8>> rawJW(n); vector<unsigned char> rawM(n);
        f = fopen(argv[7], "rb"); fread(rawJW.data(), 8, n, f); fclose(f);
        f = fopen(argv[8], "rb"); fread(rawM.data(), 1, n, f); fclose(f);
        // solda
        vector<int> ord(n); iota(ord.begin(), ord.end(), 0);
        sort(ord.begin(), ord.end(), [&](int a, int b) { return rawP[a] < rawP[b]; });
        remap.assign(n, -1); NV = 0;
        vector<int> first;
        for (int i = 0; i < n; i++) {
            if (i == 0 || rawP[ord[i]] != rawP[ord[i - 1]]) { first.push_back(ord[i]); NV++; }
            remap[ord[i]] = NV - 1;
        }
        P.resize(NV); csum.assign(NV, {0, 0, 0}); cw.assign(NV, 0); JW.resize(NV); msum.assign(NV, 0);
        for (int i = 0; i < NV; i++) { auto &q = rawP[first[i]]; P[i] = {q[0], q[1], q[2]}; JW[i] = rawJW[first[i]]; }
        for (int i = 0; i < n; i++) msum[remap[i]] += rawM[i] / 255.0;
        for (int i = 0; i < n; i++) { int v = remap[i]; for (int k = 0; k < 3; k++) { double c = rawC[i][k] / 255.0; csum[v][k] += c * c; } cw[v] += 1; }
        long nt = rawI.size() / 3; T.reserve(nt);
        for (long t = 0; t < nt; t++) {
            int a = remap[rawI[3 * t]], b = remap[rawI[3 * t + 1]], c = remap[rawI[3 * t + 2]];
            if (a == b || b == c || a == c) continue;
            T.push_back({a, b, c});
        }
        NT = T.size();
        vector<array<float, 3>>().swap(rawP); vector<unsigned>().swap(rawI); vector<array<unsigned char, 3>>().swap(rawC); vector<int>().swap(remap); vector<int>().swap(ord);
    } else {
        FILE *f = fopen(fpos, "rb"); int hdr[2]; fread(hdr, 4, 2, f); NV = hdr[0]; NT = hdr[1];
        vector<float> pp(NV * 3); fread(pp.data(), 4, NV * 3, f);
        vector<float> cc(NV * 3); fread(cc.data(), 4, NV * 3, f);
        vector<int> ii(NT * 3); fread(ii.data(), 4, NT * 3, f); fclose(f);
        P.resize(NV); csum.assign(NV, {0, 0, 0}); cw.assign(NV, 1);
        for (int i = 0; i < NV; i++) { P[i] = {pp[3 * i], pp[3 * i + 1], pp[3 * i + 2]}; csum[i] = {cc[3 * i], cc[3 * i + 1], cc[3 * i + 2]}; }
        T.resize(NT); for (int i = 0; i < NT; i++) T[i] = {ii[3 * i], ii[3 * i + 1], ii[3 * i + 2]};
    }
    fprintf(stderr, "verts %d tris %d\n", NV, NT);
    tdead.assign(NT, 0); vdead.assign(NV, 0); vbnd.assign(NV, 0); vver.assign(NV, 0);
    vf.assign(NV, {});
    {
        vector<int> deg(NV, 0);
        for (int t = 0; t < NT; t++) for (int k = 0; k < 3; k++) deg[T[t][k]]++;
        for (int v = 0; v < NV; v++) vf[v].reserve(deg[v]);
        for (int t = 0; t < NT; t++) for (int k = 0; k < 3; k++) vf[T[t][k]].push_back(t);
    }
    Q.assign(NV, Q10());
    for (auto &q : Q) for (int i = 0; i < 10; i++) q.q[i] = 0;
    // quadrics das faces
    for (int t = 0; t < NT; t++) {
        double n[3]; triN(P[T[t][0]].data(), P[T[t][1]].data(), P[T[t][2]].data(), n);
        double l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]); if (l < 1e-20) continue;
        double area = l / 2; n[0] /= l; n[1] /= l; n[2] /= l;
        double d = -(n[0] * P[T[t][0]][0] + n[1] * P[T[t][0]][1] + n[2] * P[T[t][0]][2]);
        Q10 k; qplane(k, n[0], n[1], n[2], d, area);
        for (int c = 0; c < 3; c++) qadd(Q[T[t][c]], k);
    }
    // arestas: contagem
    vector<pair<u64, int>> ek; ek.reserve((size_t)NT * 3);
    for (int t = 0; t < NT; t++) for (int k = 0; k < 3; k++) {
        int a = T[t][k], b = T[t][(k + 1) % 3]; u64 lo = min(a, b), hi = max(a, b);
        ek.push_back({(lo << 32) | hi, t});
    }
    sort(ek.begin(), ek.end());
    vector<pair<int, int>> edges; edges.reserve(ek.size() / 2 + 1000);
    vector<char> edgeBad;
    for (size_t i = 0; i < ek.size();) {
        size_t j = i; while (j < ek.size() && ek[j].first == ek[i].first) j++;
        int cnt = j - i; int a = ek[i].first >> 32, b = ek[i].first & 0xffffffffu;
        edges.push_back({a, b});
        if (cnt == 1) {
            vbnd[a] = vbnd[b] = 1;
            int t = ek[i].second;
            double n[3]; triN(P[T[t][0]].data(), P[T[t][1]].data(), P[T[t][2]].data(), n);
            double l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (l > 1e-20) {
                n[0] /= l; n[1] /= l; n[2] /= l;
                double e[3] = {P[b][0] - P[a][0], P[b][1] - P[a][1], P[b][2] - P[a][2]};
                double el = sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
                if (el > 1e-20) {
                    e[0] /= el; e[1] /= el; e[2] /= el;
                    double c[3] = {e[1] * n[2] - e[2] * n[1], e[2] * n[0] - e[0] * n[2], e[0] * n[1] - e[1] * n[0]};
                    double cl = sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
                    if (cl > 1e-12) {
                        c[0] /= cl; c[1] /= cl; c[2] /= cl;
                        double d = -(c[0] * P[a][0] + c[1] * P[a][1] + c[2] * P[a][2]);
                        Q10 k; qplane(k, c[0], c[1], c[2], d, el * el * 8);
                        qadd(Q[a], k); qadd(Q[b], k);
                    }
                }
            }
        } else if (cnt > 2) { vbnd[a] = vbnd[b] = 1; }
        i = j;
    }
    vector<pair<u64, int>>().swap(ek);
    fprintf(stderr, "arestas %zu\n", edges.size());

    priority_queue<E, vector<E>, Cmp> pq;
    {
        vector<E> init; init.reserve(edges.size());
        for (auto &e : edges) {
            E x; x.a = e.first; x.b = e.second; x.va = 0; x.vb = 0;
            x.cost = (float)bestPos(x.a, x.b, x.p);
            init.push_back(x);
        }
        pq = priority_queue<E, vector<E>, Cmp>(Cmp(), std::move(init));
    }
    vector<pair<int, int>>().swap(edges);
    fprintf(stderr, "heap %zu\n", pq.size());

    long alive = NT; long iter = 0; vector<int> rep(NV); iota(rep.begin(), rep.end(), 0);
    while (alive > target && !pq.empty()) {
        E e = pq.top(); pq.pop();
        if (vdead[e.a] || vdead[e.b] || vver[e.a] != (int)e.va || vver[e.b] != (int)e.vb) continue;
        int a = e.a, b = e.b; int ns;
        // recalcula posição (barato) para garantir consistência
        double p[3]; bestPos(a, b, p);
        if (!valid(a, b, p, ns)) { continue; }
        // aplica
        { double da = dist2(p, P[a].data()), db = dist2(p, P[b].data()); if (db < da) JW[a] = JW[b]; }
        P[a] = {p[0], p[1], p[2]};
        msum[a] += msum[b];
        qadd(Q[a], Q[b]);
        for (int k = 0; k < 3; k++) csum[a][k] += csum[b][k];
        cw[a] += cw[b];
        vbnd[a] = vbnd[a] || vbnd[b];
        vdead[b] = 1; rep[b] = a;
        vver[a]++;
        for (int f : vf[b]) {
            if (tdead[f]) continue;
            bool hasA = false; for (int k = 0; k < 3; k++) if (T[f][k] == a) hasA = true;
            if (hasA) {
                tdead[f] = 1; alive--;
                for (int k = 0; k < 3; k++) { int c = T[f][k]; if (c != a && c != b) { auto &l = vf[c]; l.erase(remove(l.begin(), l.end(), f), l.end()); } }
            } else {
                for (int k = 0; k < 3; k++) if (T[f][k] == b) T[f][k] = a;
                vf[a].push_back(f);
            }
        }
        vf[b].clear(); vf[b].shrink_to_fit();
        { auto &l = vf[a]; l.erase(remove_if(l.begin(), l.end(), [&](int f) { return tdead[f] != 0; }), l.end()); }
        // novas arestas
        static vector<int> nb; nb.clear();
        for (int f : vf[a]) for (int k = 0; k < 3; k++) if (T[f][k] != a) nb.push_back(T[f][k]);
        sort(nb.begin(), nb.end()); nb.erase(unique(nb.begin(), nb.end()), nb.end());
        for (int n : nb) {
            E x; x.a = a; x.b = n; x.va = vver[a]; x.vb = vver[n];
            x.cost = (float)bestPos(a, n, x.p);
            pq.push(x);
        }
        if ((++iter & 0x3ffff) == 0) fprintf(stderr, "alive %ld  cost %.3g\n", alive, (double)e.cost);
    }
    fprintf(stderr, "final tris %ld  (parou em custo %.3g)\n", alive, pq.empty() ? 0.0 : (double)pq.top().cost);
    // compacta
    vector<int> nid(NV, -1); int nv = 0;
    for (int t = 0; t < NT; t++) if (!tdead[t]) for (int k = 0; k < 3; k++) { int v = T[t][k]; if (nid[v] < 0) nid[v] = nv++; }
    vector<float> op(nv * 3), oc(nv * 3);
    for (int v = 0; v < NV; v++) if (nid[v] >= 0) {
        for (int k = 0; k < 3; k++) { op[nid[v] * 3 + k] = (float)P[v][k]; oc[nid[v] * 3 + k] = (float)(csum[v][k] / max(1.0, cw[v])); }
        if (mode == 1) for (int k = 0; k < 3; k++) oc[nid[v] * 3 + k] = (float)(csum[v][k] / max(1.0, cw[v]));
    }
    vector<int> oi; oi.reserve(alive * 3);
    for (int t = 0; t < NT; t++) if (!tdead[t]) for (int k = 0; k < 3; k++) oi.push_back(nid[T[t][k]]);
    FILE *f = fopen(fout, "wb"); int hdr[2] = {nv, (int)(oi.size() / 3)}; fwrite(hdr, 4, 2, f);
    fwrite(op.data(), 4, op.size(), f); fwrite(oc.data(), 4, oc.size(), f); fwrite(oi.data(), 4, oi.size(), f); fclose(f);
    { vector<unsigned char> ojw(nv * 8); vector<float> om(nv);
      for (int v = 0; v < NV; v++) if (nid[v] >= 0) { memcpy(&ojw[nid[v] * 8], JW[v].data(), 8); om[nid[v]] = (float)(msum[v] / max(1.0, cw[v])); }
      f = fopen(fout, "ab"); fwrite(ojw.data(), 1, ojw.size(), f); fwrite(om.data(), 4, nv, f); fclose(f); }
    fprintf(stderr, "escrito %d verts %zu tris\n", nv, oi.size() / 3);
    return 0;
}
