package com.skaterparkour.app

import android.content.Context
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class Renderer(private val ctx: Context, private val audio: Audio) : GLSurfaceView.Renderer {
    @Volatile var game: Game? = null
    @Volatile var loadMsg = "Carregando…"
    @Volatile var error: String? = null
    @Volatile var lastErr: String? = null

    private lateinit var rig: RigData
    private lateinit var body: WMesh
    private lateinit var arena: ArenaMesh
    private var progSky = 0; private var skyVao = 0
    private val ivp = FloatArray(16)
    private var boneTex = 0; private var charTex = 0; private var nrmTex = 0; private var progSkin = 0
    private var nJ = 80
    private lateinit var boneBuf: java.nio.FloatBuffer
    private val u = HashMap<String, Int>()
    private var fxTex = 0; private var mouthTex = 0; private var buffLv = 0f
    val vpOut = FloatArray(16)
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private var last = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            glClearColor(.56f, .64f, .74f, 1f)
            loadMsg = "Carregando o personagem…"
            rig = RigData(Gl.readAsset(ctx, "w/rig.bin")); nJ = rig.nJoints
            val cs = ClipSet(Gl.readAsset(ctx, "w/anim.bin"))
            body = WMesh(ctx, "w/char.msh", true, colored = false)
            charTex = Gl.texture(ctx, "w/char_atlas.jpg", true, false)
            nrmTex = Gl.texture(ctx, "w/char_nrm.png", true, false)
            fxTex = Gl.texture(ctx, "w/char_fx.png", true, false)
            mouthTex = Gl.texture(ctx, "w/char_mouth.png", true, false)
            loadMsg = "Montando a arena…"
            val g = Game(cs, rig, Arena.build(ctx), Arena.stations, audio)
            arena = ArenaMesh(ctx)
            progSky = Gl.program(Shaders.SKY_VS, Shaders.SKY_FS)
            skyVao = Gl.gen(::glGenVertexArrays); glBindVertexArray(skyVao)
            val sq = Gl.floats(8); sq.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); sq.flip()
            val sqb = Gl.gen(::glGenBuffers); glBindBuffer(GL_ARRAY_BUFFER, sqb); glBufferData(GL_ARRAY_BUFFER, 32, sq, GL_STATIC_DRAW)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, false, 8, 0); glBindVertexArray(0)
            progSkin = Gl.program(Shaders.SKINT_VS, Shaders.CHAR_TFS)
            for (n in listOf("uBones", "uTex", "uNrm", "uVP", "uM", "uCtr", "uExt", "uCam", "uTm", "uFx", "uMouth", "uBuff")) u[n] = glGetUniformLocation(progSkin, n)
            boneBuf = Gl.floats(nJ * 16)
            boneTex = Gl.gen(::glGenTextures)
            glBindTexture(GL_TEXTURE_2D, boneTex)
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 4, nJ, 0, GL_RGBA, GL_FLOAT, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            game = g
            g.combat.vfx.initGl(ctx)
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
        // fúria do buff: sobe em ~0,7 s e desce devagar em ~2,5 s (transição fluida, sem tranco)
        run { val tg = if (g.combat.ultT > 0f) 1f else 0f; buffLv = if (tg > buffLv) min(tg, buffLv + dt / 0.7f) else max(tg, buffLv - dt / 2.5f) }
        try { g.update(dt) } catch (e: Throwable) { Log.e("Game", "update", e); lastErr = e.toString() + " @ " + (e.stackTrace.firstOrNull()?.toString() ?: "") }

        Matrix.setLookAtM(view, 0, g.eye[0], g.eye[1], g.eye[2], g.look[0], g.look[1], g.look[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        System.arraycopy(vp, 0, vpOut, 0, 16)
        glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDisable(GL_CULL_FACE)

        // céu
        Matrix.invertM(ivp, 0, vp, 0)
        glDisable(GL_DEPTH_TEST); glDepthMask(false)
        glUseProgram(progSky)
        glUniformMatrix4fv(glGetUniformLocation(progSky, "uIVP"), 1, false, ivp, 0)
        glUniform3fv(glGetUniformLocation(progSky, "uCam"), 1, g.eye, 0)
        glBindVertexArray(skyVao); glDrawArrays(GL_TRIANGLE_STRIP, 0, 4); glBindVertexArray(0)
        glDepthMask(true); glEnable(GL_DEPTH_TEST)

        arena.draw(vp, g.eye)

        boneBuf.clear(); boneBuf.put(g.skel.skin, 0, nJ * 16); boneBuf.flip()
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, boneTex)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, nJ, GL_RGBA, GL_FLOAT, boneBuf)
        glUseProgram(progSkin)
        glUniform1i(u["uBones"]!!, 1); glUniform1i(u["uTex"]!!, 0); glUniform1i(u["uNrm"]!!, 2); glUniform1i(u["uFx"]!!, 3); glUniform1i(u["uMouth"]!!, 4)
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, fxTex)
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, mouthTex)
        glUniform1f(u["uBuff"]!!, buffLv)
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, nrmTex)
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, charTex); glActiveTexture(GL_TEXTURE1)
        glUniformMatrix4fv(u["uVP"]!!, 1, false, vp, 0); glUniformMatrix4fv(u["uM"]!!, 1, false, g.charM, 0)
        glUniform3fv(u["uCtr"]!!, 1, body.ctr, 0); glUniform3fv(u["uExt"]!!, 1, body.ext, 0); glUniform3fv(u["uCam"]!!, 1, g.eye, 0)
        glUniform1f(u["uTm"]!!, (now / 1_000_000L % 600_000L) / 1000f)
        body.draw()
        // inimigo (Guerreiro Sombra): mesma malha, outro esqueleto
        boneBuf.clear(); boneBuf.put(g.foeSkel.skin, 0, nJ * 16); boneBuf.flip()
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, nJ, GL_RGBA, GL_FLOAT, boneBuf)
        glUniformMatrix4fv(u["uM"]!!, 1, false, g.foeM, 0)
        glUniform1f(u["uBuff"]!!, 0f)
        body.draw()
        glBindVertexArray(0)

        // efeitos 3D do combate (impactos, aura, corrente...) por cima de tudo
        try { g.combat.vfx.draw(vp, view, g.eye) } catch (e: Throwable) { Log.e("Vfx", "draw", e); lastErr = e.toString() }
    }
}
