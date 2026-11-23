package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class Renderer(private val ctx: Context, private val audio: Audio) : GLSurfaceView.Renderer {
    @Volatile var game: Game? = null
    @Volatile var loadMsg = "Carregando…"
    @Volatile var error: String? = null

    private lateinit var rig: Rig
    private lateinit var anims: Anims
    private lateinit var skel: Skeleton
    private lateinit var fx: Fx
    private lateinit var arena: Arena
    private lateinit var body: WMesh
    private lateinit var cap: WMesh
    private lateinit var weapon: WMesh
    private lateinit var dummy: CMesh
    private var texBody = 0; private var texWeap = 0; private var boneTex = 0
    private var progSkin = 0; private var progWeap = 0
    private val boneBuf = Gl.floats(NB * 16)

    private val proj = FloatArray(16); private val view = FloatArray(16)
    val vp = FloatArray(16); private val ivp = FloatArray(16)
    private val vpShared = FloatArray(16)
    @Volatile var vw = 1; @Volatile var vh = 1
    private var last = 0L
    private val dM = FloatArray(16)
    private val shadows = FloatArray(12)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            glClearColor(.47f, .47f, .52f, 1f)
            loadMsg = "Carregando o guerreiro…"
            rig = Rig(ctx); anims = Anims(ctx); skel = Skeleton(rig, anims)
            body = WMesh(ctx, "w/char.msh", true)
            cap = WMesh(ctx, "w/cap.msh", true)
            weapon = WMesh(ctx, "w/weapon.msh", false)
            texBody = Gl.texture(ctx, "w/char.jpg"); texWeap = Gl.texture(ctx, "w/weapon.jpg")
            loadMsg = "Montando o campo de treino…"
            fx = Fx(ctx)
            arena = Arena(); arena.init()
            dummy = Arena.dummyMesh()
            progSkin = Gl.program(Shaders.SKIN_VS, Shaders.TEX_FS)
            progWeap = Gl.program(Shaders.WEAP_VS, Shaders.TEX_FS)
            boneTex = Gl.gen(::glGenTextures)
            glBindTexture(GL_TEXTURE_2D, boneTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, NB, 0, GL_RGBA, GL_FLOAT, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            if (game == null) game = Game(anims, skel, fx, audio)
            last = System.nanoTime()
            loadMsg = ""
        } catch (t: Throwable) {
            Log.e("Renderer", "load", t)
            error = t.toString()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        glViewport(0, 0, w, h); vw = w; vh = h
        Matrix.perspectiveM(proj, 0, 52f, w.toFloat() / max(1, h), .1f, 420f)
    }

    fun projected(out: FloatArray): FloatArray { synchronized(vpShared) { System.arraycopy(vpShared, 0, out, 0, 16) }; return out }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val g = game ?: return
        if (error != null) return
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, .1f); last = now
        g.update(dt)
        g.torchTick(arena.torches, dt)

        Matrix.setLookAtM(view, 0, g.eye[0], g.eye[1], g.eye[2], g.look[0], g.look[1], g.look[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(ivp, 0, vp, 0)
        synchronized(vpShared) { System.arraycopy(vp, 0, vpShared, 0, 16) }
        // vetores da câmera para os sprites
        fx.camR[0] = view[0]; fx.camR[1] = view[4]; fx.camR[2] = view[8]
        fx.camU[0] = view[1]; fx.camU[1] = view[5]; fx.camU[2] = view[9]
        System.arraycopy(g.eye, 0, fx.eye, 0, 3)

        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL)
        glDisable(GL_CULL_FACE)
        // sombras: guerreiro, boneco
        shadows[0] = g.P[0]; shadows[1] = g.P[2]; shadows[2] = .62f; shadows[3] = .55f
        shadows[4] = g.D[0]; shadows[5] = g.D[2]; shadows[6] = .55f + g.dummyFall() * .5f; shadows[7] = .5f
        shadows[8] = g.axePos[0]; shadows[9] = g.axePos[2]; shadows[10] = if (g.axeFlying) .3f else 0f; shadows[11] = .35f
        arena.draw(vp, ivp, g.eye, shadows)

        // boneco de treino
        g.dummyModel(dM)
        val fl = g.dFlash
        arena.drawCol(dummy, vp, g.eye, dM, floatArrayOf(fl * .5f, fl * .35f, fl * .2f))

        // guerreiro
        boneBuf.clear(); boneBuf.put(skel.skin); boneBuf.flip()
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, boneTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, NB, GL_RGBA, GL_FLOAT, boneBuf)
        glUseProgram(progSkin)
        glUniform1i(glGetUniformLocation(progSkin, "uBones"), 1)
        glUniform1i(glGetUniformLocation(progSkin, "uTex"), 0)
        glUniformMatrix4fv(glGetUniformLocation(progSkin, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(progSkin, "uM"), 1, false, g.charM, 0)
        glUniform3fv(glGetUniformLocation(progSkin, "uCtr"), 1, body.ctr, 0)
        glUniform3fv(glGetUniformLocation(progSkin, "uExt"), 1, body.ext, 0)
        glUniform3fv(glGetUniformLocation(progSkin, "uCam"), 1, g.eye, 0)
        glUniform1f(glGetUniformLocation(progSkin, "uMetal"), .1f)
        glUniform1f(glGetUniformLocation(progSkin, "uRune"), 0f)
        glUniform1f(glGetUniformLocation(progSkin, "uRim"), g.red * .12f)
        glUniform1f(glGetUniformLocation(progSkin, "uTm"), g.time)
        glUniform3f(glGetUniformLocation(progSkin, "uRC"), 1f, .12f, .05f)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texBody)
        body.draw()
        cap.draw()

        // machados
        glUseProgram(progWeap)
        glUniformMatrix4fv(glGetUniformLocation(progWeap, "uVP"), 1, false, vp, 0)
        glUniform3fv(glGetUniformLocation(progWeap, "uCtr"), 1, weapon.ctr, 0)
        glUniform3fv(glGetUniformLocation(progWeap, "uExt"), 1, weapon.ext, 0)
        glUniform3fv(glGetUniformLocation(progWeap, "uCam"), 1, g.eye, 0)
        glUniform1f(glGetUniformLocation(progWeap, "uMetal"), .35f)
        glUniform1f(glGetUniformLocation(progWeap, "uTm"), g.time)
        glUniform1i(glGetUniformLocation(progWeap, "uTex"), 0)
        glBindTexture(GL_TEXTURE_2D, texWeap)
        val useRed = g.red > g.gold
        val glow = max(g.red, g.gold)
        if (useRed) glUniform3f(glGetUniformLocation(progWeap, "uRC"), 1f, .12f, .05f) else glUniform3f(glGetUniformLocation(progWeap, "uRC"), 1f, .66f, .2f)
        glUniform1f(glGetUniformLocation(progWeap, "uRune"), glow)
        glUniform1f(glGetUniformLocation(progWeap, "uRim"), if (useRed) g.red * .35f else g.gold * .55f)
        for (s in 0 until 2) {
            val m = if (s == 1 && g.axeFlying) g.axeM else g.wM[s]
            glUniformMatrix4fv(glGetUniformLocation(progWeap, "uM"), 1, false, m, 0)
            weapon.draw()
        }

        fx.draw(vp)
        glBindVertexArray(0)
    }
}
