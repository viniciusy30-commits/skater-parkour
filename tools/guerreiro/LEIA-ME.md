# Como o Crimson Warlord foi gerado (warlord_v20.html -> app/src/main/assets/w/)
Rodar na pasta de trabalho, com `meta.json`, `blob.bin` e `hero/` (criados por extract.py). Escala do modelo: x2 (pelve em y=0,95 m).
1. `python3 extract.py` -> decodifica o HTML (malha de 569 mil vértices, 80 ossos, texturas) e amostra a cor/metal de cada vértice.
2. `g++ -O2 -std=c++17 -o qemw qemw.cpp` e, dentro de hero/:
   `qemw 0 120000 body_pos.f32 body_idx.u32 body_col.u8 body_dec.bin body_jw.u8 body_met.u8` (corpo) e
   `qemw 0 7000 axe_pos.f32 axe_idx.u32 axe_col.u8 axe_dec.bin axe_jw.u8 axe_met.u8` (machado)
3. `python3 retarget.py` -> rig.bin + anim.bin (as 492 animações do kit antigo no esqueleto novo; precisa dos rig.bin/anim.bin antigos em ASSET). Pés e dedos dos pés usam rotação relativa ao repouso.
4. `python3 hero_mesh.py` (corpo + machado direito na mão 42 + machado esquerdo na mão 15) e `python3 export_msh.py` -> char.msh
5. `python3 verify_bin.py` e `python3 fkcheck.py` conferem os arquivos e a altura dos pés; `pose_prev.py` + `rast` geram prévias.
Formato do char.msh: cabeçalho 40 bytes (0, nv, nIdx, 28, ctr[3], ext[3]); vértice de 28 bytes = pos int16x4, normal int16x4, cor RGBA8 (RGB gama 2.0, A = metal), juntas u8x4, pesos u8x4; depois índices u32.
