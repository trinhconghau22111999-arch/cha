using Xunit;

namespace TiviWidget.Tests;

public class YtRulesTests
{
    [Theory]
    [InlineData("https://www.youtube.com/watch?v=abc", true)]
    [InlineData("https://youtu.be/abc", true)]
    [InlineData("https://www.youtube.com/redirect?q=x", false)]
    [InlineData("https://accounts.google.com/signin", true)]
    [InlineData("https://example.com/", false)]
    [InlineData("about:blank", true)]
    [InlineData("file:///C:/x.html", false)]
    public void AllowedMatchesAndroidRules(string url, bool expected) =>
        Assert.Equal(expected, YtRules.Allowed(url));
}
