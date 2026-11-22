package com.desktopmini.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
    fun onPause() { if (pageReady) web.evaluateJavascript("flush(false)", null) }

    fun destroy() { try { web.destroy() } catch (e: Exception) { } }

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
                if (manual == 1) ui.post { Toast.makeText(act, "Ghi chú đang trống", Toast.LENGTH_SHORT).show() }
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
