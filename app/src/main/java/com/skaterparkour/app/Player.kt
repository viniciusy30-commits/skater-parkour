package com.skaterparkour.app

import kotlin.math.*

/** Controles já em coordenadas do mundo (a câmera converte o joystick). */
class Ctl {
    var mx = 0f; var mz = 0f            // direção desejada (módulo 0..1)
    var jump = false                    // toque no botão de pulo (borda)
    var jumpHeld = false
    var crouch = false                  // toque em agachar/deslizar/mergulhar (borda)
    var crouchHeld = false
    var drop = false                    // soltar (borda): larga a beirada
    fun clearEdges() { jump = false; crouch = false; drop = false }
}

class JumpDef(val clip: String, val tStart: Float, val tLaunch: Float, val tLand: Float, val tEnd: Float, val h: Float, val pre: Float)

/**
 * Controlador do personagem: locomoção (Mobility Pro), pulos, aterrissagens, vault, subida em plataformas,
 * pendurar/escalar beirada, deslizar, trave de equilíbrio e natação (pacote Swim).
 * Tudo em metros; +Y para cima; frente = (sin(yaw), cos(yaw)). A raiz do personagem fica nos pés.
 */
class Player(val w: World, val cs: ClipSet) {
    companion object {
        const val R = 0.3f; const val H = 1.8f; const val STEP = 0.45f; const val G = 22f
        const val HANG_ROOT = 1.74f          // raiz fica 1,74 m abaixo do topo da beirada ao pendurar
        const val HANG_FACE = 0.18f          // distância da raiz até a face da parede ao pendurar
        val JUMPS = mapOf(
            "stand" to JumpDef("m:Stand_Relaxed_Jump", 0.40f, 1.00f, 1.13f, 1.75f, 0.50f, 1.5f),
            "walk" to JumpDef("m:Walk_F_Jump", 0.15f, 0.73f, 0.80f, 1.30f, 0.55f, 1.4f),
            "jog" to JumpDef("m:Jog_F_Jump", 0.0f, 0.23f, 0.33f, 0.90f, 0.60f, 1.0f),
            "run" to JumpDef("m:Run_F_Jump", 0.08f, 0.40f, 0.90f, 1.30f, 0.85f, 1.0f)
        )
        val SPEED_WALK = 1.5f; val SPEED_JOG = 2.5f; val SPEED_RUN = 4.95f; val SPEED_CROUCH = 1.25f
        val VAULTS_L = arrayOf("Speed_L_Vault", "Dash_Vault", "Thief_L_Vault", "Safety_L_Vault", "TwoHand_L_Vault", "Revers_L_Vault")
        val VAULTS_R = arrayOf("Speed_R_Vault", "Dash_Vault", "Thief_R_Vault", "Safety_R_Vault", "TwoHand_R_Vault", "Revers_R_Vault")
        val VAULT_TOP = mapOf("Speed_L_Vault" to 1.05f, "Speed_R_Vault" to 1.05f, "Dash_Vault" to 0.95f, "Thief_L_Vault" to 0.9f, "Thief_R_Vault" to 0.9f,
            "Safety_L_Vault" to 1.05f, "Safety_R_Vault" to 1.05f, "TwoHand_L_Vault" to 1.05f, "TwoHand_R_Vault" to 1.05f,
            "Revers_L_Vault" to 1.0f, "Revers_R_Vault" to 1.0f, "Kong_Vault" to 1.25f, "Monkey_Vault" to 1.2f)
    }

    enum class St { LOCO, AIR, LAND, MAN, HANG, BEAM, SWIM }

    // ---- saída
    var x = 0f; var y = 0f; var z = 0f; var yaw = 0f
    var clip = "m:Stand_Relaxed_Idle"; var t = 0f; var loop = true
    var xfade = 0.2f; var clipChanged = true
    var st = St.LOCO
    var info = ""

    // ---- internos
    private var vx = 0f; private var vz = 0f; private var vy = 0f
    private var speed = 0f
    private var crouched = false
    private val tmp = FloatArray(3); private val tmp2 = FloatArray(3)
    private var vaultRot = 0

    // salto
    private var jd: JumpDef? = null; private var jT = 0f; private var jAir = 0f; private var jNom = 0.5f; private var jLanded = false; private var jLandT = 0f
    private var fallStartY = 0f; private var airT = 0f
    // aterrissagem
    private var landT = 0f; private var landDur = 0.3f

    // maneuver
    private class Man(val clip: String, val rate: Float, val x0: Float, val y0: Float, val z0: Float, val yaw0: Float, val yawT: Float,
                       val zk: FloatArray, val ys: Float, val xs: Float, val yAdd: (Float) -> Float, val xAdd: (Float) -> Float,
                       val r0: FloatArray, val dur: Float, val onEnd: () -> Unit, val yawBlend: Float = 0.15f) { var t = 0f }
    private var man: Man? = null

