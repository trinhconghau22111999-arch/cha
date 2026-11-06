using System;
using System.Runtime.InteropServices;
using System.Text;

namespace TiviWidget;

/// <summary>
/// Gắn cửa sổ vào lớp nền desktop (WorkerW), phủ màn hình chính khi không có app nào
/// trên màn hình chính, và thu nhỏ / ẩn khi có app.
/// </summary>
internal static class DesktopHost
{
    // ===== Cấu hình hành vi (Bước 5) =====
    /// <summary>true: có app trên màn hình chính thì ẩn hẳn widget. false: thu nhỏ về góc phải dưới (mặc định).</summary>
    public const bool HideWhenAppsOpen = false;
    public const int CompactWidth = 480;     // pixel vật lý
    public const int CompactHeight = 270;
    public const int CompactMargin = 16;

    private const uint WM_SPAWN_WORKER = 0x052C;
    private const uint SMTO_NORMAL = 0x0000;
    private const int SM_CXSCREEN = 0;
    private const int SM_CYSCREEN = 1;
    private const uint SWP_NOZORDER = 0x0004;
    private const uint SWP_NOACTIVATE = 0x0010;
    private const uint SWP_SHOWWINDOW = 0x0040;
    private const uint SPI_GETWORKAREA = 0x0030;
    private const int GWL_EXSTYLE = -20;
    private const long WS_EX_TOOLWINDOW = 0x00000080;
    private const long WS_EX_APPWINDOW = 0x00040000;
    private const int DWMWA_CLOAKED = 14;
    private const int SW_HIDE = 0;
    private const int SW_SHOWNOACTIVATE = 4;

    // Các cửa sổ của chính shell Windows không tính là "app"
    private static readonly string[] IgnoredClasses =
    {
        "Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd",
        "SHELLDLL_DefView", "Windows.UI.Core.CoreWindow", "NotifyIconOverflowWindow"
    };

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    private delegate bool EnumProc(IntPtr hwnd, IntPtr lParam);

