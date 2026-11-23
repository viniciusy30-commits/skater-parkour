package com.skaterparkour.app

import kotlin.math.*
import kotlin.random.Random

/**
 * Campo de treino — lógica do jogo. As habilidades seguem exatamente as linhas do tempo do visualizador HTML
 * (mesmos clipes, tempos, efeitos FX3, rastro TRL, sangue BLD, ondas de choque e sons).
 * Mundo em metros; TRL/BLD/onda de choque usam o espaço "unidade" do HTML (metros / 1,8).
 */
class Game(private val anims: Anims, private val skel: Skeleton, val fx: Fx3, val trl: Trl, val bld: Bld, val sw: Sw, private val audio: Audio) {
    companion object {
        const val K = 1.8f
        val BSEG = arrayOf(floatArrayOf(0f, 1.15f), floatArrayOf(1.15f, 1.75f), floatArrayOf(1.75f, 2.55f), floatArrayOf(2.55f, 4.63f))
        val SKILLS = arrayOf("basic", "s1", "s2", "s3", "s4", "ult")
        val CD = floatArrayOf(1f, 6f, 10f, 12f, 12f, 60f)
        val STRAFE = arrayOf("front", "frontL45", "left", "backL45", "back", "backR45", "right", "frontR45")
        val EDG = arrayOf(v3(-0.206f, 0.36f, 0f), v3(-0.206f, 0.76f, 0f), v3(0.241f, 0.36f, 0f), v3(0.241f, 0.76f, 0f))
        val HP = v3(0f, .6f, 0f)
        val C3T = floatArrayOf(.92f, 1.75f, 2.55f, 3.55f)
        const val TREL = .25f; const val TFLY = .3f; const val THOLD = .12f; const val TPULL = .5f
        val LOWER = (64..79).toList() + listOf(0, 1)
    }

    // ---------- jogador ----------
    val P = floatArrayOf(0f, 0f, 4.2f)
    var yaw = PIf
    private var yawTarget = yaw
    private val vel = FloatArray(2)
    private var speedSm = 0f
    private var phase = 0f
    private var idleT = 0f
    var lock = false
    var time = 0f

    private enum class Mode { LOCO, SKILL, ROLL, JUMP, HURT }
    private var mode = Mode.LOCO
    private var modeT = 0f
    private var rollDir = 0f
    private var rollClip = "roll_front"

    // ---------- câmera ----------
    var camYaw = PIf
    var camPitch = .36f
    var camDist = 7.5f
    private var camDistSm = 7.5f
    private val camTarget = floatArrayOf(0f, 1.2f, 4.2f)
    val eye = FloatArray(3)
    val look = FloatArray(3)
    var shakeA = 0f; var flashA = 0f; var vigA = 0f

    // ---------- alvo ----------
    var dHp = 100f
    private var dAtkT = 7f
    private var stunT = 0f

    // ---------- habilidade em execução (igual ao skRun do HTML) ----------
    private class Seg(val clip: String, val start: Float = 0f, val end: Float = 0f, val blend: Float = 0f)
    private abstract class Ev
    private class At(val at: Float, val fn: (Float) -> Unit) : Ev()
    private class Rg(val from: Float, val to: Float, val fn: (Float, Float) -> Unit) : Ev()
    private inner class SkRun(val id: String, val seq: List<Seg>, val ev: List<Ev>) {
        var idx = 0; val offs = FloatArray(seq.size + 1); var prev = -1f
        init { for (i in seq.indices) { val c = anims[seq[i].clip]; offs[i + 1] = offs[i] + (if (seq[i].end > 0f) seq[i].end else c.dur) - seq[i].start } }
    }
    private var skRun: SkRun? = null
    private var t = 0f                 // tempo do clipe atual
    private var cur = "idle_00"
    private var hitstop = 0f
    private var tsCur = 1f
    private val target = Pose(); private val tmpA = Pose(); private val tmpB = Pose(); private val tmpC = Pose(); private val tmpD = Pose(); private val tmpE = Pose()
    val animator = Animator(anims)

    var red = 0f; var gold = 0f
    private var s2Push = 0f; private var s2Lunge = 0f; private var s2Home: V? = null
    private var c3prev = -1f; private var sktPrev = -1f
    private var bqI = 0; private var bqQ = 0; private var bqLast = -9f; private var bqEnd = -9f
    private var bN = 0; private var bNext = 2 + Random.nextInt(2)

    // segurar Skill 4
    private var holdOn = false; private var holdFreeze = false; private var holdEl = 0f; private var holdMiss = false
    var aimYaw = 0f
    // arremesso (THR)
    private var thrOn = 0; private var thrPh = 0; private val thrM = FloatArray(16); private var thrGlow = 0f
    private var thrP0 = v3(0f, 0f, 0f); private var thrT = v3(0f, 0f, 0f); private var thrDir = v3(0f, 0f, 1f); private var thrAx = v3(1f, 0f, 0f)
    private var thrR0 = FloatArray(9); private var thrRh = FloatArray(9); private var thrTh = 0f; private var thrTc = 0f

    // cooldowns
    val cdReady = FloatArray(6)
    var noCd = false

    class Txt(val x: Float, val y: Float, val z: Float, val v: Int, val crit: Boolean, val col: Int, var t: Float = 0f)
    val texts = ArrayList<Txt>()

