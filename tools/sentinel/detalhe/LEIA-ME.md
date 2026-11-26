# Sentinel detalhado (v4): corpo do modelo original Meshy
Entrada: Ironbound_Sentinel_Meshy_AI_*.glb (437 mil vertices, 3 texturas 2048: cor, metal/rugosidade, normal).
Caminhos /home/claude/... no topo dos scripts. Ordem:
1. Extrair o GLB original para orig.npz e img0/img1/img2 (loadg.py com acc(0)=pos, 1=uv, 2=normal, 3=indices).
2. lowapp.npz (de assets/w/char.msh antigo) + Qlp.npy (ironbound.glb baixo girado -90 graus em Y, escala SC) = referencia do corpo do app.
3. make_handfix_base.py e handfix.py (curl = fecha os dedos, roll = gira o punho, iguais ao add_weapon/roll_hands).
4. Registrar o original na referencia (escala+translacao por ICP) e rodar `uvqem 100000 in.bin out100k.bin` (QEM que trava vertices de costura de UV; para em 133 mil tris).
5. stage1.py: pesos de pele por triangulo mais proximo do corpo baixo + curl + roll; igualados nas costuras.
6. stage2.py: tatuagem vermelha so em ilhas de pele (fora de cabeca/maos), padding das bordas.
7. stage3.py: monta char.msh (corpo + machados antigos), char_atlas.jpg (corpo em v 0-0.5) e char_nrm.png (R,G = normal; B = metal).
Preview: mk_prev.py + shot.py (WebGL no chromium headless); pose.py testa animacoes.
Shader do app: Scene.CHAR_TFS (igual a fs.glsl). Renderer carrega w/char_nrm.png na unidade 2 (uNrm).

v4.1: cabeca (y>1.50 no espaco do app) travada no decimador = malha original completa (uvqem 200000; fica ~222 mil tris no corpo); stage2: tatuagem vermelho escuro (150,22,30), metal zerado na pele, manchas claras da pele por inpaint, texels claros do cabelo escurecidos. mk_cmp.py compara original x decimado.
