package com.skaterparkour.app

import kotlin.math.*
import kotlin.random.Random

/**
 * Lógica do campo de treino: guerreiro (movimento, ataques, skills), boneco, câmera.
 * Unidades do mundo em metros. O modelo do guerreiro tem 1 unidade = 1,8 m.
 */
class Game(private val anims: Anims, private val skel: Skeleton, private val fx: Fx, private val audio: Audio) {
    companion object {
        const val K = 1.8f
        val BSEG = arrayOf(floatArrayOf(0f, 1.15f), floatArrayOf(1.15f, 1.75f), floatArrayOf(1.75f, 2.55f), floatArrayOf(2.55f, 4.63f))
        val BHITS = arrayOf(floatArrayOf(.48f, .98f), floatArrayOf(1.55f), floatArrayOf(2.18f), floatArrayOf(2.85f, 3.25f))
        val SKILLS = arrayOf("basic", "s1", "s2", "s3", "s4", "ult")
        val CD = floatArrayOf(1f, 6f, 10f, 12f, 12f, 60f)
        // direções de strafe (ângulo relativo, + = esquerda do personagem)
        val STRAFE = arrayOf("front", "frontL45", "left", "backL45", "back", "backR45", "right", "frontR45")
    }

    // ---------- jogador ----------
    val P = floatArrayOf(0f, 0f, 4.2f)
    var yaw = PI.toFloat()
    private var yawTarget = yaw
    private val vel = FloatArray(2)
    private var speedSm = 0f
    private var phase = 0f
    private var idleT = 0f
    var lock = false
    var time = 0f
    private var hitstop = 0f

    private enum class Mode { LOCO, ACT, ROLL, JUMP }
    private var mode = Mode.LOCO
    private var modeT = 0f
    private var rollDir = 0f
    private var rollClip = "roll_front"

    // ---------- câmera ----------
    var camYaw = PI.toFloat()
    var camPitch = .36f
    var camDist = 7.5f
    private var camDistSm = 7.5f
    private val camTarget = FloatArray(3)
    val eye = FloatArray(3)
    val look = FloatArray(3)
    private var shakeA = 0f

    // ---------- boneco ----------
    val D = floatArrayOf(0f, 0f, 0f)
    var dYaw = 0f
    val dTilt = FloatArray(2); private val dTiltV = FloatArray(2)
    var dHp = 1f
    private val dHpMax = 1800f
    private var dDead = -1f
    private var dFall = 0f
    private var dStun = 0f
    private var dLastHit = -9f
    private var dAtkT = 6f
    private val dPushV = FloatArray(2)
    var dFlash = 0f

    // ---------- ação (sequência de clipes) ----------
    private class Seg(val clip: String, val start: Float, var end: Float, val blend: Float)
    private class Ev(val t: Float, val f: () -> Unit)
    private class Tk(val a: Float, val b: Float, val f: (Float, Float) -> Unit)
    private inner class Act(val id: String, val segs: List<Seg>, val evs: List<Ev>, val tks: List<Tk>, val endBlend: Float = .28f) {
        var t = 0f; var si = 0; var segT0 = 0f; var done = false
        fun segDur(s: Seg) = (if (s.end < 0f) anims[s.clip].dur else s.end) - s.start
    }
    private var act: Act? = null
    private val target = Pose(); private val tmpA = Pose(); private val tmpB = Pose(); private val tmpC = Pose(); private val tmpD = Pose()
    val animator = Animator()

    // efeitos do personagem
    var red = 0f          // 0..1 runas vermelhas (Ultimate)
    var gold = 0f         // 0..1 brilho dourado nos machados
    private var trailsOn = 0f
    private var trailRed = false
    private var auraT = 0f
    private var basicK = 0
    private var lastBasic = -9f
    private var basicEnd = -9f

    // Skill 4 (machado arremessado)
    private enum class S4 { OFF, HOLD, WINDUP, FLY, STUCK, PULL, BACK }
    private var s4 = S4.OFF
    private var s4T = 0f
    private var s4Hold = 0f
    var aimYaw = 0f
    val axePos = FloatArray(3)
    private val axeDir = FloatArray(2)
    private var axeDist = 0f
    private var axeSpin = 0f
    private var axeHit = false
    private val pullFrom = FloatArray(3)
    private val dPullFrom = FloatArray(2)
    val axeM = FloatArray(16)
    var axeFlying = false

    // cooldowns
    val cdReady = FloatArray(6)
    var noCd = false

    // textos de dano
    class Txt(val x: Float, val y: Float, val z: Float, val v: Int, val crit: Boolean, val col: Int, var t: Float = 0f)
    val texts = ArrayList<Txt>()

    // matrizes
    val charM = FloatArray(16)
    val wM = arrayOf(FloatArray(16), FloatArray(16))
    private val wTmp = FloatArray(16)
    private val v3 = FloatArray(3); private val v3b = FloatArray(3)
    val blades = arrayOf(FloatArray(3), FloatArray(3))
    val handR = FloatArray(3)
    private val ev = ArrayList<String>()
    private val camIn = FloatArray(3)
    private var entered = false

    fun cdLeft(i: Int) = if (noCd) 0f else max(0f, cdReady[i] - time)

