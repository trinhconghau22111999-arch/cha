using System;
using System.Linq;
using System.Threading.Tasks;
using Windows.Media.SpeechRecognition;

namespace TiviWidget;

/// <summary>
/// Luôn bật mic của laptop và nghe "nghe nè &lt;lệnh&gt; ok".
/// Ưu tiên nhận dạng của Windows 10 (WinRT, tiếng Việt, cần bật "Online speech recognition");
/// nếu không có thì dùng System.Speech với gói tiếng Việt.
/// </summary>
internal sealed class WakeVoiceService : IDisposable
{
    private const int PrivacyNotAccepted = unchecked((int)0x80045509);

    private readonly WakeCommandParser _parser = new();
    private readonly object _lock = new();
    private SpeechRecognizer _rec;
    private System.Speech.Recognition.SpeechRecognitionEngine _sapi;
    private volatile bool _on;

    public event Action<string> Command;
    public event Action<string> Status;

    public bool Enabled => _on;

    public async Task StartAsync()
    {
        if (_on) return;
        _on = true;
        string why = await TryStartWinRtAsync();
        if (why == null) { Status?.Invoke("Nghe nền đang bật: nói \"nghe nè ... ok\" (Ctrl+Alt+M để tắt/bật)"); return; }
        string why2 = TryStartSapi();
        if (why2 == null) { Status?.Invoke("Nghe nền đang bật: nói \"nghe nè ... ok\" (Ctrl+Alt+M để tắt/bật)"); return; }
        _on = false;
        Status?.Invoke("Không bật được nghe nền. " + why + " | " + why2);
    }

    public void Stop()
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
        var sapi = _sapi; _sapi = null;
        if (sapi != null)
        {
            try { sapi.RecognizeAsyncCancel(); sapi.Dispose(); } catch (Exception) { }
        }
        lock (_lock) _parser.Reset();
    }

    public void Dispose() => Stop();

    /// <summary>Gọi mỗi giây: huỷ lệnh đang gom nếu quá thời gian mà chưa nghe "ok".</summary>
    public void Tick()
    {
        bool timedOut;
        lock (_lock) timedOut = _parser.Tick(DateTime.UtcNow);
        if (timedOut) Status?.Invoke("Hết thời gian: không nghe thấy \"ok\", đã huỷ lệnh");
    }

    private void OnPhrase(string text)
    {
        string cmd;
        bool capturing;
        lock (_lock)
        {
            cmd = _parser.Feed(text, DateTime.UtcNow);
            capturing = _parser.Capturing;
        }
        if (cmd != null) Command?.Invoke(cmd);
        else if (capturing) Status?.Invoke("Đang nghe lệnh... nói \"ok\" khi xong");
    }

    private async Task<string> TryStartWinRtAsync()
    {
        try
        {
            var lang = SpeechRecognizer.SupportedTopicLanguages
                .FirstOrDefault(l => l.LanguageTag.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
            if (lang == null) return "Windows chưa có nhận dạng tiếng Việt (Settings > Time & language > Speech).";

            var rec = new SpeechRecognizer(lang);
            rec.Constraints.Add(new SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.Dictation, "dictation"));
            var comp = await rec.CompileConstraintsAsync();
            if (comp.Status != SpeechRecognitionResultStatus.Success)
            {
                rec.Dispose();
                return "Không nạp được ngữ pháp: " + comp.Status;
            }

            var session = rec.ContinuousRecognitionSession;
            session.ResultGenerated += (_, a) =>
            {
                if (a.Result.Confidence == SpeechRecognitionConfidence.Rejected) return;
                var t = a.Result.Text;
                if (!string.IsNullOrWhiteSpace(t)) OnPhrase(t);
            };
            // Phiên tự dừng khi im lặng lâu: mở lại để nghe liên tục
            session.Completed += async (_, _) =>
            {
                while (_on && ReferenceEquals(_rec, rec))
                {
                    await Task.Delay(700);
                    try { await session.StartAsync(); return; }
                    catch (Exception) { await Task.Delay(3000); }
                }
            };

            _rec = rec;
            await session.StartAsync();
            return null;
        }
        catch (Exception ex) when (ex.HResult == PrivacyNotAccepted)
        {
            _rec = null;
            return "Hãy bật Settings > Privacy > Speech > Online speech recognition.";
        }
        catch (Exception ex)
        {
            _rec = null;
            return "WinRT: " + ex.Message;
        }
    }

    private string TryStartSapi()
    {
        try
        {
            var info = System.Speech.Recognition.SpeechRecognitionEngine.InstalledRecognizers()
                .FirstOrDefault(r => r.Culture.Name.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
            if (info == null) return "System.Speech chưa có gói tiếng Việt.";

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
            return "System.Speech: " + ex.Message;
        }
    }
}
