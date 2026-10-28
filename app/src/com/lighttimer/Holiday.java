package com.lighttimer;

import android.content.Context;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 工作日判定：优先用在线同步下来的 holiday-cn 缓存，缓存缺失时退回内置表。
 *
 * 判定顺序：
 *   1) 本地在线缓存（HolidayApi）—— 覆盖到当年/次年，最准确
 *   2) 内置法定节假日 + 调休补班表（国务院 2026 年公告）—— 离线兜底
 *   3) 都没有数据时按周一至周五
 *
 * 内置数据来源：国务院办公厅关于 2026 年部分节假日安排的通知（国办发明电〔2025〕7 号）。
 */
public final class Holiday {

    private static final int SUPPORTED_YEAR = 2026;

    private static Context appCtx;

    private static final Set<String> OFF = new HashSet<>();
    private static final Set<String> MAKEUP = new HashSet<>();

    static {
        // 元旦：1/1 - 1/3
        addRange(OFF, 2026, 1, 1, 3);
        // 春节：2/15 - 2/23
        addRange(OFF, 2026, 2, 15, 23);
        // 清明：4/4 - 4/6
        addRange(OFF, 2026, 4, 4, 6);
        // 劳动节：5/1 - 5/5
        addRange(OFF, 2026, 5, 1, 5);
        // 端午：6/19 - 6/21
        addRange(OFF, 2026, 6, 19, 21);
        // 中秋：9/25 - 9/27
        addRange(OFF, 2026, 9, 25, 27);
        // 国庆：10/1 - 10/7
        addRange(OFF, 2026, 10, 1, 7);

        // 调休补班：周末上班
        addDay(MAKEUP, 2026, 1, 4);
        addDay(MAKEUP, 2026, 2, 14);
        addDay(MAKEUP, 2026, 2, 28);
        addDay(MAKEUP, 2026, 5, 9);
        addDay(MAKEUP, 2026, 9, 20);
        addDay(MAKEUP, 2026, 10, 10);
    }

    private Holiday() {
    }

    private static String key(int year, int month, int day) {
        return String.format(Locale.CHINA, "%04d-%02d-%02d", year, month, day);
    }

    private static void addDay(Set<String> set, int year, int month, int day) {
        set.add(key(year, month, day));
    }

    private static void addRange(Set<String> set, int year, int month, int from, int to) {
        for (int d = from; d <= to; d++) set.add(key(year, month, d));
    }

    public static boolean hasData(int year) {
        return year == SUPPORTED_YEAR;
    }

    /** 应用启动时调用一次，让 isWorkday 能读到在线缓存的 Context。 */
    public static void init(Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    /** 该天是否为「工作日」：优先在线缓存，其次内置表，最后周一至周五。 */
    public static boolean isWorkday(Calendar c) {
        Boolean cached = HolidayApi.lookup(appCtx, c);
        if (cached != null) return cached;

        int year = c.get(Calendar.YEAR);
        String k = key(year, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        if (hasData(year)) {
            if (MAKEUP.contains(k)) return true;
            if (OFF.contains(k)) return false;
        }
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
    }
}
