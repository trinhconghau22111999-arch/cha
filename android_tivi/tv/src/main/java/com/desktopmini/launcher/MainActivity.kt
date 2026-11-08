package com.desktopmini.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private const val REQ_FILE = 3001
private const val REQ_STORAGE = 3002
private const val REQ_VOICE = 3003
private const val REQ_VOICE_PERM = 3004
private const val REQ_ROLE = 3005

class MainActivity : Activity(), FullscreenHost {
    private lateinit var content: FrameLayout
    private lateinit var barView: LinearLayout
    private lateinit var floatBack: TextView
    private var barH = 0
    private lateinit var fullLayer: FrameLayout
    private lateinit var hidden: FrameLayout
    private lateinit var btnTv: TextView
    private lateinit var btnYt: TextView
    private lateinit var btnFiles: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var remote: RemoteController
    private var connWake: PowerManager.WakeLock? = null

    private var mode = 0                  // 0 = trang trống, 1 = TV, 2 = YouTube, 3 = Tệp
    private var tv: TvPane? = null
    private var yt: YouTubePane? = null
    private var files: FilesPane? = null
    private val barItems = ArrayList<TextView>()
    private var barFocus = -1                // ô chọn trên thanh dưới: 0 TV,1 YouTube,2 Cài đặt,3 Tệp,4 ←; -1 = đang điều khiển trang. Mở app: nằm sẵn ở YouTube
    private var fullView: View? = null
    private var fullCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 15000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.black)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        // Giu CPU/mang song khi man hinh bi tat tu xa de remote van ket noi va bat lai duoc
        try {
            connWake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tv:connection").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) { }
        buildUi()
        hideSystemBars()
        setMode(2)                      // mở app lên là vào thẳng YouTube
        if (intent?.getBooleanExtra(RemoteHub.EXTRA_HOME, false) == true) { setMode(0) }   // được gọi từ nút "Màn hình chính" khi app đã bị tắt

        refreshStatus()
        ui.postDelayed(tick, 15000)

        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)

        // Kênh nhận lệnh remote/truyền tệp sống theo tiến trình (không bị tắt theo giao diện); dịch vụ nền giữ nó khi tắt app
        RemoteHub.setActivity(this)
        RemoteKeepAliveService.start(this)
        ui.postDelayed({
            if (isFinishing) return@postDelayed
            val h = RemoteHub.ensureHost(applicationContext)
            h.onTextEdit = { del, ins -> applyTextEdit(del, ins) }
            h.pickFile = { cb, params -> openFileChooser(cb, params) }
            h.attach(hidden)
        }, 2500)
        ui.postDelayed({ nextAfterAdmin() }, 600)
        ui.postDelayed({ if (!isFinishing) maybeAskIgnoreBattery() }, 9000)
        ui.postDelayed({ if (!isFinishing) maybeAskOverlay() }, 16000)
    }

    // Xin bỏ giới hạn pin cho app (một lần) để hệ thống TV ít giết dịch vụ nhận lệnh chạy nền
    private fun maybeAskIgnoreBattery() {
        if (Build.VERSION.SDK_INT < 23) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("askedBatt", false)) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            sp.edit().putBoolean("askedBatt", true).apply()
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + packageName)))
        } catch (e: Exception) { }
    }

    // Android 10+: chỉ app có quyền "Hiển thị trên ứng dụng khác" mới tự mở lại được từ nền (nút Màn hình chính trên điện thoại khi TV đang ở app khác)
    private fun maybeAskOverlay() {
        if (Build.VERSION.SDK_INT < 23) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("askedOverlay", false)) return
        try {
            if (android.provider.Settings.canDrawOverlays(this)) return
            sp.edit().putBoolean("askedOverlay", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Cho phép tự mở lại app?")
                .setMessage("Để nút \"Màn hình chính\" trên điện thoại đưa TV về app này khi đang ở app khác, hãy bật quyền \"Hiển thị trên ứng dụng khác\" cho app.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:" + packageName))) } catch (e: Exception) { }
                }
                .setNegativeButton("Để sau", null)
                .show()
        } catch (e: Exception) { }
    }

    // Không xin quyền Quản trị thiết bị. Chuyển sang bước hỏi dịch vụ trợ năng.
    private var adminChainDone = false
    private fun nextAfterAdmin() {
        if (adminChainDone) return
        adminChainDone = true
        ui.postDelayed({ maybeAskAccessibility() }, 300)
    }

    // ---------------- Đặt làm giao diện (màn hình chính) mặc định ----------------

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun maybeAskDefaultHome() {
        if (isFinishing || isDefaultHome()) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("noAskHome", false)) return
        AlertDialog.Builder(this)
            .setTitle("Đặt TV làm giao diện mặc định?")
            .setMessage("Khi bấm nút Home, máy sẽ vào thẳng giao diện TV.")
            .setPositiveButton("Đặt mặc định") { _, _ -> requestDefaultHome() }
            .setNegativeButton("Để sau", null)
            .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskHome", true).apply() }
            .show()
    }

    private fun requestDefaultHome() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(Context.ROLE_SERVICE) as RoleManager
                if (rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                }
            }
            // Android cũ: mở lại màn hình chính để hệ thống hỏi chọn ứng dụng, chọn TV rồi bấm "Luôn luôn"
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(this, "Hãy vào Cài đặt > Ứng dụng > Ứng dụng mặc định > Màn hình chính", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Giao diện ----------------

    // Giao diện gaming: thanh dưới nền đen xanh đậm, viền neon; nút tối, nút đang mở sáng màu neon, ô chọn viền vàng
    private val BAR_BG = 0xFF0A0D16.toInt()
    private val BAR_BTN_BG = 0xFF151B2D.toInt()
    private val BAR_TEXT = 0xFFE3EAFF.toInt()
    private val NEON_CYAN = 0xFF00E5FF.toInt()
    private val NEON_MAGENTA = 0xFFFF2BD6.toInt()
    private val BTN_EDGE = 0xFF2A3556.toInt()
    private val FOCUS_RING = 0xFFFFEA00.toInt()

    private fun activeBtn(b: TextView, on: Boolean, onColor: Int) {
        val fill = if (on) onColor else BAR_BTN_BG
        val focused = barFocus >= 0 && barItems.indexOf(b) == barFocus
        b.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(fill)
            when {
                focused -> setStroke(dp(3), FOCUS_RING)                 // ô chọn (viền vàng)
                on -> setStroke(dp(2), 0xCCFFFFFF.toInt())              // nút đang mở: viền sáng
                else -> setStroke(dp(1), BTN_EDGE)
            }
        }
        b.setTextColor(if (on) Color.WHITE else BAR_TEXT)
    }

    private fun barBtn(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(BAR_TEXT)
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(BAR_BTN_BG)
        isAllCaps = true
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.06f
        isFocusable = false; isFocusableInTouchMode = false      // ô chọn do app tự vẽ (barFocus); để View nhận focus thì Enter bấm nhầm nút trên thanh
        minWidth = dp(48)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun small(t: String, sp: Float = 11f, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(BAR_TEXT); gravity = Gravity.CENTER_VERTICAL
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        barH = dp(40)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val strip = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(NEON_CYAN, 0xFF7C4DFF.toInt(), NEON_MAGENTA))
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(BAR_BG), strip)).also { it.setLayerInset(1, 0, 0, 0, barH - dp(2)) }
            setPadding(dp(4), dp(3), dp(6), dp(3))
        }
        btnTv = barBtn("TV").apply { setOnClickListener { onModeButton(1) } }
        btnYt = barBtn("YouTube").apply { setOnClickListener { onModeButton(2) } }
        val btnSettings = barBtn("Cài đặt").apply { setOnClickListener { openTvSettings() } }
        btnFiles = barBtn("Tệp").apply { setOnClickListener { onModeButton(3) } }
        val btnBack = barBtn("←").apply { textSize = 22f; contentDescription = "Thoát"; setOnClickListener { goBack() } }
        // 5 nút bằng nhau: TV | YouTube | Cài đặt | Tệp | ← (Thoát)
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(btnTv, btnYt, btnSettings, btnFiles, btnBack).forEachIndexed { i, b ->
            group.addView(b, LinearLayout.LayoutParams(0, -1, 1f).also { if (i > 0) it.leftMargin = dp(4) })
        }
        bar.addView(group, LinearLayout.LayoutParams(0, -1, 1f))
        barItems.clear(); barItems.addAll(listOf(btnTv, btnYt, btnSettings, btnFiles, btnBack))

        // Góc phải: giờ, ngày, âm lịch (cùng cỡ chữ 13sp, đậm), rồi nút X gỡ app
        val cluster = 13f
        tvTime = small("--:--", cluster, true).apply { setTextColor(NEON_CYAN); setPadding(dp(14), 0, dp(12), 0) }
        bar.addView(tvTime, LinearLayout.LayoutParams(-2, -1))
        tvDate = small("", cluster, true).apply { setTextColor(0xFFB0BEC5.toInt()); setPadding(0, 0, dp(12), 0) }
        bar.addView(tvDate, LinearLayout.LayoutParams(-2, -1))
        tvLunar = small("", cluster, true).apply { setTextColor(0xFFFFC400.toInt()) }
        bar.addView(tvLunar, LinearLayout.LayoutParams(-2, -1))
        val btnKill = barBtn("X").apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xFFD50000.toInt()); setStroke(dp(1), 0xFFFF8A80.toInt()) }
            setTextColor(Color.WHITE)
            setOnClickListener { confirmSelfRemove() }
        }
        bar.addView(btnKill, LinearLayout.LayoutParams(dp(44), -1).also { it.leftMargin = dp(10) })

        barView = bar
        content = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // Hình nền trang chủ (hiện khi chưa mở TV/YouTube); các khung TV/YouTube nằm phủ lên trên
        content.addView(android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.wallpaper)
        }, FrameLayout.LayoutParams(-1, -1))
        col.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(bar, LinearLayout.LayoutParams(-1, barH))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        fullLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK) }
        val lp = FrameLayout.LayoutParams(-1, -1); lp.bottomMargin = barH
        root.addView(fullLayer, lp)
        floatBack = TextView(this).apply {
            text = "←"; contentDescription = "Thoát"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xCC0A0D16.toInt()); setStroke(dp(2), NEON_CYAN) }; setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        root.addView(floatBack, FrameLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.topMargin = dp(8) })
        remote = RemoteController(this, root, { if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else null }, { remoteVoice(it) })
        remote.onNext = { mode == 1 && (tv?.nextChannel() == true) }
        remote.onBack = { goBack() }
        remote.onScroll = { d -> mode == 1 && tv?.scrollList(d) == true }
        remote.onKey = { k -> handleRemoteKey(k) }
        remote.onFullscreen = { if (fullView != null) hideFull() else remote.fullscreenVideo() }
        hidden = FrameLayout(this)
        root.addView(hidden, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
        updateButtons()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun updateButtons() {
        activeBtn(btnTv, mode == 1, 0xFF00ACC1.toInt())
        activeBtn(btnYt, mode == 2, 0xFFFF1744.toInt())
        activeBtn(btnFiles, mode == 3, 0xFF00B85C.toInt())
        for (b in barItems) if (b !== btnTv && b !== btnYt && b !== btnFiles) activeBtn(b, false, 0)
    }

    // ---------------- Phím ▲▼◀▶ OK từ remote: thanh dưới cùng / trang đang mở ----------------

    /** Thanh dưới có ô chọn: ◀▶ đi qua TV...← (dừng trước đồng hồ), OK bấm nút, ▲ lên trang đang mở. */
    private fun handleRemoteKey(k: String): Boolean {
        if (k == "bar") {                               // giữ ▼ trên remote: nhảy xuống thanh dưới
            if (barFocus < 0) barFocus = when (mode) { 1 -> 0; 2 -> 1; 3 -> 3; else -> 0 }
            armBarRelease(); updateButtons(); return true
        }
        if (barFocus < 0 && mode == 0) { barFocus = 1; updateButtons(); return true }   // trang trống: phím đầu tiên đưa ô chọn xuống thanh dưới
        if (barFocus >= 0) {
            armBarRelease()
            when (k) {
                "left" -> if (barFocus > 0) barFocus--
                "right" -> if (barFocus < barItems.lastIndex) barFocus++
                "up" -> barFocus = -1                   // rời thanh dưới: lên trang đang mở
                "down" -> { }
                "ok" -> {                               // bấm xong là rời thanh dưới: Enter/OK lần sau chọn ô trên trang, không bấm lại nút này
                    val b = barItems.getOrNull(barFocus)
                    ui.removeCallbacks(barRelease); barFocus = -1; updateButtons()
                    b?.performClick(); return true
                }
                else -> return false
            }
            updateButtons(); return true
        }
        return when (mode) { 1 -> tv?.onKey(k) == true; 2 -> yt?.onKey(k) == true; 3 -> files?.onKey(k) == true; else -> false }
    }

    // Ô chọn thanh dưới tự nhả sau 8 giây không bấm phím (mode 0 giữ nguyên) -> không bao giờ bị "kẹt" ở nút Cài đặt
    private val barRelease = Runnable { if (mode != 0 && barFocus >= 0) { barFocus = -1; updateButtons() } }
    private fun armBarRelease() { ui.removeCallbacks(barRelease); if (mode != 0) ui.postDelayed(barRelease, 8000) }

    // Chuột (move/click) và phím (▲▼◀▶ OK, kể cả bàn phím nối TV) KHÔNG dùng đồng thời: ai dùng trước thì loại kia tạm tắt
    // cho tới khi ngừng thao tác INPUT_LOCK_MS; sang loại mới thì dọn dấu vết của loại cũ (ẩn con trỏ / bỏ ô chọn thanh dưới).
    private var lastInputKind = 0                 // 0 chưa có, 1 phím, 2 chuột
    private var lastKeyAt = 0L
    private var lastPtrAt = 0L

    private fun allowKeyInput(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastInputKind == 2 && now - lastPtrAt < INPUT_LOCK_MS) return false
        if (lastInputKind != 1) {
            ui.post { if (::remote.isInitialized) remote.hideCursor(); RemoteAccessibilityService.instance?.hideCursor() }
            lastInputKind = 1
        }
        lastKeyAt = now
        return true
    }

    private fun allowPointerInput(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (lastInputKind == 1 && now - lastKeyAt < INPUT_LOCK_MS) return false
        if (lastInputKind != 2) {
            lastInputKind = 2
            if (barFocus >= 0 && mode != 0) { ui.removeCallbacks(barRelease); barFocus = -1; updateButtons() }
        }
        lastPtrAt = now
        return true
    }

    // Bàn phím / remote vật lý nối với TV: giữ ▼ đúng 2 giây = nhảy xuống thanh dưới (YouTube, TV đều dùng được)
    private var downHoldPending = false
    private var downHoldFired = false
    private val downHold = Runnable { downHoldPending = false; downHoldFired = true; handleRemoteKey("bar") }

    private fun keyName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"; KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "ok"
        else -> null
    }

    // Bàn phím / remote vật lý: đưa thẳng vào bộ xử lý ô chọn của app trước khi View nào đó (WebView, nút...) kịp nuốt Enter.
    // Phím giả lập trong app (deviceId ảo) đi đường cũ để không bị lặp.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD && keyName(event.keyCode) != null) {
            val handled = if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                          else if (event.action == KeyEvent.ACTION_UP) onKeyUp(event.keyCode, event) else false
            if (handled) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && barFocus < 0) {
            if (!allowKeyInput()) return true
            // Đang ở trang: ▼ nhấn nhanh = đi xuống như thường (xử lý lúc nhả phím); giữ 2 giây = xuống thanh dưới
            if (event?.repeatCount == 0) { downHoldFired = false; downHoldPending = true; ui.removeCallbacks(downHold); ui.postDelayed(downHold, 2000) }
            return true
        }
        val k = keyName(keyCode)
        if (k != null && !allowKeyInput()) return true       // đang dùng chuột: tạm bỏ phím
        if (k != null) {
            val inBarOrMenu = barFocus >= 0
            val isEnter = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            // Trang đang mở (YouTube / chọn đài-kênh TV): ▲ ◀ ▶ OK đi qua đúng bộ xử lý như remote điện thoại.
            // Các trang khác: Enter của bàn phím chữ giữ nguyên cho trang.
            if (isEnter && !inBarOrMenu && mode == 2) {        // YouTube: Enter = OK trên ô chọn, hoặc Enter trong ô gõ tìm kiếm
                if ((event?.repeatCount ?: 0) == 0) yt?.enterKey()
                return true
            }
            if (isEnter && !inBarOrMenu && mode == 3) {        // Tệp: Enter = mở / cài tệp đang chọn
                if ((event?.repeatCount ?: 0) == 0) handleRemoteKey("ok")
                return true
            }
            if (isEnter && !inBarOrMenu && mode == 1) {        // TV: Enter = OK trên ô đài/kênh đang chọn (đang xem kênh thì để trang tự xử lý)
                if ((event?.repeatCount ?: 0) == 0 && handleRemoteKey("ok")) return true
                if ((event?.repeatCount ?: 0) > 0) return true
            }
            if (inBarOrMenu || !isEnter) {
                if (k == "ok" && (event?.repeatCount ?: 0) > 0) return true      // giữ OK không bấm lặp
                if (handleRemoteKey(k)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && (downHoldPending || downHoldFired)) {
            val fired = downHoldFired
            downHoldFired = false
            if (downHoldPending) {
                ui.removeCallbacks(downHold); downHoldPending = false
                if (!fired && !handleRemoteKey("down")) super.onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------- Chế độ TV / YouTube ----------------

    private fun onModeButton(m: Int) {
        if (mode == m) {                      // bấm lại nút đang mở: về trang chủ của chế độ đó
            when (m) { 1 -> tv?.home(); 2 -> yt?.home(); 3 -> files?.home() }
            return
        }
        hideFull()
        setMode(m)
        // Chuyen qua lai TV <-> YouTube: khong giu dai/video cu, luon ve menu chon dai (TV) hoac trang chu (YouTube)
        when (m) { 1 -> tv?.home(); 2 -> yt?.home(); 3 -> files?.home() }
    }

    override fun setBarVisible(v: Boolean) {
        runOnUiThread {
            barView.visibility = if (v) View.VISIBLE else View.GONE
            floatBack.visibility = if (v) View.GONE else View.VISIBLE
            (fullLayer.layoutParams as FrameLayout.LayoutParams).bottomMargin = if (v) barH else 0
            fullLayer.requestLayout()
        }
    }

    private fun setMode(m: Int) {
        barFocus = -1; updateButtons()
        setBarVisible(true)
        tv?.let { content.removeView(it.view); it.onPause() }
        yt?.let { content.removeView(it.view); it.onPause() }
        files?.let { content.removeView(it.view); it.onPause() }
        mode = m
        when (m) {
            1 -> {
                val p = tv ?: TvPane(this, this).also { tv = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            2 -> {
                val p = yt ?: YouTubePane(this, this).also { yt = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            3 -> {
                val p = files ?: FilesPane(this) { RemoteHub.host?.recentFiles() ?: emptyList() }.also { fp ->
                    fp.onExitDown = { barFocus = 3; updateButtons() }      // ▼ ở dòng cuối: xuống thanh dưới
                    files = fp
                }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
        }
        updateButtons()
    }

    // Thoát: TV -> về thẳng menu chọn đài. YouTube -> lùi từng bước (thoát toàn màn hình trước), cuối cùng về trang chủ YouTube.
    private fun goBack() {
        when (mode) {
            1 -> { if (fullView != null) hideFull(); tv?.home() }
            2 -> { if (fullView != null) { hideFull(); return }; yt?.back() }
            3 -> setMode(2)                        // Tệp -> quay lại YouTube
        }
    }

    // Khoa cam ung: nuot moi cham tren man hinh TV. Chuot ao cua remote gui thang vao root nen van hoat dong.
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (::remote.isInitialized && remote.touchLocked) return true
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { goBack() }     // luôn ở lại màn hình chính, không thoát app

    // ---------------- Điều khiển app bên ngoài (qua dịch vụ Trợ năng) ----------------

    private var resumed = false
    private val INPUT_LOCK_MS = 700L

    /** App TV ở phía trước: RemoteController xử lý. App khác ở phía trước: chuyển cho dịch vụ Trợ năng + phím media hệ thống. */
    fun routeRemote(json: String) {
        when (try { JSONObject(json).optString("action") } catch (e: Exception) { "" }) {
            "move", "click" -> if (!allowPointerInput()) return      // đang dùng phím: tạm bỏ chuột
            "key" -> if (!allowKeyInput()) return                    // đang dùng chuột: tạm bỏ phím
        }
        val svc = RemoteAccessibilityService.instance
        if (try { JSONObject(json).optString("action") == "home" } catch (e: Exception) { false }) { remoteHome(); return }
        if (backBurst(json)) return
        if (resumed || isFinishing) { remote.handle(json); return }
        val action = try { JSONObject(json).optString("action") } catch (e: Exception) { "" }
        when (action) {
            "move", "click", "scroll", "key", "back" -> if (svc != null) svc.handle(json) else remote.handle(json)
            "media" -> systemMedia(try { JSONObject(json).optString("cmd") } catch (e: Exception) { "" })
            else -> remote.handle(json)       // volume, screen (tắt/bật màn hình)... tác động toàn hệ thống nên giữ nguyên
        }
    }

    // Lệnh "lùi" nhấn 3 lần liên tục (mỗi lần cách nhau không quá ~1 giây) -> về trang chủ của chính app này,
    // kể cả khi đang ở trong app bên ngoài. Hai lần lùi đầu vẫn được xử lý bình thường.
    private val backTimes = ArrayList<Long>()

    private fun backBurst(json: String): Boolean {
        val isBack = try { JSONObject(json).optString("action") == "back" } catch (e: Exception) { false }
        if (!isBack) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (backTimes.isNotEmpty() && now - backTimes.last() > 1000) backTimes.clear()
        backTimes.add(now)
        if (backTimes.size < 3) return false
        backTimes.clear()
        goAppHome()
        return true
    }

    /** Nút "Màn hình chính" trên remote điện thoại: đang ở TV / YouTube / app khác -> về màn hình chính của app;
     *  đã ở màn hình chính của app -> về màn hình chính của TV (launcher hệ thống). */
    private fun remoteHome() {
        val atAppHome = resumed && !isFinishing && mode == 0 && fullView == null
        if (atAppHome) goTvHome() else goAppHome()
    }

    /** Trang chủ của app: hình nền + thanh menu (không mở TV/YouTube); đưa app lên phía trước nếu đang ở app khác. */
    private fun goAppHome() {
        hideFull(); setMode(0)
        if (resumed) return
        RemoteHub.bringToFront(applicationContext)
    }

    private fun systemMedia(cmd: String) {
        val code = when (cmd) {
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "fwd" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> return
        }
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
    }

    private var accPendingHome = false

    private fun maybeAskAccessibility() {
        if (isFinishing) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (RemoteAccessibilityService.isEnabled(this) || sp.getBoolean("noAskAcc", false)) { maybeAskDefaultHome(); return }
        AlertDialog.Builder(this)
            .setTitle("Bật điều khiển ứng dụng bên ngoài?")
            .setMessage("Để điện thoại điều khiển (chuột ảo, chạm, vuốt, Back) cả những ứng dụng đang mở trên TV. " +
                "Bạn cần vào Cài đặt > Trợ năng, chọn \"TV_Youtube\" rồi bật lên.")
            .setPositiveButton("Mở cài đặt") { _, _ ->
                accPendingHome = true
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (e: Exception) { accPendingHome = false; maybeAskDefaultHome() }
            }
            .setNegativeButton("Để sau") { _, _ -> maybeAskDefaultHome() }
            .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskAcc", true).apply(); maybeAskDefaultHome() }
            .setOnCancelListener { maybeAskDefaultHome() }
            .show()
    }

    // ---------------- Cài đặt, về màn hình chính TV, tự gỡ ----------------

    private fun openTvSettings() {
        val tries = listOf(
            Intent(android.provider.Settings.ACTION_SETTINGS),
            Intent("android.settings.SETTINGS"))
        for (i in tries) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        Toast.makeText(this, "Không mở được Cài đặt của TV", Toast.LENGTH_LONG).show()
    }

    /** Về màn hình chính gốc của TV (launcher hệ thống), không phải chính app này. */
    private fun goTvHome() {
        try {
            val q = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .filter { it.activityInfo.packageName != packageName }
            val pick = q.firstOrNull { (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 } ?: q.firstOrNull()
            if (pick != null) {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        } catch (e: Exception) { }
        // Không có launcher nào khác: về trang chủ của chính app (hình nền, không mở TV/YouTube)
        hideFull(); setMode(0)
        Toast.makeText(this, "TV không có màn hình chính nào khác", Toast.LENGTH_SHORT).show()
    }

    private fun confirmSelfRemove() {
        AlertDialog.Builder(this)
            .setTitle("Gỡ ứng dụng này khỏi TV?")
            .setMessage("Sẽ bỏ thiết lập giao diện mặc định (nếu có) rồi gỡ cài đặt ứng dụng.")
            .setPositiveButton("Gỡ") { _, _ -> selfRemove() }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun selfRemove() {
        // 1) Bỏ thiết lập "giao diện mặc định" của app này (nếu có)
        try { packageManager.clearPackagePreferredActivities(packageName) } catch (e: Exception) { }
        // 2) Gỡ app
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)) }
            catch (e2: Exception) { Toast.makeText(this, "Hãy gỡ thủ công trong Cài đặt > Ứng dụng", Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------- Video toàn màn hình ----------------

    override fun showFull(v: View, cb: WebChromeClient.CustomViewCallback?) {
        if (fullView != null) { cb?.onCustomViewHidden(); return }
        fullView = v; fullCb = cb
        v.setBackgroundColor(Color.BLACK)
        fullLayer.addView(v, FrameLayout.LayoutParams(-1, -1))
        fullLayer.visibility = View.VISIBLE
    }

    override fun hideFull() {
        val v = fullView ?: return
        fullLayer.removeView(v)
        fullLayer.visibility = View.GONE
        fullView = null
        fullCb?.onCustomViewHidden(); fullCb = null
    }

    // ---------------- Giọng nói (YouTube) ----------------

    override fun launchVoice() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM); return
        }
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().let { if (it.country.isEmpty()) it.language else it.language + "-" + it.country })
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube")
        }
        try { startActivityForResult(it, REQ_VOICE) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Truyền tệp (ngầm) ----------------

    private fun openFileChooser(cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
        fileCb?.onReceiveValue(null)
        fileCb = cb
        return try { startActivityForResult(params!!.createIntent(), REQ_FILE); true }
        catch (e: Exception) { fileCb?.onReceiveValue(null); fileCb = null; false }
    }

    // Remote giong noi, tuy che do dang chon san tren TV:
    //  - dang o TV (mode 1): noi ten kenh/dai -> mo kenh do
    //  - con lai (YouTube hoac trang trong): noi ten video -> tim tren YouTube va tu mo video dau tien
    // Chuẩn hoá câu nói: bỏ dấu, chữ thường, bỏ dấu câu (để so khớp lệnh "mở TV" / "mở YouTube").
    private fun normVoice(q: String): String =
        java.text.Normalizer.normalize(q.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val VOICE_OPEN = "(?:mo|bat|vao|open|chuyen sang|chuyen qua|di toi|quay ve)"
    private val VOICE_TV = Regex("^$VOICE_OPEN (?:ra |lai )?(?:trang chon kenh |trang chu |man hinh )?(?:tv|ti vi|tivi|truyen hinh|kenh tv)(?: len| di| giup| cho toi)?$")
    private val VOICE_YT = Regex("^$VOICE_OPEN (?:ra |lai )?(?:trang chu )?(?:youtube|you tube|du tup|diu tup|yu tup|iu tup)(?: len| di| giup| cho toi)?$")

    // "mở TV" -> sang TV và hiện trang chọn kênh (đang ở YouTube cũng được); "mở YouTube" -> sang YouTube, vào trang chủ.
    // Trả về true nếu câu nói là lệnh mở và đã xử lý xong.
    private fun voiceOpenCommand(q: String): Boolean {
        val n = normVoice(q)
        when {
            VOICE_TV.matches(n) -> { if (mode != 1) { hideFull(); setMode(1) }; tv?.home() }
            VOICE_YT.matches(n) -> { if (mode != 2) { hideFull(); setMode(2) }; yt?.home() }
            else -> return false
        }
        return true
    }

    // Câu ghép "mở YouTube mở bài hát X" / "mở TV kênh HTV9": chuyển app rồi làm luôn phần sau, chỉ cần nói một lần.
    // Trả về (chế độ 1 = TV, 2 = YouTube) và phần nội dung (giữ dấu); null nếu không phải câu ghép.
    private val VOICE_PREFIX = setOf("mo", "bat", "vao", "open", "chuyen", "sang", "qua", "di", "toi", "quay", "ve", "ra", "lai",
        "trang", "chu", "man", "hinh", "chon", "kenh")
    private val VOICE_YT_KEYS = listOf(listOf("youtube"), listOf("you", "tube"), listOf("du", "tup"), listOf("diu", "tup"),
        listOf("yu", "tup"), listOf("iu", "tup"))
    private val VOICE_TV_KEYS = listOf(listOf("tv"), listOf("tivi"), listOf("ti", "vi"), listOf("truyen", "hinh"))
    private val VOICE_YT_FILL = setOf("va", "roi", "mo", "phat", "nghe", "tim", "kiem", "xem", "bai", "hat", "nhac", "video", "clip",
        "cua", "ten", "cho", "toi", "len", "tren", "di")
    private val VOICE_TV_FILL = setOf("va", "roi", "mo", "bat", "xem", "kenh", "dai", "truyen", "hinh", "tren", "cho", "toi", "len", "di", "nhe")

    private fun voiceSwitchAndQuery(q: String): Pair<Int, String>? {
        val words = ArrayList<String>(); val nw = ArrayList<String>()
        for (w in q.trim().split(Regex("\\s+"))) {
            val k = normVoice(w).replace(" ", "")
            if (k.isNotEmpty()) { words.add(w.trim(',', '.', '!', '?', ';', '"', '\'')); nw.add(k) }
        }
        fun find(keys: List<List<String>>): Pair<Int, Int>? {
            for (i in nw.indices) for (k in keys) {
                if (i + k.size <= nw.size && k.indices.all { nw[i + it] == k[it] }) return i to k.size
            }
            return null
        }
        val yt = find(VOICE_YT_KEYS); val tvk = find(VOICE_TV_KEYS)
        // Lấy từ khóa xuất hiện sớm hơn (đó là app được yêu cầu chuyển tới)
        val (mode2, hit) = when {
            yt != null && (tvk == null || yt.first <= tvk.first) -> 2 to yt
            tvk != null -> 1 to tvk
            else -> return null
        }
        if (!nw.subList(0, hit.first).all { it in VOICE_PREFIX }) return null
        var a = hit.first + hit.second
        val fill = if (mode2 == 2) VOICE_YT_FILL else VOICE_TV_FILL
        while (a < nw.size && nw[a] in fill) a++
        if (a >= nw.size) return null      // chỉ có lệnh mở, không kèm nội dung
        return mode2 to words.subList(a, words.size).joinToString(" ")
    }

    private fun remoteVoice(q: String) {
        // Câu ghép: chuyển app + nói luôn tên bài / kênh trong một lần
        voiceSwitchAndQuery(q)?.let { (m, rest) ->
            hideFull()
            if (m == 1) {
                if (mode != 1) setMode(1)
                tv?.home()
                if (tv?.playByName(rest) != true) Toast.makeText(this, "Không tìm thấy kênh: $rest", Toast.LENGTH_LONG).show()
            } else {
                if (mode != 2) setMode(2)
                yt?.search(rest)
            }
            return
        }
        if (voiceOpenCommand(q)) return
        if (mode == 1) {
            val ok = tv?.playByName(q) ?: false
            if (!ok) Toast.makeText(this, "Không tìm thấy kênh: $q", Toast.LENGTH_LONG).show()
            return
        }
        if (mode != 2) { hideFull(); setMode(2) }
        yt?.search(q)
    }

    // Điện thoại gửi {del, ins}: xoá `del` ký tự trước con trỏ rồi chèn `ins` vào ô nhập đang chọn của TV/YouTube.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv: WebView = (if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else null) ?: return
        val del = delRaw.coerceIn(0, 1000000)
        if (del == 0 && ins.isEmpty()) return
        if (ins == "\n" && del == 0) {
            wv.requestFocus()
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }
        wv.evaluateJavascript("(" + TEXT_EDIT_FN + ")(" + del + "," + JSONObject.quote(ins) + ");", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_FILE -> { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null }
            REQ_VOICE -> if (resultCode == RESULT_OK) {
                val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!q.isNullOrBlank()) { if (mode != 2) setMode(2); yt?.search(q) }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoice()
    }

    // ---------------- Thanh trạng thái ----------------

    private fun refreshStatus() {
        val cal = Calendar.getInstance()
        tvTime.text = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val dow = when (cal.get(Calendar.DAY_OF_WEEK)) { Calendar.SUNDAY -> "CN"; else -> "T" + cal.get(Calendar.DAY_OF_WEEK) }
        val d = cal.get(Calendar.DAY_OF_MONTH); val m = cal.get(Calendar.MONTH) + 1; val y = cal.get(Calendar.YEAR)
        tvDate.text = "$dow $d/$m/$y"
        val l = LunarCalendar.solarToLunar(d, m, y)
        tvLunar.text = "ÂL " + l[0] + "/" + l[1] + (if (l[3] == 1) " nhuận" else "")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(RemoteHub.EXTRA_HOME, false) == true) { hideFull(); setMode(0) }
    }

    // Giao diện xuống nền: gỡ WebView nhận lệnh khỏi cửa sổ (WebView nằm trong cửa sổ ẩn bị hệ thống đóng băng -> điện thoại hết điều khiển được)
    /** Vừa nhận tệp .apk: mở màn "Tệp" và chọn sẵn tệp đó để bấm OK là cài. */
    fun consumePendingFile() {
        val n = RemoteHub.takePendingFile() ?: return
        hideFull()
        setMode(3)
        files?.highlight(n)
    }

    override fun onStart() { super.onStart(); RemoteHub.host?.attach(hidden) }
    override fun onStop() { super.onStop(); RemoteHub.host?.detach() }

    override fun onResume() { super.onResume(); resumed = true; RemoteHub.appVisible = true; RemoteAccessibilityService.instance?.hideCursor(); if (accPendingHome) { accPendingHome = false; ui.postDelayed({ maybeAskDefaultHome() }, 500) }; hideSystemBars(); refreshStatus(); if (mode == 1) tv?.onResume() else if (mode == 2) yt?.onResume() else if (mode == 3) files?.onResume(); consumePendingFile() }
    override fun onPause() { super.onPause(); resumed = false; RemoteHub.appVisible = false; tv?.onPause(); yt?.onPause(); files?.onPause() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        RemoteHub.setActivity(null)
        RemoteHub.host?.let { it.onTextEdit = { _, _ -> }; it.pickFile = { cb, _ -> cb?.onReceiveValue(null); true }; it.detach() }
        try { connWake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        tv?.destroy(); yt?.destroy(); files?.destroy()
        super.onDestroy()
    }
}
