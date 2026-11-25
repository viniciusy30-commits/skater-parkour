# Ironbound Sentinel (v2: esqueleto consertado)
Entrada: ironbound.glb. Precisa dos rig.bin/anim.bin ANTIGOS do guerreiro (oldrig.py, ASSET) para retargetar as 602 animações; os arquivos atuais em assets/w já são o resultado.
build.py -> rig.bin, anim.bin, char.msh, char_atlas.jpg (ajuste os caminhos /home/claude/... no topo).
v2: 41 ossos animados (NBONE=41): os 22 originais + spine_04, spine_05, neck_02 e 16 ossos de torção. Torção da coluna distribuída; pescoço/cabeça herdam metade do movimento (limite 35°); cabeça 100% rígida; punho relaxado.

## Arma (Runeblade War Axe)
1. `python3 prep_axe.py Runeblade_War_Axe.glb` (so ao trocar a arma): extrai e reduz a malha (QEM em C, preserva as costuras da textura) para ~10 mil triangulos; salva em `weapon/`. Variavel `TRIS` muda o alvo.
2. `python3 add_weapon.py`: prende o machado no osso `hand_r` (e espelhado em `hand_l`) com peso 100% e grava `char.msh` e `char_atlas.jpg` em assets/w. rig.bin e anim.bin nao mudam.
Opcoes (variaveis de ambiente): `HANDS=r|l|rl` (padrao rl, um em cada mao), `SC` escala (0.45 -> 0,86 m), `YG` ponto do cabo dentro do punho (-0.43), `TILT` inclinacao da cabeca (25 graus).
`weapon/char_base.msh` e `char_atlas_base.jpg` sao o guerreiro SEM arma (base para refazer sem acumular).
Limites: indices de 16 bits no visualizador, entao no maximo 65535 vertices (agora 49077). Atlas 2048x4096 (guerreiro em v 0-0.5, arma em v 0.5-1).

## v3.1 (correcoes do machado)
- Lamina girada 180 graus em volta do cabo (FLIP=1): a lamina grande agora lidera o golpe.
- Cores: `recolor.py` pinta as tatuagens do guerreiro de vermelho-runa e leva o metal do machado ao preto da armadura e o dourado ao dourado da roupa (runas vermelhas mantidas).
- Mao: dedos fechados com curva suave (CURL_F/CURL_T, graus extra por junta), cabo afinado e centrado no oco do punho; cada mao usa o proprio esqueleto.
- `preview.py` e um visualizador simples para conferir a mao.
v3.2: punho fechado de verdade (CURL_F=65,90,55 e CURL_T=35,50,40 graus extras, TILT=12), cabo ajustado ao oco do punho, rosto/pescoco excluidos da tatuagem vermelha. `render2.py` renderiza a mao com textura e z-buffer: python3 render2.py char.msh char_atlas.jpg saida.png x y z
v3.3: corrigido o bug dos dedos compridos ('pernas de aranha') nas animacoes: a curva dos dedos agora e aplicada no espaco de repouso (usando as matrizes de bind do rig) e convertida de volta ao bind. Teste feito em varias animacoes (ataques, combos, corrida, pulo): dedos a no maximo 0,16 m do pulso (antes 0,31 m).
v3.4: roll_hands.py (rodar depois de add_weapon.py) gira o punho/antebraco (torcao crescendo do cotovelo ao pulso, 100% na mao e na arma) para o machado ficar de pe no Idle. Mao direita +56, esquerda -41 graus; eixo do machado ~40 graus da vertical no Idle (antes ~60). Env FWD ignorado por enquanto.
v3.5: curva extra dos dedos reduzida (CURL_F=20,30,15 / CURL_T=20,30,20): o oco do punho passou de 1 cm para 2,2 cm de raio e o cabo (2,05 cm) cabe sem atravessar os dedos. Ordem: add_weapon.py e depois roll_hands.py.
v3.6: mao 30% maior (HS=1.3) para o cabo ficar mais grosso (76% do original em vez de 54%); polegar com IK (dobra por cima dos dedos, nao fica reto). Ordem: add_weapon.py e depois roll_hands.py.
v3.7: mao e cabo de volta ao tamanho da v3.5 (HS=1.0 por padrao); mantido o polegar com IK (dobra por cima dos dedos).
