# Como a arena foi gerada (GLB Meshy de 220 MB -> app/src/main/assets/w/arena*)
1. Extrair do GLB: pos.f32, uv.f32, idx.u32, base.jpg; cor por vértice amostrando base.jpg no UV (col.u8, gama sqrt).
2. `qem 0 700000 pos.f32 idx.u32 col.u8 stage1.bin` (solda e simplifica para 700 mil triângulos)
3. `python3 vis.py 6 120` (usa rast): marca triângulos visíveis de dentro da arena e descarta o resto; depois `qem 1 400000 stage2.bin x x stage3.bin`
4. `col stage3.bin 0.00024 0.001 -0.2079 38 arena_col.bin 5.0 0.3 0.1` -> caixas de colisão (53 mil)
5. `python3 build_arena.py` -> arena.msh (malha com AO assada, quinas vivas, blocos) e arena_detail.png
Escala: 38 (1 unidade do GLB = 38 m); piso da arena em y=0.
