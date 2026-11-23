package com.skaterparkour.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

object Gl {
    fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e("Gl", "shader: $log\n$src")
            throw RuntimeException("Shader: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link: " + glGetProgramInfoLog(p))
        return p
    }

    fun gen(f: (Int, IntArray, Int) -> Unit): Int {
        val a = IntArray(1); f(1, a, 0); return a[0]
    }

    fun direct(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
    fun floats(n: Int): FloatBuffer = direct(n * 4).asFloatBuffer()

    fun readAsset(ctx: Context, name: String): ByteArray = ctx.assets.open(name).use { it.readBytes() }

    fun texture(ctx: Context, name: String, mip: Boolean = true, repeat: Boolean = false): Int {
        val bytes = readAsset(ctx, name)
        val opt = BitmapFactory.Options().apply { inScaled = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        val t = gen(::glGenTextures)
        glBindTexture(GL_TEXTURE_2D, t)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        if (mip) glGenerateMipmap(GL_TEXTURE_2D)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (mip) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        val w = if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, w)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, w)
        return t
    }
}

/** Pequenas operações de matriz 4x4 (coluna-maior, igual ao OpenGL). */
object M4 {
    fun id(o: FloatArray = FloatArray(16)): FloatArray { o.fill(0f); o[0] = 1f; o[5] = 1f; o[10] = 1f; o[15] = 1f; return o }

    fun mul(o: FloatArray, oo: Int, a: FloatArray, ao: Int, b: FloatArray, bo: Int) {
        for (c in 0 until 4) {
            val b0 = b[bo + c * 4]; val b1 = b[bo + c * 4 + 1]; val b2 = b[bo + c * 4 + 2]; val b3 = b[bo + c * 4 + 3]
            for (r in 0 until 4) o[oo + c * 4 + r] = a[ao + r] * b0 + a[ao + 4 + r] * b1 + a[ao + 8 + r] * b2 + a[ao + 12 + r] * b3
        }
    }

    fun mul(o: FloatArray, a: FloatArray, b: FloatArray) = mul(o, 0, a, 0, b, 0)

    /** matriz a partir de quaternion (x,y,z,w) + translação */
    fun qt(o: FloatArray, q: FloatArray, qo: Int, tx: Float, ty: Float, tz: Float) {
        val x = q[qo]; val y = q[qo + 1]; val z = q[qo + 2]; val w = q[qo + 3]
        o[0] = 1 - 2 * (y * y + z * z); o[1] = 2 * (x * y + z * w); o[2] = 2 * (x * z - y * w); o[3] = 0f
        o[4] = 2 * (x * y - z * w); o[5] = 1 - 2 * (x * x + z * z); o[6] = 2 * (y * z + x * w); o[7] = 0f
        o[8] = 2 * (x * z + y * w); o[9] = 2 * (y * z - x * w); o[10] = 1 - 2 * (x * x + y * y); o[11] = 0f
        o[12] = tx; o[13] = ty; o[14] = tz; o[15] = 1f
    }

    /** T(x,y,z) * RotY(yaw) * S(s) */
    fun trs(o: FloatArray, x: Float, y: Float, z: Float, yaw: Float, s: Float) {
        val c = cos(yaw); val sn = sin(yaw)
        id(o)
        o[0] = c * s; o[2] = -sn * s
        o[5] = s
        o[8] = sn * s; o[10] = c * s
        o[12] = x; o[13] = y; o[14] = z
    }

    fun xf(m: FloatArray, mo: Int, x: Float, y: Float, z: Float, out: FloatArray, oo: Int = 0) {
        out[oo] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12]
        out[oo + 1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13]
        out[oo + 2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14]
    }
}

fun clampf(x: Float, a: Float, b: Float) = if (x < a) a else if (x > b) b else x
fun wrapA(a: Float): Float = atan2(sin(a), cos(a))
fun smooth(x: Float): Float { val t = clampf(x, 0f, 1f); return t * t * (3 - 2 * t) }
fun lerpf(a: Float, b: Float, t: Float) = a + (b - a) * t
fun rnd(a: Float, b: Float) = a + (Math.random().toFloat()) * (b - a)
