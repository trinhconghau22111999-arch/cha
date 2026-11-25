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
import android.graphics.Rect
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
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.desktopmini.launcher.aikara.AiKaraPane
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
    private var kb: MiniKeyboard? = null
    private lateinit var btnKb: TextView
    private lateinit var rootView: FrameLayout           // toàn bộ giao diện (trên + dưới), cùng nổi trong cửa sổ trợ năng khi dịch vụ bật
    private var uiParent: android.view.ViewGroup? = null // chỗ cũ của rootView trong cửa sổ Activity
    private var rootInOverlay = false
    private var noOverlayUntil = 0L
    private lateinit var fullLayer: FrameLayout
    private lateinit var hidden: FrameLayout
    private lateinit var btnMenu: TextView
    private lateinit var btnAi: TextView
    private lateinit var menuLayer: FrameLayout
    private lateinit var btnTv: TextView
    private lateinit var btnBrowser: TextView
    private lateinit var btnNotes: TextView
    private lateinit var btnYt: TextView
    private lateinit var btnFiles: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvBatt: TextView
    private var battReceiver: BroadcastReceiver? = null
    private lateinit var tvDate: TextView
    private lateinit var tvLunar: TextView
    private lateinit var remote: RemoteController
    private var connWake: PowerManager.WakeLock? = null

    private var mode = 0                  // 0 = trang trống, 1 = TV, 2 = YouTube, 3 = Tệp, 4 = Ái Kara, 5 = Trình duyệt, 6 = Ghi chú
    private var tv: TvPane? = null
    private var yt: YouTubePane? = null
    private var files: FilesPane? = null
    private var aikara: AiKaraPane? = null
    private var notes: NotesPane? = null      // Ghi chú (mode 6), tạo khi mở lần đầu
    private var browser: BrowserHost? = null   // Trình duyệt (mode 5), tạo khi mở lần đầu
    private val barItems = ArrayList<TextView>()
    private var btnKillRef: TextView? = null     // nút X: nằm trong barItems (giữa ⌨ và OFF) để remote chọn được
    private var barFocus = -1                // ô chọn trên thanh dưới (theo barItems): 0 Menu,1 TV,2 YouTube,3 Trình duyệt,4 Ghi chú,5 Ái Kara,6 Cài đặt,7 Tệp,8 Home,9 Cập nhật,10 ←; -1 = đang điều khiển trang. Mở app: nằm sẵn ở YouTube
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
        RemoteAccessibilityService.onConnected = { ui.post { attachUiOverlay() } }
        applyBarVisibility()
        hideSystemBars()
        setMode(2)                      // mở app lên là vào thẳng YouTube
        if (getSharedPreferences("tv", MODE_PRIVATE).getBoolean("kbOn", false)) rootView.post { showKeyboard() }
        if (intent?.getBooleanExtra(RemoteHub.EXTRA_HOME, false) == true) { hideMenu(); setMode(0) }   // được gọi từ nút "Màn hình chính" khi app đã bị tắt

        refreshStatus()
        ui.postDelayed(tick, 15000)
        battReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { refreshBattery() } }
        try { registerReceiver(battReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { battReceiver = null }

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
        ui.postDelayed({ if (!isFinishing) maybeAskInstallUnknown() }, 3000)
        ui.postDelayed({ if (!isFinishing) maybeAskIgnoreBattery() }, 9000)
        ui.postDelayed({ if (!isFinishing) maybeAskOverlay() }, 16000)
        ui.postDelayed({ if (!isFinishing) maybeAskWriteSettings() }, 24000)
    }

    // Android 8+: sau khi cài xong, hỏi bật "Cho phép cài ứng dụng từ nguồn không xác định" cho chính app này
    // (hỏi mỗi lần mở app cho tới khi được bật hoặc bấm "Không hỏi lại")
    private var askedInstallThisRun = false
    private fun maybeAskInstallUnknown() {
        if (Build.VERSION.SDK_INT < 26 || askedInstallThisRun) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("noAskInstall", false)) return
        try {
            if (packageManager.canRequestPackageInstalls()) return
            askedInstallThisRun = true
            AlertDialog.Builder(this)
                .setTitle("Cho phép cài ứng dụng từ nguồn không xác định?")
                .setMessage("Để cài tệp APK (cập nhật app, cài app từ tệp nhận được), hãy bật \"Cho phép từ nguồn này\" cho app.")
                .setPositiveButton("Cho phép") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                    } catch (e: Exception) { }
                }
                .setNegativeButton("Để sau", null)
                .setNeutralButton("Không hỏi lại") { _, _ -> sp.edit().putBoolean("noAskInstall", true).apply() }
                .show()
        } catch (e: Exception) { }
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
    private fun maybeAskWriteSettings() {
        if (Build.VERSION.SDK_INT < 23) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        if (sp.getBoolean("askedWriteSettings", false)) return
        try {
            if (android.provider.Settings.System.canWrite(this)) return
            sp.edit().putBoolean("askedWriteSettings", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Cho phép chỉnh độ sáng?")
                .setMessage("Để thanh \"Độ sáng\" trên điện thoại điều khiển đổi độ sáng thật của TV (cả khi đang ở app khác), hãy bật quyền \"Sửa đổi cài đặt hệ thống\" cho app.")
                .setPositiveButton("Mở cài đặt") { _, _ ->
                    try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:" + packageName))) } catch (e: Exception) { }
                }
                .setNegativeButton("Để sau", null)
                .show()
        } catch (e: Exception) { }
    }

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
        val isOff = b.tag == "off"
        val isKill = b === btnKillRef
        val isKb = b === btnKb                     // nút ⌨ bàn phím: nền xanh lá (sáng vừa)
        val fill = if (isKill) 0xFFFFC107.toInt() else if (isOff) 0xFFD50000.toInt() else if (isKb) 0xFF2E9E5B.toInt() else if (on) onColor else BAR_BTN_BG
        val focused = barFocus >= 0 && barItems.indexOf(b) == barFocus
        b.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(fill)
            when {
                focused -> setStroke(dp(3), FOCUS_RING)                 // ô chọn (viền vàng)
                on -> setStroke(dp(2), 0xCCFFFFFF.toInt())              // nút đang mở: viền sáng
                isKill -> setStroke(dp(1), 0xFFFFD54F.toInt())
                isOff -> setStroke(dp(1), 0xFFFF8A80.toInt())
                else -> setStroke(dp(1), BTN_EDGE)
            }
        }
        b.setTextColor(if (isKill) Color.WHITE else if (on || isOff || isKb) Color.WHITE else BAR_TEXT)
    }

    private fun barBtn(text: String, arrow: ArrowLeftDrawable? = null): TextView = (if (arrow != null) ArrowButton(this, arrow) else TextView(this)).apply {
        this.text = text
        setTextColor(BAR_TEXT)
        textSize = 12f
        maxLines = 1
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setBackgroundColor(BAR_BTN_BG)
        isAllCaps = true
        if (Build.VERSION.SDK_INT >= 21) letterSpacing = 0.06f
        isFocusable = false; isFocusableInTouchMode = false      // ô chọn do app tự vẽ (barFocus); để View nhận focus thì Enter bấm nhầm nút trên thanh
        minWidth = dp(40)
        setPadding(dp(4), 0, dp(4), 0)
    }

    // Rung nhẹ một cái (nút Cập nhật và nút mũi tên Thoát)
    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(40)
        } catch (_: Exception) {}
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
                android.graphics.drawable.ColorDrawable(BAR_BG), strip)).also { it.setLayerInset(1, 0, 0, 0, barH - maxOf(1, (dp(2) * 0.4f).toInt())) }
            setPadding(dp(4), dp(3), dp(6), dp(3))
        }
        btnMenu = barBtn("Menu").apply { setOnClickListener { toggleMenu() } }
        btnTv = barBtn("TV").apply { setOnClickListener { hideMenu(); onModeButton(1) } }
        btnBrowser = barBtn("Trình duyệt").apply { setOnClickListener { hideMenu(); onModeButton(5) } }
        btnNotes = barBtn("Ghi chú").apply { setOnClickListener { hideMenu(); onModeButton(6) } }
        btnYt = barBtn("YouTube").apply { setOnClickListener { hideMenu(); onModeButton(2) } }
        btnAi = barBtn("Ái Kara").apply { setOnClickListener { hideMenu(); onModeButton(4) } }
        val btnSettings = barBtn("Cài đặt").apply { setOnClickListener { hideMenu(); openTvSettings() } }
        btnFiles = barBtn("Tệp").apply { setOnClickListener { hideMenu(); onModeButton(3) } }
        val btnHome = barBtn("Home").apply { setOnClickListener { hideMenu(); goTvHome() } }
        val btnUpdate = barBtn("Cập nhật").apply { setOnClickListener { buzz(); hideMenu(); Updater.start(this@MainActivity) } }
        // OFF: nhấp 1 lần = tắt hình TV (khóa màn hình), tắt ngay
        val btnOff = barBtn("OFF").apply {
            tag = "off"
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xFFD50000.toInt()); setStroke(dp(1), 0xFFFF8A80.toInt()) }
            setTextColor(Color.WHITE)
            setOnClickListener {
                hideMenu()
                if (!ScreenAdmin.lock(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, "Cần bật Quản trị thiết bị cho app TV để tắt màn hình", Toast.LENGTH_LONG).show()
                    ScreenAdmin.requestActivation(this@MainActivity)
                }
            }
            // Nhấn giữ 1 giây: mở bảng Tắt nguồn của hệ thống (nhấn thường vẫn tắt màn hình như cũ)
            var held = false
            val fire = Runnable { held = true; openSystemPowerMenu() }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { held = false; ui.removeCallbacks(fire); ui.postDelayed(fire, 1000); false }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(fire); val h = held; held = false; h }
                    else -> held
                }
            }
        }
        val btnBack = barBtn("", ArrowLeftDrawable(dp(22), BAR_TEXT, dp(3).toFloat())).apply {
            contentDescription = "Thoát"; setOnClickListener { buzz(); hideMenu(); goBack() }
        }
        // 10 nút bằng nhau: Menu | TV | YouTube | Trình duyệt | Ghi chú | Ái Kara | Cài đặt | Tệp | Home | Cập nhật | ← (Thoát); OFF nằm cuối hàng, bên phải nút X
        btnKb = barBtn("⌨").apply { minWidth = 0; setBackgroundColor(0xFF2E9E5B.toInt()); setTextColor(Color.WHITE); contentDescription = "Bàn phím"; setOnClickListener { hideMenu(); toggleKeyboard() } }
        val group = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(btnMenu, btnTv, btnYt, btnBrowser, btnNotes, btnAi, btnSettings, btnFiles, btnHome, btnUpdate, btnBack).forEachIndexed { i, b ->
            group.addView(b, LinearLayout.LayoutParams(0, -1, 1f).also { if (i > 0) it.leftMargin = dp(4) })
        }
        bar.addView(group, LinearLayout.LayoutParams(0, -1, 1f))
        barItems.clear(); barItems.addAll(listOf(btnMenu, btnTv, btnYt, btnBrowser, btnNotes, btnAi, btnSettings, btnFiles, btnHome, btnUpdate, btnBack, btnKb, btnOff))

        // Góc phải: giờ, ngày, âm lịch (cùng cỡ chữ 13sp, đậm), rồi nút X gỡ app
        val cluster = 13f
        // Pin nằm bên trái giờ: "Pin 25%"; đang sạc thì "10W Pin 25%"
        tvBatt = small("", cluster, true).apply { setTextColor(NEON_CYAN); setPadding(dp(14), 0, 0, 0) }
        bar.addView(tvBatt, LinearLayout.LayoutParams(-2, -1))
        tvTime = small("--:--", cluster, true).apply { setTextColor(NEON_CYAN); setPadding(dp(14), 0, dp(12), 0) }
        bar.addView(tvTime, LinearLayout.LayoutParams(-2, -1))
        tvDate = small("", cluster, true).apply { setTextColor(NEON_CYAN); setPadding(0, 0, dp(12), 0) }
        bar.addView(tvDate, LinearLayout.LayoutParams(-2, -1))
        tvLunar = small("", cluster, true).apply { setTextColor(NEON_CYAN) }
        bar.addView(tvLunar, LinearLayout.LayoutParams(-2, -1))
        val btnKill = barBtn("X").apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xFFFFC107.toInt()); setStroke(dp(1), 0xFFFFD54F.toInt()) }
            setTextColor(Color.WHITE)
            setOnClickListener { hideMenu(); confirmSelfRemove() }
            // Nhấn giữ 1 giây: bảng Ẩn/hiện nút (nhấn thường vẫn hỏi gỡ app như cũ)
            var held = false
            val fire = Runnable { held = true; hideMenu(); showBarToggleDialog() }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { held = false; ui.removeCallbacks(fire); ui.postDelayed(fire, 1000); false }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(fire); val h = held; held = false; h }
                    else -> held
                }
            }
        }
        bar.addView(btnKb, LinearLayout.LayoutParams(dp(44), -1).also { it.leftMargin = dp(10) })
        bar.addView(btnKill, LinearLayout.LayoutParams(dp(44), -1).also { it.leftMargin = dp(4) })
        btnKillRef = btnKill; barItems.add(barItems.indexOf(btnOff), btnKill)      // remote ◀▶ chọn được nút X, OK = bấm
        // OFF: cuối cùng bên phải nút X, bề ngang = 60% một nút thường (theo bề ngang nút Menu sau khi bố cục xong)
        btnOff.minWidth = 0
        btnOff.ellipsize = null
        // Bề ngang tối thiểu đủ chứa trọn chữ "OFF" (đo theo chính font/cỡ chữ + giãn chữ + padding) để không bao giờ bị cắt còn "OF"
        val offNeed = {
            val ls = if (Build.VERSION.SDK_INT >= 21) btnOff.letterSpacing * btnOff.textSize * 3 else 0f
            (btnOff.paint.measureText("OFF") + ls + btnOff.paddingLeft + btnOff.paddingRight).toInt() + dp(4)
        }
        // OFF: bề ngang bằng nút X (dp(44)); chỉ rộng hơn nếu chữ "OFF" không vừa
        bar.addView(btnOff, LinearLayout.LayoutParams(maxOf(dp(44), offNeed()), -1).also { it.leftMargin = dp(4) })

        barView = bar
        if (Build.VERSION.SDK_INT >= 29) {
            // Chặn cử chỉ hệ thống (vuốt từ mép dưới lên) ngay trên vùng thanh dưới, để thanh đa nhiệm không đè lên
            bar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
            }
        }
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
        menuLayer = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xF0101018.toInt()); isClickable = true }
        val mlp = FrameLayout.LayoutParams(-1, -1); mlp.bottomMargin = barH
        root.addView(menuLayer, mlp)
        floatBack = TextView(this).apply {
            text = ""; contentDescription = "Thoát"; gravity = Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(ArrowLeftDrawable(dp(26), Color.WHITE, dp(4).toFloat()), null, null, null)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xCC0A0D16.toInt()); setStroke(dp(2), NEON_CYAN) }; setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            setOnClickListener { goBack() }
        }
        root.addView(floatBack, FrameLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.topMargin = dp(8) })
        remote = RemoteController(this, root, { if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else if (mode == 4) aikara?.web else if (mode == 5) browser?.currentWebView else if (mode == 6) notes?.web else null }, { remoteVoice(it) })
        remote.onNext = { mode == 1 && (tv?.nextChannel() == true) }
        remote.onBack = { goBack() }
        remote.onScroll = { d -> mode == 1 && tv?.scrollList(d) == true }
        remote.onKey = { k -> handleRemoteKey(k) }
        remote.onFullscreen = { if (fullView != null) hideFull() else remote.fullscreenVideo() }
        hidden = FrameLayout(this)
        root.addView(hidden, FrameLayout.LayoutParams(1, 1))
        rootView = root
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            // Thanh điều hướng/trạng thái vừa lộ ra (vuốt mép): ẩn lại ngay
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                if (insets.isVisible(WindowInsets.Type.navigationBars()) || insets.isVisible(WindowInsets.Type.statusBars())) {
                    v.post { hideSystemBars() }
                }
                insets
            }
        } else {
            // Android 10 trở xuống: cờ toàn màn hình bị hệ thống gỡ khi vuốt mép -> ẩn lại ngay
            window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
                if ((vis and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0 || (vis and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    window.decorView.post { hideSystemBars() }
                }
            }
        }
        updateButtons()
    }

    // ===== Cả giao diện (phần trên + thanh dưới) nổi chung 1 thể trong cửa sổ trợ năng, phủ lên cả thanh trạng thái lẫn thanh điều hướng =====
    // => không vuốt xuống kéo thông báo được. Dịch vụ trợ năng chưa bật / lỗi: giao diện vẫn nằm trong app như cũ.
    // Có hộp thoại/cửa sổ hệ thống hiện lên (Activity mất focus) thì giao diện tạm về lại trong app để hộp thoại không bị che.
    fun attachUiOverlay() {
        if (rootInOverlay || Build.VERSION.SDK_INT < 22 || !resumed || isFinishing || !hasWindowFocus()) return
        if (android.os.SystemClock.uptimeMillis() < noOverlayUntil) return      // đang mở bảng tắt nguồn của hệ thống: chưa phủ lại
        val svc = RemoteAccessibilityService.instance ?: return
        val parent = rootView.parent as? android.view.ViewGroup ?: return
        try {
            uiParent = parent
            parent.removeView(rootView)
            if (svc.showUi(rootView)) {
                rootInOverlay = true
                svc.onUiLost = { runOnUiThread { detachUiOverlay() } }
            } else restoreRoot()
        } catch (e: Exception) {
            try { restoreRoot() } catch (e2: Exception) { }
        }
    }

    fun detachUiOverlay() {
        if (!rootInOverlay) return
        rootInOverlay = false
        RemoteAccessibilityService.instance?.let { it.onUiLost = null; it.hideUi() }
        restoreRoot()
    }

    private fun restoreRoot() {
        rootInOverlay = false
        (rootView.parent as? android.view.ViewGroup)?.removeView(rootView)
        val p = uiParent ?: findViewById<android.view.ViewGroup>(android.R.id.content)
        p.addView(rootView, FrameLayout.LayoutParams(-1, -1))
        hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+: cờ cũ đôi khi bị bỏ qua -> dùng WindowInsetsController; vuốt từ mép chỉ hiện thanh tạm thời
            window.insetsController?.let { c ->
                c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) RemoteAccessibilityService.instance?.hideCursor()     // hộp thoại đã đóng: bỏ con trỏ phủ, dùng lại con trỏ của app
        if (hasFocus) { hideSystemBars(); attachUiOverlay() } else detachUiOverlay()
    }

    private fun updateButtons() {
        activeBtn(btnMenu, menuOpen(), 0xFF3A7BFF.toInt())
        activeBtn(btnTv, mode == 1, 0xFF00ACC1.toInt())
        activeBtn(btnYt, mode == 2, 0xFFFF1744.toInt())
        activeBtn(btnAi, mode == 4, 0xFF8E44AD.toInt())
        activeBtn(btnFiles, mode == 3, 0xFF00B85C.toInt())
        activeBtn(btnBrowser, mode == 5, 0xFF1E88E5.toInt())
        activeBtn(btnNotes, mode == 6, 0xFFFFA000.toInt())
        for (b in barItems) if (b !== btnMenu && b !== btnTv && b !== btnYt && b !== btnAi && b !== btnFiles && b !== btnBrowser && b !== btnNotes) activeBtn(b, false, 0)
    }

    // ---------------- Phím ▲▼◀▶ OK từ remote: thanh dưới cùng / trang đang mở ----------------

    /** Thanh dưới có ô chọn: ◀▶ đi qua TV...← (dừng trước đồng hồ), OK bấm nút, ▲ lên trang đang mở. */
    private fun handleRemoteKey(k: String): Boolean {
        if (k == "bar") {                               // giữ ▼ trên remote: nhảy xuống thanh dưới
            if (barFocus < 0) barFocus = if (menuOpen()) 0 else barIndexForMode(mode)
            armBarRelease(); updateButtons(); return true
        }
        if (barFocus < 0 && mode == 0 && !menuOpen()) { barFocus = 2; updateButtons(); return true }   // trang trống: phím đầu tiên đưa ô chọn xuống thanh dưới
        if (barFocus >= 0) {
            armBarRelease()
            if (barItems.getOrNull(barFocus)?.visibility == View.GONE) {     // ô chọn đang trỏ vào nút đã ẩn: dời sang nút hiện gần nhất
                val v = barItems.indices.filter { barItems[it].visibility == View.VISIBLE }
                barFocus = v.firstOrNull { it > barFocus } ?: v.lastOrNull() ?: -1
                if (barFocus < 0) { updateButtons(); return true }
            }
            when (k) {
                "left" -> { var j = barFocus - 1; while (j >= 0 && barItems[j].visibility != View.VISIBLE) j--; if (j >= 0) barFocus = j }
                "right" -> { var j = barFocus + 1; while (j <= barItems.lastIndex && barItems[j].visibility != View.VISIBLE) j++; if (j <= barItems.lastIndex) barFocus = j }
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
        if (menuOpen()) return menuKey(k)
        return when (mode) { 1 -> tv?.onKey(k) == true; 2 -> yt?.onKey(k) == true; 3 -> files?.onKey(k) == true; else -> false }
    }

    private fun menuOpen() = ::menuLayer.isInitialized && menuLayer.visibility == View.VISIBLE
    /** Ô trên thanh dưới tương ứng với chế độ đang mở. */
    private fun barIndexForMode(m: Int) = when (m) { 1 -> 1; 2 -> 2; 5 -> 3; 6 -> 4; 4 -> 5; 3 -> 7; else -> 0 }

    // Ô chọn thanh dưới tự nhả sau 8 giây không bấm phím (mode 0 giữ nguyên) -> không bao giờ bị "kẹt" ở nút Cài đặt
    private val barRelease = Runnable { if (mode != 0 && barFocus >= 0) { barFocus = -1; updateButtons() } }
    private fun armBarRelease() { ui.removeCallbacks(barRelease); if (mode != 0) ui.postDelayed(barRelease, 8000) }

    // Chuột (move/click) và phím (▲▼◀▶ OK, kể cả bàn phím nối TV) KHÔNG dùng đồng thời: ai dùng trước thì loại kia tạm tắt
    // cho tới khi ngừng thao tác INPUT_LOCK_MS; sang loại mới thì dọn dấu vết của loại cũ (ẩn con trỏ / bỏ ô chọn thanh dưới).
    private var lastInputKind = 0                 // 0 chưa có, 1 phím, 2 chuột
    private var lastKeyAt = 0L
    private var lastPtrAt = 0L

    private fun allowKeyInput(force: Boolean = false): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && lastInputKind == 2 && now - lastPtrAt < INPUT_LOCK_MS) return false
        if (lastInputKind != 1) {
            ui.post { if (::remote.isInitialized) remote.hideCursor(); RemoteAccessibilityService.instance?.hideCursor() }
            lastInputKind = 1
        }
        lastKeyAt = now
        return true
    }

    private fun allowPointerInput(force: Boolean = false): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && lastInputKind == 1 && now - lastKeyAt < INPUT_LOCK_MS) return false
        if (lastInputKind != 2) {
            lastInputKind = 2
            if (barFocus >= 0 && mode != 0) { ui.removeCallbacks(barRelease); barFocus = -1; updateButtons() }
        }
        lastPtrAt = now
        return true
    }

    private fun menuKey(k: String): Boolean {
        val grid = menuLayer.getChildAt(0) as? android.widget.GridView ?: return true
        val code = when (k) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> -1; else -> return false
        }
        if (grid.count == 0) return true
        if (!grid.hasFocus()) { grid.isFocusableInTouchMode = true; grid.requestFocus() }
        if (grid.selectedItemPosition < 0) { grid.setSelection(0); return true }
        if (code == -1) {
            val pos = grid.selectedItemPosition
            grid.performItemClick(grid.getChildAt(pos - grid.firstVisiblePosition), pos, grid.getItemIdAtPosition(pos))
            return true
        }
        val before = grid.selectedItemPosition
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        grid.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        if (k == "down" && grid.selectedItemPosition == before) { barFocus = 0; updateButtons() }   // hết hàng cuối: xuống thanh dưới
        return true
    }

    // Bàn phím / remote vật lý nối với TV: giữ ▼ đúng 1,5 giây = nhảy xuống thanh dưới (YouTube, TV đều dùng được)
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
            // Đang ở trang: ▼ nhấn nhanh = đi xuống như thường (xử lý lúc nhả phím); giữ 1,5 giây = xuống thanh dưới
            if (event?.repeatCount == 0) { downHoldFired = false; downHoldPending = true; ui.removeCallbacks(downHold); ui.postDelayed(downHold, 1500) }
            return true
        }
        val k = keyName(keyCode)
        if (k != null && !allowKeyInput()) return true       // đang dùng chuột: tạm bỏ phím
        if (k != null) {
            val inBarOrMenu = barFocus >= 0 || menuOpen()
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
            when (m) { 1 -> tv?.home(); 2 -> yt?.home(); 3 -> files?.home(); 4 -> aikara?.home() }
            return
        }
        hideFull()
        setMode(m)
        // Chuyen qua lai TV <-> YouTube: khong giu dai/video cu, luon ve menu chon dai (TV) hoac trang chu (YouTube)
        when (m) { 1 -> tv?.home(); 2 -> yt?.home(); 3 -> files?.home(); 4 -> aikara?.home(); 6 -> notes?.showList() }
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
        aikara?.let { content.removeView(it.view); it.onPause() }
        browser?.let { content.removeView(it.root); it.onPause() }
        notes?.let { content.removeView(it.view); it.onPause() }
        hideMenu()
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
                    fp.onOpenIpynb = { f -> openIpynbInBrowser(f) }
                    fp.onOpenNote = { f -> openNoteFile(f) }
                    fp.onExitDown = { barFocus = 7; updateButtons() }      // ▼ ở dòng cuối: xuống thanh dưới
                    files = fp
                }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            4 -> {
                val p = aikara ?: AiKaraPane(this).also { aikara = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            6 -> {
                val p = notes ?: NotesPane(this).also { notes = it }
                content.addView(p.view, FrameLayout.LayoutParams(-1, -1)); p.onResume()
            }
            5 -> {
                if (browser == null) {
                    // máy không dựng được giao diện trình duyệt (vd Android cũ): không làm sập app
                    try { browser = BrowserHost(this) { setMode(0) }.also { it.start(null) } } catch (e: Exception) { browser = null }
                }
                val b = browser
                if (b == null) { Toast.makeText(this, "Máy này chưa mở được trình duyệt", Toast.LENGTH_SHORT).show(); mode = 0 }
                else content.addView(b.root, FrameLayout.LayoutParams(-1, -1))
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
            4 -> aikara?.home()
            6 -> { if (notes?.onBack() != true) setMode(2) }   // Ghi chú: đang soạn -> về danh sách; đang ở danh sách -> quay lại YouTube
            5 -> if (browser?.back() != true) setMode(0)     // trình duyệt: lùi trang/đóng thẻ trước, hết thì về trang chủ
        }
    }

    // Khoa cam ung: nuot moi cham tren man hinh TV. Chuot ao cua remote gui thang vao root nen van hoat dong.
    // Cử chỉ vuốt lên bắt đầu từ mép đáy màn hình (0-1dp): giao diện không phản ứng với nó.
    // Chạm thường (không di chuyển) vẫn bấm được các nút trên thanh dưới.
    private var edgeStartY = -1f
    private var edgeSwallow = false

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (::remote.isInitialized && remote.touchLocked) return true
        if (ev != null) {
            val edgeBand = window.decorView.height - dp(1)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeSwallow = false
                    edgeStartY = if (ev.rawY > edgeBand) ev.rawY else -1f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (edgeStartY >= 0f && !edgeSwallow && edgeStartY - ev.rawY > dp(1)) {
                        edgeSwallow = true
                        // Huỷ cử chỉ đang được các nút nhận, để nút không bị bấm nhầm khi vuốt
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
            }
            if (edgeSwallow) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    edgeSwallow = false; edgeStartY = -1f
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (menuOpen()) hideMenu() else goBack() }     // luôn ở lại màn hình chính, không thoát app

    // ---------------- Điều khiển app bên ngoài (qua dịch vụ Trợ năng) ----------------

    private var resumed = false
    private val INPUT_LOCK_MS = 700L

    /** App TV ở phía trước: RemoteController xử lý. App khác ở phía trước: chuyển cho dịch vụ Trợ năng + phím media hệ thống. */
    fun routeRemote(json: String) {
        // Nút "Chuột" trên remote điện thoại: mouse=false -> chỉ dùng phím (phím luôn được nhận, bỏ lệnh chuột);
        // mouse=true -> ưu tiên chuột (chuột luôn được nhận, đang dùng chuột thì phím tạm bị bỏ).
        // Không có trường mouse (remote bản cũ) -> giữ cách cũ: ai dùng trước thì loại kia tạm tắt.
        val jo = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val mouseFlag: Boolean? = if (jo.has("mouse")) jo.optBoolean("mouse") else null
        // Chữ gõ từ bàn phím điện thoại: chèn tại con trỏ của ô nhập đang chọn (trong app này, hoặc app ngoài qua Trợ năng)
        when (jo.optString("action")) {
            "text", "enter" -> { phoneText(jo); return }
        }
        when (jo.optString("action")) {
            "move", "click", "longclick", "scroll" -> if (mouseFlag == false) return
            else -> { }
        }
        when (jo.optString("action")) {
            "move", "click", "longclick" -> if (!allowPointerInput(force = mouseFlag == true)) return      // chuột TẮT/đang dùng phím (bản cũ): bỏ chuột
            "key" -> if (!allowKeyInput(force = mouseFlag == false)) return                    // chuột BẬT và đang dùng chuột: tạm bỏ phím
        }
        val svc = RemoteAccessibilityService.instance
        if (mouseFlag == false && jo.optString("action") == "key") {
            ui.post { if (::remote.isInitialized) remote.hideCursor(); RemoteAccessibilityService.instance?.hideCursor() }
        }
        if (try { JSONObject(json).optString("action") == "home" } catch (e: Exception) { false }) { remoteHome(); return }
        if (backBurst(json)) return
        // Có hộp thoại (vd bảng Ẩn/hiện nút) đang nổi trên app: con trỏ trong app nằm DƯỚI hộp thoại nên không thấy / không bấm được
        // -> dùng con trỏ của dịch vụ trợ năng (cửa sổ phủ trên cùng, chạm theo toạ độ màn hình) cho tới khi hộp thoại đóng
        val dialogOnTop = resumed && !hasWindowFocus() && svc != null && !isFinishing
        if ((resumed || isFinishing) && !dialogOnTop) { remote.handle(json); return }
        val action = try { JSONObject(json).optString("action") } catch (e: Exception) { "" }
        when (action) {
            "move", "click", "longclick", "scroll", "key", "back" -> if (svc != null) svc.handle(json) else remote.handle(json)
            "media" -> systemMedia(try { JSONObject(json).optString("cmd") } catch (e: Exception) { "" })
            else -> remote.handle(json)       // volume, screen (tắt/bật màn hình)... tác động toàn hệ thống nên giữ nguyên
        }
    }

    private fun phoneText(jo: JSONObject) {
        val enter = jo.optString("action") == "enter"
        if (enter) TextGhost.clear()
        if (resumed && !isFinishing) {
            ui.post {
                if (enter) keyboardEnter() else keyboardEdit(jo.optInt("del").coerceAtLeast(0), jo.optString("ins"))
            }
        } else {
            val svc = RemoteAccessibilityService.instance
            if (svc != null) svc.handle(jo.toString())
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
        val atAppHome = resumed && !isFinishing && mode == 0 && !menuOpen() && fullView == null
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

    /** Mở trang "Tệp" (BrowserHost gọi khi bấm 📂 ở mục tải xuống). */
    fun showFilesPage() { hideMenu(); onModeButton(3) }

    /** Trang Tệp mở một ghi chú (.ghichu.html): sang trang Ghi chú để xem/sửa tiếp. */
    private fun openNoteFile(f: java.io.File) {
        hideMenu()
        if (mode != 6) { hideFull(); setMode(6) }
        notes?.openFile(f)
    }

    /** Trang Tệp mở tệp .ipynb: sang Trình duyệt, vào Colab và nhét tệp vào mục "Tải sổ tay lên". */
    private fun openIpynbInBrowser(f: java.io.File) {
        hideMenu()
        if (mode != 5) { hideFull(); setMode(5) }
        browser?.openColabWithFile(f)
    }

    private fun hideMenu() { if (::menuLayer.isInitialized) menuLayer.visibility = View.GONE; updateButtons() }

    private fun toggleMenu() {
        if (menuLayer.visibility == View.VISIBLE) { hideMenu(); return }
        hideFull()
        showAppMenu()
    }

    private class AppEntry(val label: String, val icon: android.graphics.drawable.Drawable?, val pkg: String, val cls: String)
    private var appCache: List<AppEntry>? = null
    private var menuLoading = false

    /** TẤT CẢ app có icon mở được (TV + điện thoại), trừ chính app này. Chạy ở luồng nền (TV Android 8 yếu). */
    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        fun query(cat: String): List<android.content.pm.ResolveInfo> =
            try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (e: Exception) { emptyList() }
        val tvResolves = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val all = tvResolves + query(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in all) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            // Không lọc app hệ thống nữa. Mỗi màn hình mở được (activity) là một ô; chỉ gộp bản trùng.
            if (!seen.add(pkg + "/" + ri.activityInfo.name)) continue
            if (hiddenApps().contains(pkg + "/" + ri.activityInfo.name)) continue   // đã "Xoá khỏi list"
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            // Một app vừa có icon TV vừa có icon điện thoại cùng tên: chỉ giữ icon TV (đã xếp trước)
            if (out.any { it.pkg == pkg && it.label == label }) continue
            val icon = try { ri.loadIcon(pm) } catch (e: Exception) { null }
            out.add(AppEntry(label, icon, pkg, ri.activityInfo.name))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun showAppMenu() {
        menuLayer.visibility = View.VISIBLE
        updateButtons()
        val cached = appCache
        if (cached != null) renderApps(cached) else {
            menuLayer.removeAllViews()
            menuLayer.addView(TextView(this).apply {
                text = "Đang tải ứng dụng..."; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        if (menuLoading) return
        menuLoading = true
        Thread {
            val list = try { loadApps() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                menuLoading = false
                if (isFinishing) return@runOnUiThread
                val changed = appCache?.map { it.pkg } != list.map { it.pkg }
                appCache = list
                if (menuLayer.visibility == View.VISIBLE && (cached == null || changed)) renderApps(list)
            }
        }.start()
    }

    private fun renderApps(apps: List<AppEntry>) {
        menuLayer.removeAllViews()
        if (apps.isEmpty()) {
            menuLayer.addView(TextView(this).apply {
                text = "Không tìm thấy ứng dụng nào"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
            return
        }
        val grid = android.widget.GridView(this).apply {
            numColumns = android.widget.GridView.AUTO_FIT
            columnWidth = dp(120)
            stretchMode = android.widget.GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8); horizontalSpacing = dp(8)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            clipToPadding = false
            selector = android.graphics.drawable.ColorDrawable(0x553A7BFF)
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(i: Int) = apps[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                    val e = apps[i]
                    val cell = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                        addView(android.widget.ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(56), dp(56)))
                        addView(TextView(this@MainActivity).apply {
                            setTextColor(Color.WHITE); textSize = 12f
                            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) })
                    }
                    (cell.getChildAt(0) as android.widget.ImageView).setImageDrawable(e.icon)
                    (cell.getChildAt(1) as TextView).text = e.label
                    return cell
                }
            }
            // Nhấn giữ 1 giây vào một icon: bảng 3 mục (Xoá khỏi list / Thông tin ứng dụng / Gỡ ứng dụng)
            var appHeld = false
            var downPos = -1
            var downX = 0f; var downY = 0f
            val slop = dp(10)
            val fireApp = Runnable {
                if (downPos in apps.indices) {
                    appHeld = true
                    val c = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                    try { onTouchEvent(c) } catch (ex: Exception) { }
                    c.recycle()
                    showAppActions(apps[downPos])
                }
            }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        appHeld = false; downX = ev.x; downY = ev.y
                        downPos = pointToPosition(ev.x.toInt(), ev.y.toInt())
                        ui.removeCallbacks(fireApp)
                        if (downPos >= 0) ui.postDelayed(fireApp, 1000)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!appHeld && (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop)) ui.removeCallbacks(fireApp)
                        appHeld
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        ui.removeCallbacks(fireApp)
                        val h = appHeld; appHeld = false; h
                    }
                    else -> appHeld
                }
            }
            setOnItemClickListener { _, _, pos, _ ->
                val e = apps[pos]
                val intent = Intent(Intent.ACTION_MAIN).setClassName(e.pkg, e.cls)
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { hideMenu(); startActivity(intent) }
                catch (ex: Exception) { Toast.makeText(this@MainActivity, "Không mở được ứng dụng này", Toast.LENGTH_SHORT).show() }
            }
        }
        menuLayer.addView(grid, FrameLayout.LayoutParams(-1, -1))
    }

    private fun hiddenApps(): MutableSet<String> =
        (getSharedPreferences("tv", MODE_PRIVATE).getString("hiddenApps", "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableSet()

    private var pendingAppRefresh = false

    /** Bảng 3 mục khi nhấn giữ icon app trong menu. */
    private fun showAppActions(e: AppEntry) {
        val items = arrayOf("Xoá khỏi list", "Thông tin ứng dụng", "Gỡ ứng dụng")
        AlertDialog.Builder(this)
            .setTitle(e.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {   // chỉ ẩn icon khỏi danh sách menu, app vẫn còn trên máy
                        val h = hiddenApps(); h.add(e.pkg + "/" + e.cls)
                        getSharedPreferences("tv", MODE_PRIVATE).edit().putString("hiddenApps", h.joinToString("|")).apply()
                        val list = (appCache ?: emptyList()).filter { !(it.pkg == e.pkg && it.cls == e.cls) }
                        appCache = list
                        if (menuOpen()) renderApps(list)
                        Toast.makeText(this, "Đã xoá \"" + e.label + "\" khỏi list", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {   // trang Thông tin ứng dụng của hệ thống
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (ex: Exception) {
                            Toast.makeText(this, "Không mở được thông tin ứng dụng", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> confirmUninstallApp(e)
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Gỡ ứng dụng khỏi máy (hỏi lại trước khi gỡ). */
    private fun confirmUninstallApp(e: AppEntry) {
        val ai = try { packageManager.getApplicationInfo(e.pkg, 0) } catch (ex: Exception) { null }
        if (ai == null) { Toast.makeText(this, "Không tìm thấy ứng dụng này", Toast.LENGTH_SHORT).show(); return }
        val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdatedSystem) {
            Toast.makeText(this, "\"" + e.label + "\" là ứng dụng hệ thống, không gỡ khỏi máy được", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Gỡ \"" + e.label + "\" khỏi máy?")
            .setMessage("Ứng dụng sẽ bị gỡ cài đặt khỏi thiết bị.")
            .setPositiveButton("Gỡ") { _, _ ->
                try {
                    pendingAppRefresh = true
                    @Suppress("DEPRECATION")
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (ex: Exception) {
                    pendingAppRefresh = false
                    Toast.makeText(this, "Không gỡ được ứng dụng này", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

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
        hideMenu(); hideFull(); setMode(0)
        Toast.makeText(this, "TV không có màn hình chính nào khác", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Ẩn/hiện nút trên thanh dưới ----------------

    // Thứ tự phải KHỚP barItems (xem buildUi): Menu | TV | YouTube | Trình duyệt | Ghi chú | Ái Kara | Cài đặt | Tệp | Home | Cập nhật | ← | Bàn phím | X | OFF
    private val barNames = listOf("Menu", "TV", "YouTube", "Trình duyệt", "Ghi chú", "Ái Kara", "Cài đặt", "Tệp", "Home", "Cập nhật", "← (Thoát)", "Bàn phím", "X", "OFF")
    private val rightNames = listOf("Pin", "Giờ", "Ngày dương", "Âm lịch")
    private fun rightViews() = listOf(tvBatt, tvTime, tvDate, tvLunar)

    private fun hiddenSet(): MutableSet<String> =
        (getSharedPreferences("tv", MODE_PRIVATE).getString("hiddenBar", "") ?: "").split("|").filter { it.isNotEmpty() }.toMutableSet()

    private fun saveHidden(h: Set<String>) { getSharedPreferences("tv", MODE_PRIVATE).edit().putString("hiddenBar", h.joinToString("|")).apply() }

    /** Áp trạng thái ẩn/hiện đã lưu lên thanh dưới (nút X luôn hiện để còn mở được bảng này). */
    private fun applyBarVisibility() {
        val h = hiddenSet()
        barItems.forEachIndexed { i, b -> b.visibility = if ((barNames.getOrNull(i) ?: "") in h) View.GONE else View.VISIBLE }
        rightViews().forEachIndexed { i, v -> v.visibility = if ((rightNames.getOrNull(i) ?: "") in h) View.GONE else View.VISIBLE }
        if (barFocus >= 0 && barItems.getOrNull(barFocus)?.visibility == View.GONE) { barFocus = -1 }
        updateButtons()
    }

    /** Bảng "Ẩn/hiện nút" kiểu gaming (nền tối, viền neon): 2 cột (trái / phải của thanh dưới). Bấm một dòng: đang hiện thì ẩn, đang ẩn thì hiện. */
    private fun showBarToggleDialog() {
        val h = hiddenSet()
        val cBg = 0xFF0A0E1F.toInt(); val cPanel = 0xFF131A33.toInt(); val cEdge = 0xFF2B3A6B.toInt()
        val cCyan = 0xFF00E5FF.toInt(); val cMag = 0xFFFF2E93.toInt(); val cViolet = 0xFF7C4DFF.toInt()
        val cGreen = 0xFF00E676.toInt(); val cRed = 0xFFFF1744.toInt(); val cDim = 0xFF7F8FBF.toInt()
        fun shape(fill: Int, stroke: Int, r: Int, sw: Int = 1) = android.graphics.drawable.GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(r).toFloat(); setStroke(dp(sw), stroke)
        }
        fun focusable(normal: android.graphics.drawable.Drawable, lit: android.graphics.drawable.Drawable) = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), lit)
            addState(intArrayOf(android.R.attr.state_pressed), lit)
            addState(intArrayOf(), normal)
        }
        fun spaced(t: TextView, v: Float) { if (Build.VERSION.SDK_INT >= 21) t.letterSpacing = v }

        val repaints = ArrayList<() -> Unit>()
        fun column(title: String, names: List<String>, accent: Int): LinearLayout {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = title; textSize = 13f; setTextColor(accent); typeface = android.graphics.Typeface.DEFAULT_BOLD
                spaced(this, 0.2f); setPadding(dp(6), dp(2), dp(6), dp(8))
            })
            for (n in names) {
                val dot = View(this)
                val label = TextView(this).apply { textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                val tag = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(dp(8), dp(2), dp(8), dp(2)) }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9)); isFocusable = true; isClickable = true
                    addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).also { it.rightMargin = dp(10) })
                    addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tag, LinearLayout.LayoutParams(-2, -2))
                }
                fun paint() {
                    val on = n !in h
                    label.text = n
                    label.setTextColor(if (on) Color.WHITE else cDim)
                    tag.text = if (on) "HIỆN" else "ẨN"
                    tag.setTextColor(if (on) 0xFF001F14.toInt() else cRed)
                    tag.background = if (on) shape(cGreen, cGreen, 6) else shape(0x22FF1744, cRed, 6)
                    dot.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(if (on) cGreen else cRed) }
                    row.background = focusable(
                        if (on) shape(0xFF102542.toInt(), cCyan, 10) else shape(cPanel, cEdge, 10),
                        shape(0xFF1E2F63.toInt(), cMag, 10, 2))
                }
                paint(); repaints.add { paint() }
                row.setOnClickListener {
                    if (n in h) h.remove(n) else h.add(n)
                    saveHidden(h); applyBarVisibility(); paint()
                }
                col.addView(row, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) })
            }
            return col
        }

        val title = TextView(this).apply {
            text = "ẨN / HIỆN NÚT"; textSize = 22f; setTextColor(cCyan); typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            spaced(this, 0.15f)
        }
        val line = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cCyan, cViolet, cMag))
        }
        val codeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = shape(cPanel, cMag, 12, 2); setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = "MÃ KẾT NỐI TV - ĐIỀU KHIỂN"; textSize = 12f; setTextColor(cDim); typeface = android.graphics.Typeface.DEFAULT_BOLD
                spaced(this, 0.2f); gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = TransferHost.CODE; textSize = 34f; setTextColor(cGreen); typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
                spaced(this, 0.3f); gravity = Gravity.CENTER
            })
        }
        val two = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(column("BÊN TRÁI", barNames.filter { it != "X" }, cViolet), LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(8) })
            addView(column("BÊN PHẢI", rightNames, cViolet), LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(8) })
        }
        val scroll = android.widget.ScrollView(this).apply { isFillViewport = false; addView(two) }

        val dlg = android.app.Dialog(this)
        fun btn(text: String, filled: Boolean, color: Int, onClick: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 15f; gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            spaced(this, 0.12f); isFocusable = true; isClickable = true
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setTextColor(if (filled) 0xFF00131A.toInt() else color)
            background = focusable(
                if (filled) shape(color, color, 10) else shape(Color.TRANSPARENT, color, 10, 2),
                shape(if (filled) Color.WHITE else 0x33FFFFFF, if (filled) Color.WHITE else color, 10, 3))
            setOnClickListener { onClick() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btn("HIỆN TẤT CẢ", false, cMag) { h.clear(); saveHidden(emptySet()); applyBarVisibility(); repaints.forEach { it() } }, LinearLayout.LayoutParams(-2, -2))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(btn("XONG", true, cCyan) { dlg.dismiss() }, LinearLayout.LayoutParams(-2, -2))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cBg, cCyan, 18, 2); setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(title, LinearLayout.LayoutParams(-2, -2))
            addView(line, LinearLayout.LayoutParams(-1, dp(2)).also { it.topMargin = dp(8); it.bottomMargin = dp(12) })
            addView(codeCard, LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(14) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val dm = resources.displayMetrics
            setLayout((dm.widthPixels * 0.82f).toInt().coerceAtMost(dp(900)), (dm.heightPixels * 0.92f).toInt())
            setDimAmount(0.7f)
        }
        showDialogImmersive(dlg)
    }

    /**
     * Hiện hộp thoại mà KHÔNG làm hiện thanh trạng thái / thanh đa nhiệm: cửa sổ hộp thoại mặc định không có cờ toàn màn hình,
     * nên vừa hiện là hệ thống kéo hai thanh đó ra. Đặt cờ toàn màn hình cho cửa sổ hộp thoại trước khi nó nhận focus.
     */
    @Suppress("DEPRECATION")
    private fun showDialogImmersive(dlg: android.app.Dialog) {
        val w = dlg.window
        w?.setFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dlg.show()
        if (w != null) {
            w.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            if (Build.VERSION.SDK_INT >= 30) {
                w.insetsController?.let { c ->
                    c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
                }
            }
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    /**
     * Nhấn giữ OFF 1 giây: rung, rồi hiện khung "Tắt nguồn TV" (hộp thoại của app, hệ thống hiện thanh điều hướng/trạng thái).
     * Chờ 0,5 giây mới hiện: cú chạm giữ chưa nhả hẳn, cú nhả rơi xuống ngoài khung sẽ đóng khung ngay (chỉ thấy rung, không thấy gì).
     * Bấm "Tắt nguồn" thì mở bảng tắt nguồn của Android (cửa sổ hệ thống); lớp phủ trợ năng được gỡ và không phủ lại trong lúc đó.
     */
    private fun openSystemPowerMenu() {
        hideMenu()
        buzz()
        ui.postDelayed({ showPowerDialog() }, 500)
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        try {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.navigationBars() or WindowInsets.Type.statusBars())
        } catch (e: Exception) { }
    }

    private fun showPowerDialog() {
        if (isFinishing) return
        try {
            detachUiOverlay()
            showSystemBars()
            val dlg = AlertDialog.Builder(this)
                .setTitle("Tắt nguồn TV")
                .setMessage("Bấm \"Tắt nguồn\" để mở bảng tắt nguồn của hệ thống, hoặc \"Khởi động lại\".")
                .setPositiveButton("Tắt nguồn") { _, _ ->
                    noOverlayUntil = android.os.SystemClock.uptimeMillis() + 5000
                    ui.postDelayed({
                        if (RemoteAccessibilityService.instance?.powerMenu() != true)
                            Toast.makeText(this, "Không mở được bảng tắt nguồn. Trợ năng của app TV: ${if (RemoteAccessibilityService.instance != null) "đã bật" else "CHƯA bật (vào Cài đặt > Trợ năng để bật)"}. Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}", Toast.LENGTH_LONG).show()
                    }, 300)
                    ui.postDelayed({ attachUiOverlay() }, 5200)
                }
                .setNeutralButton("Khởi động lại") { _, _ -> restartTv() }
                .setNegativeButton("Hủy", null)
                .create()
            dlg.setCanceledOnTouchOutside(false)
            dlg.show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi mở khung tắt nguồn: ${e.message}", Toast.LENGTH_LONG).show()
            attachUiOverlay()
        }
    }

    /** (Không còn dùng cho nút OFF) Khung hỏi khởi động lại. Nút "Tắt nguồn" mở menu nguồn của hệ thống như trước. */
    private fun confirmRestart() {
        AlertDialog.Builder(this)
            .setTitle("Khởi động lại TV?")
            .setMessage("TV sẽ tắt rồi tự bật lại.")
            .setPositiveButton("Khởi động lại") { _, _ -> restartTv() }
            .setNeutralButton("Tắt nguồn") { _, _ ->
                if (RemoteAccessibilityService.instance?.powerMenu() != true)
                    Toast.makeText(this, "Cần bật Trợ năng cho app TV để mở bảng tắt nguồn", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    /** Khởi động lại: thử reboot trực tiếp (máy có quyền hệ thống/root); không được thì mở menu nguồn để chọn Khởi động lại. */
    private fun restartTv() {
        try { (getSystemService(POWER_SERVICE) as android.os.PowerManager).reboot(null); return } catch (e: Throwable) { }
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")); return } catch (e: Throwable) { }
        if (RemoteAccessibilityService.instance?.powerMenu() == true)
            Toast.makeText(this, "Chọn \"Khởi động lại\" trong menu nguồn", Toast.LENGTH_LONG).show()
        else
            Toast.makeText(this, "Cần bật Trợ năng cho app TV để mở menu nguồn", Toast.LENGTH_LONG).show()
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
        ScreenAdmin.deactivate(this)      // bỏ quyền Quản trị thiết bị, nếu không app sẽ không gỡ được
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

    // ---------------- Bàn phím ảo nổi ----------------
    private fun toggleKeyboard() { if (kb == null) showKeyboard() else hideKeyboard() }

    private fun showKeyboard() {
        if (kb != null || !::rootView.isInitialized) return
        val sp = getSharedPreferences("tv", MODE_PRIVATE)
        val k = MiniKeyboard(this, { d, t -> keyboardEdit(d, t) }, { keyboardEnter() }, { hideKeyboard() },
            { x, y -> sp.edit().putInt("kbX", x).putInt("kbY", y).apply() })
        val rw = if (rootView.width > 0) rootView.width else resources.displayMetrics.widthPixels
        val rh = if (rootView.height > 0) rootView.height else resources.displayMetrics.heightPixels
        val w = minOf((dp(330) * MiniKeyboard.SCALE).toInt(), (rw * 0.94f).toInt()).coerceAtLeast(dp(220))
        val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.TOP or Gravity.START
        var x = sp.getInt("kbX", -1); var y = sp.getInt("kbY", -1)
        if (x < 0 || y < 0) { x = (rw - w - dp(8)).coerceAtLeast(0); y = (rh - (dp(190) * MiniKeyboard.SCALE).toInt() - barH - dp(8)).coerceAtLeast(0) }
        lp.leftMargin = x; lp.topMargin = y
        rootView.addView(k.view, lp)
        val l = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> k.clamp() }
        k.layoutListener = l
        rootView.addOnLayoutChangeListener(l)
        k.view.post { k.clamp() }
        kb = k
        sp.edit().putBoolean("kbOn", true).apply()
    }

    private fun hideKeyboard() {
        val k = kb ?: return
        kb = null
        k.layoutListener?.let { rootView.removeOnLayoutChangeListener(it) }
        rootView.removeView(k.view)
        k.destroy()
        getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("kbOn", false).apply()
    }

    /** Gõ vào ô đang chọn: ô nhập gốc của app (vd thanh địa chỉ trình duyệt) hoặc ô nhập trong trang web đang mở. */
    private fun keyboardEdit(del: Int, ins: String) {
        val f = rootView.findFocus()
        if (f is android.widget.EditText) {
            try {
                val (del2, ins2) = TextGhost.applied(del, ins)
                if (del2 == 0 && ins2.isEmpty()) return
                val t = f.text
                val a = f.selectionStart.coerceAtLeast(0); val b = f.selectionEnd.coerceAtLeast(0)
                if (a != b) t.replace(minOf(a, b), maxOf(a, b), ins2)
                else t.replace((b - del2).coerceAtLeast(0), b, ins2)
            } catch (e: Exception) { }
            return
        }
        applyTextEdit(del, ins)
    }

    private fun keyboardEnter() {
        val f = rootView.findFocus()
        if (f is android.widget.EditText) {
            f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            f.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }
        applyTextEdit(0, "\n")
    }

    // Điện thoại gửi {del, ins}: xoá `del` ký tự trước con trỏ rồi chèn `ins` vào ô nhập đang chọn của TV/YouTube.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv: WebView = (if (mode == 1) tv?.activeWeb else if (mode == 2) yt?.web else if (mode == 4) aikara?.web else if (mode == 5) browser?.currentWebView else if (mode == 6) notes?.web else null) ?: return
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
        if (browser?.handleActivityResult(requestCode, resultCode, data) == true) return   // kết quả chọn tệp của trình duyệt
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
        if (browser?.handlePermissionsResult(requestCode, permissions, grantResults) == true) return
        if (requestCode == REQ_VOICE_PERM && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoice()
    }

    // ---------------- Thanh trạng thái ----------------

    /** Pin: phần trăm; đang sạc thì thêm công suất nhận vào (W) = dòng sạc x điện áp. Máy không báo dòng thì chỉ hiện "sạc". */
    private fun refreshBattery() {
        try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) { tvBatt.text = ""; return }
            val pct = level * 100 / scale
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val charging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING
            var txt = "Pin $pct%"
            if (charging) {
                var w = -1.0
                try {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val cur = Math.abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble())   // thường là µA, vài máy là mA
                    var mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toDouble()
                    if (mv in 1.0..100.0) mv *= 1000.0                      // vài máy báo điện áp theo V
                    val ma = if (cur >= 20000.0) cur / 1000.0 else cur
                    if (ma > 0 && mv > 0) w = ma * mv / 1_000_000.0
                } catch (e: Exception) { }
                txt = if (w >= 0.05) String.format(Locale.US, "%.0fW ", w) + txt else "sạc $txt"
            }
            tvBatt.text = txt
        } catch (e: Exception) { }
    }

    private fun refreshStatus() {
        refreshBattery()
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
        if (intent?.getBooleanExtra(RemoteHub.EXTRA_HOME, false) == true) { hideMenu(); hideFull(); setMode(0) }
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

    override fun onResume() { super.onResume(); resumed = true; RemoteHub.appVisible = true; RemoteAccessibilityService.instance?.hideCursor(); attachUiOverlay(); if (accPendingHome) { accPendingHome = false; ui.postDelayed({ maybeAskDefaultHome() }, 500) }; hideSystemBars(); refreshStatus(); if (mode == 1) tv?.onResume() else if (mode == 2) yt?.onResume() else if (mode == 3) files?.onResume() else if (mode == 4) aikara?.onResume(); consumePendingFile(); if (pendingAppRefresh) { pendingAppRefresh = false; if (menuOpen()) showAppMenu() } }
    override fun onPause() { super.onPause(); resumed = false; RemoteHub.appVisible = false; detachUiOverlay(); tv?.onPause(); yt?.onPause(); files?.onPause(); aikara?.onPause(); browser?.onPause(); notes?.onPause() }

    override fun onDestroy() {
        kb?.destroy()
        detachUiOverlay()
        RemoteAccessibilityService.onConnected = null
        ui.removeCallbacksAndMessages(null)
        try { battReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        RemoteHub.setActivity(null)
        RemoteHub.host?.let { it.onTextEdit = { _, _ -> }; it.pickFile = { cb, _ -> cb?.onReceiveValue(null); true }; it.detach() }
        try { connWake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        tv?.destroy(); yt?.destroy(); files?.destroy(); aikara?.destroy(); browser?.destroy(); notes?.destroy()
        super.onDestroy()
    }
}
