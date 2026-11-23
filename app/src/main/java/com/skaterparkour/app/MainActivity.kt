package com.skaterparkour.app

import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** Campo de treino do Guerreiro — tudo nativo (OpenGL ES 3). */
class MainActivity : AppCompatActivity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var audio: Audio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = Audio(applicationContext)
        val renderer = Renderer(applicationContext, audio)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(Chooser())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        // desempenho: renderiza o 3D numa resolução interna menor (no máx. 1080 px de altura) e o sistema amplia
        run {
            val dm = resources.displayMetrics
            val w = maxOf(dm.widthPixels, dm.heightPixels); val h = minOf(dm.widthPixels, dm.heightPixels)
            if (h > 1080) { val k = 1080f / h; glView.holder.setFixedSize((w * k).toInt(), 1080) }
        }
        val root = FrameLayout(this)
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(Hud(this, renderer) { startActivity(Intent(this, SettingsActivity::class.java)) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        Updater.autoCheck(this)
        Novidades.mostrarSeNovo(this)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        Input.sens = 0.4f + prefs.getInt("sens", 50) / 100f * 1.2f
        hideBars()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        Input.jx = 0f; Input.jy = 0f
    }

    override fun onDestroy() {
        super.onDestroy()
        audio.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** RGB888 + profundidade 24 + 4x MSAA quando disponível. */
    private class Chooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val tries = arrayOf(
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 0x40, EGL10.EGL_NONE)
            )
            for (a in tries) {
                val n = IntArray(1); val cfg = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, a, cfg, 1, n) && n[0] > 0 && cfg[0] != null) return cfg[0]!!
            }
            throw IllegalStateException("Sem configuração EGL")
        }
    }
}
