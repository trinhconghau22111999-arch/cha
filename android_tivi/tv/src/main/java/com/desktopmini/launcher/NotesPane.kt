package com.desktopmini.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Camera
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import java.io.FileOutputStream
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Trang "Ghi chú": gõ chữ, dán ảnh (ảnh nằm trái/phải thì chữ chạy bên cạnh ảnh).
 * Ghi chú lưu thành tệp .ghichu.html trong thư mục riêng GhiChu của app (KHÔNG nằm trong trang Tệp, không tự xoá).
 * Xem lại ghi chú đã lưu bằng nút 📂 Danh sách ngay trong trang Ghi chú.
 * Giao diện soạn thảo là assets/ghichu.html; lưu tự động sau khi ngừng gõ 1,5 giây, rời trang, hoặc bấm Lưu.
 */
class NotesPane(private val act: Activity) {
    val view = FrameLayout(act)
    val web = WebView(act)
    private val ui = Handler(Looper.getMainLooper())

    private var curFile: File? = null
    private var curTitle: String? = null
    private var pageReady = false
    private var pendingOpen: File? = null
    private var listOnReady = false
    @Volatile private var listOpen = false      // trang danh sách đang hiện (JS báo qua AndroidNote.listState)

    init {
        view.setBackgroundColor(0xFF05070D.toInt())
        web.setBackgroundColor(Color.TRANSPARENT)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidNote")
        web.webViewClient = object : WebViewClient() {}
        web.loadUrl("file:///android_asset/ghichu.html")
        view.addView(web, FrameLayout.LayoutParams(-1, -1))
        migrateOldNotes()
    }

    fun onResume() { }

    /** Bấm nút Ghi chú trên thanh dưới: mở thẳng danh sách ghi chú đã lưu. */
    /** Nút Back khi đang soạn: về trang danh sách (trả true = đã xử lý). Đang ở danh sách: trả false để app quay về trang trước. */
    fun onBack(): Boolean {
        if (overlay != null) { closeOverlay(); return true }      // đang mở camera / cắt ảnh: đóng lại
        if (listOpen || !pageReady) return false
        listOpen = true
        showList()
        return true
    }

    fun showList() {
        if (!pageReady) { listOnReady = true; return }
        web.evaluateJavascript("showList()", null)
    }

    /** Rời trang hoặc app đi nền: lưu nốt những gì đang gõ. */
    fun onPause() {
        closeOverlay()
        if (pageReady) web.evaluateJavascript("flush(false)", null)
    }

    fun destroy() { closeOverlay(); try { web.destroy() } catch (e: Exception) { } }

    /** Mở một ghi chú đã lưu (từ trang Tệp). */
    fun openFile(f: File) {
        if (!pageReady) { pendingOpen = f; return }
        web.evaluateJavascript("flush(false)") { loadFromFile(f) }     // lưu ghi chú đang gõ trước rồi mới nạp ghi chú kia
    }

    private fun loadFromFile(f: File) {
        val raw = try { f.readText() } catch (e: Exception) { Toast.makeText(act, "Không đọc được ghi chú", Toast.LENGTH_SHORT).show(); return }
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.let { unesc(it) }
            ?: f.name.removeSuffix(".ghichu.html")
        val a = raw.indexOf("<!--NOTE-->")
        val b = raw.indexOf("<!--/NOTE-->")
        val body = if (a >= 0 && b > a) raw.substring(a + 11, b) else ""
        curFile = f
        curTitle = title
        web.evaluateJavascript("loadNote(" + JSONObject.quote(title) + "," + JSONObject.quote(body) + ")", null)
    }

    // ---------------- tệp ----------------

    /** Thư mục riêng của ghi chú (trong bộ nhớ app, không dùng chung với trang Tệp). */
    private fun dir(): File {
        val d = File(act.filesDir, "GhiChu")
        d.mkdirs()
        return d
    }

