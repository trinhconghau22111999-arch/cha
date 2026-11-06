using System;

namespace TiviWidget;

/// <summary>Port allowedPage() và isYoutubeHost() của YouTubePane (Panes.kt).</summary>
internal static class YtRules
{
    public static bool IsYoutubeHost(string h) =>
        h == "youtube.com" || h.EndsWith(".youtube.com") ||
        h == "youtu.be" ||
        h == "youtube-nocookie.com" || h.EndsWith(".youtube-nocookie.com");

    public static bool Allowed(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return false;
        if (u.Scheme == "about") return true;
        if (u.Scheme != "https" && u.Scheme != "http") return false;
        var h = u.Host.ToLowerInvariant();
        if (IsYoutubeHost(h)) return !u.AbsolutePath.StartsWith("/redirect", StringComparison.OrdinalIgnoreCase);
        return h.StartsWith("accounts.google.") || h.StartsWith("consent.google.");
    }
}
