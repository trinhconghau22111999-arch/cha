package com.desktopmini.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nút "Cập nhật": tải bản Tivi-TV.apk mới nhất từ GitHub Releases rồi tự mở trình cài đặt
 * (app đang mở thì mở thẳng; đang ở nền thì hiện thông báo). Người dùng bấm "Cập nhật" trong màn cài đặt/thông báo
 * là app lên bản mới (cùng applicationId + cùng chữ ký + versionCode mới hơn nên cài đè được).
 */
object Updater {
    const val APK_URL = "https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/Tivi-TV.apk"
    @Volatile private var running = false

    fun start(act: Activity) {
        val app = act.applicationContext
        if (running) { Toast.makeText(app, "Đang tải bản cập nhật, chờ chút...", Toast.LENGTH_SHORT).show(); return }
        running = true
        Toast.makeText(app, "Đang tải bản mới nhất...", Toast.LENGTH_LONG).show()
        Thread {
            var apk: File? = null
            try { apk = download(app) } catch (e: Exception) { }
            Handler(Looper.getMainLooper()).post {
                running = false
                if (apk != null) {
                    Toast.makeText(app, "Đã tải xong. Bấm \"Cập nhật\" để cài bản mới", Toast.LENGTH_LONG).show()
                    ApkInstaller.install(app, apk)
                } else {
                    // Tải không được: mở thẳng đường dẫn (trình duyệt / trình tải xuống của TV)
                    Toast.makeText(app, "Tải không được, mở đường dẫn tải...", Toast.LENGTH_LONG).show()
                    try {
                        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { }
                }
            }
        }.start()
    }

    private fun download(app: android.content.Context): File? {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        val dir = File(base, "Update").also { it.mkdirs() }
        val out = File(dir, "Tivi-TV.apk")
        val tmp = File(dir, "Tivi-TV.apk.part")
        var url = APK_URL
        var conn: HttpURLConnection? = null
        try {
            // tự theo dõi chuyển hướng (GitHub chuyển sang máy chủ tệp khác)
            for (i in 0 until 6) {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "TiviTV-Updater")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    url = URL(URL(url), loc).toString(); conn.disconnect(); continue
                }
                if (code != 200) return null
                break
            }
            val c = conn ?: return null
            c.inputStream.use { ins -> FileOutputStream(tmp).use { os -> ins.copyTo(os) } }
            if (tmp.length() < 100_000L) { tmp.delete(); return null }
            out.delete()
            if (!tmp.renameTo(out)) return null
            return out
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
            if (tmp.exists()) tmp.delete()
        }
    }
}
