package com.lighttimer;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 每条定时：唯一 id、时、分、启用、标签、动作（开/关）、重复方式。
 * 三种重复：
 *   DAILY   —— 每天 HH:MM 触发
 *   WEEKLY  —— 指定星期几（mask，bit0=周一 … bit6=周日）触发
 *   ONCE    —— 仅指定的那一天（yyyy-mm-dd）触发，之后自动禁用
 */
public final class AlarmStore {

    static final String PREFS = "alarms";
    static final String KEY_LIST = "list";
    static final String KEY_NEXT_ID = "next_id";

    public static final int ACTION_OFF = 0;
    public static final int ACTION_ON = 1;

    public static final int REPEAT_DAILY = 0;
    public static final int REPEAT_WEEKLY = 1;
    public static final int REPEAT_ONCE = 2;
    public static final int REPEAT_WEEKDAY = 3;  // 工作日（周一到周五）

    // 工作日 weekdayMask 固定值：0b11111  bit0=周一…bit4=周五
    public static final int WEEKDAY_MASK_WORK = 0b11111;

    private AlarmStore() {
    }

    public static class Item {
        public int id;
        public int hour;
        public int minute;
        public boolean enabled;
        public String label;
        public int action;

        public int repeat;           // REPEAT_DAILY / WEEKLY / ONCE
        public int weekdayMask;      // WEEKLY: bit0=周一 … bit6=周日
        public int onceYear;         // ONCE
        public int onceMonth;        // ONCE (1-12)
        public int onceDay;          // ONCE (1-31)

        Item(int id, int hour, int minute, boolean enabled, String label, int action) {
            this(id, hour, minute, enabled, label, action,
                    REPEAT_DAILY, 0, 0, 0, 0);
        }

        Item(int id, int hour, int minute, boolean enabled, String label, int action,
             int repeat, int weekdayMask, int onceYear, int onceMonth, int onceDay) {
            this.id = id; this.hour = hour; this.minute = minute;
            this.enabled = enabled; this.label = label; this.action = action;
            this.repeat = repeat; this.weekdayMask = weekdayMask;
            this.onceYear = onceYear; this.onceMonth = onceMonth; this.onceDay = onceDay;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id).put("h", hour).put("m", minute)
                    .put("e", enabled).put("l", label).put("a", action)
                    .put("r", repeat).put("wk", weekdayMask)
                    .put("oy", onceYear).put("om", onceMonth).put("od", onceDay);
            return o;
        }

        static Item fromJson(JSONObject o) throws JSONException {
            return new Item(o.getInt("id"), o.getInt("h"), o.getInt("m"),
                    o.getBoolean("e"), o.optString("l", ""),
                    o.optInt("a", ACTION_OFF),
                    o.optInt("r", REPEAT_DAILY),
                    o.optInt("wk", 0),
                    o.optInt("oy", 0), o.optInt("om", 0), o.optInt("od", 0));
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY_LIST, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) out.add(Item.fromJson(arr.getJSONObject(i)));
            } catch (JSONException ignored) {}
        }
        return out;
    }

    public static void save(Context c, List<Item> list) {
        JSONArray arr = new JSONArray();
        for (Item it : list) {
            try { arr.put(it.toJson()); } catch (JSONException ignored) {}
        }
        prefs(c).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    public static int newId(Context c) {
        int id = prefs(c).getInt(KEY_NEXT_ID, 1);
        prefs(c).edit().putInt(KEY_NEXT_ID, id + 1).apply();
        return id;
    }

    /** REPEAT_ONCE 触发后调一次：把 enabled 置 false。 */
    public static void markOnceFired(Context c, int id) {
        List<Item> list = load(c);
        for (Item it : list) if (it.id == id && it.repeat == REPEAT_ONCE) it.enabled = false;
        save(c, list);
    }

    /** item 是否还有效（ONCE 且日期已过则失效）。 */
    public static boolean isStillValid(Item it, long nowMs) {
        if (it.repeat != REPEAT_ONCE) return true;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(it.onceYear, it.onceMonth - 1, it.onceDay, it.hour, it.minute, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() >= nowMs;
    }

    /** 把星期几名字映射到 bit 位：周一=0 … 周日=6。 */
    public static int weekdayBit(java.util.Calendar c) {
        // Calendar.DAY_OF_WEEK: Sun=1 Mon=2 … Sat=7
        int dow = c.get(java.util.Calendar.DAY_OF_WEEK);
        return (dow + 5) % 7;  // 周一→0 ... 周日→6
    }

    /** 首次启动写入两条默认定时：23:00 关灯、08:00 开灯（每天）。 */
    public static void ensureDefaults(Context c) {
        SharedPreferences sp = prefs(c);
        if (sp.getBoolean("defaults_ready", false)) return;
        List<Item> list = load(c);
        if (list.isEmpty()) {
            list.add(new Item(1, 23, 0, true, "夜间关灯", ACTION_OFF, REPEAT_DAILY, 0, 0, 0, 0));
            list.add(new Item(2, 8, 0, true, "早晨开灯", ACTION_ON, REPEAT_DAILY, 0, 0, 0, 0));
            save(c, list);
            prefs(c).edit().putInt(KEY_NEXT_ID, 3).apply();
        }
        sp.edit().putBoolean("defaults_ready", true).apply();
    }
}