    // pendurado
    private var hBox: Box? = null; private var hNx = 0f; private var hNz = 0f; private var hTop = 0f; private var hS = 0f // posição lateral
    private var hAxisX = false
    private var hangT = 0f
    fun hangedTime() = hangT

    // beam
    private var beamBox: Box? = null

    // natação
    private var swimW: Water? = null; private var swimY = 0f; private var swimVy = 0f; private var underwater = false; private var wadeDepth = 0f
    private var swimMode = 0 // 0 superfície, 1 submerso
    private var swimEnterT = 0f

    private fun C(n: String) = cs[n]
    private fun fwdx() = sin(yaw); private fun fwdz() = cos(yaw)

    private fun setClip(n: String, lp: Boolean, start: Float = 0f, fade: Float = 0.2f) {
        if (n != clip) { clip = n; clipChanged = true; xfade = fade }
        t = start; loop = lp
    }

    fun teleport(nx: Float, ny: Float, nz: Float, nyaw: Float) {
        x = nx; y = ny; z = nz; yaw = nyaw; vx = 0f; vz = 0f; vy = 0f; speed = 0f; st = St.LOCO; man = null; crouched = false
        setClip("m:Stand_Relaxed_Idle", true, 0f, 0.1f); hBox = null; beamBox = null; swimW = null
    }

    // =====================================================================================================
    fun update(dt: Float, c: Ctl) {
        val ww = if (st == St.LOCO || st == St.AIR || st == St.BEAM) w.waterAt(x, z) else null
        if (ww != null) {
            val fl = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            val depth = ww.surface - fl
            if (y < ww.surface - 0.55f && depth > 1.15f) enterWater(ww)
        }
        when (st) {
            St.LOCO -> loco(dt, c)
            St.AIR -> air(dt, c)
            St.LAND -> land(dt, c)
            St.MAN -> maneuver(dt)
            St.HANG -> hang(dt, c)
            St.BEAM -> beam(dt, c)
            St.SWIM -> swim(dt, c)
        }
        c.clearEdges()
    }

    // ---------------------------------------------------------------------------------------- locomoção
    private fun loco(dt: Float, c: Ctl) {
        val m = min(1f, hypot(c.mx, c.mz))
        if (c.crouch) {
            if (speed > 3.3f && !crouched) { startSlide(); return }
            crouched = !crouched
        }
        var tgt = 0f
        if (m > 0.12f) tgt = if (crouched) SPEED_CROUCH else if (m < 0.45f) SPEED_WALK else if (m < 0.8f) SPEED_JOG else SPEED_RUN
        // virar
        var diff = 0f
        if (m > 0.12f) {
            val ta = atan2(c.mx, c.mz); diff = wrapA(ta - yaw)
            val rate = if (speed < 0.5f) 9f else 13f
            val step = rate * dt
            yaw += clampf(diff, -step, step)
            if (abs(diff) > 1.7f && speed > 2.0f) tgt = min(tgt, 1.0f)
        }
        val k = max(0.25f, cos(min(abs(diff), 1.5f)))
        val goal = tgt * (if (abs(diff) > 0.9f) k else 1f)
        val acc = if (goal > speed) 16f else 26f
        speed += clampf(goal - speed, -acc * dt, acc * dt)
        if (speed < 0.02f && tgt == 0f) speed = 0f
        vx = fwdx() * speed; vz = fwdz() * speed

        if (c.jump) { if (contextJump(c)) return; startJump(); return }

        // mover
        val p = floatArrayOf(x + vx * dt, y, z + vz * dt)
        w.pushOut(p, y, R, H, STEP)
        x = p[0]; z = p[2]
        val g = w.groundAt(x, z, 0.12f, y + STEP)
        if (g < y - STEP - 0.01f) { // saiu da beirada
            startFall(); return
        }
        y = if (g > y) min(g, y + 9f * dt) else if (g < y) max(g, y - 9f * dt) else y
        // equilíbrio
        val bu = w.boxUnder(x, z, y)
        if (bu != null && bu.kind == 2 && abs(y - bu.y1) < 0.1f) { st = St.BEAM; beamBox = bu; setClip("p:Balance_Idle", true, 0f, 0.25f); return }
        locoClip(dt)
    }

