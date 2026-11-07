using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Globalization;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Text.Json;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;

namespace TiviWidget;

public partial class MainWindow : Window
{
    // Cùng trang chủ YouTube như bản Android
    private const string YtHome = "https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1";
    private const string PauseMediaJs =
        "document.querySelectorAll('video,audio').forEach(function(v){try{v.pause()}catch(e){}})";

    private enum Mode { Tv, YouTube }

    private readonly DispatcherTimer _clock = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer _watchdog = new() { Interval = TimeSpan.FromSeconds(10) };
    private readonly DispatcherTimer _cover = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly DispatcherTimer _statusTimer = new() { Interval = TimeSpan.FromSeconds(4) };
    private IntPtr _hwnd = IntPtr.Zero;
    private bool _exiting;
    private bool _relaunching;
    private bool _playerActive;
    private bool _touchLocked;
    private int _brightness = 100;

    private readonly List<ChannelGroup> _groups = ChannelRepository.Load();
    private Mode _mode = Mode.YouTube;   // Android mở vào thẳng YouTube
    private int _level = 0;              // 0 = chọn đài, 1 = chọn kênh, 2 = đang xem
    private int _curGroup = -1;
    private string _curUrl = "";

    // Lịch sử trang đã xem của YouTube (port trail của YouTubePane)
    private readonly List<string> _trail = new();
    private readonly List<string> _trailKeys = new();

    // Điều hướng bằng phím/chuột: ô đang chọn có viền trắng (như ô chọn trên remote Android)
    private List<Button> _nav = new();
    private int _navCols = 1;
    private int _navSel = 0;
    private int _coverLeft = 0;

    private readonly VoiceService _voice = new();
    private readonly RemoteControl _control = new();
    private RemoteBridge _remote;

    private static readonly Brush NavOn = new SolidColorBrush(Color.FromRgb(0x15, 0x65, 0xC0));
    private static readonly Brush NavOff = new SolidColorBrush(Color.FromRgb(0x2B, 0x2B, 0x3A));

    public MainWindow()
    {
        InitializeComponent();

        _clock.Tick += (_, _) =>
        {
            UpdateClock();
            DesktopHost.UpdateState(_hwnd);   // luôn phóng to tối đa trên desktop
        };
        _clock.Start();
        UpdateClock();

        _watchdog.Tick += (_, _) =>
        {
            // Explorer khởi động lại làm mất cửa sổ nền: khởi động lại app để gắn vào desktop mới
            if (!DesktopHost.IsAlive(_hwnd)) { RelaunchSelf(); return; }
            DesktopHost.Refresh(_hwnd);
        };
        _watchdog.Start();

        _cover.Tick += (_, _) => CoverTick();
        _statusTimer.Tick += (_, _) => { _statusTimer.Stop(); VoiceStatus.Visibility = Visibility.Collapsed; };

        _voice.Recognized += text => Dispatcher.BeginInvoke(new Action(() => OnVoiceText(text)));
        _voice.Status += msg => Dispatcher.BeginInvoke(new Action(() => ShowStatus(msg)));
        _control.KeyPressed += OnRemoteKey;
        _control.BackPressed += OnRemoteBack;
        _control.HomePressed += OnRemoteHome;
        _control.VoiceQuery += OnVoiceText;
        _control.QualityRequested += SetQuality;
        _control.FullscreenRequested += FullscreenVideo;
        _control.TouchLockChanged += SetTouchLock;
        _control.BrightnessChanged += SetBrightness;

        SystemEvents.DisplaySettingsChanged += OnDisplaySettingsChanged;
        Closed += (_, _) =>
        {
            SystemEvents.DisplaySettingsChanged -= OnDisplaySettingsChanged;
            _voice.Dispose();
            InputInjector.KeepAwake(false);
            if (!_exiting) RelaunchSelf();   // cửa sổ bị huỷ ngoài ý muốn
        };

        Loaded += async (_, _) => await InitWebAsync();
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        _hwnd = new WindowInteropHelper(this).Handle;
        DesktopHost.Attach(_hwnd);
    }

