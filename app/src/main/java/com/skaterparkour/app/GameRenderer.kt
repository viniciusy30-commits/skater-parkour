package com.skaterparkour.app

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.Buffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(private val ctx: Context) : GLSurfaceView.Renderer {

    private val world = World()

    // modelo e animação
    private lateinit var model: SkinModel
    private lateinit var skeleton: Skeleton
    // 0 idle, 1 correr, 2 (livre), 3 pular na parede, 4 pendurado, 5 subir,
    // 6 andar, 7 sprint, 8 ar subindo, 9 ar descendo, 10 ação (rolamento), 11 pouso,
    // 12 decolagem, 13 ar para frente, 14 partida/parada, 15..20 curvas (andar, correr, sprint; esq/dir),
    // 21 meia-volta / giro no lugar
    private val NC = 22
    private val clipArr = arrayOfNulls<Clip>(NC)
    private val wts = FloatArray(NC).also { it[0] = 1f }
    private val tgt = FloatArray(NC)
    private val fw = FloatArray(NC)
    private val tClip = FloatArray(NC)
    private val gaitIdx = intArrayOf(6, 1, 7, 15, 16, 17, 18, 19, 20)

    // decolagem
    private var takeClip: Clip? = null
    private var takeT = 0f
    private var takeAge = 0f
    private var takeRate = 1f
    private var jumpUpStart: Clip? = null
    private var jumpFrontL: Clip? = null
    private var jumpFrontR: Clip? = null
    // pouso (começa antes de tocar o chão, para os pés encostarem junto com a animação)
    private var landClip: Clip? = null
    private var landT = 0f
    private var landAge = 0f
    private var landContact = 0f
    private var landStanding = true
    private var nextContact = 0f
    private var nextStanding = true
    private var landLight: Clip? = null
    private var landHeavy: Clip? = null
    private var jumpFinish: Clip? = null
    private var jumpUpFinish: Clip? = null
    // meia-volta correndo e giro no lugar
    private var turnClip: Clip? = null
    private var turnT = 0f
    private var turnRate = 1f
    private var turnD = 0f
    private var turnEnd = 0f
    private var run180L: Clip? = null
    private var run180R: Clip? = null
    private var sprint180L: Clip? = null
    private var sprint180R: Clip? = null
    private var rot45L: Clip? = null
    private var rot45R: Clip? = null
    private var rot90L: Clip? = null
    private var rot90R: Clip? = null
    private var rot180L: Clip? = null
    private var rot180R: Clip? = null
    private val rootBone by lazy { model.boneIndex("root").let { if (it < 0) 0 else it } }
    // partida e parada (andar, correr, sprint)
    private var gaitClip: Clip? = null
    private var gaitT = 0f
    private var gaitAge = 0f
    private var gaitFade = 1f
    private var gaitStop = false
    private var startArmed = true
    private var prevTarget = 0f
    private var walkStart: Clip? = null
    private var walkStop: Clip? = null
    private var runStart: Clip? = null
    private var runStop: Clip? = null
    private var sprintStart: Clip? = null
    private var sprintStop: Clip? = null
    private var gaitPhase = 0f
    // todas as animações do pacote (para as próximas ações do jogo)
    var allClips: Map<String, Clip> = emptyMap()
    private lateinit var skinOut: FloatArray
    private val qBlend by lazy { FloatArray(model.boneCount * 4) }
    private val qTmp by lazy { FloatArray(model.boneCount * 4) }
    private val hipsBlend = FloatArray(3)
    private val hipsTmp = FloatArray(3)

    // GL
    private var progSkin = 0
    private var progWorld = 0
    private var progShadow = 0
    private var loadedChar = -1
    private val skinBufs = ArrayList<Int>()
    private var groundOffset = 0f
    private var texBody = 0
    private var texNormal = 0
    private var vaoSkin = 0
    private var vaoCube = 0
    private var cubeIndexCount = 0
    private var vaoQuad = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val modelM = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val camPos = FloatArray(3)
    private var camTx = 0f
    private var camTy = 1f
    private var camTz = -4f
    private var lastNs = 0L
    private var aspect = 1.7f

    private val fogR = 0.62f
    private val fogG = 0.76f
    private val fogB = 0.92f

    /** Carrega (ou troca) o personagem: 0 = original, 1 = anime. Roda na thread GL. */
    private fun loadCharacter(id: Int) {
        val suf = if (id == 1) "_b" else ""
        if (texBody != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(texBody, texNormal), 0)
            texBody = 0
            texNormal = 0
        }
        if (vaoSkin != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoSkin), 0)
        if (skinBufs.isNotEmpty()) GLES30.glDeleteBuffers(skinBufs.size, skinBufs.toIntArray(), 0)
        skinBufs.clear()
        model = SkinModel(ctx, suf)
        skeleton = Skeleton(model)
        skinOut = FloatArray(model.boneCount * 12)
        val clips = Clip.loadAll(ctx, suf)
        clipArr[0] = clips["idle"]
        clipArr[1] = clips["run"]
        clipArr[3] = clips["jump"]
        clipArr[4] = clips["hang"]
        clipArr[5] = clips["climb"]
        val all = Clip.loadPacked(ctx, suf)
        allClips = all
        clipArr[6] = all["A_Walk_IP"]
        clipArr[7] = all["A_Sprint_IP"]
        clipArr[8] = all["A_Fly_Up"]
        clipArr[9] = all["A_Fly_Down"]
        clipArr[13] = all["A_Fly_Front"]
        clipArr[15] = all["A_Walk_Arc_L_IP"]
        clipArr[16] = all["A_Walk_Arc_R_IP"]
        clipArr[17] = all["A_Run_Arc_L_IP"]
        clipArr[18] = all["A_Run_Arc_R_IP"]
        clipArr[19] = all["A_Sprint_Arc_L_IP"]
        clipArr[20] = all["A_Sprint_Arc_R_IP"]
        jumpUpStart = all["A_JumpUp_Start"]
        jumpFrontL = all["A_Jump_Front_L_Start"]
        jumpFrontR = all["A_Jump_Front_R_Start"]
        landLight = all["A_Landing_Down_Light"]
        landHeavy = all["A_Landing_Down"]
        jumpFinish = all["A_Jump_Finish"]
        jumpUpFinish = all["A_JumpUp_Finish"]
        run180L = all["A_Run_180_L"]
        run180R = all["A_Run_180_R"]
        sprint180L = all["A_Sprint_180_L"]
        sprint180R = all["A_Sprint_180_R"]
        rot45L = all["A_Rotate45_L"]
        rot45R = all["A_Rotate45_R"]
        rot90L = all["A_Rotate90_L"]
        rot90R = all["A_Rotate90_R"]
        rot180L = all["A_Rotate180_L"]
        rot180R = all["A_Rotate180_R"]
        walkStart = all["A_Walk_Start"]
        walkStop = all["A_Walk_Stop"]
        runStart = all["A_Run_Start"]
        runStop = all["A_Run_Stop"]
        sprintStart = all["A_Sprint_Start"]
        sprintStop = all["A_Sprint_Stop"]
        world.setRolls(
            listOfNotNull(
                all["A_Landing_Roll_A_L"], all["A_Landing_Roll_A_R"],
                all["A_Landing_Roll_B_L"], all["A_Landing_Roll_B_R"]
            )
        )
        val cj = clips["jump"]
        val cc = clips["climb"]
        if (cj != null && cc != null) world.setClips(cj, cc)

        texBody = loadTexture(if (id == 1) "body_b.jpg" else "body.jpg")
        texNormal = loadTexture(if (id == 1) "normal_b.jpg" else "normal.png")

        buildSkinVao()
        groundOffset = if (id == 1) 0.01f else GROUND_OFFSET
        loadedChar = id
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(fogR, fogG, fogB, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        progSkin = program(Shaders.SKIN_VS, Shaders.SKIN_FS)
        progWorld = program(Shaders.WORLD_VS, Shaders.WORLD_FS)
        progShadow = program(Shaders.SHADOW_VS, Shaders.SHADOW_FS)

        loadCharacter(Input.character)
        buildCubeVao()
        buildQuadVao()
        lastNs = System.nanoTime()
        camTx = world.x; camTy = world.y + 0.9f; camTz = world.z
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 52f, aspect, 0.1f, 220f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (Input.character != loadedChar) loadCharacter(Input.character)
        val now = System.nanoTime()
        var dt = (now - lastNs) / 1_000_000_000f
        lastNs = now
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f

        world.update(dt)
        updateAnimation(dt)
        updateCamera(dt)

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        drawWorld()
        if (world.mode == 0 || world.mode == 4) drawShadow()
        drawCharacter()
    }

    // ---------------------------------------------------------------- animação

    private fun approach(v: Float, target: Float, k: Float): Float = v + (target - v) * k

    /** Pé que estará apoiado no chão daqui a pouco (fase do passo: esquerdo perto de 0, direito perto de 0,5). */
    private fun leftPlanted(p: Float): Boolean = p < 0.25f || p >= 0.7f

    /** Achata a subida do quadril das poses de ar: a altura do pulo já vem da física. */
    private fun airY(y: Float): Float = if (y > 0.56f) 0.56f + (y - 0.56f) * 0.25f else y

    private fun startTurn() {
        val d = world.turnD
        val left = d > 0f
        val ad = Math.abs(d)
        val c: Clip?
        var re: Float
        if (world.turnKind == 0) {
            c = if (world.turnSpeed > 3.2f) (if (left) sprint180L else sprint180R) else (if (left) run180L else run180R)
            re = Math.PI.toFloat()
        } else if (ad >= 2.36f) {
            c = if (left) rot180L else rot180R
            re = Math.PI.toFloat()
        } else if (ad >= 1.18f) {
            c = if (left) rot90L else rot90R
            re = (Math.PI / 2).toFloat()
        } else {
            c = if (left) rot45L else rot45R
            re = (Math.PI / 4).toFloat()
        }
        if (!left) re = -re
        turnClip = c
        turnT = 0f
        turnD = d
        turnEnd = re
        turnRate = if (c != null && world.turnLockTotal > 0.05f) c.duration / world.turnLockTotal else 1f
        gaitClip = null
    }

    private fun startTakeoff() {
        landClip = null
        gaitClip = null
        turnClip = null
        val running = world.jumpSpeed > 1.5f
        val c: Clip?
        val startT: Float
        val launchT: Float
        if (running) {
            var pp = gaitPhase + 0.2f * (world.jumpSpeed / 3.8f)
            pp -= Math.floor(pp.toDouble()).toFloat()
            c = if (leftPlanted(pp)) jumpFrontL else jumpFrontR
            startT = 1f / 30f
            launchT = 5f / 30f
        } else {
            c = jumpUpStart
            startT = 3f / 30f
            launchT = 13f / 30f
        }
        takeClip = c
        takeT = startT
        takeAge = 0f
        takeRate = if (world.jumpWind > 0.01f) (launchT - startT) / world.jumpWind else 1f
    }

    /** Escolhe a animação de pouso pela velocidade de impacto; devolve null se não precisa de nenhuma. */
    private fun chooseLanding(vImp: Float, speed: Float): Clip? {
        if (vImp < 2.2f) return null
        if (vImp > World.ROLL_VY && speed > 1.5f) return null // o mundo faz o rolamento
        nextStanding = speed <= 1.2f
        nextContact = 0f
        if (vImp > World.ROLL_VY) return landHeavy
        if (speed > 1.2f) {
            if (vImp < 3.0f) return null
            return jumpFinish
        }
        if (vImp >= 4.2f) {
            nextContact = 5f / 30f
            return jumpUpFinish
        }
        return landLight
    }

    private fun updateAnimation(dt: Float) {
        val run = clipArr[1] ?: return
        val mode = world.mode
        val grounded = world.grounded

        // ---- decolagem: agacha, sai do chão e abre o corpo (vertical parado, passada correndo)
        if (world.jumpEvent) {
            world.jumpEvent = false
            if (mode == 0) startTakeoff()
        }

        if (world.turnEvent) {
            world.turnEvent = false
            if (mode == 0 && grounded) startTurn()
        }

        // ---- pouso: começa um pouco antes de tocar o chão
        if (mode == 0 && !grounded && landClip == null) {
            val h = (world.y - world.supportY).coerceAtLeast(0f)
            val disc = world.vy * world.vy + 2f * World.GRAVITY * h
            val sq = Math.sqrt(disc.toDouble()).toFloat()
            val tl = (world.vy + sq) / World.GRAVITY
            if (world.vy < 1f && tl > 0f) {
                val c = chooseLanding(sq, world.speed)
                if (c != null && tl <= Math.max(nextContact, 0.07f)) {
                    landClip = c
                    landContact = nextContact
                    landStanding = nextStanding
                    landT = nextContact - tl
                    landAge = 0f
                    takeClip = null
                    gaitClip = null
                    turnClip = null
                }
            }
        }
        if (world.landEvent) {
            world.landEvent = false
            // se a previsão não pegou (queda repentina), usa o pouso na hora
            if (mode == 0 && landClip == null && world.landVy < -2.5f) {
                val c = chooseLanding(-world.landVy, world.speed)
                if (c != null) {
                    landClip = c
                    landContact = nextContact
                    landStanding = nextStanding
                    landT = nextContact
                    landAge = 0f
                    takeClip = null
                    gaitClip = null
                }
            }
        }

        var wL = 0f
        val lc = landClip
        if (lc != null) {
            landAge += dt
            landT += dt
            if (!grounded) landT = Math.min(landT, landContact)
            if (landT >= lc.duration || mode != 0) {
                landClip = null
            } else {
                val fin = (landAge / 0.08f).coerceIn(0f, 1f)
                val fout = ((lc.duration - landT) / 0.14f).coerceIn(0f, 1f)
                wL = Math.min(fin, fout)
                if (landStanding) {
                    val still = (1f - (world.speed - 0.4f) / 1.6f).coerceIn(0f, 1f)
                    wL *= 0.3f + 0.7f * still
                }
            }
        }

        var wT = 0f
        val tc = takeClip
        if (tc != null) {
            takeAge += dt
            takeT += dt * (if (grounded) takeRate else 1f)
            if (takeT >= tc.duration || mode != 0 || landClip != null) {
                takeClip = null
            } else {
                val fin = (takeAge / 0.07f).coerceIn(0f, 1f)
                val fout = ((tc.duration - takeT) / 0.14f).coerceIn(0f, 1f)
                wT = Math.min(fin, fout)
            }
        }

        var wN = 0f
        val nc = turnClip
        if (nc != null) {
            turnT += dt * turnRate
            if (turnT >= nc.duration || mode != 0 || !grounded || takeClip != null || landClip != null) {
                turnClip = null
            } else {
                // entra com peso total (o corpo já começa virado para o lado antigo) e some no fim
                wN = ((nc.duration - turnT) / 0.14f).coerceIn(0f, 1f)
            }
        }

        // ---- partida e parada de andar/correr/sprint
        val tsp = world.targetSpeed
        if (tsp < 0.25f) startArmed = true
        if (mode != 0 || !grounded || takeClip != null || landClip != null || turnClip != null) gaitClip = null
        if (gaitClip == null && mode == 0 && grounded && takeClip == null && landClip == null && turnClip == null) {
            if (startArmed && world.speed < 0.45f && tsp > 0.5f) {
                startArmed = false
                gaitClip = if (tsp < 1.7f) walkStart else if (tsp < 3.2f) runStart else sprintStart
                gaitStop = false
                gaitT = 0f; gaitAge = 0f; gaitFade = 1f
            } else if (prevTarget >= 0.25f && tsp < 0.25f && world.speed > 0.9f) {
                val sp = world.speed
                gaitClip = if (sp < 1.8f) walkStop else if (sp < 3.2f) runStop else sprintStop
                gaitStop = true
                gaitT = 0f; gaitAge = 0f; gaitFade = 1f
            }
        }
        prevTarget = tsp
        var wG = 0f
        var holdPhase = false
        val gc = gaitClip
        if (gc != null) {
            gaitAge += dt
            gaitT += dt
            // se o jogador muda de ideia, a animação some rápido
            val cancel = if (gaitStop) tsp > 0.5f else tsp < 0.25f
            gaitFade = if (cancel) gaitFade - dt / 0.12f else Math.min(1f, gaitFade + dt / 0.12f)
            if (gaitT >= gc.duration || gaitFade <= 0f) {
                gaitClip = null
            } else {
                val fin = (gaitAge / 0.05f).coerceIn(0f, 1f)
                val fout = ((gc.duration - gaitT) / 0.14f).coerceIn(0f, 1f)
                wG = Math.min(fin, fout) * gaitFade
                // a partida termina no passo da corrida (fase 0): o ciclo começa dali
                if (!gaitStop && gc.duration - gaitT > 0.14f) holdPhase = true
            }
        }
        // o pouso correndo termina na mesma pose (pé direito à frente)
        if (landClip != null && landClip === jumpFinish && jumpFinish!!.duration - landT > 0.16f) holdPhase = true

        java.util.Arrays.fill(tgt, 0f)
        if (mode == 0) {
            val air = if (grounded) 0f else 1f
            val g = 1f - air
            val s = world.speed
            val mv = ((s - 0.2f) / 0.6f).coerceIn(0f, 1f)
            val rs = ((s - 1.2f) / 1.2f).coerceIn(0f, 1f)
            val ss = ((s - 2.8f) / 0.9f).coerceIn(0f, 1f)
            val arc = (Math.abs(world.yawRate) / 2.5f).coerceIn(0f, 1f)
            val aL = if (world.yawRate > 0f) arc else 0f
            val aR = if (world.yawRate < 0f) arc else 0f
            val wk = g * mv * (1f - rs)
            val rn = g * mv * rs * (1f - ss)
            val sp = g * mv * rs * ss
            tgt[0] = g * (1f - mv)
            tgt[6] = wk * (1f - arc)
            tgt[1] = rn * (1f - arc)
            tgt[7] = sp * (1f - arc)
            tgt[15] = wk * aL
            tgt[16] = wk * aR
            tgt[17] = rn * aL
            tgt[18] = rn * aR
            tgt[19] = sp * aL
            tgt[20] = sp * aR
            val up = ((world.vy + 1f) / 3f).coerceIn(0f, 1f)
            val fm = ((s - 1f) / 1.5f).coerceIn(0f, 1f)
            tgt[8] = air * up * (1f - fm)
            tgt[13] = air * fm
            tgt[9] = air * (1f - up) * (1f - fm)
        } else if (mode == 4) {
            tgt[10] = 1f
        } else {
            tgt[2 + mode] = 1f
        }

        val k = (dt * (if (mode == 0) (if (landClip != null) 16f else 10f) else 12f)).coerceIn(0f, 1f)
        var sum = 0f
        for (i in 0 until NC) {
            if (i == 11 || i == 12 || i == 14 || i == 21) continue
            wts[i] = approach(wts[i], tgt[i], k)
            sum += wts[i]
        }
        if (sum < 0.0001f) {
            wts[0] = 1f
            sum = 1f
        }

        // camadas (pouso > decolagem > partida/parada) por cima da base
        val lw = wL.coerceIn(0f, 1f)
        val tw = Math.min(wT, 1f - lw)
        val nw = Math.min(wN, 1f - lw - tw).coerceAtLeast(0f)
        val gw = Math.min(wG, 1f - lw - tw - nw).coerceAtLeast(0f)
        val baseScale = (1f - lw - tw - nw - gw).coerceAtLeast(0f)
        for (i in 0 until NC) fw[i] = wts[i] / sum * baseScale
        fw[11] = lw
        fw[12] = tw
        fw[14] = gw
        fw[21] = nw

        // andar, correr e sprint dividem a mesma fase de passo (distância por ciclo de cada um)
        tClip[0] += dt
        tClip[8] += dt
        tClip[9] += dt
        tClip[13] += dt
        val wWalk = wts[6] + wts[15] + wts[16]
        val wRun = wts[1] + wts[17] + wts[18]
        val wSpr = wts[7] + wts[19] + wts[20]
        val wsum = wWalk + wRun + wSpr
        if (wsum > 0.001f) {
            val stride = (wWalk * 0.899f + wRun * 1.709f + wSpr * 2.132f) / wsum
            gaitPhase += dt * world.speed / stride
            gaitPhase -= Math.floor(gaitPhase.toDouble()).toFloat()
        }
        if (holdPhase) gaitPhase = 0f
        for (i in gaitIdx) {
            tClip[i] = gaitPhase * (clipArr[i]?.duration ?: 0f)
        }
        if (mode == 1) tClip[3] = world.clipT
        if (mode == 2) tClip[4] = world.modeT
        if (mode == 3) tClip[5] = world.modeT
        if (mode == 4) tClip[10] = world.modeT
        tClip[11] = landT
        tClip[12] = takeT
        tClip[14] = gaitT
        tClip[21] = turnT

        java.util.Arrays.fill(qBlend, 0f)
        java.util.Arrays.fill(hipsBlend, 0f)
        var first = true
        val bones = model.boneCount

        for (i in 0 until NC) {
            val w = fw[i]
            if (w < 0.001f) continue
            val clip: Clip? = when (i) {
                10 -> world.actionClip
                11 -> landClip
                12 -> takeClip
                14 -> gaitClip
                21 -> turnClip
                else -> clipArr[i]
            }
            if (clip == null) continue
            when (i) {
                3, 5, 10, 11, 12, 14, 21 -> clip.sample(tClip[i], false, qTmp, hipsTmp)
                else -> clip.sample(tClip[i], true, qTmp, hipsTmp)
            }
            if (i in 3..5) {
                hipsTmp[0] -= world.offX
                hipsTmp[1] -= world.offY
                hipsTmp[2] -= world.offZ
            }
            if (i == 10) {
                // o deslocamento do rolamento vem do mundo; aqui o corpo fica no lugar
                hipsTmp[0] = world.apx0
                hipsTmp[2] = world.apz0
            }
            if (i == 11 || i == 12 || i == 14) {
                // o deslocamento vem da física; só a altura do quadril vem da animação (sem somar a subida do pulo)
                hipsTmp[0] = 0f
                hipsTmp[2] = 0.01f
                if (i != 14) hipsTmp[1] = airY(hipsTmp[1])
            }
            if (i == 21) {
                // o clip gira o corpo; o mundo já virou, então gira o osso raiz de volta e vai desfazendo até alinhar
                hipsTmp[0] = 0f
                hipsTmp[2] = 0.01f
                val u = (tClip[21] / clip.duration).coerceIn(0f, 1f)
                val phi = -turnD - (turnEnd - turnD) * u
                val sh = sin(phi * 0.5f)
                val ch = cos(phi * 0.5f)
                val ro = rootBone * 4
                val bx = qTmp[ro]
                val by = qTmp[ro + 1]
                val bz = qTmp[ro + 2]
                val bw = qTmp[ro + 3]
                qTmp[ro] = ch * bx + sh * bz
                qTmp[ro + 1] = ch * by + sh * bw
                qTmp[ro + 2] = ch * bz - sh * bx
                qTmp[ro + 3] = ch * bw - sh * by
            }
            for (k2 in 0 until 3) hipsBlend[k2] += hipsTmp[k2] * w
            for (b in 0 until bones) {
                val o = b * 4
                var sg = 1f
                if (!first) {
                    val dot = qBlend[o] * qTmp[o] + qBlend[o + 1] * qTmp[o + 1] + qBlend[o + 2] * qTmp[o + 2] + qBlend[o + 3] * qTmp[o + 3]
                    if (dot < 0f) sg = -1f
                }
                qBlend[o] += qTmp[o] * w * sg
                qBlend[o + 1] += qTmp[o + 1] * w * sg
                qBlend[o + 2] += qTmp[o + 2] * w * sg
                qBlend[o + 3] += qTmp[o + 3] * w * sg
            }
            first = false
        }
        for (b in 0 until bones) {
            val o = b * 4
            val n = Math.sqrt((qBlend[o] * qBlend[o] + qBlend[o + 1] * qBlend[o + 1] + qBlend[o + 2] * qBlend[o + 2] + qBlend[o + 3] * qBlend[o + 3]).toDouble()).toFloat()
            if (n > 1e-6f) {
                qBlend[o] /= n; qBlend[o + 1] /= n; qBlend[o + 2] /= n; qBlend[o + 3] /= n
            } else {
                qBlend[o + 3] = 1f
            }
        }
        skeleton.update(qBlend, hipsBlend, skinOut)
    }

    // ---------------------------------------------------------------- câmera

    private fun updateCamera(dt: Float) {
        val k = (dt * 8f).coerceIn(0f, 1f)
        // alvo da câmera: quadril do personagem (acompanha a subida e o pulo na parede)
        val cyw = cos(world.yaw)
        val syw = sin(world.yaw)
        val hx = world.x + hipsBlend[0] * cyw + hipsBlend[2] * syw
        val hz = world.z - hipsBlend[0] * syw + hipsBlend[2] * cyw
        val hy = world.y + 0.35f + hipsBlend[1]
        camTx += (hx - camTx) * k
        camTy += (hy - camTy) * k
        camTz += (hz - camTz) * k
        val dist = 3.4f
        val cp = cos(world.camPitch)
        val sp = sin(world.camPitch)
        camPos[0] = camTx - sin(world.camYaw) * cp * dist
        camPos[1] = camTy + sp * dist
        camPos[2] = camTz - cos(world.camYaw) * cp * dist
        if (camPos[1] < 0.25f) camPos[1] = 0.25f
        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2], camTx, camTy, camTz, 0f, 1f, 0f)
    }

    // ---------------------------------------------------------------- desenho

    private fun drawWorld() {
        GLES30.glUseProgram(progWorld)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progWorld, "uVP"), 1, false, vp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progWorld, "uCam"), camPos[0], camPos[1], camPos[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progWorld, "uFog"), fogR, fogG, fogB)
        val uModel = GLES30.glGetUniformLocation(progWorld, "uModel")
        val uColor = GLES30.glGetUniformLocation(progWorld, "uColor")
        val uGrid = GLES30.glGetUniformLocation(progWorld, "uGrid")
        GLES30.glBindVertexArray(vaoCube)

        // chão
        Matrix.setIdentityM(modelM, 0)
        Matrix.translateM(modelM, 0, 0f, -1f, 0f)
        Matrix.scaleM(modelM, 0, 400f, 1f, 400f)
        GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
        GLES30.glUniform3f(uColor, 0.46f, 0.50f, 0.56f)
        GLES30.glUniform1f(uGrid, 1f)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, cubeIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)

        for (b in world.blocks) {
            Matrix.setIdentityM(modelM, 0)
            Matrix.translateM(modelM, 0, b.cx, 0f, b.cz)
            Matrix.scaleM(modelM, 0, b.sx, b.h, b.sz)
            GLES30.glUniformMatrix4fv(uModel, 1, false, modelM, 0)
            GLES30.glUniform3f(uColor, b.r, b.g, b.b)
            GLES30.glUniform1f(uGrid, 1f)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, cubeIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawShadow() {
        GLES30.glUseProgram(progShadow)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        val h = if (world.grounded) world.y else world.supportY
        val s = 0.55f - (world.y - h).coerceIn(0f, 1.5f) * 0.12f
        Matrix.setIdentityM(modelM, 0)
        Matrix.translateM(modelM, 0, world.x, h + 0.012f, world.z)
        Matrix.scaleM(modelM, 0, s, 1f, s)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progShadow, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progShadow, "uModel"), 1, false, modelM, 0)
        GLES30.glBindVertexArray(vaoQuad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawCharacter() {
        GLES30.glUseProgram(progSkin)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        Matrix.setIdentityM(tmpM, 0)
        Matrix.translateM(tmpM, 0, world.x, world.y + groundOffset, world.z)
        Matrix.rotateM(tmpM, 0, Math.toDegrees(world.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progSkin, "uVP"), 1, false, vp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(progSkin, "uModel"), 1, false, tmpM, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(progSkin, "uBones"), model.boneCount * 3, skinOut, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(progSkin, "uCam"), camPos[0], camPos[1], camPos[2])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texBody)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progSkin, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texNormal)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(progSkin, "uNrm"), 1)
        GLES30.glBindVertexArray(vaoSkin)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, model.indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    // ---------------------------------------------------------------- recursos GL

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("Erro no shader: $log")
        }
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw RuntimeException("Erro ao ligar programa: " + GLES30.glGetProgramInfoLog(p))
        }
        return p
    }

    private fun loadTexture(name: String): Int {
        val bmp = ctx.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        bmp.recycle()
        return id[0]
    }

    private fun floatBuf(a: FloatArray): Buffer {
        val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a)
        fb.position(0)
        return fb
    }

    private fun shortBuf(a: ShortArray): Buffer {
        val sb = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(a)
        sb.position(0)
        return sb
    }

    private fun byteBuf(a: ByteArray): Buffer {
        val bb = ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder())
        bb.put(a)
        bb.position(0)
        return bb
    }

    private fun vbo(target: Int, data: Buffer, bytes: Int): Int {
        val id = IntArray(1)
        GLES30.glGenBuffers(1, id, 0)
        GLES30.glBindBuffer(target, id[0])
        GLES30.glBufferData(target, bytes, data, GLES30.GL_STATIC_DRAW)
        return id[0]
    }

    private fun newVao(): Int {
        val id = IntArray(1)
        GLES30.glGenVertexArrays(1, id, 0)
        GLES30.glBindVertexArray(id[0])
        return id[0]
    }

    private fun buildSkinVao() {
        vaoSkin = newVao()
        skinBufs.add(vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.pos), model.pos.size * 4))
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        skinBufs.add(vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.nor), model.nor.size * 4))
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
        skinBufs.add(vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.uv), model.uv.size * 4))
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 0, 0)
        skinBufs.add(vbo(GLES30.GL_ARRAY_BUFFER, byteBuf(model.joints), model.joints.size))
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_BYTE, false, 0, 0)
        skinBufs.add(vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(model.weights), model.weights.size * 4))
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 0, 0)
        skinBufs.add(vbo(GLES30.GL_ELEMENT_ARRAY_BUFFER, shortBuf(model.indices), model.indices.size * 2))
        GLES30.glBindVertexArray(0)
    }

    private fun buildCubeVao() {
        // cubo unitário: x,z em [-0.5,0.5], y em [0,1]
        val p = ArrayList<Float>()
        val n = ArrayList<Float>()
        val idx = ArrayList<Short>()
        fun face(nx: Float, ny: Float, nz: Float, c: FloatArray) {
            val base = (p.size / 3).toShort()
            for (i in 0 until 4) {
                p.add(c[i * 3]); p.add(c[i * 3 + 1]); p.add(c[i * 3 + 2])
                n.add(nx); n.add(ny); n.add(nz)
            }
            idx.add(base); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
            idx.add(base); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
        }
        val a = -0.5f
        val b = 0.5f
        // +Y
        face(0f, 1f, 0f, floatArrayOf(a, 1f, b, b, 1f, b, b, 1f, a, a, 1f, a))
        // -Y
        face(0f, -1f, 0f, floatArrayOf(a, 0f, a, b, 0f, a, b, 0f, b, a, 0f, b))
        // +Z
        face(0f, 0f, 1f, floatArrayOf(a, 0f, b, b, 0f, b, b, 1f, b, a, 1f, b))
        // -Z
        face(0f, 0f, -1f, floatArrayOf(b, 0f, a, a, 0f, a, a, 1f, a, b, 1f, a))
        // +X
        face(1f, 0f, 0f, floatArrayOf(b, 0f, b, b, 0f, a, b, 1f, a, b, 1f, b))
        // -X
        face(-1f, 0f, 0f, floatArrayOf(a, 0f, a, a, 0f, b, a, 1f, b, a, 1f, a))
        cubeIndexCount = idx.size
        vaoCube = newVao()
        val pa = p.toFloatArray()
        val na = n.toFloatArray()
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(pa), pa.size * 4)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(na), na.size * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 0, 0)
        val ia = idx.toShortArray()
        vbo(GLES30.GL_ELEMENT_ARRAY_BUFFER, shortBuf(ia), ia.size * 2)
        GLES30.glBindVertexArray(0)
    }

    private fun buildQuadVao() {
        val q = floatArrayOf(
            -1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f,
            -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f
        )
        vaoQuad = newVao()
        vbo(GLES30.GL_ARRAY_BUFFER, floatBuf(q), q.size * 4)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        const val GROUND_OFFSET = 0f
    }
}