    private fun locoClip(dt: Float) {
        val want: String; val clipSpd: Float
        if (crouched) {
            if (speed < 0.1f) { want = "m:Crouch_Idle"; clipSpd = 0f } else { want = "m:CrouchWalk_F"; clipSpd = 1.25f }
        } else if (speed < 0.1f) { want = "m:Stand_Relaxed_Idle"; clipSpd = 0f }
        else if (speed < 2.0f) { want = "m:Walk_F"; clipSpd = SPEED_WALK }
        else if (speed < 3.7f) { want = "m:Jog_F"; clipSpd = SPEED_JOG }
        else { want = "m:Run_F"; clipSpd = SPEED_RUN }
        if (want != clip) {
            val oldD = if (cs.has(clip)) C(clip).dur else 1f
            val ph = if (clipSpd > 0f && clip.startsWith("m:") && (clip.contains("_F") )) (t / oldD) else 0f
            setClip(want, true, ph * C(want).dur, if (clipSpd == 0f) 0.3f else 0.22f)
        }
        val rate = if (clipSpd > 0f) clampf(speed / clipSpd, 0.6f, 1.35f) else 1f
        t += dt * rate
    }

    // ---------------------------------------------------------------------------------------- pulos
    private fun startJump() {
        val key = if (speed < 0.5f) "stand" else if (speed < 2.0f) "walk" else if (speed < 3.7f) "jog" else "run"
        val d = JUMPS[key]!!
        jd = d; jT = d.tStart; jAir = 0f; jLanded = false
        val vy0 = sqrt(2f * G * d.h); vy = vy0; jNom = 2f * vy0 / G
        st = St.AIR; fallStartY = y; airT = 0f; jPre = true; jPreT = 0f
        setClip(d.clip, false, d.tStart, 0.12f)
        info = "pulo $key"
    }
    private var jPre = false; private var jPreT = 0f

    private fun startFall() {
        jd = null; jPre = false; st = St.AIR; vy = 0f; fallStartY = y; airT = 0f
        setClip("p:Fly_Front", true, 0f, 0.25f); info = "queda"
    }

    private fun air(dt: Float, c: Ctl) {
        val d = jd
        if (jPre && d != null) {
            // antecipação (agacha) antes de sair do chão
            jPreT += dt * d.pre; jT = d.tStart + jPreT
            t = jT
            val m = min(1f, hypot(c.mx, c.mz))
            if (jT >= d.tLaunch - 0.02f) { jPre = false; vy = sqrt(2f * G * d.h) }
            else {
                // ainda no chão: segue andando
                val p = floatArrayOf(x + vx * dt, y, z + vz * dt); w.pushOut(p, y, R, H, STEP); x = p[0]; z = p[2]
                return
            }
        }
        airT += dt
        // controle aéreo
        val m = min(1f, hypot(c.mx, c.mz))
        if (m > 0.12f) {
            val ta = atan2(c.mx, c.mz); yaw += clampf(wrapA(ta - yaw), -5f * dt, 5f * dt)
            val ac = 7f * dt
            vx += clampf(c.mx * max(2.5f, speed) - vx, -ac, ac); vz += clampf(c.mz * max(2.5f, speed) - vz, -ac, ac)
        }
        vy -= G * dt
        val p = floatArrayOf(x + vx * dt, y, z + vz * dt)
        w.pushOut(p, y + 0.0f, R, H, 0.1f)
        x = p[0]; z = p[2]
        val ny = y + vy * dt
        val g = w.groundAt(x, z, 0.12f, y + 0.3f + max(0f, -vy * dt))
        if (vy <= 0f && ny <= g + 0.001f) { y = g; onLand(); return }
        y = ny
        // pegar beirada no ar
        if (c.jump || c.jumpHeld) { if (tryAirGrab()) return }
        // animação
        if (d != null) {
            val nomT = jNom
            if (airT < nomT + 0.05f) {
                val a = clampf(airT / nomT, 0f, 1f)
                t = d.tLaunch - 0.05f + a * (d.tLand - d.tLaunch + 0.05f)
                loop = false
            } else if (clip == d.clip) { setClip(if (vy < -5f) "p:Fly_Down" else "p:Fly_Front", true, 0f, 0.25f) }
        } else setClip(if (vy < -6f) "p:Fly_Down" else "p:Fly_Front", true, if (clip.startsWith("p:Fly")) t + dt else 0f, 0.25f)
        if (clip.startsWith("p:Fly")) { t += 0f }
    }