    // =====================================================================
    fun update(dtRaw: Float) {
        val dt = min(dtRaw, 1f / 20f)
        time += dt
        if (!entered && time > .8f) { entered = true; audio.voice("enter") }
        Input.take(camIn, ev)
        handleCam()
        for (e in ev) handleEvent(e)
        ev.clear()

        // hitstop: congela a animação por instantes no impacto
        val adt = if (hitstop > 0f) { hitstop -= dt; dt * .06f } else dt

        when (mode) {
            Mode.LOCO -> loco(dt)
            Mode.ROLL -> roll(dt)
            Mode.JUMP -> jump(dt)
            Mode.ACT -> if (s4 == S4.OFF) runAct(adt, dt)
        }
        s4Tick(dt)
        // vira suavemente
        yaw += wrapA(yawTarget - yaw) * min(1f, dt * (if (mode == Mode.ACT) 14f else 11f))

        animator.apply(target, adt)
        skel.solve(animator.out)
        M4.trs(charM, P[0], P[1], P[2], yaw, K)
        for (s in 0 until 2) { skel.weapon(s, wTmp); M4.mul(wM[s], charM, wTmp) }
        M4.mul(wTmp, 0, charM, 0, skel.world, 42 * 16); M4.xf(wTmp, 0, 0f, 0f, 0f, handR)
        for (s in 0 until 2) M4.xf(wM[s], 0, -0.206f, .76f, 0f, blades[s])

        updateTrails(dt)
        updateDummy(dt)
        updateFx(dt)
        updateCamera(dt)
        // textos
        synchronized(texts) { texts.forEach { it.t += dt }; texts.removeAll { it.t > 1.1f } }
    }

    // ---------------- entrada ----------------
    private fun handleCam() {
        camYaw -= camIn[0]
        camPitch = clampf(camPitch + camIn[1], -.05f, 1.35f)
        camDist = clampf(camDist / camIn[2], 2.6f, 26f)
    }

    private fun handleEvent(e: String) {
        when (e) {
            "basic" -> basicPress()
            "s1", "s2", "s3", "ult" -> useSkill(e)
            "s4down" -> s4Down()
            "s4up" -> s4Release()
            "roll" -> rollPress()
            "jump" -> jumpPress()
            "lock" -> lock = !lock
            "nocd" -> noCd = !noCd
        }
    }

    private fun canAct() = mode == Mode.LOCO && s4 == S4.OFF

    private fun ready(i: Int) = noCd || time >= cdReady[i]
    private fun startCd(i: Int) { cdReady[i] = time + CD[i] }

    private fun distD() = hypot(D[0] - P[0], D[2] - P[2])
    private fun yawToD() = atan2(D[0] - P[0], D[2] - P[2])
    private fun dAlive() = dDead < 0f

    private fun faceTarget(force: Boolean = false) {
        if (dAlive() && (lock || force || distD() < 7f)) { yawTarget = yawToD() }
    }

    // ---------------- locomoção ----------------
    private fun stickDir(): FloatArray? {
        val jx = Input.jx; val jy = Input.jy
        val m = hypot(jx, jy)
        if (m < .12f) return null
        // frente da câmera e direita da câmera no chão
        val fx = sin(camYaw); val fz = cos(camYaw)
        val rx = -fz; val rz = fx
        val dx = fx * jy + rx * jx; val dz = fz * jy + rz * jx
        return floatArrayOf(atan2(dx, dz), min(1f, (m - .12f) / .8f))
    }

    private fun loco(dt: Float) {
        val sd = stickDir()
        val locked = lock && dAlive()
        val maxV = if (locked) 4.1f else 5.2f
        val want = if (sd != null) (if (locked) .85f else 1f) + (maxV - (if (locked) .85f else 1f)) * sd[1] * sd[1] else 0f
        speedSm += (want - speedSm) * min(1f, dt * (if (want > speedSm) 7f else 10f))
        val dir = sd?.get(0) ?: atan2(vel[0], vel[1])
        if (speedSm > .02f) {
            val tvx = sin(dir) * speedSm; val tvz = cos(dir) * speedSm
            vel[0] += (tvx - vel[0]) * min(1f, dt * 12f); vel[1] += (tvz - vel[1]) * min(1f, dt * 12f)
        } else { vel[0] = 0f; vel[1] = 0f }
        move(vel[0] * dt, vel[1] * dt)
        val sp = hypot(vel[0], vel[1])

        if (locked) yawTarget = yawToD() else if (sd != null && sp > .2f) yawTarget = dir

        idleT += dt
        anims.sample(anims["idle_00"], idleT, true, tmpA)
        if (sp < .05f) { target.copy(tmpA); return }
        if (!locked) {
            val walk = anims["walk_front"]; val run = anims["move_run_front"]
            val wr = clampf((sp - 1.04f) / (5.39f - 1.04f), 0f, 1f)
            val cyc = lerpf(walk.dur, run.dur, wr)
            phase = (phase + dt / cyc * max(sp / lerpf(1.04f, 5.39f, wr), .55f)) % 1f
            anims.sample(walk, phase * walk.dur, true, tmpB)
            anims.sample(run, phase * run.dur, true, tmpC)
            Anims.blend(tmpB, tmpC, wr, tmpB)
        } else {
            val rel = wrapA(atan2(vel[0], vel[1]) - yaw)
            var a = rel / (PI.toFloat() / 4f); if (a < 0) a += 8f
            val i0 = a.toInt() % 8; val i1 = (i0 + 1) % 8; val w = a - a.toInt()
            val wr = clampf((sp - .85f) / (4.1f - .85f), 0f, 1f)
            val cyc = lerpf(anims["move_walk_strafe_front"].dur, anims["move_run_strafe_front"].dur, wr)
            phase = (phase + dt / cyc * max(sp / lerpf(.85f, 4.1f, wr), .55f)) % 1f
            fun dirPose(i: Int, out: Pose) {
                val cw = anims["move_walk_strafe_" + STRAFE[i]]; val cr = anims["move_run_strafe_" + STRAFE[i]]
                anims.sample(cw, phase * cw.dur, true, tmpD); anims.sample(cr, phase * cr.dur, true, out)
                Anims.blend(tmpD, out, wr, out)
            }
            dirPose(i0, tmpB); dirPose(i1, tmpC)
            Anims.blend(tmpB, tmpC, w, tmpB)
        }
        val wIdle = 1f - clampf(sp / .9f, 0f, 1f)
        Anims.blend(tmpB, tmpA, wIdle, target)
    }

