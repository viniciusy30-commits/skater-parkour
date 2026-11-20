package com.skaterparkour.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Block(val cx: Float, val cz: Float, val sx: Float, val sz: Float, val h: Float, val r: Float, val g: Float, val b: Float)

/** Arena de parkour, física do personagem e a mecânica de agarrar na parede e subir. */
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

    // 0 = normal, 1 = pulando para a parede, 2 = pendurado, 3 = subindo
    var mode = 0
    var modeT = 0f
    // compensação (espaço do modelo) das poses de parede enquanto elas somem depois de sair do modo
    var offX = 0f
    var offY = 0f
    var offZ = 0f

    // pouso e ação com movimento da própria animação (rolamento)
    var landVy = 0f
    var landEvent = false
    var jumpEvent = false
    var jumpSpeed = 0f
    // tempo de agachada antes de sair do chão (a animação de decolagem sincroniza com ele)
    var jumpWind = 0f
    private var windT = -1f
    // velocidade desejada pelo joystick e giro do corpo (para partida, parada e curvas)
    var targetSpeed = 0f
    var yawRate = 0f
    // meia-volta correndo (turnKind 0) e giro parado (turnKind 1): o mundo vira o corpo na hora e a animação compensa
    var turnEvent = false
    var turnKind = 0
    var turnD = 0f
    var turnSpeed = 0f
    var turnLockTotal = 0.5f
    private var turnLock = 0f
    private var prevTsp = 0f
    var actionClip: Clip? = null
    var apx0 = 0f
    var apz0 = 0f
    private val rollClips = ArrayList<Clip>()
    private var rollIdx = 0
    private var ax0 = 0f
    private var az0 = 0f

    private var clipJump: Clip? = null
    private var clipClimb: Clip? = null
    private var hz0 = 0f
    private var hzEnd = 1f
    private val tmpP = FloatArray(3)

    private var timeNow = 0f
    private var lastPress = -10f
    private var queuedClimb = false

    // dados da agarrada atual
    private var sx0 = 0f
    private var sy0 = 0f
    private var sz0 = 0f
    private var ox = 0f
    private var oy = 0f
    private var oz = 0f
    private var fwdX = 0f
    private var fwdZ = 1f
    private var topH = 0f
    private var yawTarget = 0f
    private var jStart = 0f
    private var hzS = 0f
    private var corrX = 0f
    private var corrZ = 0f

    /** Tempo dentro da animação de pulo na parede (começa no ponto certo conforme a distância). */
    var clipT = 0f

    init {
        build()
    }

    fun setClips(jump: Clip, climb: Clip) {
        clipJump = jump
        clipClimb = climb
        jump.hips(0f, tmpP)
        hz0 = tmpP[2]
        jump.hips(jump.duration, tmpP)
        hzEnd = tmpP[2]
        if (hzEnd - hz0 < 0.1f) hzEnd = hz0 + 0.1f
    }

    fun setRolls(list: List<Clip>) {
        rollClips.clear()
        rollClips.addAll(list)
    }

    private fun onLanded() {
        landEvent = true
        if (landVy < -ROLL_VY && speed > 1.5f && rollClips.isNotEmpty()) {
            val c = rollClips[rollIdx % rollClips.size]
            rollIdx++
            actionClip = c
            c.hips(0f, tmpP)
            apx0 = tmpP[0]
            apz0 = tmpP[2]
            ax0 = x
            az0 = z
            vx = 0f; vz = 0f
            mode = 4
            modeT = 0f
            offX = 0f; offY = 0f; offZ = 0f
        }
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
        // parede de treino para agarrar e subir (1,5 m), bem perto do início
        add(0f, 0f, 5f, 0.6f, 1.5f, 0.95f, 0.40f, 0.45f)
    }

    fun reset() {
        x = 0f; y = 0f; z = -4f; yaw = 0f
        vx = 0f; vz = 0f; vy = 0f
        grounded = true; speed = 0f
        camYaw = 0f
        mode = 0; modeT = 0f
        offX = 0f; offY = 0f; offZ = 0f
        queuedClimb = false
        windT = -1f
        targetSpeed = 0f; yawRate = 0f
        turnLock = 0f; turnEvent = false
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

    private fun turnTo(target: Float, rate: Float) {
        var d = target - yaw
        while (d > Math.PI) d -= (2 * Math.PI).toFloat()
        while (d < -Math.PI) d += (2 * Math.PI).toFloat()
        yaw += d * rate
    }

    fun update(dt: Float) {
        timeNow += dt
        val cam = FloatArray(2)
        Input.takeCam(cam)
        camYaw -= cam[0]
        camPitch = (camPitch - cam[1]).coerceIn(-0.05f, 1.25f)

        if (Input.reset) {
            Input.reset = false
            Input.takeJumps()
            reset()
        }

        val presses = Input.takeJumps()
        if (mode == 0) {
            updateNormal(dt, presses)
        } else {
            windT = -1f
            targetSpeed = 0f
            yawRate = 0f
            turnLock = 0f
            updateSpecial(dt, presses)
        }
    }

    // ---------------------------------------------------------------- agarrar na parede

    private var bestD = 0f
    private var bestNx = 0f
    private var bestNz = 0f
    private var bestPx = 0f
    private var bestPz = 0f
    private var bestTop = 0f
    private var found = false

    private fun consider(b: Block, nx: Float, nz: Float, plane: Float, lat: Float, lo: Float, hi: Float, fx: Float, fz: Float) {
        val d = if (nx != 0f) (x - plane) * nx else (z - plane) * nz
        if (d < 0.2f || d > GRAB_MAX_DIST) return
        if (fx * (-nx) + fz * (-nz) < 0.6f) return
        if (lat < lo - 0.15f || lat > hi + 0.15f) return
        if (found && d >= bestD) return
        val latC = if (hi - lo > 0.6f) lat.coerceIn(lo + 0.25f, hi - 0.25f) else (lo + hi) / 2f
        found = true
        bestD = d
        bestNx = nx
        bestNz = nz
        bestTop = b.h
        if (nx != 0f) {
            bestPx = plane
            bestPz = latC
        } else {
            bestPx = latC
            bestPz = plane
        }
    }

    private fun tryGrab(): Boolean {
        if (clipJump == null || clipClimb == null) return false
        val fx = sin(yaw)
        val fz = cos(yaw)
        found = false
        for (b in blocks) {
            val rise = b.h - y
            if (rise < 0.5f || rise > 1.9f) continue
            consider(b, 1f, 0f, b.cx + b.sx / 2f, z, b.cz - b.sz / 2f, b.cz + b.sz / 2f, fx, fz)
            consider(b, -1f, 0f, b.cx - b.sx / 2f, z, b.cz - b.sz / 2f, b.cz + b.sz / 2f, fx, fz)
            consider(b, 0f, 1f, b.cz + b.sz / 2f, x, b.cx - b.sx / 2f, b.cx + b.sx / 2f, fx, fz)
            consider(b, 0f, -1f, b.cz - b.sz / 2f, x, b.cx - b.sx / 2f, b.cx + b.sx / 2f, fx, fz)
        }
        if (!found) return false

        fwdX = -bestNx
        fwdZ = -bestNz
        yawTarget = atan2(fwdX, fwdZ)
        topH = bestTop
        ox = bestPx + bestNx * WALL_Z
        oz = bestPz + bestNz * WALL_Z
        oy = bestTop - HANG_Y
        sx0 = x
        sy0 = y
        sz0 = z
        vx = 0f; vz = 0f; vy = 0f
        speed = 0f
        mode = 1
        modeT = 0f
        queuedClimb = false
        offX = 0f; offY = 0f; offZ = 0f

        // começa a animação no ponto do pulo em que o quadril já está à distância certa da parede
        val jc = clipJump ?: return false
        val dur = jc.duration
        val target = (WALL_Z - bestD).coerceIn(hz0 + 0.05f, hzEnd - 0.1f)
        var t = 0f
        while (t < dur) {
            jc.hips(t, tmpP)
            if (tmpP[2] >= target) break
            t += 1f / 60f
        }
        jStart = min(t, dur - 0.2f)
        if (jStart < 0f) jStart = 0f
        jc.hips(jStart, tmpP)
        hzS = tmpP[2]
        if (hzEnd - hzS < 0.05f) hzS = hzEnd - 0.05f
        corrX = sx0 - (ox + fwdX * hzS)
        corrZ = sz0 - (oz + fwdZ * hzS)
        clipT = jStart
        return true
    }

    private fun exitTo(nx: Float, ny: Float, nz: Float) {
        val dx = nx - x
        val dz = nz - z
        val c = cos(yaw)
        val s = sin(yaw)
        offX = dx * c - dz * s
        offY = ny - y
        offZ = dx * s + dz * c
        x = nx; y = ny; z = nz
        vx = 0f; vz = 0f; vy = 0f
        mode = 0
        modeT = 0f
    }

    private fun updateSpecial(dt: Float, presses: Int) {
        modeT += dt
        when (mode) {
            1 -> {
                val j = clipJump
                if (j == null) {
                    mode = 0
                    return
                }
                if (presses > 0 && modeT > 0.12f) queuedClimb = true
                val jt = min(jStart + modeT, j.duration)
                clipT = jt
                j.hips(jt, tmpP)
                val u = ((tmpP[2] - hzS) / (hzEnd - hzS)).coerceIn(0f, 1f)
                x = ox + corrX * (1f - u)
                z = oz + corrZ * (1f - u)
                y = sy0 + (oy - sy0) * u
                turnTo(yawTarget, min(1f, dt * 18f))
                if (jt >= j.duration) {
                    x = ox; y = oy; z = oz
                    yaw = yawTarget
                    mode = 2
                    modeT = 0f
                }
            }
            2 -> {
                x = ox; y = oy; z = oz
                if (presses > 0 || (queuedClimb && modeT > 0.25f)) {
                    queuedClimb = false
                    mode = 3
                    modeT = 0f
                } else if (Input.jy < -0.7f && modeT > 0.35f) {
                    // soltar: joystick para trás
                    exitTo(ox + fwdX * 0.83f, oy + 0.6f, oz + fwdZ * 0.83f)
                    grounded = false
                    vy = -0.5f
                }
            }
            4 -> {
                val c = actionClip
                if (c == null) {
                    mode = 0
                    return
                }
                val t = min(modeT, c.duration)
                c.hips(t, tmpP)
                val dx = tmpP[0] - apx0
                val dz = tmpP[2] - apz0
                val cy = cos(yaw)
                val sy = sin(yaw)
                x = ax0 + dx * cy + dz * sy
                z = az0 - dx * sy + dz * cy
                pushOut()
                x = x.coerceIn(-BOUND, BOUND)
                z = z.coerceIn(-BOUND, BOUND)
                supportY = groundAt(x, z, y)
                y = supportY
                if (modeT >= c.duration) {
                    vx = sin(yaw) * 2.2f
                    vz = cos(yaw) * 2.2f
                    speed = 2.2f
                    grounded = true
                    mode = 0
                    modeT = 0f
                }
            }
            3 -> {
                val c = clipClimb
                val dur = if (c != null) c.duration else 1f
                val p = ((modeT / dur - 0.3f) / 0.7f).coerceIn(0f, 1f)
                x = ox; z = oz
                y = oy + CLIMB_RAISE * p * p * (3f - 2f * p)
                if (modeT >= dur) {
                    exitTo(ox + fwdX * CLIMB_FWD, topH, oz + fwdZ * CLIMB_FWD)
                    grounded = true
                    supportY = topH
                }
            }
        }
    }

    // ---------------------------------------------------------------- movimento normal

    private fun updateNormal(dt: Float, presses: Int) {
        for (i in 0 until presses) {
            val dbl = timeNow - lastPress < DOUBLE_T
            lastPress = timeNow
            if (dbl && tryGrab()) {
                updateSpecial(0f, 0)
                return
            }
            if (grounded && windT < 0f) {
                // agacha primeiro; o corpo só sai do chão quando a agachada termina
                jumpEvent = true
                jumpSpeed = speed
                jumpWind = if (speed > 1.5f) WIND_RUN else WIND_STAND
                windT = 0f
            }
        }
        if (windT >= 0f) {
            windT += dt
            if (!grounded) {
                windT = -1f
            } else if (windT >= jumpWind) {
                vy = JUMP_SPEED
                grounded = false
                windT = -1f
            }
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
        targetSpeed = sqrt(dvx * dvx + dvz * dvz)
        val prevT = prevTsp
        prevTsp = targetSpeed
        if (turnLock > 0f) turnLock -= dt
        if (grounded && windT < 0f && turnLock <= 0f && targetSpeed > 0.5f) {
            val tyaw = atan2(dvx, dvz)
            var d = tyaw - yaw
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            val ad = abs(d)
            if (speed > 2.0f && targetSpeed > 1.5f && ad > 2.45f) {
                // meia-volta correndo
                turnEvent = true; turnKind = 0; turnD = d; turnSpeed = speed
                turnLockTotal = if (speed > 3.2f) 0.73f else 0.6f
                turnLock = turnLockTotal
                yaw = tyaw
            } else if (speed < 0.45f && prevT < 0.25f && ad > 0.6f) {
                // giro no lugar antes de sair andando
                turnEvent = true; turnKind = 1; turnD = d; turnSpeed = speed
                turnLockTotal = if (ad >= 2.36f) 0.46f else if (ad >= 1.18f) 0.37f else 0.3f
                turnLock = turnLockTotal
                yaw = tyaw
            }
        }
        if (turnLock > 0f && turnKind == 1) {
            dvx = 0f
            dvz = 0f
        }
        // sem joystick no chão: desliza um pouco (acompanha a animação de parada)
        val k = min(1f, dt * (if (!grounded) 4f else if (targetSpeed < 0.1f) 7f else 12f))
        vx += (dvx - vx) * k
        vz += (dvz - vz) * k
        speed = sqrt(vx * vx + vz * vz)

        if (speed > 0.3f && turnLock <= 0f) {
            val y0 = yaw
            turnTo(atan2(vx, vz), min(1f, dt * 14f))
            var dy = yaw - y0
            while (dy > Math.PI) dy -= (2 * Math.PI).toFloat()
            while (dy < -Math.PI) dy += (2 * Math.PI).toFloat()
            yawRate = if (dt > 0.0001f) dy / dt else 0f
        } else {
            yawRate = 0f
        }

        x += vx * dt
        z += vz * dt
        pushOut()
        x = x.coerceIn(-BOUND, BOUND)
        z = z.coerceIn(-BOUND, BOUND)

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
                landVy = vy
                y = supportY
                vy = 0f
                grounded = true
                onLanded()
            } else {
                y = ny
            }
        }
        if (y < -5f) reset()
    }

    companion object {
        const val MAX_SPEED = 3.8f
        const val JUMP_SPEED = 5.6f
        const val WIND_STAND = 0.2f
        const val WIND_RUN = 0.09f
        const val GRAVITY = 13f
        const val BOUND = 38f
        const val DOUBLE_T = 0.5f
        // medidas das animações de parede do rig novo (UE5, 80 ossos): borda a 0,70 m do pé do modelo
        const val WALL_Z = 0.8677f
        const val HANG_Y = 0.70f
        const val CLIMB_FWD = 0.9791f
        const val CLIMB_RAISE = 0.0033f
        const val GRAB_MAX_DIST = 0.8f
        // queda mais rápida que isso (cerca de 1,5 m) e correndo: o personagem rola ao cair
        const val ROLL_VY = 5.8f
    }
}
