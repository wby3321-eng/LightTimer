package com.lighttimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;

/**
 * 核心改动：
 * 1) onReceive 里立即拿 PARTIAL_WAKE_LOCK（RTC_WAKEUP 只能保证 CPU 醒 10 秒，但发红外
 *    只需要几十毫秒；WakeLock 让我们能在整个 Receiver 执行期间锁 CPU）。
 * 2) goAsync() 延长广播窗口——避免 onReceive 返回后 CPU 又睡。
 * 3) 所有路径（发/跳过）都保证在 finally 里释放 WakeLock + finish PendingResult。
 */
public class IrAlarmReceiver extends BroadcastReceiver {

    private static final String WL_TAG = "LightTimer:IrAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        FileLog.i(context, "LT", "onReceive action=" + action
                + " extras=" + intent.getExtras());
        Holiday.init(context);

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, WL_TAG);
        wl.setReferenceCounted(false);
        wl.acquire(10_000);   // 兜底 10s 后自动释放，防泄漏

        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if ("com.lighttimer.TEST".equals(action)) {
                    IrSender.send(context, "adb-test");
                    return;
                }
                int uid = intent.getIntExtra("uid", -1);
                FileLog.i(context, "LT", "FIRE uid=" + uid);
                List<AlarmStore.Item> list = AlarmStore.load(context);
                AlarmStore.Item target = null;
                for (AlarmStore.Item it : list) {
                    if (it.id == uid) { target = it; break; }
                }
                if (target == null) {
                    FileLog.w(context, "LT", "uid=" + uid + " not found");
                    return;
                }
                if (!target.enabled) {
                    FileLog.i(context, "LT", "uid=" + uid + " disabled, skip");
                    return;
                }
                decideAndFire(context, target);
                // ONCE 触发一次后自动禁用
                if (target.repeat == AlarmStore.REPEAT_ONCE) {
                    AlarmStore.markOnceFired(context, uid);
                    AlarmScheduler.cancel(context, uid);
                    FileLog.i(context, "LT", "uid=" + uid + " ONCE fired, disabled");
                } else {
                    AlarmScheduler.schedule(context, target);
                    FileLog.i(context, "LT", "uid=" + uid + " rescheduled");
                }
            } catch (Throwable t) {
                FileLog.e(context, "LT", "receiver fail", t);
            } finally {
                try { wl.release(); } catch (Exception ignored) {}
                if (pr != null) pr.finish();
            }
        }, "LightTimer-Ir").start();
    }

    /**
     * 光感条件触发：lux 低于阈值（默认 10）判定为灯灭，否则灯亮。
     * 已在目标状态则跳过（toggle 码再发会反向）。
     * 传感器读数失败 → 跳过（宁可漏触发，不可误触发）。
     * 每次判定都写一行 #FIRE 记录，供设置里的「触发记录」查看。
     */
    private static void decideAndFire(Context context, AlarmStore.Item target) {
        int uid = target.id;
        float th = SenseConfig.threshold(context);
        String label = (target.label == null || target.label.trim().isEmpty())
                ? "定时" : target.label.trim();
        String act = target.action == AlarmStore.ACTION_ON ? "开灯" : "关灯";

        float lux = LightSensor.read(context, 2000);
        if (Float.isNaN(lux)) {
            FileLog.w(context, "LT", "uid=" + uid + " sensor read fail, skip");
            FileLog.i(context, "LIGHT", fireRecord(label, act, Float.NaN, th, "传感器读取失败，跳过"));
            return;
        }
        boolean lightOn = lux >= th;
        boolean wantOn = target.action == AlarmStore.ACTION_ON;
        FileLog.i(context, "LT", "uid=" + uid + " lux=" + lux + " th=" + th
                + " lightOn=" + lightOn + " wantOn=" + wantOn);
        if (lightOn == wantOn) {
            FileLog.i(context, "LT", "uid=" + uid + " already in target state, skip");
            FileLog.i(context, "LIGHT", fireRecord(label, act, lux, th,
                    "灯已" + (lightOn ? "亮" : "灭") + "，跳过"));
            return;
        }
        FileLog.i(context, "LT", "trigger uid=" + uid + " @"
                + String.format("%02d:%02d", target.hour, target.minute));
        IrSender.send(context, "uid-" + uid);
        FileLog.i(context, "LIGHT", fireRecord(label, act, lux, th, "已发送"));
    }

    /** 「触发记录」用的紧凑行：日志查看器按 #FIRE 过滤解析。 */
    private static String fireRecord(String label, String act, float lux, float th, String result) {
        String luxStr = Float.isNaN(lux) ? "--"
                : String.format(java.util.Locale.CHINA, "%.0f", lux);
        return "#FIRE|" + label + "|" + act + "|当前=" + luxStr
                + " lux|阈值=" + String.format(java.util.Locale.CHINA, "%.0f", th)
                + "|" + result;
    }
}
