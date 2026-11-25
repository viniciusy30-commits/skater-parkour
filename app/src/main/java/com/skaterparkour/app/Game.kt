package com.skaterparkour.app

import kotlin.math.*

/** Mapa de testes + jogador + câmera. Roda na thread do GL. */
class Game(cs: ClipSet, rig: RigData, val world: World, val stations: List<Station>, audio: Audio? = null) {
    val player = Player(world, cs)
    val combat = Combat(cs, player, audio)
    val skel = Skeleton(rig)
    val foeSkel = Skeleton(rig)
    val foeM = FloatArray(16)
    private val foeAnim = Animator(cs)
    private val csr = cs
    private var lastPc = ""
    private val cbEv = ArrayList<String>()
    private var cbPrev = false
    private val anim = Animator(cs)
    private val ctl = Ctl()
    private val cam = FloatArray(3)
    private val ev = ArrayList<String>()

    val eye = FloatArray(3); val look = FloatArray(3)
    val charM = FloatArray(16)
    var time = 0f
    var station = 0
    var camYaw = 0f; private var camPitch = .30f; private var camDist = 4.6f; private var camDistSm = 4.6f
    private val tgt = FloatArray(3)
    var underwater = false
    private var emoteI = 0

    init { M4.id(charM); M4.id(foeM); combat.onRevive = { goStation(station) }; tgt[0] = player.x; tgt[1] = player.y + 1.2f; tgt[2] = player.z; goStation(0) }

    /** O guerreiro usa SÓ animações dele (luta + locomoção). Nenhum clipe de parkour (m:/p:/s:) chega à tela. */
    private fun warriorClip(n: String): String {
        if (n.startsWith("c:")) return n
        val b = n.substring(2)
        val r: String = when {
            // ---- locomoção ----
            b == "Stand_Relaxed_Idle" -> "c:idle_00"
            b == "Stand_Relaxed_Idle_v2" -> "c:idle_01"
            n.startsWith("m:") && b.startsWith("Walk_") && !b.contains("Jump") -> when (b.removePrefix("Walk_")) {
                "FL_Loop" -> "c:walk_FrontL"; "FR_Loop" -> "c:walk_FrontR"; "L" -> "c:walk_left"; "R" -> "c:walk_right"
                "B" -> "c:walk_back"; "BL_BkPd_Loop" -> "c:walk_backL"; "BR_BkPd_Loop" -> "c:walk_backR"; else -> "c:walk_front"
            }
            n.startsWith("m:") && (b.startsWith("Jog_") || b.startsWith("Run_")) && !b.contains("Jump") -> when (b.substring(4)) {
                "FL_Loop" -> "c:move_run_strafe_frontL45"; "FR_Loop" -> "c:move_run_strafe_frontR45"
                "L" -> "c:move_run_strafe_left"; "R" -> "c:move_run_strafe_right"; "B" -> "c:move_run_strafe_back"
                "BL_BkPd_Loop" -> "c:move_run_strafe_backL45"; "BR_BkPd_Loop" -> "c:move_run_strafe_backR45"; else -> "c:move_run_front"
            }
            Regex("Stand_Relaxed_L\\d+").matches(b) -> if (b.removePrefix("Stand_Relaxed_L").toInt() >= 90) "c:turn_90L" else "c:idle_00"
            Regex("Stand_Relaxed_R_\\d+").matches(b) -> if (b.removePrefix("Stand_Relaxed_R_").toInt() >= 90) "c:turn_90R" else "c:idle_00"
            b.startsWith("Stand_Relaxed_Death_") -> when (b.last()) { 'F' -> "c:dead01"; 'B' -> "c:dead02"; 'L' -> "c:dead03"; else -> "c:dead04" }
            // ---- pulo / queda / pouso ----
            b.contains("Landing_Roll") -> if (b.endsWith("_R")) "c:roll_right" else "c:roll_front"
            b.startsWith("Landing") || b.startsWith("LandingGround") -> "c:move_step_front"
            b.contains("Jump") || b.contains("Hop") || b.startsWith("Fly") || b.startsWith("Fall") -> "c:jump"
            // ---- agachar -> postura de defesa / idle ----
            b.startsWith("Crouch") || b.contains("Crouch") ->
                if (b.contains("Walk")) "c:walk_front" else if (b == "Crouch_Idle" || b == "Crouch_Idle_V2" || b.startsWith("Crouch_to_Stand")) "c:defence" else "c:defence"
            // ---- emotes (luta/pose) ----
            b.contains("Fgt") -> when (((b.lastOrNull() ?: '1') - '0') and 3) { 0 -> "c:buff_01"; 1 -> "c:equip"; 2 -> "c:unequip"; else -> "c:throw" }
            b.contains("Conv") -> if ((b.hashCode() and 1) == 0) "c:idle_01" else "c:buff_01"
            // ---- sobe/pula obstáculo, pendurar, cano, equilíbrio, vault: o guerreiro só pula ----
            b.contains("Ledge") || b.contains("Wall") || b.contains("Pipe") || b.contains("Climb") || b.contains("Vault") ||
                b.contains("Hang") || b.contains("Mantle") || b.contains("Balance") || b.contains("Slide") -> "c:jump"
            // ---- natação / água ----
            b.contains("Run") || b.contains("Topedo") || b.contains("forward") || b.contains("walk") -> "c:move_run_front"
            else -> "c:idle_00"
        }
        return if (csr.has(r)) r else "c:idle_00"
    }

