package com.desktopmini.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Màn hình "Tệp": liệt kê mọi tệp nhận về (và ghi hình) còn trên máy, tức là trong vòng 1 giờ gần đây
 * (tệp quá 1 giờ đã bị TransferHost xoá). ▲▼ chọn, OK mở tệp; tệp .apk thì mở trình cài đặt.
 * Nhận tệp .apk mới thì RemoteHub đưa app lên trước, mở màn này và chọn sẵn tệp đó.
 */
class FilesPane(private val act: Activity, private val source: () -> List<File>) {
    val view = FrameLayout(act)

    /** ▼ ở dòng cuối: đưa ô chọn xuống thanh dưới (MainActivity gán). */
    var onExitDown: () -> Unit = { }

    private val ui = Handler(Looper.getMainLooper())
    private val header = TextView(act)
    private val empty = TextView(act)
    private val list = ListView(act)
    private var items: List<File> = emptyList()
    private var sel = 0
    private var longFired = false          // vừa nhấn giữ xong: bỏ qua cú nhả tay (không mở tệp)
    private var extracting = false

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() { refresh(items.getOrNull(sel)?.name); ui.postDelayed(this, 30000) }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
            val row = (cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
                addView(TextView(act).apply {
                    setTextColor(Color.WHITE); textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                addView(TextView(act).apply { setTextColor(0xFF90A4AE.toInt()); textSize = 13f })
            }
            val f = items[i]
            val apk = isApk(f)
            (row.getChildAt(0) as TextView).text = (if (apk) "📦 " else "") + f.name
            (row.getChildAt(1) as TextView).text = describe(f, apk)
            val frame = if (i == sel) GradientDrawable().apply {          // dòng đang chọn: nền xanh neon mờ + viền sáng
                cornerRadius = dp(8).toFloat(); setColor(0x4400E5FF); setStroke(dp(2), 0xFF00E5FF.toInt())
            } else null
            row.background = frame
            if (isZip(f)) attachLongPress(row, i)
            else row.setOnTouchListener(null)
            return row
        }
    }

