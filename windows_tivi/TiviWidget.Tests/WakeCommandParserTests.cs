using System;
using Xunit;

namespace TiviWidget.Tests;

public class WakeCommandParserTests
{
    private static readonly DateTime T0 = new(2026, 11, 6, 12, 0, 0, DateTimeKind.Utc);

    [Fact]
    public void WakeAndCommandInOnePhrase()
    {
        var p = new WakeCommandParser();
        Assert.Equal("mở youtube", p.Feed("nghe nè mở youtube ok", T0));
        Assert.False(p.Capturing);
    }

    [Fact]
    public void CommandSplitAcrossPhrases()
    {
        var p = new WakeCommandParser();
        Assert.Null(p.Feed("Nghe nè", T0));
        Assert.True(p.Capturing);
        Assert.Null(p.Feed("mở TV kênh HTV9", T0.AddSeconds(2)));
        Assert.Equal("mở TV kênh HTV9", p.Feed("ok", T0.AddSeconds(4)));
    }

    [Fact]
    public void KeepsDiacriticsAndIgnoresWordsBeforeWake()
    {
        var p = new WakeCommandParser();
        Assert.Equal("mở bài hát Lạc trôi", p.Feed("à thế hả nghe né mở bài hát Lạc trôi OK", T0));
    }

    [Fact]
    public void NoWakeWordMeansNoCommand()
    {
        var p = new WakeCommandParser();
        Assert.Null(p.Feed("mở youtube ok", T0));
        Assert.False(p.Capturing);
    }

    [Fact]
    public void EmptyCommandIsIgnored()
    {
        var p = new WakeCommandParser();
        Assert.Null(p.Feed("nghe nè ok", T0));
        Assert.False(p.Capturing);
    }

    [Fact]
    public void TimesOutWithoutOk()
    {
        var p = new WakeCommandParser(TimeSpan.FromSeconds(10));
        p.Feed("nghe nè mở youtube", T0);
        Assert.False(p.Tick(T0.AddSeconds(5)));
        Assert.True(p.Tick(T0.AddSeconds(11)));
        Assert.False(p.Capturing);
        Assert.Null(p.Feed("ok", T0.AddSeconds(12)));
    }
}
