package com.skaterparkour.app

import android.content.Context

/**
 * Uma animação de obstáculo já medida no corpo do personagem: a que distância fica a frente do
 * obstáculo, que profundidade ele pode ter, em que momento mãos e pés tocam nele etc.
 * (os números vêm de assets/parkour.txt, gerados a partir das próprias animações).
 */
class ActionDef(
    val name: String,
    /** true = sobe e fica em cima do obstáculo; false = passa por cima e cai do outro lado. */
    val climb: Boolean,
    /** 0 = degrau, 1 = baixo, 2 = parede. */
    val cls: Int,
    /** Altura do obstáculo para a qual a animação foi feita. */
    val h: Float,
    /** Distância do quadril (quadro 0) até a frente do obstáculo. */
    val zf: Float,
    val dMin: Float,
    val dMax: Float,
    /** Segundo em que as mãos/pés tocam o obstáculo pela primeira vez e pela última. */
    val c0: Float,
    val c1: Float,
    /** Velocidade do quadril no começo e no fim da animação. */
    val v0: Float,
    val ve: Float,
    val side: Char,
    /** true = descida: corre para fora de uma borda e cai até um chão mais baixo (h = altura da queda). */
    val drop: Boolean = false
) {
    var clip: Clip? = null
    var z0 = 0f
}

object Parkour {
    fun load(ctx: Context, suf: String, all: Map<String, Clip>): List<ActionDef> {
        val list = ArrayList<ActionDef>()
        try {
            val text = ctx.assets.open("parkour$suf.txt").bufferedReader().use { it.readText() }
            val tmp = FloatArray(3)
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split(" ")
                if (p.size < 16) continue
                val clip = all[p[0]] ?: continue
                val fps = clip.fps
                val d = ActionDef(
                    name = p[0],
                    climb = p[1] == "O",
                    cls = p[2].toInt(),
                    h = p[3].toFloat(),
                    zf = p[4].toFloat(),
                    dMin = p[5].toFloat(),
                    dMax = p[6].toFloat(),
                    c0 = p[7].toFloat() / fps,
                    c1 = p[8].toFloat() / fps,
                    v0 = p[9].toFloat(),
                    ve = p[10].toFloat(),
                    side = p[12][0],
                    drop = p[1] == "D"
                )
                d.clip = clip
                clip.hips(0f, tmp)
                d.z0 = tmp[2]
                list.add(d)
            }
        } catch (e: Exception) {
            // sem o catálogo o jogo continua só com o pulo normal
        }
        return list
    }
}
