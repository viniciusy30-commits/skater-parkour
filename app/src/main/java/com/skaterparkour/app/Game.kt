package com.skaterparkour.app

import kotlin.math.*

/** Mapa de testes + jogador + câmera. Roda na thread do GL. */
class Game(cs: ClipSet, rig: RigData, val world: World, val stations: List<Station>) {
    val player = Player(world, cs)
    val skel = Skeleton(rig)
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

    init { M4.id(charM); tgt[0] = player.x; tgt[1] = player.y + 1.2f; tgt[2] = player.z; goStation(0) }

    fun stationName() = stations[station].name

    private fun goStation(i: Int) {
        val n = stations.size
        station = ((i % n) + n) % n
        val s = stations[station]
        player.teleport(s.x, s.y, s.z, s.yaw)
        camYaw = s.yaw
        tgt[0] = s.x; tgt[1] = s.y + 1.2f; tgt[2] = s.z
        anim.changed(0.01f)
    }

    fun update(dtIn: Float) {
        val dt = dtIn.coerceIn(0f, 0.1f)
        time += dt
        ev.clear(); Input.take(cam, ev)
        camYaw -= cam[0]
        camPitch = clampf(camPitch + cam[1], -.15f, 1.35f)
        camDist = clampf(camDist / cam[2], 2.2f, 14f)
        var jump = false; var crouch = false; var drop = false; var emote = false; var die = false; var act = false
        for (e in ev) when (e) {
            "jump" -> jump = true; "crouch" -> crouch = true; "drop" -> drop = true
            "emote" -> emote = true; "die" -> die = true; "act" -> act = true
            "next" -> goStation(station + 1); "prev" -> goStation(station - 1)
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
        // passos fixos de no máximo 1/60 s
        val steps = max(1, ceil(dt / (1f / 60f)).toInt())
        val h = dt / steps
        for (i in 0 until steps) {
            ctl.jump = jump && i == 0; ctl.crouch = crouch && i == 0; ctl.drop = drop && i == 0
            ctl.emote = if (emote && i == 0) emoteI else 0; ctl.die = die && i == 0; ctl.act = act && i == 0
            player.update(h, ctl)
        }
        ctl.clearEdges()

        // caiu para fora da arena: volta ao ponto de partida
        if (player.y < -25f) goStation(station)

        // animação
        if (player.clipChanged) { anim.changed(player.xfade); player.clipChanged = false }
        anim.update(player.clip, player.t, player.loop, dt)
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
        val wa = world.waterAt(eye[0], eye[2])
        underwater = wa != null && eye[1] < wa.surface
    }

    fun status(): String = "${player.st} · ${player.clip.substring(2)}"
    fun info(): String = player.info
}
