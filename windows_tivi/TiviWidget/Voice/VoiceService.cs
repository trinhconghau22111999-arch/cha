using System;
using System.Diagnostics;
using System.Linq;
using Windows.Media.SpeechRecognition;

namespace TiviWidget;

/// <summary>Ctrl+M: nói một lệnh. Dùng nhận dạng giọng nói trực tuyến của Windows (tiếng Việt).</summary>
internal sealed class VoiceService : IDisposable
{
    private const int PrivacyNotAccepted = unchecked((int)0x80045509);
    private const int AccessDenied = unchecked((int)0x80070005);

    private SpeechRecognizer _rec;
    private volatile bool _busy;

    public event Action<string> Recognized;
    public event Action<string> Status;

    public async void Start()
    {
        if (_busy) return;
        _busy = true;
        try
        {
            var lang = SpeechRecognizer.SupportedTopicLanguages
                .FirstOrDefault(l => l.LanguageTag.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
            if (lang == null)
            {
                Status?.Invoke("Windows chưa có giọng nói tiếng Việt: bật Online speech recognition và cài tiếng Việt (Settings > Time & language > Speech)");
                OpenSettings("ms-settings:privacy-speech");
                return;
            }

            _rec = new SpeechRecognizer(lang);
            _rec.Constraints.Add(new SpeechRecognitionTopicConstraint(SpeechRecognitionScenario.Dictation, "dictation"));
            var comp = await _rec.CompileConstraintsAsync();
            if (comp.Status != SpeechRecognitionResultStatus.Success)
            {
                Status?.Invoke("Không nạp được nhận dạng giọng nói: " + comp.Status);
                return;
            }

            Status?.Invoke("Đang nghe... (Ctrl+M)");
            var res = await _rec.RecognizeAsync();
            if (res.Status == SpeechRecognitionResultStatus.Success
                && res.Confidence != SpeechRecognitionConfidence.Rejected
                && !string.IsNullOrWhiteSpace(res.Text))
                Recognized?.Invoke(res.Text);
            else
                Status?.Invoke("Không nghe rõ, thử lại (Ctrl+M)");
        }
        catch (Exception ex) when (ex.HResult == PrivacyNotAccepted)
        {
            Status?.Invoke("Cần bật Settings > Privacy > Speech > Online speech recognition");
            OpenSettings("ms-settings:privacy-speech");
        }
        catch (Exception ex) when (ex.HResult == AccessDenied || ex is UnauthorizedAccessException)
        {
            Status?.Invoke("Windows từ chối quyền micro: bật Settings > Privacy > Microphone");
            OpenSettings("ms-settings:privacy-microphone");
        }
        catch (Exception ex)
        {
            Status?.Invoke("Lỗi nhận giọng nói: " + ex.Message);
        }
        finally
        {
            try { _rec?.Dispose(); } catch (Exception) { }
            _rec = null;
            _busy = false;
        }
    }

    private static void OpenSettings(string uri)
    {
        try { Process.Start(new ProcessStartInfo(uri) { UseShellExecute = true }); } catch (Exception) { }
    }

    public void Dispose()
    {
        try { _rec?.Dispose(); } catch (Exception) { }
        _rec = null;
    }
}
