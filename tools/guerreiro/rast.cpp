// Rasterizador de software: prévia (PPM) e visibilidade (triângulos vistos de pontos de vista).
// uso: rast mesh.bin prev out.ppm ex ey ez tx ty tz fovdeg W H
//      rast mesh.bin vis views.txt out_visible.u8 W H     (views.txt: ex ey ez tx ty tz por linha)
#include <bits/stdc++.h>
using namespace std;
int NV, NT; vector<float> P, C; vector<int> I;
struct Cam { double e[3], r[3], u[3], f[3]; double tanh, aspect; };
static void camSet(Cam &c, const double *e, const double *t, double fov, double aspect) {
    for (int k = 0; k < 3; k++) { c.e[k] = e[k]; c.f[k] = t[k] - e[k]; }
    double l = sqrt(c.f[0] * c.f[0] + c.f[1] * c.f[1] + c.f[2] * c.f[2]); for (int k = 0; k < 3; k++) c.f[k] /= l;
    double up[3] = {0, 1, 0};
    c.r[0] = c.f[1] * up[2] - c.f[2] * up[1]; c.r[1] = c.f[2] * up[0] - c.f[0] * up[2]; c.r[2] = c.f[0] * up[1] - c.f[1] * up[0];
    l = sqrt(c.r[0] * c.r[0] + c.r[1] * c.r[1] + c.r[2] * c.r[2]); if (l < 1e-9) { c.r[0] = 1; c.r[1] = 0; c.r[2] = 0; l = 1; } for (int k = 0; k < 3; k++) c.r[k] /= l;
    c.u[0] = c.r[1] * c.f[2] - c.r[2] * c.f[1]; c.u[1] = c.r[2] * c.f[0] - c.r[0] * c.f[2]; c.u[2] = c.r[0] * c.f[1] - c.r[1] * c.f[0];
    c.tanh = tan(fov * M_PI / 360.0); c.aspect = aspect;
}
// projeta: retorna false se atrás do near
static bool proj(const Cam &c, const float *p, int W, int H, double *o) {
    double d[3] = {p[0] - c.e[0], p[1] - c.e[1], p[2] - c.e[2]};
    double z = d[0] * c.f[0] + d[1] * c.f[1] + d[2] * c.f[2]; if (z < 0.002) return false;
    double x = d[0] * c.r[0] + d[1] * c.r[1] + d[2] * c.r[2], y = d[0] * c.u[0] + d[1] * c.u[1] + d[2] * c.u[2];
    o[0] = (x / (z * c.tanh * c.aspect) * 0.5 + 0.5) * W; o[1] = (0.5 - y / (z * c.tanh) * 0.5) * H; o[2] = z; return true;
}
static void renderIds(const Cam &c, int W, int H, vector<int> &id, vector<float> &zb) {
    id.assign(W * H, -1); zb.assign(W * H, 1e30f);
    for (int t = 0; t < NT; t++) {
        double a[3], b[3], d[3];
        if (!proj(c, &P[3 * I[3 * t]], W, H, a) || !proj(c, &P[3 * I[3 * t + 1]], W, H, b) || !proj(c, &P[3 * I[3 * t + 2]], W, H, d)) continue;
        double minx = min({a[0], b[0], d[0]}), maxx = max({a[0], b[0], d[0]}), miny = min({a[1], b[1], d[1]}), maxy = max({a[1], b[1], d[1]});
        if (maxx < 0 || maxy < 0 || minx >= W || miny >= H) continue;
        double area = (b[0] - a[0]) * (d[1] - a[1]) - (b[1] - a[1]) * (d[0] - a[0]); if (fabs(area) < 1e-9) continue;
        int x0 = max(0, (int)floor(minx)), x1 = min(W - 1, (int)ceil(maxx)), y0 = max(0, (int)floor(miny)), y1 = min(H - 1, (int)ceil(maxy));
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            double px = x + 0.5, py = y + 0.5;
            double w0 = ((b[0] - px) * (d[1] - py) - (b[1] - py) * (d[0] - px)) / area;
            double w1 = ((d[0] - px) * (a[1] - py) - (d[1] - py) * (a[0] - px)) / area;
            double w2 = 1 - w0 - w1;
            if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue;
            double z = 1.0 / (w0 / a[2] + w1 / b[2] + w2 / d[2]);
            if (z < zb[y * W + x]) { zb[y * W + x] = (float)z; id[y * W + x] = t; }
        }
    }
}
int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); int hdr[2]; fread(hdr, 4, 2, f); NV = hdr[0]; NT = hdr[1];
    P.resize(NV * 3); C.resize(NV * 3); I.resize(NT * 3);
    fread(P.data(), 4, NV * 3, f); fread(C.data(), 4, NV * 3, f); fread(I.data(), 4, NT * 3, f); fclose(f);
    string mode = argv[2];
    if (mode == "prev") {
        double e[3] = {atof(argv[4]), atof(argv[5]), atof(argv[6])}, t[3] = {atof(argv[7]), atof(argv[8]), atof(argv[9])};
        double fov = atof(argv[10]); int W = atoi(argv[11]), H = atoi(argv[12]);
        Cam c; camSet(c, e, t, fov, (double)W / H);
        vector<int> id; vector<float> zb; renderIds(c, W, H, id, zb);
        FILE *o = fopen(argv[3], "wb"); fprintf(o, "P6\n%d %d\n255\n", W, H);
        double L[3] = {0.4, 0.8, 0.45}; double ll = sqrt(L[0] * L[0] + L[1] * L[1] + L[2] * L[2]); for (int k = 0; k < 3; k++) L[k] /= ll;
        for (int i = 0; i < W * H; i++) {
            unsigned char px[3];
            if (id[i] < 0) { px[0] = 150; px[1] = 180; px[2] = 215; }
            else {
                int t2 = id[i]; const float *a = &P[3 * I[3 * t2]], *b = &P[3 * I[3 * t2 + 1]], *d = &P[3 * I[3 * t2 + 2]];
                double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], wx = d[0] - a[0], wy = d[1] - a[1], wz = d[2] - a[2];
                double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx; double nl = sqrt(nx * nx + ny * ny + nz * nz) + 1e-30; nx /= nl; ny /= nl; nz /= nl;
                double dx = e[0] - a[0], dy = e[1] - a[1], dz = e[2] - a[2]; if (nx * dx + ny * dy + nz * dz < 0) { nx = -nx; ny = -ny; nz = -nz; }
                double lam = max(0.0, nx * L[0] + ny * L[1] + nz * L[2]); double sh = 0.35 + 0.65 * lam;
                double col[3]; for (int k = 0; k < 3; k++) col[k] = (C[3 * I[3 * t2] + k] + C[3 * I[3 * t2 + 1] + k] + C[3 * I[3 * t2 + 2] + k]) / 3.0;
                for (int k = 0; k < 3; k++) { double v = sqrt(col[k]) * sh * 1.35; px[k] = (unsigned char)min(255.0, v * 255); }
            }
            fwrite(px, 1, 3, o);
        }
        fclose(o);
    } else {
        ifstream in(argv[3]); int W = atoi(argv[5]), H = atoi(argv[6]);
        vector<unsigned char> vis(NT, 0); double e[3], t[3]; int n = 0;
        while (in >> e[0] >> e[1] >> e[2] >> t[0] >> t[1] >> t[2]) {
            Cam c; camSet(c, e, t, 80.0, (double)W / H);
            vector<int> id; vector<float> zb; renderIds(c, W, H, id, zb);
            for (int i = 0; i < W * H; i++) if (id[i] >= 0) vis[id[i]] = 1;
            if (++n % 50 == 0) fprintf(stderr, "vistas %d\n", n);
        }
        FILE *o = fopen(argv[4], "wb"); fwrite(vis.data(), 1, NT, o); fclose(o);
        long s = 0; for (auto v : vis) s += v; fprintf(stderr, "visíveis %ld de %d\n", s, NT);
    }
    return 0;
}
