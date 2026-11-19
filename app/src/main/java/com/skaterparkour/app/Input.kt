package com.skaterparkour.app

/** Estado de entrada compartilhado entre a interface (thread principal) e o jogo (thread GL). */
object Input {
    @Volatile var jx = 0f
    @Volatile var jy = 0f
    @Volatile var jump = false
    @Volatile var reset = false
    @Volatile var sens = 1f

    private var ay = 0f
    private var ap = 0f

    @Synchronized
    fun addCam(yaw: Float, pitch: Float) {
        ay += yaw
        ap += pitch
    }

    @Synchronized
    fun takeCam(out: FloatArray) {
        out[0] = ay
        out[1] = ap
        ay = 0f
        ap = 0f
    }
}
