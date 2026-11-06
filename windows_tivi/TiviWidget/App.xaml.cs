using System;
using System.Linq;
using System.Threading;
using System.Windows;

namespace TiviWidget;

public partial class App : Application
{
    private const string MutexName = @"Local\TiviDesktopWidget.SingleInstance";
    private Mutex _mutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        // Phải bật trước khi WPF tạo cửa sổ để toạ độ/kích thước là pixel thật
        DesktopHost.EnableDpiAwareness();

        bool isRestart = e.Args.Contains("--restarted");
        bool isAutostart = e.Args.Contains("--autostart");

        _mutex = new Mutex(false, MutexName);
        bool owned;
        try
        {
            // Bản khởi động lại (sau khi Explorer restart) đợi bản cũ thoát tối đa 15 giây.
            // Bản thường: nếu đã có bản chạy thì thoát luôn, không chạy hai widget chồng nhau.
            owned = _mutex.WaitOne(isRestart ? 15000 : 0);
        }
        catch (AbandonedMutexException)
        {
            owned = true;
        }
        if (!owned)
        {
            Shutdown();
            return;
        }

        // Khi máy vừa đăng nhập, Explorer/desktop có thể chưa sẵn sàng: chờ một chút
        if (isAutostart) Thread.Sleep(3000);

        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { _mutex?.ReleaseMutex(); } catch (Exception) { }
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
