using System;
using System.Collections.Generic;
using System.Text.Json;

namespace TiviWidget;

/// <summary>
/// Port RemoteController.handle và RemoteHub.background: nhận lệnh JSON từ điện thoại
/// (move, click, scroll, key, back, home, media, volume, screen, voice).
/// </summary>
internal sealed class RemoteControl
{
    private const double Gain = 1.6;      // như GAIN bên Android
    private const int WheelPerPx = 6;     // dy (px) -> đơn vị cuộn chuột

    private readonly List<DateTime> _backTimes = new();

    public event Action<string> KeyPressed;     // up, down, left, right, ok
    public event Action BackPressed;
    public event Action HomePressed;
    public event Action<string> VoiceQuery;
    public event Action<int> QualityRequested;      // 360 / 480 / 720
    public event Action FullscreenRequested;
    public event Action<bool> TouchLockChanged;
    public event Action<int> BrightnessChanged;     // 0..100

    public void Handle(string json)
    {
        JsonDocument doc;
        try { doc = JsonDocument.Parse(json); }
        catch (JsonException) { return; }

        using (doc)
        {
            var o = doc.RootElement;
            switch (Str(o, "action"))
            {
                case "move":
                    Move(Num(o, "dx"), Num(o, "dy"));
                    break;
                case "click":
                    InputInjector.Click();
                    break;
                case "scroll":
                    InputInjector.Wheel(unchecked((uint)(-Num(o, "dy") * 2 * WheelPerPx)));
                    break;
                case "key":
                    var k = Str(o, "key");
                    if (k.Length > 0 && k != "bar") KeyPressed?.Invoke(k);
                    break;
                case "back":
                    Back();
                    break;
                case "home":
                    HomePressed?.Invoke();
                    break;
                case "media":
                    Media(Str(o, "cmd"));
                    break;
                case "volume":
                    int level = Num(o, "level", -1);
                    if (level >= 0 && level <= 100) InputInjector.SetVolume(level / 100f);
                    break;
                case "screen":
                    if (Bool(o, "on", true)) InputInjector.ScreenOn(); else InputInjector.ScreenOff();
                    break;
                case "quality":
                    QualityRequested?.Invoke(Num(o, "q"));
                    break;
                case "fullscreen":
                    FullscreenRequested?.Invoke();
                    break;
                case "touchlock":
                    TouchLockChanged?.Invoke(Bool(o, "on", false));
                    break;
                case "brightness":
                    int b = Num(o, "level", -1);
                    if (b >= 0 && b <= 100) BrightnessChanged?.Invoke(b);
                    break;
                case "voice":
                    var q = Str(o, "q");
                    if (q.Trim().Length > 0) VoiceQuery?.Invoke(q);
                    break;
            }
        }
    }

    private static void Move(int dx, int dy)
    {
        if (!InputInjector.GetCursor(out int x, out int y)) return;
        int nx = Math.Clamp(x + (int)Math.Round(dx * Gain), 0, InputInjector.ScreenWidth - 1);
        int ny = Math.Clamp(y + (int)Math.Round(dy * Gain), 0, InputInjector.ScreenHeight - 1);
        InputInjector.SetCursor(nx, ny);
    }

    /// <summary>Lùi 1 lần: như back của Android. Lùi 3 lần liên tục (mỗi lần cách nhau không quá 1 giây) thì về trang chủ.</summary>
    private void Back()
    {
        var now = DateTime.UtcNow;
        if (_backTimes.Count > 0 && (now - _backTimes[_backTimes.Count - 1]).TotalMilliseconds > 1000)
            _backTimes.Clear();
        _backTimes.Add(now);
        if (_backTimes.Count >= 3)
        {
            _backTimes.Clear();
            HomePressed?.Invoke();
            return;
        }
        BackPressed?.Invoke();
    }

    /// <summary>Phím media. Windows không có phím tua, nên rew/fwd đổi sang bài trước/sau.</summary>
    private static void Media(string cmd)
    {
        switch (cmd)
        {
            case "rew": case "prev": InputInjector.Key(0xB1); break;   // VK_MEDIA_PREV_TRACK
            case "fwd": case "next": InputInjector.Key(0xB0); break;   // VK_MEDIA_NEXT_TRACK
            case "playpause": InputInjector.Key(0xB3); break;          // VK_MEDIA_PLAY_PAUSE
        }
    }

    private static string Str(JsonElement o, string name) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static int Num(JsonElement o, string name, int def = 0) =>
        o.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? (int)Math.Round(v.GetDouble()) : def;

    private static bool Bool(JsonElement o, string name, bool def) =>
        o.TryGetProperty(name, out var v) && (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False)
            ? v.GetBoolean() : def;
}