    init {
        view.setBackgroundColor(0xF005070D.toInt())
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        header.apply {
            setTextColor(0xFF00E5FF.toInt()); textSize = 15f; setPadding(dp(18), dp(10), dp(18), dp(8))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(0xFF0A0D16.toInt())
        }
        col.addView(header, LinearLayout.LayoutParams(-1, -2))
        list.apply {
            adapter = this@FilesPane.adapter
            divider = android.graphics.drawable.ColorDrawable(0x00000000)
            dividerHeight = dp(4)
            setPadding(dp(10), dp(6), dp(10), dp(6)); clipToPadding = false
            isFocusable = false; isFocusableInTouchMode = false
            setOnItemClickListener { _, _, pos, _ ->
                if (longFired) { longFired = false; return@setOnItemClickListener }
                sel = pos; this@FilesPane.adapter.notifyDataSetChanged(); open(pos) }
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(col, FrameLayout.LayoutParams(-1, -1))
        empty.apply {
            text = "Chưa có tệp nào trong 1 giờ qua.\nTệp nhận từ điện thoại sẽ hiện ở đây."
            setTextColor(0xFF90A4AE.toInt()); textSize = 18f; gravity = Gravity.CENTER
        }
        view.addView(empty, FrameLayout.LayoutParams(-1, -1))
    }

    private fun isZip(f: File) = f.name.endsWith(".zip", ignoreCase = true)

    /** Nhấn giữ tệp .zip 1,2 giây thì tự giải nén. Trả false để cú chạm vẫn cuộn/bấm bình thường. */
    private fun attachLongPress(row: View, pos: Int) {
        var sx = 0f; var sy = 0f
        val fire = Runnable {
            longFired = true
            items.getOrNull(pos)?.let { extractZip(it) }
        }
        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; longFired = false; ui.postDelayed(fire, ZIP_HOLD_MS) }
                MotionEvent.ACTION_MOVE -> if (Math.abs(ev.rawX - sx) > dp(12) || Math.abs(ev.rawY - sy) > dp(12)) ui.removeCallbacks(fire)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(fire)
            }
            false
        }
    }

    /** Giải nén mọi tệp trong .zip ra cùng thư mục (danh sách Tệp phẳng nên bỏ cấu trúc thư mục con). Giữ nguyên tệp .zip. */
    private fun extractZip(zip: File) {
        if (extracting) { Toast.makeText(act, "Đang giải nén, đợi một chút", Toast.LENGTH_SHORT).show(); return }
        if (!zip.isFile) { refresh(); return }
        extracting = true
        Toast.makeText(act, "Đang giải nén: " + zip.name, Toast.LENGTH_SHORT).show()
        val dir = zip.parentFile ?: run { extracting = false; return }
        Thread {
            var count = 0
            var err: String? = null
            var firstName: String? = null
            try {
                ZipInputStream(java.io.BufferedInputStream(java.io.FileInputStream(zip))).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) { zin.closeEntry(); continue }
                        var base = e.name.replace('\\', '/').substringAfterLast('/')
                        if (base.isEmpty() || base.startsWith("._") || e.name.startsWith("__MACOSX")) { zin.closeEntry(); continue }
                        base = base.map { if (it in "\\/:*?\"<>|") '_' else it }.joinToString("")
                        var out = File(dir, base)
                        if (out.exists()) {
                            val dot = base.lastIndexOf('.')
                            val stem = if (dot > 0) base.substring(0, dot) else base
                            val ext = if (dot > 0) base.substring(dot) else ""
                            var n = 1
                            while (out.exists()) { out = File(dir, "$stem ($n)$ext"); n++ }
                        }
                        FileOutputStream(out).use { fo ->
                            val buf = ByteArray(65536)
                            while (true) { val r = zin.read(buf); if (r < 0) break; fo.write(buf, 0, r) }
                        }
                        out.setLastModified(System.currentTimeMillis())
                        if (firstName == null) firstName = out.name
                        count++
                        zin.closeEntry()
                    }
                }
            } catch (e: Throwable) { err = e.message ?: e.javaClass.simpleName }
            ui.post {
                extracting = false
                if (err != null) Toast.makeText(act, "Giải nén lỗi: $err" + (if (count > 0) " (đã ra $count tệp)" else ""), Toast.LENGTH_LONG).show()
                else if (count == 0) Toast.makeText(act, "Tệp .zip trống", Toast.LENGTH_SHORT).show()
                else Toast.makeText(act, "Đã giải nén $count tệp ra trang Tệp", Toast.LENGTH_LONG).show()
                refresh(firstName ?: items.getOrNull(sel)?.name)
            }
        }.start()
    }

    private fun isApk(f: File) = f.name.endsWith(".apk", ignoreCase = true)

    private fun size(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format("%.1f GB", b / 1073741824.0)
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1048576.0)
        b >= 1024 -> (b / 1024).toString() + " KB"
        else -> "$b B"
    }

    private fun describe(f: File, apk: Boolean): CharSequence {
        val left = MAX_FILE_AGE_MS - (System.currentTimeMillis() - f.lastModified())
        val t = if (left <= 60000) "sắp bị xoá" else "còn " + (left / 60000) + " phút trước khi bị xoá"
        val sb = SpannableStringBuilder(size(f.length()) + "  •  ")
        val a = sb.length
        sb.append(t)
        if (left < 10 * 60000) sb.setSpan(ForegroundColorSpan(0xFFFFC400.toInt()), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)   // sắp hết hạn: vàng
        if (apk) {
            sb.append("  •  ")
            val b = sb.length
            sb.append("OK để cài đặt")
            sb.setSpan(ForegroundColorSpan(0xFF00E676.toInt()), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (isZip(f)) {
            sb.append("  •  ")
            val b = sb.length
            sb.append("Nhấn giữ 1,2 giây để giải nén")
            sb.setSpan(ForegroundColorSpan(0xFF00E676.toInt()), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** Nạp lại danh sách (mới nhất ở trên); giữ chọn đúng tệp [keep] nếu còn. */
    fun refresh(keep: String? = null) {
        items = try { source() } catch (e: Exception) { emptyList() }
        val at = if (keep != null) items.indexOfFirst { it.name == keep } else -1
        sel = if (at >= 0) at else sel.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        header.text = "TỆP NHẬN 1 GIỜ QUA: ${items.size}   •   QUÁ 1 GIỜ TỰ XOÁ"
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        if (items.isNotEmpty()) list.setSelection(sel)
    }

    /** Mở màn hình và chọn sẵn tệp [name] (tệp .apk vừa nhận). */
    fun highlight(name: String) { refresh(name) }

    fun home() { sel = 0; refresh(); }

    fun onResume() { refresh(items.getOrNull(sel)?.name); ui.removeCallbacks(tick); ui.postDelayed(tick, 30000) }
    fun onPause() { ui.removeCallbacks(tick) }
    fun destroy() { ui.removeCallbacks(tick) }

    /** Phím điều khiển (remote điện thoại / bàn phím). true = đã xử lý. */
    fun onKey(k: String): Boolean {
        when (k) {
            "up" -> { if (sel > 0) { sel--; adapter.notifyDataSetChanged(); list.setSelection(sel) } }
            "down" -> {
                if (items.isNotEmpty() && sel < items.size - 1) { sel++; adapter.notifyDataSetChanged(); list.setSelection(sel) }
                else onExitDown()
            }
            "ok" -> open(sel)
            else -> return false
        }
        return true
    }

    private fun open(pos: Int) {
        val f = items.getOrNull(pos) ?: return
        if (!f.isFile) { refresh(); return }
        if (isApk(f)) installApk(f) else openOther(f)
    }

    private fun uriFor(f: File): Uri =
        if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(act, act.packageName + ".fileprovider", f)
        else Uri.fromFile(f)

    private fun installApk(f: File) {
        // Android 8+: phải bật "Cho phép cài ứng dụng từ nguồn này" cho chính app này trước
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(act, "Hãy bật \"Cho phép từ nguồn này\" cho app rồi quay lại bấm cài lại", Toast.LENGTH_LONG).show()
            try {
                act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) { }
            return
        }
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(f), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(act, "TV không có trình cài đặt ứng dụng", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOther(f: File) {
        val ext = f.name.substringAfterLast('.', "").lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uriFor(f), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(act, "Không có ứng dụng nào mở được tệp này", Toast.LENGTH_SHORT).show()
        }
    }
}

private const val ZIP_HOLD_MS = 1200L
