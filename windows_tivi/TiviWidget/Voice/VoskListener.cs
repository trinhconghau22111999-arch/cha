using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using NAudio.Wave;
using Vosk;

namespace TiviWidget;

/// <summary>
/// Nhận dạng tiếng Việt chạy ngay trên máy (Vosk, không cần mạng, không phụ thuộc ngôn ngữ của Windows).
/// Chống nghe bậy bằng hai tầng:
///  1) Luôn nghe bằng bộ TỪ HẸP (từ khoá, lệnh mở, tên đài/kênh, "ok"); tiếng ồn và câu lạ bị bỏ qua.
///  2) Chỉ khi nghe ra "nghe nè" (hoặc đang gom lệnh) mới giải mã lại đúng đoạn âm thanh đó bằng bộ ĐẦY ĐỦ
///     để lấy phần nội dung tự do (vd. tên bài hát).
/// Mô hình nằm ở Assets\model-vn (bộ cài đã kèm); nếu thiếu thì tải về %LOCALAPPDATA%\TiviWidget\model-vn.
/// </summary>
internal sealed class VoskListener : IDisposable
{
    private const string ModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip";
    private const int Rate = 16000;
    private const int MaxPcmBytes = Rate * 2 * 20;   // giữ tối đa 20 giây âm thanh chưa kết thúc câu

    private static readonly string[] BaseWords =
    {
        "nghe", "nè", "né", "nẻ", "nhé", "ok", "ô", "kê", "mở", "bật", "vào", "chuyển", "sang", "qua",
        "tivi", "ti", "vi", "tv", "truyền", "hình", "youtube", "you", "tube", "du", "tup", "diu", "yu", "iu",
        "kênh", "đài", "xem", "tìm", "kiếm", "bài", "hát", "nhạc", "video", "lên", "đi", "giúp", "cho", "tôi",
        "trang", "chủ", "chọn", "quay", "về", "ra", "lại", "phát", "việt", "nam"
    };

    private readonly object _gate = new();
    private readonly MemoryStream _pcm = new();
    private Model _model;
    private VoskRecognizer _gram;
    private WaveInEvent _wave;
    private Channel<(string gram, byte[] pcm)> _queue;
    private CancellationTokenSource _cts;

    private Action<string> _onPhrase, _onHeard;
    private Func<bool> _isCapturing;

    private static string BundledDir => Path.Combine(AppContext.BaseDirectory, "Assets", "model-vn");
    private static string UserDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TiviWidget", "model-vn");

    private static bool IsModel(string dir) => Directory.Exists(Path.Combine(dir, "am"));