    // matrizes
    val charM = FloatArray(16)
    val wM = arrayOf(FloatArray(16), FloatArray(16))
    val axeM = FloatArray(16)
    val axeFlying get() = thrOn == 1
    private val wTmp = FloatArray(16)
    private val wU = arrayOf(FloatArray(16), FloatArray(16))
    private val tips = arrayOf(v3(0f, 1f, 0f), v3(0f, 1f, 0f))
    private val blades = arrayOf(v3(0f, 0f, 0f), v3(0f, 0f, 0f))
    private val ev = ArrayList<String>()
    private val camIn = FloatArray(3)
    private var entered = false

    // rastro (vfxTick)
    private class SwG { var on = false; var pk = 0f; var kd = 0f }
    private val swG = arrayOf(SwG(), SwG())
    private val prevTip = arrayOfNulls<Array<V>>(2)
    private var vLastT = 0f; private var vLastCur = ""

    fun cdLeft(i: Int) = if (noCd) 0f else max(0f, cdReady[i] - time)
    fun dummyStunned() = stunT > 0f
    val D: V get() = v3(bld.E[0] * K, 0f, bld.E[2] * K)

    init {
        bld.E = v3(0f, 0f, 0f)
        bld.onImpact = { c, d, k -> onImpact(c, d, k) }
    }

