package com.lighttimer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * 在线节假日数据（NateScarlet/holiday-cn，数据源自国务院公告），按年缓存到本地。
 *
 * 设计原则：网络只用来「补数据」，绝不参与实时判定。
 *   启动/开机时后台拉取一次 → 按年写入 SharedPreferences
 *   判定时只读本地缓存，缓存未就绪则由 Holiday 的内置表兜底
 * 因此飞行模式、断网、后台被杀都不影响定时准确性。
 */
public final class HolidayApi {

    private static final String PREFS = "holiday_api";
    private static final String KEY_YEAR = "year_done_";   // year_done_2026=true 表示该年已同步
    private static final String KEY_DAY = "d_";            // d_2026-10-01=true 表示当天放假
    private static final String KEY_YEAR_DAYS = "year_days_";   // year_days_2026=27 表示该年缓存了几天
    private static final String KEY_ATTEMPT = "last_attempt_ms"; // 最近一次「实际发起同步」的时间
    private static final String KEY_OK = "last_ok";
    private static final String KEY_SRC = "last_src";            // 手动 / 启动 / 开机
    private static final String KEY_NOTE = "last_note";          // 结果说明
    private static final long STALE_MS = 3L * 24 * 3600 * 1000;   // 3 天内不重复拉取

    /** 多个镜像依次尝试，任一成功即停止。 */
    private static final String[] MIRRORS = {
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json",
            "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json",
    };

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;

    private HolidayApi() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 查本地缓存：返回 true=工作日 / false=休息日；null 表示该年份还没同步过。
     * 已同步的年份里，未出现在 holiday-cn 列表中的日期按普通周末规则处理。
     */
    static Boolean lookup(Context c, Calendar day) {
        if (c == null) return null;
        SharedPreferences p = prefs(c);
        int year = day.get(Calendar.YEAR);
        if (!p.getBoolean(KEY_YEAR + year, false)) return null;
        String key = KEY_DAY + String.format(Locale.CHINA, "%04d-%02d-%02d",
                year, day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
        if (p.contains(key)) return !p.getBoolean(key, false);   // isOffDay=false 表示调休上班
        int dow = day.get(Calendar.DAY_OF_WEEK);
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
    }

    /** 后台线程同步，不阻塞 UI。source 记录触发来源：开机 / 启动 / 手动。 */
    public static void syncAsync(Context c, String source) {
        new Thread(() -> syncIfStale(c, source), "HolidaySync").start();
    }

    /**
     * 自动同步的节流：成功过则 3 天内不再请求；失败则 6 小时后允许重试。
     * 命中节流时返回 true，但不会刷新「上次同步」记录（因为并未真正发起请求）。
     */
    public static boolean syncIfStale(Context c, String source) {
        long since = System.currentTimeMillis() - lastAttempt(c);
        if (since < STALE_MS && (lastOk(c) || since < 6L * 3600 * 1000)) {
            return true;
        }
        return sync(c, source);
    }

    /** 强制同步当前年（10 月之后顺带拉明年，通常 11 月公布）。返回是否至少成功一年。 */
    public static boolean sync(Context c, String source) {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        boolean okThis = fetchYear(c, year);
        boolean okNext = false;
        if (now.get(Calendar.MONTH) >= Calendar.OCTOBER) {
            okNext = fetchYear(c, year + 1);
        }
        boolean ok = okThis || okNext;

        StringBuilder note = new StringBuilder();
        if (okThis) note.append(year).append(" 年 ").append(daysOf(c, year)).append(" 天");
        if (okNext) {
            if (note.length() > 0) note.append(" · ");
            note.append(year + 1).append(" 年 ").append(daysOf(c, year + 1)).append(" 天");
        }
        if (note.length() == 0) {
            note.append("失败：网络不可用，继续使用内置表");
        } else if (now.get(Calendar.MONTH) >= Calendar.OCTOBER && !okNext) {
            note.append("（").append(year + 1).append(" 年暂未公布）");
        }

        prefs(c).edit()
                .putLong(KEY_ATTEMPT, System.currentTimeMillis())
                .putBoolean(KEY_OK, ok)
                .putString(KEY_SRC, source)
                .putString(KEY_NOTE, note.toString())
                .apply();
        return ok;
    }

    // ── 供设置页展示同步状态 ──────────────────────────

    /** 最近一次实际发起同步的时间，0 表示从未同步过。 */
    public static long lastAttempt(Context c) {
        return prefs(c).getLong(KEY_ATTEMPT, 0);
    }

    public static boolean lastOk(Context c) {
        return prefs(c).getBoolean(KEY_OK, false);
    }

    /** 同步来源：手动 / 启动 / 开机。 */
    public static String lastSource(Context c) {
        return prefs(c).getString(KEY_SRC, "");
    }

    /** 同步结果说明，例如「2026 年 27 天」。 */
    public static String lastNote(Context c) {
        return prefs(c).getString(KEY_NOTE, "");
    }

    /** 某年缓存的节假日天数，0 表示该年还没同步过。 */
    public static int daysOf(Context c, int year) {
        return prefs(c).getInt(KEY_YEAR_DAYS + year, 0);
    }

    /** 最近一次同步距今是否还在 3 天节流窗口内。 */
    public static boolean fresh(Context c) {
        return System.currentTimeMillis() - lastAttempt(c) < STALE_MS;
    }

    private static boolean fetchYear(Context c, int year) {
        for (String mirror : MIRRORS) {
            String url = String.format(Locale.CHINA, mirror, year);
            String body = httpGet(url);
            if (body == null) continue;
            if (parseAndStore(c, year, body)) return true;
        }
        FileLog.w(c, "LT", "holiday sync " + year + " failed (all mirrors)");
        return false;
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "LightTimer/1.0");
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 解析 holiday-cn 的 {year, days:[{date,isOffDay}]} 结构并写入本地缓存。 */
    private static boolean parseAndStore(Context c, int year, String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONArray days = root.optJSONArray("days");
            if (days == null || days.length() == 0) return false;
            SharedPreferences.Editor edit = prefs(c).edit();
            for (int i = 0; i < days.length(); i++) {
                JSONObject o = days.optJSONObject(i);
                if (o == null) continue;
                String date = o.optString("date", "");
                if (date.isEmpty()) continue;
                edit.putBoolean(KEY_DAY + date, o.optBoolean("isOffDay", false));
            }
            edit.putBoolean(KEY_YEAR + year, true);
            edit.putInt(KEY_YEAR_DAYS + year, days.length());
            edit.apply();
            FileLog.i(c, "LT", "holiday sync " + year + " ok, days=" + days.length());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
