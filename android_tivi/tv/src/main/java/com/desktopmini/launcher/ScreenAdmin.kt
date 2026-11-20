package com.desktopmini.launcher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Quyền Quản trị thiết bị: chỉ dùng để khóa màn hình (tắt hình TV). Không cần root, chạy được trên Android 7.1.1. */
class ScreenAdminReceiver : DeviceAdminReceiver()

object ScreenAdmin {
    private fun component(ctx: Context) = ComponentName(ctx, ScreenAdminReceiver::class.java)

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isActive(ctx: Context): Boolean = try { dpm(ctx).isAdminActive(component(ctx)) } catch (e: Exception) { false }

    /** Tắt màn hình. Trả về false nếu chưa bật quyền Quản trị thiết bị. */
    fun lock(ctx: Context): Boolean {
        if (!isActive(ctx)) return false
        return try { dpm(ctx).lockNow(); true } catch (e: Exception) { false }
    }

    /** Mở màn hình xin quyền Quản trị thiết bị cho app này (bấm Kích hoạt một lần là xong). */
    fun requestActivation(ctx: Context) {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Cho phép app TV tắt màn hình khi bấm nút OFF hoặc lệnh từ điều khiển.")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { }
    }

    /** Bỏ quyền Quản trị thiết bị trước khi gỡ app (nếu không sẽ không gỡ được). */
    fun deactivate(ctx: Context) {
        try { if (isActive(ctx)) dpm(ctx).removeActiveAdmin(component(ctx)) } catch (e: Exception) { }
    }
}
