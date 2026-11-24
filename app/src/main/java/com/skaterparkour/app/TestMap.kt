package com.skaterparkour.app

/** Estação do mapa de testes (para teleporte rápido pelo menu). */
class Station(val name: String, val x: Float, val z: Float, val yaw: Float, val y: Float = 0f)

object TestMap {
    val stations = ArrayList<Station>()

    fun build(): World {
        val w = World()
        stations.clear()
        // chão (com buraco da piscina em x 18..42, z 30..62)
        w.add(-60f, -8f, -30f, 18f, 0f, 160f, 0, "chão")
        w.add(42f, -8f, -30f, 60f, 0f, 160f, 0, "chão")
        w.add(18f, -8f, -30f, 42f, 0f, 30f, 0, "chão")
        w.add(18f, -8f, 62f, 42f, 0f, 160f, 0, "chão")
        // piscina: parte rasa (x 18..24) e funda
        w.add(18f, -8f, 30f, 24f, -0.6f, 62f, 0, "piscina rasa")
        w.add(24f, -8f, 30f, 42f, -4f, 62f, 0, "piscina fundo")
        w.waters.add(Water(18f, 30f, 42f, 62f, -0.35f))
        stations.add(Station("Início (campo aberto)", 0f, -4f, 0f))
        // 1. vault baixo 0.9
        w.add(-3f, 0f, 14f, 3f, 0.9f, 14.5f, 0, "vault 0.9")
        stations.add(Station("Vault baixo (0,9 m)", 0f, 7f, 0f))
        // 2. vault alto 1.25
        w.add(-3f, 0f, 30f, 3f, 1.25f, 31f, 0, "vault 1.25")
        stations.add(Station("Vault alto (1,25 m)", 0f, 23f, 0f))
        // 3. plataformas
        w.add(-3f, 0f, 46f, 3f, 0.9f, 50f, 0, "plataforma 0.9")
        w.add(-3f, 0f, 58f, 3f, 1.4f, 62f, 0, "plataforma 1.4")
        stations.add(Station("Subir plataformas (0,9 / 1,4 m)", 0f, 40f, 0f))
        // 4. parede alta para pendurar (2.2)
        w.add(-5f, 0f, 78f, 5f, 2.2f, 82f, 0, "parede 2.2")
        stations.add(Station("Parede alta: pendurar e escalar", 0f, 70f, 0f))
        // 5. trave de equilíbrio
        w.add(-3f, 0f, 92f, 3f, 1.0f, 95f, 0, "plataforma da trave A")
        w.add(-0.12f, 0f, 95f, 0.12f, 1.0f, 107f, 2, "trave")
        w.add(-3f, 0f, 107f, 3f, 1.0f, 110f, 0, "plataforma da trave B")
        stations.add(Station("Trave de equilíbrio", 0f, 86f, 0f))
        // 6. pilares (pulos de precisão)
        w.add(-1.5f, 0f, 120f, 1.5f, 1.0f, 123f, 0, "pilar 1")
        w.add(-1.5f, 0f, 125.2f, 1.5f, 1.0f, 128.2f, 0, "pilar 2")
        w.add(-1.5f, 0f, 131.2f, 1.5f, 1.0f, 134.2f, 0, "pilar 3")
        stations.add(Station("Pulos entre pilares", 0f, 114f, 0f))
        // 7. torre com escada: queda alta
        for (i in 0 until 9) w.add(-16f + i * 0.5f, 0f, 12f, -15.5f + i * 0.5f, 0.4f * (i + 1), 14f, 0, "degrau")
        w.add(-11.5f, 0f, 12f, -6f, 3.6f, 14f, 0, "torre")
        stations.add(Station("Torre (escada) para testar quedas", -18f, 13f, 1.5708f))
        // 8. parede longa (corrida na parede — próxima etapa)
        w.add(-25f, 0f, 40f, -24f, 5f, 80f, 0, "parede longa")
        stations.add(Station("Parede longa (corrida na parede)", -20f, 38f, 0f))
        stations.add(Station("Piscina (natação)", 12f, 46f, 1.5708f))
        return w
    }
}
