package com.desktopmini.launcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Mở trình cài đặt hệ thống với tệp APK vừa nhận. Người dùng chỉ cần bấm Cài đặt / Cập nhật.
 * Cài đè lên app cũ được khi cùng applicationId, cùng chữ ký và versionCode mới hơn (CI đã làm đủ 3 điều này).
 * Khi app đang chạy nền, Android chặn mở Activity từ nền nên dùng thông báo toàn màn hình (có thể tự mở) + bấm để cài.
 */
object ApkInstaller {
    private const val CHANNEL = "apk_install"
    private const val NOTIF_ID = 4417
    private const val MIME = "application/vnd.android.package-archive"
    private const val FLAG_IMMUTABLE_COMPAT = 0x04000000 // PendingIntent.FLAG_IMMUTABLE (API 23+)

    fun install(ctx: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            // Chưa cho phép "cài ứng dụng không rõ nguồn gốc" cho app này: mở đúng màn hình đó
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            post(ctx, "Cần cho phép cài ứng dụng", "Bật quyền cho TV rồi bấm để cài ${apk.name}", settings, false)
            return
        }
        val intent = installIntent(ctx, apk)
        if (RemoteHub.appVisible) {
            try { ctx.startActivity(intent); return } catch (e: Exception) { }
        }
        post(ctx, "Đã nhận ${apk.name}", "Bấm để cài đặt / cập nhật", intent, true)
    }

    private fun installIntent(ctx: Context, apk: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk) else Uri.fromFile(apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun post(ctx: Context, title: String, text: String, target: Intent, fullScreen: Boolean) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Cài đặt APK", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE_COMPAT
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
        if (fullScreen) b.setFullScreenIntent(pi, true)
        nm.notify(NOTIF_ID, b.build())
    }
}
