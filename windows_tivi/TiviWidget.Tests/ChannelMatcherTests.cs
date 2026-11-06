using System.Collections.Generic;
using Xunit;

namespace TiviWidget.Tests;

public class ChannelMatcherTests
{
    private static List<ChannelGroup> Groups() => new()
    {
        new ChannelGroup
        {
            Name = "Cà Mau",
            Channels = new List<Channel> { new Channel { Name = "Truyền hình Cà Mau", Url = "u218" } }
        },
        new ChannelGroup
        {
            Name = "HTV9",
            Channels = new List<Channel> { new Channel { Name = "HTV9", Url = "u10068" } }
        }
    };

    [Fact]
    public void FindsByNameWithoutDiacritics()
    {
        var r = ChannelMatcher.Find("ca mau", Groups());
        Assert.NotNull(r);
        Assert.Equal(0, r.Value.Gi);
        Assert.Equal("u218", r.Value.Url);
    }

    [Fact]
    public void FindsExactChannel()
    {
        var r = ChannelMatcher.Find("HTV9", Groups());
        Assert.NotNull(r);
        Assert.Equal(1, r.Value.Gi);
        Assert.Equal("u10068", r.Value.Url);
    }

    [Fact]
    public void UnknownNameReturnsNull() =>
        Assert.Null(ChannelMatcher.Find("không có", Groups()));
}