    // =====================================================================
    fun update(dtRaw: Float) {
        val dt = min(dtRaw, 1f / 15f)
        time += dt
        if (!entered && time > .6f) { entered = true; audio.voice("enter") }
        Input.take(camIn, ev)
        handleCam()
        for (e in ev) handleEvent(e)
        ev.clear()
        if (holdFreeze) holdEl += dt

        // tempo da animação (hitstop e congelamento da Skill 4)
        tsCur = if (holdFreeze) 0f else if (hitstop > 0f) { hitstop -= dt; .06f } else 1f
        val run = skRun
        if (run != null) {
            t += dt * tsCur
            val c = anims[cur]
            val it = run.seq[run.idx]
            if ((it.end > 0f && t >= it.end) || t >= c.dur) skClipEnd()
        }

        when (mode) {
            Mode.LOCO -> loco(dt, target)
            Mode.ROLL -> roll(dt)
            Mode.JUMP -> jump(dt)
            Mode.HURT -> hurt(dt)
            Mode.SKILL -> skillPose(dt)
        }
        yaw += wrapA(yawTarget - yaw) * min(1f, dt * (if (mode == Mode.SKILL) 16f else 11f))

        animator.apply(target, if (skRun != null && !holdOn) dt * tsCur else dt)
        skel.solve(animator.out)
        M4.trs(charM, P[0], P[1], P[2], yaw, K)
        for (s in 0 until 2) {
            skel.weapon(s, wTmp); M4.mul(wM[s], charM, wTmp)
            for (i in 0 until 16) wU[s][i] = if (i % 4 == 3) wM[s][i] else wM[s][i] / K
        }

        vfxTick()
        skTick(dt)
        updateCamera(dt)
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
            "s4up" -> s4Up()
            "roll" -> rollPress()
            "jump" -> jumpPress()
            "lock" -> lock = !lock
            "nocd" -> noCd = !noCd
        }
    }

    private fun canAct() = skRun == null && mode != Mode.ROLL && mode != Mode.JUMP
    private fun ready(i: Int) = noCd || time >= cdReady[i]
    private fun startCd(i: Int) { cdReady[i] = time + CD[i] }
    private fun distD(): Float { val d = D; return hypot(d[0] - P[0], d[2] - P[2]) }
    private fun yawToD(): Float { val d = D; return atan2(d[0] - P[0], d[2] - P[2]) }
    private fun faceTarget() { if (lock || distD() < 4.5f) { yaw = yawToD(); yawTarget = yaw } }

    // ---------------- locomoção ----------------
    private fun stickDir(): FloatArray? {
        val jx = Input.jx; val jy = Input.jy
        val m = hypot(jx, jy)
        if (m < .12f) return null
        val fx0 = sin(camYaw); val fz = cos(camYaw)
        val rx = -fz; val rz = fx0
        val dx = fx0 * jy + rx * jx; val dz = fz * jy + rz * jx
        return floatArrayOf(atan2(dx, dz), min(1f, (m - .12f) / .8f))
    }

    /** Locomoção em blend tree. strafeYaw != null: anda de lado olhando para essa direção. */
    private fun loco(dt: Float, out: Pose, strafeYaw: Float? = null, maxSp: Float = -1f) {
        val sd = stickDir()
        val locked = strafeYaw != null || lock
        val maxV = if (maxSp > 0f) maxSp else if (locked) 4.1f else 5.2f
        val minV = if (locked) .85f else 1f
        val want = if (sd != null) min(maxV, minV + (maxV - minV) * sd[1] * sd[1]) else 0f
        speedSm += (want - speedSm) * min(1f, dt * (if (want > speedSm) 7f else 10f))
        val dir = sd?.get(0) ?: atan2(vel[0], vel[1])
        if (speedSm > .02f) {
            val tvx = sin(dir) * speedSm; val tvz = cos(dir) * speedSm
            vel[0] += (tvx - vel[0]) * min(1f, dt * 12f); vel[1] += (tvz - vel[1]) * min(1f, dt * 12f)
        } else { vel[0] = 0f; vel[1] = 0f }
        move(vel[0] * dt, vel[1] * dt)
        val sp = hypot(vel[0], vel[1])
        if (strafeYaw != null) yawTarget = strafeYaw
        else if (lock) yawTarget = yawToD() else if (sd != null && sp > .2f) yawTarget = dir

        idleT += dt
        anims.sample(anims["idle_00"], idleT, true, tmpA)
        if (sp < .05f) { out.copy(tmpA); return }
        if (!locked) {
            val walk = anims["walk_front"]; val runC = anims["move_run_front"]
            val wr = clampf((sp - 1.04f) / (5.39f - 1.04f), 0f, 1f)
            val cyc = lerpf(walk.dur, runC.dur, wr)
            phase = (phase + dt / cyc * max(sp / lerpf(1.04f, 5.39f, wr), .55f)) % 1f
            anims.sample(walk, phase * walk.dur, true, tmpB)
            anims.sample(runC, phase * runC.dur, true, tmpC)
            Anims.blend(tmpB, tmpC, wr, tmpB)
        } else {
            val rel = wrapA(atan2(vel[0], vel[1]) - yaw)
            var a = rel / (PIf / 4f); if (a < 0) a += 8f
            val i0 = a.toInt() % 8; val i1 = (i0 + 1) % 8; val w = a - a.toInt()
            val wr = clampf((sp - .85f) / (4.1f - .85f), 0f, 1f)
            val cyc = lerpf(anims["move_walk_strafe_front"].dur, anims["move_run_strafe_front"].dur, wr)
            phase = (phase + dt / cyc * max(sp / lerpf(.85f, 4.1f, wr), .55f)) % 1f
            fun dirPose(i: Int, o: Pose) {
                val cw = anims["move_walk_strafe_" + STRAFE[i]]; val cr = anims["move_run_strafe_" + STRAFE[i]]
                anims.sample(cw, phase * cw.dur, true, tmpD); anims.sample(cr, phase * cr.dur, true, o)
                Anims.blend(tmpD, o, wr, o)
            }
            dirPose(i0, tmpB); dirPose(i1, tmpC)
            Anims.blend(tmpB, tmpC, w, tmpB)
        }
        Anims.blend(tmpB, tmpA, 1f - clampf(sp / .9f, 0f, 1f), out)
    }

    private fun move(dx: Float, dz: Float) {
        var nx = P[0] + dx; var nz = P[2] + dz
        val d = D; val ex = nx - d[0]; val ez = nz - d[2]; val de = hypot(ex, ez)
        if (de < .75f) { nx = d[0] + ex / max(de, 1e-3f) * .75f; nz = d[2] + ez / max(de, 1e-3f) * .75f }
        val r = hypot(nx, nz)
        if (r > 14.8f) { nx *= 14.8f / r; nz *= 14.8f / r }
        P[0] = nx; P[2] = nz
    }

    private fun rollPress() {
        if (!canAct() && skRun?.id != "basic") return
        if (skRun != null) { skRun = null; trl.clear() }
        val sd = stickDir()
        rollDir = sd?.get(0) ?: yaw
        rollClip = "roll_front"
        if (lock) {
            val rel = wrapA(rollDir - yaw)
            rollClip = when { abs(rel) < PIf / 4 -> "roll_front"; abs(rel) > 3 * PIf / 4 -> "roll_back"; rel < 0 -> "roll_right"; else -> "roll_left" }
        } else { yawTarget = rollDir; yaw = rollDir }
        mode = Mode.ROLL; modeT = 0f
        animator.transition(.1f)
        audio.sfx("swing2", .7f, .75f)
    }

    private fun roll(dt: Float) {
        modeT += dt
        val u = modeT / .62f
        val sp = if (u < .85f) 5.2f * (1 - u * .6f) else 0f
        move(sin(rollDir) * sp * dt, cos(rollDir) * sp * dt)
        anims.sample(anims[rollClip], modeT, false, target)
        if (modeT >= .62f) { mode = Mode.LOCO; animator.transition(.22f); vel[0] = 0f; vel[1] = 0f; speedSm = 0f }
    }

    private fun jumpPress() {
        if (!canAct()) return
        mode = Mode.JUMP; modeT = 0f; animator.transition(.12f)
        audio.sfx("swing1", .5f, .8f)
    }

    private fun jump(dt: Float) {
        modeT += dt
        val sd = stickDir()
        if (sd != null) { val v = sd[1] * 3.2f; move(sin(sd[0]) * v * dt, cos(sd[0]) * v * dt); if (!lock) yawTarget = sd[0] }
        val c = anims["jump"]
        anims.sample(c, modeT, false, target)
        if (modeT >= c.dur - .05f) { mode = Mode.LOCO; animator.transition(.2f) }
    }

    private var hurtClip = "hit_body_front"
    private fun hurt(dt: Float) {
        modeT += dt
        val c = anims[hurtClip]
        anims.sample(c, modeT, false, target)
        if (modeT >= c.dur) { mode = Mode.LOCO; animator.transition(.25f) }
    }

    // ---------------- habilidades (skStart / skClipEnd / skTick do HTML) ----------------
    private fun setClip(name: String, blend: Float, start: Float) {
        if (mode == Mode.SKILL && skRun != null && cur != "idle_00") animator.transitionClip(anims[cur], t, blend) else animator.transition(blend)
        cur = name; t = start
    }

    private fun skillPose(dt: Float) {
        val run = skRun ?: run { mode = Mode.LOCO; return }
        anims.sample(anims[cur], t, false, target)
        if (run.id == "s4" && holdOn) {
            // segurando a Skill 4: braços erguidos com o machado, pernas andando de verdade
            loco(dt, tmpE, aimYaw, 2.4f)
            for (k in LOWER) for (i in 0 until 4) target.q[k * 4 + i] = tmpE.q[k * 4 + i]
            for (i in 0 until 3) target.pel[i] = tmpE.pel[i]
            if (holdFreeze) {
                anims.sample(anims["throw"], .14f + .012f * sin(time * 3f), false, tmpD)
                for (k in 2..9) for (i in 0 until 4) target.q[k * 4 + i] = tmpD.q[k * 4 + i]
            }
        } else { vel[0] = 0f; vel[1] = 0f; speedSm = 0f }
    }

    private fun skStart(id: String): Boolean {
        s2Push = 0f; s2Lunge = 0f; s2Home = null
        if (id == "s1") audio.sfx("s1_charge", 1f, 1f, .05f)
        if (id == "s3") { audio.sfx("s3_spin", 1f, 1f, .35f); audio.sfx("s3_rise", .8f, 1f, .25f) }
        gold = 0f
        when (id) {
            "s2" -> audio.voice("hah")
            "basic" -> later(.06f) { basicLine() }
            "s1", "s3", "s4" -> later(.06f) { audio.voice(id) }
            "ult" -> later(.25f) { audio.voice("buff") }
        }
        thrReset(); c3prev = -1f; sktPrev = -1f
        val seq: List<Seg>; val evs: List<Ev>; val pal: String
        when (id) {
            "basic" -> { val g = BSEG[bqI]; seq = listOf(Seg("Combo_04", g[0], g[1])); evs = emptyList(); pal = "gold" }
            "s1" -> { seq = listOf(Seg("attack_03", 0f, 1.75f)); evs = evS1(); pal = "gold" }
            "s2" -> { seq = listOf(Seg("defence", 0f, .42f), Seg("attack_07", .15f, 0f, .2f)); evs = evS2(); pal = "gold" }
            "s3" -> { seq = listOf(Seg("attack_05", 0f, 2.7f)); evs = emptyList(); pal = "earth" }
            "s4" -> { seq = listOf(Seg("throw"), Seg("attack_08", 0f, 0f, .14f)); evs = emptyList(); pal = "gold" }
            else -> { seq = listOf(Seg("buff_01", 0f, 1.3f), Seg("Combo_03", .3f, 0f, .2f)); evs = evUlt(); pal = "ult" }
        }
        // vfxClear + FX3.reset (como no HTML)
        trl.clear(); bld.clear(); swG.forEach { it.on = false; it.pk = 0f; it.kd = 0f }; prevTip[0] = null; prevTip[1] = null
        fx.reset(); fx.setPal(pal); sw.clear(); trl.TL = .55f; shakeA = 0f; hitstop = 0f
        if (id == "s4") fx.aimFx { holdOn }
        skRun = SkRun(id, seq, evs)
        mode = Mode.SKILL
        setClip(seq[0].clip, .22f, seq[0].start)
        vLastCur = ""
        return true
    }

    private fun skClipEnd() {
        val r = skRun ?: return
        if (r.id == "basic" && r.idx == r.seq.size - 1 && bqQ == 1) { bqQ = 0; bqI = (bqI + 1) % BSEG.size; skStart("basic"); return }
        if (r.id == "basic") bqEnd = time
        r.idx++
        if (r.idx < r.seq.size) {
            val it = r.seq[r.idx]
            setClip(it.clip, if (it.blend > 0f) it.blend else .22f, it.start)
            return
        }
        animator.transitionClip(anims[cur], t, .45f)
        skRun = null; mode = Mode.LOCO
        cur = "idle_00"; t = 0f
        holdOn = false; holdFreeze = false
    }

    private fun basicLine() {
        bN++
        if (bN >= bNext) { bN = 0; bNext = 2 + Random.nextInt(2); audio.voice("cai") } else audio.voice("hah")
    }

    private fun basicPress() {
        if (time - bqLast < 1f && !noCd) return
        if (skRun?.id == "basic") { bqLast = time; startCd(0); bqQ = 1; return }
        if (!canAct()) return
        bqLast = time
        bqI = if (time - bqEnd > 2.5f) 0 else (bqI + 1) % BSEG.size
        faceTarget()
        skStart("basic"); startCd(0)
    }

    private fun useSkill(id: String) {
        val i = SKILLS.indexOf(id)
        if (!ready(i) || !canAct()) return
        faceTarget()
        vel[0] = 0f; vel[1] = 0f
        if (skStart(id)) startCd(i)
    }

    // ---- pontos de referência (Hl/Fl/Rl/tips/blades do HTML), em metros ----
    private fun skRootU(): V { M4.mul(wTmp, 0, charM, 0, skel.world, 16); return v3(wTmp[12] / K, wTmp[13] / K, wTmp[14] / K) }
    private fun Hl(d: Float, y: Float): V { val r = skRootU(); return v3(r[0] * K + sin(yaw) * d, y, r[2] * K + cos(yaw) * d) }
    private fun Rl() = Hl(0f, 0f)

    private fun evS1(): List<Ev> = listOf(
        At(.72f) {
            val h = Hl(1.15f, 1.0f); val T = .2f
            shake(.15f); audio.sfx("s1_launch")
            val a = tips[0]; val b = tips[1]; val f0 = Hl(.35f, 0f); f0[1] = clampf((a[1] + b[1]) / 2, .7f, 1.3f)
            fx.xBlade2(f0, h, T)
            fx.later(T) { audio.sfx("s1_impact"); dmg(20f); shake(.5f); hitstop = .06f }
        })

    private fun evS2(): List<Ev> = listOf(
        At(.04f) { audio.sfx("s2_guard"); fx.guardAura(.95f) { k -> tips[k] } },
        Rg(.04f, .3f) { _, p -> gold = min(1f, p * 1.2f) },
        At(.2f) { s2Lunge = .16f },
        At(.34f) {
            val a = tips[0]; val b = tips[1]
            fx.deflectFx(v3((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2))
            audio.sfx("parry"); audio.sfx("impact", .8f); shake(.6f); hitstop = .07f; gold = 1f; bld.flash = .6f
        },
        At(1.02f) {
            fx.counterWave(Rl()); audio.voice("s2"); audio.sfx("s2_wave"); audio.sfx("s2_thud", .8f)
            dmg(25f); shake(1f); hitstop = .08f; s2Push = .32f
        },
        Rg(1.06f, 1.6f) { _, p -> gold = max(0f, 1 - p * 2) })

    private fun evUlt(): List<Ev> = listOf(
        At(.3f) {
            fx.bloodAura(5.75f); audio.sfx("ult_roar"); audio.sfx("ult_aura", 1f, 1f, .25f, "aura", 4.6f, 1f)
            flash(.3f); vig(1f); shake(1f)
        },
        Rg(1.35f, 30f) { _, _ -> ultC3() })

    private fun ultC3() {
        if (cur != "Combo_03") { c3prev = -1f; return }
        val tt = t
        if (c3prev < 0) { c3prev = tt; return }
        for (i in C3T.indices) {
            val T = C3T[i]
            if (c3prev < T && tt >= T) {
                val fin = i == C3T.size - 1
                val a = blades[0]; val b = blades[1]; val c = v3((a[0] + b[0]) / 2, 0f, (a[2] + b[2]) / 2); val far = hypot(a[0] - b[0], a[2] - b[2]) > .9f
                fun both(fn: (V, Float) -> Unit) { if (far) { fn(v3(a[0], 0f, a[2]), .72f); fn(v3(b[0], 0f, b[2]), .72f) } else fn(c, 1f) }
                if (fin) {
                    audio.sfx("ult_final"); audio.sfx("ult_debris", .9f); audio.sfx("ult_slam", .8f, .9f); dmg(30f)
                    both { p, k -> fx.ultSlamBig(p, 2.0f * k) }
                    sw.fire(c, 13f, 1.7f, v3(1f, .06f, .03f), 3f); sw.fire(c, 8.5f, 1.2f, v3(1f, .14f, .06f), 2.2f, .12f); sw.fire(c, 5f, .8f, v3(1f, .35f, .25f), 1.6f, .25f)
                    hitstop = .24f; shake(3f); vig(.8f)
                } else {
                    audio.sfx("ult_slam", .8f + i * .08f, rnd(.96f, 1.04f)); audio.sfx("ult_debris", .55f + i * .08f, rnd(.95f, 1.05f)); dmg(14f)
                    both { p, k -> fx.ultSlamBig(p, (1.35f + i * .15f) * k) }
                    sw.fire(c, 3.2f + i * .6f, .7f, v3(1f, .08f, .04f), 1.4f + i * .3f)
                    hitstop = .09f; shake(1.5f + i * .3f); vig(.35f)
                }
            }
        }
        c3prev = tt
    }

    /** Skill 3: impacto no tempo exato do golpe (SKT do HTML) */
    private fun skTimed() {
        val r = skRun ?: return
        if (r.id != "s3" || cur != "attack_05") { sktPrev = -1f; return }
        val T = 1.35f; val Pw = .62f
        if (sktPrev >= 0 && sktPrev < T && t >= T) {
            val a = blades[0]; val b = blades[1]; val c = v3((a[0] + b[0]) / 2, 0f, (a[2] + b[2]) / 2)
            audio.sfx("s3_slam"); audio.sfx("s3_boom", .85f); dmg(22f)
            if (hypot(a[0] - b[0], a[2] - b[2]) > .9f) { fx.tintSlam(v3(a[0], 0f, a[2]), Pw * .75f, fx.earthT); fx.tintSlam(v3(b[0], 0f, b[2]), Pw * .75f, fx.earthT) }
            else fx.tintSlam(c, Pw, fx.earthT)
            sw.fire(c, .9f + Pw * 1.3f, .6f, v3(1f, .5f, .15f), 1.2f + Pw * .4f)
            hitstop = .08f; shake(2.6f * .6f)
        }
        sktPrev = t
    }

    private fun s2LungeTick(dt: Float) {
        if (s2Lunge <= 0f) { val h = s2Home; if (h != null && !(s2Push > 0f)) { bld.E = h.copyOf(); s2Home = null }; return }
        if (s2Home == null) s2Home = bld.E.copyOf()
        val home = s2Home!!
        s2Lunge -= dt
        val r = skRootU(); val q = 1 - max(0f, s2Lunge) / .16f; val k = sin(PIf * q) * .32f
        val dx = home[0] - r[0]; val dz = home[2] - r[2]; val d = hypot(dx, dz).let { if (it == 0f) 1f else it }
        bld.E = v3(home[0] - dx / d * k, 0f, home[2] - dz / d * k); bld.tilt = -.25f * sin(PIf * q)
    }

    private fun s2PushTick(dt: Float) {
        if (s2Push <= 0f) return
        s2Push -= dt
        val r = skRootU(); var dx = bld.E[0] - r[0]; var dz = bld.E[2] - r[2]; val d = hypot(dx, dz).let { if (it == 0f) 1f else it }; dx /= d; dz /= d
        val v = dt * 9 * max(0f, s2Push / .32f)
        if (d < 3) bld.E = v3(bld.E[0] + dx * v, 0f, bld.E[2] + dz * v)
        if (Math.random() < .6) fx.dustFx(v3(bld.E[0] * K, 0f, bld.E[2] * K), .4f, 1)
    }

    // ---------------- Skill 4 ----------------
    private fun s4Down() {
        if (!ready(4) || !canAct()) return
        faceTarget(); aimYaw = yaw
        holdOn = true; holdFreeze = false; holdEl = 0f
        skStart("s4"); startCd(4)
    }
    private fun s4Up() { holdOn = false; holdFreeze = false }

    private fun thrReset() { thrOn = 0; thrGlow = 0f; thrPh = 0 }

    private fun m3(M: FloatArray) = floatArrayOf(M[0], M[1], M[2], M[4], M[5], M[6], M[8], M[9], M[10])
    private fun rotAx(ax: V, a: Float): FloatArray {
        val c = cos(a); val s = sin(a); val tt = 1 - c; val x = ax[0]; val y = ax[1]; val z = ax[2]
        return floatArrayOf(tt * x * x + c, tt * x * y + s * z, tt * x * z - s * y, tt * x * y - s * z, tt * y * y + c, tt * y * z + s * x, tt * x * z + s * y, tt * y * z - s * x, tt * z * z + c)
    }
    private fun mul3(A: FloatArray, B: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (c in 0 until 3) for (r in 0 until 3) o[c * 3 + r] = A[r] * B[c * 3] + A[3 + r] * B[c * 3 + 1] + A[6 + r] * B[c * 3 + 2]
        return o
    }
    private fun setM(R: FloatArray, Pp: V) {
        val M = thrM
        M[0] = R[0]; M[1] = R[1]; M[2] = R[2]; M[3] = 0f; M[4] = R[3]; M[5] = R[4]; M[6] = R[5]; M[7] = 0f; M[8] = R[6]; M[9] = R[7]; M[10] = R[8]; M[11] = 0f
        val hx = R[0] * HP[0] + R[3] * HP[1] + R[6] * HP[2]; val hy = R[1] * HP[0] + R[4] * HP[1] + R[7] * HP[2]; val hz = R[2] * HP[0] + R[5] * HP[1] + R[8] * HP[2]
        M[12] = Pp[0] - hx; M[13] = Pp[1] - hy; M[14] = Pp[2] - hz; M[15] = 1f
    }
    private fun tf(m: FloatArray, p: V) = v3(m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12], m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13], m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14])
    private fun handPosM() = v3(wM[1][12], wM[1][13], wM[1][14])

    private fun thrTick(dt: Float) {
        val r = skRun
        if (r == null || r.id != "s4") { if (thrOn != 0 || thrGlow > 0f) { thrOn = 0; thrGlow = max(0f, thrGlow - dt * 3) }; return }
        val ts = r.offs[r.idx] + t
        if (thrPh == 0 && holdOn) {
            if (ts >= .14f) holdFreeze = true
            if (holdFreeze) { thrGlow = min(1f, .6f + .4f * sin(time * 1000f / 90f)); aimTick(); if (holdEl > 5f) s4Up(); return }
        }
        if (thrPh == 0) {
            thrGlow = min(1f, ts / TREL)
            if (ts >= TREL) {
                thrPh = 1
                val m0 = wU[1].copyOf(); thrP0 = tf(m0, HP)
                val ru = skRootU()
                val dd = D; val ddx = dd[0] - P[0]; val ddz = dd[2] - P[2]; val L = hypot(ddx, ddz); val a = atan2(ddx, ddz)
                val hit = L < 10.5f && abs(wrapA(a - yaw)) < .22f
                holdMiss = !hit
                if (!hit) { val fx0 = sin(yaw); val fz0 = cos(yaw); thrDir = v3(fx0, 0f, fz0); val d = 9f / K; thrT = v3(ru[0] + fx0 * d, .5f, ru[2] + fz0 * d) }
                else { val E = bld.E; var dx = E[0] - ru[0]; var dz = E[2] - ru[2]; val n2 = hypot(dx, dz).let { if (it == 0f) 1f else it }; dx /= n2; dz /= n2; thrDir = v3(dx, 0f, dz); thrT = v3(E[0] - dx * .1f, .5f, E[2] - dz * .1f) }
                thrAx = v3(thrDir[2], 0f, -thrDir[0]); thrOn = 1; thrR0 = m3(m0)
                shake(.25f); audio.sfx("axe_whir", .9f); audio.sfx("chain_throw")
                fx.chain2({ handPosM() }, { vmul(tf(thrM, v3(0f, .05f, 0f)), K) }, { thrPh }, {
                    val rr = skRun; if (rr == null) 1f else clampf(((rr.offs[rr.idx] + t) - TREL) / TFLY, 0f, 1f)
                }, TPULL)
            }
        }
        if (thrPh == 1) {
            val u = min(1f, (ts - TREL) / TFLY)
            val Pp = v3(thrP0[0] + (thrT[0] - thrP0[0]) * u, thrP0[1] + (thrT[1] - thrP0[1]) * u + sin(PIf * u) * .12f, thrP0[2] + (thrT[2] - thrP0[2]) * u)
            val R = mul3(rotAx(thrAx, u * PIf * 2 * 2.25f), thrR0); setM(R, Pp); thrRh = R
            if (Math.random() < .9) fx.thrSpark(vmul(Pp, K))
            if (u >= 1) {
                thrPh = 2; thrTh = ts
                fx.thrHit(vmul(Pp, K)); audio.sfx("chain_snap_o")
                if (!holdMiss) {
                    audio.sfx("axe_hit2", 1f); dmg(15f)
                    stunT = 1.9f
                    fx.stunFx({ v3(bld.E[0] * K, 1.75f, bld.E[2] * K) }, 1.9f)
                }
                shake(.6f); hitstop = .06f
            }
        }
        if (thrPh == 2 && ts - thrTh >= THOLD) { thrPh = 3; audio.sfx("chain_pull") }
        if (thrPh == 3) {
            val u = min(1f, (ts - thrTh - THOLD) / TPULL); val e = u * u * (3 - 2 * u)
            val mh = wU[1]; val Ph = tf(mh, HP)
            val Pp = v3(thrT[0] + (Ph[0] - thrT[0]) * e, thrT[1] + (Ph[1] - thrT[1]) * e + sin(PIf * e) * .1f, thrT[2] + (Ph[2] - thrT[2]) * e)
            val Rr = mul3(rotAx(thrAx, -PIf * 2 * 2 * (1 - e)), m3(mh)); val k = min(1f, u * 4)
            val R = FloatArray(9) { i -> thrRh[i] + (Rr[i] - thrRh[i]) * k }; setM(R, Pp)
            if (!holdMiss) {
                val ru = skRootU(); val Dd = thrDir
                val pr = (Pp[0] - ru[0]) * Dd[0] + (Pp[2] - ru[2]) * Dd[2]; val want = max(.8f, pr + .1f)
                bld.E = v3(ru[0] + Dd[0] * want, 0f, ru[2] + Dd[2] * want)
            }
            if (Math.random() < .7) fx.thrSpark(vmul(Pp, K))
            if (u >= 1) { thrPh = 4; thrOn = 0; thrTc = ts; fx.thrCatch(vmul(Ph, K)); shake(.45f); audio.sfx("impact", .6f, 1.15f) }
        }
        if (thrPh == 4) thrGlow = max(0f, 1 - (ts - thrTc) / .5f)
        if (thrOn == 1) { for (i in 0 until 16) axeM[i] = if (i % 4 == 3) thrM[i] else thrM[i] * K }
    }

    /** GAME.aimTick: o corpo gira para a direção escolhida no arrasto */
    private fun aimTick() {
        if (Input.aimActive) {
            val ax = Input.aimX; val ay = Input.aimY
            val f0 = sin(camYaw); val fz = cos(camYaw)
            aimYaw = atan2(f0 * ay - fz * ax, fz * ay + f0 * ax)
        }
        yaw += wrapA(aimYaw - yaw) * .25f; yawTarget = yaw
    }

    // ---------------- vfxTick: rastro, acerto pelas lâminas ----------------
    private fun vfxTick() {
        val atk = skRun != null && (cur.startsWith("attack", true) || cur.startsWith("combo", true))
        val ad = t - vLastT
        if (cur != vLastCur || ad < -1e-4f || ad > .25f) { trl.trail[0].clear(); trl.trail[1].clear(); prevTip[0] = null; prevTip[1] = null; swG.forEach { it.on = false; it.pk = 0f; it.kd = 0f } }
        for (s in 0 until 2) {
            val tr = trl.trail[s]
            for (q in tr) q.age += max(ad, 0f)
            while (tr.isNotEmpty() && tr[0].age > trl.TL) tr.removeAt(0)
            if (!atk || !(ad > 0f)) continue
            val wm = if (s == 1 && thrOn == 1) thrM else wU[s]
            val Pq = Array(4) { tf(wm, EDG[it]) }
            val pt = prevTip[s]
            var sp = 0f
            if (pt != null) for (i in intArrayOf(1, 3)) sp = max(sp, vlen(vsub(Pq[i], pt[i])) / ad)
            val g = swG[s]
            if (!g.on && sp > 2.4f && sp >= g.pk) { g.on = true; if (s == 1 || !swG[1].on) whoosh(min(1.6f, sp / 5)) }
            if (g.on) { g.pk = max(g.pk, sp); if (sp < g.pk * .62f) g.on = false } else if (sp < 1.3f) g.pk = 0f
            val kt = if (g.on) clampf((sp - 1.4f) / 2.6f, 0f, 1f) else 0f
            g.kd += (kt - g.kd) * (if (kt > g.kd) .55f else .22f)
            val k = g.kd
            if (k > .015f) tr.add(Trl.Row(Pq, 0f, k))
            bld.hitTest(s, Pq, pt, k, ad, sp)
            prevTip[s] = Pq
        }
        vLastCur = cur; vLastT = t
    }

    private fun whoosh(k: Float) {
        val g = min(1.2f, .55f + .4f * k)
        audio.sfx(if (Random.nextBoolean()) "swing1" else "swing2", g, rnd(.9f, 1.1f))
        audio.sfx("swing3", g * .7f, rnd(.95f, 1.15f))
    }

    /** __IMP do HTML: som, dano e impacto visual quando a lâmina acerta o boneco */
    private fun onImpact(c: V, d: V, k: Float) {
        audio.sfx("axe_hit" + (1 + Random.nextInt(3)), .8f + .3f * min(1.4f, .7f + .5f * min(1f, k)), rnd(.93f, 1.07f))
        dmg(4 + 6 * min(1f, k))
        if (skRun?.id == "s1") return
        fx.impactFx(vmul(c, K), .75f + .55f * min(1f, k), v3(d[0], 0f, d[2]))
    }

    private fun dmg(n: Float) {
        dHp -= n
        val crit = n >= 20f
        synchronized(texts) {
            val E = bld.E
            texts.add(Txt(E[0] * K + rnd(-.3f, .3f), 1.9f + rnd(0f, .3f), E[2] * K + rnd(-.3f, .3f), (n * 10).roundToInt(), crit,
                if (red > .5f) 0xFFFF5A3C.toInt() else if (crit) 0xFFFFD25A.toInt() else 0xFFFFFFFF.toInt()))
        }
        if (dHp <= 0f) { dHp = 100f; later(.35f) { audio.voice(if (Random.nextBoolean()) "fraco" else "naopode") }; bld.flash = 1f }
    }

    // ---------------- skTick ----------------
    private fun skTick(dt: Float) {
        val ultOn = skRun?.id == "ult"
        red += ((if (ultOn) 1f else 0f) - red) * min(1f, dt * (if (ultOn) 5f else 1.5f))
        trl.red = red
        val sd = dt * tsCur
        trl.upd(sd); bld.upd(sd)
        shakeA *= exp(-dt * 6.5f); flashA *= exp(-dt * 5f); vigA = max(0f, vigA - dt / 1.6f)
        sw.tick(dt)
        val ru = skRootU(); fx.root[0] = ru[0] * K; fx.root[1] = 0f; fx.root[2] = ru[2] * K; fx.yaw = yaw
        for (k in 0 until 2) {
            val wm = if (k == 1 && thrOn == 1) axeM else wM[k]
            val tp = tf(wm, v3(0f, .6f, 0f)); tips[k][0] = tp[0]; tips[k][1] = tp[1]; tips[k][2] = tp[2]
            var lo: V? = null
            for (e in EDG) { val w = tf(wm, e); if (lo == null || w[1] < lo[1]) lo = w }
            System.arraycopy(lo!!, 0, blades[k], 0, 3)
        }
        val r = skRun
        if (r != null) {
            val now = r.offs[r.idx] + t; val pv = r.prev
            for (e in r.ev) {
                when (e) {
                    is At -> if (pv < e.at && now >= e.at) e.fn(now)
                    is Rg -> if (now >= e.from && now <= e.to) e.fn(sd, (now - e.from) / (e.to - e.from))
                }
            }
            r.prev = now
        }
        if (skRun != null) { skTimed(); thrTick(sd); s2LungeTick(sd); s2PushTick(sd) }
        if (skRun == null) { gold = max(0f, gold - dt * 2); if (thrOn != 0 || thrGlow > 0f) thrTick(dt) }
        if (stunT > 0f) stunT -= dt
        if (pending.isNotEmpty()) {
            val due = ArrayList<() -> Unit>(); val it = pending.iterator()
            while (it.hasNext()) { val p = it.next(); if (p.first <= time) { due.add(p.second); it.remove() } }
            due.forEach { it() }
        }
        fx.upd(if (holdFreeze) dt else sd)
        dummyAttack(dt)
    }

    private val pending = ArrayList<Pair<Float, () -> Unit>>()
    private fun later(d: Float, f: () -> Unit) { pending.add(Pair(time + d, f)) }

    /** de vez em quando o boneco revida se você ficar parado perto dele */
    private fun dummyAttack(dt: Float) {
        dAtkT -= dt
        if (dAtkT > 0f) return
        dAtkT = rnd(7f, 10f)
        if (mode == Mode.LOCO && distD() < 2.4f && stunT <= 0f) {
            val d = D; val dx = P[0] - d[0]; val dz = P[2] - d[2]; val l = max(hypot(dx, dz), .01f)
            bld.dirH = floatArrayOf(-dx / l, -dz / l); bld.tv -= 2.2f
            audio.sfx("swing3", .7f, .8f)
            later(.18f) {
                if (mode == Mode.LOCO) {
                    hurtClip = if (Random.nextBoolean()) "hit_body_front" else "hit_head_front"
                    mode = Mode.HURT; modeT = 0f; animator.transition(.06f)
                    audio.sfx("hit_body", .8f)
                    if (Random.nextFloat() < .7f) audio.voice(if (Random.nextBoolean()) "argh" else "desgr", true)
                    shake(.4f)
                }
            }
        }
    }

    fun torchTick(torches: List<FloatArray>, dt: Float) { for (tc in torches) fx.torch(tc, dt) }

    // ---------------- câmera ----------------
    fun shake(a: Float) { shakeA = max(shakeA, a) }
    private fun flash(a: Float) { flashA = max(flashA, a * .7f) }
    private fun vig(a: Float) { vigA = max(vigA, a) }

    private fun updateCamera(dt: Float) {
        camDistSm += (camDist - camDistSm) * min(1f, dt * 8f)
        val lift = 1.25f + (camDistSm - 6f) * .03f
        camTarget[0] += (P[0] - camTarget[0]) * min(1f, dt * 10f)
        camTarget[1] += (P[1] + lift - camTarget[1]) * min(1f, dt * 10f)
        camTarget[2] += (P[2] - camTarget[2]) * min(1f, dt * 10f)
        val cp = cos(camPitch); val sp = sin(camPitch)
        eye[0] = camTarget[0] - sin(camYaw) * cp * camDistSm
        eye[1] = max(.3f, camTarget[1] + sp * camDistSm)
        eye[2] = camTarget[2] - cos(camYaw) * cp * camDistSm
        System.arraycopy(camTarget, 0, look, 0, 3)
        if (shakeA > .003f) {
            val s = shakeA * .09f * K
            eye[0] += rnd(-.5f, .5f) * s; eye[1] += rnd(-.5f, .5f) * s; eye[2] += rnd(-.5f, .5f) * s
        }
    }

    fun glowGold(s: Int) = max(gold, if (s == 1) thrGlow else 0f)
    fun isHolding4() = holdOn
}