    private fun move(dx: Float, dz: Float) {
        var nx = P[0] + dx; var nz = P[2] + dz
        // não atravessa o boneco
        val ex = nx - D[0]; val ez = nz - D[2]; val de = hypot(ex, ez)
        if (de < .75f && dAlive()) { nx = D[0] + ex / max(de, 1e-3f) * .75f; nz = D[2] + ez / max(de, 1e-3f) * .75f }
        // limite do campo (cerca)
        val r = hypot(nx, nz)
        if (r > 14.8f) { nx *= 14.8f / r; nz *= 14.8f / r }
        P[0] = nx; P[2] = nz
    }

    private fun rollPress() {
        if (!(mode == Mode.LOCO || (mode == Mode.ACT && act?.id == "basic")) || s4 != S4.OFF) return
        if (mode == Mode.ACT) { act = null }
        val sd = stickDir()
        rollDir = sd?.get(0) ?: yaw
        rollClip = "roll_front"
        if (lock && dAlive()) {
            val rel = wrapA(rollDir - yaw)
            rollClip = when {
                abs(rel) < PI.toFloat() / 4 -> "roll_front"
                abs(rel) > 3 * PI.toFloat() / 4 -> "roll_back"
                rel < 0 -> "roll_right"
                else -> "roll_left"
            }
        } else { yawTarget = rollDir; yaw = rollDir }
        mode = Mode.ROLL; modeT = 0f
        animator.transition(.1f)
        audio.sfx("swing2", .5f, .75f)
    }

    private fun roll(dt: Float) {
        modeT += dt
        val u = modeT / .62f
        val sp = if (u < .85f) 5.4f * (1 - u * .6f) else 0f
        move(sin(rollDir) * sp * dt, cos(rollDir) * sp * dt)
        anims.sample(anims[rollClip], modeT, false, target)
        if (modeT >= .62f) { mode = Mode.LOCO; animator.transition(.22f); vel[0] = 0f; vel[1] = 0f; speedSm = 0f }
    }

    private fun jumpPress() {
        if (mode != Mode.LOCO || s4 != S4.OFF) return
        mode = Mode.JUMP; modeT = 0f; animator.transition(.12f)
        audio.sfx("swing1", .4f, .8f)
    }

    private fun jump(dt: Float) {
        modeT += dt
        val sd = stickDir()
        if (sd != null) { val v = 3.4f * sd[1] + 1f; move(sin(sd[0]) * v * dt, cos(sd[0]) * v * dt); if (!lock) yawTarget = sd[0] }
        val c = anims["jump"]
        anims.sample(c, modeT, false, target)
        if (modeT >= c.dur - .05f) { mode = Mode.LOCO; animator.transition(.2f) }
    }

    // ---------------- ações ----------------
    private fun startAct(a: Act, blend: Float) {
        act = a; mode = Mode.ACT; modeT = 0f
        animator.transition(blend)
        vel[0] = 0f; vel[1] = 0f; speedSm = 0f
        fireUntil(a, -1f, 0f)
    }

    private fun fireUntil(a: Act, t0: Float, t1: Float) {
        for (e in a.evs) if (e.t > t0 && e.t <= t1) e.f()
    }

    private fun runAct(adt: Float, dt: Float) {
        val a = act ?: run { mode = Mode.LOCO; return }
        val t0 = a.t
        a.t += adt
        var seg = a.segs[a.si]
        // troca de segmento
        while (a.t - a.segT0 >= a.segDur(seg)) {
            if (a.si + 1 < a.segs.size) {
                a.segT0 += a.segDur(seg); a.si++; seg = a.segs[a.si]
                animator.transition(seg.blend)
            } else { a.done = true; break }
        }
        fireUntil(a, t0, a.t)
        for (k in a.tks) if (a.t >= k.a && t0 < k.b) k.f(dt, clampf((a.t - k.a) / (k.b - k.a), 0f, 1f))
        if (a.done) {
            act = null; mode = Mode.LOCO
            animator.transition(a.endBlend)
            if (a.id == "basic") basicEnd = time
            trailsOn = 0f
            return
        }
        anims.sample(anims[seg.clip], seg.start + (a.t - a.segT0), false, target)
        // gira para o alvo durante golpes
        if (a.id != "hurt") faceTarget()
    }

