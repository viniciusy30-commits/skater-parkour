package com.skaterparkour.app

import kotlin.math.*

/** Inimigo de treino: o "Guerreiro Sombra" (mesmo modelo, outro papel). */
class Foe {
    companion object { const val MAXHP = 400f }
    var x = 0f; var y = 0f; var z = 0f; var yaw = 0f
    var hp = MAXHP
    var clip = "c:idle_00"; var t = 0f; var loop = true; var changed = true; var fade = 0.15f
    var state = 0            // 0 livre, 1 reação, 2 atordoado, 3 atacando, 4 derrubado, 5 morto
    var stT = 0f; var hold = 0f; var stage = 0
    var atkCd = 2.5f; var atkHit = false
    var vx = 0f; var vz = 0f
    var pullT = 0f; var pullSx = 0f; var pullSz = 0f; var pullTx = 0f; var pullTz = 0f
    var respawnT = 0f
    fun play(c: String, lp: Boolean, fd: Float) { clip = c; t = 0f; loop = lp; changed = true; fade = fd }
}

/** Efeito 2D simples desenhado pela interface: 0 anel no chão, 1 "X" de corte, 2 selo no chão. */
class Fx(val kind: Int, val x: Float, val y: Float, val z: Float, val r: Float, val life: Float, val color: Int) { var age = 0f }
class Pop(val x: Float, val y: Float, val z: Float, val text: String, val color: Int) { var age = 0f }

/**
 * Kit de combate do Guerreiro (portado do Visualizador v99): Corte Dual (Combo 04 em 4 toques),
 * Skill 1 Cruz Ceifadora, Skill 2 Contra-Ataque do Carrasco, Skill 3 Rompe-Terra,
 * Skill 4 Corrente do Carrasco e a Ultimate Ragnarok do Carrasco.
 */
class Combat(private val cs: ClipSet, private val player: Player, private val audio: Audio?) {
    class Seg(val clip: String, val start: Float, val end: Float, val fade: Float)
    class Ev(val at: Float, val fn: () -> Unit) { var done = false }

    val foe = Foe()
    val fx = ArrayList<Fx>()
    val pops = ArrayList<Pop>()
    val maxHp = 100f; val maxMana = 100f
    var hp = 100f; var mana = 100f
    var busy = false
    var clip = "c:idle_00"; var t = 0f; var loop = false; var changed = false; var fade = 0.15f
    var flash = 0f; var flashColor = 0xFFFFFFFF.toInt(); var shake = 0f
    var ultT = 0f
    var guard = false

    private val cdLeft = FloatArray(5)
    private val cdMax = floatArrayOf(6f, 10f, 12f, 12f, 60f)
    private val cost = floatArrayOf(30f, 40f, 55f, 50f, 100f)
    private var segs: List<Seg> = emptyList()
    private var evs: List<Ev> = emptyList()
    private var segI = 0; private var segT = 0f; private var skT = 0f
    private var kind = ""
    private var hitstop = 0f
    private var clock = 0f
    private var basicIdx = 0; private var basicQ = false; private var basicEnd = -99f; private var basicPress = -99f
    private var deadT = 0f
    private var parried = false
    var onRevive: (() -> Unit)? = null

    private val BSEG = arrayOf(floatArrayOf(0f, 1.15f), floatArrayOf(1.15f, 1.75f), floatArrayOf(1.75f, 2.55f), floatArrayOf(2.55f, 4.63f))
    private val GOLD = 0xFFE9C47E.toInt(); private val RED = 0xFFE0402A.toInt(); private val EARTH = 0xFFC08040.toInt(); private val WHITE = 0xFFFFFFFF.toInt()

    fun cdFrac(i: Int): Float = if (cdLeft[i] <= 0f) 0f else cdLeft[i] / cdMax[i]
    fun canCast(i: Int): Boolean = cdLeft[i] <= 0f && mana >= cost[i] && kind != "dead"

    private fun sfx(k: String, g: Float = 1f, rate: Float = 1f, delay: Float = 0f) { audio?.sfx(k, g, rate, delay) }
    private fun clipDur(n: String): Float = if (cs.has(n)) cs[n].dur else 1f
    private fun fwdX() = sin(player.yaw); private fun fwdZ() = cos(player.yaw)

