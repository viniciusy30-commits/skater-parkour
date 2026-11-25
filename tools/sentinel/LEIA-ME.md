# Ironbound Sentinel (v2: esqueleto consertado)
Entrada: ironbound.glb. Precisa dos rig.bin/anim.bin ANTIGOS do guerreiro (oldrig.py, ASSET) para retargetar as 602 animações; os arquivos atuais em assets/w já são o resultado.
build.py -> rig.bin, anim.bin, char.msh, char_atlas.jpg (ajuste os caminhos /home/claude/... no topo).
v2: 41 ossos animados (NBONE=41): os 22 originais + spine_04, spine_05, neck_02 e 16 ossos de torção. Torção da coluna distribuída; pescoço/cabeça herdam metade do movimento (limite 35°); cabeça 100% rígida; punho relaxado.
