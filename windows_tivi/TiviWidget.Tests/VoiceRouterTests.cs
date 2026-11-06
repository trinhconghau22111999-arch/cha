using Xunit;

namespace TiviWidget.Tests;

public class VoiceRouterTests
{
    [Fact]
    public void CompoundYouTubeSearch_KeepsDiacriticsInQuery()
    {
        var r = VoiceRouter.SwitchAndQuery("mở YouTube mở bài hát Lạc trôi");
        Assert.NotNull(r);
        Assert.Equal(VoiceTarget.YouTube, r.Value.Mode);
        Assert.Equal("Lạc trôi", r.Value.Rest);
    }

    [Fact]
    public void CompoundTvChannel()
    {
        var r = VoiceRouter.SwitchAndQuery("mở TV kênh HTV9");
        Assert.NotNull(r);
        Assert.Equal(VoiceTarget.Tv, r.Value.Mode);
        Assert.Equal("HTV9", r.Value.Rest);
    }

    [Theory]
    [InlineData("mở TV", VoiceTarget.Tv)]
    [InlineData("mở YouTube", VoiceTarget.YouTube)]
    [InlineData("Cà Mau", VoiceTarget.None)]
    public void OpenCommandOnly(string q, VoiceTarget expected) =>
        Assert.Equal(expected, VoiceRouter.OpenCommand(q));

    [Theory]
    [InlineData("mở TV")]
    [InlineData("mở YouTube")]
    [InlineData("Cà Mau")]
    public void NoContentMeansNoCompoundCommand(string q) =>
        Assert.Null(VoiceRouter.SwitchAndQuery(q));

    [Fact]
    public void NormVoiceStripsPunctuationAndDiacritics() =>
        Assert.Equal("mo youtube", VoiceRouter.NormVoice("Mở YouTube!"));
}
