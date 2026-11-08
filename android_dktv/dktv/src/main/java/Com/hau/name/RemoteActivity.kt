package Com.hau.name

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [BAN GOP] Gop 2 app dieu khien: DK TV (repo DK_TV: nut tat/mo man hinh, do sang, khoa cam ung)
 * va DK TV 2 (module dktv trong repo a3: nut man hinh chinh, 5 phim huong, giu ▼ 2 giay xuong thanh duoi,
 * con tro co gia toc, cuon co quan tinh). Giu TAT CA chuc nang cua ca hai.
 *
 * Tay cam dieu khien app TV (thay cho giao dien chup anh/gui tep cu - cai cu van con trong
 * code nhung khong con duoc mo).
 *
 * Moi lenh la 1 tin JSON {"type":"remote","action":...} gui qua DataChannel truyen tep co san
 * (cung ma co dinh 654321, cung ket noi WebRTC/Firebase) toi app TV:
 *   - volume / brightness : level = 0..100 (am luong / do sang man hinh TV)
 *   - quality : q = 360 | 480 | 720 (chat luong video YouTube)
 *   - back : nut LUI - lui 1 buoc tren TV (giong nut Back)
 *   - fullscreen : phong to / thu nho video dang phat tren TV
 *   - media : cmd = rew | fwd | playpause | next (tua 10 giay, phat/dung, bai/video ke tiep)
 *   - touchlock : on = true (TV khong nhan cam ung nua) / false
 *   - key    : key = up | down | left | right | ok (5 phim dieu huong tren khung chuot)
 *   - move   : dx, dy (chuot cam ung, di chuyen tuong doi)
 *   - click  : cham 1 lan tren touchpad
 *   - scroll : dy (2 ngon tay truot)
 *   - screen : on = false (tat man hinh TV) / true (bat lai)
 *   - voice  : q = cau noi da nhan dang tren dien thoai (TV mo YouTube tim va mo video)
 */
class RemoteActivity : AppCompatActivity(), CameraStreamService.ConnectionStatusListener {

    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnScreen: View   // nut MAN HINH CHINH (home)
    private lateinit var btnPower: View    // nut TAT / MO MAN HINH TV
    private lateinit var powerBox: LinearLayout

