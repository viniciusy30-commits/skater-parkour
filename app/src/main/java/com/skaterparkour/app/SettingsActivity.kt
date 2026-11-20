package com.skaterparkour.app

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(28), dp(20), dp(28), dp(20))

        col.addView(text("Configurações", 24f, Color.WHITE))
        col.addView(text("Versão instalada: 1." + Updater.installedVersion(this), 14f, Color.argb(200, 255, 255, 255)).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        col.addView(text("Sensibilidade da câmera", 16f, Color.WHITE))
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = prefs.getInt("sens", 50)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt("sens", p).apply()
                Input.sens = 0.4f + p / 100f * 1.2f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        col.addView(text("Personagem", 16f, Color.WHITE))
        val nomes = arrayOf("Original", "Anime (novo)")
        val botoes = ArrayList<Button>()
        val linha = LinearLayout(this)
        linha.orientation = LinearLayout.HORIZONTAL
        fun pinta() {
            val sel = prefs.getInt("char", 0)
            for (i in botoes.indices) {
                botoes[i].text = (if (i == sel) "✓ " else "") + nomes[i]
                botoes[i].alpha = if (i == sel) 1f else 0.6f
            }
        }
        for (i in nomes.indices) {
            val b = Button(this)
            b.setOnClickListener {
                prefs.edit().putInt("char", i).apply()
                Input.character = i
                pinta()
            }
            botoes.add(b)
            linha.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        pinta()
        col.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        val status = text("", 14f, Color.argb(220, 255, 200, 140))
        val btnUpdate = Button(this)
        btnUpdate.text = "Verificar atualização"
        btnUpdate.setOnClickListener { Updater.checkManual(this) { msg -> status.text = msg } }
        col.addView(btnUpdate)
        col.addView(status.apply { setPadding(0, dp(6), 0, dp(14)) })

        val btnNews = Button(this)
        btnNews.text = "O que mudou"
        btnNews.setOnClickListener { Novidades.mostrarTudo(this) }
        col.addView(btnNews)

        val btnBack = Button(this)
        btnBack.text = "Voltar ao jogo"
        btnBack.setOnClickListener { finish() }
        col.addView(btnBack, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#12151C"))
        scroll.addView(col)
        setContentView(scroll)
        scroll.alpha = 0f
        scroll.animate().alpha(1f).setDuration(250).start()
    }
}