    private fun onLand() {
        val fall = fallStartY - y
        val d = jd
        val sp = hypot(vx, vz)
        if (d != null && fall < 0.9f && abs(airT - jNom) < 0.25f) {
            // pouso do próprio pulo: continua o clipe a partir do contato
            st = St.LAND; landT = 0f; landDur = max(0.05f, d.tEnd - d.tLand) * 0.7f
            setClip(d.clip, false, d.tLand, 0.08f); jd = d
            speed = sp; vy = 0f; return
        }
        val roll = sp > 3.0f && fall > 1.6f
        val clipN: String; var dur: Float
        if (roll) { clipN = if ((airT * 7).toInt() % 2 == 0) "p:Landing_Roll_A_L" else "p:Landing_Roll_A_R"; dur = C(clipN).dur }
        else if (fall > 3.0f) { clipN = "p:Landing_Down"; dur = C(clipN).dur }
        else if (fall > 1.2f) { clipN = "p:Landing_Down_Light"; dur = C(clipN).dur }
        else { clipN = if (sp > 2.5f) "p:LandingGround_L" else "p:Landing_Down_Light"; dur = min(C(clipN).dur, 0.5f) }
        beginMan(clipN, 1f, null, dur, { })
        // rolamento anda com a raiz do clipe; pouso parado só amortece
        info = "pouso " + clipN
        speed = 0f; vx = 0f; vz = 0f; jd = null; vy = 0f
        manLandGround = true
    }
    private var manLandGround = false

    private fun land(dt: Float, c: Ctl) {
        landT += dt
        t += dt
        val m = min(1f, hypot(c.mx, c.mz))
        // anda devagar durante a recuperação
        val p = floatArrayOf(x + vx * dt * 0.5f, y, z + vz * dt * 0.5f); w.pushOut(p, y, R, H, STEP); x = p[0]; z = p[2]
        if (landT >= landDur || (c.jump && landT > 0.08f)) {
            st = St.LOCO; jd = null
            if (c.jump) { startJump(); return }
        }
    }

    // ---------------------------------------------------------------------------------------- contexto do pulo
    private fun contextJump(c: Ctl): Boolean {
        val dx = fwdx(); val dz = fwdz()
        val h = w.probe(x, z, dx, dz, 2.1f, y + 0.5f, y + 1.1f) ?: return false
        val hRel = h.top - y
        // ângulo com a normal
        val ang = acos(clampf(-(dx * h.nx + dz * h.nz), -1f, 1f))
        if (ang > 1.0f) return false
        if (hRel <= 1.55f) {
            if (h.dist > 2.0f) return false
            if (h.depth <= 2.3f && (h.farGround > y - 1.3f)) { startVault(h, hRel); return true }
            if (h.depth >= 1.5f && hRel <= 1.45f) { startClimbOn(h, hRel); return true }
            return false
        }
        if (hRel <= 2.65f && h.dist <= 1.9f) { startHangAttach(h, hRel); return true }
        return false
    }

    private fun tryAirGrab(): Boolean {
        if (vy > 4.5f) return false
        val h = w.probe(x, z, fwdx(), fwdz(), 0.95f, y + 1.2f, y + 3f) ?: return false
        val dtop = h.top - y
        if (dtop < 1.35f || dtop > 2.35f) return false
        if (h.dist > 0.8f) return false
        val ang = acos(clampf(-(fwdx() * h.nx + fwdz() * h.nz), -1f, 1f))
        if (ang > 0.9f) return false
        // precisa de espaço livre no topo (chão no topo)
        enterHang(h.box, h.nx, h.nz, h.top, if (abs(h.nx) > 0.5f) z else x)
        return true
    }

    // ---------------------------------------------------------------------------------------- maneuvers
    private fun beginMan(clipN: String, rate: Float, zk: FloatArray?, durLimit: Float, onEnd: () -> Unit, ys: Float = 1f, yAdd: (Float) -> Float = { 0f },
                         xAdd: (Float) -> Float = { 0f }, yawT: Float = yaw, yawBlend: Float = 0.15f, fade: Float = 0.12f, startT: Float = 0f) {
        val cl = C(clipN); val r0 = FloatArray(3); cs.rootAt(cl, startT, r0)
        man = Man(clipN, rate, x, y, z, yaw, yawT, zk ?: floatArrayOf(0f, 0f, 20f, 20f), ys, 1f, yAdd, xAdd, r0, min(durLimit, cl.dur - startT) , onEnd, yawBlend)
        man!!.t = startT
        st = St.MAN; setClip(clipN, false, startT, fade)
    }

    private fun zmap(zk: FloatArray, dz: Float): Float {
        val n = zk.size / 2
        if (dz <= zk[0]) return zk[1] + (dz - zk[0])
        for (i in 0 until n - 1) {
            val a = zk[i * 2]; val b = zk[i * 2 + 2]
            if (dz <= b) { val u = (dz - a) / max(1e-4f, b - a); return zk[i * 2 + 1] + u * (zk[i * 2 + 3] - zk[i * 2 + 1]) }
        }
        return zk[n * 2 - 1] + (dz - zk[n * 2 - 2])
    }

