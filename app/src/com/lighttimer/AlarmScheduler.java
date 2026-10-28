package com.lighttimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三种重复模式的调度：
 *   DAILY   —— nextTrigger(DAILY, h, m)：今天或明天 HH:MM
 *   WEEKLY  —— nextTrigger(WEEKLY, h, m, weekdayMask)：从今天起找第一个匹配位的那一天 HH:MM
 *   WEEKDAY —— nextWorkday(h, m)：跳过法定节假日，调休补班日照常
 *   ONCE    —— nextTrigger(ONCE, h, m, y, mo, d)：指定那一天 HH:MM
 */
public final class AlarmScheduler {

    private AlarmScheduler() {
    }

    public static void rescheduleAll(Context context) {
        List<AlarmStore.Item> list = AlarmStore.load(context);
        Set<Integer> uids = new HashSet<>();
        long now = System.currentTimeMillis();
        for (AlarmStore.Item it : list) {
            uids.add(it.id);
            if (it.enabled && AlarmStore.isStillValid(it, now)) {
                schedule(context, it);
            } else {
                cancel(context, it.id);
            }
        }
        FileLog.i(context, "LT", "rescheduleAll ok, " + list.size() + " items");
    }

    public static void schedule(Context context, AlarmStore.Item item) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) { FileLog.e(context, "LT", "AlarmManager null", null); return; }
        long at = nextTrigger(item);
        if (at <= 0) {
            FileLog.w(context, "LT", "uid=" + item.id + " no future trigger, skip");
            return;
        }
        PendingIntent pi = pendingIntent(context, item.id);
        am.cancel(pi);
        Intent show = new Intent(context, MainActivity.class);
        PendingIntent showPi = PendingIntent.getActivity(
                context, item.id, show, PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            FileLog.e(context, "LT", "uid=" + item.id + " AlarmClock SecurityException -> downgrade", e);
        }
        FileLog.i(context, "LT", "schedule uid=" + item.id
                + " @" + String.format("%02d:%02d", item.hour, item.minute)
                + " -> " + new Date(at));
    }

    public static void cancel(Context context, int uid) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pendingIntent(context, uid));
        FileLog.i(context, "LT", "cancel uid=" + uid);
    }

    public static PendingIntent pendingIntent(Context context, int uid) {
        Intent intent = new Intent(context, IrAlarmReceiver.class);
        intent.setAction("com.lighttimer.FIRE");
        intent.putExtra("uid", uid);
        return PendingIntent.getBroadcast(
                context, uid, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 返回下一次触发的绝对时间戳。已过期返回 -1。 */
    static long nextTrigger(AlarmStore.Item item) {
        switch (item.repeat) {
            case AlarmStore.REPEAT_DAILY:
                return nextDaily(item.hour, item.minute);
            case AlarmStore.REPEAT_WEEKLY:
                return nextWeekly(item.hour, item.minute, item.weekdayMask);
            case AlarmStore.REPEAT_WEEKDAY:
                return nextWorkday(item.hour, item.minute);
            case AlarmStore.REPEAT_ONCE:
                return nextOnce(item.hour, item.minute,
                        item.onceYear, item.onceMonth, item.onceDay);
            default:
                return -1;
        }
    }

    /** 对外暴露：仅每日模式用。 */
    public static long nextTrigger(int hour, int minute) {
        return nextDaily(hour, minute);
    }

    private static long nextDaily(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1);
        return target.getTimeInMillis();
    }

    private static long nextWeekly(int hour, int minute, int mask) {
        if (mask == 0) return -1;
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset <= 7; offset++) {
            Calendar cand = (Calendar) target.clone();
            cand.add(Calendar.DAY_OF_YEAR, offset);
            int bit = AlarmStore.weekdayBit(cand);
            if ((mask & (1 << bit)) != 0) {
                if (offset == 0 && !cand.after(now)) continue;
                return cand.getTimeInMillis();
            }
        }
        return -1;
    }

    /** 工作日：按法定节假日表跳过休息日，调休补班的周末照常触发。 */
    private static long nextWorkday(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = (Calendar) now.clone();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        // 最长连续假期（春节 9 天）也要能跨过
        for (int offset = 0; offset <= 14; offset++) {
            Calendar cand = (Calendar) target.clone();
            cand.add(Calendar.DAY_OF_YEAR, offset);
            if (!Holiday.isWorkday(cand)) continue;
            if (offset == 0 && !cand.after(now)) continue;
            return cand.getTimeInMillis();
        }
        return -1;
    }

    private static long nextOnce(int hour, int minute, int y, int mo, int d) {
        if (y <= 0) return -1;
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(y, mo - 1, d, hour, minute, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) return -1;
        return target.getTimeInMillis();
    }
}
