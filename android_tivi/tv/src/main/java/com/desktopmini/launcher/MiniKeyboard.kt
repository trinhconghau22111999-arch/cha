package com.desktopmini.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Bộ gõ tiếng Việt kiểu Telex: ghép các phím đã gõ của TỪ đang gõ thành chữ có dấu. */
object Telex {
    private val TONES = mapOf(
        'a' to "aáàảãạ", 'ă' to "ăắằẳẵặ", 'â' to "âấầẩẫậ", 'e' to "eéèẻẽẹ", 'ê' to "êếềểễệ",
        'i' to "iíìỉĩị", 'o' to "oóòỏõọ", 'ô' to "ôốồổỗộ", 'ơ' to "ơớờởỡợ", 'u' to "uúùủũụ",
        'ư' to "ưứừửữự", 'y' to "yýỳỷỹỵ")
    private const val MODS = "ăâêôơư"

    fun compose(raw: String): String {
        val buf = ArrayList<Char>()
        val caps = ArrayList<Boolean>()
        var tone = 0
        for (ch in raw) {
            val up = Character.isUpperCase(ch)
            val c = Character.toLowerCase(ch)
            val n = buf.size
            val last: Char = if (n == 0) '\u0000' else buf[n - 1]
            val hasV = buf.any { TONES.containsKey(it) }
            val t = when (c) { 's' -> 1; 'f' -> 2; 'r' -> 3; 'x' -> 4; 'j' -> 5; else -> 0 }
            if (t != 0 && hasV) {
                if (tone == t) { tone = 0; buf.add(c); caps.add(up) } else tone = t
                continue
            }
            if (c == 'z' && tone != 0) { tone = 0; continue }
            if (c == 'd' && last == 'd') { buf[n - 1] = 'đ'; continue }
            if ((c == 'a' || c == 'e' || c == 'o') && last == c) {
                buf[n - 1] = when (c) { 'a' -> 'â'; 'e' -> 'ê'; else -> 'ô' }
                continue
            }
            if (c == 'w') {
                if (last == 'a') { buf[n - 1] = 'ă'; continue }
                if (last == 'o') {
                    if (n >= 2 && (buf[n - 2] == 'u' || buf[n - 2] == 'ư')) buf[n - 2] = 'ư'
                    buf[n - 1] = 'ơ'; continue
                }
                if (last == 'u') { buf[n - 1] = 'ư'; continue }
                buf.add('ư'); caps.add(up); continue
            }
            buf.add(c); caps.add(up)
        }
        if (tone != 0) {
            var idx = ArrayList<Int>()
            for (i in buf.indices) if (TONES.containsKey(buf[i])) idx.add(i)
            val keep = ArrayList<Int>()
            for (k in idx.indices) {
                val i = idx[k]
                val skip = i > 0 && ((buf[i] == 'u' && buf[i - 1] == 'q') || (buf[i] == 'i' && buf[i - 1] == 'g')) && k + 1 < idx.size
                if (!skip) keep.add(i)
            }
            idx = keep
            if (idx.isNotEmpty()) {
                var pos = -1
                for (i in idx) if (MODS.indexOf(buf[i]) >= 0) pos = i
                if (pos < 0) {
                    pos = when (idx.size) {
                        1 -> idx[0]
                        2 -> {
                            val fin = idx[1] != buf.size - 1
                            val pair = "" + buf[idx[0]] + buf[idx[1]]
                            if (fin || pair == "oa" || pair == "oe" || pair == "uy") idx[1] else idx[0]
                        }
                        else -> idx[1]
                    }
                }
                val s = TONES[buf[pos]]
                if (s != null) buf[pos] = s[tone]
            }
        }
        val sb = StringBuilder()
        for (i in buf.indices) sb.append(if (caps[i]) Character.toUpperCase(buf[i]) else buf[i])
        return sb.toString()
    }
}

/**
 * Bàn phím ảo nổi, nhỏ: nằm TRONG giao diện (root) nên xoay theo giao diện; kéo thanh trên cùng để di chuyển.
 * send(del, ins): xoá [del] ký tự trước con trỏ rồi chèn [ins]; enter(): phím Enter.
 */
