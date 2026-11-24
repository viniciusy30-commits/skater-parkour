package com.skaterparkour.app

/** Estado de toque compartilhado entre a interface (thread de UI) e o jogo (thread do GL). */
object Input {
    @Volatile var jx = 0f          // joystick: direita +
    @Volatile var jy = 0f          // joystick: para cima +
    private var camYaw = 0f
    private var camPitch = 0f
    private var zoom = 1f
    private val events = ArrayList<String>()

    // Skill 4: segurando e mirando (direção do arrasto na tela, já normalizada)
    @Volatile var aimHeld = false
    @Volatile var aimX = 0f
    @Volatile var aimY = 0f
    @Volatile var aimActive = false

    @Volatile var sens = 1f
    @Volatile var aimMode = false
    @Volatile var jumpHeld = false
    @Volatile var crouchHeld = false

    @Synchronized fun addCam(dy: Float, dp: Float) { camYaw += dy; camPitch += dp }
    @Synchronized fun addZoom(f: Float) { zoom *= f }
    @Synchronized fun press(e: String) { events.add(e) }

    @Synchronized fun take(cam: FloatArray, ev: ArrayList<String>) {
        cam[0] = camYaw; cam[1] = camPitch; cam[2] = zoom
        camYaw = 0f; camPitch = 0f; zoom = 1f
        ev.addAll(events); events.clear()
    }
}
