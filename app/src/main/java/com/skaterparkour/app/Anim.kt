package com.skaterparkour.app

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

const val NB = 80

/** Esqueleto do guerreiro (rig UE5, 80 ossos). */
class Rig(ctx: Context) {
    val parent = IntArray(NB)
    val inv = FloatArray(NB * 16)
    val wpre = FloatArray(16)
    val attachBone = IntArray(2)
    val attachM = arrayOf(FloatArray(16), FloatArray(16))
    var wScale = 0.58f; var wsx = 1.3f; var wsz = 1.3f

    init {
        val b = ByteBuffer.wrap(Gl.readAsset(ctx, "w/rig.bin")).order(ByteOrder.LITTLE_ENDIAN)
        b.int
        for (i in 0 until NB) parent[i] = b.int
        for (i in 0 until NB * 16) inv[i] = b.float
        for (i in 0 until 16) wpre[i] = b.float
        for (s in 0 until 2) { attachBone[s] = b.int; for (i in 0 until 16) attachM[s][i] = b.float }
        wScale = b.float; wsx = b.float; wsz = b.float
    }
}

class Clip(val name: String, val nf: Int, val fps: Float, val q: ShortArray, val pt: FloatArray) {
    val dur get() = maxOf((nf - 1) / fps, 1e-3f)
}

/** Pose = quaternion local de todos os ossos + posição da pélvis. */
class Pose {
    val q = FloatArray(NB * 4)
    val pel = FloatArray(3)
    fun copy(o: Pose) { System.arraycopy(o.q, 0, q, 0, q.size); System.arraycopy(o.pel, 0, pel, 0, 3) }
}

class Anims(ctx: Context) {
    val rest = FloatArray(NB * 3)
    val clips = HashMap<String, Clip>()

    init {
        val b = ByteBuffer.wrap(Gl.readAsset(ctx, "w/anim.bin")).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.int
        for (i in 0 until NB * 3) rest[i] = b.float
        repeat(n) {
            val l = b.int
            val nm = ByteArray(l); b.get(nm)
            val nf = b.int; val fps = b.float
            val q = ShortArray(nf * NB * 4); val pt = FloatArray(nf * 3)
            for (f in 0 until nf) {
                for (i in 0 until NB * 4) q[f * NB * 4 + i] = b.short
                for (i in 0 until 3) pt[f * 3 + i] = b.float
            }
            val name = String(nm)
            clips[name] = Clip(name, nf, fps, q, pt)
        }
    }

    operator fun get(n: String): Clip = clips[n] ?: throw IllegalArgumentException("clip $n")

    /** Amostra o clipe no tempo t (s). */
    fun sample(c: Clip, t0: Float, loop: Boolean, out: Pose) {
        var t = t0
        val d = c.dur
        if (loop) { t %= d; if (t < 0) t += d } else t = clampf(t, 0f, d)
        val f = t * c.fps
        val i0 = minOf(f.toInt(), c.nf - 1)
        val i1 = if (loop && i0 + 1 >= c.nf) 0 else minOf(i0 + 1, c.nf - 1)
        val a = f - i0
        val o0 = i0 * NB * 4; val o1 = i1 * NB * 4
        val k = 1f / 32767f
        for (j in 0 until NB) {
            val p = j * 4
            val x0 = c.q[o0 + p] * k; val y0 = c.q[o0 + p + 1] * k; val z0 = c.q[o0 + p + 2] * k; val w0 = c.q[o0 + p + 3] * k
            var x1 = c.q[o1 + p] * k; var y1 = c.q[o1 + p + 1] * k; var z1 = c.q[o1 + p + 2] * k; var w1 = c.q[o1 + p + 3] * k
            if (x0 * x1 + y0 * y1 + z0 * z1 + w0 * w1 < 0) { x1 = -x1; y1 = -y1; z1 = -z1; w1 = -w1 }
            val x = x0 + (x1 - x0) * a; val y = y0 + (y1 - y0) * a; val z = z0 + (z1 - z0) * a; val w = w0 + (w1 - w0) * a
            val l = 1f / sqrt(x * x + y * y + z * z + w * w)
            out.q[p] = x * l; out.q[p + 1] = y * l; out.q[p + 2] = z * l; out.q[p + 3] = w * l
        }
        for (i in 0 until 3) out.pel[i] = c.pt[i0 * 3 + i] + (c.pt[i1 * 3 + i] - c.pt[i0 * 3 + i]) * a
    }