    // ---- básico: cada toque toca o próximo trecho do Combo 04 (sem trancos: o clipe segue contínuo) ----
    private fun basicPress() {
        if (time - lastBasic < CD[0] && !noCd) return
        val a = act
        if (mode == Mode.ACT && a != null && a.id == "basic") {
            if (basicK < 3) {
                basicK++
                a.segs[0].end = BSEG[basicK][1]
                lastBasic = time; startCd(0)
            }
            return
        }
        if (!canAct()) return
        basicK = if (time - basicEnd < 1.6f && basicK < 3) basicK + 1 else 0
        val st = BSEG[basicK][0]
        val evs = ArrayList<Ev>()
        for (k in basicK until 4) {
            val s0 = BSEG[k][0] - st
            evs.add(Ev(max(s0, 0f) + .001f) { audio.basicShout(); trailsOn = 1f; trailRed = false })
            for (h in BHITS[k]) {
                evs.add(Ev(h - st - .14f) { audio.sfx(if (Random.nextBoolean()) "swing1" else "swing2", .8f, rnd(.92f, 1.08f)) })
                evs.add(Ev(h - st) { meleeHit(2.7f, 1.9f, if (k == 3 && h > 3f) 75 else 48, 1f) })
            }
        }
        val seg = Seg("Combo_04", st, BSEG[basicK][1], 0f)
        startAct(Act("basic", listOf(seg), evs, emptyList(), .3f), if (basicK == 0) .14f else .16f)
        faceTarget()
        lastBasic = time; startCd(0)
    }

    /** Golpe corpo a corpo: acerta o boneco se estiver no alcance e à frente. */
    private fun meleeHit(range: Float, cone: Float, dmg: Int, power: Float): Boolean {
        if (!dAlive()) return false
        val d = distD()
        if (d > range) return false
        val rel = abs(wrapA(yawToD() - yaw))
        if (rel > cone) return false
        damage(dmg, power, true)
        audio.sfx("axe_hit" + (1 + Random.nextInt(3)), .95f, rnd(.94f, 1.06f))
        val at = floatArrayOf(D[0] + (P[0] - D[0]) * .25f, 1.15f, D[2] + (P[2] - D[2]) * .25f)
        fx.hitSparks(at, if (trailRed) Fx.RED2 else Fx.GOLD2, 7, .8f)
        hitstop = max(hitstop, .045f); shake(.25f * power)
        return true
    }

    private fun damage(base: Int, power: Float, sparks: Boolean) {
        if (!dAlive()) return
        val crit = Random.nextFloat() < .2f
        val v = (base * rnd(.9f, 1.12f) * (if (crit) 1.75f else 1f)).toInt()
        dHp = max(0f, dHp - v / dHpMax)
        dLastHit = time; dFlash = 1f
        // balanço do boneco para longe do jogador
        val dx = D[0] - P[0]; val dz = D[2] - P[2]; val l = max(hypot(dx, dz), .01f)
        dTiltV[0] += dx / l * 4.5f * power; dTiltV[1] += dz / l * 4.5f * power
        synchronized(texts) { texts.add(Txt(D[0] + rnd(-.3f, .3f), 1.9f + rnd(0f, .3f), D[2] + rnd(-.3f, .3f), v, crit, if (red > .5f) 0xFFFF5A3C.toInt() else if (crit) 0xFFFFD25A.toInt() else 0xFFFFFFFF.toInt())) }
        if (dHp <= 0f) killDummy()
    }

    private fun killDummy() {
        dDead = 0f; dFall = 0f
        audio.voice(if (Random.nextBoolean()) "fraco" else "naopode")
        audio.sfx("s2_thud", .7f, .8f, .2f)
    }

    private fun useSkill(id: String) {
        val i = SKILLS.indexOf(id)
        if (!ready(i) || !canAct()) return
        faceTarget(true)
        yaw = yawTarget
        val a = when (id) {
            "s1" -> skill1(); "s2" -> skill2(); "s3" -> skill3(); "ult" -> ultimate(); else -> null
        } ?: return
        startAct(a, .14f)
        startCd(i)
    }

    private fun front(d: Float, out: FloatArray = FloatArray(3)): FloatArray {
        out[0] = P[0] + sin(yaw) * d; out[1] = 0f; out[2] = P[2] + cos(yaw) * d; return out
    }

    private fun midBlades(): FloatArray = floatArrayOf((blades[0][0] + blades[1][0]) / 2, (blades[0][1] + blades[1][1]) / 2, (blades[0][2] + blades[1][2]) / 2)

    // Skill 1 — Cruz Ceifadora
    private fun skill1(): Act {
        audio.voice("s1")
        return Act("s1", listOf(Seg("attack_03", 0f, 1.75f, .14f)), listOf(
            Ev(.01f) { trailsOn = 1f; trailRed = false; audio.sfx("s1_charge", .8f) },
            Ev(.72f) {
                audio.sfx("s1_launch")
                val from = midBlades(); from[1] = clampf(from[1], 1.1f, 1.6f)
                val hit = dAlive() && distD() < 9f && abs(wrapA(yawToD() - yaw)) < .6f
                val to = if (hit) floatArrayOf(D[0], 1.25f, D[2]) else front(7f).also { it[1] = 1.3f }
                val L = hypot(to[0] - from[0], to[2] - from[2])
                val T = clampf(L / 22f, .08f, .3f)
                fx.xBlade(from, to, Fx.GOLD, Fx.GOLD2, T)
                shake(.15f)
                later(T) {
                    if (hit && dAlive()) {
                        fx.xMark(floatArrayOf(D[0] + (P[0] - D[0]) * .08f, 1.25f, D[2] + (P[2] - D[2]) * .08f), yawToD(), Fx.GOLD, Fx.GOLD2)
                        audio.sfx("s1_impact"); audio.sfx("axe_hit2", .7f)
                        damage(150, 1.6f, true); hitstop = .07f; shake(.5f)
                    }
                }
            }
        ), listOf(Tk(.25f, .72f) { _, p -> for (b in blades) fx.gather(b, Fx.GOLD2, p) }))
    }

