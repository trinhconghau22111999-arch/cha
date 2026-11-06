using System;
using System.Collections.Generic;
using System.Linq;

namespace TiviWidget;

/// <summary>
/// Máy trạng thái cho chế độ nghe nền: "nghe nè &lt;lệnh&gt; ok".
/// Chờ cụm "nghe nè", gom các từ sau đó (có thể qua nhiều câu), gặp "ok" thì trả về lệnh.
/// Giữ nguyên dấu tiếng Việt của lệnh để tìm YouTube đúng.
/// </summary>
internal sealed class WakeCommandParser
{
    private static readonly HashSet<string> EndWords = new() { "ok", "oke", "okay", "okey" };

    private readonly TimeSpan _window;
    private readonly List<string> _buf = new();
    private DateTime _deadline;

    public bool Capturing { get; private set; }

    public WakeCommandParser(TimeSpan? window = null) => _window = window ?? TimeSpan.FromSeconds(15);

    /// <summary>Đưa một câu nhận dạng vào. Trả về lệnh khi đã đủ "nghe nè ... ok", ngược lại null.</summary>
    public string Feed(string text, DateTime now)
    {
        if (Capturing && now > _deadline) Reset();
        if (string.IsNullOrWhiteSpace(text)) return null;

        var toks = text.Split(new[] { ' ', '\t', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
        int i = 0;
        if (!Capturing)
        {
            int w = FindWake(toks);
            if (w < 0) return null;
            Capturing = true;
            _deadline = now + _window;
            _buf.Clear();
            i = w + 2;
        }

        for (; i < toks.Length; i++)
        {
            if (IsEnd(toks, i))
            {
                var cmd = string.Join(" ", _buf).Trim();
                Reset();
                return cmd.Length > 0 ? cmd : null;
            }
            _buf.Add(toks[i]);
        }
        return null;
    }

    /// <summary>Gọi định kỳ. Trả về true nếu vừa hết thời gian chờ "ok" và đã huỷ lệnh đang gom.</summary>
    public bool Tick(DateTime now)
    {
        if (!Capturing || now <= _deadline) return false;
        Reset();
        return true;
    }

    public void Reset()
    {
        Capturing = false;
        _buf.Clear();
    }

    /// <summary>"ok", "oke", hoặc nhận dạng thành "ô kê" (hai từ).</summary>
    private static bool IsEnd(string[] toks, int i)
    {
        var k = Key(toks[i]);
        if (EndWords.Contains(k)) return true;
        if (k == "o" && i + 1 < toks.Length)
        {
            var n = Key(toks[i + 1]);
            return n == "ke" || n == "key" || n == "kay";
        }
        return false;
    }

    private static int FindWake(string[] toks)
    {
        for (int i = 0; i + 1 < toks.Length; i++)
            if (Key(toks[i]) == "nghe" && IsWakeTail(Key(toks[i + 1]))) return i;
        return -1;
    }

    // "nè" hay bị nhận dạng thành "né/nẻ/nhé"
    private static bool IsWakeTail(string k) => k == "ne" || k == "nhe";

    private static string Key(string tok) =>
        string.Concat(VoiceText.Fold(tok).Where(char.IsLetterOrDigit));
}