    fun stationName() = stations[station].name

    private fun goStation(i: Int) {
        val n = stations.size
        station = ((i % n) + n) % n
        val s = stations[station]
        player.teleport(s.x, s.y, s.z, s.yaw)
        camYaw = s.yaw
        tgt[0] = s.x; tgt[1] = s.y + 1.2f; tgt[2] = s.z
        anim.changed(0.01f)
        combat.respawnFoe()
    }

    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, 0.1f)
        time += dt
        ev.clear(); cbEv.clear(); Input.take(cam, ev)
        camYaw -= cam[0]
        camPitch = clampf(camPitch + cam[1], -.15f, 1.35f)
        camDist = clampf(camDist / cam[2], 2.2f, 14f)
        var jump = false; var crouch = false; var drop = false; var emote = false; var die = false; var act = false
        for (e in ev) when (e) {
            "jump" -> jump = true; "crouch" -> crouch = true; "drop" -> drop = true
            "emote" -> emote = true; "die" -> die = true; "act" -> act = true
            "next" -> goStation(station + 1); "prev" -> goStation(station - 1)
            "atk", "s1", "s2", "s3", "s4", "ult" -> cbEv.add(e)
        }
        // joystick -> direção no mundo (relativa à câmera)
        val jx = Input.jx; val jy = Input.jy
        var m = hypot(jx, jy)
        if (m < .12f) m = 0f
        m = min(1f, m)
        val fx = sin(camYaw); val fz = cos(camYaw)
        val rx = -cos(camYaw); val rz = sin(camYaw)
        if (m > 0f) {
            val nx = (fx * jy + rx * jx) / hypot(jx, jy); val nz = (fz * jy + rz * jx) / hypot(jx, jy)
            ctl.mx = nx * m; ctl.mz = nz * m
        } else { ctl.mx = 0f; ctl.mz = 0f }
        ctl.jumpHeld = Input.jumpHeld; ctl.crouchHeld = Input.crouchHeld
        ctl.aim = Input.aimMode; ctl.aimYaw = camYaw
        if (emote) emoteI = emoteI % 8 + 1
        combat.update(dt, cbEv, player.st == Player.St.LOCO)
        if (combat.busy) { ctl.mx = 0f; ctl.mz = 0f; jump = false; crouch = false; drop = false; emote = false; die = false; act = false }
        // passos fixos de no máximo 1/60 s
        val steps = max(1, ceil(dt / (1f / 60f)).toInt())
        val h = dt / steps
        for (i in 0 until steps) {
            ctl.jump = jump && i == 0; ctl.crouch = crouch && i == 0; ctl.drop = drop && i == 0
            ctl.emote = if (emote && i == 0) emoteI else 0; ctl.die = die && i == 0; ctl.act = act && i == 0
            if (!combat.busy) player.update(h, ctl)
        }
        ctl.clearEdges()

        // caiu para fora da arena: volta ao ponto de partida
        if (player.y < -25f) goStation(station)

        // animação
        if (combat.busy) {
            if (combat.changed) { anim.changed(combat.fade); combat.changed = false }
            anim.update(combat.clip, combat.t, combat.loop, dt)
        } else {
            val pc = warriorClip(player.clip)
            if (cbPrev) { anim.changed(0.2f); player.clipChanged = false }
            else if (player.clipChanged) { if (pc != lastPc) anim.changed(player.xfade); player.clipChanged = false }
            lastPc = pc
            anim.update(pc, player.t, player.loop, dt)
        }
        cbPrev = combat.busy
        val fo = combat.foe
        if (fo.changed) { foeAnim.changed(fo.fade); fo.changed = false }
        foeAnim.update(fo.clip, fo.t, fo.loop, dt)
        foeSkel.solve(foeAnim.out)
        M4.trs(foeM, fo.x, fo.y, fo.z, fo.yaw, 1f)
        skel.solve(anim.out)
        M4.trs(charM, player.x, player.y, player.z, player.yaw, 1f)

        // câmera
        val k = min(1f, dt * 9f)
        tgt[0] += (player.x - tgt[0]) * k
        tgt[1] += ((player.y + if (player.st == Player.St.SWIM) .5f else 1.2f) - tgt[1]) * min(1f, dt * 6f)
        tgt[2] += (player.z - tgt[2]) * k
        camDistSm += (camDist - camDistSm) * min(1f, dt * 8f)
        val cp = cos(camPitch); val sp = sin(camPitch)
        eye[0] = tgt[0] - sin(camYaw) * cp * camDistSm
        eye[1] = max(.25f, tgt[1] + sp * camDistSm)
        eye[2] = tgt[2] - cos(camYaw) * cp * camDistSm
        look[0] = tgt[0]; look[1] = tgt[1]; look[2] = tgt[2]
        val shk = combat.shake
        if (shk > 0f) {
            eye[0] += (kotlin.random.Random.nextFloat() - .5f) * shk * .14f; eye[1] += (kotlin.random.Random.nextFloat() - .5f) * shk * .14f
            look[0] += (kotlin.random.Random.nextFloat() - .5f) * shk * .08f; look[1] += (kotlin.random.Random.nextFloat() - .5f) * shk * .08f
        }
        val wa = world.waterAt(eye[0], eye[2])
        underwater = wa != null && eye[1] < wa.surface
    }

    fun status(): String = if (combat.busy) "COMBATE · ${combat.clip.substring(2)}" else "${player.st} · ${player.clip.substring(2)}"
    fun info(): String = player.info
}
