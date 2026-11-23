#!/usr/bin/env python3
"""Monta guerreiro-visualizador-v99.html a partir do v98 (motor do Guerreiro) + pacote do Mercenario + shell novo.

uso: build_v99.py v98.html merc_manifest.json merc_blob.bin saida.html
"""
import sys, re, json, zlib, base64, os, io
HERE = os.path.dirname(os.path.abspath(__file__)); SRC = os.path.join(HERE, '..', 'src')
v98_path, mm_path, mb_path, out_path = sys.argv[1:5]

L = open(v98_path, encoding='utf8').read().split('\n')
def line(n): return L[n - 1]          # numeracao do v98 (1-based)

def patch(s, old, new, n=1):
    c = s.count(old)
    assert c == n, 'patch esperava %d ocorrencia(s) e achou %d: %r' % (n, c, old[:90])
    return s.replace(old, new)

# ------------------------------------------------------------------ dados do v98
man = json.loads(re.search(r'<script id="manifest" type="application/json">(.*)</script>', line(83), re.S).group(1))
blob = zlib.decompress(base64.b64decode(re.search(r'<script id="blob" type="text/plain">(.*)</script>', line(84), re.S).group(1)))
print('v98 blob', len(blob))

extras = {}
def add(name, data): extras[name] = data

# boneco de treino (linha 1550)
m = re.search(r"const bin=atob\('([A-Za-z0-9+/=]+)'\)", line(1550)); add('dummy', base64.b64decode(m.group(1)))
# atlas de efeitos (linha 681)
m = re.match(r'^const FXATL=\{src:"data:image/jpeg;base64,([A-Za-z0-9+/=]+)"(.*)$', line(681), re.S)
add('fxatl', base64.b64decode(m.group(1))); FXATL_REST = m.group(2)
# texturas de onda de choque (linha 1722)
for k, b in re.findall(r"(\w+):'data:image/png;base64,([^']+)'", line(1722)): add('swt.' + k, base64.b64decode(b))
# sons e falas (mp3)
def split_obj(ln, prefix):
    t = ln[len(prefix):]; obj, end = json.JSONDecoder().raw_decode(t); return obj, t[end:]
sfx, SFX_REST = split_obj(line(1910), 'const SFXD='); vox, VOX_REST = split_obj(line(1930), 'const VOXD=')
for k, b in sfx.items(): add('sfx.' + k, base64.b64decode(b))
for k, b in vox.items(): add('vox.' + k, base64.b64decode(b))
print('extras', len(extras), sum(len(v) for v in extras.values()))

raw = io.BytesIO(); raw.write(blob)
man['extra'] = {}
for k, d in extras.items():
    while raw.tell() % 4: raw.write(b'\0')
    man['extra'][k] = [raw.tell(), len(d)]; raw.write(d)
warrior_raw = raw.getvalue()
warrior_z = zlib.compress(warrior_raw, 9)
print('guerreiro raw %.1f MB -> %.1f MB' % (len(warrior_raw) / 1e6, len(warrior_z) / 1e6))
mman = json.load(open(mm_path)); mraw = open(mb_path, 'rb').read()
AUR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'auras')
mman['extra'] = {}; mb = io.BytesIO(); mb.write(mraw)
for k in ('flip', 'glow', 'shards'):
    d = open(os.path.join(AUR, 'aura_%s.png' % k), 'rb').read()
    while mb.tell() % 4: mb.write(b'\0')
    mman['extra']['aura.' + k] = [mb.tell(), len(d)]; mb.write(d)
mraw = mb.getvalue(); merc_z = zlib.compress(mraw, 9)
print('mercenario raw %.1f MB -> %.1f MB' % (len(mraw) / 1e6, len(merc_z) / 1e6))

