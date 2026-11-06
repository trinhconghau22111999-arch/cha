using System;
using System.Collections.Generic;
using System.Linq;
using TiviWidget;

Console.OutputEncoding = System.Text.Encoding.UTF8;
int pass = 0, fail = 0;
void Check(bool ok, string name) { if (ok) pass++; else { fail++; Console.WriteLine("FAIL: " + name); } }

// ---- Lịch âm
void Lunar(int d, int m, int y, int[] exp) {
    var r = LunarCalendar.SolarToLunar(d, m, y);
    Check(r.SequenceEqual(exp), $"lunar {d}/{m}/{y} -> [{string.Join(",", r)}] expected [{string.Join(",", exp)}]");
}
Lunar(1, 1, 2000, new[] { 25, 11, 1999, 0 });
Lunar(10, 2, 2024, new[] { 1, 1, 2024, 0 });
Lunar(17, 2, 2026, new[] { 1, 1, 2026, 0 });
Lunar(6, 11, 2026, new[] { 28, 9, 2026, 0 });

// ---- Giọng nói
var sw = VoiceRouter.SwitchAndQuery("mở YouTube mở bài hát Lạc trôi");
Check(sw.HasValue && sw.Value.Mode == VoiceTarget.YouTube && sw.Value.Rest == "Lạc trôi", "compound youtube");
var tv = VoiceRouter.SwitchAndQuery("mở TV kênh HTV9");
Check(tv.HasValue && tv.Value.Mode == VoiceTarget.Tv && tv.Value.Rest == "HTV9", "compound tv");
Check(VoiceRouter.SwitchAndQuery("mở TV") == null, "open-only has no compound");
Check(VoiceRouter.SwitchAndQuery("mở YouTube") == null, "open-only youtube has no compound");
Check(VoiceRouter.SwitchAndQuery("Cà Mau") == null, "plain name has no compound");
Check(VoiceRouter.OpenCommand("mở TV") == VoiceTarget.Tv, "open tv");
Check(VoiceRouter.OpenCommand("mở YouTube") == VoiceTarget.YouTube, "open youtube");
Check(VoiceRouter.OpenCommand("Cà Mau") == VoiceTarget.None, "open none");
Check(VoiceRouter.NormVoice("Mở YouTube!") == "mo youtube", "normvoice");

// ---- Ghép kênh
var groups = new List<ChannelGroup> {
    new ChannelGroup { Name = "Cà Mau", Channels = new List<Channel> { new Channel { Name = "Truyền hình Cà Mau", Url = "u218" } } },
    new ChannelGroup { Name = "HTV9", Channels = new List<Channel> { new Channel { Name = "HTV9", Url = "u10068" } } }
};
var cm = ChannelMatcher.Find("ca mau", groups);
Check(cm.HasValue && cm.Value.Gi == 0 && cm.Value.Url == "u218", "channel ca mau");
var h9 = ChannelMatcher.Find("HTV9", groups);
Check(h9.HasValue && h9.Value.Gi == 1 && h9.Value.Url == "u10068", "channel htv9");
Check(ChannelMatcher.Find("không có", groups) == null, "channel unknown");

// ---- Đọc tên kênh viết tắt
Check(ChannelMatcher.NormQuery("hát tê vê chín") == "htv9", "spoken htv9");
Check(ChannelMatcher.NormQuery("vê tê vê một") == "vtv1", "spoken vtv1");
Check(ChannelMatcher.NormQuery("a nờ tê vê") == "antv", "spoken antv");
Check(ChannelMatcher.NormQuery("Việt Nam") == "vietnam", "viet nam unchanged");
var sp = ChannelMatcher.Find("mở kênh hát tê vê chín", groups);
Check(sp.HasValue && sp.Value.Gi == 1 && sp.Value.Url == "u10068", "channel spoken htv9");

// ---- Lọc YouTube
Check(YtRules.Allowed("https://www.youtube.com/watch?v=abc"), "yt watch");
Check(YtRules.Allowed("https://youtu.be/abc"), "youtu.be");
Check(!YtRules.Allowed("https://www.youtube.com/redirect?q=x"), "yt redirect blocked");
Check(YtRules.Allowed("https://accounts.google.com/signin"), "google accounts");
Check(!YtRules.Allowed("https://example.com/"), "other host blocked");
Check(YtRules.Allowed("about:blank"), "about blank");
Check(!YtRules.Allowed("file:///C:/x.html"), "file blocked");

// ---- Điều khiển từ điện thoại
var rc = new RemoteControl();
string key = null; rc.KeyPressed += k => key = k;
rc.Handle("{\"action\":\"key\",\"key\":\"ok\"}"); Check(key == "ok", "key ok");
key = null; rc.Handle("{\"action\":\"key\",\"key\":\"bar\"}"); Check(key == null, "bar ignored");
int q = 0; rc.QualityRequested += v => q = v;
rc.Handle("{\"action\":\"quality\",\"q\":720}"); Check(q == 720, "quality");
bool? tl = null; rc.TouchLockChanged += on => tl = on;
rc.Handle("{\"action\":\"touchlock\",\"on\":true}"); Check(tl == true, "touchlock");
var bl = new List<int>(); rc.BrightnessChanged += bl.Add;
rc.Handle("{\"action\":\"brightness\",\"level\":50}"); rc.Handle("{\"action\":\"brightness\",\"level\":150}");
Check(bl.SequenceEqual(new[] { 50 }), "brightness range");
string vq = null; rc.VoiceQuery += v => vq = v;
rc.Handle("{\"action\":\"voice\",\"q\":\"mở TV\"}"); Check(vq == "mở TV", "voice");
int back = 0, home = 0; rc.BackPressed += () => back++; rc.HomePressed += () => home++;
for (int i = 0; i < 3; i++) rc.Handle("{\"action\":\"back\"}");
Check(back == 2 && home == 1, $"back burst back={back} home={home}");
bool threw = false; try { rc.Handle("không phải json"); } catch { threw = true; }
Check(!threw, "invalid json ignored");
InputInjector.Calls.Clear();
rc.Handle("{\"action\":\"move\",\"dx\":10,\"dy\":-5}");
Check(InputInjector.Calls.Count == 1 && InputInjector.Calls[0] == "cursor 116,92", $"move math: {string.Join(";", InputInjector.Calls)}");
rc.Handle("{\"action\":\"click\"}");
Check(InputInjector.Calls.Last() == "click", "click");
rc.Handle("{\"action\":\"volume\",\"level\":50}");
Check(InputInjector.Calls.Last() == "volume 0.50", "volume");

// ---- Tài nguyên: JS nhúng và danh sách kênh
Check(!string.IsNullOrWhiteSpace(WebScripts.YtNav) && WebScripts.YtNav.Contains("__wbNav"), "YtNav content");
Check(WebScripts.TextEditFn.Length > 100, "TextEditFn content");
Check(TvScripts.TvFull.Contains("requestFullscreen") || TvScripts.TvFull.Contains("video"), "TvFull content");

// ---- Đọc channels.json thật (Assets đặt cạnh exe)
var loaded = ChannelRepository.Load();
Check(loaded.Count > 0 && loaded.All(g => g.Channels.Count > 0), $"channels.json groups={loaded.Count}");
Check(loaded.Any(g => g.Name == "Việt Nam" && g.Channels.Any(c => c.Name == "VTV1")), "channels.json has VTV1");
Console.WriteLine($"channels.json: {loaded.Count} đài, {loaded.Sum(g => g.Channels.Count)} kênh");

Console.WriteLine($"\nKết quả: {pass} đạt, {fail} lỗi");
return fail == 0 ? 0 : 1;