    companion object {
        /** out = a*(1-w) + b*w (nlerp com correção de sinal). out pode ser a. */
        fun blend(a: Pose, b: Pose, w: Float, out: Pose) {
            if (w <= 0f) { if (out !== a) out.copy(a); return }
            if (w >= 1f) { if (out !== b) out.copy(b); return }
            val iw = 1 - w
            for (j in 0 until NB) {
                val p = j * 4
                val s = if (a.q[p] * b.q[p] + a.q[p + 1] * b.q[p + 1] + a.q[p + 2] * b.q[p + 2] + a.q[p + 3] * b.q[p + 3] < 0) -w else w
                val x = a.q[p] * iw + b.q[p] * s; val y = a.q[p + 1] * iw + b.q[p + 1] * s
                val z = a.q[p + 2] * iw + b.q[p + 2] * s; val ww = a.q[p + 3] * iw + b.q[p + 3] * s
                val l = 1f / sqrt(x * x + y * y + z * z + ww * ww)
                out.q[p] = x * l; out.q[p + 1] = y * l; out.q[p + 2] = z * l; out.q[p + 3] = ww * l
            }
            for (i in 0 until 3) out.pel[i] = a.pel[i] * iw + b.pel[i] * w
        }
    }
}

/** Saída final da animação com transição suave (crossfade). Como no HTML, o clipe anterior continua andando durante a mistura. */
class Animator(private val anims: Anims) {
    val out = Pose()
    private val snap = Pose()
    private var fade = 1f
    private var fadeDur = 0.2f
    private var prevClip: Clip? = null
    private var prevT = 0f

    /** Congela a pose atual e mistura para a próxima em [dur] segundos. */
    fun transition(dur: Float) {
        prevClip = null
        if (dur <= 0f) { fade = 1f; return }
        snap.copy(out); fade = 0f; fadeDur = dur
    }

    /** Mistura a partir de um clipe que segue tocando (setClip do HTML). */
    fun transitionClip(c: Clip, t: Float, dur: Float) {
        if (dur <= 0f) { fade = 1f; prevClip = null; return }
        snap.copy(out); prevClip = c; prevT = t; fade = 0f; fadeDur = dur
    }

    fun apply(target: Pose, dt: Float) {
        if (fade < 1f) {
            fade = minOf(1f, fade + dt / fadeDur)
            val pc = prevClip
            if (pc != null) anims.sample(pc, minOf(prevT + fade * fadeDur, pc.dur - 1e-3f), false, snap)
            Anims.blend(snap, target, smooth(fade), out)
        } else out.copy(target)
    }
}

/** FK + matrizes de skin. */
class Skeleton(val rig: Rig, val anims: Anims) {
    val world = FloatArray(NB * 16)
    val skin = FloatArray(NB * 16)
    private val lm = FloatArray(16)

    fun solve(p: Pose) {
        for (k in 0 until NB) {
            val t = if (k == 1) p.pel else null
            val tx = t?.get(0) ?: anims.rest[k * 3]
            val ty = t?.get(1) ?: anims.rest[k * 3 + 1]
            val tz = t?.get(2) ?: anims.rest[k * 3 + 2]
            M4.qt(lm, p.q, k * 4, tx, ty, tz)
            val par = rig.parent[k]
            if (par < 0) M4.mul(world, k * 16, rig.wpre, 0, lm, 0) else M4.mul(world, k * 16, world, par * 16, lm, 0)
        }
        for (k in 0 until NB) M4.mul(skin, k * 16, world, k * 16, rig.inv, k * 16)
    }

    /** Matriz da arma (espaço do modelo) do lado s (0=esq,1=dir). */
    fun weapon(s: Int, out: FloatArray) {
        val tmp = FloatArray(16)
        M4.mul(tmp, 0, world, rig.attachBone[s] * 16, rig.attachM[s], 0)
        val sc = FloatArray(16); M4.id(sc)
        sc[0] = rig.wScale * rig.wsx; sc[5] = rig.wScale; sc[10] = rig.wScale * rig.wsz
        M4.mul(out, tmp, sc)
    }
}