# ------------------------------------------------------------------ prelude (inflate, matematica, shaders)
prelude = '\n'.join(L[86:233])        # linhas 87..233
prelude = patch(prelude, 'vec3 base=vec3(.105,.115,.14);vec3 c=base+vec3(.12,.13,.16)*l*uGrid*fade;', 'vec3 base=vec3(.07,.073,.092);vec3 c=base+vec3(.17,.15,.1)*l*uGrid*fade;')
prelude = patch(prelude, 'uniform float uRune,uTm,uRim;uniform vec3 uRC;', 'uniform float uRune,uTm,uRim,uGain;uniform vec3 uRC;')
prelude = patch(prelude, ' o=vec4(pow(c,vec3(1./2.2)),1.);}`;\nconst VS_G', ' if(uGain>0.)c*=uGain;\n o=vec4(pow(c,vec3(1./2.2)),1.);}`;\nconst VS_G')
prelude = patch(prelude, 'c+=vec3(.05,.055,.07)*smoothstep(3.,0.,r);', 'c+=vec3(.075,.065,.045)*smoothstep(3.,0.,r);')
# cenario selecionavel: cores/grade/chao por uniformes
prelude = patch(prelude, 'vec3 base=vec3(.07,.073,.092);vec3 c=base+vec3(.17,.15,.1)*l*uGrid*fade;', 'vec3 base=uBase;vec3 c=base+uLn*l*uGrid*fade;')
prelude = patch(prelude, 'c+=vec3(.075,.065,.045)*smoothstep(3.,0.,r);', 'c+=uGl*smoothstep(3.,0.,r);')
prelude = patch(prelude, 'uniform vec2 uSh;uniform float uGrid;out vec4 o;', 'uniform vec2 uSh;uniform float uGrid,uFl;uniform vec3 uBase,uLn,uGl;out vec4 o;')
prelude = patch(prelude, 'o=vec4(c,fade*.96+.04);}`', 'o=vec4(c,(fade*.96+.04)*uFl);}`')

# ------------------------------------------------------------------ motor do Guerreiro (linhas 236..2122)
app = L[235:2122]                     # indices 235..2121  => linhas 236..2122
def rep_lines(a, b, new):             # a..b inclusive, numeracao do v98
    for i in range(a, b + 1): app[i - 236] = None
    app[a - 236] = new
def lget(n): return app[n - 236]

