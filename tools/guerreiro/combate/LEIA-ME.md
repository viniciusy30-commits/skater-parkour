# Clipes de combate (110) do visualizador v99 -> esqueleto do Crimson Warlord
Entrada: guerreiro-visualizador-v99.html (blocos m_g/b_g, zlib+base64) -> m_g.json e b_g.dec; rig.bin/anim.bin atuais do app.
`retarget2.py` gira o esqueleto antigo 90° (frente +X -> +Z), escala 1,70x, refaz os ossos pelas direções e corrige a altura da pelve pelos pés. Os clipes entram no anim.bin com prefixo `c:`. Deslocamento da raiz fica na própria pelve (root = 0).