    private fun maneuver(dt: Float) {
        val m = man ?: run { st = St.LOCO; return }
        m.t += dt * m.rate
        val cl = C(m.clip)
        val tc = min(m.t, cl.dur)
        t = tc
        cs.rootAt(cl, tc, tmp)
        val dx = tmp[0] - m.r0[0]; val dy = tmp[1] - m.r0[1]; val dz = tmp[2] - m.r0[2]
        val tn = clampf(m.t / max(1e-3f, m.dur), 0f, 1f)
        val lx = dx + m.xAdd(tn); val ly = dy * m.ys + m.yAdd(tn); val lz = zmap(m.zk, dz)
        val yb = clampf(m.t / max(0.01f, m.yawBlend), 0f, 1f)
        yaw = m.yaw0 + wrapA(m.yawT - m.yaw0) * smooth(yb)
        val c = cos(m.yaw0 + wrapA(m.yawT - m.yaw0)); val s = sin(m.yaw0 + wrapA(m.yawT - m.yaw0))
        // eixo local usa o yaw final (alinha com o obstáculo)
        x = m.x0 + lx * c + lz * s; z = m.z0 - lx * s + lz * c; y = m.y0 + ly
        if (m.t >= m.dur) { man = null; val e = m.onEnd; if (manLandGround) { manLandGround = false; st = St.LOCO; snapGround() } ; e() }
    }

    private fun snapGround() {
        val g = w.groundAt(x, z, 0.12f, y + 0.6f)
        if (g > -500f) y = g
    }

    private fun clipRootZ(clipN: String, tIn: Float): Float { val cl = C(clipN); cs.rootAt(cl, tIn, tmp2); return tmp2[2] }
    /** Instante (s) em que a raiz do clipe atinge o deslocamento z. */
    private fun timeAtZ(clipN: String, z: Float): Float {
        val cl = C(clipN)
        for (f in 0 until cl.nf) if (cl.root[f * 3 + 2] >= z) return f / 30f
        return cl.dur
    }

    private fun startVault(h: Hit, hRel: Float) {
        val left = ((vaultRot++) % 2 == 0)
        val set = if (left) VAULTS_L else VAULTS_R
        // escolhe o clipe cujo topo autoral está mais perto
        var best = ""; var bd = 9f
        val pool = ArrayList<String>(set.toList()); pool.add("Kong_Vault"); pool.add("Monkey_Vault")
        val start = (vaultRot * 3) % pool.size
        for (i in pool.indices) {
            val n = pool[(start + i) % pool.size]
            val dd = abs((VAULT_TOP[n] ?: 1f) - hRel) + (if (hRel > 1.2f && n != "Kong_Vault" && n != "Monkey_Vault") 0.3f else 0f) + (if (hRel < 1.1f && (n == "Kong_Vault")) 0.4f else 0f)
            if (dd < bd - 0.02f) { bd = dd; best = n }
        }
        val topClip = VAULT_TOP[best] ?: 1f
        val zin = 1.4f; val zout = 2.6f
        val cl = C("p:$best")
        val tin = timeAtZ("p:$best", 1.0f); val tout = timeAtZ("p:$best", zout + 0.5f)
        val endZ = clipRootZ("p:$best", cl.dur)
        val ang = atan2(-h.nx, -h.nz)
        val d0 = max(0.25f, h.dist + R * 0.0f)
        val dEnd = d0 + h.depth
        val dyBump = hRel - topClip
        val farG = if (h.farGround > -500f) h.farGround else (y)
        val dyEnd = farG - y
        val zk = floatArrayOf(0f, 0f, zin - 0.2f, d0 - 0.1f, zout, dEnd + 0.1f, max(zout + 0.5f, endZ), dEnd + 0.1f + (max(zout + 0.5f, endZ) - zout))
        val dur = cl.dur
        val yAdd = { tn: Float ->
            val tt = tn * dur
            val up = smooth((tt - (tin - 0.15f)) / 0.15f); val dn = smooth((tt - tout) / 0.2f)
            dyBump * up * (1f - dn) + dyEnd * dn
        }
        speed = 0f; vx = 0f; vz = 0f
        info = "vault $best"
        beginMan("p:$best", 1f, zk, dur, { st = St.LOCO; speed = SPEED_JOG * 0.8f; snapGround() }, 1f, yAdd, { 0f }, ang, 0.2f)
    }

