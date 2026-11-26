package com.skaterparkour.app

/**
 * Ficha de ritmo e números de um personagem. O Combat lê tudo daqui, então um personagem novo
 * começa como uma ficha nova (clipes, tempos de cancelamento, recargas, esquiva) em [Champions].
 * Os efeitos específicos de cada skill ficam no Combat, em um bloco por personagem.
 */
class ChampDef(
    val name: String,
    val cdMax: FloatArray,            // recarga de s1..s4, ult e esquiva (6 valores)
    val cost: FloatArray,             // mana de s1..s4, ult e esquiva (6 valores)
    val basicSegs: Array<FloatArray>, // trecho do clipe de combo por golpe: [início, fim]
    val basicCancel: FloatArray,      // a partir de quando cada golpe pode ser cancelado (tempo do clipe)
    val basicScale: Float,            // velocidade do ataque básico (1 = clipe original)
    val skillScale: Float,            // velocidade das skills
    val ultScale: Float,              // velocidade da ultimate
    val dashScale: Float,             // velocidade do rolamento da esquiva
    val dashDist: Float,              // distância da esquiva (m)
    val dashTime: Float,              // duração do deslocamento (s)
    val invul: Float,                 // invulnerabilidade da esquiva (s)
    val lungeMax: Float,              // avanço automático máximo nos golpes (m)
    val manaRegen: Float,             // mana por segundo
    val comboWindow: Float,           // tempo para manter o combo (s)
    val comboBonus: Float,            // bônus de dano por acerto de combo
)

object Champions {
    val WARRIOR = ChampDef(
        name = "Guerreiro",
        cdMax = floatArrayOf(4f, 7f, 8f, 8f, 45f, 2.2f),
        cost = floatArrayOf(20f, 30f, 40f, 35f, 100f, 0f),
        basicSegs = arrayOf(floatArrayOf(0.3f, 1.05f), floatArrayOf(1.2f, 1.63f), floatArrayOf(1.85f, 2.3f), floatArrayOf(2.6f, 3.85f)),
        basicCancel = floatArrayOf(0.71f, 0.41f, 0.40f, 0.71f),
        basicScale = 1.0f, skillScale = 0.95f, ultScale = 0.9f, dashScale = 1.05f,
        dashDist = 5.2f, dashTime = 0.3f, invul = 0.42f, lungeMax = 4.5f,
        manaRegen = 10f, comboWindow = 2.2f, comboBonus = 0.03f,
    )
}