    /// <summary>Trả về null nếu bật được, ngược lại là lý do lỗi.</summary>
    public async Task<string> StartAsync(Action<string> onPhrase, Action<string> onHeard, Action<string> status,
                                         string[] extraWords, Func<bool> isCapturing)
    {
        _onPhrase = onPhrase; _onHeard = onHeard; _isCapturing = isCapturing;
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

            var words = BaseWords
                .Concat((extraWords ?? Array.Empty<string>())
                    .SelectMany(n => (n ?? "").ToLowerInvariant().Split(' ', StringSplitOptions.RemoveEmptyEntries))
                    .Where(w => w.All(char.IsLetter)))
                .Distinct().ToList();
            words.Add("[unk]");
            string grammar = JsonSerializer.Serialize(words);

            await Task.Run(() =>
            {
                try { Vosk.Vosk.SetLogLevel(-1); } catch (Exception) { }
                _model = new Model(dir);
                _gram = new VoskRecognizer(_model, Rate, grammar);
            });
            VoiceLog.Write("Đã nạp mô hình Vosk: " + dir + " (bộ từ hẹp " + words.Count + " từ)");

            _queue = Channel.CreateUnbounded<(string, byte[])>();
            _cts = new CancellationTokenSource();
            _ = Task.Run(() => WorkAsync(_cts.Token));

            _wave = NewWave(-1);   // -1: micro mặc định của Windows
            _wave.DataAvailable += OnData;
            _wave.RecordingStopped += (_, e) =>
            {
                if (e.Exception != null) VoiceLog.Write("Mic dừng do lỗi: " + e.Exception.Message);
            };
            try { _wave.StartRecording(); }
            catch (Exception)
            {
                // Một số máy không nhận DeviceNumber = -1: dùng micro đầu tiên
                _wave.Dispose();
                _wave = NewWave(0);
                _wave.DataAvailable += OnData;
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

    private static WaveInEvent NewWave(int device) =>
        new() { WaveFormat = new WaveFormat(Rate, 16, 1), BufferMilliseconds = 100, DeviceNumber = device };

    private void OnData(object sender, WaveInEventArgs a)
    {
        try
        {
            string json = null;
            byte[] pcm = null;
            lock (_gate)
            {
                if (_gram == null) return;
                _pcm.Write(a.Buffer, 0, a.BytesRecorded);
                if (_pcm.Length > MaxPcmBytes)
                {
                    var all = _pcm.ToArray();
                    _pcm.SetLength(0);
                    _pcm.Write(all, all.Length - MaxPcmBytes / 2, MaxPcmBytes / 2);
                }
                if (_gram.AcceptWaveform(a.Buffer, a.BytesRecorded))
                {
                    json = _gram.Result();
                    pcm = _pcm.ToArray();
                    _pcm.SetLength(0);
                }
            }
            if (json == null) return;
            var text = Clean(ParseText(json));
            if (text.Length > 0) _queue?.Writer.TryWrite((text, pcm));
        }
        catch (Exception ex) { VoiceLog.Write("Vosk lỗi xử lý âm thanh: " + ex.Message); }
    }

    private async Task WorkAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var (g, pcm) in _queue.Reader.ReadAllAsync(ct))
            {
                try { Process(g, pcm); }
                catch (Exception ex) { VoiceLog.Write("Vosk lỗi giải mã: " + ex.Message); }
            }
        }
        catch (OperationCanceledException) { }
    }

    private void Process(string g, byte[] pcm)
    {
        bool capturing = _isCapturing();
        bool gWake = WakeCommandParser.HasWake(g);

        // Bộ từ hẹp không nghe ra "nghe nè" và đang không chờ lệnh: bỏ qua (chỉ hiện để biết mic còn sống)
        if (!gWake && !capturing)
        {
            VoiceLog.Write("Bỏ qua: " + g);
            _onHeard?.Invoke(g);
            return;
        }

        string full = Clean(Decode(pcm));
        bool gEnd = WakeCommandParser.HasEnd(g);
        string text;
        if (capturing || WakeCommandParser.HasWake(full))
        {
            text = full;   // bản đầy đủ có phần nội dung tự do
            if (gEnd && !WakeCommandParser.HasEnd(full)) text += " ok";   // "ok" nghe chắc hơn ở bộ từ hẹp
        }
        else text = g;     // bản đầy đủ không nghe ra "nghe nè": tin bộ từ hẹp
        VoiceLog.Write($"hẹp='{g}' | đầy đủ='{full}' -> '{text}'");
        _onPhrase?.Invoke(text);
    }

    private string Decode(byte[] pcm)
    {
        Model m = _model;
        if (m == null || pcm == null || pcm.Length == 0) return "";
        using var r = new VoskRecognizer(m, Rate);
        const int chunk = 8000;
        for (int o = 0; o < pcm.Length; o += chunk)
            r.AcceptWaveform(pcm.AsSpan(o, Math.Min(chunk, pcm.Length - o)).ToArray(), Math.Min(chunk, pcm.Length - o));
        return ParseText(r.FinalResult());
    }

    private static string ParseText(string json)
    {
        using var doc = JsonDocument.Parse(json);
        return doc.RootElement.TryGetProperty("text", out var t) ? t.GetString() ?? "" : "";
    }

    private static string Clean(string s) =>
        string.Join(" ", (s ?? "").Replace("[unk]", " ").Split(' ', StringSplitOptions.RemoveEmptyEntries));

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
        try { _cts?.Cancel(); _queue?.Writer.TryComplete(); } catch (Exception) { }
        lock (_gate)
        {
            try { _gram?.Dispose(); } catch (Exception) { }
            _gram = null;
        }
        try { _model?.Dispose(); } catch (Exception) { }
        _model = null;
    }
}
