package com.skaterparkour.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * Efeitos (SoundPool) + falas (fila: uma fala só começa quando a anterior termina).
 * Enquanto uma fala toca, os efeitos abaixam um pouco (ducking).
 */
class Audio(private val ctx: Context) {
    private val pool: SoundPool
    private val ids = HashMap<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var player: MediaPlayer? = null
    @Volatile var voiceOn = false
    private var hahCount = 0
    private var caiAt = 2 + Random.nextInt(2)
    private val loops = HashMap<String, Int>()

    val SFX = listOf(
        "swing1", "swing2", "swing3", "ding", "parry", "hit_body", "s1_charge", "s2_guard", "s2_wave", "s2_thud",
        "s3_rise", "s3_slam", "s3_boom", "chain_throw", "chain_snap", "chain_pull", "axe_whir", "ult_roar", "ult_aura",
        "ult_slam", "ult_debris", "ult_final", "impact", "s3_spin", "axe_hit1", "axe_hit2", "axe_hit3", "s1_launch", "s1_impact"
    )

    init {
        pool = SoundPool.Builder().setMaxStreams(14).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        for (k in SFX) {
            try { ids[k] = pool.load(ctx.assets.openFd("snd/$k.ogg"), 1) } catch (_: Exception) {}
        }
    }

    /** Toca um efeito. [delay] em segundos. */
    fun sfx(k: String, g: Float = 1f, rate: Float = 1f, delay: Float = 0f, loopKey: String? = null) {
        val id = ids[k] ?: return
        val run = Runnable {
            val v = clampf(g * (if (voiceOn) 0.6f else 1f), 0f, 1f)
            val s = pool.play(id, v, v, 1, 0, clampf(rate, 0.5f, 2f))
            if (loopKey != null) loops[loopKey] = s
        }
        if (delay > 0f) main.postDelayed(run, (delay * 1000).toLong()) else main.post(run)
    }

    fun stop(loopKey: String) { main.post { loops.remove(loopKey)?.let { pool.stop(it) } } }

    /** Fala do personagem. */
    fun voice(k: String, dropIfBusy: Boolean = false) {
        main.post {
            if (dropIfBusy && (player != null || queue.isNotEmpty())) return@post
            if (queue.size >= 3) queue.removeFirst()
            queue.addLast(k)
            if (player == null) next()
        }
    }

    /** Grito do básico: "Hah!" e, a cada 2–3, "Cai!". */
    fun basicShout() {
        hahCount++
        if (hahCount >= caiAt) { hahCount = 0; caiAt = 2 + Random.nextInt(2); voice("cai", true) } else voice("hah", true)
    }

    private fun next() {
        val k = queue.removeFirstOrNull() ?: run { voiceOn = false; return }
        try {
            val fd = ctx.assets.openFd("snd/v_$k.ogg")
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setVolume(1f, 1f)
            mp.setOnCompletionListener { it.release(); player = null; main.postDelayed({ next() }, 60) }
            mp.prepare()
            mp.start()
            player = mp
            voiceOn = true
        } catch (e: Exception) {
            player = null; next()
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        player?.release(); player = null
        pool.release()
    }
}
