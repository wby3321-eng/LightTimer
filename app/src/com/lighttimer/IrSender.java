package com.lighttimer;

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.util.Log;

/** 直接通过 ConsumerIrManager 发红外，不需要解锁、不需要 UI。 */
public final class IrSender {

    public static final int FREQUENCY = 38180;

    /** TCL 吸顶灯 rid=8259 的「电源」键时序（微秒，来自酷控云端明文码表）。 */
    public static final int[] PATTERN = {
            8992, 4471, 558, 542, 558, 542, 558, 542, 558, 542, 558, 542, 558, 542, 558, 542,
            558, 1642, 559, 1642, 559, 1642, 558, 1642, 558, 1642, 558, 1642, 558, 1642,
            558, 1642, 558, 542, 558, 1642, 558, 542, 558, 542, 558, 542, 558, 542, 558, 542,
            558, 542, 558, 542, 558, 542, 558, 1642, 558, 1642, 558, 1642, 558, 1642, 558, 1642,
            558, 1642, 558, 1642, 558, 39647, 8992, 2245, 558, 94735
    };

    private IrSender() {
    }

    public static boolean send(Context context, String tag) {
        ConsumerIrManager ir =
                (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
        if (ir == null) {
            FileLog.e(context, "LT", "ir service null, tag=" + tag, null);
            Log.e("LightTimer", "ir service null, tag=" + tag);
            return false;
        }
        if (!ir.hasIrEmitter()) {
            FileLog.e(context, "LT", "no ir emitter, tag=" + tag, null);
            Log.e("LightTimer", "no ir emitter, tag=" + tag);
            return false;
        }
        try {
            ir.transmit(FREQUENCY, PATTERN);
            FileLog.i(context, "LT", "ir sent OK tag=" + tag + " fre=" + FREQUENCY
                    + " pattern_len=" + PATTERN.length);
            Log.i("LightTimer", "ir sent OK tag=" + tag);
            return true;
        } catch (Exception e) {
            FileLog.e(context, "LT", "ir transmit fail tag=" + tag, e);
            Log.e("LightTimer", "ir transmit fail tag=" + tag, e);
            return false;
        }
    }
}