    // Skill 2 — Contra-Ataque do Carrasco
    private fun skill2(): Act {
        audio.voice("hah")
        return Act("s2", listOf(Seg("defence", 0f, .42f, .12f), Seg("attack_07", .15f, -1f, .2f)), listOf(
            Ev(.04f) { audio.sfx("s2_guard", .9f) },
            Ev(.3f) { dummyLunge() },
            Ev(.34f) {
                val m = midBlades()
                fx.deflect(m, Fx.GOLD, Fx.GOLD2)
                audio.sfx("parry"); audio.sfx("impact", .7f)
                shake(.6f); hitstop = .07f; gold = 1f
            },
            Ev(.95f) { trailsOn = 1f; trailRed = false },
            Ev(1.02f) {
                fx.counterWave(floatArrayOf(P[0], 0f, P[2]), Fx.GOLD, Fx.GOLD2)
                audio.voice("s2"); audio.sfx("s2_wave"); audio.sfx("s2_thud", .8f)
                shake(1f); hitstop = .08f
                if (dAlive() && distD() < 4.8f) {
                    damage(170, 2f, true)
                    val dx = D[0] - P[0]; val dz = D[2] - P[2]; val l = max(hypot(dx, dz), .01f)
                    dPushV[0] = dx / l * 9f; dPushV[1] = dz / l * 9f
                }
            }
        ), listOf(
            Tk(.04f, .3f) { _, p -> gold = max(gold, min(1f, p * 1.2f)) },
            Tk(.3f, 1.06f) { _, _ -> gold = 1f },
            Tk(1.06f, 1.6f) { _, p -> gold = max(0f, 1f - p * 1.6f) }
        ))
    }

    // Skill 3 — Rompe-Terra
    private fun skill3(): Act {
        audio.voice("s3")
        val c = front(1.75f)
        return Act("s3", listOf(Seg("attack_05", 0f, 2.7f, .14f)), listOf(
            Ev(.05f) { audio.sfx("s3_rise", .8f) },
            Ev(.28f) { fx.teleOn = .9f; fx.telePos[0] = c[0]; fx.telePos[2] = c[2]; fx.teleR = 2.2f; trailsOn = 1f; trailRed = false },
            Ev(.45f) { audio.sfx("s3_spin", .85f) },
            Ev(.82f) { audio.sfx("s3_spin", .9f, 1.08f) },
            Ev(1.2f) { audio.sfx("swing2", .9f, .85f) },
            Ev(1.36f) {
                fx.teleOn = 0f
                val g = lowBlade()
                fx.groundImpact(g, Fx.GOLD, Fx.GOLD2, 1.15f)
                audio.sfx("s3_slam"); audio.sfx("s3_boom", .9f, 1f, .05f)
                shake(1.1f); hitstop = .13f
                if (dAlive() && hypot(D[0] - g[0], D[2] - g[2]) < 3f) damage(260, 2.4f, true)
            }
        ), listOf(
            Tk(.28f, 1.36f) { _, p -> fx.teleP = p; val b = lowBlade(); fx.telePos[0] = lerpf(fx.telePos[0], b[0], .1f); fx.telePos[2] = lerpf(fx.telePos[2], b[2], .1f) },
            Tk(.4f, 1.28f) { _, p -> for (b in blades) fx.gather(b, Fx.GOLD2, p) }
        ))
    }

    private fun lowBlade(): FloatArray {
        val b = if (blades[0][1] < blades[1][1]) blades[0] else blades[1]
        // mantém à frente do personagem
        val f = front(1.6f)
        return floatArrayOf(lerpf(f[0], b[0], .6f), 0f, lerpf(f[2], b[2], .6f))
    }

    // Ultimate — Ragnarok do Carrasco
    private fun ultimate(): Act {
        audio.voice("buff")
        val slams = floatArrayOf(.92f, 1.75f, 2.55f, 3.55f).map { 1.3f + (it - .3f) }
        val evs = arrayListOf(
            Ev(.3f) {
                auraT = 5.75f; red = 1f; trailRed = true
                fx.auraBurst(floatArrayOf(P[0], 0f, P[2]))
                audio.sfx("ult_roar"); audio.sfx("ult_aura", 1f, 1f, .25f, "aura")
                shake(1f)
            },
            Ev(1.3f) { trailsOn = 1f; trailRed = true }
        )
        for ((i, t) in slams.withIndex()) {
            evs.add(Ev(t - .14f) { audio.sfx("swing2", .9f, .8f) })
            evs.add(Ev(t) {
                val last = i == slams.size - 1
                val g = lowBlade()
                fx.groundImpact(g, Fx.RED, Fx.RED2, if (last) 1.5f else 1.05f)
                audio.sfx(if (last) "ult_final" else "ult_slam"); audio.sfx("ult_debris", .7f, 1f, .05f)
                shake(if (last) 1.4f else .9f); hitstop = if (last) .14f else .09f
                if (dAlive() && hypot(D[0] - g[0], D[2] - g[2]) < (if (last) 3.6f else 3f)) damage(if (last) 340 else 140, if (last) 2.6f else 1.6f, true)
            })
        }
        return Act("ult", listOf(Seg("buff_01", 0f, 1.3f, .12f), Seg("Combo_03", .3f, -1f, .2f)), evs, emptyList(), .35f)
    }

