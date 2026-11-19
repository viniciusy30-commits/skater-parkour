package com.skaterparkour.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var glView: GameGLView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        glView = GameGLView(this)
        root.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val joystick = JoystickView(this)
        root.addView(joystick, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(36); bottomMargin = dp(28)
        })

        val jump = IconButton(this, IconButton.JUMP)
        root.addView(jump, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(44); bottomMargin = dp(40)
        })

        val gear = IconButton(this, IconButton.GEAR)
        gear.onClick = { startActivity(Intent(this, SettingsActivity::class.java)) }
        root.addView(gear, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(20); topMargin = dp(14)
        })

        val reset = IconButton(this, IconButton.RESET)
        reset.onClick = { Input.reset = true }
        root.addView(reset, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(76); topMargin = dp(14)
        })

        setContentView(root)

        // entrada suave
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(600).start()

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
        Input.jx = 0f
        Input.jy = 0f
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
}
