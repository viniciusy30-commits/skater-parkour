package com.skaterparkour.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Block(val cx: Float, val cz: Float, val sx: Float, val sz: Float, val h: Float, val r: Float, val g: Float, val b: Float)

/** Arena de parkour e física simples do personagem. */
class World {
    val blocks = ArrayList<Block>()

    var x = 0f
    var y = 0f
    var z = -4f
    var yaw = 0f
    var vx = 0f
    var vz = 0f
    var vy = 0f
    var grounded = true
    var speed = 0f
    var supportY = 0f

    var camYaw = 0f
    var camPitch = 0.38f

    init {
        build()
    }

    private fun add(cx: Float, cz: Float, sx: Float, sz: Float, h: Float, r: Float, g: Float, b: Float) {
        blocks.add(Block(cx, cz, sx, sz, h, r, g, b))
    }

    private fun build() {
        // degraus
        add(-6f, 6f, 2.5f, 2f, 0.4f, 0.95f, 0.55f, 0.25f)
        add(-6f, 8f, 2.5f, 2f, 0.8f, 0.95f, 0.45f, 0.25f)
        add(-6f, 10f, 2.5f, 2f, 1.2f, 0.95f, 0.35f, 0.25f)
        // plataformas para pular
        add(3f, 5f, 2f, 2f, 0.5f, 0.30f, 0.75f, 0.95f)
        add(6.5f, 5f, 2f, 2f, 0.8f, 0.30f, 0.65f, 0.95f)
        add(10f, 5f, 2f, 2f, 1.0f, 0.30f, 0.55f, 0.95f)
        add(13.5f, 5f, 2f, 2f, 0.6f, 0.30f, 0.45f, 0.95f)
        // caixas grandes
        add(8f, -4f, 3f, 3f, 1.0f, 0.60f, 0.85f, 0.45f)
        add(11.5f, -4f, 2f, 2f, 1.5f, 0.50f, 0.80f, 0.40f)
        add(-9f, -5f, 4f, 2f, 0.6f, 0.80f, 0.60f, 0.90f)
        // paredes
        add(0f, 14f, 10f, 1f, 2.2f, 0.75f, 0.75f, 0.80f)
        add(-14f, 2f, 1f, 8f, 1.8f, 0.70f, 0.72f, 0.78f)
        // obstáculos baixos
        add(0f, 3f, 1f, 1f, 0.3f, 0.95f, 0.85f, 0.30f)
        add(-2f, 4f, 1f, 1f, 0.45f, 0.95f, 0.85f, 0.30f)
    }

    fun reset() {
        x = 0f; y = 0f; z = -4f; yaw = 0f
        vx = 0f; vz = 0f; vy = 0f
        grounded = true; speed = 0f
        camYaw = 0f
    }

    private fun groundAt(px: Float, pz: Float, feet: Float): Float {
        var g = 0f
        for (b in blocks) {
            if (b.h <= feet + 0.3f && b.h > g &&
                abs(px - b.cx) <= b.sx / 2f + 0.05f && abs(pz - b.cz) <= b.sz / 2f + 0.05f
            ) g = b.h
        }
        return g
    }

    private fun pushOut() {
        val rad = 0.28f
        for (b in blocks) {
            if (b.h <= y + 0.25f) continue
            val hx = b.sx / 2f + rad
            val hz = b.sz / 2f + rad
            val dx = x - b.cx
            val dz = z - b.cz
            if (abs(dx) < hx && abs(dz) < hz) {
                val px = hx - abs(dx)
                val pz = hz - abs(dz)
                if (px < pz) {
                    x += if (dx >= 0f) px else -px
                    vx = 0f
                } else {
                    z += if (dz >= 0f) pz else -pz
                    vz = 0f
                }
            }
        }
    }

    fun update(dt: Float) {
        val cam = FloatArray(2)
        Input.takeCam(cam)
        camYaw -= cam[0]
        camPitch = (camPitch - cam[1]).coerceIn(-0.05f, 1.25f)

        if (Input.reset) {
            Input.reset = false
            reset()
        }

        val jx = Input.jx
        val jy = Input.jy
        val mag = min(1f, sqrt(jx * jx + jy * jy))
        var dvx = 0f
        var dvz = 0f
        if (mag > 0.08f) {
            val fx = sin(camYaw)
            val fz = cos(camYaw)
            val rx = -cos(camYaw)
            val rz = sin(camYaw)
            var dx = fx * jy + rx * jx
            var dz = fz * jy + rz * jx
            val len = sqrt(dx * dx + dz * dz)
            if (len > 1e-5f) {
                dx /= len; dz /= len
                dvx = dx * MAX_SPEED * mag
                dvz = dz * MAX_SPEED * mag
            }
        }
        val k = min(1f, dt * (if (grounded) 12f else 4f))
        vx += (dvx - vx) * k
        vz += (dvz - vz) * k
        speed = sqrt(vx * vx + vz * vz)

        if (speed > 0.3f) {
            val target = atan2(vx, vz)
            var d = target - yaw
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            yaw += d * min(1f, dt * 14f)
        }

        x += vx * dt
        z += vz * dt
        pushOut()
        x = x.coerceIn(-BOUND, BOUND)
        z = z.coerceIn(-BOUND, BOUND)

        if (Input.jump) {
            Input.jump = false
            if (grounded) {
                vy = JUMP_SPEED
                grounded = false
            }
        }

        supportY = groundAt(x, z, y)
        if (grounded) {
            if (supportY > y + 0.001f) {
                y = supportY
            } else if (supportY < y - 0.08f) {
                grounded = false
            } else {
                y = supportY
            }
        }
        if (!grounded) {
            vy -= GRAVITY * dt
            val ny = y + vy * dt
            if (vy <= 0f && ny <= supportY) {
                y = supportY
                vy = 0f
                grounded = true
            } else {
                y = ny
            }
        }
        if (y < -5f) reset()
    }

    companion object {
        const val MAX_SPEED = 3.8f
        const val JUMP_SPEED = 5.6f
        const val GRAVITY = 13f
        const val BOUND = 38f
    }
}
