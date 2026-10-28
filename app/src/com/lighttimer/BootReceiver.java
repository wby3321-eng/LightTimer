package com.lighttimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Holiday.init(context);
        AlarmScheduler.rescheduleAll(context);
        HolidayApi.syncAsync(context, "开机");
    }
}