    private fun addShake(a: Float) { shake = max(shake, a) }
    private fun addFlash(a: Float, c: Int) { flash = max(flash, a); flashColor = c }
    private fun pop(x: Float, y: Float, z: Float, s: String, c: Int) { pops.add(Pop(x, y, z, s, c)) }

    fun respawnFoe() {
        val f = foe
        f.x = player.x + sin(player.yaw) * 5f; f.z = player.z + cos(player.yaw) * 5f; f.y = player.y
        f.hp = Foe.MAXHP; f.state = 0; f.vx = 0f; f.vz = 0f; f.pullT = 0f; f.atkCd = 2.5f
        f.play("c:idle_00", true, 0.01f)
    }

    // ------------------------------------------------------------------------------------ entradas
    fun update(dtIn: Float, presses: List<String>, canStart: Boolean) {
        clock += dtIn
        for (i in 0 until 5) if (cdLeft[i] > 0f) cdLeft[i] -= dtIn
        mana = min(maxMana, mana + 7f * dtIn)
        if (flash > 0f) flash = max(0f, flash - dtIn * 2.5f)
        if (shake > 0f) shake = max(0f, shake - dtIn * 3.5f)
        if (ultT > 0f) ultT -= dtIn
        for (e in presses) {
            if (!canStart && !busy) continue
            when (e) {
                "atk" -> pressAtk()
                "s1" -> cast(0); "s2" -> cast(1); "s3" -> cast(2); "s4" -> cast(3); "ult" -> cast(4)
            }
        }
        var dt = dtIn
        if (hitstop > 0f) { hitstop -= dtIn; dt = 0f }
        stepSeq(dt)
        updateFoe(dt)
        var k = 0
        while (k < fx.size) { val f = fx[k]; f.age += dtIn; if (f.age >= f.life) fx.removeAt(k) else k++ }
        k = 0
        while (k < pops.size) { val p = pops[k]; p.age += dtIn; if (p.age >= 1.1f) pops.removeAt(k) else k++ }
    }

    private fun pressAtk() {
        if (kind == "dead") return
        if (busy && kind == "basic") { basicQ = true; return }
        if (busy) return
        if (clock - basicPress < 0.3f) return
        basicPress = clock
        val idx = if (clock - basicEnd > 2.5f) 0 else (basicIdx + 1) % 4
        startBasic(idx, false)
    }

    private fun startBasic(idx: Int, cont: Boolean) {
        basicIdx = idx; basicQ = false
        val s0 = BSEG[idx][0]; val s1 = BSEG[idx][1]
        faceFoe()
        val ev = ArrayList<Ev>()
        fun hit(clipT: Float, dmg: Float, big: Boolean) {
            ev.add(Ev(clipT - s0 - 0.06f) { sfx("swing" + (1 + (idx % 3)), 0.8f) })
            ev.add(Ev(clipT - s0) {
                val h = hitFoe(player.x, player.z, 3.3f, dmg, if (big) 1 else 0, if (big) 1.6f else 0.6f)
                if (h) { sfx("axe_hit" + (1 + (idx % 3)), 1f); addShake(if (big) 0.45f else 0.2f); if (big) hitstop = 0.05f }
            })
        }
        when (idx) {
            0 -> hit(0.93f, 8f, false)
            1 -> hit(1.53f, 8f, false)
            2 -> hit(2.17f, 10f, false)
            else -> { hit(2.83f, 10f, false); hit(3.23f, 18f, true) }
        }
        beginSeq("basic", listOf(Seg("c:Combo_04", s0, s1, 0.12f)), ev, cont)
    }

    private fun cast(i: Int) {
        if (busy && kind != "basic") return
        if (!canCast(i)) return
        mana -= cost[i]; cdLeft[i] = cdMax[i]
        faceFoe()
        when (i) {
            0 -> skill1(); 1 -> skill2(); 2 -> skill3(); 3 -> skill4(); else -> ultimate()
        }
    }

    private fun beginSeq(name: String, s: List<Seg>, e: List<Ev>, cont: Boolean) {
        kind = name; segs = s; evs = e
        for (x in e) x.done = false
        segI = 0; segT = 0f; skT = 0f; busy = true; guard = false
        clip = s[0].clip; t = s[0].start; loop = false
        if (!cont) { changed = true; fade = s[0].fade }
    }