    // ---------------- Skill 4: segurar, mirar e soltar ----------------
    private fun s4Down() {
        if (!ready(4) || !canAct()) return
        s4 = S4.HOLD; s4Hold = 0f
        aimYaw = if (dAlive() && distD() < 11f) yawToD() else yaw
        mode = Mode.ACT; act = null
        animator.transition(.15f)
        audio.voice("s4")
        audio.sfx("s1_charge", .5f, 1.2f)
    }

    private fun s4Release() {
        if (s4 != S4.HOLD) return
        s4 = S4.WINDUP; s4T = .14f
        startCd(4)
        fx.aimOn = false
    }

    private fun s4Tick(dt: Float) {
        when (s4) {
            S4.OFF -> return
            S4.HOLD -> {
                s4Hold += dt
                if (Input.aimActive) {
                    val ax = Input.aimX; val ay = Input.aimY
                    val fx2 = sin(camYaw); val fz = cos(camYaw)
                    val dx = fx2 * ay + -fz * ax; val dz = fz * ay + fx2 * ax
                    aimYaw = atan2(dx, dz)
                }
                yawTarget = aimYaw
                gold = min(1f, gold + dt * 3f)
                fx.aimOn = true; fx.aimFrom[0] = P[0]; fx.aimFrom[2] = P[2]; fx.aimYaw = aimYaw; fx.aimLen = 11f
                anims.sample(anims["throw"], .14f, false, target)
                if (s4Hold >= 5f) s4Release()
            }
            S4.WINDUP -> {
                s4T += dt
                anims.sample(anims["throw"], s4T, false, target)
                yawTarget = aimYaw
                if (s4T >= .25f) {
                    s4 = S4.FLY; axeFlying = true; axeHit = false; axeDist = 0f
                    axePos[0] = handR[0]; axePos[1] = max(handR[1], 1.1f); axePos[2] = handR[2]
                    axeDir[0] = sin(aimYaw); axeDir[1] = cos(aimYaw)
                    audio.sfx("chain_throw"); audio.sfx("axe_whir", .8f, 1f, .05f)
                    shake(.2f)
                }
            }
            S4.FLY -> {
                s4T += dt
                anims.sample(anims["throw"], s4T, false, target)
                val step = 24f * dt
                axePos[0] += axeDir[0] * step; axePos[2] += axeDir[1] * step; axeDist += step
                axePos[1] = lerpf(axePos[1], 1.2f, dt * 4f)
                axeSpin += dt * 26f
                if (dAlive() && hypot(axePos[0] - D[0], axePos[2] - D[2]) < .7f) {
                    s4 = S4.STUCK; s4T = 0f; axeHit = true
                    fx.hitSparks(floatArrayOf(axePos[0], 1.2f, axePos[2]), Fx.GOLD2, 14, 1.2f)
                    audio.sfx("axe_hit3"); audio.sfx("chain_snap", 1f, 1f, .05f)
                    damage(70, 1.2f, true); hitstop = .06f; shake(.35f)
                    dStun = 1.6f; fx.stunStars(floatArrayOf(D[0], 2.0f, D[2]))
                } else if (axeDist > 11.5f) {
                    s4 = S4.BACK; s4T = 0f; System.arraycopy(axePos, 0, pullFrom, 0, 3)
                }
            }
            S4.STUCK -> {
                s4T += dt
                anims.sample(anims["throw"], .73f, false, target)
                axePos[0] = D[0] + (P[0] - D[0]) * .1f; axePos[2] = D[2] + (P[2] - D[2]) * .1f
                fx.chainOn = true; fx.chainA0 = min(1f, s4T / .1f)
                System.arraycopy(handR, 0, fx.chainA, 0, 3); System.arraycopy(axePos, 0, fx.chainB, 0, 3)
                if (s4T > .16f) {
                    s4 = S4.PULL; s4T = 0f; System.arraycopy(axePos, 0, pullFrom, 0, 3)
                    dPullFrom[0] = D[0]; dPullFrom[1] = D[2]
                    audio.sfx("chain_pull")
                    animator.transition(.12f)
                }
            }
            S4.PULL -> {
                s4T += dt
                val u = smooth(s4T / .42f)
                anims.sample(anims["throw"], .73f, false, target)
                // o boneco vem junto até ficar à frente do guerreiro
                val stop = front(1.55f)
                D[0] = lerpf(dPullFrom[0], stop[0], u); D[2] = lerpf(dPullFrom[1], stop[2], u)
                for (i in 0 until 3) axePos[i] = lerpf(pullFrom[i], handR[i], u)
                axePos[1] = lerpf(pullFrom[1], handR[1], u)
                axeSpin -= dt * 22f
                fx.chainOn = true; System.arraycopy(handR, 0, fx.chainA, 0, 3); System.arraycopy(axePos, 0, fx.chainB, 0, 3)
                if (Math.random() < .5) fx.sprite(floatArrayOf(D[0], .1f, D[2]), Fx.SMOKE, 1, .5f, 1.2f, .6f, Fx.DUST, .35f, Fx.M_SMOKE)
                if (s4T >= .42f) catchAxe(true)
            }
            S4.BACK -> {
                s4T += dt
                val u = smooth(s4T / .38f)
                anims.sample(anims["throw"], .73f, false, target)
                for (i in 0 until 3) axePos[i] = lerpf(pullFrom[i], handR[i], u)
                axeSpin -= dt * 26f
                if (s4T >= .38f) catchAxe(false)
            }
        }
        if (axeFlying) {
            // matriz do machado voando: gira em torno do eixo lateral
            val m = FloatArray(16); M4.trs(m, axePos[0], axePos[1], axePos[2], aimYaw, K * skel.rig.wScale)
            val r = FloatArray(16); M4.id(r)
            val c = cos(axeSpin); val s = sin(axeSpin)
            r[5] = c; r[6] = s; r[9] = -s; r[10] = c
            val sc = FloatArray(16); M4.id(sc); sc[0] = skel.rig.wsx; sc[10] = skel.rig.wsz
            val t = FloatArray(16); M4.mul(t, m, r); M4.mul(axeM, t, sc)
            // centraliza o cabo (o pivô da malha fica no punho)
            axeM[12] -= (axeM[4] * .45f); axeM[13] -= (axeM[5] * .45f); axeM[14] -= (axeM[6] * .45f)
        }
    }