# cabecalho: vem do shell
assert lget(236) == '(async function(){'
rep_lines(236, 245, "async function bootWarrior(SH){\nconst $=id=>document.getElementById(id);\nconst prog=SH.prog;\nconst man=SH.man,buf=SH.buf;\nconst cv=SH.cv,gl=SH.gl;")
# lista/chips do v98 -> a interface nova monta a lista a partir da API
assert lget(556).startswith('function buildList(){') and lget(557).startswith('const CH=$(')
rep_lines(556, 557, 'function buildList(){}')
# camera: compartilhada com o shell (o shell trata o arrastar/zoom)
l = lget(571)
l = patch(l, "const fitD=()=>Math.max(2.6,.85/(Math.tan(.4)*(cv.clientWidth/Math.max(1,cv.clientHeight))));", "const fitD=()=>SH.fitD();")
l = patch(l, "let cam={...CAM0};", "const cam=SH.cam;Object.assign(cam,CAM0);")
l = patch(l, "cam={...CAM0}", "Object.assign(cam,CAM0)", 2)
app[571 - 236] = l
for n in range(572, 579): assert lget(n) is not None
assert lget(572).startswith('const pts=new Map()') and lget(578).startswith("cv.addEventListener('wheel'")
rep_lines(572, 578, '')
l = lget(2083); l = patch(l, "cam={...CAM0}", "Object.assign(cam,CAM0)"); app[2083 - 236] = l
# projecao: recuo da interface (gaveta/barras) vem do shell
l = lget(625); l = patch(l, 'GAME.on?.62:.5,asp', 'GAME.on?.62:SH.fov(),asp'); l = patch(l, "P[9]=-Math.min(.9,$('panel').getBoundingClientRect().height/innerHeight);", "P[9]=window.GAME&&GAME.on?0:-SH.insetY();P[8]=window.GAME&&GAME.on?0:SH.insetX();"); app[625 - 236] = l
l = lget(624); l = patch(l, 'gl.clearColor(.078,.086,.105,1)', 'gl.clearColor(...SH.map().bg,1)'); app[624 - 236] = l
l = lget(630); l = patch(l, "gl.uniform1f(U(PG,'uGrid'),1);", 'SH.mapApply(U,PG,1);'); app[630 - 236] = l
l = lget(626); l = patch(l, 'const DD=cam.dist*1.656;', 'const DD=SH.dist()*1.656;cam.pitch=Math.max(cam.pitch,SH.pmin(cam.ty,DD));'); app[626 - 236] = l
# o laco de quadros e do shell
assert lget(654) == ' requestAnimationFrame(frame)}'
app[654 - 236] = ' }'
# dados pesados vem do bloco do personagem
l = lget(681); assert l.startswith('const FXATL={src:"data:image/jpeg;base64,'); app[681 - 236] = "const FXATL={src:SH.url('fxatl','image/jpeg')" + FXATL_REST
l = lget(813); l = patch(l, 'im.onload=()=>{', 'im.onload=()=>{if(!SH.alive())return;'); app[813 - 236] = l
l = lget(1550); assert l.startswith("(function(){const bin=atob('")
l = re.sub(r"const bin=atob\('[A-Za-z0-9+/=]+'\),", "const bin=SH.bin('dummy'),", l, count=1)
l = patch(l, "const u8=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)u8[i]=bin.charCodeAt(i);", "const u8=bin.slice();")
app[1550 - 236] = l
assert lget(1722).startswith("const SWT={n3:'data:image/png;base64,")
app[1722 - 236] = "const SWT={};for(const k of SH.keys('swt.'))SWT[k.slice(4)]=SH.url(k,'image/png');"
l = lget(1746); l = patch(l, 'im.onload=()=>{', 'im.onload=()=>{if(!SH.alive())return;'); app[1746 - 236] = l
assert lget(1910).startswith('const SFXD={'); app[1910 - 236] = 'const SFXD=null' + SFX_REST
l = lget(1914); l = patch(l, "for(const k in SFXD){const b=Uint8Array.from(atob(SFXD[k]),c=>c.charCodeAt(0)).buffer;ac.decodeAudioData(b,d=>{buf[k]=d},()=>{})}", "for(const k of SH.keys('sfx.')){const b=SH.extra(k),n=k.slice(4);ac.decodeAudioData(b,d=>{buf[n]=d},()=>{})}"); app[1914 - 236] = l
assert lget(1930).startswith('const VOXD={'); app[1930 - 236] = 'const VOXD=null' + VOX_REST
l = lget(1934); l = patch(l, "for(const k in VOXD){const b=Uint8Array.from(atob(VOXD[k]),c=>c.charCodeAt(0)).buffer;ac.decodeAudioData(b,d=>{buf[k]=d},()=>{})}", "for(const k of SH.keys('vox.')){const b=SH.extra(k),n=k.slice(4);ac.decodeAudioData(b,d=>{buf[n]=d},()=>{})}"); app[1934 - 236] = l
# sem legenda de fala na tela
assert lget(1935).startswith(' function sub(text,red,dur){'); app[1935 - 236] = ' function sub(){}'
# fim: devolve a API em vez de iniciar o laco
assert lget(2120) == "$('load').style.display='none';" and lget(2121) == 'requestAnimationFrame(frame);' and lget(2122).startswith('})().catch')
API = r"""
const KICON={basic:'i-s-basic',s1:'i-s-x',s2:'i-s-shield',s3:'i-s-slam',s4:'i-s-chain',ult:'i-s-skull'};
const legacyBtn=t=>[...document.querySelectorAll('#legacy button')].find(b=>b.textContent.startsWith(t));
const API={id:'guerreiro',title:'Guerreiro',
 clips:clips.map(c=>({name:c.name,cat:cat(c.name),dur:clipDur(c),nf:c.nf})),cats:cats.slice(1),
 kit:SKILLS.map(s=>({id:s.id,nome:s.nome,tag:s.tag,cd:s.cd,custo:s.custo,desc:s.desc,ult:s.id==='ult',icon:KICON[s.id]})),
 frame,state:()=>({cur,t,dur:clipDur(clips[cur]),playing,loop,speed,skill:skRun?skRun.sk.id:null,game:GAME.on}),
 setClip(i){skRun=null;pal=PAL.gold;TL=.55;setClip(i);playing=true},
 useSkill(id){if(id==='basic')basicPress();else skStart(id)},
 seek(f){t=f*clipDur(clips[cur]);playing=false},
 setPlaying(b){if(b&&!skRun&&!loop&&t>=clipDur(clips[cur])-1e-3)t=0;playing=b},
 setLoop(b){loop=b},setSpeed(x){speed=x},
 toggles:[
  {id:'w',label:'Armas',sub:'Mostrar os dois machados',icon:'i-axe',get:()=>showW,set:v=>{showW=v}},
  {id:'vfx',label:'Efeitos',sub:'Rastro dourado, faíscas e habilidades',icon:'i-sparkle',get:()=>vfxOn,set:v=>{vfxOn=v;if(!v)vfxClear()}},
  {id:'spin',label:'Girar câmera',sub:'Órbita automática em volta do personagem',icon:'i-rotate',get:()=>spin,set:v=>{spin=v}},
  {id:'tp',label:'Pose de repouso',sub:'Mostra o modelo parado, sem animação',icon:'i-person',get:()=>restMode,set:v=>{restMode=v}}],
 actions:[
  {id:'enm',icon:'i-target',label:'Alvo',dyn:()=>{const e=document.getElementById('enm');return e?e.textContent:'Alvo'},run:()=>BLD.cycle(),wide:true},
  {id:'dano',icon:'i-hurt',label:'Levar dano',run:()=>legacyBtn('Levar dano').click()},
  {id:'morrer',icon:'i-s-skull',label:'Morrer',tone:'red',run:()=>legacyBtn('Morrer').click()},
  {id:'arena',icon:'i-arena',label:'Arena (jogo)',run:()=>{GAME.on=true},wide:true}],
 dispose(){try{skRun=null;vfxClear();if(GAME.on)GAME.on=false}catch(e){}playing=false;frozen=true}};
return API;
}"""
app[2120 - 236] = API; app[2121 - 236] = ''; app[2122 - 236] = ''
app_js = '\n'.join(x for x in app if x is not None)