    private fun stepSeq(dt: Float) {
        if (kind == "dead") {
            deadT += dt
            clip = "c:dead01"; t = min(deadT, clipDur("c:dead01")); loop = false
            if (deadT >= 3.2f) {
                hp = maxHp; mana = maxMana; busy = false; kind = ""; deadT = 0f
                onRevive?.invoke()
            }
            return
        }
        if (!busy) return
        skT += dt; segT += dt
        val s = segs[segI]
        val len = max(0.01f, s.end - s.start)
        t = s.start + min(segT, len)
        for (e in evs) if (!e.done && skT >= e.at) { e.done = true; e.fn() }
        if (segT >= len) {
            if (segI + 1 < segs.size) {
                segI++; segT = 0f
                val n = segs[segI]
                clip = n.clip; t = n.start; changed = true; fade = n.fade
            } else finish()
        }
    }

    private fun finish() {
        val wasBasic = kind == "basic"
        busy = false; guard = false
        if (wasBasic) {
            basicEnd = clock
            if (basicQ && basicIdx < 3) { startBasic(basicIdx + 1, true); return }
        }
        kind = ""
    }

    private fun faceFoe() {
        val f = foe
        if (f.state == 5) return
        val dx = f.x - player.x; val dz = f.z - player.z
        val d = hypot(dx, dz)
        if (d in 0.05f..14f) player.yaw = atan2(dx, dz)
    }

    // ------------------------------------------------------------------------------------ skills
    private fun ring(cx: Float, cz: Float, r: Float, life: Float, c: Int) { fx.add(Fx(0, cx, player.y, cz, r, life, c)) }

    private fun skill1() {
        val ev = ArrayList<Ev>()
        ev.add(Ev(0.25f) { sfx("s1_charge") })
        ev.add(Ev(0.72f) {
            sfx("s1_launch")
            val f = foe
            if (f.state != 5 && hypot(f.x - player.x, f.z - player.z) <= 6f) fx.add(Fx(1, f.x, 1.2f, f.z, 1.2f, 0.45f, RED))
            else fx.add(Fx(1, player.x + fwdX() * 2.2f, 1.2f, player.z + fwdZ() * 2.2f, 1.2f, 0.45f, RED))
        })
        ev.add(Ev(0.92f) {
            sfx("s1_impact")
            if (hitFoe(player.x, player.z, 6f, 20f, 1, 1f)) { addShake(0.5f); hitstop = 0.06f; addFlash(0.12f, 0xFFFFD0C8.toInt()) }
        })
        beginSeq("s1", listOf(Seg("c:attack_03", 0f, 1.75f, 0.15f)), ev, false)
    }

    private fun skill2() {
        parried = false
        val ev = ArrayList<Ev>()
        ev.add(Ev(0.04f) { sfx("s2_guard"); guard = true })
        ev.add(Ev(1.0f) { guard = false })
        ev.add(Ev(1.02f) {
            sfx("s2_wave"); sfx("s2_thud", 0.8f)
            val dmg = if (parried) 35f else 25f
            hitFoe(player.x, player.z, 5f, dmg, 2, 3.5f)
            ring(player.x, player.z, 5f, 0.7f, GOLD)
            addShake(1f); hitstop = 0.08f
        })
        beginSeq("s2", listOf(Seg("c:defence", 0f, 0.42f, 0.12f), Seg("c:attack_07", 0.15f, 2.0f, 0.2f)), ev, false)
    }

    private fun skill3() {
        val ev = ArrayList<Ev>()
        ev.add(Ev(0.28f) {
            sfx("s3_rise")
            val f = foe
            if (f.state != 5) fx.add(Fx(2, f.x, f.y, f.z, 1.9f, 1.0f, EARTH))
        })
        ev.add(Ev(1.28f) { sfx("swing3") })
        ev.add(Ev(1.36f) {
            sfx("s3_slam"); sfx("s3_boom", 0.85f)
            val cx = player.x + fwdX() * 1.8f; val cz = player.z + fwdZ() * 1.8f
            hitFoe(cx, cz, 3.6f, 22f, 2, 2f)
            ring(cx, cz, 3.8f, 0.7f, EARTH)
            addShake(1f); hitstop = 0.13f; addFlash(0.25f, 0xFFFFD0C8.toInt())
        })
        beginSeq("s3", listOf(Seg("c:attack_05", 0f, 2.7f, 0.15f)), ev, false)
    }