    /** Ghi chú cũ đã lưu nhầm vào trang Tệp: chuyển sang thư mục Ghi chú để không mất. */
    private fun migrateOldNotes() {
        try {
            val base = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: act.filesDir
            File(base, "TruyenTep").listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".ghichu.html", ignoreCase = true)) {
                    val t = File(dir(), f.name)
                    if (!t.exists()) f.renameTo(t)
                }
            }
        } catch (e: Exception) { }
    }

    private fun safeName(t: String): String {
        val bad = "\\/:*?\"<>|"
        val s = t.map { if (it in bad || it < ' ') '_' else it }.joinToString("").trim().take(80)
        return if (s.isEmpty()) "Ghi chú" else s
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun uniqueFile(name: String): File {
        var f = File(dir(), "$name.ghichu.html")
        var n = 1
        while (f.exists()) { f = File(dir(), "$name ($n).ghichu.html"); n++ }
        return f
    }

    /** Tiêu đề lúc bấm "+": Ghi chú ngày-tháng giờ-phút (không trùng tên tệp đã có). */
    private fun makeTitle(): String {
        val base = "Ghi chú " + SimpleDateFormat("dd-MM HH'h'mm", Locale("vi")).format(Date())
        if (!File(dir(), "$base.ghichu.html").exists()) return base
        return uniqueFile(base).name.removeSuffix(".ghichu.html")
    }

    private fun wrap(title: String, body: String): String =
        "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>" + esc(title) + "</title><style>" +
        "body{font-family:sans-serif;font-size:19px;line-height:1.45;padding:14px;max-width:900px;margin:auto}" +
        "img{max-width:100%}img.l{float:left;margin:2px 14px 6px 0}img.r{float:right;margin:2px 0 6px 14px}" +
        "img.b{display:block;margin:8px auto}</style></head><body>\n<!--NOTE-->" + body + "<!--/NOTE-->\n</body></html>"

    private fun toastMsg(m: String) { ui.post { Toast.makeText(act, m, Toast.LENGTH_SHORT).show() } }

    private fun clipboard() = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ---------------- cầu nối với trang soạn thảo ----------------

    private inner class Bridge {
        @JavascriptInterface fun ready() {
            ui.post {
                pageReady = true
                val po = pendingOpen
                pendingOpen = null
                if (po != null) loadFromFile(po)
                else if (curTitle == null) {
                    val t = makeTitle()
                    curTitle = t; curFile = null
                    web.evaluateJavascript("loadNote(" + JSONObject.quote(t) + ",'')", null)
                }
                if (listOnReady) { listOnReady = false; web.evaluateJavascript("showList()", null) }
            }
        }

        /** Bấm "+": ghi chú mới, tiêu đề lấy theo giờ lúc bấm. */
        @JavascriptInterface fun newTitle(): String {
            val t = makeTitle()
            curTitle = t; curFile = null
            return t
        }

        /** Lưu (tự động hoặc bấm Lưu) vào thư mục Ghi chú. Trang vẫn mở, không thoát. Ghi chú trống thì không tạo tệp. */
        @JavascriptInterface fun save(title: String, html: String, text: String, hasImg: Int, manual: Int) {
            if (text.isBlank() && hasImg == 0) {
                // Ghi chú trống: nếu đây là ghi chú đã lưu thì coi như đã xoá (không cần bấm Lưu).
                // Gõ lại chữ vào trang thì ghi chú được lưu lại như mới.
                val old = curFile
                if (old != null) {
                    try { old.delete() } catch (e: Exception) { }
                    curFile = null
                    if (manual == 1) toastMsg("Ghi chú trống nên đã xoá")
                } else if (manual == 1) toastMsg("Ghi chú đang trống")
                return
            }
            try {
                val t = if (title.isBlank()) (curTitle ?: makeTitle()) else title
                val old = curFile
                var target = File(dir(), safeName(t) + ".ghichu.html")
                if (old != null && old != target && target.exists()) target = uniqueFile(safeName(t))
                if (old == null && target.exists()) target = uniqueFile(safeName(t))
                target.writeText(wrap(t, html))
                target.setLastModified(System.currentTimeMillis())
                if (old != null && old != target) { try { old.delete() } catch (e: Exception) { } }
                curFile = target
                curTitle = t
                if (manual == 1) ui.post { Toast.makeText(act, "Đã lưu ghi chú: " + target.name.removeSuffix(".ghichu.html"), Toast.LENGTH_SHORT).show() }
            } catch (e: Throwable) {
                ui.post { Toast.makeText(act, "Lưu ghi chú lỗi: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }

        /** Danh sách ghi chú đã lưu, mới nhất trước: JSON [{name, time}]. */
        @JavascriptInterface fun list(): String {
            val arr = org.json.JSONArray()
            val fmt = SimpleDateFormat("dd-MM HH'h'mm", Locale("vi"))
            dir().listFiles()
                ?.filter { it.isFile && it.name.endsWith(".ghichu.html", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { f ->
                    arr.put(JSONObject().put("name", f.name.removeSuffix(".ghichu.html"))
                        .put("time", fmt.format(Date(f.lastModified()))))
                }
            return arr.toString()
        }

        /** Hiện thông báo ngắn (cho JS báo lỗi/kết quả). */
        @JavascriptInterface fun toast(m: String) { toastMsg(m) }

        /** Ẩn bàn phím của máy (khi ô đang lấy tiêu điểm mà chế độ gõ đang tắt). */
        @JavascriptInterface fun hideIme() {
            ui.post {
                try {
                    val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(web.windowToken, 0)
                } catch (e: Exception) { }
            }
        }

        /** Hiện bàn phím của máy (khi bấm ⌨ Bàn phím). */
        @JavascriptInterface fun showIme() {
            ui.post {
                try {
                    val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(web, 0)
                } catch (e: Exception) { }
            }
        }

        /** JS báo trang danh sách đang mở (true) hay đã đóng (false). */
        @JavascriptInterface fun listState(open: Boolean) { listOpen = open }

        /** Xoá một ghi chú trong danh sách. Trả true nếu đó là ghi chú đang mở trong trang soạn (trang sẽ làm trống). */
        @JavascriptInterface fun delete(name: String): Boolean {
            if (name.isEmpty() || name.contains('/') || name.contains('\\')) { toastMsg("Tên ghi chú không hợp lệ"); return false }
            val f = File(dir(), "$name.ghichu.html")
            if (!f.exists()) { toastMsg("Không tìm thấy ghi chú: $name"); return false }
            val wasOpen = curFile?.absolutePath == f.absolutePath
            val ok = try { f.delete() } catch (e: Exception) { false }
            if (!ok && f.exists()) { toastMsg("Không xoá được: $name"); return false }
            if (wasOpen) { curFile = null; curTitle = null }
            toastMsg("Đã xoá ghi chú: $name")
            return wasOpen
        }

        /** Copy một ghi chú trong danh sách: chữ + HTML có ảnh, như nút Copy ghi chú. */
        @JavascriptInterface fun copyNote(name: String) {
            Thread {
                try {
                    val raw = File(dir(), "$name.ghichu.html").readText()
                    val a = raw.indexOf("<!--NOTE-->")
                    val b = raw.indexOf("<!--/NOTE-->")
                    if (a < 0 || b <= a) throw Exception("ghi chú trống")
                    val body = raw.substring(a + 11, b)
                    val text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    ui.post {
                        clipboard().setPrimaryClip(ClipData.newHtmlText("Ghi chú", text, body))
                        Toast.makeText(act, "Đã copy ghi chú: $name", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    ui.post { Toast.makeText(act, "Copy ghi chú lỗi: " + (e.message ?: ""), Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        /** Mở một ghi chú trong danh sách (trang đang gõ đã được lưu trước đó bằng flush). */
        @JavascriptInterface fun open(name: String) {
            ui.post {
                val f = File(dir(), "$name.ghichu.html")
                if (f.isFile) loadFromFile(f) else Toast.makeText(act, "Không tìm thấy ghi chú", Toast.LENGTH_SHORT).show()
            }
        }

        /** Copy ghi chú (chữ + HTML có ảnh) để dán sang nơi khác, ví dụ Zalo web. */
        @JavascriptInterface fun copy(text: String, html: String) {
            ui.post {
                try {
                    clipboard().setPrimaryClip(ClipData.newHtmlText("Ghi chú", text, html))
                    Toast.makeText(act, "Đã copy ghi chú", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) { Toast.makeText(act, "Copy lỗi", Toast.LENGTH_SHORT).show() }
            }
        }

        /** Copy riêng một ảnh (ảnh thật trong bộ nhớ tạm) để dán vào khung chat. */
        @JavascriptInterface fun copyImage(src: String) {
            Thread {
                try {
                    val comma = src.indexOf(',')
                    if (!src.startsWith("data:") || comma < 0) throw Exception("không phải ảnh")
                    val png = src.startsWith("data:image/png")
                    val bytes = Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
                    val d = File(act.cacheDir, "notes"); d.mkdirs()
                    d.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3600_000L) it.delete() }
                    val f = File(d, "anh_" + System.currentTimeMillis() + (if (png) ".png" else ".jpg"))
                    f.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
                    ui.post {
                        clipboard().setPrimaryClip(ClipData.newUri(act.contentResolver, "Ảnh", uri))
                        Toast.makeText(act, "Đã copy ảnh", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Throwable) {
                    ui.post { Toast.makeText(act, "Copy ảnh lỗi: " + (e.message ?: ""), Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        /** Dán chữ trong bộ nhớ tạm vào ghi chú (chỗ con trỏ). */
        @JavascriptInterface fun pasteText() {
            ui.post {
                val item = try { clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) } catch (e: Throwable) { null }
                val t: String? = try {
                    if (item == null) null
                    else if (item.text != null) item.text.toString()
                    else if (item.uri == null) item.coerceToText(act).toString()
                    else null
                } catch (e: Throwable) { null }
                if (t.isNullOrEmpty()) { Toast.makeText(act, "Chưa có chữ trong bộ nhớ tạm", Toast.LENGTH_SHORT).show(); return@post }
                web.evaluateJavascript("insertText(" + JSONObject.quote(t) + ")", null)
            }
        }

        /** Nút Chụp: mở camera sau (có thanh zoom, nút chụp). */
        @JavascriptInterface fun openCamera() { ui.post { openCameraUi() } }

        /** Cắt ảnh đang chọn: kéo chọn vùng giống nút chụp màn hình của trình duyệt. */
        @JavascriptInterface fun cropImage(src: String) {
            Thread {
                try {
                    val comma = src.indexOf(',')
                    if (!src.startsWith("data:") || comma < 0) throw Exception("không phải ảnh")
                    val bytes = Base64.decode(src.substring(comma + 1), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("không đọc được ảnh")
                    ui.post { showCrop(bmp) }
                } catch (e: Throwable) {
                    ui.post { Toast.makeText(act, "Cắt ảnh lỗi: " + (e.message ?: ""), Toast.LENGTH_SHORT).show() }
                }
            }.start()
        }

        /** Dán ảnh đang có trong bộ nhớ tạm của máy vào ghi chú (chỗ con trỏ). */
        @JavascriptInterface fun pasteImage() {
            ui.post {
                val item = try { clipboard().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) } catch (e: Throwable) { null }
                val uri = item?.uri
                if (uri == null) { Toast.makeText(act, "Chưa có ảnh trong bộ nhớ tạm", Toast.LENGTH_SHORT).show(); return@post }
                Thread {
                    try {
                        val bmp = decode(uri) ?: throw Exception("không đọc được ảnh")
                        val bo = ByteArrayOutputStream()
                        val alpha = bmp.hasAlpha()
                        bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, bo)
                        val data = "data:" + (if (alpha) "image/png" else "image/jpeg") + ";base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                        ui.post { web.evaluateJavascript("insertImg(" + JSONObject.quote(data) + ")", null) }
                    } catch (e: Throwable) {
                        ui.post { Toast.makeText(act, "Dán ảnh lỗi: " + (e.message ?: "bộ nhớ tạm không phải ảnh"), Toast.LENGTH_SHORT).show() }
                    }
                }.start()
            }
        }
    }

    // ---------------- camera + cắt ảnh ----------------

    companion object { const val REQ_CAM = 3010 }

    private var overlay: View? = null
    private var cam: Camera? = null
    private var camRot = 0
    private var shooting = false
    private var partialNext = false   // true = lần chụp này là "chụp một phần" (phải kéo chọn vùng)

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun closeOverlay() {
        releaseCamera()
        overlay?.let { try { view.removeView(it) } catch (e: Exception) { } }
        overlay = null
    }

    private fun releaseCamera() {
        val c = cam ?: return
        cam = null
        try { c.stopPreview() } catch (e: Exception) { }
        try { c.release() } catch (e: Exception) { }
    }

    private fun openCameraUi() {
        if (overlay != null) return
        if (android.os.Build.VERSION.SDK_INT >= 23 && act.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQ_CAM)
            return
        }
        startCamera()
    }

    /** Gọi từ MainActivity sau khi người dùng trả lời hộp xin quyền camera. */
    fun onCameraPermission(ok: Boolean) {
        if (ok) ui.post { startCamera() } else Toast.makeText(act, "Chưa cấp quyền camera", Toast.LENGTH_SHORT).show()
    }

    private fun startCamera() {
        if (overlay != null) return
        val n = try { Camera.getNumberOfCameras() } catch (e: Throwable) { 0 }
        if (n <= 0) { Toast.makeText(act, "Máy này không có camera", Toast.LENGTH_SHORT).show(); return }
        var id = 0
        val info = Camera.CameraInfo()
        for (i in 0 until n) { Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break } }

        val root = FrameLayout(act)
        root.setBackgroundColor(Color.BLACK)
        root.isClickable = true
        val tex = TextureView(act)
        root.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        // thanh dưới: − [thanh kéo zoom] + và nút chụp tròn
        val bar = android.widget.LinearLayout(act)
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(0x99000000.toInt())
        bar.setPadding(dp(14), dp(8), dp(14), dp(8))
        val seek = SeekBar(act)
        // thanh zoom dày gấp 4 lần mặc định (~4dp -> 16dp), bỏ dấu − và +
        val track = GradientDrawable().apply { setColor(0x66FFFFFF); cornerRadius = dp(8).toFloat(); setSize(-1, dp(16)) }
        val fill = GradientDrawable().apply { setColor(0xFF00B85C.toInt()); cornerRadius = dp(8).toFloat(); setSize(-1, dp(16)) }
        seek.progressDrawable = android.graphics.drawable.LayerDrawable(arrayOf(
            track, android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        )).also { it.setId(0, android.R.id.background); it.setId(1, android.R.id.progress) }
        seek.thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(28), dp(28)) }
        bar.addView(seek, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        val shutter = TextView(act)
        shutter.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), 0xFF00B85C.toInt()) }
        shutter.gravity = Gravity.CENTER
        shutter.contentDescription = "Chụp"
        bar.addView(shutter, android.widget.LinearLayout.LayoutParams(dp(51), dp(51)).apply { leftMargin = dp(16) })
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val close = TextView(act)
        close.text = "✕ Đóng"
        close.setTextColor(Color.WHITE); close.textSize = 16f
        close.setPadding(dp(14), dp(8), dp(14), dp(8))
        close.background = GradientDrawable().apply { setColor(0xFFD50000.toInt()); cornerRadius = dp(8).toFloat() }
        close.setOnClickListener { closeOverlay() }
        root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })

        // nút vuông "Một phần" (góc trên bên trái): chụp rồi kéo chọn vùng cần lấy
        val part = TextView(act)
        part.text = "✂\nMột phần"
        part.gravity = Gravity.CENTER
        part.setTextColor(Color.WHITE); part.textSize = 12f
        part.background = GradientDrawable().apply { setColor(0xFF1E88E5.toInt()); cornerRadius = dp(8).toFloat() }
        part.setOnClickListener { if (!shooting && cam != null) { partialNext = true; takePhoto() } }
        root.addView(part, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })

        shooting = false
        partialNext = false
        shutter.setOnClickListener { takePhoto() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val c = cam ?: return
                try { val p = c.parameters; if (p.isZoomSupported) { p.zoom = progress; c.parameters = p } } catch (e: Exception) { }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { }
            override fun onStopTrackingTouch(sb: SeekBar?) { }
        })
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { startPreview(id, st, tex, root, seek) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { releaseCamera(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { }
        }
        overlay = root
        view.addView(root, FrameLayout.LayoutParams(-1, -1))
        root.bringToFront()
    }

    private fun startPreview(id: Int, st: SurfaceTexture, tex: TextureView, root: View, seek: SeekBar) {
        try {
            val c = Camera.open(id)
            cam = c
            val info = Camera.CameraInfo()
            Camera.getCameraInfo(id, info)
            val deg = when (act.windowManager.defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            val rot = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) (360 - (info.orientation + deg) % 360) % 360
                      else (info.orientation - deg + 360) % 360
            camRot = rot
            c.setDisplayOrientation(rot)
            val p = c.parameters
            val vw = maxOf(1, root.width).toFloat(); val vh = maxOf(1, root.height).toFloat()
            val swap = rot == 90 || rot == 270
            val target = if (swap) vh / vw else vw / vh              // tỉ lệ w/h của khung cảm biến
            val pv = p.supportedPreviewSizes.filter { it.width <= 1920 }.ifEmpty { p.supportedPreviewSizes }
                .sortedWith(compareBy({ Math.abs(it.width.toFloat() / it.height - target) > 0.05f }, { -(it.width * it.height) })).first()
            p.setPreviewSize(pv.width, pv.height)
            val pic = p.supportedPictureSizes.filter { it.width * it.height <= 8_000_000 }.ifEmpty { p.supportedPictureSizes }
                .sortedWith(compareBy({ Math.abs(it.width.toFloat() / it.height - pv.width.toFloat() / pv.height) > 0.05f }, { -(it.width * it.height) })).first()
            p.setPictureSize(pic.width, pic.height)
            val modes = p.supportedFocusModes ?: emptyList<String>()
            if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            else if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            c.parameters = p
            if (p.isZoomSupported) { seek.max = p.maxZoom; seek.progress = 0 } else seek.visibility = View.INVISIBLE
            // khung xem trước giữ đúng tỉ lệ, nằm giữa màn hình
            val dr = if (swap) pv.height.toFloat() / pv.width else pv.width.toFloat() / pv.height
            val lw: Int; val lh: Int
            if (vw / vh > dr) { lh = vh.toInt(); lw = (vh * dr).toInt() } else { lw = vw.toInt(); lh = (vw / dr).toInt() }
            tex.layoutParams = FrameLayout.LayoutParams(lw, lh, Gravity.CENTER)
            c.setPreviewTexture(st)
            c.startPreview()
        } catch (e: Throwable) {
            Toast.makeText(act, "Không mở được camera: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
            closeOverlay()
        }
    }

    private fun takePhoto() {
        val c = cam ?: return
        if (shooting) return
        shooting = true
        val shoot = {
            try {
                c.takePicture(null, null, Camera.PictureCallback { data, _ -> onPhoto(data, camRot) })
            } catch (e: Throwable) {
                shooting = false
                partialNext = false
                Toast.makeText(act, "Chụp lỗi: " + (e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
        try {
            if (c.parameters.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) c.autoFocus { _, _ -> shoot() } else shoot()
        } catch (e: Throwable) { shoot() }
    }

    /** Chụp xong: 1 bản đầy đủ lưu vào trang Tệp (TruyenTep), 1 bản hiện ngay trên ghi chú đang làm.
     *  Nếu vừa bấm nút "Một phần" thì mở màn kéo chọn vùng trước; phần đã cắt mới được lưu và chèn. */
    private fun onPhoto(data: ByteArray, rot: Int) {
        val partial = partialNext
        partialNext = false
        closeOverlay()
        Thread {
            try {
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, opt)
                var sample = 1
                while (opt.outWidth / sample > 4096 || opt.outHeight / sample > 4096) sample *= 2
                val full = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw Exception("không đọc được ảnh vừa chụp")
                val bmp = if (rot != 0) Bitmap.createBitmap(full, 0, 0, full.width, full.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else full
                if (partial) {
                    ui.post { showCrop(bmp, true) }
                } else {
                    val f = saveToTruyenTep(bmp)
                    val url = photoUrl(bmp)
                    ui.post {
                        web.evaluateJavascript("insertPhoto(" + JSONObject.quote(url) + ")", null)
                        Toast.makeText(act, "Đã chụp - lưu vào trang Tệp: " + f.name, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                ui.post { Toast.makeText(act, "Lưu ảnh chụp lỗi: " + (e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Lưu ảnh vào thư mục TruyenTep (gọi trên luồng nền). */
    private fun saveToTruyenTep(bmp: Bitmap): File {
        val base = act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: act.filesDir
        val d = File(base, "TruyenTep"); d.mkdirs()
        val name = "Anh_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var f = File(d, "$name.jpg"); var k = 1
        while (f.exists()) { f = File(d, "$name ($k).jpg"); k++ }
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f
    }

    /** Thu nhỏ còn tối đa 1280 px rồi đổi sang chuỗi data: để chèn vào ghi chú (gọi trên luồng nền). */
    private fun photoUrl(bmp: Bitmap): String {
        val m = maxOf(bmp.width, bmp.height)
        val small = if (m > 1280) Bitmap.createScaledBitmap(bmp, (bmp.width * 1280f / m).toInt(), (bmp.height * 1280f / m).toInt(), true) else bmp
        val bo = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, bo)
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
    }

    /** Mở màn kéo chọn vùng. asNewPhoto = true: vùng cắt là ảnh chụp mới (lưu + chèn như ảnh chụp thường). */
    private fun showCrop(bmp: Bitmap, asNewPhoto: Boolean = false) {
        if (overlay != null) return
        val v = CropView(bmp, asNewPhoto)
        overlay = v
        view.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.bringToFront()
    }

    private fun applyCropped(crop: Bitmap, asNewPhoto: Boolean) {
        closeOverlay()
        Thread {
            try {
                if (asNewPhoto) {
                    val f = saveToTruyenTep(crop)
                    val url = photoUrl(crop)
                    ui.post {
                        web.evaluateJavascript("insertPhoto(" + JSONObject.quote(url) + ")", null)
                        Toast.makeText(act, "Đã chụp một phần - lưu vào trang Tệp: " + f.name, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val bo = ByteArrayOutputStream()
                    val alpha = crop.hasAlpha()
                    crop.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 90, bo)
                    val data = "data:" + (if (alpha) "image/png" else "image/jpeg") + ";base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    ui.post { web.evaluateJavascript("replaceSelImg(" + JSONObject.quote(data) + ")", null) }
                }
            } catch (e: Throwable) {
                ui.post { Toast.makeText(act, "Cắt ảnh lỗi: " + (e.message ?: ""), Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    /** Kéo chọn vùng cần giữ lại (giống nút chụp màn hình của trình duyệt); thả ra là cắt xong. */
    @SuppressLint("ViewConstructor")
    private inner class CropView(private val shot: Bitmap, private val asNewPhoto: Boolean) : View(act) {
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val border = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val hintBg = Paint().apply { color = 0xCC000000.toInt(); isAntiAlias = true }
        private val hintTx = Paint().apply { color = Color.WHITE; textSize = dp(14).toFloat(); isAntiAlias = true; textAlign = Paint.Align.CENTER }
        private val cancelBg = Paint().apply { color = 0xFFD50000.toInt(); isAntiAlias = true }
        private val img = RectF()
        private val sel = RectF()
        private val cancelBox = RectF()
        private var hasSel = false
        private var sx = 0f; private var sy = 0f

        private fun fit() {
            val k = minOf(width.toFloat() / shot.width, height.toFloat() / shot.height)
            val w = shot.width * k; val h = shot.height * k
            img.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        }

        override fun onDraw(c: Canvas) {
            fit()
            c.drawColor(Color.BLACK)
            val dst = Rect(img.left.toInt(), img.top.toInt(), img.right.toInt(), img.bottom.toInt())
            c.drawBitmap(shot, null, dst, null)
            c.drawRect(img, dim)
            if (hasSel) {
                c.save(); c.clipRect(sel)
                c.drawBitmap(shot, null, dst, null)
                c.restore()
                c.drawRect(sel, border)
            }
            val w = width.toFloat(); val pad = dp(10).toFloat()
            val t = "Kéo để chọn vùng cần cắt"
            val tw = hintTx.measureText(t)
            c.drawRoundRect(w / 2 - tw / 2 - pad, pad, w / 2 + tw / 2 + pad, pad + dp(32), dp(8).toFloat(), dp(8).toFloat(), hintBg)
            c.drawText(t, w / 2, pad + dp(21), hintTx)
            val cw = dp(72).toFloat()
            cancelBox.set(w - cw - pad, pad, w - pad, pad + dp(32))
            c.drawRoundRect(cancelBox, dp(8).toFloat(), dp(8).toFloat(), cancelBg)
            c.drawText("✕ Hủy", cancelBox.centerX(), pad + dp(21), hintTx)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x.coerceIn(img.left, img.right); val y = e.y.coerceIn(img.top, img.bottom)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (cancelBox.contains(e.x, e.y)) { closeOverlay(); return true }
                    sx = x; sy = y; hasSel = false
                }
                MotionEvent.ACTION_MOVE -> {
                    sel.set(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y)); hasSel = true; invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    sel.set(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y))
                    if (sel.width() >= dp(12) && sel.height() >= dp(12)) done() else { hasSel = false; invalidate() }
                }
                MotionEvent.ACTION_CANCEL -> { hasSel = false; invalidate() }
            }
            return true
        }

        private fun done() {
            val k = shot.width / img.width()
            val l = ((sel.left - img.left) * k).toInt().coerceIn(0, shot.width - 1)
            val t = ((sel.top - img.top) * k).toInt().coerceIn(0, shot.height - 1)
            val w = (sel.width() * k).toInt().coerceIn(1, shot.width - l)
            val h = (sel.height() * k).toInt().coerceIn(1, shot.height - t)
            applyCropped(Bitmap.createBitmap(shot, l, t, w, h), asNewPhoto)
        }
    }

    /** Đọc ảnh từ Uri, thu nhỏ còn tối đa 1280 px cho nhẹ. */
    private fun decode(uri: android.net.Uri): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        var sample = 1
        while (opt.outWidth / sample > 2560 || opt.outHeight / sample > 2560) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val m = maxOf(bmp.width, bmp.height)
        if (m > 1280) {
            val k = 1280f / m
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        return bmp
    }
}
