package com.skaterparkour.app

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

@SuppressLint("ViewConstructor")
class GameGLView(context: Context) : GLSurfaceView(context) {

    private var camPointer = -1
    private var lastX = 0f
    private var lastY = 0f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(Chooser())
        setRenderer(GameRenderer(context.applicationContext))
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (camPointer == -1) {
                    val i = e.actionIndex
                    camPointer = e.getPointerId(i)
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(camPointer)
                if (i >= 0) {
                    val dx = e.getX(i) - lastX
                    val dy = e.getY(i) - lastY
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                    val s = 0.0055f * Input.sens
                    Input.addCam(dx * s, dy * s * 0.8f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> camPointer = -1
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == camPointer) camPointer = -1
            }
        }
        return true
    }

    private class Chooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            for (samples in intArrayOf(4, 0)) {
                val attrs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16,
                    EGL10.EGL_RENDERABLE_TYPE, 0x40,
                    EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                    EGL10.EGL_SAMPLES, samples,
                    EGL10.EGL_NONE
                )
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, null, 0, num) && num[0] > 0) {
                    val configs = arrayOfNulls<EGLConfig>(num[0])
                    egl.eglChooseConfig(display, attrs, configs, num[0], num)
                    val c = configs[0]
                    if (c != null) return c
                }
            }
            throw IllegalArgumentException("Nenhuma configuração EGL compatível")
        }
    }
}