    private fun skill4() {
        val ev = ArrayList<Ev>()
        ev.add(Ev(0.2f) { sfx("chain_throw"); sfx("axe_whir", 0.8f) })
        ev.add(Ev(0.45f) {
            val f = foe
            if (f.state != 5 && hypot(f.x - player.x, f.z - player.z) <= 14f) {
                sfx("chain_snap"); sfx("axe_hit2"); sfx("chain_pull", 0.9f, 1f, 0.1f)
                hitFoe(player.x, player.z, 14.5f, 15f, 0, 0f)
                if (f.state != 5) {
                    f.state = 2; f.stT = 0f; f.hold = 1.9f; f.play("c:stun", true, 0.1f)
                    f.pullT = 0.6f; f.pullSx = f.x; f.pullSz = f.z
                    f.pullTx = player.x + fwdX() * 1.7f; f.pullTz = player.z + fwdZ() * 1.7f
                    fx.add(Fx(1, f.x, 1.2f, f.z, 0.6f, 0.4f, GOLD))
                }
            } else sfx("chain_snap_o", 0.7f)
        })
        ev.add(Ev(1.26f) {
            sfx("axe_hit3"); sfx("impact")
            if (hitFoe(player.x, player.z, 3.4f, 25f, 1, 1.5f)) { addShake(0.6f); hitstop = 0.07f }
        })
        beginSeq("s4", listOf(Seg("c:throw", 0f, 0.73f, 0.12f), Seg("c:attack_08", 0f, 1.2f, 0.14f)), ev, false)
    }

    private fun ultimate() {
        val ev = ArrayList<Ev>()
        ev.add(Ev(0.02f) { addFlash(0.3f, 0xFFFF2A1A.toInt()) })
        ev.add(Ev(0.3f) { sfx("ult_roar"); ultT = 5.8f; addShake(1f); addFlash(0.3f, 0xFFFF2A1A.toInt()) })
        ev.add(Ev(0.55f) { sfx("ult_aura") })
        val times = floatArrayOf(1.92f, 2.75f, 3.55f, 4.55f)
        for (i in 0 until 4) {
            val last = i == 3
            ev.add(Ev(times[i]) {
                if (last) { sfx("ult_final"); sfx("ult_debris", 0.9f); sfx("ult_slam", 0.8f, 0.9f) }
                else { sfx("ult_slam", 0.8f + i * 0.08f); sfx("ult_debris", 0.55f + i * 0.08f) }
                hitFoe(player.x, player.z, 5f, if (last) 30f else 14f, if (last) 2 else 1, if (last) 4f else 1.2f)
                ring(player.x + fwdX() * 1.5f, player.z + fwdZ() * 1.5f, if (last) 7f else 3.5f + i * 0.6f, 0.7f, RED)
                addShake(if (last) 3f else 1.5f + i * 0.3f); hitstop = if (last) 0.24f else 0.09f
                if (last) addFlash(0.35f, 0xFFFF2A1A.toInt())
            })
        }
        beginSeq("ult", listOf(Seg("c:buff_01", 0f, 1.3f, 0.15f), Seg("c:Combo_03", 0.3f, 4.97f, 0.2f)), ev, false)
    }

    // ------------------------------------------------------------------------------------ dano
    /** Acerta o inimigo se ele estiver a até [radius] m do centro. Retorna true se acertou. */
    private fun hitFoe(cx: Float, cz: Float, radius: Float, amount: Float, kindHit: Int, push: Float): Boolean {
        val f = foe
        if (f.state == 5) return false
        if (hypot(f.x - cx, f.z - cz) > radius + 0.4f) return false
        f.hp -= amount
        pop(f.x, f.y + 2.1f, f.z, "-" + amount.toInt(), if (amount >= 20f) 0xFFFFC040.toInt() else WHITE)
        val dx = f.x - player.x; val dz = f.z - player.z; val d = max(0.1f, hypot(dx, dz))
        f.vx += dx / d * push * 4f; f.vz += dz / d * push * 4f
        if (f.hp <= 0f) {
            f.hp = 0f; f.state = 5; f.respawnT = 4f; f.pullT = 0f
            f.play("c:dead01", false, 0.08f); addShake(1.2f)
            return true
        }
        if (amount > 0f) {
            f.stT = 0f; f.atkHit = true; f.stage = 0
            when (kindHit) {
                0 -> { f.state = 1; f.hold = 0.4f; f.play("c:hit_body_front", false, 0.06f) }
                1 -> { f.state = 1; f.hold = 0.9f; f.play("c:hit_heavy_front", false, 0.06f) }
                else -> { f.state = 4; f.hold = 1.13f; f.play("c:down_knockdown_down", false, 0.06f) }
            }
        }
        return true
    }

