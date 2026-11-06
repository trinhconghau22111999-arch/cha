using Xunit;

namespace TiviWidget.Tests;

public class LunarCalendarTests
{
    // 6/11/2026 = ÂL 28/9: khớp với Android hiển thị trong ảnh chụp
    [Theory]
    [InlineData(1, 1, 2000, 25, 11, 1999)]
    [InlineData(10, 2, 2024, 1, 1, 2024)]
    [InlineData(17, 2, 2026, 1, 1, 2026)]
    [InlineData(6, 11, 2026, 28, 9, 2026)]
    public void SolarToLunar_MatchesKnownDates(int d, int m, int y, int ld, int lm, int ly)
    {
        var r = LunarCalendar.SolarToLunar(d, m, y);
        Assert.Equal(new[] { ld, lm, ly, 0 }, r);
    }
}
