package com.skaterparkour.app

/** Estação do mapa de testes (para teleporte rápido pelo menu). */
class Station(val name: String, val x: Float, val z: Float, val yaw: Float, val y: Float = 0f)

object TestMap {
    val stations = ArrayList<Station>()

    fun build(): World {
        val w = World()
        stations.clear()
        // chão (com buraco da piscina em x 18..42, z 30..62)
        w.add(-60f, -8f, -30f, 18f, 0f, 330f, 0, "chão")
        w.add(42f, -8f, -30f, 60f, 0f, 330f, 0, "chão")
        w.add(18f, -8f, -30f, 42f, 0f, 30f, 0, "chão")
        w.add(18f, -8f, 62f, 42f, 0f, 330f, 0, "chão")
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
        // 8. paredes longas paralelas (corrida horizontal na parede)
        w.add(-25f, 0f, 30f, -24f, 5f, 100f, 0, "parede corrida")
        w.add(-19f, 0f, 30f, -18f, 5f, 100f, 0, "parede corrida")
        stations.add(Station("Corrida na parede (parede à direita)", -23.2f, 30f, 0f))
        stations.add(Station("Corrida na parede (parede à esquerda)", -19.8f, 30f, 0f))
        stations.add(Station("Piscina (natação)", 12f, 46f, 1.5708f))
        // 9. pilar mais distante (pulo de precisão)
        w.add(-1.5f, 0f, 138.1f, 1.5f, 1.0f, 141.1f, 0, "pilar 4")
        // 10. parede alta: correr na parede para cima
        w.add(-6f, 0f, 150f, 6f, 5.5f, 152f, 0, "parede alta")
        stations.add(Station("Parede alta 5,5 m (correr para cima)", 0f, 142f, 0f))
        // 11. tic-tac sobre plataforma alta
        w.add(-3f, 0f, 166f, 3f, 2.2f, 171f, 0, "plataforma alta")
        stations.add(Station("Tic-tac para plataforma de 2,2 m", 0f, 158f, 0f))
        // 12. tic-tac sobre muro fino
        w.add(-3f, 0f, 184f, 3f, 2.2f, 184.5f, 0, "muro fino")
        stations.add(Station("Tic-tac sobre muro fino de 2,2 m", 0f, 176f, 0f))
        // 13. parede para parede (tic-tac lateral) com plataforma baixa atrás
        w.add(-4f, 0f, 200f, 4f, 2.2f, 200.5f, 0, "parede A")
        w.add(-6f, 0f, 200.5f, -1f, 1.28f, 206f, 0, "plataforma B")
        stations.add(Station("Pulo de parede para parede (tic-tac lateral)", 0f, 192f, 0f))
        // 14. canos
        w.add(-8f, 0f, 230f, 8f, 5f, 232f, 0, "parede dos canos")
        w.add(-0.1f, 0f, 229.8f, 0.1f, 4.8f, 230f, 3, "cano 1")
        w.add(1.6f, 0f, 229.8f, 1.8f, 4.8f, 230f, 3, "cano 2")
        w.add(-1.8f, 0f, 229.8f, -1.6f, 4.8f, 230f, 3, "cano 0")
        stations.add(Station("Canos (subir, pular entre canos)", 0f, 224f, 0f))
        // 15. parede de apoios (ledges)
        w.add(-6f, 0f, 250f, 10f, 5f, 252.5f, 0, "parede de apoios")
        w.add(-0.6f, 1.35f, 249.75f, 0.6f, 1.5f, 250f, 4, "apoio A")
        w.add(2.65f, 1.35f, 249.75f, 3.85f, 1.5f, 250f, 4, "apoio B")
        w.add(-2.3f, 1.35f, 249.75f, -1.1f, 1.5f, 250f, 4, "apoio E")
        w.add(-0.6f, 2.55f, 249.75f, 0.6f, 2.7f, 250f, 4, "apoio C")
        w.add(-0.6f, 4.85f, 249.75f, 0.6f, 5.0f, 250f, 4, "apoio topo")
        stations.add(Station("Apoios (ledge): mover, pular, subir", 0f, 244f, 0f))
        // 16. muro baixo para vaults variados
        w.add(-3f, 0f, 270f, 3f, 1.1f, 270.5f, 0, "muro 1,1")
        w.add(-3f, 0f, 290f, 3f, 1.45f, 290.5f, 0, "muro 1,45")
        stations.add(Station("Muros 1,1 m e 1,45 m (vaults variados)", 0f, 262f, 0f))
        stations.add(Station("Barra baixa (deslizar por baixo)", 0f, 300f, 0f))
        w.add(-3f, 0.75f, 308f, 3f, 1.15f, 308.4f, 0, "barra")
        return w
    }
}
