package com.skaterparkour.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.Buffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {

    private val world = World()

    // modelo e animação
    private lateinit var model: SkinModel
    private lateinit var skeleton: Skeleton
    private var clipIdle: Clip? = null
    private var clipRun: Clip? = null
    private lateinit var skinOut: FloatArray
    private val qBlend by lazy { FloatArray(model.boneCount * 4) }
    private val qTmp by lazy { FloatArray(model.boneCount * 4) }
    private val hipsBlend = FloatArray(3)
    private val hipsTmp = FloatArray(3)
    private var wIdle = 1f
    private var wRun = 0f
    private var wAir = 0f
    private var tIdle = 0f
    private var tRun = 0f

    // GL
    private var progSkin = 0
    private var progWorld = 0
    private var progShadow = 0
    private var texBody = 0
    private var texNormal = 0
    private var vaoSkin = 0
    private var vaoCube = 0
    private var cubeIndexCount = 0
    private var vaoQuad = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val modelM = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val camPos = FloatArray(3)
    private var camTx = 0f
    private var camTy = 1f
    private var camTz = -4f
    private var lastNs = 0L
    private var aspect = 1.7f

    private val fogR = 0.62f
    private val fogG = 0.76f
    private val fogB = 0.92f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(fogR, fogG, fogB, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        progSkin = program(Shaders.SKIN_VS, Shaders.SKIN_FS)
        progWorld = program(Shaders.WORLD_VS, Shaders.WORLD_FS)
        progShadow = program(Shaders.SHADOW_VS, Shaders.SHADOW_FS)

        model = SkinModel(ctx)
        skeleton = Skeleton(model)
        skinOut = FloatArray(model.boneCount * 12)
        val clips = Clip.loadAll(ctx)
        clipIdle = clips["idle"]
        clipRun = clips["run"]

        texBody = loadTexture("body.jpg")
        texNormal = loadTexture("normal.png")

        buildSkinVao()
        buildCubeVao()
        buildQuadVao()
        lastNs = System.nanoTime()
        camTx = world.x; camTy = world.y + 0.9f; camTz = world.z
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 52f, aspect, 0.1f, 220f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1_000_000_000f
        lastNs = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f

        world.update(dt)
        updateAnimation(dt)
        updateCamera(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawWorld()
        drawShadow()
        drawCharacter()
    }

    // ---------------------------------------------------------------- animação

    private fun approach(v: Float, target: Float, k: Float): Float = v + (target - v) * k

    private fun updateAnimation(dt: Float) {
        val run = clipRun ?: return
        val idle = clipIdle ?: return

        val air = if (world.grounded) 0f else 1f
        val runT = (1f - air) * ((world.speed - 0.3f) / 0.9f).coerceIn(0f, 1f)
        val idleT = (1f - air) * (1f - runT)
        val k = (dt * 10f).coerceIn(0f, 1f)
        wAir = approach(wAir, air, k)
        wRun = approach(wRun, runT, k)
        wIdle = approach(wIdle, idleT, k)
        val sum = wAir + wRun + wIdle
        val nAir = wAir / sum
        val nRun = wRun / sum
        val nIdle = wIdle / sum

        val rate = (world.speed / 2.66f).coerceIn(0.45f, 1.7f)
        tRun += dt * rate
        tIdle += dt

        java.util.Arrays.fill(qBlend, 0f)
        java.util.Arrays.fill(hipsBlend, 0f)
        var first = true
        val bones = model.boneCount

        fun accumulate(w: Float) {
            if (w < 0.001f) return
            for (k2 in 0 until 3) hipsBlend[k2] += hipsTmp[k2] * w
            for (b in 0 until bones) {
                val o = b * 4
                var sg = 1f
                if (!first) {
                    val dot = qBlend[o] * qTmp[o] + qBlend[o + 1] * qTmp[o + 1] + qBlend[o + 2] * qTmp[o + 2] + qBlend[o + 3] * qTmp[o + 3]
                    if (dot < 0f) sg = -1f
                }
                qBlend[o] += qTmp[o] * w * sg
                qBlend[o + 1] += qTmp[o + 1] * w * sg
                qBlend[o + 2] += qTmp[o + 2] * w * sg
                qBlend[o + 3] += qTmp[o + 3] * w * sg
            }
            first = false
        }

        if (nIdle >= 0.001f) {
            idle.sample(tIdle, true, qTmp, hipsTmp)
            accumulate(nIdle)
        }
        if (nRun >= 0.001f) {
            run.sample(tRun, true, qTmp, hipsTmp)
            accumulate(nRun)
        }
        if (nAir >= 0.001f) {
            run.sample(4f / 30f, false, qTmp, hipsTmp)
            accumulate(nAir)
        }
        for (b in 0 until bones) {
            val o = b * 4
            val n = Math.sqrt((qBlend[o] * qBlend[o] + qBlend[o + 1] * qBlend[o + 1] + qBlend[o + 2] * qBlend[o + 2] + qBlend[o + 3] * qBlend[o + 3]).toDouble()).toFloat()
            if (n > 1e-6f) {
                qBlend[o] /= n; qBlend[o + 1] /= n; qBlend[o + 2] /= n; qBlend[o + 3] /= n
            } else {
                qBlend[o + 3] = 1f
            }
        }
        skeleton.update(qBlend, hipsBlend, skinOut)
    }

    // ---------------------------------------------------------------- câmera

    private fun updateCamera(dt: Float) {
        val k = (dt * 8f).coerceIn(0f, 1f)
        camTx += (world.x - camTx) * k
        camTy += (world.y + 0.85f - camTy) * k
        camTz += (world.z - camTz) * k
        val dist = 3.4f
        val cp = cos(world.camPitch)
        val sp = sin(world.camPitch)
        camPos[0] = camTx - sin(world.camYaw) * cp * dist
        camPos[1] = camTy + sp * dist
        camPos[2] = camTz - cos(world.camYaw) * cp * dist
        if (camPos[1] < 0.25f) camPos[1] = 0.25f
        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2], camTx, camTy, camTz, 0f, 1f, 0f)
    }

    // ---------------------------------------------------------------- desenho

    private fun drawWorld() {
        GLES30.glUseProgram(progWorld)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progWorld, "uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progWorld, "uCam"), camPos[0], camPos[1], camPos[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progWorld, "uFog"), fogR, fogG, fogB)
        val uModel = GLES30.glGetUniformLocation(progWorld, "uModel")
        val uColor = GLES30.glGetUniformLocation(progWorld, "uColor")
        val uGrid = GLES30.glGetUniformLocation(progWorld, "uGrid")
        GLES30.glBindVertexArray(vaoCube)

        // chão
        Matrix.setIdentityM(modelM, 0)
        Matrix.translateM(modelM, 0, 0f, -1f, 0f)
        Matrix.scaleM(modelM, 0, 400f, 1f, 400f)
        GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
        GLES30.glUniform3f(uColor, 0.46f, 0.50f, 0.56f)
        GLES30.glUniform1f(uGrid, 1f)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, cubeIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        for (b in world.blocks) {
            Matrix.setIdentityM(modelM, 0)
            Matrix.translateM(modelM, 0, b.cx, 0f, b.cz)
            Matrix.scaleM(modelM, 0, b.sx, b.h, b.sz)
            GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
            GLES30.glUniform3f(uColor, b.r, b.g, b.b)
            GLES30.glUniform1f(uGrid, 1f)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, cubeIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawShadow() {
        GLES30.glUseProgram(progShadow)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        val h = if (world.grounded) world.y else world.supportY
        val s = 0.55f - (world.y - h).coerceIn(0f, 1.5f) * 0.12f
        Matrix.setIdentityM(modelM, 0)
        Matrix.translateM(modelM, 0, world.x, h + 0.012f, world.z)
        Matrix.scaleM(modelM, 0, s, 1f, s)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progShadow, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progShadow, "uModel"), 1, false, modelM, 0)
        GLES30.glBindVertexArray(vaoQuad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawCharacter() {
        GLES30.glUseProgram(progSkin)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        Matrix.setIdentityM(tmpM, 0)
        Matrix.translateM(tmpM, 0, world.x, world.y + GROUND_OFFSET, world.z)
        Matrix.rotateM(tmpM, 0, Math.toDegrees(world.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progSkin, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progSkin, "uModel"), 1, false, tmpM, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(progSkin, "uBones"), model.boneCount * 3, skinOut, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progSkin, "uCam"), camPos[0], camPos[1], camPos[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBody)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progSkin, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texNormal)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progSkin, "uNrm"), 1)
        GLES30.glBindVertexArray(vaoSkin)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, model.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    // ---------------------------------------------------------------- recursos GL

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("Erro no shader: $log")
        }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw RuntimeException("Erro ao ligar programa: " + GLES30.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun loadTexture(name: String): Int {
        val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        bmp.recycle()
        return id[0]
    }

    private fun floatBuf(a: FloatArray): Buffer {
        val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a)
        fb.position(0)
        return fb
    }

    private fun shortBuf(a: ShortArray): Buffer {
        val sb = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(a)
        sb.position(0)
        return sb
    }

    private fun byteBuf(a: ByteArray): Buffer {
        val bb = ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder())
        bb.put(a)
        bb.position(0)
        return bb
    }

    private fun vbo(target: Int, data: Buffer, bytes: Int): Int {
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(target, id[0])
        GLES30.glBufferData(target, bytes, data, GLES30.GL_STATIC_DRAW)
        return id[0]
    }

    private fun newVao(): Int {
        val id = IntArray(1)
        GLES30.glGenVertexArrays(1, id, 0)
        GLES30.glBindVertexArray(id[0])
        return id[0]
    }

    private fun buildSkinVao() {
        vaoSkin = newVao()
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.pos), model.pos.size * 4)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.nor), model.nor.size * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.uv), model.uv.size * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, byteBuf(model.joints), model.joints.size)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_BYTE, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.weights), model.weights.size * 4)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ELEMENT_ARRAY_BUFFER, shortBuf(model.indices), model.indices.size * 2)
        GLES30.glBindVertexArray(0)
    }

    private fun buildCubeVao() {
        // cubo unitário: x,z em [-0.5,0.5], y em [0,1]
        val p = ArrayList<Float>()
        val n = ArrayList<Float>()
        val idx = ArrayList<Short>()
        fun face(nx: Float, ny: Float, nz: Float, c: FloatArray) {
            val base = (p.size / 3).toShort()
            for (i in 0 until 4) {
                p.add(c[i * 3]); p.add(c[i * 3 + 1]); p.add(c[i * 3 + 2])
                n.add(nx); n.add(ny); n.add(nz)
            }
            idx.add(base); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
            idx.add(base); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
        }
        val a = -0.5f
        val b = 0.5f
        // +Y
        face(0f, 1f, 0f, floatArrayOf(a, 1f, b, b, 1f, b, b, 1f, a, a, 1f, a))
        // -Y
        face(0f, -1f, 0f, floatArrayOf(a, 0f, a, b, 0f, a, b, 0f, b, a, 0f, b))
        // +Z
        face(0f, 0f, 1f, floatArrayOf(a, 0f, b, b, 0f, b, b, 1f, b, a, 1f, b))
        // -Z
        face(0f, 0f, -1f, floatArrayOf(b, 0f, a, a, 0f, a, a, 1f, a, b, 1f, a))
        // +X
        face(1f, 0f, 0f, floatArrayOf(b, 0f, b, b, 0f, a, b, 1f, a, b, 1f, b))
        // -X
        face(-1f, 0f, 0f, floatArrayOf(a, 0f, a, a, 0f, b, a, 1f, b, a, 1f, a))
        cubeIndexCount = idx.size
        vaoCube = newVao()
        val pa = p.toFloatArray()
        val na = n.toFloatArray()
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(pa), pa.size * 4)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(na), na.size * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
        val ia = idx.toShortArray()
        vbo(GLES30.GL_ELEMENT_ARRAY_BUFFER, shortBuf(ia), ia.size * 2)
        GLES30.glBindVertexArray(0)
    }

    private fun buildQuadVao() {
        val q = floatArrayOf(
            -1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f,
            -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f
        )
        vaoQuad = newVao()
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(q), q.size * 4)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        const val GROUND_OFFSET = 0.038f
    }
}
