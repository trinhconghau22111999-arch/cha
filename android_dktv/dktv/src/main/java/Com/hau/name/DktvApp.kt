package Com.hau.name

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Khởi tạo Firebase NGAY khi tiến trình app bắt đầu.
 *
 * Trước đây app chỉ có Firebase khi workflow tạo được google-services.json từ secret
 * GOOGLE_SERVICES_JSON_BASE64. Repo chưa có secret đó nên bản build không có cấu hình,
 * FirebaseDatabase.getInstance() ném lỗi và app văng ngay khi mở. Giờ nếu chưa có app
 * Firebase mặc định (không có google-services.json) thì dùng đúng cấu hình mà bản Windows
 * (truyentep.html) đang dùng, để hai bên cùng một dự án Firebase. Nếu có google-services.json
 * thì giữ nguyên cấu hình đó.
 */
class DktvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyCPwMKElbsQLjOeSVddGAfjNiHnPHV9b1A")
                    .setApplicationId("1:949360436062:web:c9b5b5d47ee4f76fdce5cf")
                    .setProjectId("qrremod")
                    .setDatabaseUrl("https://qrremod-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .build()
                FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("DktvApp", "Không khởi tạo được Firebase", e)
        }
    }
}
