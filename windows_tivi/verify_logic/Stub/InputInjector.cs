using System.Collections.Generic;
namespace TiviWidget;
internal static class InputInjector
{
    public static readonly List<string> Calls = new();
    public static int ScreenWidth => 1920;
    public static int ScreenHeight => 1080;
    public static bool GetCursor(out int x, out int y) { x = 100; y = 100; return true; }
    public static void SetCursor(int x, int y) => Calls.Add($"cursor {x},{y}");
    public static void Click() => Calls.Add("click");
    public static void Wheel(uint delta) => Calls.Add($"wheel {delta}");
    public static void Key(byte vk) => Calls.Add($"key {vk}");
    public static void ScreenOff() => Calls.Add("screenoff");
    public static void ScreenOn() => Calls.Add("screenon");
    public static void SetVolume(float s) => Calls.Add($"volume {s:0.00}");
    public static void KeepAwake(bool on) => Calls.Add($"keepawake {on}");
}
