using System;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using Microsoft.Win32;
using NAudio.Wave;
using Windows.Media.SpeechRecognition;

namespace TiviWidget;

/// <summary>
/// Luôn bật mic của laptop và nghe "nghe nè &lt;lệnh&gt; ok".
/// Ưu tiên nhận dạng của Windows 10 (WinRT, tiếng Việt, cần bật "Online speech recognition");
/// nếu không có thì dùng System.Speech với gói tiếng Việt.
/// Windows không hỏi quyền micro cho app desktop: service tự kiểm tra các công tắc trong Settings,
/// báo đúng lỗi, mở trang cài đặt cần bật, tự thử lại, và ghi log.
/// </summary>
internal sealed class WakeVoiceService : IDisposable
{
    private const int PrivacyNotAccepted = unchecked((int)0x80045509);
    private const int AccessDenied = unchecked((int)0x80070005);
    private static readonly TimeSpan RetryEvery = TimeSpan.FromSeconds(20);

    private readonly WakeCommandParser _parser = new();
    private readonly object _lock = new();
    private SpeechRecognizer _rec;
    private System.Speech.Recognition.SpeechRecognitionEngine _sapi;
    private VoskListener _vosk;
    private volatile bool _on;
    private volatile bool _wanted;
    private volatile bool _starting;
    private DateTime _nextRetry = DateTime.MinValue;
    private string _lastSettingsOpened = "";

    public event Action<string> Command;
    public event Action<string> Status;
    /// <summary>Mọi câu nghe được (kể cả không có "nghe nè"), để thấy mic có hoạt động không.</summary>
    public event Action<string> Heard;

    public bool Enabled => _on || _wanted;

    public async Task StartAsync()
    {
        _wanted = true;
        if (_on || _starting) return;
        _starting = true;
        try
        {
            VoiceLog.Write("Bắt đầu bật nghe nền");

            var mic = CheckMicrophone();
            if (mic != null) { Fail(mic.Value.msg, mic.Value.settings); return; }

            VoiceLog.Write("Ngôn ngữ giao diện Windows: " + System.Globalization.CultureInfo.CurrentUICulture.Name);

            string why = await TryStartWinRtAsync();
            if (why == null) { Ok("Windows"); return; }
            VoiceLog.Write("WinRT không bật được: " + why);

            // Windows chưa có tiếng Việt (vd. máy để tiếng Anh): dùng bộ nhận dạng tiếng Việt kèm theo app
            string why2 = await TryStartVoskAsync();
            if (why2 == null) { Ok("offline"); return; }
            VoiceLog.Write("Vosk không bật được: " + why2);

            string why3 = TryStartSapi();
            if (why3 == null) { Ok("System.Speech"); return; }
            VoiceLog.Write("System.Speech không bật được: " + why3);

            Fail(why + " " + why2 + " " + why3, _pendingSettings);
        }
        finally { _starting = false; }
    }

    private string _pendingSettings = "ms-settings:privacy-speech";

    private void Ok(string engine)
    {
        _on = true;
        VoiceLog.Write("Nghe nền ĐANG BẬT (" + engine + ")");
        Status?.Invoke("Nghe nền đang bật (" + engine + "): nói \"nghe nè ... ok\" (Ctrl+Alt+M để tắt/bật)");
    }

    private void Fail(string msg, string settingsUri)
    {
        _on = false;
        _nextRetry = DateTime.UtcNow + RetryEvery;
        VoiceLog.Write("LỖI: " + msg);
        Status?.Invoke("Mic chưa nghe được: " + msg);
        // Chỉ tự mở trang cài đặt một lần cho mỗi loại lỗi, tránh bật liên tục
        if (!string.IsNullOrEmpty(settingsUri) && settingsUri != _lastSettingsOpened)
        {
            _lastSettingsOpened = settingsUri;
            try { Process.Start(new ProcessStartInfo(settingsUri) { UseShellExecute = true }); } catch (Exception) { }
        }
    }

