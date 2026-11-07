using System;

namespace TiviWidget;

/// <summary>Đổi dương lịch -> âm lịch Việt Nam (UTC+7), thuật toán Hồ Ngọc Đức. Port từ LunarCalendar.kt.</summary>
internal static class LunarCalendar
{
    /// <summary>Tên thứ bằng tiếng Việt: "Thứ hai" ... "Thứ bảy", "Chủ nhật".</summary>
    public static string VietnameseWeekday(DayOfWeek d) => d switch
    {
        DayOfWeek.Monday => "Thứ hai",
        DayOfWeek.Tuesday => "Thứ ba",
        DayOfWeek.Wednesday => "Thứ tư",
        DayOfWeek.Thursday => "Thứ năm",
        DayOfWeek.Friday => "Thứ sáu",
        DayOfWeek.Saturday => "Thứ bảy",
        _ => "Chủ nhật"
    };

    private const double TZ = 7.0;
    private const double Dr = Math.PI / 180;

    private static double S(double x) => Math.Sin(x * Dr);

    private static int JdFromDate(int dd, int mm, int yy)
    {
        int a = (14 - mm) / 12;
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
        if (jd < 2299161) jd = dd + (153 * m + 2) / 5 + 365 * y + y / 4 - 32083;
        return jd;
    }

    private static double NewMoon(int k)
    {
        double t = k / 1236.85;
        double t2 = t * t;
        double t3 = t2 * t;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3;
        jd1 += 0.00033 * S(166.56 + 132.87 * t - 0.009173 * t2);
        double m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3;
        double mpr = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3;
        double f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3;
        double c1 = (0.1734 - 0.000393 * t) * S(m) + 0.0021 * S(2 * m);
        c1 -= 0.4068 * S(mpr) - 0.0161 * S(2 * mpr);
        c1 -= 0.0004 * S(3 * mpr);
        c1 += 0.0104 * S(2 * f) - 0.0051 * S(m + mpr);
        c1 -= 0.0074 * S(m - mpr) - 0.0004 * S(2 * f + m);
        c1 -= 0.0004 * S(2 * f - m) + 0.0006 * S(2 * f + mpr);
        c1 += 0.0010 * S(2 * f - mpr) + 0.0005 * S(2 * mpr + m);
        double deltat = t < -11
            ? 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3
            : -0.000278 + 0.000265 * t + 0.000262 * t2;
        return jd1 + c1 - deltat;
    }

    private static int SunLongitude(int jdn)
    {
        double t = (jdn - 2451545.5 - TZ / 24) / 36525;
        double t2 = t * t;
        double m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2;
        double l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2;
        double dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * S(m);
        dl += (0.019993 - 0.000101 * t) * S(2 * m) + 0.000290 * S(3 * m);
        double l = (l0 + dl) * Dr;
        l -= Math.PI * 2 * Math.Floor(l / (Math.PI * 2));
        return (int)Math.Floor(l / Math.PI * 6);
    }

    private static int NewMoonDay(int k) => (int)Math.Floor(NewMoon(k) + 0.5 + TZ / 24);

    private static int LunarMonth11(int yy)
    {
        int off = JdFromDate(31, 12, yy) - 2415021;
        int k = (int)Math.Floor(off / 29.530588853);
        int nm = NewMoonDay(k);
        if (SunLongitude(nm) >= 9) nm = NewMoonDay(k - 1);
        return nm;
    }

    private static int LeapMonthOffset(int a11)
    {
        int k = (int)Math.Floor((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int i = 1;
        int arc = SunLongitude(NewMoonDay(k + i));
        int last;
        do
        {
            last = arc;
            i++;
            arc = SunLongitude(NewMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    /// <summary>Trả về [ngày, tháng, năm, nhuận(0/1)] âm lịch.</summary>
    public static int[] SolarToLunar(int dd, int mm, int yy)
    {
        int dayNumber = JdFromDate(dd, mm, yy);
        int k = (int)Math.Floor((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = NewMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = NewMoonDay(k);
        int a11 = LunarMonth11(yy);
        int b11 = a11;
        int lunarYear0;
        if (a11 >= monthStart)
        {
            lunarYear0 = yy;
            a11 = LunarMonth11(yy - 1);
        }
        else
        {
            lunarYear0 = yy + 1;
            b11 = LunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = (monthStart - a11) / 29;
        int leap = 0;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365)
        {
            int leapDiff = LeapMonthOffset(a11);
            if (diff >= leapDiff)
            {
                lunarMonth = diff + 10;
                if (diff == leapDiff) leap = 1;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        int lunarYear = lunarYear0;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new[] { lunarDay, lunarMonth, lunarYear, leap };
    }
}