# ------------------------------------------------------------------ HTML
css = open(os.path.join(SRC, 'shell.css'), encoding='utf8').read()
body = open(os.path.join(SRC, 'body.html'), encoding='utf8').read()
shell = open(os.path.join(SRC, 'shell.js'), encoding='utf8').read()
shell = shell.replace('//__MERC_TAIL__', open(os.path.join(SRC, 'merc_tail.js'), encoding='utf8').read())
b64 = lambda b: base64.b64encode(b).decode('ascii')
html = f"""<!DOCTYPE html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#07080b">
<title>Visualizador v99 · Guerreiro &amp; Mercenário</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Cinzel:wght@500;600;700&family=Inter:wght@400;500;600;700&display=swap">
<style>
{css}
</style></head><body>
{body}
<script id="m_g" type="application/json">{json.dumps(man, separators=(',', ':'))}</script>
<script id="b_g" type="text/plain">{b64(warrior_z)}</script>
<script id="m_m" type="application/json">{json.dumps(mman, separators=(',', ':'))}</script>
<script id="b_m" type="text/plain">{b64(merc_z)}</script>
<script>
"use strict";
{prelude}
{app_js}
{shell}
</script></body></html>
"""
open(out_path, 'w', encoding='utf8').write(html)
print('html %.1f MB' % (os.path.getsize(out_path) / 1e6))
