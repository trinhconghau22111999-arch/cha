package Com.hau.name

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * App chỉ có 1 vai trò: Máy Camera (điện thoại làm webcam cho Qrtuxa trên máy tính).
 * Mở vào CameraActivity; trước đó (Android 8+) hỏi bật "Cho phép cài ứng dụng từ nguồn không xác định"
 * cho chính app này cho tới khi được bật hoặc bấm "Không hỏi lại".
 */
class MainActivity : AppCompatActivity() {
    private var wentToSettings = false
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("dktv", MODE_PRIVATE)
        val need = Build.VERSION.SDK_INT >= 26 &&
            !packageManager.canRequestPackageInstalls() &&
            !sp.getBoolean("noAskInstall", false)
        if (!need) { openCamera(); return }
        AlertDialog.Builder(this)
            .setTitle("Cho phép cài ứng dụng từ nguồn không xác định?")
            .setMessage("Để cài tệp APK (cập nhật app), hãy bật \"Cho phép từ nguồn này\" cho app.")
            .setPositiveButton("Cho phép") { _, _ ->
                wentToSettings = true
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                } catch (e: Exception) { wentToSettings = false; openCamera() }
            }
            .setNegativeButton("Để sau") { _, _ -> openCamera() }
            .setNeutralButton("Không hỏi lại") { _, _ ->
                sp.edit().putBoolean("noAskInstall", true).apply()
                openCamera()
            }
            .setOnCancelListener { openCamera() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Quay lại từ màn hình cài đặt quyền thì vào app luôn
        if (wentToSettings) openCamera()
    }

    private fun openCamera() {
        if (opened) return
        opened = true
        startActivity(Intent(this, CameraActivity::class.java))
        finish()
    }
}
