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

    // obstáculos: ações medidas (saltos por cima, subidas, degraus)
    private var defs: List<ActionDef> = emptyList()
    private val rnd = java.util.Random()
    private var lastDefName = ""
    /** 0 = rolamento, 1 = ação de obstáculo (a animação desloca o corpo). */
    var actionKind = 0
    /** Ajuste vertical (m) do quadril da animação para a altura real do obstáculo. */
    var actWarp = 0f
    private var curDef: ActionDef? = null
    private var obsFx = 0f
    private var obsFz = 1f
    private var obsX0 = 0f
    private var obsZ0 = 0f
    private var obsY0 = 0f
    private var obsH0x = 0f
    private var obsH0z = 0f
    private var obsTop = 0f
    private var obsDelta = 0f
    // ação planejada que espera o personagem chegar na distância certa do obstáculo
    private var pend: ActionDef? = null
    private var pendNx = 0f
    private var pendNz = 0f
    private var pendPlane = 0f
    private var pendAxisX = true
    private var pendTop = 0f
    private var pendT = 0f
    private var pendDrop = false
    private var pendEx = 0f
    private var pendEz = 0f
    private var pendFx = 0f
    private var pendFz = 1f
    private var autoCd = 0f
    /** Deslocamento vertical (m) da câmera durante a ação, para ela acompanhar a subida/queda do corpo. */
    var camLift = 0f
    /** Altura do chão sob o personagem durante uma ação (para a sombra). */
    var shadowY = 0f
    private var obsHy0 = 0f

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

    fun setParkour(list: List<ActionDef>) {
        defs = list
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
            actionKind = 0
            actWarp = 0f
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

    /** Obstáculo com a frente em z = 0 (aproximação pelo lado -z); o centro fica a meia profundidade. */
    private fun station(cx: Float, z0: Float, depth: Float, h: Float, width: Float, r: Float, g: Float, b: Float) {
        add(cx, z0 + depth / 2f, width, depth, h, r, g, b)
    }

    private fun build() {
        // Cada altura tem animações feitas sob medida: degrau 0,26 m, baixo 0,62 m, parede 1,30 m.
        // Amarelo = degrau, azul = baixo, laranja = parede, roxo = muro alto (agarrar e subir).
        // fila de treino (frente em z = 0): finos para passar por cima, fundos para subir
        station(-16f, 0f, 0.5f, 0.26f, 3f, 0.95f, 0.85f, 0.30f)
        station(-12f, 0f, 1.8f, 0.26f, 3f, 0.95f, 0.80f, 0.25f)
        station(-8f, 0f, 0.45f, 0.62f, 3f, 0.30f, 0.65f, 0.95f)
        station(-4f, 0f, 0.8f, 0.62f, 3f, 0.30f, 0.75f, 0.95f)
        station(0f, 0f, 1.6f, 0.62f, 3f, 0.35f, 0.55f, 0.95f)
        station(4f, 0f, 2.6f, 0.62f, 3f, 0.30f, 0.45f, 0.90f)
        station(8f, 0f, 0.4f, 1.30f, 3f, 0.98f, 0.55f, 0.25f)
        station(12f, 0f, 2.0f, 1.30f, 3f, 0.95f, 0.45f, 0.20f)
        station(16f, 0f, 1.0f, 1.80f, 3f, 0.70f, 0.45f, 0.90f)
        // percurso encadeado (x = 24): vários obstáculos seguidos
        station(24f, 8f, 0.45f, 0.62f, 3f, 0.30f, 0.65f, 0.95f)
        station(24f, 15f, 0.5f, 0.26f, 3f, 0.95f, 0.85f, 0.30f)
        station(24f, 22f, 1.2f, 0.62f, 3f, 0.35f, 0.55f, 0.95f)
        station(24f, 29f, 0.4f, 1.30f, 3f, 0.98f, 0.55f, 0.25f)
        // escada de alturas (0,26 / 0,62 / 1,30) para subir e descer
        add(-24f, 10f, 3f, 2f, 0.26f, 0.95f, 0.85f, 0.30f)
        add(-24f, 12f, 3f, 2f, 0.62f, 0.30f, 0.65f, 0.95f)
        add(-24f, 14f, 3f, 2f, 1.30f, 0.98f, 0.55f, 0.25f)
        // praça de descidas (z = -20): plataformas largas de alturas diferentes; corra para fora da borda
        add(-14f, -20f, 3f, 5f, 0.30f, 0.95f, 0.85f, 0.30f)
        add(-8f, -20f, 3f, 5f, 0.45f, 0.45f, 0.85f, 0.55f)
        add(-2f, -20f, 3f, 5f, 0.62f, 0.30f, 0.65f, 0.95f)
        add(4f, -20f, 3f, 5f, 0.80f, 0.70f, 0.55f, 0.95f)
        add(10f, -20f, 3f, 5f, 0.95f, 0.95f, 0.45f, 0.55f)
        // muros altos nas bordas da arena
        add(0f, 36f, 14f, 1f, 2.4f, 0.75f, 0.75f, 0.80f)
        add(-30f, 6f, 1f, 14f, 1.8f, 0.70f, 0.72f, 0.78f)
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
        pend = null
        curDef = null
        actionKind = 0
        actWarp = 0f
        camLift = 0f
        autoCd = 0f
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
            if (rise < 1.5f || rise > 1.9f) continue
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
                if (actionKind == 1) {
                    updateObstacle(dt)
                    return
                }
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

    // ---------------------------------------------------------------- obstáculos (saltos, subidas, degraus)

    /** Procura o obstáculo à frente e escolhe a animação pela altura, profundidade e velocidade. */
    private fun planObstacle(auto: Boolean = false): Boolean {
        if (defs.isEmpty()) return false
        val hy = if (speed > 0.8f) atan2(vx, vz) else yaw
        val fx = sin(hy)
        val fz = cos(hy)
        var bestT = 1e9f
        var bb: Block? = null
        var bnx = 0f
        var bnz = 0f
        for (b in blocks) {
            val rise = b.h - y
            if (rise < OBS_MIN_RISE || rise > OBS_MAX_RISE) continue
            if (auto && rise > 0.43f) continue
            val minX = b.cx - b.sx / 2f
            val maxX = b.cx + b.sx / 2f
            val minZ = b.cz - b.sz / 2f
            val maxZ = b.cz + b.sz / 2f
            var tNear = -1e9f
            var tFar = 1e9f
            var nx = 0f
            var nz = 0f
            var miss = false
            if (abs(fx) < 1e-5f) {
                if (x < minX || x > maxX) miss = true
            } else {
                var t1 = (minX - x) / fx
                var t2 = (maxX - x) / fx
                var n = -1f
                if (t1 > t2) {
                    val sw = t1; t1 = t2; t2 = sw
                    n = 1f
                }
                if (t1 > tNear) {
                    tNear = t1; nx = n; nz = 0f
                }
                if (t2 < tFar) tFar = t2
            }
            if (abs(fz) < 1e-5f) {
                if (z < minZ || z > maxZ) miss = true
            } else {
                var t1 = (minZ - z) / fz
                var t2 = (maxZ - z) / fz
                var n = -1f
                if (t1 > t2) {
                    val sw = t1; t1 = t2; t2 = sw
                    n = 1f
                }
                if (t1 > tNear) {
                    tNear = t1; nx = 0f; nz = n
                }
                if (t2 < tFar) tFar = t2
            }
            if (miss || tNear > tFar || tNear < 0f || tNear > (if (auto) 1.8f else OBS_REACH)) continue
            if (tNear < bestT) {
                bestT = tNear
                bb = b
                bnx = nx
                bnz = nz
            }
        }
        val b = bb ?: return false
        if (bnx == 0f && bnz == 0f) return false
        val cosA = fx * (-bnx) + fz * (-bnz)
        if (cosA < 0.75f) return false
        val axisX = bnx != 0f
        val plane = if (axisX) (if (bnx > 0f) b.cx + b.sx / 2f else b.cx - b.sx / 2f)
        else (if (bnz > 0f) b.cz + b.sz / 2f else b.cz - b.sz / 2f)
        val dn = if (axisX) (x - plane) * bnx else (z - plane) * bnz
        if (dn < 0.05f) return false
        val depth = if (axisX) b.sx else b.sz
        val lat = if (axisX) z else x
        val lo = if (axisX) b.cz - b.sz / 2f else b.cx - b.sx / 2f
        val hi = if (axisX) b.cz + b.sz / 2f else b.cx + b.sx / 2f
        if (hi - lo > 0.5f && (lat < lo + 0.15f || lat > hi - 0.15f)) return false

        val rise = b.h - y
        val cls = if (rise < 0.43f) 0 else if (rise < 0.96f) 1 else 2
        val running = speed > 1.8f
        var best: ActionDef? = null
        var bestScore = 1e9f
        for (d in defs) {
            if (d.drop || d.cls != cls) continue
            if (abs(rise - d.h) > OBS_WARP_MAX) continue
            if (depth < d.dMin || depth > d.dMax + 0.04f) continue
            if (dn < d.zf - d.z0 && findStart(d, dn) < 0f) continue
            var sc = abs(d.v0 - speed) * 0.6f + abs(rise - d.h) * 6f + rnd.nextFloat() * 1.2f
            if (d.name == lastDefName) sc += 2f
            // correndo: prefere passar por cima; devagar: prefere subir
            if (running == d.climb) sc += 1.5f
            if (sc < bestScore) {
                bestScore = sc
                best = d
            }
        }
        val d = best ?: return false
        pend = d
        pendNx = bnx
        pendNz = bnz
        pendPlane = plane
        pendAxisX = axisX
        pendTop = b.h
        pendT = 0f
        pendDrop = false
        lastDefName = d.name
        // já está na distância certa: começa agora
        if (dn <= d.zf - d.z0 + 0.02f) {
            val ok = startPlanned(d, dn)
            pend = null
            return ok
        }
        return true
    }

    /** Procura uma borda à frente (chão que fica bem mais baixo) e escolhe a animação de descida pela altura da queda. */
    private fun planDrop(): Boolean {
        if (defs.isEmpty() || y < 0.2f) return false
        val hy = atan2(vx, vz)
        val fx = sin(hy)
        val fz = cos(hy)
        val ed = edgeDist(0f, fx, fz)
        if (ed < 0f) return false
        // a borda precisa ser larga e quase perpendicular: raios paralelos dos dois lados acham a borda perto da mesma distância
        val el = edgeDist(0.4f, fx, fz)
        val er = edgeDist(-0.4f, fx, fz)
        if (el < 0f || er < 0f || abs(el - ed) > 0.3f || abs(er - ed) > 0.3f) return false
        // chão de pouso: três pontos depois da borda precisam estar na mesma altura
        val lx = x + fx * (ed + 0.9f)
        val lz = z + fz * (ed + 0.9f)
        val g1 = groundAt(lx, lz, y - 0.2f)
        val g2 = groundAt(x + fx * (ed + 0.5f), z + fz * (ed + 0.5f), y - 0.2f)
        val g3 = groundAt(x + fx * (ed + 1.4f), z + fz * (ed + 1.4f), y - 0.2f)
        if (abs(g1 - g2) > 0.12f || abs(g1 - g3) > 0.12f) return false
        val dh = y - g1
        if (dh < 0.22f || dh > 1.0f) return false
        var best: ActionDef? = null
        var bestScore = 1e9f
        for (d in defs) {
            if (!d.drop) continue
            if (abs(dh - d.h) > OBS_DROP_WARP) continue
            if (d.endZ - d.zf < 0.22f) continue
            if (ed < d.zf - d.z0 && findStart(d, ed) < 0f) continue
            var sc = abs(d.v0 - speed) * 0.6f + abs(dh - d.h) * 6f + rnd.nextFloat() * 1.2f
            if (d.name == lastDefName) sc += 2f
            if (sc < bestScore) {
                bestScore = sc
                best = d
            }
        }
        val d = best ?: return false
        pend = d
        pendDrop = true
        pendFx = fx
        pendFz = fz
        pendEx = x + fx * ed
        pendEz = z + fz * ed
        pendTop = g1
        pendT = 0f
        lastDefName = d.name
        if (ed <= d.zf - d.z0 + 0.02f) {
            val ok = startPlanned(d, ed)
            pend = null
            return ok
        }
        return true
    }

    /** Distância até a primeira borda onde o chão cai pelo menos 0,2 m, andando na direção (fx, fz) a partir de um ponto deslocado de lado; -1 se não há. */
    private fun edgeDist(side: Float, fx: Float, fz: Float): Float {
        val sx = x + fz * side
        val sz = z - fx * side
        var t = 0.05f
        while (t <= 2.4f) {
            val g = groundAt(sx + fx * t, sz + fz * t, y)
            if (g < y - 0.2f) return t
            t += 0.05f
        }
        return -1f
    }

    /** Tempo da animação em que o quadril já percorreu a distância que falta; -1 se está perto demais. */
    private fun findStart(d: ActionDef, dn: Float): Float {
        val c = d.clip ?: return -1f
        val zfa = d.zf - d.z0
        if (dn >= zfa) return 0f
        val need = zfa - dn
        val tMax = (d.c0 - 0.08f).coerceAtLeast(0f)
        var t = 0f
        while (t < tMax) {
            c.hips(t, tmpP)
            if (tmpP[2] - d.z0 >= need) break
            t += 1f / 60f
        }
        c.hips(t, tmpP)
        return if (tmpP[2] - d.z0 < need - 0.06f) -1f else t
    }

    private fun updatePending(dt: Float): Boolean {
        val d = pend ?: return false
        pendT += dt
        val dn = if (pendDrop) (pendEx - x) * pendFx + (pendEz - z) * pendFz
        else if (pendAxisX) (x - pendPlane) * pendNx else (z - pendPlane) * pendNz
        val zfa = d.zf - d.z0
        if (pendT > 1.6f || !grounded || dn < 0.08f || (pendT > 0.4f && speed < 0.5f && targetSpeed < 0.3f)) {
            pend = null
            return false
        }
        if (dn <= zfa + 0.02f) {
            val ok = startPlanned(d, dn)
            pend = null
            return ok
        }
        return false
    }

    private fun startPlanned(d: ActionDef, dn: Float): Boolean {
        val c = d.clip ?: return false
        val tS = findStart(d, dn)
        if (tS < 0f) return false
        curDef = d
        actionClip = c
        actionKind = 1
        obsFx = if (pendDrop) pendFx else -pendNx
        obsFz = if (pendDrop) pendFz else -pendNz
        obsX0 = x
        obsZ0 = z
        obsY0 = y
        obsTop = pendTop
        obsDelta = if (d.drop) d.h - (y - pendTop) else (pendTop - y) - d.h
        c.hips(0f, tmpP)
        apx0 = tmpP[0]
        apz0 = tmpP[2]
        c.hips(tS, tmpP)
        obsH0x = tmpP[0]
        obsH0z = tmpP[2]
        obsHy0 = tmpP[1]
        actWarp = 0f
        camLift = 0f
        vx = 0f; vz = 0f; vy = 0f
        speed = 0f
        mode = 4
        modeT = tS
        offX = 0f; offY = 0f; offZ = 0f
        return true
    }

    private fun smooth(u: Float): Float {
        val k = u.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    private fun updateObstacle(dt: Float) {
        val d = curDef
        val c = actionClip
        if (d == null || c == null) {
            mode = 0
            actionKind = 0
            return
        }
        val t = min(modeT, c.duration)
        c.hips(t, tmpP)
        val dx = tmpP[0] - obsH0x
        val dz = tmpP[2] - obsH0z
        x = obsX0 + dx * obsFz + dz * obsFx
        z = obsZ0 - dx * obsFx + dz * obsFz
        y = obsY0
        // vira de frente para o obstáculo logo no começo
        turnTo(atan2(obsFx, obsFz), min(1f, dt * 16f))
        // sobe o corpo até a altura real do obstáculo (mãos e pés encostam em cima dele)
        val up = smooth((t - (d.c0 - 0.2f)) / 0.2f)
        actWarp = if (d.drop) obsDelta * smooth((t - d.c0) / (c.duration - d.c0).coerceAtLeast(0.05f))
        else if (d.climb) obsDelta * up else obsDelta * up * (1f - smooth((t - d.c1) / 0.2f))
        camLift = tmpP[1] - obsHy0 + actWarp
        shadowY = groundAt(x, z, obsY0 + 0.05f)
        if (modeT >= c.duration - 0.04f) {
            val ve = d.ve
            vx = obsFx * ve
            vz = obsFz * ve
            speed = ve
            x = x.coerceIn(-BOUND, BOUND)
            z = z.coerceIn(-BOUND, BOUND)
            // altura de chegada: sobe = topo do obstáculo; desce = chão de pouso (nunca volta para o topo da borda); salto = chão
            val feet = if (d.climb) obsTop + 0.05f else if (d.drop) obsTop - 0.1f else obsY0 + 0.1f
            var gy = groundAt(x, z, feet)
            if (d.drop && gy > obsY0) gy = obsY0
            // primeiro acerta a altura, só depois empurra para fora das paredes (antes o empurrão jogava o corpo de volta ao chão)
            y = gy
            pushOut()
            offY = gy - obsY0
            supportY = gy
            grounded = true
            mode = 0
            modeT = 0f
            camLift = 0f
            autoCd = 0.6f
        }
    }

    // ---------------------------------------------------------------- movimento normal

    private fun updateNormal(dt: Float, presses: Int) {
        if (autoCd > 0f) autoCd -= dt
        if (pend != null && updatePending(dt)) {
            updateSpecial(0f, 0)
            return
        }
        // automático: degraus baixos e bordas (descidas) disparam a animação certa sem apertar nada
        if (pend == null && grounded && windT < 0f && autoCd <= 0f && speed > 1.0f && targetSpeed > 0.8f) {
            if (planDrop() || planObstacle(true)) {
                if (pend == null) {
                    updateSpecial(0f, 0)
                    return
                }
            }
        }
        for (i in 0 until presses) {
            val dbl = timeNow - lastPress < DOUBLE_T
            lastPress = timeNow
            if (dbl && tryGrab()) {
                updateSpecial(0f, 0)
                return
            }
            if (pend != null) continue
            if (grounded && windT < 0f && planObstacle()) {
                if (pend == null) {
                    updateSpecial(0f, 0)
                    return
                }
                continue
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
                vy = if (jumpSpeed > 1.5f) JUMP_RUN else JUMP_STAND
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
        // pulo parado ~0,5 m e pulo correndo ~0,65 m (antes 1,2 m nos dois)
        const val JUMP_STAND = 3.6f
        const val JUMP_RUN = 4.1f
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
        // obstáculos: alcance da busca, alturas aceitas (m acima dos pés) e ajuste máximo da animação
        const val OBS_REACH = 3.6f
        const val OBS_MIN_RISE = 0.14f
        const val OBS_MAX_RISE = 1.55f
        const val OBS_WARP_MAX = 0.36f
        const val OBS_DROP_WARP = 0.25f
    }
}
