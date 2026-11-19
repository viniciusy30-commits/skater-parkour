package com.skaterparkour.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val REPO = "viniciusy30-commits/skater-parkour"
    private const val APK_NAME = "SkaterParkour.apk"
    private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    private val main = Handler(Looper.getMainLooper())

    private class Release(val version: Int, val tag: String, val url: String)

    fun installedVersion(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    /** Verificação automática ao abrir o app: no máximo a cada 6 horas e só avisa se houver versão nova. */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences("updater", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < SIX_HOURS) return
        prefs.edit().putLong("last", now).apply()
        Thread {
            val r = try { fetchLatest() } catch (e: Exception) { null }
            main.post {
                if (!activity.isFinishing && r != null && r.version > installedVersion(activity)) showNewVersion(activity, r)
            }
        }.start()
    }

    /** Botão "Verificar atualização": mostra mensagens de status. */
    fun checkManual(activity: Activity, status: (String) -> Unit) {
        status("Procurando atualização…")
        Thread {
            var msg = ""
            var rel: Release? = null
            try {
                val r = fetchLatest()
                if (r == null) {
                    msg = "Nenhuma versão publicada ainda."
                } else if (r.version > installedVersion(activity)) {
                    msg = "Nova versão disponível: " + r.tag
                    rel = r
                } else {
                    msg = "Você já está na versão mais nova."
                }
            } catch (e: Exception) {
                msg = "Sem internet ou GitHub indisponível. Tente de novo."
            }
            val finalMsg = msg
            val finalRel = rel
            main.post {
                if (activity.isFinishing) return@post
                status(finalMsg)
                if (finalRel != null) showNewVersion(activity, finalRel)
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SkaterParkour-App")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val tag = json.getString("tag_name")
            val ver = tag.removePrefix("v").trim().toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    break
                }
            }
            if (url == null) return null
            return Release(ver, tag, url)
        } finally {
            conn.disconnect()
        }
    }

    private fun showNewVersion(activity: Activity, r: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Nova versão disponível")
            .setMessage("Versão " + r.tag + " pronta para instalar. Seus dados são mantidos.")
            .setPositiveButton("Atualizar") { _, _ -> startUpdate(activity, r) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun canInstall(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
    }

    private fun startUpdate(activity: Activity, r: Release) {
        if (!canInstall(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("Permitir instalação")
                .setMessage("Para atualizar, o Android precisa liberar \"Instalar apps desconhecidos\" para o Skater Parkour. Toque em Abrir configuração, ative a opção e volte aqui para atualizar de novo.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                    activity.startActivity(i)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        val label = TextView(activity)
        label.text = "Baixando… 0%"
        val box = LinearLayout(activity)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad, pad, pad)
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Baixando atualização")
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        Thread {
            var error: String? = null
            var file: File? = null
            try {
                val dir = File(activity.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, APK_NAME)
                val conn = URL(r.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "SkaterParkour-App")
                conn.instanceFollowRedirects = true
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(32 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    main.post {
                                        bar.progress = pct
                                        label.text = "Baixando… $pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                file = out
            } catch (e: Exception) {
                error = "Falha no download. Verifique a internet e tente de novo."
            }
            val finalError = error
            val finalFile = file
            main.post {
                dialog.dismiss()
                if (activity.isFinishing) return@post
                if (finalError != null || finalFile == null) {
                    AlertDialog.Builder(activity).setTitle("Erro").setMessage(finalError ?: "Erro desconhecido")
                        .setPositiveButton("OK", null).show()
                } else {
                    install(activity, finalFile)
                }
            }
        }.start()
    }

    private fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
    }
}