    [DllImport("user32.dll")] private static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr FindWindow(string cls, string win);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr FindWindowEx(IntPtr parent, IntPtr after, string cls, string win);
    [DllImport("user32.dll")] private static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam, uint flags, uint timeout, out IntPtr result);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] private static extern IntPtr SetParent(IntPtr child, IntPtr newParent);
    [DllImport("user32.dll")] private static extern IntPtr GetParent(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern bool SystemParametersInfo(uint action, uint param, out RECT rect, uint winIni);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] private static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hwnd, int cmd);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attr, out int value, int size);

    private static bool _appsOpen = false;

    /// <summary>Gọi trước khi tạo cửa sổ: GetSystemMetrics trả về pixel thật, không bị scale DPI.</summary>
    public static void EnableDpiAwareness() => SetProcessDPIAware();

    /// <summary>false khi cửa sổ đã bị huỷ (vd. Explorer khởi động lại làm mất WorkerW).</summary>
    public static bool IsAlive(IntPtr h) => h != IntPtr.Zero && IsWindow(h);

    /// <summary>Tìm WorkerW (lớp nền desktop) đang nằm sau SHELLDLL_DefView (icon).</summary>
    public static IntPtr GetWorkerW()
    {
        IntPtr progman = FindWindow("Progman", null);
        // Gửi 0x052C để Explorer tạo WorkerW phía sau icon desktop
        SendMessageTimeout(progman, WM_SPAWN_WORKER, IntPtr.Zero, IntPtr.Zero, SMTO_NORMAL, 1000, out _);

        IntPtr workerw = IntPtr.Zero;
        EnumWindows((hwnd, lp) =>
        {
            if (FindWindowEx(hwnd, IntPtr.Zero, "SHELLDLL_DefView", null) != IntPtr.Zero)
                workerw = FindWindowEx(IntPtr.Zero, hwnd, "WorkerW", null);
            return true;
        }, IntPtr.Zero);

        return workerw != IntPtr.Zero ? workerw : progman;
    }

    /// <summary>Gắn cửa sổ vào WorkerW rồi đặt bố cục đúng theo trạng thái app.</summary>
    public static void Attach(IntPtr child)
    {
        if (child == IntPtr.Zero) return;
        _appsOpen = CountAppWindowsOnPrimary() > 0;
        SetParent(child, GetWorkerW());
        Layout(child);
    }

    /// <summary>Gọi định kỳ: gắn lại nếu WorkerW đổi, rồi đặt lại bố cục.</summary>
    public static void Refresh(IntPtr child)
    {
        if (child == IntPtr.Zero) return;
        IntPtr workerw = GetWorkerW();
        if (GetParent(child) != workerw) SetParent(child, workerw);
        Layout(child);
    }

    /// <summary>Gọi mỗi giây: nếu trạng thái "có app / không có app" đổi thì đổi bố cục.</summary>
    public static void UpdateState(IntPtr child)
    {
        if (child == IntPtr.Zero) return;
        bool appsOpen = CountAppWindowsOnPrimary() > 0;
        if (appsOpen == _appsOpen) return;
        _appsOpen = appsOpen;
        Layout(child);
    }

    /// <summary>Đếm cửa sổ app đang hiện trên màn hình chính (bỏ qua shell, cửa sổ ẩn, cửa sổ thu nhỏ, tool window).</summary>
    public static int CountAppWindowsOnPrimary()
    {
        int cx = GetSystemMetrics(SM_CXSCREEN);
        int cy = GetSystemMetrics(SM_CYSCREEN);
        int count = 0;
        var sb = new StringBuilder(256);

        EnumWindows((hwnd, lp) =>
        {
            if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return true;

            long ex = GetWindowLongPtr(hwnd, GWL_EXSTYLE).ToInt64();
            if ((ex & WS_EX_TOOLWINDOW) != 0 && (ex & WS_EX_APPWINDOW) == 0) return true;
            if (IsCloaked(hwnd)) return true;

            sb.Clear();
            GetClassName(hwnd, sb, sb.Capacity);
            if (Array.IndexOf(IgnoredClasses, sb.ToString()) >= 0) return true;

            if (!GetWindowRect(hwnd, out RECT r)) return true;
            if (r.Right - r.Left <= 0 || r.Bottom - r.Top <= 0) return true;
            // Chỉ tính cửa sổ có phần nằm trên màn hình chính (góc 0,0 - cx,cy)
            if (r.Right <= 0 || r.Bottom <= 0 || r.Left >= cx || r.Top >= cy) return true;

            count++;
            return true;
        }, IntPtr.Zero);

        return count;
    }

    /// <summary>Đặt bố cục theo trạng thái hiện tại: phủ màn hình chính, thu nhỏ góc phải dưới, hoặc ẩn.</summary>
    private static void Layout(IntPtr child)
    {
        if (_appsOpen && HideWhenAppsOpen)
        {
            ShowWindow(child, SW_HIDE);
            return;
        }

        ShowWindow(child, SW_SHOWNOACTIVATE);

        if (_appsOpen)
        {
            // Góc phải dưới, nằm trong vùng làm việc (không đè lên taskbar)
            var wa = WorkArea();
            int x = wa.Right - CompactWidth - CompactMargin;
            int y = wa.Bottom - CompactHeight - CompactMargin;
            SetWindowPos(child, IntPtr.Zero, x, y, CompactWidth, CompactHeight,
                         SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW);
        }
        else
        {
            // Không có app nào trên màn hình chính: phủ toàn bộ màn hình chính
            int cx = GetSystemMetrics(SM_CXSCREEN);
            int cy = GetSystemMetrics(SM_CYSCREEN);
            SetWindowPos(child, IntPtr.Zero, 0, 0, cx, cy,
                         SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW);
        }
    }

    private static RECT WorkArea()
    {
        if (SystemParametersInfo(SPI_GETWORKAREA, 0, out RECT wa, 0)) return wa;
        return new RECT { Left = 0, Top = 0, Right = GetSystemMetrics(SM_CXSCREEN), Bottom = GetSystemMetrics(SM_CYSCREEN) };
    }

    private static bool IsCloaked(IntPtr hwnd)
    {
        return DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, out int v, sizeof(int)) == 0 && v != 0;
    }
}