    /// <summary>Tắt theo ý người dùng (không tự thử lại nữa).</summary>
    public void Stop()
    {
        _wanted = false;
        StopEngines();
    }

    private void StopEngines()
    {
        _on = false;
        var rec = _rec; _rec = null;
        if (rec != null)
        {
            Task.Run(async () =>
            {
                try { await rec.ContinuousRecognitionSession.CancelAsync(); } catch (Exception) { }
                try { rec.Dispose(); } catch (Exception) { }
            });
        }
        var vosk = _vosk; _vosk = null;
        if (vosk != null) { try { vosk.Dispose(); } catch (Exception) { } }
        var sapi = _sapi; _sapi = null;
        if (sapi != null)
        {
            try { sapi.RecognizeAsyncCancel(); sapi.Dispose(); } catch (Exception) { }
        }
        lock (_lock) _parser.Reset();
    }

    public void Dispose() => Stop();

    /// <summary>Gọi mỗi giây: huỷ lệnh quá hạn; nếu đang lỗi thì tự thử bật lại (sau khi người dùng bật công tắc).</summary>
    public void Tick()
    {
        bool timedOut;
        lock (_lock) timedOut = _parser.Tick(DateTime.UtcNow);
        if (timedOut) Status?.Invoke("Hết thời gian: không nghe thấy \"ok\", đã huỷ lệnh");

        if (_wanted && !_on && !_starting && DateTime.UtcNow >= _nextRetry)
        {
            _nextRetry = DateTime.UtcNow + RetryEvery;
            _ = StartAsync();
        }
    }

    /// <summary>Kiểm tra micro trước khi nghe. Trả về null nếu ổn.</summary>
    private static (string msg, string settings)? CheckMicrophone()
    {
        try
        {
            // Windows 10: công tắc quyền micro nằm trong registry (hộp thoại xin quyền không có với app desktop)
            const string baseKey = @"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager\ConsentStore\microphone";
            foreach (var hive in new[] { Registry.LocalMachine, Registry.CurrentUser })
            {
                using var k = hive.OpenSubKey(baseKey);
                if (k?.GetValue("Value") as string == "Deny")
                    return ("Windows đang tắt quyền micro. Bật: Settings > Privacy > Microphone > Allow apps to access your microphone.",
                            "ms-settings:privacy-microphone");
            }
            using (var k = Registry.CurrentUser.OpenSubKey(baseKey + @"\NonPackaged"))
            {
                if (k?.GetValue("Value") as string == "Deny")
                    return ("Windows đang chặn ứng dụng desktop dùng micro. Bật: Settings > Privacy > Microphone > Allow desktop apps to access your microphone.",
                            "ms-settings:privacy-microphone");
            }
        }
        catch (Exception ex) { VoiceLog.Write("Không đọc được quyền micro: " + ex.Message); }

        try
        {
            int n = WaveInEvent.DeviceCount;
            VoiceLog.Write("Số micro tìm thấy: " + n);
            if (n == 0)
                return ("Không thấy micro nào. Cắm micro hoặc bật micro trong Settings > System > Sound > Input.",
                        "ms-settings:sound");
        }
        catch (Exception ex) { VoiceLog.Write("Không liệt kê được micro: " + ex.Message); }
        return null;
    }

    private void OnPhrase(string text)
    {
        VoiceLog.Write("Nghe: " + text);
        Heard?.Invoke(text);

        string cmd;
        bool capturing;
        lock (_lock)
        {
            cmd = _parser.Feed(text, DateTime.UtcNow);
            capturing = _parser.Capturing;
        }
        if (cmd != null)
        {
            VoiceLog.Write("LỆNH: " + cmd);
            Command?.Invoke(cmd);
        }
        else if (capturing) Status?.Invoke("Đang nghe lệnh... nói \"ok\" khi xong");
    }

