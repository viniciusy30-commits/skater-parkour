package com.skaterparkour.app

import kotlin.math.*

/** Bloco sólido alinhado aos eixos. kind: 0 = sólido, 2 = trave de equilíbrio. */
class Box(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float, val kind: Int = 0, val name: String = "") {
    val top get() = y1
    fun containsXZ(x: Float, z: Float, r: Float = 0f) = x >= x0 - r && x <= x1 + r && z >= z0 - r && z <= z1 + r
}

class Water(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val surface: Float) {
    fun contains(x: Float, z: Float) = x >= x0 && x <= x1 && z >= z0 && z <= z1
}

/** Resultado de um raio horizontal contra os blocos. */
class Hit(val dist: Float, val top: Float, val nx: Float, val nz: Float, val box: Box, val depth: Float, val farGround: Float, val exitDist: Float)

class World {
    val boxes = ArrayList<Box>()
    /** Blocos especiais (trave, canos, apoios): kind != 0. */
    val special = ArrayList<Box>()
    val waters = ArrayList<Water>()

    // grade espacial no plano XZ: consultas só olham os blocos da vizinhança
    private val cells = arrayOfNulls<ArrayList<Box>>(GN * GN)
    private fun ci(v: Float): Int = ((v - GMIN) / CELL).toInt().coerceIn(0, GN - 1)

    fun add(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, kind: Int = 0, name: String = ""): Box {
        val b = Box(min(x0, x1), min(y0, y1), min(z0, z1), max(x0, x1), max(y0, y1), max(z0, z1), kind, name)
        boxes.add(b); if (kind != 0) special.add(b)
        for (cx in ci(b.x0)..ci(b.x1)) for (cz in ci(b.z0)..ci(b.z1)) {
            val k = cx * GN + cz
            var l = cells[k]; if (l == null) { l = ArrayList(); cells[k] = l }
            l.add(b)
        }
        return b
    }

    private inline fun forBoxes(x0: Float, z0: Float, x1: Float, z1: Float, f: (Box) -> Unit) {
        for (cx in ci(x0)..ci(x1)) for (cz in ci(z0)..ci(z1)) {
            val l = cells[cx * GN + cz] ?: continue
            for (i in 0 until l.size) f(l[i])
        }
    }

    fun waterAt(x: Float, z: Float): Water? { for (w in waters) if (w.contains(x, z)) return w; return null }

    /** Altura do chão sob o círculo (x,z,r): topo mais alto <= maxY. Retorna -1000 se não há. */
    fun groundAt(x: Float, z: Float, r: Float, maxY: Float): Float {
        var best = -1000f
        forBoxes(x - r, z - r, x + r, z + r) { b ->
            if (!(b.kind == 3 || b.y1 > maxY || b.y1 <= best) && b.containsXZ(x, z, r)) best = b.y1
        }
        return best
    }

    fun boxUnder(x: Float, z: Float, y: Float): Box? {
        var best: Box? = null
        forBoxes(x - 0.05f, z - 0.05f, x + 0.05f, z + 0.05f) { b ->
            if (b.kind != 3 && b.containsXZ(x, z, 0.05f) && b.y1 <= y + 0.02f && (best == null || b.y1 > best!!.y1 + 0.001f || (abs(b.y1 - best!!.y1) <= 0.001f && b.kind == 2))) best = b
        }
        return best
    }

    /** Empurra o círculo para fora de blocos que bloqueiam entre y+step e y+h. */
    fun pushOut(p: FloatArray, y: Float, r: Float, h: Float, step: Float) {
        for (it in 0 until 3) {
            forBoxes(p[0] - r, p[2] - r, p[0] + r, p[2] + r) { b ->
                if (!(b.y1 <= y + step || b.y0 >= y + h) && !(p[0] + r <= b.x0 || p[0] - r >= b.x1 || p[2] + r <= b.z0 || p[2] - r >= b.z1)) {
                    val px = min(p[0] + r - b.x0, b.x1 - (p[0] - r)); val pz = min(p[2] + r - b.z0, b.z1 - (p[2] - r))
                    if (px < pz) { if (p[0] < (b.x0 + b.x1) / 2) p[0] = b.x0 - r else p[0] = b.x1 + r }
                    else { if (p[2] < (b.z0 + b.z1) / 2) p[2] = b.z0 - r else p[2] = b.z1 + r }
                }
            }
        }
    }

