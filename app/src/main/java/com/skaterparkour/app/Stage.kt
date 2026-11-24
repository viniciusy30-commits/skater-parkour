package com.skaterparkour.app

import android.opengl.GLES30.*

/** Desenha o mapa de testes (blocos coloridos) e a água translúcida. */
class Stage(private val w: World) {
    private val solid = CMesh()
    private val water = CMesh()
    private var progS = 0; private var progW = 0
    private val m = FloatArray(16)

    private fun col(b: Box): FloatArray = when {
        b.kind == 2 -> floatArrayOf(.90f, .70f, .20f)
        b.name == "chão" -> floatArrayOf(.35f, .55f, .35f)
        b.name.startsWith("piscina") -> floatArrayOf(.80f, .74f, .55f)
        b.name.startsWith("parede") && b.name.contains("corrida") -> floatArrayOf(.55f, .62f, .78f)
        b.name.startsWith("degrau") || b.name.startsWith("torre") -> floatArrayOf(.62f, .60f, .66f)
        else -> floatArrayOf(.72f, .55f, .38f)
    }

    fun init() {
        for (b in w.boxes) solid.box((b.x0 + b.x1) / 2, (b.y0 + b.y1) / 2, (b.z0 + b.z1) / 2, b.x1 - b.x0, b.y1 - b.y0, b.z1 - b.z0, 0f, col(b))
        for (a in w.waters) water.box((a.x0 + a.x1) / 2, a.surface - 0.01f, (a.z0 + a.z1) / 2, a.x1 - a.x0, 0.02f, a.z1 - a.z0, 0f, floatArrayOf(.18f, .45f, .85f))
        solid.upload(); water.upload()
        progS = Gl.program(Shaders.COL_VS, Shaders.COL_FS)
        progW = Gl.program(Shaders.COL_VS, WATER_FS)
        M4.id(m)
    }

    fun drawSolid(vp: FloatArray, eye: FloatArray) {
        glUseProgram(progS)
        glUniformMatrix4fv(glGetUniformLocation(progS, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(progS, "uM"), 1, false, m, 0)
        glUniform3fv(glGetUniformLocation(progS, "uCam"), 1, eye, 0)
        glUniform3f(glGetUniformLocation(progS, "uFlash"), 0f, 0f, 0f)
        solid.draw()
    }

    fun drawWater(vp: FloatArray, eye: FloatArray) {
        glUseProgram(progW)
        glUniformMatrix4fv(glGetUniformLocation(progW, "uVP"), 1, false, vp, 0)
        glUniformMatrix4fv(glGetUniformLocation(progW, "uM"), 1, false, m, 0)
        glUniform3fv(glGetUniformLocation(progW, "uCam"), 1, eye, 0)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); glDepthMask(false)
        water.draw()
        glDepthMask(true); glDisable(GL_BLEND)
        glBindVertexArray(0)
    }

    companion object {
        const val WATER_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vP; in vec3 vC; uniform vec3 uCam; out vec4 o;
void main(){ float f=.55+.45*pow(1.-clamp(normalize(uCam-vP).y,0.,1.),2.); o=vec4(vC*(.75+.25*sin(vP.x*1.7+vP.z*1.3)),.28+.25*f); }"""
    }
}