    private fun foeHitsPlayer() {
        if (kind == "dead") return
        val f = foe
        if (guard) {
            sfx("parry"); sfx("impact", 0.8f)
            parried = true
            f.state = 2; f.stT = 0f; f.hold = 1.4f; f.play("c:hit_heavy_front", false, 0.06f)
            pop(player.x, player.y + 2.1f, player.z, "DEFENDIDO!", GOLD)
            addShake(0.5f); hitstop = 0.07f; addFlash(0.2f, 0xFFFFE9B0.toInt())
            return
        }
        hp -= 10f
        sfx("hit_body"); addShake(0.4f); addFlash(0.18f, 0xFFFF2A1A.toInt())
        pop(player.x, player.y + 2.1f, player.z, "-10", RED)
        if (hp <= 0f) {
            hp = 0f; kind = "dead"; busy = true; deadT = 0f; guard = false; ultT = 0f
            clip = "c:dead01"; t = 0f; loop = false; changed = true; fade = 0.08f
        } else if (!busy) {
            beginSeq("hurt", listOf(Seg("c:hit_body_front", 0f, 0.4f, 0.06f)), emptyList(), false)
        }
    }

    // ------------------------------------------------------------------------------------ inimigo
    private fun foeClip(c: String, lp: Boolean, fd: Float) { if (foe.clip != c) foe.play(c, lp, fd) }

    private fun updateFoe(dt: Float) {
        val f = foe
        f.t += dt
        val dx = player.x - f.x; val dz = player.z - f.z
        val d = hypot(dx, dz)
        f.x += f.vx * dt; f.z += f.vz * dt
        val dec = exp(-6f * dt)
        f.vx *= dec; f.vz *= dec
        if (f.pullT > 0f) {
            f.pullT -= dt
            val k = smooth(1f - max(0f, f.pullT) / 0.6f)
            f.x = f.pullSx + (f.pullTx - f.pullSx) * k; f.z = f.pullSz + (f.pullTz - f.pullSz) * k
        }
        when (f.state) {
            0 -> {
                if (d > 0.05f) f.yaw = atan2(dx, dz)
                if (kind == "dead") { foeClip("c:idle_00", true, 0.2f); return }
                if (d > 2.4f) {
                    foeClip("c:walk_front", true, 0.2f)
                    val sp = 1.7f * dt
                    f.x += dx / d * sp; f.z += dz / d * sp
                } else {
                    foeClip("c:idle_00", true, 0.2f)
                    f.atkCd -= dt
                    if (f.atkCd <= 0f) { f.state = 3; f.stT = 0f; f.atkHit = false; f.play("c:attack_01", false, 0.12f) }
                }
            }
            3 -> {
                f.stT += dt
                if (d > 0.05f && f.stT < 0.5f) f.yaw = atan2(dx, dz)
                if (!f.atkHit && f.stT >= 0.67f) { f.atkHit = true; if (d <= 3.3f) foeHitsPlayer() }
                if (f.stT >= 1.3f) { f.state = 0; f.atkCd = 3f; f.play("c:idle_00", true, 0.2f) }
            }
            1, 2, 4 -> {
                f.stT += dt
                if (f.stT >= f.hold) {
                    if (f.state == 4 && f.stage == 0) { f.stage = 1; f.stT = 0f; f.hold = 1.5f; f.play("c:down_knockdown_up01", false, 0.1f) }
                    else { f.state = 0; f.stage = 0; f.atkCd = 1.2f; f.play("c:idle_00", true, 0.2f) }
                }
            }
            5 -> {
                f.respawnT -= dt
                if (f.respawnT <= 0f) respawnFoe()
            }
        }
    }
}
