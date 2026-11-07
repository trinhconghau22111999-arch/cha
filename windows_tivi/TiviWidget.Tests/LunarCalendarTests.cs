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

    [Theory]
    [InlineData(2026, 11, 2, "Thứ hai")]
    [InlineData(2026, 11, 3, "Thứ ba")]
    [InlineData(2026, 11, 4, "Thứ tư")]
    [InlineData(2026, 11, 5, "Thứ năm")]
    [InlineData(2026, 11, 6, "Thứ sáu")]
    [InlineData(2026, 11, 7, "Thứ bảy")]
    [InlineData(2026, 11, 8, "Chủ nhật")]
    public void VietnameseWeekday_FullNames(int y, int m, int d, string expected)
    {
        Assert.Equal(expected, LunarCalendar.VietnameseWeekday(new System.DateTime(y, m, d).DayOfWeek));
    }
}
