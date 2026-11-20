package Com.hau.name

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cập nhật app: nhấn giữ chữ "DK TV" góc trái trên 2 giây -> tải DK-TV-Gop.apk mới nhất từ GitHub Releases
 * rồi mở trình cài đặt. Bấm "Cập nhật" trong màn cài đặt là app lên bản mới (cùng applicationId + cùng chữ ký
 * + versionCode mới hơn nên cài đè được).
 */
object Updater {
    const val APK_URL = "https://github.com/trinhconghau22111999-arch/cha/releases/latest/download/DK-TV-Gop.apk"
    private const val CHANNEL = "apk_update"
    private const val NOTIF_ID = 4418
    private const val MIME = "application/vnd.android.package-archive"
    @Volatile private var running = false

    fun start(act: Activity) {
        val app = act.applicationContext
        if (running) { Toast.makeText(app, "Đang tải bản cập nhật, chờ chút...", Toast.LENGTH_SHORT).show(); return }
        // Chưa cho phép cài app từ nguồn này: mở đúng màn hình cấp quyền trước
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(app, "Hãy bật \"Cho phép từ nguồn này\" rồi nhấn giữ DK TV lại để cập nhật", Toast.LENGTH_LONG).show()
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName)))
            } catch (e: Exception) { }
            return
        }
        running = true
        Toast.makeText(app, "Đang tải bản mới nhất...", Toast.LENGTH_LONG).show()
        Thread {
            var apk: File? = null
            try { apk = download(app) } catch (e: Exception) { }
            Handler(Looper.getMainLooper()).post {
                running = false
                if (apk != null) {
                    Toast.makeText(app, "Đã tải xong. Bấm \"Cập nhật\" để cài bản mới", Toast.LENGTH_LONG).show()
                    install(app, act, apk)
                } else {
                    // Tải không được: mở thẳng đường dẫn tải bằng trình duyệt
                    Toast.makeText(app, "Tải không được, mở đường dẫn tải...", Toast.LENGTH_LONG).show()
                    try { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                }
            }
        }.start()
    }

    private fun install(app: Context, act: Activity, apk: File) {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(app, app.packageName + ".fileprovider", apk) else Uri.fromFile(apk)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val foreground = try {
            (act as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
        } catch (e: Exception) { false }
        if (foreground) {
            try { app.startActivity(intent); return } catch (e: Exception) { }
        }
        // App đang ở nền (Android chặn mở màn hình từ nền): báo bằng thông báo, bấm vào là cài
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Cập nhật app", NotificationManager.IMPORTANCE_HIGH))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(app, 0, intent, flags)
        nm.notify(NOTIF_ID, NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Đã tải bản cập nhật DK TV")
            .setContentText("Bấm để cài đặt / cập nhật")
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
    }

    private fun download(app: Context): File? {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        val dir = File(base, "Update").also { it.mkdirs() }
        val out = File(dir, "DK-TV-Gop.apk")
        val tmp = File(dir, "DK-TV-Gop.apk.part")
        var url = APK_URL
        var conn: HttpURLConnection? = null
        try {
            // tự theo dõi chuyển hướng (GitHub chuyển sang máy chủ tệp khác)
            for (i in 0 until 6) {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "DKTV-Updater")
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
