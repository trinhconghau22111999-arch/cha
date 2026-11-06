using System.Collections.Generic;
using Xunit;

namespace TiviWidget.Tests;

public class SpokenNameTests
{
    private static ChannelGroup G(string group, params string[] names)
    {
        var g = new ChannelGroup { Name = group };
        foreach (var n in names) g.Channels.Add(new Channel { Name = n, Url = "u:" + n });
        return g;
    }

    private static List<ChannelGroup> Groups() => new()
    {
        G("HTV9", "HTV9"),
        G("Việt Nam", "VTV1", "VTV2", "VTV3", "VTV5", "VTV9", "ANTV", "QPVN"),
        G("Hà Nội", "Hà Nội 1", "Hà Nội 2"),
        G("Quảng Ninh", "Quảng Ninh 1", "Quảng Ninh 3"),
        G("Cần Thơ", "Truyền hình Cần Thơ 1"),
        G("Cà Mau", "Truyền hình Cà Mau"),
    };

    [Theory]
    [InlineData("hát tê vê chín", "htv9")]
    [InlineData("Hát Tê Vê Chín", "htv9")]
    [InlineData("mở kênh hát tê vê 9", "htv9")]
    [InlineData("vê tê vê một", "vtv1")]
    [InlineData("vê tê vê năm", "vtv5")]
    [InlineData("a nờ tê vê", "antv")]
    [InlineData("quy pê vê en", "qpvn")]
    [InlineData("HTV chín", "htv9")]
    [InlineData("VTV năm", "vtv5")]
    [InlineData("H T V 9", "htv9")]
    [InlineData("hà nội hai", "hanoi2")]
    [InlineData("quảng ninh ba", "quangninh3")]
    public void NormQueryConvertsSpokenAbbreviations(string spoken, string expected) =>
        Assert.Equal(expected, ChannelMatcher.NormQuery(spoken));

    [Theory]
    [InlineData("hát tê vê chín", "u:HTV9")]
    [InlineData("vê tê vê một", "u:VTV1")]
    [InlineData("vê tê vê ba", "u:VTV3")]
    [InlineData("vê tê vê năm", "u:VTV5")]
    [InlineData("mở kênh vê tê vê chín", "u:VTV9")]
    [InlineData("a nờ tê vê", "u:ANTV")]
    [InlineData("quy pê vê en", "u:QPVN")]
    [InlineData("hà nội hai", "u:Hà Nội 2")]
    [InlineData("quảng ninh ba", "u:Quảng Ninh 3")]
    [InlineData("cần thơ một", "u:Truyền hình Cần Thơ 1")]
    public void FindsChannelFromSpokenName(string spoken, string url)
    {
        var r = ChannelMatcher.Find(spoken, Groups());
        Assert.NotNull(r);
        Assert.Equal(url, r.Value.Url);
    }

    [Fact]
    public void WordsThatAreNotLettersAreLeftAlone()
    {
        Assert.Equal("camau", ChannelMatcher.NormQuery("cà mau"));
        Assert.Equal("vietnam", ChannelMatcher.NormQuery("Việt Nam"));
        Assert.Equal("nghean", ChannelMatcher.NormQuery("Nghệ An"));
    }

    [Fact]
    public void BareNumberDoesNotGuessAChannel() =>
        Assert.Null(ChannelMatcher.Find("kênh ba", Groups()));

    [Fact]
    public void ExistingNamesStillMatch()
    {
        Assert.Equal("u:Truyền hình Cà Mau", ChannelMatcher.Find("ca mau", Groups())!.Value.Url);
        Assert.Equal("u:HTV9", ChannelMatcher.Find("HTV9", Groups())!.Value.Url);
    }
}
