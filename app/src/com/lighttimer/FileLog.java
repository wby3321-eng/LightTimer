package com.lighttimer;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量文件日志：锁屏后 logcat 可能被厂商清理，但外部存储通常可用。
 * 路径：/sdcard/Android/data/com.lighttimer/files/log.txt
 */
public final class FileLog {

    private static final String FILE = "log.txt";

    private FileLog() {}

    public static void i(Context c, String tag, String msg) { write(c, "I", tag, msg, null); }
    public static void w(Context c, String tag, String msg) { write(c, "W", tag, msg, null); }
    public static void e(Context c, String tag, String msg, Throwable t) { write(c, "E", tag, msg, t); }

    private static synchronized void write(Context c, String level, String tag, String msg, Throwable t) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, FILE);
        // 超过 500KB 截半：读后半部回写，保留最新日志
        try {
            if (file.exists() && file.length() > 512 * 1024) {
                byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
                int start = data.length / 2;
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                    fos.write(data, start, data.length - start);
                }
            }
        } catch (Exception ignored) {}
        try (FileWriter w = new FileWriter(file, true)) {
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
            w.append(ts).append(' ').append(level).append('/').append(tag).append(' ')
                    .append(msg == null ? "" : msg).append('\n');
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                w.append(sw.toString());
            }
            // 同时打到 logcat（方便 adb logcat 看）
            Log.println(logLevel(level), tag, msg == null ? "" : msg);
            if (t != null) Log.e(tag, msg, t);
        } catch (Exception ignored) {}
    }

    private static int logLevel(String l) {
        switch (l) { case "E": return Log.ERROR; case "W": return Log.WARN; default: return Log.INFO; }
    }

    /**
     * 倒序读取最近的日志行（最新在前），最多 maxLines 条；
     * contains 不为 null 时只保留包含该关键字的行。用于设置里的「触发记录」。
     */
    public static java.util.List<String> tail(Context c, int maxLines, String contains) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return out;
        File file = new File(dir, FILE);
        if (!file.exists()) return out;
        try {
            java.util.List<String> all = java.nio.file.Files.readAllLines(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            for (int i = all.size() - 1; i >= 0 && out.size() < maxLines; i--) {
                String line = all.get(i);
                if (contains == null || line.contains(contains)) out.add(line);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
