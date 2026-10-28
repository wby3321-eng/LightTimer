package com.lighttimer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 光线触发阈值：读数低于阈值判定为「灯灭」，否则判定为「灯亮」。
 * 默认为 10 lux（多次采样实测：灯灭环境基本在 10 以下），用户可在设置里调整。
 */
public final class SenseConfig {

    static final String PREFS = "sense";
    static final String KEY_THRESHOLD = "lux_threshold";

    public static final float DEFAULT_THRESHOLD = 10f;
    private static final float MIN_THRESHOLD = 1f;
    private static final float MAX_THRESHOLD = 500f;

    private SenseConfig() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前触发阈值（lux），永远返回有效值。 */
    public static float threshold(Context c) {
        float v = prefs(c).getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        if (Float.isNaN(v) || v <= 0) return DEFAULT_THRESHOLD;
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, v));
    }

    public static void setThreshold(Context c, float lux) {
        float v = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, lux));
        prefs(c).edit().putFloat(KEY_THRESHOLD, v).apply();
    }
}