class MiniKeyboard(
    private val ctx: Context,
    private val send: (Int, String) -> Unit,
    private val enter: () -> Unit,
    private val onClose: () -> Unit,
    private val onMoved: (Int, Int) -> Unit
) {
    companion object {
        @Volatile var vnMode = true
        const val SCALE = 1.1f          // phóng to bàn phím (1.1 = +10%)
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density * SCALE).toInt()
    private val h = Handler(Looper.getMainLooper())

    private val raw = StringBuilder()
    private var shown = 0
    private var shift = false
    private val letterKeys = ArrayList<Pair<Char, TextView>>()
    private lateinit var shiftKey: TextView
    private lateinit var langKey: TextView

    val view: LinearLayout = LinearLayout(ctx)
    var layoutListener: View.OnLayoutChangeListener? = null

    private fun rect(c: Int, r: Int = 5) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(c) }
    private fun keyBg(base: Int = 0xFF1B2340.toInt()): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rect(0xFF00ACC1.toInt()))
        addState(intArrayOf(), rect(base))
    }

    private fun key(label: String, weight: Float, base: Int = 0xFF1B2340.toInt(), sp: Float = 13f, click: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = sp * SCALE; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isFocusable = false; isFocusableInTouchMode = false
            background = keyBg(base)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, dp(30), weight).also { it.setMargins(dp(1), dp(1), dp(1), dp(1)) }
        }

    private fun spacer(weight: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, dp(30), weight) }

    private fun row(vararg items: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in items) addView(v)
    }

    // ----- gõ -----
    /** Quên từ đang gõ (gọi khi con trỏ được đặt sang chỗ khác, vd ô bảng khác) để không chèn lại chữ cũ. */
    fun resetWord() { raw.setLength(0); shown = 0 }

    private fun typeLetter(ch: Char) {
        val c = if (shift) Character.toUpperCase(ch) else ch
        if (shift) { shift = false; refreshLabels() }
        if (!vnMode) { send(0, c.toString()); return }
        raw.append(c)
        val out = Telex.compose(raw.toString())
        send(shown, out); shown = out.length
    }

    private fun typeOther(s: String) { resetWord(); send(0, s) }

    private fun backspace() {
        if (vnMode && raw.isNotEmpty()) {
            raw.setLength(raw.length - 1)
            val out = Telex.compose(raw.toString())
            send(shown, out); shown = out.length
        } else { resetWord(); send(1, "") }
    }

    private fun refreshLabels() {
        for ((c, tv) in letterKeys) tv.text = (if (shift) Character.toUpperCase(c) else c).toString()
        shiftKey.background = keyBg(if (shift) 0xFF7C4DFF.toInt() else 0xFF2A3556.toInt())
        langKey.text = if (vnMode) "VN" else "EN"
    }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setPadding(dp(4), dp(3), dp(4), dp(4))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat(); setColor(0xEE0A0D16.toInt()); setStroke(dp(2), 0xFF00E5FF.toInt())
        }
        view.isClickable = true            // nuốt chạm trên bàn phím, không rơi xuống trang bên dưới
        if (android.os.Build.VERSION.SDK_INT >= 21) view.elevation = dp(8).toFloat()

        // Thanh trên: kéo để di chuyển, ✕ để đóng
        val title = TextView(ctx).apply {
            text = "⠿  Bàn phím"; textSize = 11f * SCALE; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        val close = TextView(ctx).apply {
            text = "✕"; textSize = 14f * SCALE; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = rect(0xFFD50000.toInt(), 6)
            setOnClickListener { onClose() }
        }
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(title, LinearLayout.LayoutParams(0, dp(24), 1f))
        strip.addView(close, LinearLayout.LayoutParams(dp(36), dp(24)))
        var dx = 0f
        var dy = 0f
        strip.setOnTouchListener { _, ev ->
            val lp = view.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX - lp.leftMargin; dy = ev.rawY - lp.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (ev.rawX - dx).toInt(); lp.topMargin = (ev.rawY - dy).toInt()
                    clampLp(lp); view.layoutParams = lp
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onMoved(lp.leftMargin, lp.topMargin)
            }
            true
        }
        view.addView(strip, LinearLayout.LayoutParams(-1, -2))

        // Hàng số
        view.addView(row(*"1234567890".map { d -> key(d.toString(), 1f) { typeOther(d.toString()) } }.toTypedArray()))
        // Hàng chữ
        fun letter(c: Char, w: Float = 1f): TextView = key(c.toString(), w) { typeLetter(c) }.also { letterKeys.add(Pair(c, it)) }
        view.addView(row(*"qwertyuiop".map { letter(it) }.toTypedArray()))
        view.addView(row(spacer(0.5f), *"asdfghjkl".map { letter(it) }.toTypedArray(), spacer(0.5f)))
        shiftKey = key("⇧", 1.5f, 0xFF2A3556.toInt()) { shift = !shift; refreshLabels() }
        val bs = key("⌫", 1.5f, 0xFF2A3556.toInt()) { }
        bs.setOnClickListener(null)
        val rep = object : Runnable { override fun run() { backspace(); h.postDelayed(this, 70) } }
        bs.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; backspace(); h.postDelayed(rep, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(rep) }
            }
            true
        }
        view.addView(row(shiftKey, *"zxcvbnm".map { letter(it) }.toTypedArray(), bs))
        // Hàng cuối
        langKey = key("VN", 1.5f, 0xFF2A3556.toInt(), 12f) { vnMode = !vnMode; resetWord(); refreshLabels() }
        view.addView(row(
            langKey,
            key("@", 1f) { typeOther("@") },
            key(",", 1f) { typeOther(",") },
            key("", 3f) { typeOther(" ") },
            key(".", 1f) { typeOther(".") },
            key("?", 1f) { typeOther("?") },
            key("↵", 1.5f, 0xFF00897B.toInt()) { resetWord(); enter() }
        ))
        refreshLabels()
    }

    /** Giữ bàn phím nằm gọn trong giao diện (gọi khi kéo và khi giao diện đổi kích thước / xoay). */
    private fun clampLp(lp: FrameLayout.LayoutParams) {
        val p = view.parent as? View ?: return
        val maxX = (p.width - view.width).coerceAtLeast(0)
        val maxY = (p.height - view.height).coerceAtLeast(0)
        lp.leftMargin = lp.leftMargin.coerceIn(0, maxX)
        lp.topMargin = lp.topMargin.coerceIn(0, maxY)
    }

    fun clamp() {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val x = lp.leftMargin; val y = lp.topMargin
        clampLp(lp)
        if (x != lp.leftMargin || y != lp.topMargin) view.layoutParams = lp
    }

    fun destroy() { h.removeCallbacksAndMessages(null) }
}
