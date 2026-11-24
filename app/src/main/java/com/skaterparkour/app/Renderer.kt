package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

class Renderer(private val ctx: Context, @Suppress("unused") private val audio: Audio) : GLSurfaceView.Renderer {
    @Volatile var game: Game? = null
    @Volatile var loadMsg = "Carregando…"
    @Volatile var error: String? = null
    @Volatile var lastErr: String? = null

    private lateinit var rig: RigData
    private lateinit var body: WMesh
    private lateinit var stage: Stage
    private var texBody = 0; private var boneTex = 0; private var progSkin = 0
    private var nJ = 95
    private lateinit var boneBuf: java.nio.FloatBuffer
    private val u = HashMap<String, Int>()
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private var last = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            glClearColor(.56f, .64f, .74f, 1f)
            loadMsg = "Carregando o personagem…"
            rig = RigData(Gl.readAsset(ctx, "w/rig.bin")); nJ = rig.nJoints
            val cs = ClipSet(Gl.readAsset(ctx, "w/anim.bin"))
            body = WMesh(ctx, "w/char.msh", true)
            texBody = Gl.texture(ctx, "w/char.jpg")
            loadMsg = "Montando o mapa de testes…"
            val g = Game(cs, rig)
            stage = Stage(g.world); stage.init()
            progSkin = Gl.program(Shaders.SKIN_VS, Shaders.TEX_FS)
            for (n in listOf("uBones", "uTex", "uVP", "uM", "uCtr", "uExt", "uCam", "uMetal", "uRune", "uRim", "uTm", "uRC")) u[n] = glGetUniformLocation(progSkin, n)
            boneBuf = Gl.floats(nJ * 16)
            boneTex = Gl.gen(::glGenTextures)
            glBindTexture(GL_TEXTURE_2D, boneTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, nJ, 0, GL_RGBA, GL_FLOAT, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            game = g
            last = System.nanoTime()
            loadMsg = ""
        } catch (t: Throwable) {
            Log.e("Renderer", "load", t)
            error = t.toString() + " @ " + (t.stackTrace.firstOrNull()?.toString() ?: "")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        glViewport(0, 0, w, h)
        Matrix.perspectiveM(proj, 0, 58f, w.toFloat() / max(1, h), .1f, 600f)
    }

    override fun onDrawFrame(gl: GL10?) {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val g = game ?: return
        if (error != null) return
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, .1f); last = now
        try { g.update(dt) } catch (e: Throwable) { Log.e("Game", "update", e); lastErr = e.toString() + " @ " + (e.stackTrace.firstOrNull()?.toString() ?: "") }

        Matrix.setLookAtM(view, 0, g.eye[0], g.eye[1], g.eye[2], g.look[0], g.look[1], g.look[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE)

        stage.drawSolid(vp, g.eye)

        boneBuf.clear(); boneBuf.put(g.skel.skin, 0, nJ * 16); boneBuf.flip()
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, boneTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, nJ, GL_RGBA, GL_FLOAT, boneBuf)
        glUseProgram(progSkin)
        glUniform1i(u["uBones"]!!, 1); glUniform1i(u["uTex"]!!, 0)
        glUniformMatrix4fv(u["uVP"]!!, 1, false, vp, 0); glUniformMatrix4fv(u["uM"]!!, 1, false, g.charM, 0)
        glUniform3fv(u["uCtr"]!!, 1, body.ctr, 0); glUniform3fv(u["uExt"]!!, 1, body.ext, 0); glUniform3fv(u["uCam"]!!, 1, g.eye, 0)
        glUniform1f(u["uMetal"]!!, 0f); glUniform1f(u["uRune"]!!, 0f); glUniform1f(u["uRim"]!!, 0f); glUniform1f(u["uTm"]!!, g.time)
        glUniform3f(u["uRC"]!!, 0f, 0f, 0f)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, texBody)
        body.draw()
        glBindVertexArray(0)

        stage.drawWater(vp, g.eye)
    }
}
