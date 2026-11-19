package com.skaterparkour.app

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Lê assets/novidades.txt (seção mais nova no topo) e mostra o resumo. */
object Novidades {
    class Secao(val id: String, val titulo: String, val itens: List<String>)

    fun ler(ctx: Context): List<Secao> {
        val text = try {
            ctx.assets.open("novidades.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Secao>()
        var id: String? = null
        var titulo = ""
        var itens = ArrayList<String>()
        fun fecha() {
            val i = id
            if (i != null) out.add(Secao(i, titulo, itens))
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("#id")) {
                fecha()
                id = line.removePrefix("#id").trim()
                titulo = ""
                itens = ArrayList()
            } else if (line.startsWith("#titulo")) {
                titulo = line.removePrefix("#titulo").trim()
            } else if (line.startsWith("-")) {
                itens.add(line.removePrefix("-").trim())
            }
        }
        fecha()
        return out
    }

    private fun mensagem(s: Secao): String = s.itens.joinToString("\n") { "• $it" }

    /** Mostra só na primeira abertura depois de uma atualização. */
    fun mostrarSeNovo(activity: Activity) {
        val secoes = ler(activity)
        val top = secoes.firstOrNull() ?: return
        val prefs = activity.getSharedPreferences("novidades", Context.MODE_PRIVATE)
        if (prefs.getString("visto", "") == top.id) return
        prefs.edit().putString("visto", top.id).apply()
        AlertDialog.Builder(activity)
            .setTitle("O que mudou: " + top.titulo)
            .setMessage(mensagem(top))
            .setPositiveButton("Legal!", null)
            .show()
    }

    /** Histórico completo (botão em Configurações). */
    fun mostrarTudo(activity: Activity) {
        val secoes = ler(activity)
        val msg = if (secoes.isEmpty()) "Sem novidades registradas." else
            secoes.joinToString("\n\n") { it.titulo + "\n" + mensagem(it) }
        AlertDialog.Builder(activity)
            .setTitle("O que mudou")
            .setMessage(msg)
            .setPositiveButton("Fechar", null)
            .show()
    }
}