    private fun startClimbOn(h: Hit, hRel: Float) {
        val opts = arrayOf("Step_L_On" to 0.46f, "Jump_L_On" to 1.03f, "TwoHand_L_On" to 1.2f, "Monkey_On" to 1.3f, "Roll_L_On" to 1.0f)
        var best = opts[0]; var bd = 9f
        for (o in opts) { val dd = abs(o.second - hRel); if (dd < bd) { bd = dd; best = o } }
        val nm = "p:" + best.first; val cl = C(nm)
        val ang = atan2(-h.nx, -h.nz)
        val endZ = clipRootZ(nm, cl.dur); val rise = best.second
        val faceClip = 1.0f
        val d0 = max(0.25f, h.dist)
        val zk = floatArrayOf(0f, 0f, faceClip, d0, max(faceClip + 0.5f, endZ), d0 + (max(faceClip + 0.5f, endZ) - faceClip))
        val tin = timeAtZ(nm, faceClip - 0.4f); val dur = cl.dur
        val dyTot = hRel - rise
        val yAdd = { tn: Float -> dyTot * smooth((tn * dur - tin) / max(0.1f, dur - tin)) }
        speed = 0f; vx = 0f; vz = 0f
        info = "subir " + best.first
        beginMan(nm, 1f, zk, dur, { st = St.LOCO; snapGround() }, 1f, yAdd, { 0f }, ang, 0.2f)
    }

    private fun startHangAttach(h: Hit, hRel: Float) {
        val nm = "p:Hanging_Attach"; val cl = C(nm)
        val ang = atan2(-h.nx, -h.nz)
        val faceClip = 1.38f + HANG_FACE
        val d0 = max(0.3f, h.dist)
        val endZ = clipRootZ(nm, cl.dur)
        val zk = floatArrayOf(0f, 0f, faceClip, d0, faceClip + 1f, d0 + 1f)
        // y final do mundo = topo - 1.74 ; delta do clipe = -0.88
        val dyEnd = (h.top - HANG_ROOT - y) - (-0.88f)
        val dur = cl.dur
        val yAdd = { tn: Float -> dyEnd * smooth((tn - 0.05f) / 0.45f) }
        val box = h.box; val nx = h.nx; val nz = h.nz; val top = h.top
        val s = if (abs(nx) > 0.5f) z else x
        speed = 0f; vx = 0f; vz = 0f
        info = "pendurar"
        beginMan(nm, 1f, zk, dur, { enterHang(box, nx, nz, top, s) }, 1f, yAdd, { 0f }, ang, 0.25f)
    }

    private fun startSlide() {
        // caminho livre?
        val dx = fwdx(); val dz = fwdz()
        var ok = true
        var d = 0.5f
        while (d <= 5.4f) { if (w.blocked(x + dx * d, z + dz * d, 0.25f, y + 0.5f, y + 0.55f)) ok = false; d += 0.5f }
        if (!ok) { crouched = true; return }
        info = "deslizar"
        speed = 0f
        val s = "p:Slide_L_Start"
        beginMan(s, 1f, null, C(s).dur, {
            beginMan("p:Slide_L_Finish", 1f, null, C("p:Slide_L_Finish").dur, { st = St.LOCO; speed = 2f; crouched = false; snapGround() }, fade = 0.05f)
        }, fade = 0.15f)
    }

    // ---------------------------------------------------------------------------------------- pendurado
    private fun enterHang(b: Box, nx: Float, nz: Float, top: Float, s: Float) {
        hBox = b; hNx = nx; hNz = nz; hTop = top; hAxisX = abs(nz) > 0.5f
        hS = s; yaw = atan2(-nx, -nz); st = St.HANG; man = null; vy = 0f; vx = 0f; vz = 0f; speed = 0f; jd = null
        hangPlace()
        setClip("p:Hanging_Idle", true, 0f, 0.15f); info = "pendurado"; hangT = 0f
    }

    private fun hangPlace() {
        val b = hBox!!
        val face = if (abs(hNx) > 0.5f) (if (hNx < 0) b.x0 else b.x1) else (if (hNz < 0) b.z0 else b.z1)
        if (abs(hNx) > 0.5f) { x = face + hNx * HANG_FACE; z = hS } else { z = face + hNz * HANG_FACE; x = hS }
        y = hTop - HANG_ROOT
    }

