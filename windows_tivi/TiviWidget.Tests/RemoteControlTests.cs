using System.Collections.Generic;
using Xunit;

namespace TiviWidget.Tests;

// Chỉ kiểm tra các lệnh phát sự kiện. move, click, scroll, volume, screen gọi hệ thống
// (chuột, âm lượng, màn hình) nên được kiểm thử bằng tay, xem TEST_PLAN.md.
public class RemoteControlTests
{
    [Fact]
    public void KeyOkIsForwarded()
    {
        var rc = new RemoteControl();
        string got = null;
        rc.KeyPressed += k => got = k;
        rc.Handle("{\"action\":\"key\",\"key\":\"ok\"}");
        Assert.Equal("ok", got);
    }

    [Fact]
    public void BarKeyIsIgnored()
    {
        var rc = new RemoteControl();
        string got = null;
        rc.KeyPressed += k => got = k;
        rc.Handle("{\"action\":\"key\",\"key\":\"bar\"}");
        Assert.Null(got);
    }

    [Fact]
    public void QualityIsForwarded()
    {
        var rc = new RemoteControl();
        int got = 0;
        rc.QualityRequested += q => got = q;
        rc.Handle("{\"action\":\"quality\",\"q\":720}");
        Assert.Equal(720, got);
    }

    [Fact]
    public void TouchLockIsForwarded()
    {
        var rc = new RemoteControl();
        bool? got = null;
        rc.TouchLockChanged += on => got = on;
        rc.Handle("{\"action\":\"touchlock\",\"on\":true}");
        Assert.True(got);
    }

    [Fact]
    public void BrightnessOnlyAcceptsRange0To100()
    {
        var rc = new RemoteControl();
        var list = new List<int>();
        rc.BrightnessChanged += list.Add;
        rc.Handle("{\"action\":\"brightness\",\"level\":50}");
        rc.Handle("{\"action\":\"brightness\",\"level\":150}");
        Assert.Equal(new[] { 50 }, list);
    }

    [Fact]
    public void VoiceQueryIsForwarded()
    {
        var rc = new RemoteControl();
        string got = null;
        rc.VoiceQuery += q => got = q;
        rc.Handle("{\"action\":\"voice\",\"q\":\"mở TV\"}");
        Assert.Equal("mở TV", got);
    }

    [Fact]
    public void ThreeQuickBacksGoHome()
    {
        var rc = new RemoteControl();
        int back = 0, home = 0;
        rc.BackPressed += () => back++;
        rc.HomePressed += () => home++;
        for (int i = 0; i < 3; i++) rc.Handle("{\"action\":\"back\"}");
        Assert.Equal(2, back);
        Assert.Equal(1, home);
    }

    [Fact]
    public void InvalidJsonIsIgnored()
    {
        var rc = new RemoteControl();
        var ex = Record.Exception(() => rc.Handle("không phải json"));
        Assert.Null(ex);
    }
}
