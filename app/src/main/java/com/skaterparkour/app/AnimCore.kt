package com.skaterparkour.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Quantidade de ossos animados (pelve, coluna, cabeça, braços e pernas). */
const val NBONE = 22

/** Esqueleto do personagem Meshy (grafo de nós do glTF, 95 juntas de skin). */
class RigData(bytes: ByteArray) {
    val nNodes: Int; val nJoints: Int; val pelvisNode: Int
    val parent: IntArray; val restT: FloatArray; val restQ: FloatArray; val order: IntArray
    val jointNode: IntArray; val ibm: FloatArray; val boneNode: IntArray

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        nNodes = b.int; nJoints = b.int; pelvisNode = b.int; val nb = b.int
        require(nb == NBONE)
        parent = IntArray(nNodes) { b.int }
        restT = FloatArray(nNodes * 3) { b.float }
        restQ = FloatArray(nNodes * 4) { b.float }
        order = IntArray(nNodes) { b.int }
        jointNode = IntArray(nJoints) { b.int }
        ibm = FloatArray(nJoints * 16) { b.float }
        boneNode = IntArray(nb) { b.int }
    }
}

class Clip(val name: String, val nf: Int, val q: ShortArray, val pel: FloatArray, val root: FloatArray) {
    val dur: Float get() = max((nf - 1) / 30f, 1e-3f)
}

/** Pose = quaternion local dos 22 ossos + posição da pelve + movimento da raiz (metros, no espaço do clipe). */
class Pose {
    val q = FloatArray(NBONE * 4)
    val pel = FloatArray(3)
    val root = FloatArray(3)
    fun copy(o: Pose) { System.arraycopy(o.q, 0, q, 0, q.size); System.arraycopy(o.pel, 0, pel, 0, 3); System.arraycopy(o.root, 0, root, 0, 3) }
}

class ClipSet(bytes: ByteArray) {
    val clips = HashMap<String, Clip>()

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.int
        repeat(n) {
            val l = b.short.toInt() and 0xFFFF
            val nm = ByteArray(l); b.get(nm)
            val nf = b.int
            val q = ShortArray(nf * NBONE * 4); val pel = FloatArray(nf * 3); val root = FloatArray(nf * 3)
            for (f in 0 until nf) {
                for (i in 0 until NBONE * 4) q[f * NBONE * 4 + i] = b.short
                for (i in 0 until 3) pel[f * 3 + i] = b.float
                for (i in 0 until 3) root[f * 3 + i] = b.float
            }
            val name = String(nm)
            clips[name] = Clip(name, nf, q, pel, root)
        }
    }

    operator fun get(n: String): Clip = clips[n] ?: throw IllegalArgumentException("clip $n")
    fun has(n: String) = clips.containsKey(n)

    /** Amostra o clipe no tempo t (s). Se loop, repete. */
    fun sample(c: Clip, t0: Float, loop: Boolean, out: Pose) {
        var t = t0
        val d = c.dur
        if (loop) { t %= d; if (t < 0) t += d } else t = if (t < 0f) 0f else if (t > d) d else t
        val f = t * 30f
        val i0 = min(f.toInt(), c.nf - 1)
        val i1 = if (loop && i0 + 1 >= c.nf) 0 else min(i0 + 1, c.nf - 1)
        val a = f - i0
        val o0 = i0 * NBONE * 4; val o1 = i1 * NBONE * 4
        val k = 1f / 32767f
        for (j in 0 until NBONE) {
            val p = j * 4
            val x0 = c.q[o0 + p] * k; val y0 = c.q[o0 + p + 1] * k; val z0 = c.q[o0 + p + 2] * k; val w0 = c.q[o0 + p + 3] * k
            var x1 = c.q[o1 + p] * k; var y1 = c.q[o1 + p + 1] * k; var z1 = c.q[o1 + p + 2] * k; var w1 = c.q[o1 + p + 3] * k
            if (x0 * x1 + y0 * y1 + z0 * z1 + w0 * w1 < 0) { x1 = -x1; y1 = -y1; z1 = -z1; w1 = -w1 }
            val x = x0 + (x1 - x0) * a; val y = y0 + (y1 - y0) * a; val z = z0 + (z1 - z0) * a; val w = w0 + (w1 - w0) * a
            val l = 1f / sqrt(x * x + y * y + z * z + w * w)
            out.q[p] = x * l; out.q[p + 1] = y * l; out.q[p + 2] = z * l; out.q[p + 3] = w * l
        }
        for (i in 0 until 3) {
            out.pel[i] = c.pel[i0 * 3 + i] + (c.pel[i1 * 3 + i] - c.pel[i0 * 3 + i]) * a
            out.root[i] = c.root[i0 * 3 + i] + (c.root[i1 * 3 + i] - c.root[i0 * 3 + i]) * a
        }
    }

    /** Só o movimento da raiz (x,y,z) no tempo t, sem repetir. */
    fun rootAt(c: Clip, t: Float, out: FloatArray) {
        val d = c.dur
        val tt = if (t < 0f) 0f else if (t > d) d else t
        val f = tt * 30f
        val i0 = min(f.toInt(), c.nf - 1); val i1 = min(i0 + 1, c.nf - 1); val a = f - i0
        for (i in 0 until 3) out[i] = c.root[i0 * 3 + i] + (c.root[i1 * 3 + i] - c.root[i0 * 3 + i]) * a
    }

    companion object {
        /** out = a*(1-w) + b*w (nlerp com correção de sinal). */
        fun blend(a: Pose, b: Pose, w: Float, out: Pose) {
            if (w <= 0f) { if (out !== a) out.copy(a); return }
            if (w >= 1f) { if (out !== b) out.copy(b); return }
            val iw = 1 - w
            for (j in 0 until NBONE) {
                val p = j * 4
                val s = if (a.q[p] * b.q[p] + a.q[p + 1] * b.q[p + 1] + a.q[p + 2] * b.q[p + 2] + a.q[p + 3] * b.q[p + 3] < 0) -w else w
                val x = a.q[p] * iw + b.q[p] * s; val y = a.q[p + 1] * iw + b.q[p + 1] * s
                val z = a.q[p + 2] * iw + b.q[p + 2] * s; val ww = a.q[p + 3] * iw + b.q[p + 3] * s
                val l = 1f / sqrt(x * x + y * y + z * z + ww * ww)
                out.q[p] = x * l; out.q[p + 1] = y * l; out.q[p + 2] = z * l; out.q[p + 3] = ww * l
            }
            for (i in 0 until 3) { out.pel[i] = a.pel[i] * iw + b.pel[i] * w; out.root[i] = a.root[i] * iw + b.root[i] * w }
        }
    }
}
