// Gera caixas de colisão (AABB) a partir da malha: amostra a superfície, monta vãos sólidos por coluna e funde em caixas.
// uso: col mesh.bin cx cz floorY S out.bin [maxFill]
#include <bits/stdc++.h>
using namespace std;
int NV, NT; vector<float> P, C; vector<int> I;
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); int hdr[2]; fread(hdr, 4, 2, f); NV = hdr[0]; NT = hdr[1];
    P.resize(NV * 3); C.resize(NV * 3); I.resize(NT * 3);
    fread(P.data(), 4, NV * 3, f); fread(C.data(), 4, NV * 3, f); fread(I.data(), 4, NT * 3, f); fclose(f);
    double cx = atof(argv[2]), cz = atof(argv[3]), fy = atof(argv[4]), S = atof(argv[5]); const char *fout = argv[6];
    double maxFill = argc > 7 ? atof(argv[7]) : 5.0;
    const double H = argc > 8 ? atof(argv[8]) : 0.2, D = 0.1, QY = argc > 9 ? atof(argv[9]) : 0.05;
    vector<array<double, 3>> W(NV);
    double mnx = 1e9, mxx = -1e9, mnz = 1e9, mxz = -1e9;
    for (int i = 0; i < NV; i++) { W[i] = {(P[3 * i] - cx) * S, (P[3 * i + 1] - fy) * S, (P[3 * i + 2] - cz) * S}; mnx = min(mnx, W[i][0]); mxx = max(mxx, W[i][0]); mnz = min(mnz, W[i][2]); mxz = max(mxz, W[i][2]); }
    int nx = (int)ceil((mxx - mnx) / H) + 2, nz = (int)ceil((mxz - mnz) / H) + 2;
    fprintf(stderr, "grade %d x %d  x[%.1f,%.1f] z[%.1f,%.1f]\n", nx, nz, mnx, mxx, mnz, mxz);
    vector<unsigned> cnt((size_t)nx * nz + 1, 0);
    auto forSamples = [&](auto fn) {
        for (int t = 0; t < NT; t++) {
            auto &a = W[I[3 * t]]; auto &b = W[I[3 * t + 1]]; auto &c = W[I[3 * t + 2]];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            double nxx = uy * vz - uz * vy, nyy = uz * vx - ux * vz, nzz = ux * vy - uy * vx; double nl = sqrt(nxx * nxx + nyy * nyy + nzz * nzz); if (nl < 1e-12) continue;
            double ny = nyy / nl;
            int kind = ny > 0.5 ? 0 : (ny < -0.5 ? 1 : 2);
            double e0 = sqrt(ux * ux + uy * uy + uz * uz), e1 = sqrt(vx * vx + vy * vy + vz * vz);
            double e2 = sqrt((c[0] - b[0]) * (c[0] - b[0]) + (c[1] - b[1]) * (c[1] - b[1]) + (c[2] - b[2]) * (c[2] - b[2]));
            int n = (int)ceil(max(e0, max(e1, e2)) / D); n = max(1, min(n, 400));
            if (n == 1) { fn((a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, (a[2] + b[2] + c[2]) / 3, kind); continue; }
            for (int i = 0; i <= n; i++) for (int j = 0; i + j <= n; j++) {
                double s = (double)i / n, u = (double)j / n;
                fn(a[0] + ux * s + vx * u, a[1] + uy * s + vy * u, a[2] + uz * s + vz * u, kind);
            }
        }
    };
    auto colOf = [&](double x, double z) -> long { int ix = (int)floor((x - mnx) / H), iz = (int)floor((z - mnz) / H); if (ix < 0 || iz < 0 || ix >= nx || iz >= nz) return -1; return (long)ix * nz + iz; };
    forSamples([&](double x, double y, double z, int k) { long c = colOf(x, z); if (c >= 0) cnt[c + 1]++; });
    for (size_t i = 0; i < (size_t)nx * nz; i++) cnt[i + 1] += cnt[i];
    size_t total = cnt[(size_t)nx * nz]; fprintf(stderr, "amostras %zu\n", total);
    vector<float> ys(total); vector<unsigned char> ks(total); vector<unsigned> fill(cnt.begin(), cnt.end() - 1);
    forSamples([&](double x, double y, double z, int k) { long c = colOf(x, z); if (c >= 0) { size_t p = fill[c]++; ys[p] = (float)y; ks[p] = (unsigned char)k; } });
    vector<unsigned>().swap(fill);
    // spans por coluna (em unidades de QY inteiras)
    vector<vector<pair<int, int>>> spans((size_t)nx * nz);
    for (long c = 0; c < (long)nx * nz; c++) {
        unsigned s0 = cnt[c], s1 = cnt[c + 1]; if (s0 == s1) continue;
        vector<pair<float, int>> v; v.reserve(s1 - s0);
        for (unsigned i = s0; i < s1; i++) v.push_back({ys[i], ks[i]});
        sort(v.begin(), v.end());
        vector<pair<double, double>> iv;
        for (size_t i = 0; i < v.size(); i++) {
            double y = v[i].first; int k = v[i].second;
            if (k == 2) iv.push_back({y - 0.08, y + 0.08});
            else if (k == 1) iv.push_back({y, y + 0.12});
            else {
                double lo = y - maxFill;
                for (int j = (int)i - 1; j >= 0; j--) if (v[j].first < y - 0.04) { lo = max(lo, (double)v[j].first - (v[j].second == 2 ? 0.08 : 0.0)); break; }
                iv.push_back({lo, y});
            }
        }
        sort(iv.begin(), iv.end());
        vector<pair<double, double>> m;
        for (auto &p : iv) { if (!m.empty() && p.first <= m.back().second + 0.02) m.back().second = max(m.back().second, p.second); else m.push_back(p); }
        for (auto &p : m) {
            int a = (int)llround(p.first / QY), b = (int)llround(p.second / QY);
            if (b - a < 2) continue;
            spans[c].push_back({a, b});
        }
    }
    vector<float>().swap(ys); vector<unsigned char>().swap(ks);
    // funde em z
    struct Run { int x, z0, z1, a, b; };
    vector<vector<Run>> runs(nx);
    for (int x = 0; x < nx; x++) {
        map<pair<int, int>, int> open;
        for (int z = 0; z <= nz; z++) {
            set<pair<int, int>> here;
            if (z < nz) for (auto &p : spans[(size_t)x * nz + z]) here.insert(p);
            for (auto it = open.begin(); it != open.end();) {
                if (!here.count(it->first)) { runs[x].push_back({x, it->second, z - 1, it->first.first, it->first.second}); it = open.erase(it); } else ++it;
            }
            for (auto &p : here) if (!open.count(p)) open[p] = z;
        }
    }
    // funde em x
    vector<array<float, 6>> boxes;
    {
        map<array<int, 4>, int> open;
        for (int x = 0; x <= nx; x++) {
            set<array<int, 4>> here;
            if (x < nx) for (auto &r : runs[x]) here.insert({r.z0, r.z1, r.a, r.b});
            for (auto it = open.begin(); it != open.end();) {
                if (!here.count(it->first)) {
                    auto &k = it->first; int xs = it->second;
                    boxes.push_back({(float)(mnx + xs * H), (float)(k[2] * QY), (float)(mnz + k[0] * H), (float)(mnx + x * H), (float)(k[3] * QY), (float)(mnz + (k[1] + 1) * H)});
                    it = open.erase(it);
                } else ++it;
            }
            for (auto &k : here) if (!open.count(k)) open[k] = x;
        }
    }
    fprintf(stderr, "caixas %zu\n", boxes.size());
    FILE *o = fopen(fout, "wb"); int n = boxes.size(); fwrite(&n, 4, 1, o); for (auto &b : boxes) fwrite(b.data(), 4, 6, o); fclose(o);
    return 0;
}
