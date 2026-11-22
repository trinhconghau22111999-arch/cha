package com.desktopmini.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * Điều khiển app BÊN NGOÀI (app đang mở trên TV) bằng remote điện thoại.
 * Khi app TV không còn ở phía trước, MainActivity chuyển lệnh sang đây:
 *  - move/click: chuột ảo vẽ trên cửa sổ overlay trợ năng, "click" = chạm giả lập bằng dispatchGesture (Android 7+)
 *  - scroll: vuốt tại vị trí con trỏ; back/home: hành động hệ thống
 *  - phím mũi tên: dịch con trỏ từng bước (Android 13+ dùng phím mũi tên thật của hệ thống); ok: chạm tại con trỏ
 * Người dùng phải tự bật dịch vụ trong Cài đặt > Trợ năng.
 */
class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
        /** MainActivity đăng ký: gọi khi dịch vụ vừa kết nối (để đưa giao diện lên cửa sổ trợ năng). */
        @Volatile var onConnected: (() -> Unit)? = null
        fun isEnabled(ctx: Context): Boolean {
            if (instance != null) return true
            return try {
                val v = android.provider.Settings.Secure.getString(ctx.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
                v.split(':').any { it.startsWith(ctx.packageName + "/") && it.contains("RemoteAccessibilityService") }
            } catch (e: Exception) { false }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursor: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var cx = 0f
    private var cy = 0f
    private var placed = false
    private val hideRunnable = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        instance = this
        // Cho phép đọc cả cửa sổ hệ thống (menu nguồn) nằm dưới lớp giao diện phủ kín màn hình của app
        try { serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } } catch (e: Exception) { }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        onConnected?.invoke()
    }

    // ===== Toàn bộ giao diện app TV: 1 cửa sổ trợ năng phủ kín màn hình (che cả thanh trạng thái và thanh điều hướng) =====
    private var uiBox: FrameLayout? = null
    private var uiLp: WindowManager.LayoutParams? = null
    @Volatile private var uiBrightness = -1f      // -1 = theo hệ thống
    /** Dịch vụ bị tắt / cửa sổ mất: MainActivity đưa giao diện về lại trong app. */
    @Volatile var onUiLost: (() -> Unit)? = null

    /** Đưa [ui] vào cửa sổ trợ năng toàn màn hình. Trả false nếu không thêm được (khi đó [ui] không còn thuộc cửa sổ nào). */
    fun showUi(ui: View): Boolean {
        if (Build.VERSION.SDK_INT < 22) return false
        val w = wm ?: return false
        hideUi()
        val box = FrameLayout(this)
        return try {
            (ui.parent as? ViewGroup)?.removeView(ui)
            box.addView(ui, FrameLayout.LayoutParams(-1, -1))
            val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            p.screenBrightness = uiBrightness
            w.addView(box, p)
            uiBox = box; uiLp = p
            true
        } catch (e: Exception) {
            try { box.removeAllViews() } catch (e2: Exception) { }
            uiBox = null
            false
        }
    }

    /** Độ sáng 0..1 cho cửa sổ giao diện nổi (cửa sổ Activity bên dưới trống nên chỉnh độ sáng của nó không còn tác dụng). */
    fun setUiBrightness(b: Float) {
        uiBrightness = b
        val box = uiBox ?: return
        val p = uiLp ?: return
        p.screenBrightness = b
        try { wm?.updateViewLayout(box, p) } catch (e: Exception) { }
    }

    /** Gỡ cửa sổ giao diện (view được trả lại cho MainActivity). */
    fun hideUi() {
        val box = uiBox ?: return
        try { box.removeAllViews() } catch (e: Exception) { }
        try { wm?.removeView(box) } catch (e: Exception) { }
        uiBox = null; uiLp = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        uiLost(); removeCursor(); instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() { uiLost(); removeCursor(); instance = null; super.onDestroy() }

    private fun uiLost() {
        if (uiBox == null) return
        val cb = onUiLost
        hideUi()
        cb?.invoke()
    }

    private fun screen(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm?.defaultDisplay?.getRealMetrics(dm)
        return dm
    }

    private fun ensureCursor() {
        if (cursor != null || Build.VERSION.SDK_INT < 22) return
        val size = (28 * resources.displayMetrics.density).toInt()
        val v = object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = width / 2f
                p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt(); c.drawCircle(r, r, r - 3f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Color.BLACK; c.drawCircle(r, r, r - 3f, p)
            }
        }
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm?.addView(v, params); cursor = v; lp = params } catch (e: Exception) { }
    }

    private fun removeCursor() {
        ui.removeCallbacks(hideRunnable)
        try { cursor?.let { wm?.removeView(it) } } catch (e: Exception) { }
        cursor = null; lp = null
    }

    /** MainActivity quay lại phía trước: ẩn con trỏ overlay (dùng con trỏ của app). */
    fun hideCursor() { ui.post { cursor?.visibility = View.GONE } }

    private fun showCursor() {
        val dm = screen()
        if (!placed) { cx = dm.widthPixels / 2f; cy = dm.heightPixels / 2f; placed = true }
        ensureCursor()
        val v = cursor ?: return
        val p = lp ?: return
        p.x = (cx - p.width / 2f).toInt(); p.y = (cy - p.height / 2f).toInt()
        v.visibility = View.VISIBLE
        try { wm?.updateViewLayout(v, p) } catch (e: Exception) { }
        ui.removeCallbacks(hideRunnable); ui.postDelayed(hideRunnable, 6000)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        try {
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
        } catch (e: Exception) { }
    }

    /** Nhận cùng JSON với RemoteController.handle(); chỉ xử lý các lệnh liên quan app ngoài. */
    fun handle(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        ui.post {
            val dm = screen()
            // Remote đang TẮT chuột (chỉ dùng phím): không vẽ/di chuyển con trỏ, phím điều hướng bằng ô chọn thật của app
            val mouseOff = o.has("mouse") && !o.optBoolean("mouse")
            when (o.optString("action")) {
                "move" -> {
                    if (!placed) { cx = dm.widthPixels / 2f; cy = dm.heightPixels / 2f; placed = true }
                    cx = (cx + o.optInt("dx") * 1.6f).coerceIn(0f, dm.widthPixels - 1f)
                    cy = (cy + o.optInt("dy") * 1.6f).coerceIn(0f, dm.heightPixels - 1f)
                    showCursor()
                }
                "click" -> { showCursor(); gesture(cx, cy, cx, cy, 50) }
                "longclick" -> { showCursor(); gesture(cx, cy, cx, cy, 1000) }       // chuột phải / nhấn giữ
                "scroll" -> {
                    scrollAccum += -o.optInt("dy") * 2 * resources.displayMetrics.density * 6f
                    showCursor(); pumpScroll(dm)
                }
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "key" -> {
                    if (mouseOff) { hideCursor(); navKey(o.optString("key")) }
                    else key(o.optString("key"), dm)
                }
                "mouse" -> if (!o.optBoolean("on", true)) hideCursor()
                "text" -> typeText(o.optInt("del").coerceAtLeast(0), o.optString("ins"))
                "enter" -> pressEnter()
            }
        }
    }

    /** Gõ từ điện thoại vào ô nhập đang chọn của app khác: xoá [del] ký tự trước con trỏ (hoặc thay vùng đang chọn) rồi chèn [ins] tại con trỏ. */
    private fun typeText(delRaw: Int, insRaw: String) {
        if (Build.VERSION.SDK_INT < 21) return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (n == null || !n.isEditable) { TextGhost.dropped(insRaw); return }     // không có ô nhập: bỏ chữ, nhớ để khỏi dồn vào sau
            val (del, ins) = TextGhost.applied(delRaw, insRaw)
            if (del == 0 && ins.isEmpty()) return
            val showingHint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
            val base = if (showingHint) "" else (n.text ?: "").toString()
            var a = n.textSelectionStart; var b = n.textSelectionEnd
            if (showingHint || a < 0 || b < 0) { a = base.length; b = base.length }
            val s = minOf(a, b).coerceIn(0, base.length); val e = maxOf(a, b).coerceIn(0, base.length)
            val from = if (s != e) s else (s - del).coerceAtLeast(0)
            val out = base.substring(0, from) + ins + base.substring(e)
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, out)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            val pos = from + ins.length
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } catch (e: Exception) { }
    }

    /** Phím Enter / Gửi của bàn phím điện thoại, trong ô nhập app khác (Android 11+). */
    private fun pressEnter() {
        if (Build.VERSION.SDK_INT < 30) return
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return
        try {
            val n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (e: Exception) { }
    }

    // Cuon: gop cac lenh den trong luc cu vuot truoc chua xong, vuot ngan (~90ms) lien tiep cho muot
    private var scrollAccum = 0f
    private var scrollBusy = false

    private fun pumpScroll(dm: DisplayMetrics) {
        if (scrollBusy || Build.VERSION.SDK_INT < 24) return
        val d = scrollAccum.coerceIn(-dm.heightPixels * 0.6f, dm.heightPixels * 0.6f)
        if (kotlin.math.abs(d) < 1f) return
        scrollAccum = 0f
        val y1 = cy.coerceIn(0f, dm.heightPixels - 1f)
        val y2 = (cy - d).coerceIn(0f, dm.heightPixels - 1f)
        val path = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        scrollBusy = true
        val cb = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { scrollBusy = false; if (scrollAccum != 0f) pumpScroll(screen()) }
            override fun onCancelled(g: GestureDescription?) { scrollBusy = false }
        }
        try {
            if (!dispatchGesture(GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build(), cb, ui)) scrollBusy = false
        } catch (e: Exception) { scrollBusy = false }
    }

    private fun key(k: String, dm: DisplayMetrics) {
        if (Build.VERSION.SDK_INT >= 33) {
            val g = when (k) { "up" -> 16; "down" -> 17; "left" -> 18; "right" -> 19; "ok" -> 20; else -> -1 }
            if (g > 0 && performGlobalAction(g)) return
        }
        val step = 60 * resources.displayMetrics.density
        when (k) {
            "up" -> cy = (cy - step).coerceAtLeast(0f)
            "down" -> cy = (cy + step).coerceAtMost(dm.heightPixels - 1f)
            "left" -> cx = (cx - step).coerceAtLeast(0f)
            "right" -> cx = (cx + step).coerceAtMost(dm.widthPixels - 1f)
            "ok" -> { showCursor(); gesture(cx, cy, cx, cy, 50); return }
            else -> return
        }
        showCursor()
    }

    /**
     * Chế độ chỉ dùng phím (chuột TẮT): chuyển ô chọn (focus) thật của app đang mở theo hướng ▲▼◀▶, OK = bấm ô đang chọn.
     * Không dịch/vẽ con trỏ chuột. Cần Android 5+ (focusSearch).
     */
    private fun navKey(k: String): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return false
        try {
            val cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (k == "ok") {
                var n: AccessibilityNodeInfo? = cur
                while (n != null && !n.isClickable) n = n.parent
                return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val dir = when (k) {
                "up" -> View.FOCUS_UP; "down" -> View.FOCUS_DOWN
                "left" -> View.FOCUS_LEFT; "right" -> View.FOCUS_RIGHT
                else -> return false
            }
            val next = (cur ?: root).focusSearch(dir) ?: return false
            return next.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        } catch (e: Exception) { return false }
    }

    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    /** Mở menu nguồn của Android (có nút Tắt nguồn). App thường không tự tắt nguồn được, nên người dùng bấm xác nhận trong menu này. Chỉ Android 9 trở lên. */
    fun powerMenu(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
}