    /// <summary>Mở bản mới rồi thoát bản này. Bản mới chờ bản cũ thoát (xem App.OnStartup).</summary>
    private void RelaunchSelf()
    {
        if (_relaunching || _exiting) return;
        _relaunching = true;
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = true, Arguments = "--restarted" });
        }
        catch (Exception) { }
        QuitApp();
    }

    /// <summary>Thoát widget (Ctrl+Alt+Q, hoặc khi gỡ cài đặt).</summary>
    private void QuitApp()
    {
        _exiting = true;
        Application.Current.Shutdown();
    }

    private void OnDisplaySettingsChanged(object sender, EventArgs e)
    {
        Dispatcher.BeginInvoke(new Action(() => DesktopHost.Refresh(_hwnd)));
    }

    // ================= WebView2 =================

    private async Task InitWebAsync()
    {
        // YouTube
        await Web.EnsureCoreWebView2Async();
        var yt = Web.CoreWebView2;
        ConfigureCore(yt);
        await yt.AddScriptToExecuteOnDocumentCreatedAsync(WebScripts.YtDocStart);   // chặn quảng cáo sớm
        yt.WebMessageReceived += (_, e) =>
        {
            if (e.TryGetWebMessageAsString() == "voice")   // nút mic của YouTube
                Dispatcher.BeginInvoke(new Action(StartVoice));
        };
        yt.NavigationStarting += (_, e) =>
        {
            if (YtRules.Allowed(e.Uri)) return;
            e.Cancel = true;
            ShowStatus("Mục này chỉ mở được YouTube");
        };
        yt.NavigationCompleted += (_, _) => InjectYouTube();
        yt.HistoryChanged += (_, _) => { if (Web.Source != null) RecordTrail(Web.Source.ToString()); };

        // Kênh TV
        await Player.EnsureCoreWebView2Async();
        ConfigureCore(Player.CoreWebView2);
        Player.CoreWebView2.NavigationCompleted += (_, _) =>
        {
            _ = Player.CoreWebView2.ExecuteScriptAsync(TvScripts.TvFull);
            ApplyBrightness(Player.CoreWebView2);
        };

        // Điều khiển từ điện thoại: trang truyentep.html chạy ẩn (Firebase + WebRTC, như Android)
        _remote = new RemoteBridge(RemoteHost);
        _remote.RemoteJson += _control.Handle;
        _remote.TextEdit += OnTextEdit;
        _remote.KeepAwake += InputInjector.KeepAwake;
        _remote.Toast += ShowStatus;
        await _remote.StartAsync();

        OpenYouTube();
    }

    private static void ConfigureCore(CoreWebView2 core)
    {
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsZoomControlEnabled = false;
        AdBlocker.Attach(core);
        // Link target=_blank mở ngay trong khung, không mở cửa sổ lạ
        core.NewWindowRequested += (_, args) =>
        {
            args.Handled = true;
            core.Navigate(args.Uri);
        };
    }

    /// <summary>Chạy các JS YouTube sau khi trang tải xong (giống onPageFinished bên Android).</summary>
    private void InjectYouTube()
    {
        var core = Web.CoreWebView2;
        if (core == null || Web.Source == null) return;
        RecordTrail(Web.Source.ToString());
        if (!YtRules.IsYoutubeHost(Web.Source.Host.ToLowerInvariant())) return;
        foreach (var js in new[]
        {
            WebScripts.YtOnlyAdWithFlag, WebScripts.VoiceHook, WebScripts.HideOpenApp,
            WebScripts.YtNoMute, WebScripts.YtNav, WebScripts.HideShorts
        })
            _ = core.ExecuteScriptAsync(js);
        ApplyBrightness(core);
    }

    private void OpenYouTube()
    {
        var core = Web.CoreWebView2;
        if (core == null) return;
        _trail.Clear();
        _trailKeys.Clear();
        _ = core.ExecuteScriptAsync(PauseMediaJs);
        core.Navigate(YtHome);
    }

    /// <summary>Lưu từng trang đã xem để nút ← quay lại đúng từng bước.</summary>
    private void RecordTrail(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return;
        if (u.Scheme != "https" && u.Scheme != "http") return;
        var key = PageKey(u);
        if (_trail.Count > 0 && _trailKeys[_trailKeys.Count - 1] == key)
        {
            _trail[_trail.Count - 1] = url;   // cùng trang (vd. đổi tham số phụ): thay URL mới nhất
            return;
        }
        _trail.Add(url);
        _trailKeys.Add(key);
        if (_trail.Count > 40) { _trail.RemoveAt(0); _trailKeys.RemoveAt(0); }
    }

    /// <summary>Các URL cùng một video (thêm t=, list=...) tính là một trang.</summary>
    private static string PageKey(Uri u)
    {
        if (u.AbsolutePath == "/watch")
            return "watch:" + (System.Web.HttpUtility.ParseQueryString(u.Query)["v"] ?? "");
        return new UriBuilder(u) { Fragment = "" }.Uri.ToString();
    }

    private void SearchYouTube(string q)
    {
        if (_mode != Mode.YouTube) SetMode(Mode.YouTube);
        Web.CoreWebView2?.Navigate("https://www.youtube.com/results?search_query=" + Uri.EscapeDataString(q));
    }

    /// <summary>Lùi theo danh sách trang đã xem (port back() của YouTubePane); hết lịch sử thì về trang chủ.</summary>
    private void YtBack()
    {
        var core = Web.CoreWebView2;
        if (core == null) return;
        if (_trail.Count > 1)
        {
            _trail.RemoveAt(_trail.Count - 1);
            _trailKeys.RemoveAt(_trailKeys.Count - 1);
            _ = core.ExecuteScriptAsync(PauseMediaJs);
            core.Navigate(_trail[_trail.Count - 1]);
            return;
        }
        var path = Web.Source?.AbsolutePath ?? "/";
        if (path == "/" || path.Length == 0) return;   // đã ở trang chủ
        OpenYouTube();
    }

    /// <summary>Remote ▲▼◀▶ OK: thử qua JS trước; nếu trang không có ô chọn thì gửi phím thô (giống onKey bên Android).</summary>
    private async void YtRemote(string k)
    {
        var core = Web.CoreWebView2;
        if (core == null) return;
        string js = k switch
        {
            "up" => "window.__wbVert&&window.__wbVert(-1)",
            "down" => "window.__wbVert&&window.__wbVert(1)",
            "left" => "window.__wbNav&&window.__wbNav(-1)",
            "right" => "window.__wbNav&&window.__wbNav(1)",
            "ok" => "window.__wbOk&&window.__wbOk()",
            _ => ""
        };
        if (js.Length == 0) return;
        var r = await core.ExecuteScriptAsync(js);
        if (r != "true") FallbackKey(k);
    }

    private void FallbackKey(string k)
    {
        if (Web.CoreWebView2 != null) DispatchKeyJs(Web.CoreWebView2, k);
    }

    /// <summary>Gửi phím tới phần tử đang có focus trong trang (JS). Enter khi k = "ok".</summary>
    private static void DispatchKeyJs(CoreWebView2 core, string k)
    {
        string key = k switch
        {
            "up" => "ArrowUp",
            "down" => "ArrowDown",
            "left" => "ArrowLeft",
            "right" => "ArrowRight",
            _ => "Enter"
        };
        _ = core.ExecuteScriptAsync(
            "(function(){var el=document.activeElement||document.body;" +
            "['keydown','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,{key:'" + key +
            "',bubbles:true}));});})();");
    }

    /// <summary>Phím từ điện thoại (port RemoteController.key): đang xem thì đưa cho trang, còn lại điều hướng ô chọn.</summary>
    private void OnRemoteKey(string k)
    {
        if (_mode == Mode.YouTube) { YtRemote(k); return; }
        if (_level == 2)
        {
            if (Player.CoreWebView2 != null) DispatchKeyJs(Player.CoreWebView2, k);
            return;
        }
        switch (k)
        {
            case "left": MoveNav(-1); break;
            case "right": MoveNav(1); break;
            case "up": MoveNav(-_navCols); break;
            case "down": MoveNav(_navCols); break;
            case "ok":
                if (_navSel < _nav.Count) _nav[_navSel].RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                break;
        }
    }

    /// <summary>Lùi (port RemoteHub back): YouTube thì lùi theo lịch sử, TV thì quay lại bước trước.</summary>
    private void OnRemoteBack()
    {
        if (_mode == Mode.YouTube) YtBack();
        else GoBack();
    }

    /// <summary>Nút Màn hình chính: đầu trang chọn đài -> sang YouTube; còn lại -> về trang chọn đài.</summary>
    private void OnRemoteHome()
    {
        if (_mode == Mode.Tv && _level == 0) { SetMode(Mode.YouTube); OpenYouTube(); return; }
        if (_mode != Mode.Tv) SetMode(Mode.Tv);
        ShowGroups();
    }

    /// <summary>Điện thoại gõ chữ vào ô đang chọn (port applyTextEdit): xoá del ký tự trước con trỏ rồi chèn ins.</summary>
    private void OnTextEdit(int del, string ins)
    {
        CoreWebView2 target = _mode == Mode.YouTube
            ? Web.CoreWebView2
            : (_level == 2 ? Player.CoreWebView2 : null);
        if (target == null) return;
        if (del == 0 && ins == "\n") { DispatchKeyJs(target, "ok"); return; }   // Enter
        if (del == 0 && ins.Length == 0) return;
        _ = target.ExecuteScriptAsync("(" + WebScripts.TextEditFn + ")(" + del + "," + JsonSerializer.Serialize(ins) + ");");
    }

    // ================= Chế độ TV / YouTube =================

    private void BtnTv_Click(object sender, RoutedEventArgs e)
    {
        if (_mode == Mode.Tv) { ShowGroups(); return; }   // bấm lại: về trang chọn đài
        SetMode(Mode.Tv);
        ShowGroups();
    }

    private void BtnYt_Click(object sender, RoutedEventArgs e)
    {
        if (_mode == Mode.YouTube) { OpenYouTube(); return; }
        SetMode(Mode.YouTube);
        OpenYouTube();
    }

    private void SetMode(Mode m)
    {
        StopPlayer();
        _mode = m;
        bool tv = m == Mode.Tv;
        Web.Visibility = tv ? Visibility.Collapsed : Visibility.Visible;
        Chooser.Visibility = tv ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowGroups(int sel = 0)
    {
        _level = 0;
        _curGroup = -1;
        StopPlayer();
        ChooserTitle.Text = "Chọn Kênh";

        var grid = new UniformGrid { Columns = 3, Margin = new Thickness(8, 0, 8, 12) };
        var items = new List<Button>();
        for (int i = 0; i < _groups.Count; i++)
        {
            int gi = i;
            var b = MakeChip(_groups[i].Name, 64);
            b.Click += (_, _) => ShowChannels(gi);
            items.Add(b);
            grid.Children.Add(b);
        }
        ChooserScroll.Content = grid;
        SetNav(items, 3, sel);
    }

    private void ShowChannels(int gi, int sel = 0)
    {
        var g = _groups[gi];
        _curGroup = gi;
        if (g.Channels.Count == 1) { Play(g.Channels[0].Url); return; }   // đài chỉ có 1 kênh: xem luôn

        _level = 1;
        StopPlayer();
        ChooserTitle.Text = g.Name;

        var panel = new StackPanel { Margin = new Thickness(8, 0, 8, 12) };
        var items = new List<Button>();
        foreach (var ch in g.Channels)
        {
            var url = ch.Url;
            var b = MakeChip(ch.Name, 56);
            b.Margin = new Thickness(4, 4, 4, 4);
            b.Click += (_, _) => Play(url);
            items.Add(b);
            panel.Children.Add(b);
        }
        ChooserScroll.Content = panel;
        SetNav(items, 1, sel);
    }

    private void Play(string url)
    {
        _level = 2;
        _curUrl = url;
        _nav = new List<Button>();
        Chooser.Visibility = Visibility.Collapsed;
        _playerActive = true;
        BtnBack.Visibility = Visibility.Visible;
        StartCover();
        Player.CoreWebView2?.Navigate(url);
    }

    private void StopPlayer()
    {
        StopCover();
        if (!_playerActive) return;
        _playerActive = false;
        Player.Visibility = Visibility.Collapsed;
        BtnBack.Visibility = Visibility.Collapsed;
        Player.CoreWebView2?.Navigate("about:blank");   // dừng âm thanh/video kênh cũ
    }

    private void GoBack()
    {
        switch (_level)
        {
            case 2:
                if (_curGroup >= 0 && _groups[_curGroup].Channels.Count > 1) ShowChannels(_curGroup);
                else ShowGroups();
                break;
            case 1:
                ShowGroups();
                break;
        }
    }

    private void NextChannel()
    {
        if (_level != 2 || _curGroup < 0) return;
        var list = _groups[_curGroup].Channels;
        if (list.Count < 2) return;
        int i = list.FindIndex(c => c.Url == _curUrl);
        Play(list[(i + 1) % list.Count].Url);
    }

    private void BtnBack_Click(object sender, RoutedEventArgs e) => GoBack();

    // ================= Đếm ngược che kênh đang tải =================

    private void StartCover()
    {
        _cover.Stop();
        Player.Visibility = Visibility.Collapsed;   // WebView2 luôn nằm trên WPF nên phải ẩn để thấy lớp đếm ngược
        _coverLeft = 5;
        CoverText.Text = "5";
        CoverBorder.Visibility = Visibility.Visible;
        _cover.Start();
    }

    private void CoverTick()
    {
        _coverLeft--;
        if (_coverLeft >= 1) CoverText.Text = _coverLeft.ToString();
        else StopCover();
    }

    private void StopCover()
    {
        _cover.Stop();
        CoverBorder.Visibility = Visibility.Collapsed;
        if (_playerActive) Player.Visibility = Visibility.Visible;
    }

    // ================= Giọng nói =================

    private void StartVoice()
    {
        if (_mode == Mode.YouTube && Web.Visibility != Visibility.Visible) return;
        _voice.Start();
    }

    /// <summary>Port remoteVoice(): câu ghép trước, rồi lệnh mở, rồi theo chế độ đang chọn.</summary>
    private void OnVoiceText(string text)
    {
        ShowStatus("Nghe: " + text);

        var compound = VoiceRouter.SwitchAndQuery(text);
        if (compound is { } c)
        {
            if (c.Mode == VoiceTarget.Tv)
            {
                if (_mode != Mode.Tv) SetMode(Mode.Tv);
                ShowGroups();
                PlayChannelOrReport(c.Rest);
            }
            else
            {
                if (_mode != Mode.YouTube) SetMode(Mode.YouTube);
                SearchYouTube(c.Rest);
            }
            return;
        }

        var open = VoiceRouter.OpenCommand(text);
        if (open != VoiceTarget.None)
        {
            if (open == VoiceTarget.Tv)
            {
                if (_mode != Mode.Tv) SetMode(Mode.Tv);
                ShowGroups();
            }
            else
            {
                if (_mode != Mode.YouTube) SetMode(Mode.YouTube);
                OpenYouTube();
            }
            return;
        }

        if (_mode == Mode.Tv) { PlayChannelOrReport(text); return; }
        if (_mode != Mode.YouTube) SetMode(Mode.YouTube);
        SearchYouTube(text);
    }

    private void PlayChannelOrReport(string q)
    {
        var hit = ChannelMatcher.Find(q, _groups);
        if (hit == null) { ShowStatus("Không tìm thấy kênh: " + q); return; }
        _curGroup = hit.Value.Gi;
        Play(hit.Value.Url);
    }

    // ================= Điều khiển từ điện thoại: chất lượng, toàn màn hình, khoá cảm ứng, độ sáng =================

    /// <summary>Trang đang được điều khiển: YouTube, hoặc kênh TV đang xem.</summary>
    private CoreWebView2 ActiveCore() =>
        _mode == Mode.YouTube ? Web.CoreWebView2 : (_level == 2 ? Player.CoreWebView2 : null);

    /// <summary>Port setQuality của RemoteController: lưu chất lượng và ép player; lặp ở 1,5 s và 4 s như Android.</summary>
    private async void SetQuality(int q)
    {
        string name = q switch { 360 => "medium", 480 => "large", 720 => "hd720", _ => "" };
        var core = ActiveCore();
        if (name.Length == 0 || core == null) return;
        string js = "(function(){var q=" + q + ",n='" + name + "';try{var t=Date.now();localStorage.setItem('yt-player-quality',JSON.stringify({data:JSON.stringify({quality:q,previousQuality:q}),expiration:t+2592000000,creation:t}));}catch(e){}" +
            "try{var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');if(p){if(p.setPlaybackQualityRange)p.setPlaybackQualityRange(n,n);if(p.setPlaybackQuality)p.setPlaybackQuality(n);}}catch(e){}})()";
        try
        {
            await core.ExecuteScriptAsync(js);
            await Task.Delay(1500);
            await core.ExecuteScriptAsync(js);
            await Task.Delay(2500);
            await core.ExecuteScriptAsync(js);
        }
        catch (Exception) { }
    }

    /// <summary>Port fullscreenVideo: chạm thật vào giữa video (DevTools) rồi mới xin toàn màn hình, vì trình duyệt chỉ chấp nhận khi có thao tác người dùng.</summary>
    private async void FullscreenVideo()
    {
        var core = ActiveCore();
        if (core == null) return;
        try
        {
            string raw = await core.ExecuteScriptAsync(
                "(function(){var v=document.querySelector('video');if(!v)return '';var b=v.getBoundingClientRect();" +
                "return Math.round(b.left+b.width/2)+','+Math.round(b.top+b.height/2);})()");
            var parts = (JsonSerializer.Deserialize<string>(raw) ?? "").Split(',');
            if (parts.Length != 2 || !int.TryParse(parts[0], out int x) || !int.TryParse(parts[1], out int y)) return;
            string pos = "\"x\":" + x.ToString(CultureInfo.InvariantCulture) + ",\"y\":" + y.ToString(CultureInfo.InvariantCulture) +
                         ",\"button\":\"left\",\"clickCount\":1}";
            await core.CallDevToolsProtocolMethodAsync("Input.dispatchMouseEvent", "{\"type\":\"mousePressed\"," + pos);
            await core.CallDevToolsProtocolMethodAsync("Input.dispatchMouseEvent", "{\"type\":\"mouseReleased\"," + pos);
            await core.ExecuteScriptAsync(
                "(function(){var v=document.querySelector('video');if(!v)return;var p=v.closest('#movie_player,.html5-video-player')||v;" +
                "if(p.requestFullscreen)p.requestFullscreen().catch(function(){});})()");
        }
        catch (Exception) { }
    }

    /// <summary>Khoá cảm ứng: khoá các nút WPF và bỏ phím tại chỗ. Chạm trong WebView2 vẫn không chặn được (hạn chế của WebView2).</summary>
    private void SetTouchLock(bool on)
    {
        _touchLocked = on;
        BtnTv.IsEnabled = !on;
        BtnYt.IsEnabled = !on;
        BtnBack.IsEnabled = !on;
        ChooserScroll.IsEnabled = !on;
        ShowStatus(on ? "Điện thoại đang khoá cảm ứng TV" : "Đã mở khoá cảm ứng TV");
    }

    /// <summary>Độ sáng 0..100 như Android. Không đổi độ sáng màn hình hệ thống: làm tối nội dung trang bằng CSS filter.</summary>
    private void SetBrightness(int level)
    {
        _brightness = level;
        ApplyBrightness(Web.CoreWebView2);
        ApplyBrightness(Player.CoreWebView2);
    }

    private void ApplyBrightness(CoreWebView2 core)
    {
        if (core == null) return;
        string filter = _brightness >= 100
            ? "none"
            : "brightness(" + Math.Max(0.02, _brightness / 100.0).ToString(CultureInfo.InvariantCulture) + ")";
        _ = core.ExecuteScriptAsync("(function(){document.documentElement.style.filter='" + filter + "';})();");
    }

    // ================= Phím / chuột =================

    private void SetNav(List<Button> items, int cols, int sel)
    {
        _nav = items;
        _navCols = cols;
        _navSel = Math.Clamp(sel, 0, Math.Max(0, items.Count - 1));
        PaintNav();
    }

    private void PaintNav()
    {
        for (int i = 0; i < _nav.Count; i++)
        {
            bool sel = i == _navSel;
            _nav[i].Background = sel ? NavOn : NavOff;
            _nav[i].BorderBrush = Brushes.White;
            _nav[i].BorderThickness = new Thickness(sel ? 3 : 0);
        }
        if (_navSel < _nav.Count) _nav[_navSel].BringIntoView();
    }

    private void MoveNav(int d)
    {
        if (_nav.Count == 0) return;
        int last = _nav.Count - 1;
        int n = _navSel + d;
        if (n >= 0 && n <= last) _navSel = n;
        else if (d == _navCols && _navCols > 1 && _navSel / _navCols < last / _navCols) _navSel = last;
        PaintNav();
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);
        if (e.Handled) return;

        // Ctrl+Alt+Q: thoát widget (không có nút X vì đã gỡ theo yêu cầu)
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        if (key == Key.Q && Keyboard.Modifiers == (ModifierKeys.Control | ModifierKeys.Alt))
        {
            QuitApp();
            e.Handled = true;
            return;
        }

        if (_touchLocked) return;   // đang khoá cảm ứng: bỏ phím tại chỗ, chỉ nhận lệnh từ điện thoại

        // Ctrl+M: nói lệnh (TV hoặc YouTube)
        if (Keyboard.Modifiers == ModifierKeys.Control && e.Key == Key.M)
        {
            StartVoice();
            e.Handled = true;
            return;
        }

        if (_mode == Mode.YouTube)
        {
            switch (e.Key)
            {
                case Key.Up: YtRemote("up"); break;
                case Key.Down: YtRemote("down"); break;
                case Key.Left: YtRemote("left"); break;
                case Key.Right: YtRemote("right"); break;
                case Key.Enter: YtRemote("ok"); break;
                case Key.Escape:
                case Key.Back: YtBack(); break;
                default: return;
            }
            e.Handled = true;
            return;
        }

        switch (e.Key)
        {
            case Key.Escape:
            case Key.Back:
                GoBack();
                break;
            case Key.N:
            case Key.PageDown:
                if (_level != 2) return;
                NextChannel();
                break;
            case Key.Left when _level < 2:  MoveNav(-1); break;
            case Key.Right when _level < 2: MoveNav(1); break;
            case Key.Up when _level < 2:    MoveNav(-_navCols); break;
            case Key.Down when _level < 2:  MoveNav(_navCols); break;
            case Key.Enter:
                if (_level < 2 && _navSel < _nav.Count)
                    _nav[_navSel].RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                break;
            default:
                return;
        }
        e.Handled = true;
    }

    private void ShowStatus(string text)
    {
        VoiceStatus.Text = text;
        VoiceStatus.Visibility = Visibility.Visible;
        _statusTimer.Stop();
        _statusTimer.Start();
    }

    private static Button MakeChip(string text, double height) => new()
    {
        Content = text,
        Height = height,
        Margin = new Thickness(4),
        FontSize = 16,
        Foreground = Brushes.White,
        Background = NavOff,
        BorderThickness = new Thickness(0),
        Padding = new Thickness(8, 0, 8, 0)
    };

    // ================= Đồng hồ + âm lịch =================

    private void UpdateClock()
    {
        var now = DateTime.Now;
        int thu = now.DayOfWeek == DayOfWeek.Sunday ? 8 : (int)now.DayOfWeek + 1;
        var l = LunarCalendar.SolarToLunar(now.Day, now.Month, now.Year);
        string leap = l[3] == 1 ? " nhuận" : "";
        ClockText.Text = $"{now:HH:mm}   T{thu} {now:dd/MM/yyyy}   ÂL {l[0]}/{l[1]}{leap}";
    }
}
