using System;
using System.IO;

namespace TiviWidget;

/// <summary>Nhật ký nghe nền: %LOCALAPPDATA%\TiviWidget\voice.log (để biết vì sao mic không hoạt động).</summary>
internal static class VoiceLog
{
    private static readonly object L = new();
    public static string PathName =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TiviWidget", "voice.log");

    public static void Write(string msg)
    {
        try
        {
            lock (L)
            {
                var p = PathName;
                Directory.CreateDirectory(Path.GetDirectoryName(p)!);
                if (File.Exists(p) && new FileInfo(p).Length > 200_000) File.Delete(p);
                File.AppendAllText(p, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + "  " + msg + Environment.NewLine);
            }
        }
        catch (Exception) { }
    }
}
