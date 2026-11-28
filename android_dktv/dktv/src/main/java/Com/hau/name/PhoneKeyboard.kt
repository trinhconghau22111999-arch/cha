package Com.hau.name

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

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
 * Bàn phím riêng của remote DK TV: vẽ kín phần còn lại của màn hình NGANG (không dùng bàn phím hệ thống),
 * gõ Telex tiếng Việt (VN) hoặc gõ thẳng (EN). send(del, ins): xoá [del] ký tự trước con trỏ TV rồi chèn [ins]; enter(): phím Enter.
 */
class PhoneKeyboard(
    private val ctx: Context,
    private val send: (Int, String) -> Unit,
    private val enter: () -> Unit,
    private val onClose: () -> Unit,
    private val onClick: () -> Unit = {},
    private val onLongClick: () -> Unit = {}
) {
    companion object { @Volatile var vnMode = true }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val h = Handler(Looper.getMainLooper())

    private val raw = StringBuilder()
    private var shown = 0
    private var shift = false
    private var symbols = false
    private val echo = StringBuilder()          // chữ vừa gõ (chỉ để hiển thị trên điện thoại)

    /** Vuốt 1 ngón trên bàn phím = vuốt chuột: nhận cử chỉ (đầu tiên là điểm chạm xuống, sau đó là các điểm vuốt). Chạm nhẹ vẫn là gõ phím. */
    var onSwipe: ((MotionEvent) -> Unit)? = null

    val view: LinearLayout = object : LinearLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat() * 1.5f
        private var x0 = 0f
        private var y0 = 0f
        private var swiping = false
        private var down: MotionEvent? = null

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.x; y0 = e.y; swiping = false
                    down?.recycle(); down = MotionEvent.obtain(e)
                }
                MotionEvent.ACTION_MOVE -> if (!swiping && (abs(e.x - x0) > slop || abs(e.y - y0) > slop)) {
                    swiping = true                                  // vuốt: phím đang bị chạm nhận CANCEL, không gõ
                    down?.let { onSwipe?.invoke(it) }
                    onSwipe?.invoke(e)
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (swiping) {
                onSwipe?.invoke(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swiping = false
            }
            return true
        }
    }
    private val body = LinearLayout(ctx)
    private val echoTv = TextView(ctx)

    private val TEAL = 0xFF00897B.toInt()       // nền xanh ngọc của phím

    private fun rect(fill: Int, stroke: Int = Neon.EDGE_HI, r: Int = 8) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke)
    }

    private fun keyBg(base: Int): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rect(0xFF00C9A7.toInt(), Neon.CYAN))
        addState(intArrayOf(), rect(base))
    }

    private fun key(label: String, weight: Float, base: Int = TEAL, sp: Float = 22f, click: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label; textSize = sp; setTextColor(Neon.TEXT); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            isFocusable = false; isFocusableInTouchMode = false
            background = keyBg(base)
            setOnTouchListener { v, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) Haptics.key(v); false }   // rung ngay khi chạm, vẫn nhận click
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }

    private fun spacer(weight: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight) }

    private fun row(vararg items: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in items) addView(v)
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)         // mỗi hàng chia đều chiều cao còn lại
    }

    // ----- gõ -----
    private fun emit(del: Int, ins: String) {
        if (del > 0) echo.setLength((echo.length - del).coerceAtLeast(0))
        echo.append(ins)
        if (echo.length > 400) echo.delete(0, echo.length - 200)
        echoTv.text = echo.toString()
        if (del > 0 || ins.isNotEmpty()) send(del, ins)
    }

    private fun resetWord() { raw.setLength(0); shown = 0 }

    private fun typeLetter(ch: Char) {
        val c = if (shift) Character.toUpperCase(ch) else ch
        if (shift) { shift = false; rebuild() }
        if (!vnMode) { emit(0, c.toString()); return }
        raw.append(c)
        val out = Telex.compose(raw.toString())
        emit(shown, out); shown = out.length
    }

    private fun typeOther(s: String) { resetWord(); emit(0, s) }

    private fun backspace() {
        if (vnMode && raw.isNotEmpty()) {
            raw.setLength(raw.length - 1)
            val out = Telex.compose(raw.toString())
            emit(shown, out); shown = out.length
        } else { resetWord(); emit(1, "") }
    }

    private fun doEnter() {
        resetWord(); echo.setLength(0); echoTv.text = ""
        enter()
    }

    private fun bsKey(weight: Float): TextView {
        val bs = key("⌫", weight, TEAL, 32f) { }
        bs.setOnClickListener(null)
        val rep = object : Runnable { override fun run() { Haptics.key(bs); backspace(); h.postDelayed(this, 70) } }
        bs.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; Haptics.key(v); backspace(); h.postDelayed(rep, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(rep) }
            }
            true
        }
        return bs
    }

    private fun chars(s: String) = s.map { c -> if (symbols) key(c.toString(), 1f) { typeOther(c.toString()) } else key((if (shift) Character.toUpperCase(c) else c).toString(), 1f) { typeLetter(c) } }.toTypedArray()

    private fun rebuild() {
        body.removeAllViews()
        body.addView(row(*"1234567890".map { d -> key(d.toString(), 1f) { typeOther(d.toString()) } }.toTypedArray()))
        if (!symbols) {
            body.addView(row(*chars("qwertyuiop")))
            body.addView(row(spacer(0.5f), *chars("asdfghjkl"), spacer(0.5f)))
            val shiftKey = key("⇧", 1.5f, if (shift) 0xFF7C4DFF.toInt() else TEAL, 32f) { shift = !shift; rebuild() }
            body.addView(row(shiftKey, *chars("zxcvbnm"), bsKey(1.5f)))
        } else {
            body.addView(row(*chars("@#$%&*-+()")))
            body.addView(row(*chars("!\"':;/_=[]")))
            body.addView(row(spacer(1.5f), *chars("~^<>{}|\\"), bsKey(1.5f)))
        }
        body.addView(row(
            key(if (symbols) "ABC" else "?123", 1.5f, TEAL, 18f) { symbols = !symbols; resetWord(); rebuild() },
            key(if (vnMode) "VN" else "EN", 1.2f, TEAL, 18f) { vnMode = !vnMode; resetWord(); rebuild() },
            key(",", 1f) { typeOther(",") },
            key(if (vnMode) "Tiếng Việt" else "English", 4f, TEAL, 16f) { typeOther(" ") },
            key(".", 1f) { typeOther(".") },
            key("?", 1f) { typeOther("?") },
            key("↵", 1.8f, TEAL, 36f) { doEnter() }
        ))
    }

    /** Về trạng thái ban đầu mỗi lần mở bàn phím. */
    fun reset() {
        resetWord(); shift = false; symbols = false
        echo.setLength(0); echoTv.text = ""
        rebuild()
    }

    fun destroy() { h.removeCallbacksAndMessages(null) }

    init {
        view.orientation = LinearLayout.VERTICAL
        view.setBackgroundColor(Neon.BG)
        view.setPadding(0, 0, 0, dp(6))          // lề trên/trái/phải nằm trong thanh trên để vùng chạm chuột ra tới mép
        view.isClickable = true                  // nuốt chạm, không rơi xuống phần dưới

        // Thanh trên: tên + chữ vừa gõ + đóng. Cả thanh còn là 2 nút chuột: nửa trái = chuột trái, nửa phải = chuột phải
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(ctx).apply {
            text = "GÕ SANG TV"; textSize = 10f; setTextColor(Neon.DIM); letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).also { it.rightMargin = dp(8) })
        echoTv.apply {
            hint = "Chữ gõ sẽ hiện ở đây và nhập vào ô đang chọn trên TV"; setHintTextColor(Neon.DIM)
            textSize = 17f; setTextColor(Neon.TEXT); gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.START
            background = rect(Neon.PANEL, Neon.CYAN)
            setPadding(dp(12), 0, dp(12), 0)
        }
        bar.addView(echoTv, LinearLayout.LayoutParams(0, dp(40), 1f))
        bar.addView(TextView(ctx).apply {
            text = "✕"; textSize = 18f; setTextColor(Neon.TEXT); gravity = Gravity.CENTER
            contentDescription = "Đóng bàn phím"
            setOnClickListener { Haptics.tick(this); onClose() }
        }, LinearLayout.LayoutParams(dp(48), dp(40)).also { it.leftMargin = dp(6) })
        bar.setPadding(dp(8), dp(6), dp(8), dp(4))
        bar.setOnTouchListener { v, ev ->        // riêng nút ✕ tự nhận chạm của nó nên vẫn đóng bàn phím
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> Haptics.key(v)
                MotionEvent.ACTION_UP -> if (ev.x >= 0 && ev.x < v.width && ev.y >= 0 && ev.y < v.height) {
                    if (ev.x < v.width / 2f) onClick() else onLongClick()
                }
            }
            true                                 // vuốt trên thanh vẫn là di chuột (view cha chặn lại và gửi CANCEL)
        }
        view.addView(bar, LinearLayout.LayoutParams(-1, -2))

        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(8), 0, dp(8), 0)
        view.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))      // phím chiếm hết phần còn lại của màn hình
        rebuild()
    }
}
