package com.skaterparkour.app

import kotlin.math.max
import kotlin.math.min

/** FK do glTF: pose (22 ossos + pelve) -> matrizes de skin das 95 juntas. */
class Skeleton(private val rig: RigData) {
    val world = FloatArray(rig.nNodes * 16)
    val skin = FloatArray(rig.nJoints * 16)
    val pelvisNode: Int get() = rig.pelvisNode
    private val lm = FloatArray(16)
    private val boneOf = IntArray(rig.nNodes) { -1 }

    init { for (j in 0 until NBONE) boneOf[rig.boneNode[j]] = j }

    fun solve(p: Pose) {
        for (n in rig.order) {
            val pel = n == rig.pelvisNode
            val tx = if (pel) p.pel[0] else rig.restT[n * 3]
            val ty = if (pel) p.pel[1] else rig.restT[n * 3 + 1]
            val tz = if (pel) p.pel[2] else rig.restT[n * 3 + 2]
            val bj = boneOf[n]
            if (bj >= 0) M4.qt(lm, p.q, bj * 4, tx, ty, tz) else M4.qt(lm, rig.restQ, n * 4, tx, ty, tz)
            val par = rig.parent[n]
            if (par < 0) System.arraycopy(lm, 0, world, n * 16, 16) else M4.mul(world, n * 16, world, par * 16, lm, 0)
        }
        for (j in 0 until rig.nJoints) M4.mul(skin, j * 16, world, rig.jointNode[j] * 16, rig.ibm, j * 16)
    }
}

/** Amostra o clipe do Player e faz a transição suave (crossfade) quando o clipe muda. */
class Animator(private val cs: ClipSet) {
    val out = Pose()
    private val snap = Pose()
    private val tgt = Pose()
    private var fade = 1f
    private var fd = 0.2f

    fun changed(dur: Float) { snap.copy(out); fade = 0f; fd = max(dur, 0.01f) }

    fun update(clip: String, t: Float, loop: Boolean, dt: Float) {
        cs.sample(cs[clip], t, loop, tgt)
        if (fade < 1f) {
            fade = min(1f, fade + dt / fd)
            ClipSet.blend(snap, tgt, smooth(fade), out)
        } else out.copy(tgt)
    }
}
