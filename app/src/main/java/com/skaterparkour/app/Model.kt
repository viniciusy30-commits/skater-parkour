package com.skaterparkour.app

import android.content.Context
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun readFloats(bb: ByteBuffer, n: Int): FloatArray {
    val a = FloatArray(n)
    bb.asFloatBuffer().get(a)
    bb.position(bb.position() + n * 4)
    return a
}

/** Malha com esqueleto (formato model.bin gerado a partir do GLB do protótipo). */
class SkinModel(ctx: Context) {
    val vertexCount: Int
    val indexCount: Int
    val boneCount: Int
    val pos: FloatArray
    val nor: FloatArray
    val uv: FloatArray
    val joints: ByteArray
    val weights: FloatArray
    val indices: ShortArray
    val parent: IntArray
    val restT: FloatArray
    val restQ: FloatArray
    val ibm: FloatArray
    val names: List<String>

    init {
        val bytes = ctx.assets.open("model.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = bb.int
        require(magic == 0x314C444D) { "model.bin inválido" }
        vertexCount = bb.int
        indexCount = bb.int
        boneCount = bb.int
        pos = readFloats(bb, vertexCount * 3)
        nor = readFloats(bb, vertexCount * 3)
        uv = readFloats(bb, vertexCount * 2)
        joints = ByteArray(vertexCount * 4)
        bb.get(joints)
        weights = readFloats(bb, vertexCount * 4)
        indices = ShortArray(indexCount)
        bb.asShortBuffer().get(indices)
        bb.position(bb.position() + indexCount * 2)
        parent = IntArray(boneCount)
        restT = FloatArray(boneCount * 3)
        restQ = FloatArray(boneCount * 4)
        ibm = FloatArray(boneCount * 16)
        for (i in 0 until boneCount) {
            parent[i] = bb.int
            for (k in 0 until 3) restT[i * 3 + k] = bb.float
            for (k in 0 until 4) restQ[i * 4 + k] = bb.float
            for (k in 0 until 16) ibm[i * 16 + k] = bb.float
        }
        val len = bb.int
        val nb = ByteArray(len)
        bb.get(nb)
        names = String(nb, Charsets.UTF_8).split("\n")
    }

    fun boneIndex(name: String): Int = names.indexOf(name)
}

/** Uma animação: frames x (3 posição do quadril + bones*4 quaternions). */
class Clip(val name: String, val frames: Int, val fps: Float, private val data: FloatArray, private val bones: Int) {
    private val stride = 3 + bones * 4
    val duration: Float get() = (frames - 1) / fps

    fun sample(tIn: Float, loop: Boolean, q: FloatArray, p: FloatArray) {
        val dur = duration
        var t = tIn
        if (loop && dur > 0f) {
            t %= dur
            if (t < 0f) t += dur
        } else {
            t = t.coerceIn(0f, dur)
        }
        val f = t * fps
        var i0 = f.toInt()
        if (i0 > frames - 2) i0 = frames - 2
        if (i0 < 0) i0 = 0
        val a = f - i0
        val s0 = i0 * stride
        val s1 = (i0 + 1) * stride
        for (k in 0 until 3) p[k] = data[s0 + k] + (data[s1 + k] - data[s0 + k]) * a
        for (b in 0 until bones) {
            val o0 = s0 + 3 + b * 4
            val o1 = s1 + 3 + b * 4
            val dot = data[o0] * data[o1] + data[o0 + 1] * data[o1 + 1] + data[o0 + 2] * data[o1 + 2] + data[o0 + 3] * data[o1 + 3]
            val sg = if (dot < 0f) -1f else 1f
            var x = data[o0] + (sg * data[o1] - data[o0]) * a
            var y = data[o0 + 1] + (sg * data[o1 + 1] - data[o0 + 1]) * a
            var z = data[o0 + 2] + (sg * data[o1 + 2] - data[o0 + 2]) * a
            var w = data[o0 + 3] + (sg * data[o1 + 3] - data[o0 + 3]) * a
            val n = Math.sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
            if (n > 1e-6f) {
                x /= n; y /= n; z /= n; w /= n
            }
            q[b * 4] = x; q[b * 4 + 1] = y; q[b * 4 + 2] = z; q[b * 4 + 3] = w
        }
    }

    /** Só a posição do quadril (barato), usada para sincronizar o movimento do personagem. */
    fun hips(tIn: Float, p: FloatArray) {
        val t = tIn.coerceIn(0f, duration)
        val f = t * fps
        var i0 = f.toInt()
        if (i0 > frames - 2) i0 = frames - 2
        if (i0 < 0) i0 = 0
        val a = f - i0
        val s0 = i0 * stride
        val s1 = s0 + stride
        for (k in 0 until 3) p[k] = data[s0 + k] + (data[s1 + k] - data[s0 + k]) * a
    }

    companion object {
        fun loadAll(ctx: Context): Map<String, Clip> {
            val bytes = ctx.assets.open("anim.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.int == 0x31414E41) { "anim.bin inválido" }
            val count = bb.int
            val bones = bb.int
            val fps = bb.int.toFloat()
            val map = HashMap<String, Clip>()
            for (c in 0 until count) {
                val nl = bb.int
                val nb = ByteArray(nl)
                bb.get(nb)
                val name = String(nb, Charsets.UTF_8)
                val frames = bb.int
                val data = readFloats(bb, frames * (3 + bones * 4))
                map[name] = Clip(name, frames, fps, data, bones)
            }
            return map
        }
    }
}

/** Calcula as matrizes de pele (3 linhas por osso, formato do shader) a partir de uma pose. */
class Skeleton(private val m: SkinModel) {
    val world = FloatArray(m.boneCount * 16)
    private val local = FloatArray(16)
    private val tmp = FloatArray(16)

    fun update(q: FloatArray, hips: FloatArray, outSkin: FloatArray) {
        for (i in 0 until m.boneCount) {
            val x = q[i * 4]
            val y = q[i * 4 + 1]
            val z = q[i * 4 + 2]
            val w = q[i * 4 + 3]
            local[0] = 1f - 2f * (y * y + z * z)
            local[1] = 2f * (x * y + z * w)
            local[2] = 2f * (x * z - y * w)
            local[3] = 0f
            local[4] = 2f * (x * y - z * w)
            local[5] = 1f - 2f * (x * x + z * z)
            local[6] = 2f * (y * z + x * w)
            local[7] = 0f
            local[8] = 2f * (x * z + y * w)
            local[9] = 2f * (y * z - x * w)
            local[10] = 1f - 2f * (x * x + y * y)
            local[11] = 0f
            if (i == 0) {
                local[12] = hips[0]; local[13] = hips[1]; local[14] = hips[2]
            } else {
                local[12] = m.restT[i * 3]; local[13] = m.restT[i * 3 + 1]; local[14] = m.restT[i * 3 + 2]
            }
            local[15] = 1f
            val p = m.parent[i]
            if (p < 0) {
                System.arraycopy(local, 0, world, i * 16, 16)
            } else {
                Matrix.multiplyMM(world, i * 16, world, p * 16, local, 0)
            }
        }
        for (i in 0 until m.boneCount) {
            Matrix.multiplyMM(tmp, 0, world, i * 16, m.ibm, i * 16)
            for (r in 0 until 3) {
                val o = (i * 3 + r) * 4
                outSkin[o] = tmp[r]
                outSkin[o + 1] = tmp[4 + r]
                outSkin[o + 2] = tmp[8 + r]
                outSkin[o + 3] = tmp[12 + r]
            }
        }
    }
}