    /** Há algum bloco ocupando o cilindro (x,z,r) entre y0 e y1? */
    fun blocked(x: Float, z: Float, r: Float, y0: Float, y1: Float): Boolean {
        forBoxes(x - r, z - r, x + r, z + r) { b -> if (!(b.y1 <= y0 || b.y0 >= y1) && b.containsXZ(x, z, r)) return true }
        return false
    }

    /**
     * Raio horizontal a partir de (x,z) na direção (dx,dz) (unitária). Procura o primeiro bloco cujo topo está acima de [minTop]
     * e cuja base está abaixo de [maxBase]. Mede profundidade (comprimento do bloco no raio) e o chão do outro lado.
     */
    fun probe(x: Float, z: Float, dx: Float, dz: Float, maxD: Float, minTop: Float, maxBase: Float): Hit? {
        var bestT = 1e9f; var bb: Box? = null; var bnx = 0f; var bnz = 0f; var tExit = 0f
        val ex = x + dx * maxD; val ez = z + dz * maxD
        forBoxes(min(x, ex) - 0.05f, min(z, ez) - 0.05f, max(x, ex) + 0.05f, max(z, ez) + 0.05f) { b ->
            run {
                if (b.kind >= 3 || b.y1 < minTop || b.y0 > maxBase) return@run
                var t0 = -1e9f; var t1 = 1e9f; var nx = 0f; var nz = 0f
                // eixo x
                if (abs(dx) < 1e-6f) { if (x < b.x0 || x > b.x1) return@run }
                else {
                    var ta = (b.x0 - x) / dx; var tb = (b.x1 - x) / dx; var sn = -1f
                    if (ta > tb) { val s = ta; ta = tb; tb = s; sn = 1f }
                    if (ta > t0) { t0 = ta; nx = sn; nz = 0f }
                    if (tb < t1) t1 = tb
                }
                if (abs(dz) < 1e-6f) { if (z < b.z0 || z > b.z1) return@run }
                else {
                    var ta = (b.z0 - z) / dz; var tb = (b.z1 - z) / dz; var sn = -1f
                    if (ta > tb) { val s = ta; ta = tb; tb = s; sn = 1f }
                    if (ta > t0) { t0 = ta; nx = 0f; nz = sn }
                    if (tb < t1) t1 = tb
                }
                if (t1 < max(t0, 0f) || t0 > maxD) return@run
                if (t0 < 0f) { t0 = 0f }
                if (t0 < bestT) { bestT = t0; bb = b; bnx = nx; bnz = nz; tExit = t1 }
            }
        }
        val b = bb ?: return null
        // profundidade: continua enquanto houver blocos com topo próximo
        var dEx = tExit
        var guard = 0
        while (guard++ < 8) {
            val px = x + dx * (dEx + 0.06f); val pz = z + dz * (dEx + 0.06f)
            var nxt: Box? = null
            forBoxes(px, pz, px, pz) { c -> if (c !== b && abs(c.y1 - b.y1) < 0.25f && c.containsXZ(px, pz)) nxt = c }
            if (nxt == null) break
            dEx += 0.12f
        }
        val fx = x + dx * (dEx + 0.45f); val fz = z + dz * (dEx + 0.45f)
        val fg = groundAt(fx, fz, 0.2f, b.y1 - 0.01f)
        return Hit(bestT, b.y1, bnx, bnz, b, dEx - bestT, fg, dEx)
    }

    companion object {
        const val CELL = 2f
        const val GN = 64
        const val GMIN = -64f   // a grade cobre x,z em [-64, 64]; blocos fora ficam na borda
    }
}
