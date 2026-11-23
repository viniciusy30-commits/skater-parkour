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
    @Volatile var lastErr: String? = null

    private lateinit var rig: Rig
    private lateinit var anims: Anims
    private lateinit var skel: Skeleton
    private lateinit var fx: Fx3
    private lateinit var trl: Trl
    private lateinit var bld: Bld
    private lateinit var sw: Sw
    private lateinit var arena: Arena
    private lateinit var body: WMesh
    private lateinit var cap: WMesh
    private lateinit var weapon: WMesh
    private var texBody = 0; private var texWeap = 0; private var boneTex = 0
    private var progSkin = 0; private var progWeap = 0
    private val boneBuf = Gl.floats(NB * 16)
    private val uS = HashMap<String, Int>(); private val uW = HashMap<String, Int>()

    private val proj = FloatArray(16); private val view = FloatArray(16)
    val vp = FloatArray(16); private val ivp = FloatArray(16); private val vpU = FloatArray(16); private val sK = FloatArray(16)
    private val vpShared = FloatArray(16)
    @Volatile var vw = 1; @Volatile var vh = 1
    private var last = 0L
    private val shadows = FloatArray(12)
    private val eyeU = FloatArray(3); private val camR = FloatArray(3); private val camU = FloatArray(3)

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
            fx = Fx3(ctx); trl = Trl(); bld = Bld(ctx); sw = Sw(ctx)
            arena = Arena(); arena.init()
            progSkin = Gl.program(Shaders.SKIN_VS, Shaders.TEX_FS)
            progWeap = Gl.program(Shaders.WEAP_VS, Shaders.TEX_FS)
            for (n in listOf("uBones", "uTex", "uVP", "uM", "uCtr", "uExt", "uCam", "uMetal", "uRune", "uRim", "uTm", "uRC")) {
                uS[n] = glGetUniformLocation(progSkin, n); uW[n] = glGetUniformLocation(progWeap, n)
            }
            boneTex = Gl.gen(::glGenTextures)
            glBindTexture(GL_TEXTURE_2D, boneTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, NB, 0, GL_RGBA, GL_FLOAT, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            game = Game(anims, skel, fx, trl, bld, sw, audio)
            M4.id(sK); sK[0] = Game.K; sK[5] = Game.K; sK[10] = Game.K
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
        try { g.update(dt); g.torchTick(arena.torches, dt) } catch (e: Exception) { Log.e("Game", "update", e); lastErr = e.toString() + " @ " + (e.stackTrace.firstOrNull()?.toString() ?: "") }

        Matrix.setLookAtM(view, 0, g.eye[0], g.eye[1], g.eye[2], g.look[0], g.look[1], g.look[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(ivp, 0, vp, 0)
        Matrix.multiplyMM(vpU, 0, vp, 0, sK, 0)
        synchronized(vpShared) { System.arraycopy(vp, 0, vpShared, 0, 16) }
        camR[0] = view[0]; camR[1] = view[4]; camR[2] = view[8]
        camU[0] = view[1]; camU[1] = view[5]; camU[2] = view[9]
        System.arraycopy(camR, 0, fx.camR, 0, 3); System.arraycopy(camU, 0, fx.camU, 0, 3); System.arraycopy(g.eye, 0, fx.eye, 0, 3)
        for (i in 0 until 3) { eyeU[i] = g.eye[i] / Game.K; trl.eye[i] = eyeU[i] }

        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL)
        glDisable(GL_CULL_FACE)
        shadows[0] = g.P[0]; shadows[1] = g.P[2]; shadows[2] = .62f; shadows[3] = .55f
        shadows[6] = 0f; shadows[10] = 0f
        arena.draw(vp, ivp, g.eye, shadows)

        // guerreiro
        boneBuf.clear(); boneBuf.put(skel.skin); boneBuf.flip()
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, boneTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, NB, GL_RGBA, GL_FLOAT, boneBuf)
        glUseProgram(progSkin)
        glUniform1i(uS["uBones"]!!, 1); glUniform1i(uS["uTex"]!!, 0)
        glUniformMatrix4fv(uS["uVP"]!!, 1, false, vp, 0); glUniformMatrix4fv(uS["uM"]!!, 1, false, g.charM, 0)
        glUniform3fv(uS["uCtr"]!!, 1, body.ctr, 0); glUniform3fv(uS["uExt"]!!, 1, body.ext, 0); glUniform3fv(uS["uCam"]!!, 1, g.eye, 0)
        glUniform1f(uS["uMetal"]!!, 0f); glUniform1f(uS["uRune"]!!, 0f); glUniform1f(uS["uRim"]!!, 0f); glUniform1f(uS["uTm"]!!, g.time)
        glUniform3f(uS["uRC"]!!, 1f, .05f, .02f)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texBody)
        body.draw(); cap.draw()

        // machados (runas vermelhas na Ultimate, brilho dourado na Skill 2/4 — como no HTML)
        glUseProgram(progWeap)
        glUniformMatrix4fv(uW["uVP"]!!, 1, false, vp, 0)
        glUniform3fv(uW["uCtr"]!!, 1, weapon.ctr, 0); glUniform3fv(uW["uExt"]!!, 1, weapon.ext, 0); glUniform3fv(uW["uCam"]!!, 1, g.eye, 0)
        glUniform1f(uW["uMetal"]!!, .35f); glUniform1f(uW["uTm"]!!, g.time); glUniform1i(uW["uTex"]!!, 0)
        glBindTexture(GL_TEXTURE_2D, texWeap)
        for (s in 0 until 2) {
            val gg = g.glowGold(s); val gold = gg > .001f
            glUniform1f(uW["uRune"]!!, if (gold) gg else g.red)
            glUniform1f(uW["uRim"]!!, if (gold) gg * .35f else 0f)
            if (gold) glUniform3f(uW["uRC"]!!, 1f, .62f, .12f) else glUniform3f(uW["uRC"]!!, 1f, .05f, .02f)
            glUniformMatrix4fv(uW["uM"]!!, 1, false, if (s == 1 && g.axeFlying) g.axeM else g.wM[s], 0)
            weapon.draw()
        }

        // boneco, efeitos, rastro, onda de choque, sangue (mesma ordem do HTML)
        bld.drawEnemy(vpU, eyeU)
        try { fx.draw(vp, g.time); trl.draw(vpU); sw.draw(vpU); bld.drawBlood(vpU, camR, camU, eyeU) } catch (e: Exception) { Log.e("Game", "draw", e); lastErr = e.toString() + " @ " + (e.stackTrace.firstOrNull()?.toString() ?: "") }
        glBindVertexArray(0)
    }
}
