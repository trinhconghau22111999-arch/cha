using System;
using System.Runtime.InteropServices;
using System.Text;
using NAudio.CoreAudioApi;

namespace TiviWidget;

/// <summary>Thao tác hệ thống: chuột, phím đa phương tiện, màn hình, âm lượng, giữ màn hình sáng.</summary>
internal static class InputInjector
{
    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern IntPtr WindowFromPoint(POINT p);
    [DllImport("user32.dll")] private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] private static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] private static extern uint SetThreadExecutionState(uint flags);

    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint KEYEVENTF_KEYUP = 0x0002;
    private const uint WM_SYSCOMMAND = 0x0112;
    private const uint SC_MONITORPOWER = 0xF170;
    private static readonly IntPtr HwndBroadcast = new(0xFFFF);
    private const uint ES_CONTINUOUS = 0x80000000;
    private const uint ES_SYSTEM_REQUIRED = 0x00000001;
    private const uint ES_DISPLAY_REQUIRED = 0x00000002;

    public static int ScreenWidth => GetSystemMetrics(0);
    public static int ScreenHeight => GetSystemMetrics(1);

    public static bool GetCursor(out int x, out int y)
    {
        bool ok = GetCursorPos(out POINT p);
        x = p.X; y = p.Y;
        return ok;
    }

    public static void SetCursor(int x, int y) => SetCursorPos(x, y);

    /// <summary>
    /// true nếu dưới con trỏ là lớp nền desktop (widget nằm sau lớp icon nên chuột hệ thống không tới được widget)
    /// hoặc chính cửa sổ widget. false nếu có app khác (hoặc taskbar) ở dưới con trỏ: khi đó dùng chuột hệ thống bình thường.
    /// </summary>
    public static bool CursorOverDesktop(IntPtr self)
    {
        if (!GetCursorPos(out POINT p)) return true;
        IntPtr h = WindowFromPoint(p);
        if (h == IntPtr.Zero) return true;
        IntPtr root = GetAncestor(h, 2);   // GA_ROOT
        if (root == IntPtr.Zero) root = h;
        if (self != IntPtr.Zero && root == self) return true;
        var sb = new StringBuilder(64);
        GetClassName(root, sb, sb.Capacity);
        string c = sb.ToString();
        return c == "Progman" || c == "WorkerW";
    }

    public static void Click()
    {
        mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
        mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
    }

    public static void Wheel(uint delta) => mouse_event(MOUSEEVENTF_WHEEL, 0, 0, delta, UIntPtr.Zero);

    /// <summary>Nhấn và thả một phím ảo (vd. phím media).</summary>
    public static void Key(byte vk)
    {
        keybd_event(vk, 0, 0, UIntPtr.Zero);
        keybd_event(vk, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
    }

    /// <summary>Tắt màn hình (monitor power off).</summary>
    public static void ScreenOff() => SendMessage(HwndBroadcast, WM_SYSCOMMAND, new IntPtr(SC_MONITORPOWER), new IntPtr(2));

    /// <summary>Bật lại màn hình.</summary>
    public static void ScreenOn() => SendMessage(HwndBroadcast, WM_SYSCOMMAND, new IntPtr(SC_MONITORPOWER), new IntPtr(-1));

    /// <summary>Âm lượng hệ thống 0..1 (cùng vai trò với setStreamVolume của Android).</summary>
    public static void SetVolume(float scalar)
    {
        using var enumerator = new MMDeviceEnumerator();
        using var device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        device.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(scalar, 0f, 1f);
    }

    /// <summary>Giữ máy/màn hình không tự ngủ khi đang có camera hoặc remote cần (keepAwake của bản Android).</summary>
    public static void KeepAwake(bool on)
    {
        SetThreadExecutionState(on ? (ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED) : ES_CONTINUOUS);
    }
}
