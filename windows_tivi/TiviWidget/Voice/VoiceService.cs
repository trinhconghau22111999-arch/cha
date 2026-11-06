using System;
using System.Linq;
using System.Speech.Recognition;

namespace TiviWidget;

/// <summary>Nhận giọng nói tiếng Việt bằng System.Speech (cần gói ngôn ngữ tiếng Việt của Windows).</summary>
internal sealed class VoiceService : IDisposable
{
    private SpeechRecognitionEngine _engine;

    public event Action<string> Recognized;
    public event Action<string> Status;

    public void Start()
    {
        var info = SpeechRecognitionEngine.InstalledRecognizers()
            .FirstOrDefault(r => r.Culture.Name.StartsWith("vi", StringComparison.OrdinalIgnoreCase));
        if (info == null)
        {
            Status?.Invoke("Chưa có nhận dạng tiếng Việt: Settings > Time & language > Speech");
            return;
        }

        Dispose();
        _engine = new SpeechRecognitionEngine(info);
        _engine.SetInputToDefaultAudioDevice();
        _engine.LoadGrammar(new DictationGrammar());
        _engine.SpeechRecognized += (_, e) =>
        {
            if (e.Result != null && !string.IsNullOrWhiteSpace(e.Result.Text))
                Recognized?.Invoke(e.Result.Text);
        };
        _engine.RecognizeCompleted += (_, e) =>
        {
            if (e.Error != null) Status?.Invoke("Lỗi nhận giọng nói: " + e.Error.Message);
        };
        _engine.RecognizeAsync(RecognizeMode.Single);
        Status?.Invoke("Đang nghe... (Ctrl+M)");
    }

    public void Dispose()
    {
        if (_engine == null) return;
        try { _engine.RecognizeAsyncCancel(); _engine.Dispose(); } catch (Exception) { }
        _engine = null;
    }
}
