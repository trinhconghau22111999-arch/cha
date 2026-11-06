using System;
using System.Collections.Generic;
using System.IO;
using Microsoft.Web.WebView2.Core;

namespace TiviWidget;

/// <summary>
/// Chặn quảng cáo ở tầng mạng: request tới domain trong danh sách được trả về rỗng (204),
/// khớp domain chính xác hoặc mọi subdomain. Cùng logic với isAdHost() bên Android.
/// </summary>
internal static class AdBlocker
{
    private static readonly string[] BuiltIn =
    {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    };

    private static readonly HashSet<string> Hosts = new(StringComparer.OrdinalIgnoreCase);

    static AdBlocker()
    {
        foreach (var h in BuiltIn) Hosts.Add(h);
        var path = Path.Combine(AppContext.BaseDirectory, "Assets", "blocklist.txt");
        if (!File.Exists(path)) return;
        foreach (var line in File.ReadAllLines(path))
        {
            var t = line.Trim();
            if (t.Length == 0 || t.StartsWith("#")) continue;
            Hosts.Add(t);
        }
    }

    public static void Attach(CoreWebView2 core)
    {
        core.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
        core.WebResourceRequested += (_, e) =>
        {
            var host = HostOf(e.Request.Uri);
            if (host != null && IsBlocked(host))
                e.Response = core.Environment.CreateWebResourceResponse(new MemoryStream(), 204, "No Content", "");
        };
    }

    private static bool IsBlocked(string host)
    {
        var x = host;
        while (true)
        {
            if (Hosts.Contains(x)) return true;
            int i = x.IndexOf('.');
            if (i < 0) return false;
            x = x.Substring(i + 1);
        }
    }

    private static string HostOf(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.Host.ToLowerInvariant() : null;
}
