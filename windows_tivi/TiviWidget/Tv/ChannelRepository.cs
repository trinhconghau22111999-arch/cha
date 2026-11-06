using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace TiviWidget;

public class Channel
{
    public string Name { get; set; } = "";
    public string Url { get; set; } = "";
}

public class ChannelGroup
{
    public string Name { get; set; } = "";
    public List<Channel> Channels { get; set; } = new();
}

internal class ChannelsFile
{
    public List<ChannelGroup> Groups { get; set; } = new();
}

/// <summary>Đọc Assets/channels.json (cùng định dạng với bản Android).</summary>
public static class ChannelRepository
{
    public static List<ChannelGroup> Load()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Assets", "channels.json");
        if (!File.Exists(path)) return new List<ChannelGroup>();
        var opts = new JsonSerializerOptions { PropertyNameCaseInsensitive = true };
        var file = JsonSerializer.Deserialize<ChannelsFile>(File.ReadAllText(path), opts);
        var groups = file?.Groups ?? new List<ChannelGroup>();
        groups.RemoveAll(g => g.Channels == null || g.Channels.Count == 0);
        return groups;
    }
}