    private async Task<string> TryStartWinRtAsync()
    {
        try
        {
            var tags = SpeechRecognizer.SupportedTopicLanguages.Select(l => l.LanguageTag).ToArray();
            VoiceLog.Write("Ngôn ngữ nhận dạng của Windows: " + string.Join(", ", tags));
            var lang = SpeechRecognizer.SupportedTopicLanguages
                .FirstOrDefault(l => l.LanguageTag.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
            if (lang == null)
            {
                _pendingSettings = "ms-settings:privacy-speech";
                return "Windows chưa có nhận dạng giọng nói tiếng Việt: bật Settings > Privacy > Speech > Online speech recognition và cài tiếng Việt ở Time & language > Speech.";
            }

            var rec = new SpeechRecognizer(lang);
            rec.Constraints.Add(new SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.Dictation, "dictation"));
            var comp = await rec.CompileConstraintsAsync();
            if (comp.Status != SpeechRecognitionResultStatus.Success)
            {
                rec.Dispose();
                _pendingSettings = "ms-settings:privacy-speech";
                return "Không nạp được nhận dạng giọng nói (" + comp.Status + "): kiểm tra Online speech recognition.";
            }

            var session = rec.ContinuousRecognitionSession;
            session.ResultGenerated += (_, a) =>
            {
                if (a.Result.Confidence == SpeechRecognitionConfidence.Rejected) return;
                var t = a.Result.Text;
                if (!string.IsNullOrWhiteSpace(t)) OnPhrase(t);
            };
            // Phiên tự dừng khi im lặng lâu: mở lại để nghe liên tục
            session.Completed += async (_, e) =>
            {
                VoiceLog.Write("Phiên nghe kết thúc: " + e.Status + ", mở lại");
                while (_on && ReferenceEquals(_rec, rec))
                {
                    await Task.Delay(700);
                    try { await session.StartAsync(); return; }
                    catch (Exception ex) { VoiceLog.Write("Mở lại phiên lỗi: " + ex.Message); await Task.Delay(3000); }
                }
            };

            _rec = rec;
            await session.StartAsync();
            return null;
        }
        catch (Exception ex) when (ex.HResult == PrivacyNotAccepted)
        {
            _rec = null;
            _pendingSettings = "ms-settings:privacy-speech";
            return "Cần bật Settings > Privacy > Speech > Online speech recognition.";
        }
        catch (Exception ex) when (ex.HResult == AccessDenied || ex is UnauthorizedAccessException)
        {
            _rec = null;
            _pendingSettings = "ms-settings:privacy-microphone";
            return "Windows từ chối quyền micro: bật Settings > Privacy > Microphone > Allow desktop apps to access your microphone.";
        }
        catch (Exception ex)
        {
            _rec = null;
            return "WinRT: " + ex.Message + ".";
        }
    }

    private async Task<string> TryStartVoskAsync()
    {
        var v = new VoskListener();
        var err = await v.StartAsync(OnPhrase, m => Status?.Invoke(m));
        if (err != null) { v.Dispose(); return err; }
        _vosk = v;
        return null;
    }

    private string TryStartSapi()
    {
        try
        {
            var info = System.Speech.Recognition.SpeechRecognitionEngine.InstalledRecognizers()
                .FirstOrDefault(r => r.Culture.Name.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
            if (info == null) return "System.Speech cũng chưa có gói tiếng Việt.";

            var eng = new System.Speech.Recognition.SpeechRecognitionEngine(info);
            eng.SetInputToDefaultAudioDevice();
            eng.LoadGrammar(new System.Speech.Recognition.DictationGrammar());
            eng.SpeechRecognized += (_, e) =>
            {
                if (e.Result != null && !string.IsNullOrWhiteSpace(e.Result.Text)) OnPhrase(e.Result.Text);
            };
            eng.RecognizeAsync(System.Speech.Recognition.RecognizeMode.Multiple);
            _sapi = eng;
            return null;
        }
        catch (Exception ex)
        {
            return "System.Speech: " + ex.Message + ".";
        }
    }
}
