using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;

namespace TiviWidget;

internal enum VoiceTarget { None, Tv, YouTube }

/// <summary>Câu ghép "mở YouTube bài X" / "mở TV kênh HTV9": app cần mở và phần nội dung còn lại.</summary>
internal readonly record struct VoiceSwitch(VoiceTarget Mode, string Rest);

/// <summary>Chuẩn hoá tiếng Việt (bỏ dấu, chữ thường).</summary>
internal static class VoiceText
{
    internal static readonly HashSet<string> ChannelSkip = new()
        { "mo", "kenh", "dai", "truyen", "hinh", "xem", "cho", "toi", "tv", "len", "bat" };

    /// <summary>Bỏ dấu, chữ thường, đ -> d (port normVoice + normName của Android).</summary>
    public static string Fold(string s)
    {
        var d = s.ToLowerInvariant().Replace('đ', 'd').Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(d.Length);
        foreach (var c in d)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(c);
        return sb.ToString();
    }

    public static string[] Words(string folded) =>
        Regex.Split(folded, "[^a-z0-9]+").Where(w => w.Length > 0).ToArray();
}

/// <summary>Định tuyến lệnh giọng nói, port từ MainActivity.kt (voiceOpenCommand, voiceSwitchAndQuery).</summary>
internal static class VoiceRouter
{
    private const string OpenWord = "(?:mo|bat|vao|open|chuyen sang|chuyen qua|di toi|quay ve)";
    private static readonly Regex OpenTv = new(
        "^" + OpenWord + " (?:ra |lai )?(?:trang chon kenh |trang chu |man hinh )?(?:tv|ti vi|tivi|truyen hinh|kenh tv)(?: len| di| giup| cho toi)?$");
    private static readonly Regex OpenYt = new(
        "^" + OpenWord + " (?:ra |lai )?(?:trang chu )?(?:youtube|you tube|du tup|diu tup|yu tup|iu tup)(?: len| di| giup| cho toi)?$");

    private static readonly HashSet<string> Prefix = new()
        { "mo", "bat", "vao", "open", "chuyen", "sang", "qua", "di", "toi", "quay", "ve", "ra", "lai", "trang", "chu", "man", "hinh", "chon", "kenh" };
    private static readonly HashSet<string> YtFill = new()
        { "va", "roi", "mo", "phat", "nghe", "tim", "kiem", "xem", "bai", "hat", "nhac", "video", "clip", "cua", "ten", "cho", "toi", "len", "tren", "di" };
    private static readonly HashSet<string> TvFill = new()
        { "va", "roi", "mo", "bat", "xem", "kenh", "dai", "truyen", "hinh", "tren", "cho", "toi", "len", "di", "nhe" };

    private static readonly string[][] YtKeys =
    {
        new[] { "youtube" }, new[] { "you", "tube" }, new[] { "du", "tup" },
        new[] { "diu", "tup" }, new[] { "yu", "tup" }, new[] { "iu", "tup" }
    };
    private static readonly string[][] TvKeys =
    {
        new[] { "tv" }, new[] { "tivi" }, new[] { "ti", "vi" }, new[] { "truyen", "hinh" }
    };

    /// <summary>Chuẩn hoá câu nói: bỏ dấu, bỏ dấu câu, gộp khoảng trắng.</summary>
    public static string NormVoice(string q)
    {
        var s = VoiceText.Fold(q);
        s = Regex.Replace(s, "[^a-z0-9 ]", " ");
        s = Regex.Replace(s, "\\s+", " ");
        return s.Trim();
    }

    /// <summary>Chỉ có lệnh mở ("mở TV", "mở YouTube") không kèm nội dung.</summary>
    public static VoiceTarget OpenCommand(string q)
    {
        var n = NormVoice(q);
        if (OpenTv.IsMatch(n)) return VoiceTarget.Tv;
        if (OpenYt.IsMatch(n)) return VoiceTarget.YouTube;
        return VoiceTarget.None;
    }

    /// <summary>Câu ghép: "mở YouTube mở bài X", "mở TV kênh HTV9". Trả null nếu không phải.</summary>
    public static VoiceSwitch? SwitchAndQuery(string q)
    {
        var words = new List<string>();
        var nw = new List<string>();
        foreach (var w0 in Regex.Split(q.Trim(), "\\s+"))
        {
            if (w0.Length == 0) continue;
            var k = NormVoice(w0).Replace(" ", "");
            if (k.Length == 0) continue;
            words.Add(w0.Trim(',', '.', '!', '?', ';', '"', '\''));
            nw.Add(k);
        }

        (int Pos, int Len)? Find(string[][] keys)
        {
            for (int i = 0; i < nw.Count; i++)
                foreach (var key in keys)
                    if (i + key.Length <= nw.Count && key.Select((t, j) => nw[i + j] == t).All(b => b))
                        return (i, key.Length);
            return null;
        }

        var yt = Find(YtKeys);
        var tvk = Find(TvKeys);
        VoiceTarget mode;
        (int Pos, int Len) hit;
        if (yt != null && (tvk == null || yt.Value.Pos <= tvk.Value.Pos)) { mode = VoiceTarget.YouTube; hit = yt.Value; }
        else if (tvk != null) { mode = VoiceTarget.Tv; hit = tvk.Value; }
        else return null;

        if (!nw.Take(hit.Pos).All(Prefix.Contains)) return null;

        int a = hit.Pos + hit.Len;
        var fill = mode == VoiceTarget.YouTube ? YtFill : TvFill;
        while (a < nw.Count && fill.Contains(nw[a])) a++;
        if (a >= nw.Count) return null;   // chỉ có lệnh mở, không kèm nội dung

        return new VoiceSwitch(mode, string.Join(" ", words.Skip(a)));
    }
}

/// <summary>Tìm kênh/đài theo tên đã nói (port playByName của TvPane).</summary>
internal static class ChannelMatcher
{
    public static string Norm(string t) =>
        string.Concat(VoiceText.Words(VoiceText.Fold(t)).Where(w => !VoiceText.ChannelSkip.Contains(w)));

    public static (int Gi, string Url)? Find(string q, IList<ChannelGroup> groups)
    {
        string nq = Norm(q);
        if (nq.Length == 0) return null;

        int best = 0, bestGi = -1, bestDiff = int.MaxValue;
        string bestUrl = "";
        for (int gi = 0; gi < groups.Count; gi++)
        {
            foreach (var ch in groups[gi].Channels)
            {
                foreach (var cand in new[] { ch.Name, groups[gi].Name })
                {
                    var n = Norm(cand);
                    if (n.Length == 0) continue;
                    int score = n == nq ? 3 : (n.Contains(nq) || nq.Contains(n)) ? 2 : 0;
                    int diff = Math.Abs(n.Length - nq.Length);
                    if (score > best || (score == best && score > 0 && diff < bestDiff))
                    {
                        best = score; bestGi = gi; bestUrl = ch.Url; bestDiff = diff;
                    }
                }
            }
        }
        if (best == 0) return null;
        return (bestGi, bestUrl);
    }
}