    private fun catchAxe(hit: Boolean) {
        axeFlying = false; fx.chainOn = false
        audio.sfx("axe_hit1", .5f, 1.2f)
        s4 = S4.OFF
        if (hit) {
            val evs = listOf(
                Ev(.01f) { trailsOn = 1f; trailRed = false },
                Ev(.4f) { audio.sfx("swing1", .9f) },
                Ev(.55f) { meleeHit(2.8f, 1.6f, 120, 1.4f); gold = .6f }
            )
            startAct(Act("s4b", listOf(Seg("attack_08", 0f, -1f, .14f)), evs, listOf(Tk(.6f, 1.1f) { _, p -> gold = max(0f, .6f * (1 - p)) })), .14f)
        } else {
            mode = Mode.LOCO; animator.transition(.25f)
            gold = 0f
        }
    }

    // ---------------- boneco ----------------
    private fun dummyLunge() {
        val dx = P[0] - D[0]; val dz = P[2] - D[2]; val l = max(hypot(dx, dz), .01f)
        dTiltV[0] += dx / l * 6f; dTiltV[1] += dz / l * 6f
        audio.sfx("swing3", .7f, .8f)
    }

    private fun updateDummy(dt: Float) {
        dFlash = max(0f, dFlash - dt * 5f)
        // mola do balanço
        for (i in 0 until 2) {
            dTiltV[i] += (-dTilt[i] * 60f - dTiltV[i] * 5.5f) * dt
            dTilt[i] += dTiltV[i] * dt
            dTilt[i] = clampf(dTilt[i], -.7f, .7f)
        }
        // empurrão
        if (abs(dPushV[0]) + abs(dPushV[1]) > .01f) {
            D[0] += dPushV[0] * dt; D[2] += dPushV[1] * dt
            val k = exp(-7f * dt); dPushV[0] *= k; dPushV[1] *= k
            val r = hypot(D[0], D[2]); if (r > 13.5f) { D[0] *= 13.5f / r; D[2] *= 13.5f / r }
        }
        // vira devagar para o jogador
        if (dAlive()) dYaw += wrapA(atan2(P[0] - D[0], P[2] - D[2]) - dYaw) * min(1f, dt * 2f)
        if (dStun > 0f) dStun -= dt
        // morte e retorno
        if (!dAlive()) {
            dDead += dt
            dFall = smooth(dDead / .55f) * (if (dDead > 2.6f) 1f - smooth((dDead - 2.6f) / .6f) else 1f)
            if (dDead > 3.2f) { dDead = -1f; dFall = 0f; dHp = 1f; audio.sfx("ding", .5f) }
        } else if (time - dLastHit > 5f && dHp < 1f) dHp = min(1f, dHp + dt * .25f)
        // o boneco revida de vez em quando (o guerreiro sente o golpe)
        dAtkT -= dt
        if (dAtkT <= 0f) {
            dAtkT = rnd(7f, 10f)
            if (dAlive() && dStun <= 0f && mode == Mode.LOCO && s4 == S4.OFF && distD() < 2.4f) {
                dummyLunge()
                later(.18f) {
                    if (mode == Mode.LOCO && s4 == S4.OFF) {
                        startAct(Act("hurt", listOf(Seg(if (Random.nextBoolean()) "hit_body_front" else "hit_head_front", 0f, -1f, .06f)), emptyList(), emptyList(), .2f), .06f)
                        audio.sfx("hit_body", .9f)
                        if (Random.nextFloat() < .7f) audio.voice(if (Random.nextBoolean()) "argh" else "desgr", true)
                        shake(.3f)
                    }
                }
            }
        }
    }

    fun dummyFall() = dFall

    // ---------------- efeitos ----------------
    private val pending = ArrayList<Pair<Float, () -> Unit>>()
    private fun later(d: Float, f: () -> Unit) { pending.add(Pair(time + d, f)) }

