package com.lighttimer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 环境光一次性读取：注册监听后在 timeoutMs 内最多采 3 个样本，取中位数。
 * 无传感器 / 超时无读数返回 NaN。必须在子线程调用（阻塞等待）。
 */
public final class LightSensor {

    public static final float INVALID = Float.NaN;

    private LightSensor() {
    }

    public static float read(Context context, long timeoutMs) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return INVALID;
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (s == null) return INVALID;

        List<Float> samples = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                samples.add(e.values[0]);
                if (samples.size() >= 3) latch.countDown();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        try {
            if (!sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)) {
                return INVALID;
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return INVALID;
        } finally {
            sm.unregisterListener(listener);
        }
        if (samples.isEmpty()) return INVALID;
        List<Float> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
