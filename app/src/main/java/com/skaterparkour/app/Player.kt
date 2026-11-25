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
    var aim = false                     // modo mirar (strafe): corpo olha para aimYaw
    var aimYaw = 0f
    var emote = 0                       // 1..8 toca emote (borda)
    var die = false                     // morrer (borda)
    var act = false                     // ação (borda): faca/martelo na água
    fun clearEdges() { jump = false; crouch = false; drop = false; emote = 0; die = false; act = false }
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
        const val HANG_FACE = 0.18f
        const val LEDGE_ROOT = 1.24f; const val LEDGE_FACE = 0.30f          // distância da raiz até a face da parede ao pendurar
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

    enum class St { LOCO, AIR, LAND, MAN, HANG, BEAM, SWIM, WALLRUN, PIPE }

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
    var vaultRot = 0

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

    // pegada: 0 beirada (mãos no topo, braços esticados), 1 apoio (ledge)
    private var grip = 0
    private var hAway = false; private var hBackT = 0f; private var hTurnSide = 0
    // corrida na parede / cano
    private var wrBox: Box? = null; private var wrLeft = true; private var wrNx = 0f; private var wrNz = 0f; private var wrY = 0f
    private var wrT = 0f; private var wrStall = 0f; private var wrTotal = 0f
    private var pipeBox: Box? = null; private var pipeY = 0f; private var pipeNx = 0f; private var pipeNz = 0f

    // natação
    private var swimW: Water? = null; private var swimY = 0f; private var swimVy = 0f; private var underwater = false; private var wadeDepth = 0f
    private var swimMode = 0 // 0 superfície, 1 submerso
    private var swimHold = 0f; private var swimIdleT = 0f; private var subT = 0f; private var drown = 0; private var drownT = 0f; private var actOn = false; private var actI = 0
    private var lastYawRate = 0f
    private var swimEnterT = 0f

    // ---- extras de mobilidade
    private var aimOn = false; private var mdir = 0f
    private var mv = 0; private var mvClip = ""; private var mvT = 0f; private var mvDur = 0f; private var mvRate = 1f
    private var mvYaw0 = 0f; private var mvDYaw = 0f; private var mvSp0 = 0f; private var mvSp1 = 0f; private var mvRot = 0.7f
    private var idleT = 0f; private var dead = false; private var deadT = 0f; private var altI = 0
    private var wadeD = 0f
    private val TN = arrayOf("Walk", "Jog", "Run")
    private val AUTO = HashMap<String, JumpDef>()

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
        aimOn = c.aim
        val ww = if (st == St.LOCO || st == St.AIR || st == St.BEAM) w.waterAt(x, z) else null
        if (ww != null) {
            val fl = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            val depth = ww.surface - fl
            wadeD = depth
            if (y < ww.surface - 0.55f && depth > 1.15f) enterWater(ww)
        } else wadeD = 0f
        when (st) {
            St.LOCO -> loco(dt, c)
            St.AIR -> air(dt, c)
            St.LAND -> land(dt, c)
            St.MAN -> maneuver(dt)
            St.HANG -> hang(dt, c)
            St.BEAM -> beam(dt, c)
            St.SWIM -> swim(dt, c)
            St.WALLRUN -> wallRun(dt, c)
            St.PIPE -> pipe(dt, c)
        }
        c.clearEdges()
    }

    // ---------------------------------------------------------------------------------------- locomoção
    private fun tierOf(m: Float) = if (m < 0.45f) 0 else if (m < 0.8f) 1 else 2
    private fun bucket(a: Float): Int = if (a < 0.4f) 0 else if (a < 1.18f) 45 else if (a < 1.96f) 90 else if (a < 2.75f) 135 else 180
    private fun turnName(left: Boolean, a: Int) = if (crouched) "m:Crouch_${if (left) "L" else "R"}_$a" else if (left) "m:Stand_Relaxed_L$a" else "m:Stand_Relaxed_R_$a"
    private fun startName(tr: Int, left: Boolean, a: Int): String =
        if (crouched) (if (a == 0) "m:Crouch_to_CrouchWalk_F" else "m:Crouch_To_CrouchWalk_${if (left) "L" else "R"}${a}_Fwd")
        else if (a == 0) "m:Stand_Relaxed_to_${TN[tr]}_F" else "m:Stand_Relaxed_To_${TN[tr]}_${if (left) "L" else "R"}${a}_Fwd"

    private fun mvBegin(kind: Int, name: String, rate: Float, dYaw: Float, sp1: Float, rotFrac: Float, cut: Float): Boolean {
        if (!cs.has(name)) return false
        mv = kind; mvClip = name; mvT = 0f; mvRate = rate; mvDur = C(name).dur * cut / rate
        mvYaw0 = yaw; mvDYaw = dYaw; mvSp0 = speed; mvSp1 = sp1; mvRot = rotFrac
        setClip(name, false, 0f, 0.15f); info = name.substring(2)
        return true
    }

    /** anda um passo no chão; false se saiu do estado (queda / equilíbrio). */
    private fun moveStep(dt: Float): Boolean {
        val p = floatArrayOf(x + vx * dt, y, z + vz * dt)
        w.pushOut(p, y, R, H, STEP)
        x = p[0]; z = p[2]
        val g = w.groundAt(x, z, 0.12f, y + STEP)
        if (g < y - STEP - 0.01f) { mv = 0; startFall(); return false }
        y = if (g > y) min(g, y + 9f * dt) else if (g < y) max(g, y - 9f * dt) else y
        val bu = w.boxUnder(x, z, y)
        if (bu != null && bu.kind == 2 && abs(y - bu.y1) < 0.1f) { mv = 0; st = St.BEAM; beamBox = bu; setClip("p:Balance_Idle", true, 0f, 0.25f); return false }
        return true
    }

    /** ação em andamento (largada, parada, virada, postura, emote). true = consumiu o quadro. */
    private fun mvStep(dt: Float, c: Ctl, m: Float): Boolean {
        mvT += dt
        val u = clampf(mvT / mvDur, 0f, 1f)
        if ((c.jump || c.crouch) && mv != 4) { mv = 0; return false }
        when (mv) {
            1 -> if (m < 0.12f && mvT > 0.2f) { mv = 0; return false }
            2 -> if (m > 0.3f && mvT > 0.3f) { mv = 0; return false }
            3 -> if (m > 0.35f && mvT > 0.3f) { mv = 0; return false }
            5 -> if ((m > 0.3f || c.crouch) && mvT > 0.2f) { mv = 0; return false }
        }
        yaw = wrapA(mvYaw0 + mvDYaw * smooth(clampf(u / mvRot, 0f, 1f)))
        speed = when (mv) {
            1 -> mvSp0 + (mvSp1 - mvSp0) * smooth(clampf((u - 0.15f) / 0.6f, 0f, 1f))
            2 -> mvSp0 * (1f - smooth(clampf(u / 0.75f, 0f, 1f)))
            else -> 0f
        }
        mdir = yaw; vx = sin(yaw) * speed; vz = cos(yaw) * speed
        if (!moveStep(dt)) return true
        t = mvT * mvRate
        if (u >= 1f) { mv = 0; if (speed < 0.3f) speed = 0f; return false }
        return true
    }

    private fun startEmote(i: Int) {
        val nm = if (i <= 4) "m:Stand_Relaxed_Conv_v$i" else "m:Stand_Relaxed_Fgt_v${i - 4}"
        mvBegin(5, nm, 1f, 0f, 0f, 1f, 1f)
    }

    private fun startDeath() {
        val nm = arrayOf("m:Stand_Relaxed_Death_F", "m:Stand_Relaxed_Death_B", "m:Stand_Relaxed_Death_L", "m:Stand_Relaxed_Death_R")[(altI++) % 4]
        mv = 0; info = "morreu"; speed = 0f; vx = 0f; vz = 0f
        beginMan(nm, 1f, null, C(nm).dur, { st = St.LOCO; dead = true; deadT = 0f; snapGround() }, fade = 0.15f)
    }

    private fun loco(dt: Float, c: Ctl) {
        val m = min(1f, hypot(c.mx, c.mz))
        if (dead) { deadT += dt; if (deadT > 3f) { dead = false; speed = 0f; crouched = false; setClip("m:Stand_Relaxed_Idle", true, 0f, 0.6f) }; return }
        if (c.die) { startDeath(); return }
        if (mv != 0 && mvStep(dt, c, m)) return
        if (c.emote > 0 && speed < 0.2f && !crouched && m < 0.12f) { startEmote(c.emote); return }
        if (c.crouch) {
            if (speed > 3.3f && !crouched && !aimOn) { startSlide(); return }
            if (speed < 0.3f) {
                crouched = !crouched
                if (mvBegin(4, if (crouched) "m:Stand_Relaxed_to_Crouch" else "m:Crouch_to_Stand_Relaxed", 2.0f, 0f, 0f, 1f, 1f)) return
            } else crouched = !crouched
        }
        val spdTier = if (crouched) SPEED_CROUCH else if (m < 0.45f) SPEED_WALK else if (m < 0.8f) SPEED_JOG else SPEED_RUN
        // ---- virar / largar / parar (modo livre)
        if (!aimOn) {
            mdir = yaw
            if (m > 0.12f && speed < 0.3f) {
                val diff = wrapA(atan2(c.mx, c.mz) - yaw); val a = bucket(abs(diff))
                if (m < 0.3f) { if (a > 0 && mvBegin(3, turnName(diff > 0f, a), 2.2f, diff, 0f, 0.75f, 0.7f)) return }
                else if (mvBegin(1, startName(tierOf(m), diff > 0f, a), 1.5f, diff, spdTier, 0.45f, 0.8f)) return
            }
            if (m < 0.12f && speed > 1.2f) {
                val ph = if (clip.startsWith("m:") && cs.has(clip)) (t / C(clip).dur) % 1f else 0f
                val sfx = if (ph < 0.5f) "_LU" else "_RU"
                val base = if (crouched) "m:CrouchWalk_F_to_Crouch" else "m:" + TN[if (speed < 2.2f) 0 else if (speed < 3.9f) 1 else 2] + "_F_to_Stand_Relaxed"
                if (mvBegin(2, base + sfx, 1.8f, 0f, 0f, 1f, 0.6f)) return
            }
        } else {
            val dy = wrapA(c.aimYaw - yaw)
            if (speed < 0.3f && m < 0.12f && abs(dy) > 0.9f) { val a = bucket(abs(dy)); if (mvBegin(3, turnName(dy > 0f, a), 2.2f, dy, 0f, 0.75f, 0.7f)) return }
            yaw = wrapA(yaw + clampf(dy, -12f * dt, 12f * dt))
            if (m > 0.12f) mdir = wrapA(mdir + clampf(wrapA(atan2(c.mx, c.mz) - mdir), -14f * dt, 14f * dt))
        }
        val tgt = if (m > 0.12f) spdTier else 0f
        var diff = 0f
        if (m > 0.12f) {
            val ta = atan2(c.mx, c.mz)
            if (!aimOn) {
                diff = wrapA(ta - yaw)
                val rate = if (speed < 0.5f) 9f else 13f
                yaw = wrapA(yaw + clampf(diff, -rate * dt, rate * dt)); mdir = yaw
            } else diff = wrapA(ta - mdir)
        }
        val tg = if (!aimOn && abs(diff) > 1.7f && speed > 2.0f) min(tgt, 1.0f) else tgt
        val k = max(0.25f, cos(min(abs(diff), 1.5f)))
        val goal = tg * (if (abs(diff) > 0.9f) k else 1f) * (if (wadeD > 0.3f) 0.7f else 1f)
        val acc = if (goal > speed) 16f else 26f
        speed += clampf(goal - speed, -acc * dt, acc * dt)
        if (speed < 0.02f && tg == 0f) speed = 0f
        vx = sin(mdir) * speed; vz = cos(mdir) * speed

        if (c.jump) {
            val y0 = yaw; val rel0 = wrapA(mdir - yaw)
            if (aimOn && speed > 0.5f) yaw = mdir
            if (contextJump(c)) return
            yaw = y0; startJump(rel0); return
        }
        if (!moveStep(dt)) return
        locoClip(dt, m)
    }

    private fun sector(rel: Float): String {
        val a = abs(rel); val l = rel > 0f
        return if (a < 0.4f) "F" else if (a < 1.2f) (if (l) "FL" else "FR") else if (a < 1.95f) (if (l) "L" else "R") else if (a < 2.75f) (if (l) "BL" else "BR") else "B"
    }

    private fun locoClip(dt: Float, m: Float) {
        var want: String; val clipSpd: Float
        val tr = if (speed < 2.0f) 0 else if (speed < 3.7f) 1 else 2
        val sec = if (aimOn && speed > 0.1f) sector(wrapA(mdir - yaw)) else "F"
        if (speed < 0.1f) {
            idleT += dt
            if (crouched) { want = if (idleT > 6f && idleT < 11f) "m:Crouch_Idle_V2" else "m:Crouch_Idle"; if (idleT > 11f) idleT = 0f }
            else { want = if (idleT > 8f && idleT < 14f) "m:Stand_Relaxed_Idle_v2" else "m:Stand_Relaxed_Idle"; if (idleT > 14f) idleT = 0f }
            clipSpd = 0f
        } else {
            idleT = 0f
            val pre = if (crouched) "CrouchWalk" else TN[tr]
            val n = when (sec) {
                "F" -> "${pre}_F"; "FL" -> "${pre}_FL_Loop"; "FR" -> "${pre}_FR_Loop"; "L" -> "${pre}_L"; "R" -> "${pre}_R"
                "BL" -> "${pre}_BL_BkPd_Loop"; "BR" -> "${pre}_BR_BkPd_Loop"; else -> "${pre}_B"
            }
            want = "m:$n"
            if (!cs.has(want)) want = "m:" + (if (crouched) "CrouchWalk" else "Jog") + "_" + (if (sec.startsWith("B")) (if (sec == "B") "B" else sec + "_BkPd_Loop") else sec.let { if (it == "FL" || it == "FR") it + "_Loop" else it })
            if (!cs.has(want)) want = if (crouched) "m:CrouchWalk_F" else "m:Jog_F"
            clipSpd = if (crouched) 1.25f else if (tr == 0) SPEED_WALK else if (tr == 1) SPEED_JOG else SPEED_RUN
            if (wadeD > 0.3f && !crouched) want = if (tr == 2) "s:Run_forward" else "s:walk_forward"
        }
        if (want != clip) {
            val oldD = if (cs.has(clip)) C(clip).dur else 1f
            val moving = clipSpd > 0f && clip.length > 2 && (clip.contains("Walk") || clip.contains("Jog") || clip.contains("Run") || clip.contains("walk") )
            val ph = if (moving) (t / oldD) % 1f else 0f
            setClip(want, true, ph * C(want).dur, if (clipSpd == 0f) 0.3f else 0.22f)
        }
        val rate = if (clipSpd > 0f) clampf(speed / clipSpd, 0.6f, 1.35f) else 1f
        t += dt * rate
    }

    // ---------------------------------------------------------------------------------------- pulos
    private fun autoJump(name: String, h: Float): JumpDef? {
        AUTO[name]?.let { return it }
        if (!cs.has(name)) return null
        val c = C(name)
        val base = (c.pel[1] + c.pel[4] + c.pel[7]) / 3f
        var up = -1; var dn = -1
        for (i in 0 until c.nf) if (c.pel[i * 3 + 1] > base + 0.04f) { if (up < 0) up = i; dn = i }
        if (up < 0 || dn <= up + 2) return null
        val d = JumpDef(name, max(0f, up / 30f - 0.2f), up / 30f, min(dn / 30f, c.dur), min(c.dur, dn / 30f + 0.45f), h, 1f)
        AUTO[name] = d; return d
    }

    private fun startJump(rel: Float = 0f) {
        val key = if (speed < 0.5f) "stand" else if (speed < 2.0f) "walk" else if (speed < 3.7f) "jog" else "run"
        var d = JUMPS[key]!!
        if (aimOn && speed < 0.3f && !crouched) { autoJump("m:Stand_Relaxed_Hop", 0.3f)?.let { d = it } }
        else if (aimOn && speed > 0.5f) {
            val sc = sector(rel); val pre = if (key == "walk") "Walk" else if (key == "jog") "Jog" else "Run"
            val side = if (sc == "L" || sc == "R") sc else if (sc.startsWith("B")) "B" else "F"
            if (side != "F") {
                val cand = arrayOf("m:${pre}_${side}_Jump", "m:${pre}_${side}_Jump_" + (if ((altI++) % 2 == 0) "LU" else "RU"), "m:Walk_${side}_Jump")
                for (n in cand) { val a = autoJump(n, 0.6f); if (a != null) { d = a; break } }
            }
        }
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
            val ta = atan2(c.mx, c.mz); if (aimOn) yaw = wrapA(yaw + clampf(wrapA(c.aimYaw - yaw), -8f * dt, 8f * dt)) else yaw += clampf(wrapA(ta - yaw), -5f * dt, 5f * dt)
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
        if (c.crouchHeld && w.waterAt(x, z) != null) diveIn = true
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
        run { val wa = w.waterAt(x, z); if (wa != null && y > wa.surface + 0.4f && vy < 0f && d == null || (wa != null && y > wa.surface + 0.4f && vy < -1f && airT > (jNom + 0.05f))) { if (clip != "s:Jump_A_Air_Loop") setClip("s:Jump_A_Air_Loop", true, 0f, 0.2f); t += dt } }
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
        if (roll) { clipN = arrayOf("p:Landing_Roll_A_L", "p:Landing_Roll_A_R", "p:Landing_Roll_B_L", "p:Landing_Roll_B_R")[(altI++) % 4]; dur = C(clipN).dur }
        else if (fall > 3.0f) { clipN = "p:Landing_Down"; dur = C(clipN).dur }
        else if (fall > 1.2f && sp > 2.5f) { clipN = arrayOf("p:Landing_Front_L", "p:Landing_Front_R")[(altI++) % 2]; dur = C(clipN).dur }
        else if (fall > 1.2f) { clipN = "p:Landing_Down_Light"; dur = C(clipN).dur }
        else { clipN = if (sp > 2.5f) arrayOf("p:LandingGround_L", "p:LandingGround_R")[(altI++) % 2] else "p:Landing_Down_Light"; dur = min(C(clipN).dur, 0.5f) }
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
        if (tryWallRunH()) return true
        if (tryPipeGrab(false)) return true
        if (tryLedgeFromGround()) return true
        val h = w.probe(x, z, dx, dz, 2.4f, y + 0.5f, y + 1.1f)
        if (h == null) { return tryGapJump() }
        val hRel = h.top - y
        val ang = acos(clampf(-(dx * h.nx + dz * h.nz), -1f, 1f))
        if (ang > 1.0f) return false
        if (hRel <= 1.55f) {
            if (h.dist > 2.0f) return false
            if (h.depth <= 2.3f && (h.farGround > y - 1.3f)) { startVault(h, hRel); return true }
            if (h.depth >= 1.5f && hRel <= 1.45f) { startClimbOn(h, hRel); return true }
            return false
        }
        if (hRel <= 2.65f) {
            if (h.dist > 1.9f) return false
            if (speed > 3.5f && tryTicTac(h, hRel)) return true
            startHangAttach(h, hRel); return true
        }
        if (hRel <= 6.2f && speed > 3.5f && h.dist <= 3.2f && startWallRunUp(h, hRel)) return true
        return false
    }

    private fun tryAirGrab(): Boolean {
        if (vy > 4.5f) return false
        if (tryPipeGrab(true)) return true
        if (tryLedgeInAir()) return true
        val h = w.probe(x, z, fwdx(), fwdz(), 0.95f, y + 1.2f, y + 3f) ?: return false
        val dtop = h.top - y
        if (dtop < 1.35f || dtop > 2.35f) return false
        if (h.dist > 0.8f) return false
        val ang = acos(clampf(-(fwdx() * h.nx + fwdz() * h.nz), -1f, 1f))
        if (ang > 0.9f) return false
        enterHang(h.box, h.nx, h.nz, h.top, if (abs(h.nx) > 0.5f) z else x)
        return true
    }

    // ---- apoios e canos: busca
    private class Face(val b: Box, val nx: Float, val nz: Float, val dist: Float, val s: Float)
    /** Face do bloco b voltada para o jogador na direção em que ele olha. */
    private fun faceOf(b: Box, maxD: Float): Face? {
        val dx = fwdx(); val dz = fwdz()
        var best: Face? = null
        if (abs(dx) > 0.2f) {
            val nx = if (dx > 0) -1f else 1f; val fc = if (dx > 0) b.x0 else b.x1
            val d = (fc - x) / dx
            if (d >= -0.05f && d <= maxD) { val zz = z + dz * d; if (zz >= b.z0 - 0.1f && zz <= b.z1 + 0.1f) best = Face(b, nx, 0f, max(0f, d), zz) }
        }
        if (abs(dz) > 0.2f) {
            val nz = if (dz > 0) -1f else 1f; val fc = if (dz > 0) b.z0 else b.z1
            val d = (fc - z) / dz
            if (d >= -0.05f && d <= maxD && (best == null || d < best.dist)) { val xx = x + dx * d; if (xx >= b.x0 - 0.1f && xx <= b.x1 + 0.1f) best = Face(b, 0f, nz, max(0f, d), xx) }
        }
        return best
    }

    private fun findLedgeAhead(maxD: Float, dyMin: Float, dyMax: Float): Face? {
        var best: Face? = null
        for (b in w.special) {
            if (b.kind != 4) continue
            val dy = b.y1 - y; if (dy < dyMin || dy > dyMax) continue
            val f = faceOf(b, maxD) ?: continue
            val ang = acos(clampf(-(fwdx() * f.nx + fwdz() * f.nz), -1f, 1f)); if (ang > 0.8f) continue
            if (best == null || f.dist < best.dist) best = f
        }
        return best
    }

    private fun tryLedgeFromGround(): Boolean {
        val f = findLedgeAhead(1.5f, 1.1f, 2.4f) ?: return false
        val b = f.b; val top = b.y1
        val thick = if (abs(f.nx) > 0.5f) b.x1 - b.x0 else b.z1 - b.z0
        val dEnd = f.dist + thick - LEDGE_FACE
        val ang = atan2(-f.nx, -f.nz)
        val nx = f.nx; val nz = f.nz; val s2 = f.s
        val yEnd = top - LEDGE_ROOT + 0.35f
        info = "agarrar apoio"; speed = 0f; vx = 0f; vz = 0f
        val y0 = y
        beginMan("p:Ledge_Attach", 1f, floatArrayOf(0f, 0f, 0.18f, max(0.05f, dEnd), 3f, max(0.05f, dEnd) + 2.82f), C("p:Ledge_Attach").dur,
            { enterHang(b, nx, nz, top, s2, 1) }, 1f, { tn -> (yEnd - y0) * smooth(tn * 2f) }, { 0f }, ang, 0.2f)
        return true
    }

    private fun tryLedgeInAir(): Boolean {
        val f = findLedgeAhead(0.9f, 0.95f, 2.3f) ?: return false
        enterHang(f.b, f.nx, f.nz, f.b.y1, f.s, 1); return true
    }

    private fun findPipe(maxD: Float): Triple<Box, Float, Float>? {
        var best: Triple<Box, Float, Float>? = null; var bd = 1e9f
        for (b in w.special) {
            if (b.kind != 3) continue
            if (y + 1.4f > b.y1 + 0.2f || y + 1.8f < b.y0) continue
            val cx = (b.x0 + b.x1) / 2; val cz = (b.z0 + b.z1) / 2
            val ddx = cx - x; val ddz = cz - z; val d = hypot(ddx, ddz)
            if (d > maxD) continue
            val al = (ddx * fwdx() + ddz * fwdz()) / max(d, 1e-3f); if (al < 0.6f) continue
            val nx = if (abs(ddx) > abs(ddz)) (if (ddx > 0) -1f else 1f) else 0f
            val nz = if (abs(ddx) > abs(ddz)) 0f else (if (ddz > 0) -1f else 1f)
            if (d < bd) { bd = d; best = Triple(b, nx, nz) }
        }
        return best
    }

    private fun pipePlace() {
        val b = pipeBox!!
        val cx = (b.x0 + b.x1) / 2; val cz = (b.z0 + b.z1) / 2
        x = cx + pipeNx * 0.38f; z = cz + pipeNz * 0.38f; y = pipeY
    }

    private fun enterPipe(b: Box, nx: Float, nz: Float, yy: Float) {
        pipeBox = b; pipeNx = nx; pipeNz = nz; pipeY = yy; st = St.PIPE; man = null; vx = 0f; vz = 0f; vy = 0f; speed = 0f; jd = null; jPre = false
        yaw = atan2(-nx, -nz); pipePlace(); setClip("p:Wall_Pipe_Idle", true, 0f, 0.15f); info = "no cano"
    }

    private fun tryPipeGrab(air: Boolean): Boolean {
        val f = findPipe(if (air) 0.9f else 2.2f) ?: return false
        val (b, nx, nz) = f
        if (air) { enterPipe(b, nx, nz, max(b.y0 + 0.1f, y)); return true }
        val cx = (b.x0 + b.x1) / 2; val cz = (b.z0 + b.z1) / 2
        val tx = cx + nx * 0.38f; val tz = cz + nz * 0.38f
        val dEnd = hypot(tx - x, tz - z)
        val ang = atan2(-nx, -nz)
        val yEnd = y + 0.9f; val y0 = y
        info = "agarrar cano"; speed = 0f; vx = 0f; vz = 0f
        beginMan("p:Wall_Pipe_Attach", 1f, floatArrayOf(0f, 0f, 0.2f, dEnd, 3f, dEnd + 2.8f), C("p:Wall_Pipe_Attach").dur,
            { enterPipe(b, nx, nz, yEnd) }, 1f, { tn -> (yEnd - y0 + 0.35f) * smooth(tn * 2f) }, { 0f }, ang, 0.2f)
        return true
    }

    private fun pipe(dt: Float, c: Ctl) {
        val b = pipeBox ?: run { st = St.LOCO; return }
        val mf = c.mx * sin(yaw) + c.mz * cos(yaw)
        val ml = c.mx * cos(yaw) - c.mz * sin(yaw)
        val topY = b.y1
        val behindX = x - pipeNx * 0.9f; val behindZ = z - pipeNz * 0.9f
        val gTop = w.groundAt(behindX, behindZ, 0.1f, topY + 3f)
        var want = "p:Wall_Pipe_Idle"
        if (mf > 0.4f) {
            if (gTop > -500f && gTop - pipeY < 1.95f && gTop - pipeY > 0.9f) { pipeClimbOver(gTop); return }
            if (pipeY + 1.45f < topY) { pipeY += 1.3f * dt; want = "p:Wall_Pipe_Move_Up" }
        } else if (mf < -0.4f) {
            val gr = w.groundAt(x + pipeNx * 0.2f, z + pipeNz * 0.2f, 0.1f, pipeY + 0.3f)
            if (pipeY > gr + 0.05f) { pipeY = max(gr, pipeY - 1.3f * dt); want = "p:Wall_Pipe_Move_Down" }
            else { st = St.LOCO; y = gr; speed = 0f; setClip("m:Stand_Relaxed_Idle", true, 0f, 0.2f); info = "desceu do cano"; return }
        }
        pipePlace()
        if (clip != want) setClip(want, true, 0f, 0.12f)
        t += dt
        if (c.jump) { pipeJump(ml, mf, gTop); return }
        if (c.crouch || c.drop) {
            info = "soltou do cano"; val nx = pipeNx; val nz = pipeNz; pipeBox = null
            beginMan("p:Wall_Pipe_Fall_Down", 1f, null, C("p:Wall_Pipe_Fall_Down").dur, { launchAir(nx * 1.0f, nz * 1.0f, -1f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
        }
    }

    private fun pipeClimbOver(gTop: Float) {
        val b = pipeBox!!; pipeBox = null
        val dy = gTop - pipeY
        info = "subir pelo cano"
        beginMan("p:Wall_Pipe_Climbe_Up_2Hand", 1f, null, C("p:Wall_Pipe_Climbe_Up_2Hand").dur, { st = St.LOCO; y = gTop; snapGround(); crouched = false },
            1f, { tn -> (dy - 1.46f) * smooth(tn) }, { 0f }, yaw, 0.01f, 0.15f)
    }

    private fun pipeJump(ml: Float, mf: Float, gTop: Float) {
        val b = pipeBox!!
        val tcur = if (abs(pipeNx) > 0.5f) (b.z0 + b.z1) / 2 else (b.x0 + b.x1) / 2
        if (abs(ml) > 0.5f) {
            val left = ml > 0f
            val axisPos = if (abs(pipeNx) > 0.5f) -sin(yaw) else cos(yaw)
            val dirW = (if (left) 1f else -1f) * (if (axisPos >= 0) 1f else -1f)
            var tgt: Box? = null; var bd = 1e9f
            for (o in w.special) {
                if (o.kind != 3 || o === b) continue
                val oc = if (abs(pipeNx) > 0.5f) (o.z0 + o.z1) / 2 else (o.x0 + o.x1) / 2
                val d = (oc - tcur) * dirW; if (d < 1.0f || d > 2.4f) continue
                val oface = if (abs(pipeNx) > 0.5f) (o.x0 + o.x1) / 2 else (o.z0 + o.z1) / 2
                val cface = if (abs(pipeNx) > 0.5f) (b.x0 + b.x1) / 2 else (b.z0 + b.z1) / 2
                if (abs(oface - cface) > 0.2f || pipeY + 1.4f > o.y1 + 0.2f) continue
                if (d < bd) { bd = d; tgt = o }
            }
            val o = tgt ?: return
            val dist = bd; val nm = "p:Wall_Pipe_Jump_" + (if (left) "L" else "R")
            val cdx = 1.70f * (if (left) 1f else -1f); val want = (if (left) 1f else -1f) * dist
            val nx = pipeNx; val nz = pipeNz; val yy = pipeY; pipeBox = null
            info = "pulo entre canos"
            beginMan(nm, 1f, null, C(nm).dur, { enterPipe(o, nx, nz, yy) }, 1f, { 0f }, { tn -> (want - cdx) * smooth(tn) }, yaw, 0.01f, 0.1f)
            return
        }
        if (mf < -0.5f) {
            val gr = w.groundAt(x + pipeNx * 0.2f, z + pipeNz * 0.2f, 0.1f, pipeY + 0.3f)
            if (pipeY - 1.19f < gr) return
            val nx = pipeNx; val nz = pipeNz; val yy = pipeY - 1.19f; val bb = b; pipeBox = null
            info = "pulo para baixo"
            beginMan("p:Wall_Pipe_Jump_Dwn", 1f, null, C("p:Wall_Pipe_Jump_Dwn").dur, { enterPipe(bb, nx, nz, yy) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
            return
        }
        if (gTop > -500f && gTop - pipeY < 1.95f) { pipeClimbOver(gTop); return }
        if (pipeY + 1.42f + 1.4f > b.y1 + 0.2f) return
        val nx = pipeNx; val nz = pipeNz; val yy = pipeY + 1.42f; val bb = b; pipeBox = null
        info = "pulo para cima"
        beginMan("p:Wall_Pipe_Jump_Up", 1f, null, C("p:Wall_Pipe_Jump_Up").dur, { enterPipe(bb, nx, nz, yy) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
    }

    // ---- correr na parede (para cima)
    private fun startWallRunUp(h: Hit, hRel: Float): Boolean {
        val two = hRel > 4.5f
        val rise = hRel - HANG_ROOT
        val endY = if (two) 4.03f else 2.54f
        val ys = rise / endY
        if (ys < 0.6f || ys > 1.5f) return false
        val left = (vaultRot++ % 2 == 0)
        val nm = "p:WallRun_" + (if (left) "L" else "R") + "_" + (if (two) "Two" else "One")
        val ang = atan2(-h.nx, -h.nz); val d0 = max(0.3f, h.dist)
        val zk = floatArrayOf(0f, 0f, 1.15f, d0 - 0.15f, 4f, d0 - 0.15f + 2.85f)
        val box = h.box; val nx = h.nx; val nz = h.nz; val top = h.top
        speed = 0f; vx = 0f; vz = 0f; info = "correr na parede"
        beginMan(nm, 1f, zk, C(nm).dur, { enterHang(box, nx, nz, top, if (abs(nx) > 0.5f) z else x) }, ys, { 0f }, { 0f }, ang, 0.2f)
        return true
    }

    // ---- correr na parede (horizontal)
    private fun tryWallRunH(): Boolean {
        if (speed < 3.6f) return false
        val fx = fwdx(); val fz = fwdz()
        for (sd in intArrayOf(1, -1)) {
            val sx = cos(yaw) * sd; val sz = -sin(yaw) * sd
            val h = w.probe(x, z, sx, sz, 1.2f, y + 3.0f, y + 0.4f) ?: continue
            if (abs(h.nx * fx + h.nz * fz) > 0.3f) continue
            var len = 0f; var d = 0f
            while (d < 16f) {
                val p = w.probe(x + fx * d, z + fz * d, sx, sz, 1.6f, y + 3.0f, y + 0.4f)
                if (p == null || p.box !== h.box) break
                len = d; d += 0.5f
            }
            if (len < 4.5f) continue
            startWallRunH(sd == 1, h, len); return true
        }
        return false
    }

    private fun startWallRunH(left: Boolean, h: Hit, len: Float) {
        wrBox = h.box; wrLeft = left; wrNx = h.nx; wrNz = h.nz
        val side = if (left) 1f else -1f; val tag = if (left) "L" else "R"
        val dw = h.dist
        val xa = side * (dw - 0.77f)
        info = "correr na parede"
        if (len >= 10.5f && speed > 4.5f && (vaultRot++ % 2 == 0)) {
            val nm = "p:WallRun_Horizontal_${tag}_Full"; val cl = C(nm)
            beginMan(nm, 1f, null, cl.dur, { st = St.LOCO; speed = SPEED_RUN; snapGround() }, 1f, { 0f },
                { tn -> xa * smooth(tn * 4f) * (1f - smooth((tn - 0.72f) * 4f)) }, yaw, 0.01f, 0.1f)
            return
        }
        val nm = "p:WallRun_Horizontal_${tag}_Start"; val cl = C(nm)
        speed = 0f
        beginMan(nm, 1f, null, cl.dur, { enterWallRun() }, 1f, { 0f }, { tn -> xa * smooth(tn) }, yaw, 0.01f, 0.1f)
    }

    private fun enterWallRun() {
        st = St.WALLRUN; wrY = y; wrT = 0f; wrStall = 0f; man = null
        setClip("p:WallRun_Horizontal_" + (if (wrLeft) "L" else "R") + "_Cycle", true, 0f, 0.05f)
    }

    private fun wallRun(dt: Float, c: Ctl) {
        val b = wrBox ?: run { st = St.LOCO; return }
        val tag = if (wrLeft) "L" else "R"
        wrT += dt
        val m = hypot(c.mx, c.mz)
        if (m < 0.2f) wrStall += dt else wrStall = 0f
        val stalled = wrStall > 0.2f
        val want = "p:WallRun_Horizontal_${tag}_Cycle" + (if (stalled) "_IP" else "")
        if (clip != want) setClip(want, true, t, 0.1f)
        t += dt
        if (!stalled) { x += fwdx() * 6.4f * dt; z += fwdz() * 6.4f * dt }
        if (abs(wrNx) > 0.5f) x = (if (wrNx < 0) b.x0 else b.x1) + wrNx * 0.45f else z = (if (wrNz < 0) b.z0 else b.z1) + wrNz * 0.45f
        y = wrY
        val ax = x + fwdx() * 0.9f; val az = z + fwdz() * 0.9f
        val inside = if (abs(wrNx) > 0.5f) (az > b.z0 + 0.2f && az < b.z1 - 0.2f) else (ax > b.x0 + 0.2f && ax < b.x1 - 0.2f)
        if (c.jump) { wrJump(c); return }
        if (!inside || wrT > 4f || wrStall > 1.4f || c.crouch || c.drop) wrFinish()
    }

    private fun wrFinish() {
        val tag = if (wrLeft) "L" else "R"; val nm = "p:WallRun_Horizontal_${tag}_Finish"
        info = "fim da corrida na parede"
        beginMan(nm, 1f, null, C(nm).dur, { st = St.LOCO; speed = SPEED_RUN * 0.8f; snapGround() }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
    }

    private fun wrJump(c: Ctl) {
        val tag = if (wrLeft) "L" else "R"
        val away = c.mx * wrNx + c.mz * wrNz
        if (away > 0.5f) {
            val nm = "p:WallRun_Horizontal_${tag}_Jump_90_" + (if (wrLeft) "R" else "L"); val cl = C(nm)
            info = "pulo da parede (90°)"
            val ax = wrNx; val az = wrNz; val dy = if (wrLeft) -PI.toFloat() / 2f else PI.toFloat() / 2f
            val fx = fwdx(); val fz = fwdz()
            beginMan(nm, 1f, null, cl.dur * 0.55f, { yaw = wrapA(yaw + dy); launchAir(ax * 3.0f + fx * 2f, az * 3.0f + fz * 2f, 1.5f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
        } else {
            val nm = "p:WallRun_Horizontal_${tag}_Jump_F"; val cl = C(nm)
            info = "pulo da parede"
            val fx = fwdx(); val fz = fwdz()
            beginMan(nm, 1f, null, cl.dur * 0.5f, { launchAir(fx * 5.5f, fz * 5.5f, 1.0f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
        }
    }

    // ---- tic-tac (chute na parede)
    private fun tryTicTac(h: Hit, hRel: Float): Boolean {
        if (hRel < 1.7f || hRel > 2.65f) return false
        val ang = atan2(-h.nx, -h.nz); val d0 = max(0.3f, h.dist)
        val rotL = ((vaultRot++) % 2 == 0)
        val fx = sin(ang); val fz = cos(ang); val lx = cos(ang); val lz = -sin(ang)
        val depth = h.depth
        fun late(tn: Float) = smooth((tn - 0.55f) / 0.4f)
        fun bump(tn: Float) = smooth((tn - 0.2f) / 0.2f) * (1f - smooth((tn - 0.7f) / 0.25f))
        val bd = hRel - 2.22f
        // 1) de parede para parede (lateral)
        if (depth <= 1.2f) {
            for (sd in intArrayOf(if (rotL) 1 else -1, if (rotL) -1 else 1)) {
                val lat = -1.69f * sd   // L (sd=1) vai para a direita
                val fwdTo = d0 + depth + 0.5f
                val wx = x + lx * lat + fx * fwdTo; val wz = z + lz * lat + fz * fwdTo
                val g = w.groundAt(wx, wz, 0.2f, y + 2.0f)
                if (g < -500f) continue
                val rel = g - y
                if (rel < 0.8f || rel > 1.8f) continue
                val nm = "p:Tic-Tac_" + (if (sd == 1) "L" else "R") + "_Jump_Side"
                val dyEnd = rel - 1.28f
                val zk = floatArrayOf(0f, 0f, 1.15f, d0 - 0.15f, 2.1f, fwdTo, 5f, fwdTo + 2.9f)
                info = "tic-tac lateral"; speed = 0f; vx = 0f; vz = 0f
                val yawEnd = if (sd == 1) PI.toFloat() / 2f else -PI.toFloat() / 2f
                beginMan(nm, 1f, zk, C(nm).dur, { endFacing(yawEnd) }, 1f, { tn -> bd * bump(tn) + dyEnd * late(tn) }, { 0f }, ang, 0.2f)
                return true
            }
        }
        val tag = if (rotL) "L" else "R"
        if (depth >= 2.0f) {
            val run = speed > 4.2f
            val nm = "p:Tic-Tac_${tag}_Jump_On" + (if (run) "_Run" else ""); val endZ = if (run) 3.92f else 3.40f
            val landD = d0 + min(depth * 0.5f, 1.5f)
            val dyEnd = hRel - 2.22f
            val zk = floatArrayOf(0f, 0f, 1.15f, d0 - 0.15f, endZ, landD, endZ + 3f, landD + 3f)
            info = "tic-tac para plataforma"; speed = 0f; vx = 0f; vz = 0f
            beginMan(nm, 1f, zk, C(nm).dur, { st = St.LOCO; speed = SPEED_JOG; snapGround() }, 1f, { tn -> dyEnd * smooth((tn - 0.25f) / 0.45f) }, { 0f }, ang, 0.2f)
            return true
        }
        // muro fino: passa por cima e cai do outro lado
        if (h.farGround < -500f) return false
        val rel = h.farGround - y
        if (rel > 1.6f || rel < -3.5f) return false
        val opts = arrayOf("Tic-Tac_${tag}_Speed" to 0.93f, "Tic-Tac_${tag}_Revers" to 0.84f, "Tic-Tac_${tag}_Jump" to 1.28f, "Wall_${tag}_Turn" to 0.98f)
        val pick = opts[(vaultRot / 2) % opts.size]
        val nm = "p:" + pick.first; val cl = C(nm); val turn = pick.first.startsWith("Wall_")
        val endZ = clipRootZ(nm, cl.dur)
        val zk = if (turn) floatArrayOf(0f, 0f, 1.15f, d0 - 0.15f, 1.46f, d0 + 0.1f, 2.11f, d0 + depth + 0.2f, 2.55f, d0 + depth + 0.65f, 6f, d0 + depth + 4.1f)
        else floatArrayOf(0f, 0f, 1.15f, d0 - 0.15f, 2.15f, d0 + 0.2f, 3.4f, d0 + depth + 0.25f, endZ, d0 + depth + 0.25f + (endZ - 3.4f), endZ + 3f, d0 + depth + 3.25f + (endZ - 3.4f))
        val dyEnd = rel - pick.second
        info = "tic-tac " + pick.first; speed = 0f; vx = 0f; vz = 0f
        if (turn) beginMan(nm, 1f, zk, cl.dur, { endFacing(PI.toFloat()) }, 1f, { tn -> bd * bump(tn) + dyEnd * late(tn) }, { 0f }, ang, 0.2f)
        else beginMan(nm, 1f, zk, cl.dur, { st = St.LOCO; speed = SPEED_JOG; snapGround() }, 1f, { tn -> bd * bump(tn) + dyEnd * late(tn) }, { 0f }, ang, 0.2f)
        return true
    }

    // ---- pulos longos e de precisão sobre vãos
    private fun tryGapJump(): Boolean {
        if (st != St.LOCO) return false
        val dx = fwdx(); val dz = fwdz()
        var edge = -1f; var d = 0.2f
        while (d <= 1.7f) { if (w.groundAt(x + dx * d, z + dz * d, 0.15f, y + 0.5f) < y - 0.7f) { edge = d; break }; d += 0.1f }
        if (edge < 0f) return false
        var land = -1f; d = edge + 0.3f
        while (d <= 4.8f) { val g = w.groundAt(x + dx * d, z + dz * d, 0.15f, y + 0.6f); if (g > y - 0.4f) { land = d; break }; d += 0.1f }
        if (land < 0f) return false
        val T = land + 0.45f
        if (T < 2.9f || T > 4.5f) return false
        val g = w.groundAt(x + dx * T, z + dz * T, 0.15f, y + 0.6f)
        if (g < -500f) return false
        val left = (vaultRot++ % 2 == 0); val tag = if (left) "L" else "R"
        val nm: String; val fin: String
        if (T < 3.5f) { nm = "p:Jump_Front_${tag}_Start"; fin = "p:Jump_Finish" } else { nm = "p:AccurJump_Start"; fin = "p:AccurJump_Finish" }
        val cl = C(nm); val endZ = clipRootZ(nm, cl.dur); val dy = g - y
        info = if (T < 3.5f) "pulo longo" else "pulo de precisão"
        speed = 0f; vx = 0f; vz = 0f
        val zk = floatArrayOf(0f, 0f, endZ, T, endZ + 3f, T + 3f)
        beginMan(nm, 1f, zk, cl.dur, {
            beginMan(fin, 1f, null, C(fin).dur, { st = St.LOCO; speed = SPEED_WALK; snapGround() }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.05f)
        }, 1f, { tn -> dy * smooth(tn) }, { 0f }, yaw, 0.01f, 0.1f)
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
        val sd = if ((altI++) % 2 == 0) "L" else "R"
        val s = "p:Slide_${sd}_Start"
        beginMan(s, 1f, null, C(s).dur, {
            beginMan("p:Slide_${sd}_Finish", 1f, null, C("p:Slide_${sd}_Finish").dur, { st = St.LOCO; speed = 2f; crouched = false; snapGround() }, fade = 0.05f)
        }, fade = 0.15f)
    }

    // ---------------------------------------------------------------------------------------- pendurado
    private fun enterHang(b: Box, nx: Float, nz: Float, top: Float, s: Float, g: Int = 0) {
        grip = g; hAway = false; hBackT = 0f
        hBox = b; hNx = nx; hNz = nz; hTop = top; hAxisX = abs(nz) > 0.5f
        hS = s; yaw = atan2(-nx, -nz); st = St.HANG; man = null; vy = 0f; vx = 0f; vz = 0f; speed = 0f; jd = null
        hangPlace()
        setClip(if (g == 1) "p:Ledge_Idle" else "p:Hanging_Idle", true, 0f, 0.15f); info = if (g == 1) "no apoio" else "pendurado"; hangT = 0f
    }

    private fun hangPlace() {
        val b = hBox!!
        val fd = if (grip == 1) LEDGE_FACE else HANG_FACE
        val front = if (abs(hNx) > 0.5f) (if (hNx < 0) b.x0 else b.x1) else (if (hNz < 0) b.z0 else b.z1)
        val back = if (abs(hNx) > 0.5f) (if (hNx < 0) b.x1 else b.x0) else (if (hNz < 0) b.z1 else b.z0)
        val face = if (grip == 1) back else front
        if (abs(hNx) > 0.5f) { x = face + hNx * fd; z = hS } else { z = face + hNz * fd; x = hS }
        y = hTop - (if (grip == 1) LEDGE_ROOT else HANG_ROOT)
    }

    private fun frontCoord(b: Box) = if (abs(hNx) > 0.5f) (if (hNx < 0) b.x0 else b.x1) else (if (hNz < 0) b.z0 else b.z1)
    private fun centerS(b: Box) = if (abs(hNx) > 0.5f) (b.z0 + b.z1) / 2 else (b.x0 + b.x1) / 2
    private fun launchAir(vxn: Float, vzn: Float, vyn: Float) {
        man = null; hBox = null; beamBox = null; st = St.AIR; vx = vxn; vz = vzn; vy = vyn; jd = null; jPre = false; fallStartY = y; airT = 0f
        setClip("p:Fly_Front", true, 0f, 0.25f)
    }
    /** Termina uma manobra que muda a orientação do corpo: o clipe já acabou girado, então corta direto para o idle. */
    private fun endFacing(dYaw: Float) {
        yaw = wrapA(yaw + dYaw); speed = 0f; vx = 0f; vz = 0f; st = St.LOCO; crouched = false; snapGround()
        setClip("m:Stand_Relaxed_Idle", true, 0f, 0.001f)
    }

    private fun hang(dt: Float, c: Ctl) {
        val b = hBox ?: run { st = St.LOCO; return }
        hangT += dt
        val lx = cos(yaw); val lz = -sin(yaw) // esquerda do personagem
        val ml = c.mx * lx + c.mz * lz
        val mf = c.mx * sin(yaw) + c.mz * cos(yaw)
        val mg = if (grip == 1) 0.1f else 0.35f
        val lim0: Float; val lim1: Float
        if (abs(hNx) > 0.5f) { lim0 = b.z0 + mg; lim1 = b.z1 - mg } else { lim0 = b.x0 + mg; lim1 = b.x1 - mg }
        val idleN = if (grip == 1) "p:Ledge_Idle" else "p:Hanging_Idle"
        var mv = 0f
        if (ml > 0.35f) mv = 1f else if (ml < -0.35f) mv = -1f
        if (mv != 0f && lim1 > lim0) {
            val axisPos = if (abs(hNx) > 0.5f) lz else lx
            val dir = mv * (if (axisPos >= 0) 1f else -1f)
            hS = clampf(hS + dir * 1.1f * dt, lim0, lim1)
            val want = if (grip == 1) (if (mv > 0) "p:Ledge_Move_L_IP" else "p:Ledge_Move_R_IP") else (if (mv > 0) "p:Hanging_Move_L" else "p:Hanging_Move_R")
            if (clip != want) setClip(want, true, 0f, 0.12f)
            t += dt
        } else {
            if (clip != idleN) setClip(idleN, true, 0f, 0.15f)
            t += dt
        }
        hangPlace()
        // costas para fora / de frente (180) — só na beirada
        if (grip == 0) {
            if ((!hAway && mf < -0.6f) || (hAway && mf < -0.6f)) hBackT += dt else hBackT = 0f
            if (hBackT > 0.25f) { hBackT = 0f; hang180(); return }
        }
        if (c.jump) {
            if (grip == 0) { if (hAway) hangLeap(mf) else hangClimb(); return }
            ledgeJump(ml, mf); return
        }
        if (c.crouch || c.drop) {
            if (grip == 1) { ledgeDown(); return }
            setClip("p:Hanging_Drop", false, 0f, 0.1f)
            st = St.AIR; jd = null; vy = -1f; fallStartY = y; airT = 0f; jPre = false
            x += hNx * 0.25f; z += hNz * 0.25f
            vx = 0f; vz = 0f; info = "soltou"; hBox = null
        }
    }

    private fun hang180() {
        val nm = if ((vaultRot++ % 2) == 0) "p:Hanging_180_L" else "p:Hanging_180_R"
        info = "virar na beirada"
        beginMan(nm, 1f, null, C(nm).dur, {
            yaw = wrapA(yaw + PI.toFloat()); hAway = !hAway; st = St.HANG; hangPlace()
            setClip("p:Hanging_Idle", true, 0f, 0.001f)
        }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
    }

    private fun hangLeap(mf: Float) {
        val far = mf > 0.5f
        val nm = if (far) "p:Fly_Hanging_Jump_F" else "p:Hanging_Jump_F"; val cl = C(nm)
        info = if (far) "salto longo da beirada" else "salto da beirada"
        val v = if (far) 5.0f else 3.6f
        val fx = sin(yaw); val fz = cos(yaw)
        hBox = null
        beginMan(nm, 1f, null, cl.dur * 0.5f, { launchAir(fx * v, fz * v, 1.0f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
    }

    // ---- apoios (ledge)
    private fun sameWall(b: Box) = b.kind == 4 && b !== hBox && abs(frontCoord(b) - frontCoord(hBox!!)) < 0.15f
    private fun ledgeFind(dLat: Float, latMin: Float, latMax: Float, dyMin: Float, dyMax: Float): Box? {
        var best: Box? = null; var bd = 1e9f
        for (b in w.special) {
            if (!sameWall(b)) continue
            val dy = b.y1 - hTop; if (dy < dyMin || dy > dyMax) continue
            val d = (centerS(b) - hS) * dLat; if (d < latMin || d > latMax) continue
            val score = abs(d) + abs(dy); if (score < bd) { bd = score; best = b }
        }
        return best
    }
    private fun leftSign(): Float { val axisPos = if (abs(hNx) > 0.5f) -sin(yaw) else cos(yaw); return if (axisPos >= 0) 1f else -1f }

    private fun ledgeJump(ml: Float, mf: Float) {
        val b = hBox!!
        if (ml > 0.5f || ml < -0.5f) {
            val left = ml > 0f
            val dirW = (if (left) 1f else -1f) * leftSign()      // sinal no eixo do mundo
            val tgt = ledgeFind(dirW, 1.0f, 3.9f, -0.35f, 0.35f) ?: return
            val dist = abs(centerS(tgt) - hS)
            val short = dist < 2.5f
            val nm = "p:Ledge_Jump_" + (if (left) "L" else "R") + (if (short) "_Short" else "")
            val cdx = (if (short) 1.70f else 3.25f) * (if (left) 1f else -1f)
            val want = (if (left) 1f else -1f) * dist
            info = "pulo lateral"
            val nx = hNx; val nz = hNz; val s2 = centerS(tgt); val top = tgt.y1
            beginMan(nm, 1f, null, C(nm).dur, { enterHang(tgt, nx, nz, top, s2, 1) }, 1f, { 0f }, { tn -> (want - cdx) * smooth(tn) }, yaw, 0.01f, 0.1f)
            return
        }
        if (mf < -0.5f) { // pula para trás, de costas para a parede
            val nm = if ((vaultRot++ % 2) == 0) "p:Ledge_Jump180_L" else "p:Ledge_Jump180_R"
            info = "pulo para trás"
            val ax = hNx; val az = hNz
            beginMan(nm, 1f, null, C(nm).dur * 0.7f, { yaw = wrapA(yaw + PI.toFloat()); launchAir(ax * 2.2f, az * 2.2f, -0.5f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
            return
        }
        if (mf > 0.5f) {
            val tgt = ledgeFind(1f, -0.9f, 0.9f, 0.8f, 2.9f)
            if (tgt != null) {
                val dy = tgt.y1 - hTop
                val nm = if (dy > 1.7f) "p:Ledge_Jump_Up_Power" else "p:Ledge_Jump_Up"
                val cdy = if (dy > 1.7f) 2.31f else 1.18f
                info = "pulo para cima"
                val nx = hNx; val nz = hNz; val s2 = centerS(tgt); val top = tgt.y1; val ds = (s2 - hS) * leftSign()
                beginMan(nm, 1f, null, C(nm).dur, { enterHang(tgt, nx, nz, top, s2, 1) }, 1f, { tn -> (dy - cdy) * smooth(tn) }, { tn -> ds * smooth(tn) }, yaw, 0.01f, 0.1f)
                return
            }
        }
        hangClimb()
    }

    private fun ledgeDown() {
        val tgt = ledgeFind(1f, -0.9f, 0.9f, -2.9f, -0.8f)
        val nx = hNx; val nz = hNz
        if (tgt != null) {
            val dy = tgt.y1 - hTop; val s2 = centerS(tgt); val top = tgt.y1; val ds = (s2 - hS) * leftSign()
            info = "descer de apoio"
            beginMan("p:Ledge_Jump_Down", 1f, null, C("p:Ledge_Jump_Down").dur, { enterHang(tgt, nx, nz, top, s2, 1) }, 1f, { tn -> (dy + 1.19f) * smooth(tn) }, { tn -> ds * smooth(tn) }, yaw, 0.01f, 0.1f)
        } else {
            info = "soltou do apoio"
            beginMan("p:Ledge_Fall_Down", 1f, null, C("p:Ledge_Fall_Down").dur, { launchAir(nx * 1.0f, nz * 1.0f, -1f) }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.1f)
        }
        hBox = null
    }

    private fun hangClimb() {
        val b = hBox!!
        // há chão no topo?
        val ex = x - hNx * 0.5f; val ez = z - hNz * 0.5f
        if (w.groundAt(ex, ez, 0.1f, hTop + 0.05f) < hTop - 0.1f) return
        val top = hTop
        if (grip == 1) {
            val pick = (vaultRot++) % 3
            val nm = if (pick == 0) "p:Ledge_ClimbUp_TwoHand_L" else if (pick == 1) "p:Ledge_ClimbUp_TwoHand_R" else "p:Ledge_ClimbUp_Monkey"
            info = "subir do apoio"; hBox = null
            beginMan(nm, 1f, null, C(nm).dur, { st = St.LOCO; y = top; snapGround(); crouched = false }, 1f, { 0f }, { 0f }, yaw, 0.01f, 0.15f)
            return
        }
        val nm = "p:Hanging_ClimbeUp_F"; val cl = C(nm)
        info = "escalar"; hBox = null
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
    private var once = false; private var diveIn = false
    private fun playOnce(n: String, fade: Float = 0.15f) { if (cs.has(n)) { setClip(n, false, 0f, fade); once = true } }

    private fun enterWater(ww: Water) {
        swimW = ww; st = St.SWIM; man = null; underwater = false; swimMode = 0; swimVy = min(0f, vy) * 0.3f
        swimY = y; speed = 0f; swimEnterT = 0f; jd = null; jPre = false; swimHold = 0f; swimIdleT = 0f; subT = 0f; drown = 0; actOn = false
        val fall = fallStartY - y
        if (diveIn && fall > 1.0f) {
            swimMode = 1; swimVy = -1.5f; diveIn = false; playOnce("s:Dive_B", 0.1f); info = "mergulho"; return
        }
        diveIn = false
        playOnce(if (fall > 3f) "s:Jump_B_End" else if (fall > 1.2f) "s:Jump_A_End_02" else "s:Jump_A_End_01", 0.1f); info = "entrou na água"
    }

    private fun swim(dt: Float, c: Ctl) {
        val ww = swimW ?: run { st = St.LOCO; return }
        swimEnterT += dt
        val floor = w.groundAt(x, z, 0.12f, ww.surface + 3f)
        val depth = ww.surface - floor
        val m = min(1f, hypot(c.mx, c.mz))
        val surfRoot = ww.surface - 1.0f
        // ---- afogamento
        if (drown > 0) { drownStep(dt, ww, surfRoot); return }
        if (swimMode == 1) { subT += dt; if (subT > 20f) { drown = 1; drownT = 0f; setClip("s:drowning", true, 0f, 0.3f); info = "afogando"; return } } else subT = max(0f, subT - dt * 3f)
        // ---- direção
        val oldYaw = yaw
        var rel = 0f
        if (c.aim && swimMode == 0) {
            yaw = wrapA(yaw + clampf(wrapA(c.aimYaw - yaw), -8f * dt, 8f * dt))
            if (m > 0.12f) { mdir = wrapA(mdir + clampf(wrapA(atan2(c.mx, c.mz) - mdir), -10f * dt, 10f * dt)); rel = wrapA(mdir - yaw) } else mdir = yaw
        } else {
            if (m > 0.12f) { val ta = atan2(c.mx, c.mz); yaw = wrapA(yaw + clampf(wrapA(ta - yaw), -6f * dt, 6f * dt)) }
            mdir = yaw
        }
        lastYawRate += (wrapA(yaw - oldYaw) / max(dt, 1e-3f) - lastYawRate) * min(1f, 8f * dt)
        if (m > 0.8f && !c.aim && swimMode == 0) swimHold += dt else swimHold = 0f
        val tier = if (swimHold > 5f) 4 else if (swimHold > 3.5f) 3 else if (swimHold > 2f) 2 else if (swimHold > 1f) 1 else 0
        val base = if (m < 0.5f) 1.2f else 2.1f
        val spd = if (m < 0.12f) 0f else if (swimMode == 1) 2.0f else if (c.aim) base * 0.8f else base + 0.4f * tier
        speed += clampf(spd - speed, -6f * dt, 6f * dt)
        val p = floatArrayOf(x + sin(mdir) * speed * dt, y, z + cos(mdir) * speed * dt)
        w.pushOut(p, y + 0.3f, R, H - 0.3f, 0.2f)
        x = p[0]; z = p[2]
        if (swimMode == 0) {
            val gy = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            val target = max(surfRoot, gy)
            y += clampf(target - y, -3.0f * dt, 3.0f * dt)
            if (c.crouch && swimEnterT > 0.4f) {
                swimMode = 1; swimVy = 0f; subT = 0f
                if (m < 0.12f && cs.has("s:transition_idleA_to_dive")) playOnce("s:transition_idleA_to_dive") else playOnce("s:Dive_A")
                info = "mergulhando"; return
            }
            if (c.jump && swimEnterT > 0.5f) { if (!waterExit(c)) { /* pulinho na água */ } }
            if (c.act && m < 0.12f && !actOn) {
                val names = arrayOf("s:cutting_knife_l", "s:cutting_knife_r", "s:hammering_l", "s:hammering_r")
                val n = names[(actI++) % 4]; actOn = true; setClip(n, false, 0f, 0.2f); once = true; info = n.substring(2); return
            }
            surfaceClip(dt, m, rel, tier)
        } else {
            val up = if (c.jumpHeld) 1f else 0f; val dn = if (c.crouchHeld) 1f else 0f
            val ty = (up - dn) * 2.2f
            swimVy += clampf(ty - swimVy, -5f * dt, 5f * dt)
            if (up == 0f && dn == 0f) swimVy += clampf(-0.0f - swimVy, -2f * dt, 2f * dt)
            var ny = y + swimVy * dt
            val gy = w.groundAt(x, z, 0.1f, ww.surface + 3f)
            if (ny < gy) { ny = gy; swimVy = 0f }
            if (ny >= surfRoot) {
                ny = surfRoot; swimMode = 0; swimVy = 0f; swimEnterT = 0f; subT = 0f
                setClip("s:to_surface", false, 0f, 0.15f); once = true; y = ny; return
            }
            y = ny
            underClip(dt, m, c)
        }
    }

    private fun drownStep(dt: Float, ww: Water, surfRoot: Float) {
        drownT += dt
        swimVy += clampf(-0.6f - swimVy, -2f * dt, 2f * dt)
        val gy = w.groundAt(x, z, 0.1f, ww.surface + 3f)
        y = max(gy, y + swimVy * dt)
        t += dt
        if (drown == 1 && drownT > 3f) { drown = 2; drownT = 0f; setClip("s:dying", false, 0f, 0.3f); info = "morrendo" }
        else if (drown == 2 && drownT > C("s:dying").dur) { drown = 3; drownT = 0f; setClip("s:dead", false, 0f, 0.2f); info = "morreu afogado" }
        else if (drown == 3 && drownT > 2.5f) {
            drown = 0; subT = 0f; swimMode = 0; swimVy = 0f; y = surfRoot; swimEnterT = 0f
            setClip("s:idle_A", true, 0f, 0.5f); info = "reapareceu"
        }
    }

    private fun lean(base: String, slow: Boolean): String {
        val l = lastYawRate
        if (abs(l) > 1.4f) { val n = base + (if (l > 0f) "_Left" else "_Right") + (if (slow) "_slow" else ""); if (cs.has("s:$n")) return "s:$n"; val n2 = base + (if (l > 0f) "_Left" else "_Right"); if (cs.has("s:$n2")) return "s:$n2" }
        return "s:" + base + (if (slow) "_slow" else "")
    }

    private fun surfaceClip(dt: Float, m: Float, rel: Float, tier: Int) {
        if (actOn) {
            if (m > 0.3f || t >= C(clip).dur - 0.05f) { actOn = false; once = false } else { t += dt; return }
        }
        if (once) { if (t < C(clip).dur - 0.05f && swimEnterT < 3f) { t += dt; return } else once = false }
        val want: String
        if (m < 0.12f) {
            swimIdleT += dt
            want = if (swimIdleT > 12f && swimIdleT < 18f) "s:idle_C" else if (swimIdleT > 6f && swimIdleT < 12f) "s:idle_B" else "s:idle_A"
            if (swimIdleT > 18f) swimIdleT = 0f
        } else {
            swimIdleT = 0f
            if (c_aimSec(rel) == "B" && aimOn) want = "s:backward_A"
            else if (aimOn && c_aimSec(rel) == "L") want = "s:left"
            else if (aimOn && c_aimSec(rel) == "R") want = "s:right"
            else if (tier >= 1) want = lean("forward_" + "ABCDE"[tier], false)
            else want = lean("forward_A", speed < 1.6f)
        }
        if (clip != want) setClip(want, true, if (want.startsWith("s:forward") && clip.startsWith("s:forward")) t else 0f, 0.25f)
        t += dt
    }
    private fun c_aimSec(rel: Float): String { val a = abs(rel); return if (a < 0.8f) "F" else if (a < 2.3f) (if (rel > 0f) "L" else "R") else "B" }

    private fun underClip(dt: Float, m: Float, c: Ctl) {
        if (once) { if (t < C(clip).dur - 0.05f) { t += dt; return } else once = false }
        val want = if (swimVy < -0.8f) "s:Topedo_down" else if (swimVy > 0.8f) "s:Topedo_up" else if (m > 0.12f) (if (aimOn && abs(wrapA(mdir - yaw)) > 2.3f) "s:Topedo_backwd" else "s:Topedo_fwd") else "s:floating"
        if (clip != want) {
            if (clip == "s:floating" && want == "s:Topedo_fwd" && cs.has("s:transition_to_SwimFwd")) { playOnce("s:transition_to_SwimFwd", 0.2f); return }
            if (want == "s:Topedo_up" && clip != "s:Topedo_up" && cs.has("s:transition_to_SwimUp")) { playOnce("s:transition_to_SwimUp", 0.2f); return }
            setClip(want, true, 0f, 0.3f)
        }
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
