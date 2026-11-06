using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using NAudio.Wave;
using Vosk;

namespace TiviWidget;

/// <summary>
/// Nhận dạng tiếng Việt chạy ngay trên máy (Vosk, không cần mạng, không phụ thuộc ngôn ngữ của Windows).
/// Mic lấy bằng NAudio 16 kHz mono. Mô hình nằm ở Assets\model-vn (bộ cài đã kèm); nếu thiếu thì tải về
/// %LOCALAPPDATA%\TiviWidget\model-vn ở lần chạy đầu.
/// </summary>
internal sealed class VoskListener : IDisposable
{
    private const string ModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip";

    private readonly object _gate = new();
    private Model _model;
    private VoskRecognizer _rec;
    private WaveInEvent _wave;

    private static string BundledDir => Path.Combine(AppContext.BaseDirectory, "Assets", "model-vn");
    private static string UserDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TiviWidget", "model-vn");

    private static bool IsModel(string dir) => Directory.Exists(Path.Combine(dir, "am"));

    /// <summary>Trả về null nếu bật được, ngược lại là lý do lỗi.</summary>
    public async Task<string> StartAsync(Action<string> onPhrase, Action<string> status)
    {
        try
        {
            string dir = IsModel(BundledDir) ? BundledDir : IsModel(UserDir) ? UserDir : null;
            if (dir == null)
            {
                status?.Invoke("Đang tải mô hình giọng nói tiếng Việt (~35 MB), lần đầu thôi...");
                VoiceLog.Write("Tải mô hình Vosk về " + UserDir);
                await DownloadModelAsync();
                dir = UserDir;
            }

            await Task.Run(() =>
            {
                try { Vosk.Vosk.SetLogLevel(-1); } catch (Exception) { }
                _model = new Model(dir);
                _rec = new VoskRecognizer(_model, 16000f);
            });
            VoiceLog.Write("Đã nạp mô hình Vosk: " + dir);

            _wave = new WaveInEvent { WaveFormat = new WaveFormat(16000, 16, 1), BufferMilliseconds = 100 };
            try { _wave.DeviceNumber = -1; }   // micro mặc định của Windows
            catch (Exception) { }
            _wave.DataAvailable += (_, a) =>
            {
                try
                {
                    string json = null;
                    lock (_gate)
                    {
                        if (_rec == null) return;
                        if (_rec.AcceptWaveform(a.Buffer, a.BytesRecorded)) json = _rec.Result();
                    }
                    if (json == null) return;
                    using var doc = JsonDocument.Parse(json);
                    if (doc.RootElement.TryGetProperty("text", out var t))
                    {
                        var text = t.GetString();
                        if (!string.IsNullOrWhiteSpace(text)) onPhrase(text);
                    }
                }
                catch (Exception ex) { VoiceLog.Write("Vosk lỗi xử lý âm thanh: " + ex.Message); }
            };
            _wave.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null) VoiceLog.Write("Mic dừng do lỗi: " + e.Exception.Message);
            };

            try { _wave.StartRecording(); }
            catch (Exception)
            {
                // Một số máy không nhận DeviceNumber = -1: dùng micro đầu tiên
                _wave.Dispose();
                _wave = new WaveInEvent { WaveFormat = new WaveFormat(16000, 16, 1), BufferMilliseconds = 100, DeviceNumber = 0 };
                _wave.DataAvailable += (_, a) =>
                {
                    try
                    {
                        string json = null;
                        lock (_gate)
                        {
                            if (_rec == null) return;
                            if (_rec.AcceptWaveform(a.Buffer, a.BytesRecorded)) json = _rec.Result();
                        }
                        if (json == null) return;
                        using var doc = JsonDocument.Parse(json);
                        if (doc.RootElement.TryGetProperty("text", out var t) && !string.IsNullOrWhiteSpace(t.GetString()))
                            onPhrase(t.GetString());
                    }
                    catch (Exception ex) { VoiceLog.Write("Vosk lỗi xử lý âm thanh: " + ex.Message); }
                };
                _wave.StartRecording();
            }
            return null;
        }
        catch (Exception ex)
        {
            Dispose();
            return "Vosk: " + ex.Message + ".";
        }
    }

    private static async Task DownloadModelAsync()
    {
        string tmpZip = Path.Combine(Path.GetTempPath(), "vosk-model-vn.zip");
        string tmpDir = Path.Combine(Path.GetTempPath(), "vosk-model-vn-extract");
        using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) })
        using (var resp = await http.GetAsync(ModelUrl, HttpCompletionOption.ResponseHeadersRead))
        {
            resp.EnsureSuccessStatusCode();
            await using var fs = File.Create(tmpZip);
            await resp.Content.CopyToAsync(fs);
        }
        if (Directory.Exists(tmpDir)) Directory.Delete(tmpDir, true);
        ZipFile.ExtractToDirectory(tmpZip, tmpDir);
        var inner = Directory.GetDirectories(tmpDir).FirstOrDefault(IsModel)
                    ?? throw new InvalidOperationException("Gói mô hình tải về không đúng định dạng");
        Directory.CreateDirectory(Path.GetDirectoryName(UserDir)!);
        if (Directory.Exists(UserDir)) Directory.Delete(UserDir, true);
        Directory.Move(inner, UserDir);
        try { File.Delete(tmpZip); Directory.Delete(tmpDir, true); } catch (Exception) { }
    }

    public void Dispose()
    {
        try { _wave?.StopRecording(); } catch (Exception) { }
        try { _wave?.Dispose(); } catch (Exception) { }
        _wave = null;
        lock (_gate)
        {
            try { _rec?.Dispose(); } catch (Exception) { }
            try { _model?.Dispose(); } catch (Exception) { }
            _rec = null;
            _model = null;
        }
    }
}