    private fun hang(dt: Float, c: Ctl) {
        val b = hBox ?: run { st = St.LOCO; return }
        hangT += dt
        val lx = cos(yaw); val lz = -sin(yaw) // esquerda do personagem
        val ml = c.mx * lx + c.mz * lz
        val lim0: Float; val lim1: Float
        if (abs(hNx) > 0.5f) { lim0 = b.z0 + 0.35f; lim1 = b.z1 - 0.35f } else { lim0 = b.x0 + 0.35f; lim1 = b.x1 - 0.35f }
        // movimento lateral
        var mv = 0f
        if (ml > 0.35f) mv = 1f else if (ml < -0.35f) mv = -1f
        if (mv != 0f) {
            // esquerda do personagem em eixo do mundo
            val axisPos = if (abs(hNx) > 0.5f) lz else lx  // componente da esquerda no eixo tangente
            val dir = mv * (if (axisPos >= 0) 1f else -1f)
            hS = clampf(hS + dir * 1.1f * dt, lim0, lim1)
            val want = if (mv > 0) "p:Hanging_Move_L" else "p:Hanging_Move_R"
            if (clip != want) setClip(want, true, 0f, 0.12f)
            t += dt
        } else {
            if (clip != "p:Hanging_Idle") setClip("p:Hanging_Idle", true, 0f, 0.15f)
            t += dt
        }
        hangPlace()
        if (c.jump) { hangClimb(); return }
        if (c.crouch || c.drop) {
            setClip("p:Hanging_Drop", false, 0f, 0.1f)
            st = St.AIR; jd = null; vy = -1f; fallStartY = y; airT = 0f; jPre = false
            // empurra um pouco para longe da parede
            x += hNx * 0.25f; z += hNz * 0.25f
            vx = 0f; vz = 0f; info = "soltou"
        }
    }

    private fun hangClimb() {
        val b = hBox!!
        // há chão no topo?
        val ex = x - hNx * 0.5f; val ez = z - hNz * 0.5f
        if (w.groundAt(ex, ez, 0.1f, hTop + 0.05f) < hTop - 0.1f) return
        val nm = "p:Hanging_ClimbeUp_F"; val cl = C(nm)
        info = "escalar"
        val top = hTop
        beginMan(nm, 1f, null, cl.dur, { st = St.LOCO; y = top; snapGround(); crouched = false }, HANG_ROOT / 2.12f, { 0f }, { 0f }, yaw, 0.01f, 0.15f)
    }

    // ---------------------------------------------------------------------------------------- trave
    private fun beam(dt: Float, c: Ctl) {
        val b = beamBox ?: run { st = St.LOCO; return }
        val m = min(1f, hypot(c.mx, c.mz))
        val alongX = (b.x1 - b.x0) > (b.z1 - b.z0)
        var mv = 0f
        if (m > 0.2f) {
            val ta = atan2(c.mx, c.mz); yaw += clampf(wrapA(ta - yaw), -8f * dt, 8f * dt)
            // projeta na trave
            val dirx = if (alongX) sign(c.mx) else 0f; val dirz = if (alongX) 0f else sign(c.mz)
            val ty = if (alongX) (if (dirx >= 0) PI.toFloat() / 2f else -PI.toFloat() / 2f) else (if (dirz >= 0) 0f else PI.toFloat())
            yaw += clampf(wrapA(ty - yaw), -12f * dt, 12f * dt)
            if (abs(wrapA(ty - yaw)) < 0.5f) mv = 1.6f * m
            x += fwdx() * mv * dt; z += fwdz() * mv * dt
            if (alongX) z = clampf(z, (b.z0 + b.z1) / 2 - 0.03f, (b.z0 + b.z1) / 2 + 0.03f) else x = clampf(x, (b.x0 + b.x1) / 2 - 0.03f, (b.x0 + b.x1) / 2 + 0.03f)
        }
        val want = if (mv > 0.2f) "p:Balance_Run" else "p:Balance_Idle"
        if (clip != want) setClip(want, true, 0f, 0.2f)
        t += dt * (if (mv > 0.2f) clampf(mv / 2.2f, 0.5f, 1.2f) else 1f)
        y = b.y1
        if (!b.containsXZ(x, z, -0.0f) || w.groundAt(x, z, 0.05f, y + 0.1f) < y - 0.2f) {
            if (!b.containsXZ(x, z, 0.05f)) { beamBox = null; val g = w.groundAt(x, z, 0.1f, y + STEP); if (g >= y - STEP) { st = St.LOCO; speed = 0f } else startFall() }
        }
        if (c.jump) { beamBox = null; st = St.LOCO; speed = 0f; startJump() }
    }

    // ---------------------------------------------------------------------------------------- água
    private fun enterWater(ww: Water) {
        swimW = ww; st = St.SWIM; man = null; underwater = false; swimMode = 0; swimVy = min(0f, vy) * 0.3f
        swimY = y; speed = 0f; swimEnterT = 0f; jd = null; jPre = false
        setClip("s:Jump_A_End_01", false, 0f, 0.1f); info = "entrou na água"
    }