    private fun updateTrails(dt: Float) {
        val on = trailsOn > 0f && mode == Mode.ACT
        for (s in 0 until 2) {
            val t = fx.trails[s]
            if (on) {
                M4.xf(wM[s], 0, -0.206f, .34f, 0f, v3)
                M4.xf(wM[s], 0, -0.206f, .78f, 0f, v3b)
                if (s == 1 && axeFlying) { t.n = 0; continue }
                t.push(v3, v3b, fx.time + dt)
                val c = if (trailRed) Fx.RED else Fx.GOLD
                System.arraycopy(c, 0, t.c, 0, 3); t.w = if (trailRed) 1f else .85f
            }
        }
        fx.trailAge = .2f
    }

    private fun updateFx(dt: Float) {
        // pendentes
        if (pending.isNotEmpty()) {
            val now = ArrayList<() -> Unit>()
            val it = pending.iterator()
            while (it.hasNext()) { val p = it.next(); if (p.first <= time) { now.add(p.second); it.remove() } }
            now.forEach { it() }
        }
        if (auraT > 0f) {
            auraT -= dt
            fx.auraTick(floatArrayOf(P[0], 0f, P[2]), dt, min(1f, auraT * 2f))
            if (auraT <= 0f) { audio.stop("aura") }
        } else red = max(0f, red - dt * 1.5f)
        if (act == null && s4 == S4.OFF && auraT <= 0f) gold = max(0f, gold - dt * 2f)
        fx.teleOn = if (act?.id == "s3") fx.teleOn else 0f
        if (!axeFlying) fx.chainOn = false
        fx.update(dt)
    }

    fun torchTick(torches: List<FloatArray>, dt: Float) {
        for (t in torches) {
            if (Math.random() < dt * 22f) {
                val p = fx.sprite(floatArrayOf(t[0] + rnd(-.05f, .05f), t[1], t[2] + rnd(-.05f, .05f)), Fx.FLAME, 4, rnd(.28f, .4f), .12f, rnd(.35f, .55f), floatArrayOf(1f, .55f, .2f), .9f, Fx.M_TINT, Fx.Orient.VBILL, rot = 0f)
                p.vel[1] = rnd(.4f, .9f)
            }
            if (Math.random() < dt * 3f) {
                val p = fx.sprite(floatArrayOf(t[0], t[1] + .3f, t[2]), Fx.SPARK, 1, .04f, .01f, 1f, Fx.GOLD2, 1f)
                p.vel[1] = rnd(.8f, 1.6f); p.vel[0] = rnd(-.3f, .3f)
            }
            if (Math.random() < dt * 6f) fx.sprite(floatArrayOf(t[0], t[1] + .15f, t[2]), Fx.GLOW, 1, .9f, 1f, .3f, floatArrayOf(1f, .5f, .18f), .25f)
        }
    }

    // ---------------- câmera ----------------
    fun shake(a: Float) { shakeA = max(shakeA, a) }

    private fun updateCamera(dt: Float) {
        camDistSm += (camDist - camDistSm) * min(1f, dt * 8f)
        val lift = 1.25f + (camDistSm - 6f) * .03f
        val tx = P[0]; val ty = P[1] + lift; val tz = P[2]
        camTarget[0] += (tx - camTarget[0]) * min(1f, dt * 10f)
        camTarget[1] += (ty - camTarget[1]) * min(1f, dt * 10f)
        camTarget[2] += (tz - camTarget[2]) * min(1f, dt * 10f)
        val cp = cos(camPitch); val sp = sin(camPitch)
        eye[0] = camTarget[0] - sin(camYaw) * cp * camDistSm
        eye[1] = max(.3f, camTarget[1] + sp * camDistSm)
        eye[2] = camTarget[2] - cos(camYaw) * cp * camDistSm
        System.arraycopy(camTarget, 0, look, 0, 3)
        if (shakeA > .001f) {
            val s = shakeA * .09f
            eye[0] += rnd(-s, s); eye[1] += rnd(-s, s); eye[2] += rnd(-s, s)
            look[0] += rnd(-s, s) * .5f; look[1] += rnd(-s, s) * .5f
            shakeA *= exp(-dt * 7f)
        }
    }

    fun dummyModel(out: FloatArray) {
        // T(D) * Rot(tilt) * RotY(dYaw)
        val fall = dFall * 1.45f
        val tx = dTilt[0] * .5f + (if (fall > 0f) sin(dYaw + PI.toFloat()) * fall else 0f)
        val tz = dTilt[1] * .5f + (if (fall > 0f) cos(dYaw + PI.toFloat()) * fall else 0f)
        val ang = hypot(tx, tz)
        val r = FloatArray(16); M4.id(r)
        if (ang > 1e-4f) {
            // eixo = up × direção da inclinação
            val ax = tz / ang; val az = -tx / ang
            val c = cos(ang); val s = sin(ang); val t = 1 - c
            r[0] = t * ax * ax + c; r[1] = s * az; r[2] = t * ax * az
            r[4] = -s * az; r[5] = c; r[6] = s * ax
            r[8] = t * ax * az; r[9] = -s * ax; r[10] = t * az * az + c
        }
        val ty = FloatArray(16); M4.trs(ty, 0f, 0f, 0f, dYaw, 1f)
        val tr = FloatArray(16); M4.id(tr); tr[12] = D[0]; tr[13] = 0f; tr[14] = D[2]
        val tmp = FloatArray(16); M4.mul(tmp, tr, r); M4.mul(out, tmp, ty)
    }

    fun isHolding4() = s4 == S4.HOLD
    fun dummyAlive() = dAlive()
    fun dummyStunned() = dStun > 0f
}