    private var attachedService: CameraStreamService? = null
    private var connected = false
    private var tvScreenOn = true
    private var lastWarnAt = 0L

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val q = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!q.isNullOrBlank()) send(JSONObject().put("action", "voice").put("q", q.trim()))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int = 0, oval: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        // Back chi dua app xuong nen (dich vu ket noi van chay), khong thoat han.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) }
        })
        setConnected(false)
    }

    // ---------------------------------------------------------------- UI

    /** Khung vat goc vien neon (kieu gaming). */
    private fun neon(fill: Int, edge: Int, cutDp: Float = 10f, glow: Boolean = true, grid: Boolean = false): Drawable =
        NeonDrawable(fill, edge, cutDp, glow = glow, grid = grid)

    /** Nut vat goc 2 trang thai: thuong / dang nhan (sang vien). */
    private fun neonBtn(fill: Int, edge: Int, pressedFill: Int, cutDp: Float = 10f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), NeonDrawable(pressedFill, edge, cutDp, glow = true))
        addState(intArrayOf(), NeonDrawable(fill, edge, cutDp, glow = false))
    }

    private fun caption(t: String) = TextView(this).apply {
        text = t; textSize = 10f; setTextColor(Neon.DIM); letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
    }

    private fun labeled(btn: View, label: String, size: Int = 56, compact: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(btn, LinearLayout.LayoutParams(dp(size), dp(size)))
        addView(caption(label).also { if (compact) { it.textSize = 9f; it.letterSpacing = 0.05f } },
            LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(if (compact) 3 else 6) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        // Thanh he thong toi de hop nen gaming
        window.statusBarColor = Neon.BG
        window.navigationBarColor = Neon.BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0F1426.toInt(), Neon.BG))
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }

        // Dau trang: ten neon + the trang thai ket noi
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        title.addView(TextView(this).apply {
            text = "DK TV"; textSize = 18f; setTextColor(Neon.CYAN); letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        title.addView(TextView(this).apply {
            text = "REMOTE"; textSize = 10f; setTextColor(Neon.MAGENTA); letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.bottomMargin = dp(3) })
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        statusText = TextView(this).apply {
            textSize = 10.5f; letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        statusDot = View(this)
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = neon(Neon.PANEL, Neon.EDGE, 8f, glow = false)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        chip.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).also { it.rightMargin = dp(8) })
        chip.addView(statusText)
        // Chạm vào thẻ trạng thái ("CHƯA KẾT NỐI"/"ĐÃ KẾT NỐI") để đổi mã ghép nối
        chip.isClickable = true
        chip.setOnClickListener { showCodeDialog() }
        head.addView(chip)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        // Duong ke gradient neon cyan -> tim -> hong
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Neon.CYAN, Neon.VIOLET, Neon.MAGENTA))
        }, LinearLayout.LayoutParams(-1, dp(2)).also { it.topMargin = dp(8) })

        // Hang tren: tat/mo man hinh + chuyen doi giao dien + khoa cam ung + phong to + giong noi
        btnPower = IconButton(this, IconButton.POWER).also {
            it.rect = true
            it.setOnClickListener {
                tvScreenOn = !tvScreenOn
                send(JSONObject().put("action", "screen").put("on", tvScreenOn))
                refreshPowerButton()
            }
        }
        powerBox = labeled(btnPower, "TẮT\nMÀN HÌNH", 56, true)
        // Nut "Chuyển đổi giao diện": đang ở TV/YouTube/app khác -> về màn hình chính của app; đang ở màn hình chính của app -> về màn hình chính của TV
        btnScreen = IconButton(this, IconButton.HOME).also {
            it.rect = true
            it.setOnClickListener { send(JSONObject().put("action", "home")) }
        }
        val btnMic = IconButton(this, IconButton.MIC).also {
            it.rect = true
            it.setOnClickListener {
                startVoice()
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnTouch = IconButton(this, IconButton.TOUCH).also {
            it.rect = true
            it.setOnClickListener {
                tvTouchOff = !tvTouchOff
                send(JSONObject().put("action", "touchlock").put("on", tvTouchOff))
                refreshTouchButton()
            }
        }
        val btnFull = IconButton(this, IconButton.FULL).also {
            it.rect = true
            it.setOnClickListener { send(JSONObject().put("action", "fullscreen")) }
        }
        // 5 nut cung co, cach deu, nhan 2 dong cho can doi
        screenBox = labeled(btnScreen, "CHUYỂN ĐỔI\nGIAO DIỆN", 56, true)
        touchBox = labeled(btnTouch, "TẮT CẢM\nỨNG TV", 56, true)
        // Nút "Tắt cảm ứng TV" (touchBox) đã được ẩn khỏi giao diện
        for (b in listOf(powerBox, screenBox, labeled(btnFull, "PHÓNG TO\nMÀN HÌNH", 56, true), labeled(btnMic, "GIỌNG\nNÓI", 56, true))) {
            top.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(top, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        refreshPowerButton()
        refreshScreenButton()
        refreshTouchButton()

        // Chuot cam ung: khung toi vat goc, vien neon cyan, luoi ke o
        val pad = FrameLayout(this).apply { background = neon(Neon.PANEL, Neon.CYAN, 24f, glow = true, grid = true) }
        // 5 phim: len / xuong / trai / phai / OK (giua) - nam trong khung chuot, can doi
        fun key(kind: Int, k: String, w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
            val v = DPadButton(this, kind).also { btn ->
                btn.setOnClickListener {
                    if (k == "down" && barHoldFired) { barHoldFired = false; return@setOnClickListener }   // vua giu 2s: khong tinh la bam
                    send(JSONObject().put("action", "key").put("key", k))
                }
                // Giu phim ▼ dung 2 giay: nhay xuong thanh duoi cung (Menu / TV / YouTube / Ai Kara...) cua TV
                if (k == "down") btn.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            barHoldFired = false
                            ui.removeCallbacks(barHold); ui.postDelayed(barHold, BAR_HOLD_MS)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(barHold)
                    }
                    false
                }
            }
            pad.addView(v, FrameLayout.LayoutParams(dp(w), dp(h), gravity).also {
                it.setMargins(dp(l), dp(t), dp(r), dp(b))
            })
        }
        key(DPadButton.UP, "up", 100, 52, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 24)
        key(DPadButton.DOWN, "down", 100, 52, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 24)
        key(DPadButton.LEFT, "left", 52, 100, Gravity.START or Gravity.CENTER_VERTICAL, l = 26)
        key(DPadButton.RIGHT, "right", 52, 100, Gravity.END or Gravity.CENTER_VERTICAL, r = 26)
        key(DPadButton.OK, "ok", 72, 72, Gravity.CENTER)
        attachTouchpad(pad)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(10); it.bottomMargin = dp(4) })
        // Dòng chú thích cách dùng chuột cảm ứng đã được ẩn khỏi giao diện
        root.addView(View(this), LinearLayout.LayoutParams(-1, dp(8)))

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Hang 1: 2 thanh truot nam ngang (am luong | do sang), moi thanh nam trong 1 the toi vat goc
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sliders.addView(sliderRow("ÂM LƯỢNG", "volume", 50, Neon.CYAN, Neon.VIOLET), LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(5) })
        sliders.addView(sliderRow("ĐỘ SÁNG", "brightness", 70, Neon.AMBER, Neon.MAGENTA), LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(5) })
        panel.addView(sliders, LinearLayout.LayoutParams(-1, -2))
        // Hang 2: LUI (vien hong) + BAI TIEP (nen cyan dac) cung 1 hang, rong bang nhau
        val btnBack = TextView(this).apply {
            text = "LÙI"; textSize = 14f; gravity = Gravity.CENTER; letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Neon.MAGENTA)
            background = neonBtn(Neon.PANEL, Neon.MAGENTA, mix2(Neon.PANEL, Neon.MAGENTA))
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "back")) }
        }
        val btnNext = TextView(this).apply {
            text = "BÀI TIẾP"; textSize = 14f; gravity = Gravity.CENTER; letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Neon.INK)
            background = neonBtn(0xFF00C8E0.toInt(), Neon.CYAN, 0xFF0098AD.toInt())
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "media").put("cmd", "next")) }
        }
        val backNext = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backNext.addView(btnBack, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.rightMargin = dp(4) })
        backNext.addView(btnNext, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.leftMargin = dp(4) })
        panel.addView(backNext, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        // Chat luong video: nhan ben trai + 3 nut cung 1 hang (360p | 480p | 720p)
        val q = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        q.addView(caption("CHẤT LƯỢNG\nVIDEO").also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(dp(LABEL_W), -2))
        btnQ360 = qualityButton(360); btnQ480 = qualityButton(480); btnQ720 = qualityButton(720)
        q.addView(btnQ360, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.rightMargin = dp(3) })
        q.addView(btnQ480, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.leftMargin = dp(3); it.rightMargin = dp(3) })
        q.addView(btnQ720, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.leftMargin = dp(3) })
        panel.addView(q, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        // Hang duoi cung: 3 nut vat goc, rong bang nhau: lui 10s, phat/dung, tien 10s
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun mediaBtn(kind: Int, cmd: String) = IconButton(this, kind).also { b ->
            b.rect = true
            b.setOnClickListener { send(JSONObject().put("action", "media").put("cmd", cmd)) }
        }
        val mediaBtns = listOf(mediaBtn(IconButton.REW, "rew"), mediaBtn(IconButton.PLAY, "playpause"), mediaBtn(IconButton.FWD, "fwd"))
        for ((i, b) in mediaBtns.withIndex()) {
            media.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).also {
                if (i > 0) it.leftMargin = dp(4); if (i < mediaBtns.size - 1) it.rightMargin = dp(4)
            })
        }
        panel.addView(media, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        refreshQuality()
        return root
    }

    /** Pha 30% mau nhan vao nen panel - dung cho trang thai dang nhan cua nut LUI. */
    private fun mix2(base: Int, accent: Int): Int = Color.rgb(
        (Color.red(base) * 0.65f + Color.red(accent) * 0.35f).toInt(),
        (Color.green(base) * 0.65f + Color.green(accent) * 0.35f).toInt(),
        (Color.blue(base) * 0.65f + Color.blue(accent) * 0.35f).toInt()
    )

    private lateinit var btnQ360: TextView
    private lateinit var btnQ480: TextView
    private lateinit var btnQ720: TextView
    private var quality = 0

    /** Thanh truot neon: the toi vat goc; tren: nhan + so (mau nhan); duoi: duong day toi, phan keo la gradient [c1] -> [c2], nut tron sang vien. */
    private fun sliderRow(label: String, action: String, initial: Int, c1: Int, c2: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = neon(Neon.PANEL, Neon.EDGE, 10f, glow = false)
        setPadding(dp(12), dp(8), dp(10), dp(4))
        val value = TextView(this@RemoteActivity).apply {
            text = "$initial"; textSize = 13f; setTextColor(c1); gravity = Gravity.END
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val labelRow = LinearLayout(this@RemoteActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        labelRow.addView(caption(label).also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
        labelRow.addView(value, LinearLayout.LayoutParams(-2, -2))
        addView(labelRow, LinearLayout.LayoutParams(-1, -2))
        val trackH = dp(10)
        val bg = GradientDrawable().apply {
            setColor(0xFF05070C.toInt()); cornerRadius = trackH / 2f; setStroke(dp(1), Neon.EDGE_HI); setSize(-1, trackH)
        }
        val fg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            cornerRadius = trackH / 2f; setSize(-1, trackH)
        }
        val layers = LayerDrawable(arrayOf<Drawable>(bg, ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress)
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Neon.TEXT); setStroke(dp(3), c2); setSize(dp(22), dp(22))
        }
        val sb = SeekBar(this@RemoteActivity).apply {
            max = 100; progress = initial
            progressDrawable = layers
            thumb = knob
            splitTrack = false
            setPadding(dp(12), 0, dp(12), 0)
            var last = 0L
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = "$p"
                    if (!fromUser) return
                    val now = SystemClock.uptimeMillis()
                    if (now - last >= 80) {
                        last = now
                        send(JSONObject().put("action", action).put("level", p), warn = false)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    send(JSONObject().put("action", action).put("level", s.progress))
                }
            })
        }
        addView(sb, LinearLayout.LayoutParams(-1, dp(30)))
    }

    private fun qualityButton(q: Int) = TextView(this).apply {
        text = "${q}p"; textSize = 13f; gravity = Gravity.CENTER; letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setOnClickListener {
            Haptics.tick(this)
            quality = q
            refreshQuality()
            send(JSONObject().put("action", "quality").put("q", q))
        }
    }

    /** Nut dang chon: nen cyan dac chu den, co vang sang; nut con lai: nen toi vien xam chu sang. */
    private fun refreshQuality() {
        for ((b, q) in listOf(btnQ360 to 360, btnQ480 to 480, btnQ720 to 720)) {
            val on = quality == q
            b.background = if (on) neon(Neon.CYAN, Neon.CYAN, 9f, glow = true) else neon(Neon.PANEL, Neon.EDGE_HI, 9f, glow = false)
            b.setTextColor(if (on) Neon.INK else Neon.TEXT)
        }
    }

    private lateinit var btnTouch: View
    private lateinit var touchBox: LinearLayout
    private lateinit var screenBox: LinearLayout
    private var tvTouchOff = false

    /** Nut khoa cam ung: dang khoa thi dao mau + co gach cheo, nhan doi thanh "BAT CAM UNG TV". */
    private fun refreshTouchButton() {
        (btnTouch as IconButton).inverted = tvTouchOff
        (touchBox.getChildAt(1) as TextView).text = if (tvTouchOff) "BẬT CẢM\nỨNG TV" else "TẮT CẢM\nỨNG TV"
    }

    /** Nut tat man hinh: do khi TV dang sang, xanh khi TV dang tat. */
    private fun refreshPowerButton() {
        (btnPower as IconButton).tint = if (tvScreenOn) Neon.RED else Neon.GREEN
        (powerBox.getChildAt(1) as TextView).text = if (tvScreenOn) "TẮT\nMÀN HÌNH" else "MỞ\nMÀN HÌNH"
    }

    /** Nut Chuyen doi giao dien: xanh duong = TV, do = YouTube (theo che do TV bao ve). */
    private fun refreshScreenButton() {
        (btnScreen as IconButton).tint =
            if (RemoteState.mode == "yt") Neon.RED else Neon.BLUE
        (screenBox.getChildAt(1) as TextView).text = "CHUYỂN ĐỔI\nGIAO DIỆN"
    }

    // ---------------------------------------------------------------- Touchpad

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var moved = false
    private var multi = false
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendScroll = 0f
    private var flushScheduled = false
    private var lastMoveT = 0L
    private var scrollActive = false
    private var flingV = 0f
    private var flingLastT = 0L
    private val sampT = LongArray(6)
    private val sampY = FloatArray(6)
    private var sampN = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchpad(pad: View) {
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                    downAt = e.eventTime; moved = false; multi = false
                    stopFling(); scrollActive = false; sampN = 0; lastMoveT = e.eventTime
                    // Bao TV hien con tro ngay luc cham (khong cho den khi truot)
                    send(JSONObject().put("action", "move").put("dx", 0).put("dy", 0), warn = false)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    stopFling(); sampN = 0
                    lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        pendScroll += (y - lastY) * SCROLL_GAIN
                        lastY = y
                        scrollActive = true
                        addSample(e.eventTime, y)
                        scheduleFlush()
                    } else if (!multi) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        lastX = e.x; lastY = e.y
                        if (!moved && (abs(e.x - startX) > dp(6) || abs(e.y - startY) > dp(6))) moved = true
                        // Gia toc con tro: di cham = chinh xac, vuot nhanh = bay xa (nhu chuot may tinh)
                        val dt = (e.eventTime - lastMoveT).coerceAtLeast(1L)
                        lastMoveT = e.eventTime
                        val v = kotlin.math.hypot(dx, dy) / dt / resources.displayMetrics.density   // dp tren ms
                        val t = ((v - ACC_V0) / (ACC_V1 - ACC_V0)).coerceIn(0f, 1f)
                        val g = MOVE_GAIN_SLOW + (MOVE_GAIN_FAST - MOVE_GAIN_SLOW) * t * t * (3f - 2f * t)
                        pendDx += dx * g
                        pendDy += dy * g
                        scheduleFlush()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (scrollActive && e.pointerCount == 2) startFling(e.eventTime)
                    // Con 1 ngon sau khi nha ngon thu 2: khong gay nhay con tro
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) { lastX = e.getX(idx); lastY = e.getY(idx) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi && e.eventTime - downAt < TAP_MS) {
                        Haptics.tick(pad)
                        send(JSONObject().put("action", "click"))
                    }
                }
            }
            true
        }
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    private var lastFlushAt = 0L
    private var barHoldFired = false
    private val barHold = Runnable {
        barHoldFired = true
        pendingHaptic()
        send(JSONObject().put("action", "key").put("key", "bar"))
    }
    private fun pendingHaptic() { try { Haptics.tick(window.decorView) } catch (e: Exception) { } }

    /** Gui ngay cu truot dau tien; cac cu tiep theo gop toi da ~60 tin/giay de khong nghen kenh truyen. */
    private fun scheduleFlush() {
        if (flushScheduled) return
        val wait = 16L - (SystemClock.uptimeMillis() - lastFlushAt)
        if (wait <= 0L) { flushNow(); return }
        flushScheduled = true
        ui.postDelayed({ flushScheduled = false; flushNow() }, wait)
    }

    private fun addSample(t: Long, y: Float) {
        if (sampN == sampT.size) {
            for (i in 1 until sampN) { sampT[i - 1] = sampT[i]; sampY[i - 1] = sampY[i] }
            sampN--
        }
        sampT[sampN] = t; sampY[sampN] = y; sampN++
    }

    /** Nha 2 ngon dang luot: cuon tiep voi van toc cu va cham dan (quan tinh nhu touchpad laptop). */
    private fun startFling(upT: Long) {
        scrollActive = false
        if (sampN < 2 || upT - sampT[sampN - 1] > 80) { sampN = 0; return }       // dung ngon truoc khi nha: khong troi
        var i0 = sampN - 1
        while (i0 > 0 && sampT[sampN - 1] - sampT[i0 - 1] <= 90) i0--
        val dt = (sampT[sampN - 1] - sampT[i0]).coerceAtLeast(1L)
        val v = (sampY[sampN - 1] - sampY[i0]) / dt * SCROLL_GAIN               // px tren ms
        sampN = 0
        if (kotlin.math.abs(v) < FLING_MIN_V) return
        flingV = v.coerceIn(-FLING_MAX_V, FLING_MAX_V)
        flingLastT = SystemClock.uptimeMillis()
        ui.removeCallbacks(flingStep); ui.post(flingStep)
    }

    private fun stopFling() { flingV = 0f; ui.removeCallbacks(flingStep) }

    private val flingStep = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - flingLastT).coerceIn(1L, 50L)
            flingLastT = now
            pendScroll += flingV * dt
            flingV *= Math.pow(FLING_DECAY.toDouble(), dt / 16.0).toFloat()       // ma sat: giam deu theo thoi gian
            scheduleFlush()
            if (kotlin.math.abs(flingV) >= FLING_STOP_V) ui.postDelayed(this, 16) else flingV = 0f
        }
    }

    private fun flushNow() {
        lastFlushAt = SystemClock.uptimeMillis()
        val mx = pendDx.roundToInt(); val my = pendDy.roundToInt()
        val sc = pendScroll.roundToInt()
        if (mx != 0 || my != 0) {
            pendDx -= mx; pendDy -= my
            send(JSONObject().put("action", "move").put("dx", mx).put("dy", my), warn = false)
        }
        if (sc != 0) {
            pendScroll -= sc
            send(JSONObject().put("action", "scroll").put("dy", sc), warn = false)
        }
    }

    // ---------------------------------------------------------------- Mic

    private fun startVoice() {
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên video, tên kênh TV, hoặc “mở TV” / “mở YouTube”")
        }
        try { voiceLauncher.launch(it) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Điện thoại chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- Đổi mã ghép nối

    /** Hộp thoại đổi mã (vd 654321 -> 123456). Bấm OK: lưu mã mới và nối lại ngay bằng mã đó. */
    private fun showCodeDialog() {
        val current = CameraActivity.savedCode(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
            setSelection(text.length)
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
            setTextColor(Neon.CYAN)
            setHintTextColor(Neon.DIM)
            hint = "123456"
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Đổi mã ghép nối")
            .setMessage("Nhập mã của TV/máy tính cần điều khiển (4–12 chữ số). Mã hiện tại: $current")
            .setView(box)
            .setPositiveButton("OK", null)
            .setNegativeButton("Hủy", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (!CameraActivity.isValidCode(code)) {
                    Toast.makeText(this, "Mã phải gồm 4–12 chữ số", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                applyNewCode(code)
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun applyNewCode(code: String) {
        getSharedPreferences(CameraActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(CameraActivity.KEY_FIXED_CODE, code).apply()
        val svc = CameraStreamService.instance
        if (svc != null) svc.changeRoomCode(code) else ensureService(force = true)
        setConnected(false)
        Toast.makeText(this, "Đã đổi mã thành $code — đang kết nối lại…", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- Gui lenh

    private fun send(cmd: JSONObject, warn: Boolean = true) {
        val json = cmd.put("type", "remote").toString()
        sender.execute {
            val n = CameraStreamService.instance?.sendRemoteCommand(json) ?: 0
            if (n == 0 && warn) {
                val now = System.currentTimeMillis()
                if (now - lastWarnAt > 3000) {
                    lastWarnAt = now
                    ui.post { Toast.makeText(this, "Chưa kết nối với TV", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Trang thai ket noi

    override fun onConnectionStatusChanged(connectedCount: Int, maxViewers: Int) {
        setConnected(connectedCount > 0)
    }

    private fun setConnected(on: Boolean) {
        connected = on
        val c = if (on) Neon.GREEN else Neon.RED
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
            setStroke(dp(2), (c and 0x00FFFFFF) or 0x55000000)
        }
        statusText.setTextColor(c)
        statusText.text = if (on) "ĐÃ KẾT NỐI"
        else "CHƯA KẾT NỐI"
    }

    /** Service co the chua kip khoi dong luc mo man hinh: thu gan lai moi giay cho toi khi co. */
    private val attachLoop = object : Runnable {
        override fun run() {
            val svc = CameraStreamService.instance
            if (svc == null) ensureService(force = false)     // service bị hệ thống kill ở nền -> tự bật lại
            if (svc !== attachedService) {
                attachedService?.removeConnectionStatusListener(this@RemoteActivity)
                attachedService = svc
                svc?.addConnectionStatusListener(this@RemoteActivity)
                if (svc == null) setConnected(false)
            }
            ui.postDelayed(this, 1000)
        }
    }

    private val modeListener: (String) -> Unit = { ui.post { refreshScreenButton() } }

    /** Mốc lúc app xuống nền (elapsedRealtime) — để biết đã vắng mặt bao lâu khi quay lại. */
    private var pausedAt = 0L
    private var lastEnsureAt = 0L

    /**
     * Bật lại CameraStreamService nếu nó không còn chạy. Khi app nằm nền lâu, hệ thống (đặc biệt
     * Xiaomi/Oppo/Vivo/Samsung tiết kiệm pin) có thể kill tiến trình; lúc quay lại, Android chỉ dựng
     * lại RemoteActivity (giao diện "y nguyên") chứ KHÔNG chạy CameraActivity nên không ai bật service
     * -> thanh trạng thái mãi "CHƯA KẾT NỐI". Trước đây phải vuốt bỏ app rồi mở lại từ icon mới bật được.
     */
    private fun ensureService(force: Boolean) {
        if (CameraStreamService.instance != null) return
        val prefs = getSharedPreferences(CameraActivity.PREFS_NAME, MODE_PRIVATE)
        if (!force && !prefs.getBoolean(CameraActivity.KEY_SESSION_ACTIVE, false)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnsureAt < 4000) return        // service cần ~1s để lên: đừng bật dồn dập
        lastEnsureAt = now
        prefs.edit().putBoolean(CameraActivity.KEY_SESSION_ACTIVE, true).apply()
        try {
            ContextCompat.startForegroundService(this, Intent(this, CameraStreamService::class.java)
                .putExtra(CameraStreamService.EXTRA_ROOM_CODE, CameraActivity.savedCode(this)))
        } catch (e: Exception) {
            Toast.makeText(this, "Không bật được dịch vụ kết nối: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pausedAt != 0L) ensureService(force = true)    // quay lại từ nền (lần mở đầu do CameraActivity đã bật service)
        // Quay lại sau > 20 giây: đường truyền có thể đã "chết ngầm" (WebRTC vẫn báo CONNECTED) -> nối lại tất cả.
        // Ngắn hơn: chỉ nối lại những kênh đang không sẵn sàng.
        val away = if (pausedAt == 0L) 0L else SystemClock.elapsedRealtime() - pausedAt
        CameraStreamService.instance?.reconnectNow(force = away > 20_000L)
        ui.post(attachLoop)
        RemoteState.addListener(modeListener)
    }

    override fun onPause() {
        pausedAt = SystemClock.elapsedRealtime()
        RemoteState.removeListener(modeListener)
        ui.removeCallbacks(attachLoop)
        attachedService?.removeConnectionStatusListener(this)
        attachedService = null
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        sender.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val LABEL_W = 84
        private const val MOVE_GAIN_SLOW = 1.1f      // di cham: chinh xac
        private const val MOVE_GAIN_FAST = 3.6f      // vuot nhanh: bay xa
        private const val ACC_V0 = 0.15f             // dp/ms bat dau tang toc
        private const val ACC_V1 = 1.6f              // dp/ms dat toc do toi da
        private const val FLING_MIN_V = 0.25f        // px/ms: cham hon thi khong troi
        private const val FLING_MAX_V = 6f
        private const val FLING_STOP_V = 0.03f
        private const val FLING_DECAY = 0.94f        // moi 16ms
        private const val SCROLL_GAIN = 1.0f
        private const val TAP_MS = 250L
        private const val BAR_HOLD_MS = 2000L
    }
}