    private fun swim(dt: Float, c: Ctl) {
        val ww = swimW ?: run { st = St.LOCO; return }
        swimEnterT += dt
        val floor = w.groundAt(x, z, 0.12f, ww.surface + 3f)
        val depth = ww.surface - floor
        val m = min(1f, hypot(c.mx, c.mz))
        val surfRoot = ww.surface - 1.0f  // raiz de quem nada na superfície
        // direção
        if (m > 0.12f) {
            val ta = atan2(c.mx, c.mz); yaw += clampf(wrapA(ta - yaw), -6f * dt, 6f * dt)
        }
        val spd = if (m < 0.12f) 0f else (if (swimMode == 1) 2.0f else (if (m < 0.5f) 1.2f else 2.1f)) * (if (m < 0.5f) 1f else 1f)
        speed += clampf(spd - speed, -6f * dt, 6f * dt)
        val p = floatArrayOf(x + fwdx() * speed * dt, y, z + fwdz() * speed * dt)
        // mantém dentro do volume + empurra paredes da piscina
        w.pushOut(p, y + 0.3f, R, H - 0.3f, 0.2f)
        x = p[0]; z = p[2]
        // vertical
        val head = y + 1.0f
        if (swimMode == 0) {
            // flutua na superfície
            val gy = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            val target = max(surfRoot, gy)
            y += clampf(target - y, -3.0f * dt, 3.0f * dt)
            if (c.crouch && swimEnterT > 0.4f) { swimMode = 1; swimVy = 0f; setClip("s:Dive_A", false, 0f, 0.15f); info = "mergulhando"; return }
            if (c.jump && swimEnterT > 0.5f) { if (!waterExit(c)) { /* pulinho na água */ } }
            surfaceClip(dt, m)
        } else {
            // submerso: pular sobe, agachar desce
            val up = if (c.jumpHeld) 1f else 0f; val dn = if (c.crouchHeld) 1f else 0f
            val ty = (up - dn) * 2.2f
            swimVy += clampf(ty - swimVy, -5f * dt, 5f * dt)
            if (up == 0f && dn == 0f) swimVy += clampf(-0.0f - swimVy, -2f * dt, 2f * dt)
            var ny = y + swimVy * dt
            val gy = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            if (ny < gy) { ny = gy; swimVy = 0f }
            if (ny >= surfRoot) { // voltou à superfície
                ny = surfRoot; swimMode = 0; swimVy = 0f; swimEnterT = 0f
                setClip("s:to_surface", false, 0f, 0.15f); y = ny; return
            }
            y = ny
            underClip(dt, m)
        }
    }

    private fun surfaceClip(dt: Float, m: Float) {
        val want: String; var rate = 1f
        if (clip == "s:Jump_A_End_01" && t < C(clip).dur - 0.05f && swimEnterT < C(clip).dur) { t += dt; return }
        if (clip == "s:to_surface" && t < C(clip).dur - 0.05f) { t += dt; return }
        want = if (m < 0.12f) "s:idle_A" else if (speed > 1.6f) "s:forward_A" else "s:forward_A_slow"
        if (clip != want) setClip(want, true, if (want.startsWith("s:forward")) 0f else 0f, 0.25f)
        t += dt * rate
    }

    private fun underClip(dt: Float, m: Float) {
        if (clip == "s:Dive_A" && t < C(clip).dur - 0.05f) { t += dt; return }
        val want = if (swimVy < -0.8f) "s:Topedo_down" else if (swimVy > 0.8f) "s:Topedo_up" else if (m > 0.12f) "s:Topedo_fwd" else "s:floating"
        if (clip != want) setClip(want, true, 0f, 0.3f)
        t += dt
    }

    private fun waterExit(c: Ctl): Boolean {
        val ww = swimW ?: return false
        val dx = fwdx(); val dz = fwdz()
        val h = w.probe(x, z, dx, dz, 1.4f, ww.surface + 0.05f, ww.surface + 1.2f) ?: return false
        val hRel = h.top - y
        if (h.top - ww.surface > 1.3f) return false
        // sai da água subindo na borda
        val nm = "p:Jump_L_On"; val cl = C(nm)
        val ang = atan2(-h.nx, -h.nz)
        val faceClip = 1.0f; val d0 = max(0.25f, h.dist)
        val endZ = clipRootZ(nm, cl.dur)
        val zk = floatArrayOf(0f, 0f, faceClip, d0, max(faceClip + 0.5f, endZ), d0 + (max(faceClip + 0.5f, endZ) - faceClip))
        val tin = timeAtZ(nm, faceClip - 0.4f); val dur = cl.dur
        val dyTot = hRel - 1.03f
        val yAdd = { tn: Float -> dyTot * smooth((tn * dur - tin) / max(0.1f, dur - tin)) }
        swimW = null; speed = 0f; info = "saiu da água"
        beginMan(nm, 1f, zk, dur, { st = St.LOCO; snapGround() }, 1f, yAdd, { 0f }, ang, 0.2f)
        return true
    }
}
