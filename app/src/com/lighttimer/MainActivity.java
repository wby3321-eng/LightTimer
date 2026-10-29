package com.lighttimer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private LinearLayout listContainer;
    private SensorManager sm;
    private Sensor lightSensor;
    private final float[] lastLux = {Float.NaN};
    private SensorEventListener luxListener;
    private TextView holidaySubView;   // 设置页「同步节假日数据」副标题，同步完成后刷新

    private static final String[] WEEK_NAMES = {"一","二","三","四","五","六","日"};
    private static final String[] WEEK_FULL = {"一","二","三","四","五","六","日"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AlarmStore.ensureDefaults(this);
        Holiday.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.color.bg_page);

        // 状态栏 padding
        int statusBarH = 0;
        int resid = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resid > 0) statusBarH = getResources().getDimensionPixelSize(resid);
        root.setPadding(0, statusBarH, 0, 0);

        int pad = dp(16);
        int cardPad = dp(20);

        // ── 顶部：齿轮右边距对齐卡片内 Switch 右边缘 ──
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        topBar.setPadding(pad, dp(8), pad + cardPad, dp(4));

        TextView gear = new TextView(this);
        gear.setText("\u2699");
        gear.setTextSize(22);
        gear.setTextColor(getColor(R.color.text_secondary));
        gear.setOnClickListener(v -> showMenu());
        topBar.addView(gear);
        root.addView(topBar);

        // ── 列表 ──
        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(pad, dp(4), pad, 0);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(listContainer);

        scroll.addView(scrollContent);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sclp);

        // ── 底部：居中青绿胶囊「＋ 添加定时」──
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(pad, dp(12), pad, dp(24));

        TextView addBtn = new TextView(this);
        addBtn.setText("＋ 添加定时");
        addBtn.setTextSize(16);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams ablp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addBtn.setLayoutParams(ablp);
        int bh = dp(44);
        int bw = dp(180);
        addBtn.setWidth(bw);
        addBtn.setHeight(bh);
        android.graphics.drawable.GradientDrawable addBg = new android.graphics.drawable.GradientDrawable();
        addBg.setColor(getColor(R.color.primary));
        addBg.setCornerRadius(bh / 2);
        addBtn.setBackground(addBg);
        addBtn.setOnClickListener(v -> addItem());
        bottomBar.addView(addBtn);

        root.addView(bottomBar);

        setContentView(root);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        startLuxWatch();

        AlarmScheduler.rescheduleAll(this);
        refresh();
        syncHolidayInBackground(false, "启动");
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmScheduler.rescheduleAll(this);
        refresh();
        startLuxWatch();
    }
    @Override protected void onPause() {
        super.onPause();
        stopLuxWatch();
    }

    private void startLuxWatch() {
        if (sm != null && lightSensor != null && luxListener == null) {
            luxListener = new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent e) { lastLux[0] = e.values[0]; }
                @Override public void onAccuracyChanged(Sensor s, int a) {}
            };
            sm.registerListener(luxListener, lightSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }
    private void stopLuxWatch() {
        if (sm != null && luxListener != null) { sm.unregisterListener(luxListener); luxListener = null; }
    }

    /** 后台同步在线节假日数据：成功则重算下一次触发（工作日可能变化）。 */
    private void syncHolidayInBackground(boolean userTriggered, String source) {
        if (userTriggered) toast("正在同步节假日数据…");
        new Thread(() -> {
            boolean ok = userTriggered
                    ? HolidayApi.sync(this, source)
                    : HolidayApi.syncIfStale(this, source);
            runOnUiThread(() -> {
                if (userTriggered) {
                    toast(ok ? "节假日数据已更新" : "同步失败：网络不可用，继续使用内置表");
                }
                if (ok) {
                    AlarmScheduler.rescheduleAll(this);
                    refresh();
                }
                refreshHolidaySub();
            });
        }, "HolidaySync").start();
    }

    private void refreshHolidaySub() {
        if (holidaySubView != null) holidaySubView.setText(holidayStatusShort());
    }

    /** 设置行副标题：上次同步时间 + 结果。 */
    private String holidayStatusShort() {
        long at = HolidayApi.lastAttempt(this);
        if (at <= 0) return "尚未同步，当前使用内置节假日表";
        boolean ok = HolidayApi.lastOk(this);
        return "上次 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(at))
                + " · " + (ok ? "成功" : "失败")
                + (ok && HolidayApi.fresh(this) ? " · 3 天内不再自动请求" : "");
    }

    /** 节假日数据详情：何时触发同步、结果、缓存内容。 */
    private String holidayStatusText() {
        long at = HolidayApi.lastAttempt(this);
        StringBuilder sb = new StringBuilder();
        if (at <= 0) {
            sb.append("上次同步：从未同步过\n")
              .append("当前判定依据：内置 2026 年节假日表\n\n")
              .append("自动同步：启动 / 开机时检查；成功过 3 天内不再请求，失败则 6 小时后重试");
            return sb.toString();
        }
        String src = HolidayApi.lastSource(this);
        boolean ok = HolidayApi.lastOk(this);
        sb.append("上次同步：")
          .append(new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(at)))
          .append(src.isEmpty() ? "" : "（" + src + "）")
          .append("\n结果：").append(ok ? "成功" : "失败")
          .append("\n数据：").append(HolidayApi.lastNote(this))
          .append("\n\n自动同步：启动 / 开机时检查；成功过 3 天内不再请求，失败则 6 小时后重试");
        return sb.toString();
    }

    private void showHolidayStatus() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.bg_card));
        int pad = dp(22);
        box.setPadding(pad, dp(20), pad, dp(18));

        TextView title = new TextView(this);
        title.setText("节假日数据");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(14);
        title.setLayoutParams(tlp);
        box.addView(title);

        TextView status = new TextView(this);
        status.setText(holidayStatusText());
        status.setTextSize(13);
        status.setTextColor(getColor(R.color.text_secondary));
        status.setLineSpacing(dp(4), 1f);
        box.addView(status);

        TextView syncBtn = new TextView(this);
        syncBtn.setText("立即同步");
        syncBtn.setTextSize(15);
        syncBtn.setGravity(Gravity.CENTER);
        syncBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        blp.topMargin = dp(18);
        syncBtn.setLayoutParams(blp);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(getColor(R.color.primary));
        btnBg.setCornerRadius(dp(22));
        syncBtn.setBackground(btnBg);
        box.addView(syncBtn);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(true)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(getColor(R.color.bg_card));
            bg.setCornerRadius(dp(20));
            dialog.getWindow().setBackgroundDrawable(bg);
        }

        syncBtn.setOnClickListener(v -> {
            syncBtn.setEnabled(false);
            syncBtn.setText("同步中…");
            new Thread(() -> {
                boolean ok = HolidayApi.sync(this, "手动");
                runOnUiThread(() -> {
                    syncBtn.setEnabled(true);
                    syncBtn.setText("立即同步");
                    status.setText(holidayStatusText());
                    toast(ok ? "节假日数据已更新" : "同步失败：网络不可用，继续使用内置表");
                    if (ok) {
                        AlarmScheduler.rescheduleAll(this);
                        refresh();
                    }
                    refreshHolidaySub();
                });
            }, "HolidaySync").start();
        });
    }

    private void showMenu() {
        // 仿参考图：深色卡片 + 两行列表 + 中间大胶囊按钮
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.bg_card));
        int pad = dp(24);
        box.setPadding(pad, dp(20), pad, dp(20));

        // 标题
        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(16);
        title.setLayoutParams(tlp);
        box.addView(title);

        // 行1：光线标定
        TextView row1 = new TextView(this);
        row1.setText("光线标定");
        row1.setTextSize(16);
        row1.setTextColor(getColor(R.color.text_primary));
        row1.setPadding(0, dp(14), 0, dp(14));
        row1.setOnClickListener(v -> { showCalibration(); });
        box.addView(row1);

        View div1 = new View(this);
        div1.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams dlp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        box.addView(div1, dlp1);


        View div2 = new View(this);
        div2.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        box.addView(div2, dlp2);

        // 行3：触发记录
        TextView row3 = new TextView(this);
        row3.setText("触发记录");
        row3.setTextSize(16);
        row3.setTextColor(getColor(R.color.text_primary));
        row3.setPadding(0, dp(14), 0, dp(14));
        row3.setOnClickListener(v -> { showFireLog(); });
        box.addView(row3);

        View div3 = new View(this);
        div3.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams dlp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        box.addView(div3, dlp3);

        // 行4：同步节假日数据（副标题显示上次同步时间与结果）
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.VERTICAL);
        row4.setPadding(0, dp(14), 0, dp(14));

        TextView row4Title = new TextView(this);
        row4Title.setText("同步节假日数据");
        row4Title.setTextSize(16);
        row4Title.setTextColor(getColor(R.color.text_primary));
        row4.addView(row4Title);

        TextView row4Sub = new TextView(this);
        row4Sub.setText(holidayStatusShort());
        row4Sub.setTextSize(12);
        row4Sub.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        row4.addView(row4Sub, subLp);

        holidaySubView = row4Sub;
        row4.setOnClickListener(v -> showHolidayStatus());
        box.addView(row4);

        View div4 = new View(this);
        div4.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams dlp4 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        box.addView(div4, dlp4);

        // 中间大胶囊按钮
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(20);
        btnLp.height = dp(48);

        TextView pillBtn = new TextView(this);
        pillBtn.setText("开始测试");
        pillBtn.setTextSize(16);
        pillBtn.setGravity(Gravity.CENTER);
        pillBtn.setTextColor(0xFFFFFFFF);
        pillBtn.setLayoutParams(btnLp);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(getColor(R.color.primary));
        btnBg.setCornerRadius(dp(24));
        pillBtn.setBackground(btnBg);
        pillBtn.setOnClickListener(v -> { IrSender.send(this, "manual"); toast("已发送红外"); });
        box.addView(pillBtn);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(true)
                .create();
        dialog.setOnDismissListener(d -> holidaySubView = null);
        dialog.show();

        // 深色窗口背景
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(getColor(R.color.bg_card));
            bg.setCornerRadius(dp(20));
            dialog.getWindow().setBackgroundDrawable(bg);
        }
    }

    // ── 列表 ──────────────────────────────────────────

    private void refresh() {
        listContainer.removeAllViews();
        List<AlarmStore.Item> list = AlarmStore.load(this);
        long now = System.currentTimeMillis();
        for (AlarmStore.Item it : list) {
            if (it.repeat == AlarmStore.REPEAT_ONCE
                    && !AlarmStore.isStillValid(it, now)) continue;
            listContainer.addView(buildRow(it));
        }
        if (listContainer.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("还没有定时任务\n点击下方 ＋ 添加");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(15);
            empty.setTextColor(getColor(R.color.text_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(80);
            empty.setLayoutParams(lp);
            listContainer.addView(empty);
        }
    }

    private View buildRow(AlarmStore.Item it) {
        // 外层 FrameLayout 叠两层：下层红色删除，上层可滑动的卡片
        FrameLayout container = new FrameLayout(this);

        // ── 下层：红色删除按钮 ──
        LinearLayout behind = new LinearLayout(this);
        behind.setOrientation(LinearLayout.HORIZONTAL);
        behind.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable redBg = new android.graphics.drawable.GradientDrawable();
        redBg.setColor(0xFFFF6B6B);
        redBg.setCornerRadius(dp(20));
        behind.setBackground(redBg);
        behind.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView delBtn = new TextView(this);
        delBtn.setText("\uD83D\uDDD1  删除");
        delBtn.setTextSize(16);
        delBtn.setTextColor(0xFFFFFFFF);
        delBtn.setOnClickListener(v -> {
            removeItem(it.id);
        });
        behind.addView(delBtn);
        container.addView(behind);

        // ── 上层：可滑动的胶囊卡片 ──
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int cardPad = dp(20);
        card.setPadding(cardPad, dp(16), cardPad, dp(16));
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(getColor(R.color.bg_card));
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);

        TextView time = new TextView(this);
        time.setTextSize(30);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setTextColor(getColor(R.color.text_primary));
        time.setText(String.format(Locale.CHINA, "%02d:%02d", it.hour, it.minute));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.rightMargin = dp(20);
        time.setLayoutParams(tlp);
        row.addView(time);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        center.setLayoutParams(mlp);

        TextView sub = new TextView(this);
        sub.setTextSize(13);
        sub.setTextColor(getColor(R.color.text_secondary));
        String actStr = it.action == AlarmStore.ACTION_ON ? "开灯" : "关灯";
        String lbl = (it.label == null || it.label.isEmpty()) ? "" : "  ·  " + it.label;
        String next = it.enabled && AlarmStore.isStillValid(it, System.currentTimeMillis())
                ? ("  ·  " + nextText(it)) : "  ·  已禁用";
        sub.setText(actStr + "  " + repeatText(it) + next + lbl);
        center.addView(sub);
        row.addView(center);

        Switch sw = new Switch(this);
        sw.setChecked(it.enabled);
        sw.setOnCheckedChangeListener((v, on) -> toggleItem(it.id, on));
        row.addView(sw);

        card.addView(row);

        // ── 手势：左滑 1/5 即可露出删除按钮并可点击 ──
        final int maxSwipe = dp(140);
        final int partial = maxSwipe / 5;  // 露出 1/5 即保持
        final int holdThreshold = partial - dp(4);  // 略低于 partial，好触发

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy)) {
                    if (vx < -100) {
                        card.animate().translationX(-partial).setDuration(180).start();
                    } else if (vx > 100) {
                        card.animate().translationX(0).setDuration(180).start();
                    }
                }
                return super.onFling(e1, e2, vx, vy);
            }
        });

        final float[] touchX = {0};
        final float[] startX = {0};
        card.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = ev.getRawX();
                    touchX[0] = card.getTranslationX();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - startX[0];
                    float newX = Math.min(0, Math.max(-maxSwipe, touchX[0] + dx));
                    card.setTranslationX(newX);
                    break;
                case MotionEvent.ACTION_UP:
                    float tx = card.getTranslationX();
                    card.animate().translationX(tx < -holdThreshold ? -partial : 0)
                            .setDuration(180).start();
                    if (Math.abs(ev.getRawX() - startX[0]) < dp(8) && Math.abs(tx) < dp(5)) {
                        editItem(it);
                    }
                    break;
            }
            gd.onTouchEvent(ev);
            return true;
        });

        // 点击 Switch 时不触发卡片点击
        sw.setOnTouchListener((v, ev) -> false);
        sw.setOnClickListener(v -> {});

        container.addView(card);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        container.setLayoutParams(clp);
        return container;
    }

    private String repeatText(AlarmStore.Item it) {
        switch (it.repeat) {
            case AlarmStore.REPEAT_DAILY:   return "每天";
            case AlarmStore.REPEAT_WEEKDAY: return "工作日";
            case AlarmStore.REPEAT_WEEKLY:  return weekdaysText(it.weekdayMask);
            case AlarmStore.REPEAT_ONCE:
                return String.format(Locale.CHINA, "%d-%02d-%02d",
                        it.onceYear, it.onceMonth, it.onceDay);
            default: return "";
        }
    }
    private String nextText(AlarmStore.Item it) {
        long at = AlarmScheduler.nextTrigger(it);
        if (at <= 0) return "即将触发";
        long diff = at - System.currentTimeMillis();
        if (diff < 0) return "即将触发";
        long mins = diff / 60_000L;
        if (mins >= 60 * 24) return (mins / (60*24)) + "天后";
        if (mins >= 60) return (mins / 60) + "h后";
        if (mins > 0) return mins + "m后";
        return "即将触发";
    }
    private String weekdaysText(int mask) {
        if (mask == 0) return "未选择";
        if (mask == AlarmStore.WEEKDAY_MASK_WORK) return "工作日";
        if (mask == 0x7F) return "每天";
        StringBuilder sb = new StringBuilder("周");
        for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) sb.append(WEEK_NAMES[i]);
        return sb.toString();
    }

    // ── 增删改 ───────────────────────────────────────

    private void toggleItem(int uid, boolean on) {
        List<AlarmStore.Item> list = AlarmStore.load(this);
        for (AlarmStore.Item it : list) if (it.id == uid) it.enabled = on;
        AlarmStore.save(this, list);
        if (on) AlarmScheduler.rescheduleAll(this);
        else AlarmScheduler.cancel(this, uid);
        refresh();
    }
    private void removeItem(int uid) {
        List<AlarmStore.Item> list = AlarmStore.load(this);
        list.removeIf(it -> it.id == uid);
        AlarmStore.save(this, list);
        AlarmScheduler.cancel(this, uid);
        refresh();
    }
    private void addItem() {
        int id = AlarmStore.newId(this);
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        int m = c.get(Calendar.MINUTE);
        AlarmStore.Item it = new AlarmStore.Item(id, h, m, true, "", AlarmStore.ACTION_OFF);
        editItem(it);
    }

    private void editItem(AlarmStore.Item it) {
        final int[] pH = {it.hour};
        final int[] pM = {it.minute};
        final int[] pAct = {it.action};
        final int[] pRep = {it.repeat};
        final boolean[] wkSel = new boolean[7];
        for (int i = 0; i < 7; i++) wkSel[i] = (it.repeat == AlarmStore.REPEAT_WEEKLY) && (it.weekdayMask & (1 << i)) != 0;
        final Calendar onceCalF = Calendar.getInstance();
        if (it.repeat == AlarmStore.REPEAT_ONCE && it.onceYear > 0) {
            onceCalF.set(it.onceYear, it.onceMonth - 1, it.onceDay);
        }
        // 输入框保持用户原值（新建时为空，交给 hint 显示默认值）；完成时才补默认标签。
        final String[] lblHolder = {it.label == null ? "" : it.label.trim()};
        // 保存时间 EditText 引用，完成时主动读取最新值
        final EditText[] hourEtHolder = new EditText[1];
        final EditText[] minEtHolder = new EditText[1];

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.bg_card));
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(12));
        scroll.addView(box);

        // ── 顶栏：左取消（灰） 居中标题 右完成（青绿） ──
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(4);
        top.setLayoutParams(tlp);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(16);
        cancel.setTextColor(getColor(R.color.text_secondary));
        top.addView(cancel);

        LinearLayout titleCenter = new LinearLayout(this);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCenter.setLayoutParams(tclp);
        titleCenter.setGravity(Gravity.CENTER);
        TextView title = new TextView(this);
        title.setText(AlarmStore.load(this).stream().anyMatch(x -> x.id == it.id) ? "编辑定时" : "新建定时");
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getColor(R.color.text_primary));
        titleCenter.addView(title);
        top.addView(titleCenter);

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(16);
        done.setTextColor(getColor(R.color.primary));
        top.addView(done);

        box.addView(top);

        final android.app.AlertDialog[] dialogHolder = new android.app.AlertDialog[1];

        // ── 下次触发提示 ──f
        final TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(16);
        hint.setLayoutParams(hlp);

        // ── 自定义时间选择：两个 EditText 直接编辑 ──
        LinearLayout pickerRow = new LinearLayout(this);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER);
        pickerRow.setPadding(0, 0, 0, 0);

        // 两个时间列共用的 helper
        class TimeCol {
            TimeCol(int initial, int max, boolean hour, int[] valHolder, EditText[] etHolder) {
                EditText et = new EditText(MainActivity.this);
                etHolder[0] = et;  // 暴露引用供完成按钮读取
                et.setText(String.format(Locale.CHINA, "%02d", initial));
                et.setTextSize(34);
                et.setTypeface(Typeface.DEFAULT_BOLD);
                et.setTextColor(0xFFFFFFFF);
                et.setGravity(Gravity.CENTER);
                et.setHeight(dp(36));
                et.setWidth(dp(68));
                et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
                android.graphics.drawable.GradientDrawable etBg = new android.graphics.drawable.GradientDrawable();
                etBg.setColor(getColor(R.color.pill_off));
                etBg.setCornerRadius(dp(10));
                et.setBackground(etBg);
                et.setOnFocusChangeListener((v, has) -> {
                    if (!has) {
                        try {
                            int vv = Integer.parseInt(et.getText().toString());
                            int clamped = Math.max(0, Math.min(max, vv));
                            if (hour) { pH[0] = clamped; } else { pM[0] = clamped; }
                            et.setText(String.format(Locale.CHINA, "%02d", clamped));
                            updateHint(hint, pH, pM, pRep, wkSel, onceCalF);
                        } catch (NumberFormatException e) {
                            et.setText(String.format(Locale.CHINA, "%02d", valHolder[0]));
                        }
                    }
                });
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = dp(6);
                et.setLayoutParams(clp);
                pickerRow.addView(et);
            }
        }

        new TimeCol(it.hour, 23, true, pH, hourEtHolder);

        TextView colon = new TextView(this);
        colon.setText(":");
        colon.setTextSize(34);
        colon.setTextColor(getColor(R.color.text_primary));
        colon.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(6);
        clp.leftMargin = dp(2);
        colon.setLayoutParams(clp);
        pickerRow.addView(colon);

        new TimeCol(it.minute, 59, false, pM, minEtHolder);

        // ── 时间卡片：hint + pickerRow ──
        LinearLayout timeCard = sectionCard();
        timeCard.addView(hint);
        timeCard.addView(pickerRow);
        box.addView(timeCard);

        // ── 动作：label 在外 + 条形 segmented（关灯/开灯 2 合 1） ──
        box.addView(sectionLabel("动作"));
        LinearLayout actSeg = new LinearLayout(this);
        actSeg.setOrientation(LinearLayout.HORIZONTAL);
        actSeg.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable actSegD = new android.graphics.drawable.GradientDrawable();
        actSegD.setColor(getColor(R.color.pill_off));
        actSegD.setCornerRadius(dp(22));
        actSeg.setBackground(actSegD);
        actSeg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams actSegLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actSegLp.bottomMargin = dp(12);
        actSeg.setLayoutParams(actSegLp);

        String[] actOpts = {"🌙 关灯", "☀ 开灯"};
        int actSel = it.action == AlarmStore.ACTION_ON ? 1 : 0;
        for (int i = 0; i < actOpts.length; i++) {
            final int idx = i;
            TextView seg = new TextView(this);
            seg.setText(actOpts[i]);
            seg.setTextSize(15);
            seg.setGravity(Gravity.CENTER);
            seg.setHeight(dp(36));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) slp.leftMargin = dp(2);
            seg.setLayoutParams(slp);
            seg.setSelected(i == actSel);
            pillSetStyle(seg);
            seg.setOnClickListener(v -> {
                pAct[0] = (idx == 1) ? AlarmStore.ACTION_ON : AlarmStore.ACTION_OFF;
                for (int j = 0; j < actSeg.getChildCount(); j++) {
                    TextView tv = (TextView) actSeg.getChildAt(j);
                    tv.setSelected(j == idx);
                    pillSetStyle(tv);
                }
            });
            actSeg.addView(seg);
        }
        box.addView(actSeg);

        // ── 先声明星期/日期行（供 repRow onClick 引用） ──
        final LinearLayout weekRow = buildWeekRow(wkSel, hint, pH, pM, pRep, onceCalF);
        final LinearLayout dateRow = buildDateRow(hint, pH, pM, pRep, wkSel, onceCalF);

        // ── 重复方式：长条 segmented 4 合 1 ──
        TextView repLabel = sectionLabel("重复方式");
        box.addView(repLabel);

        LinearLayout segBg = new LinearLayout(this);
        segBg.setOrientation(LinearLayout.HORIZONTAL);
        segBg.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable segBgD = new android.graphics.drawable.GradientDrawable();
        segBgD.setColor(getColor(R.color.pill_off));
        segBgD.setCornerRadius(dp(22));
        segBg.setBackground(segBgD);
        segBg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.bottomMargin = dp(12);
        segBg.setLayoutParams(segLp);

        String[] repOpts = {"一次性", "工作日", "每天", "每周"};
        int repSel;
        switch (it.repeat) {
            case AlarmStore.REPEAT_ONCE:    repSel = 0; break;
            case AlarmStore.REPEAT_WEEKDAY: repSel = 1; break;
            case AlarmStore.REPEAT_WEEKLY:  repSel = 3; break;
            default: repSel = 2;
        }
        final LinearLayout segContainer = segBg;
        for (int i = 0; i < repOpts.length; i++) {
            final int idx = i;
            TextView seg = new TextView(this);
            seg.setText(repOpts[i]);
            seg.setTextSize(13);
            seg.setGravity(Gravity.CENTER);
            seg.setHeight(dp(36));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) slp.leftMargin = dp(2);
            seg.setLayoutParams(slp);
            seg.setSelected(i == repSel);
            pillSetStyle(seg);
            seg.setOnClickListener(v -> {
                switch (idx) {
                    case 0: pRep[0] = AlarmStore.REPEAT_ONCE; break;
                    case 1: pRep[0] = AlarmStore.REPEAT_WEEKDAY; break;
                    case 2: pRep[0] = AlarmStore.REPEAT_DAILY; break;
                    case 3: pRep[0] = AlarmStore.REPEAT_WEEKLY; break;
                }
                // 刷新 4 段选中态
                for (int j = 0; j < segContainer.getChildCount(); j++) {
                    TextView tv = (TextView) segContainer.getChildAt(j);
                    tv.setSelected(j == idx);
                    pillSetStyle(tv);
                }
                updateDynamicRows(box, weekRow, dateRow, pRep, wkSel, onceCalF);
                updateHint(hint, pH, pM, pRep, wkSel, onceCalF);
            });
            segBg.addView(seg);
        }
        box.addView(segBg);

        // ── 选择星期：独立扁平，拉大与标签间距 ──
        LinearLayout weekContainer = new LinearLayout(this);
        weekContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wclp.bottomMargin = dp(20);
        weekContainer.setLayoutParams(wclp);
        weekContainer.addView(weekRow);
        box.addView(weekContainer);

        // ── 日期行（条件显隐） ──
        box.addView(dateRow);
        updateDynamicRows(box, weekRow, dateRow, pRep, wkSel, onceCalF);

        // ── 标签：label 在框外，EditText 自身是深色圆角 box ──
        box.addView(sectionLabel("标签"));
        EditText lab = new EditText(this);
        lab.setHint("卧室灯");
        lab.setHintTextColor(getColor(R.color.text_secondary));
        lab.setTextColor(getColor(R.color.text_primary));
        lab.setText(lblHolder[0]);
        android.graphics.drawable.GradientDrawable labBg = new android.graphics.drawable.GradientDrawable();
        labBg.setColor(getColor(R.color.pill_off));
        labBg.setCornerRadius(dp(10));
        lab.setBackground(labBg);
        lab.setPadding(dp(12), dp(2), dp(12), dp(2));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(6);
        lab.setLayoutParams(llp);
        box.addView(lab);

        updateHint(hint, pH, pM, pRep, wkSel, onceCalF);

        boolean editing = AlarmStore.load(this).stream().anyMatch(x -> x.id == it.id);

        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setView(scroll)
                .setCancelable(true);
        dialogHolder[0] = b.create();
        dialogHolder[0].show();

        // 深色背景（防止 dialog 窗口白边）
        if (dialogHolder[0].getWindow() != null) {
            int cardColor = getColor(R.color.bg_card);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(cardColor);
            bg.setCornerRadius(dp(16));
            dialogHolder[0].getWindow().setBackgroundDrawable(bg);
        }

        cancel.setOnClickListener(v -> dialogHolder[0].dismiss());
        done.setOnClickListener(v -> {
            // 主动从两个 EditText 读取最新值（防止用户编辑后未失焦就点完成）
            try {
                int h = Integer.parseInt(hourEtHolder[0].getText().toString());
                pH[0] = Math.max(0, Math.min(23, h));
            } catch (NumberFormatException ignored) {}
            try {
                int m = Integer.parseInt(minEtHolder[0].getText().toString());
                pM[0] = Math.max(0, Math.min(59, m));
            } catch (NumberFormatException ignored) {}

            int wkMask = 0;
            if (pRep[0] == AlarmStore.REPEAT_WEEKLY) {
                for (int i = 0; i < 7; i++) if (wkSel[i]) wkMask |= (1 << i);
            }
            if (pRep[0] == AlarmStore.REPEAT_WEEKDAY) wkMask = AlarmStore.WEEKDAY_MASK_WORK;
            int oy = pRep[0] == AlarmStore.REPEAT_ONCE ? onceCalF.get(Calendar.YEAR) : 0;
            int om = pRep[0] == AlarmStore.REPEAT_ONCE ? onceCalF.get(Calendar.MONTH) + 1 : 0;
            int od = pRep[0] == AlarmStore.REPEAT_ONCE ? onceCalF.get(Calendar.DAY_OF_MONTH) : 0;
            // 标签为空 → 用默认「卧室灯」；非空 → 原样保存
            String lb = lab.getText().toString().trim();
            lblHolder[0] = lb.isEmpty() ? "卧室灯" : lb;

            // 检查时间是否已过期
            long nextAt = AlarmScheduler.nextTrigger(new AlarmStore.Item(0, pH[0], pM[0], false, "",
                    AlarmStore.ACTION_OFF, pRep[0], wkMask, oy, om, od));
            if (nextAt <= 0) {
                if (pRep[0] == AlarmStore.REPEAT_ONCE) {
                    toast("该时间已过期，不会触发");
                } else if (pRep[0] == AlarmStore.REPEAT_WEEKLY && wkMask == 0) {
                    toast("请至少选择一个星期");
                    return;  // 不保存，阻止无效状态
                } else {
                    toast("没有可预见的触发时间");
                }
            }

            List<AlarmStore.Item> list = AlarmStore.load(this);
            AlarmStore.Item found = null;
            for (AlarmStore.Item x : list) if (x.id == it.id) { found = x; break; }
            if (found == null) {
                list.add(new AlarmStore.Item(it.id, pH[0], pM[0], true, lblHolder[0],
                        pAct[0], pRep[0], wkMask, oy, om, od));
            } else {
                found.hour = pH[0]; found.minute = pM[0];
                found.label = lblHolder[0]; found.action = pAct[0];
                found.repeat = pRep[0]; found.weekdayMask = wkMask;
                found.onceYear = oy; found.onceMonth = om; found.onceDay = od;
            }
            AlarmStore.save(this, list);
            AlarmScheduler.rescheduleAll(this);
            refresh();
            dialogHolder[0].dismiss();
        });

        if (editing) {
            dialogHolder[0].setOnShowListener(d -> {
                dialogHolder[0].getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFFFF6B6B);
                dialogHolder[0].getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    removeItem(it.id);
                    dialogHolder[0].dismiss();
                });
            });
        }
    }

    // ── 时间格式化 ──────────────────────────────────

    private void updateTimeText(TextView tv, final int[] h, final int[] m) {
        tv.setText(String.format(Locale.CHINA, "%02d:%02d", h[0], m[0]));
    }

    // ── 胶囊按钮工具 ──────────────────────────────────

    private LinearLayout pillRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(6), 0, dp(12));
        return row;
    }

    /** 紧凑等宽胶囊——用于星期 7 个挤一行，weight=1 自动均分 */
    private TextView buildPillCompact(String text, boolean selected, int idx, int total) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (idx < total - 1) lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        tv.setSelected(selected);
        pillSetStyle(tv);
        return tv;
    }

    private TextView buildPill(String text, boolean selected) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        int padH = dp(14), padV = dp(8);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setSelected(selected);
        pillSetStyle(tv);
        return tv;
    }

    private void pillSetStyle(TextView tv) {
        if (tv.isSelected()) {
            tv.setBackground(pillBgSelected());
            tv.setTextColor(0xFFFFFFFF);
        } else {
            tv.setBackground(pillBgNormal());
            tv.setTextColor(getColor(R.color.text_primary));
        }
    }

    /** 给 LinearLayout 里所有 pill 重新上色。selIdx=-1 时保留原有 isSelected 状态仅刷新。 */
    private void refreshPills(LinearLayout row, int selIdx) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView tv = (TextView) row.getChildAt(i);
            if (selIdx >= 0) tv.setSelected(i == selIdx);
            pillSetStyle(tv);
        }
    }

    private android.graphics.drawable.Drawable pillBgSelected() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(getColor(R.color.primary));
        d.setCornerRadius(dp(20));
        return d;
    }
    private android.graphics.drawable.Drawable pillBgNormal() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(getColor(R.color.pill_off));
        d.setCornerRadius(dp(20));
        return d;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        return v;
    }
    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    /** 包一个带描边的深色圆角分组框，内部可以直接 addView 多个子 View。 */
    private LinearLayout sectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        card.setPadding(pad, dp(12), pad, dp(12));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(R.color.pill_off));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), getColor(R.color.divider));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    private void updateHint(TextView hint, final int[] h, final int[] m,
                            final int[] rep, final boolean[] wkSel, final Calendar onceCal) {
        int wkMask = 0;
        if (rep[0] == AlarmStore.REPEAT_WEEKLY) {
            for (int i = 0; i < 7; i++) if (wkSel[i]) wkMask |= (1 << i);
        }
        if (rep[0] == AlarmStore.REPEAT_WEEKDAY) wkMask = AlarmStore.WEEKDAY_MASK_WORK;
        AlarmStore.Item tmp = new AlarmStore.Item(0, h[0], m[0], false, "",
                AlarmStore.ACTION_OFF, rep[0], wkMask,
                rep[0] == AlarmStore.REPEAT_ONCE ? onceCal.get(Calendar.YEAR) : 0,
                rep[0] == AlarmStore.REPEAT_ONCE ? onceCal.get(Calendar.MONTH) + 1 : 0,
                rep[0] == AlarmStore.REPEAT_ONCE ? onceCal.get(Calendar.DAY_OF_MONTH) : 0);
        long at = AlarmScheduler.nextTrigger(tmp);
        if (at > 0) {
            long diff = at - System.currentTimeMillis();
            long mins = diff / 60_000L;
            if (mins >= 60 * 24) hint.setText(String.format(Locale.CHINA,
                    "距离下次响铃还有 %d天%dh", mins / (60*24), (mins / 60) % 24));
            else if (mins >= 60) hint.setText(String.format(Locale.CHINA,
                    "距离下次响铃还有 %dh%dm", mins / 60, mins % 60));
            else hint.setText(String.format(Locale.CHINA,
                    "距离下次响铃还有 %dm", Math.max(1, mins)));
        } else {
            hint.setText("没有可预见的触发时间");
        }
    }

    // ── 标定 ──────────────────────────────────────────

    private void showCalibration() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.bg_card));
        int pad = dp(24);
        box.setPadding(pad, dp(20), pad, dp(20));

        // 标题
        TextView title = new TextView(this);
        title.setText("光线标定");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(8);
        title.setLayoutParams(tlp);
        box.addView(title);

        // 当前 lux：只做实时采样展示，不记录亮/灭两点
        TextView cur = new TextView(this);
        cur.setTextSize(40);
        cur.setTypeface(Typeface.DEFAULT_BOLD);
        cur.setGravity(Gravity.CENTER);
        cur.setText("--");
        cur.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams curl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        curl.topMargin = dp(12);
        cur.setLayoutParams(curl);
        box.addView(cur);

        TextView curUnit = new TextView(this);
        curUnit.setText("lux（实时采样）");
        curUnit.setTextSize(12);
        curUnit.setGravity(Gravity.CENTER);
        curUnit.setTextColor(getColor(R.color.text_secondary));
        box.addView(curUnit);

        // 阈值输入
        TextView thLabel = sectionLabel("触发阈值（低于该值判定为灯灭）");
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thlp.topMargin = dp(18);
        thLabel.setLayoutParams(thlp);
        box.addView(thLabel);

        LinearLayout thRow = new LinearLayout(this);
        thRow.setOrientation(LinearLayout.HORIZONTAL);
        thRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams thrlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thrlp.topMargin = dp(8);
        thrlp.bottomMargin = dp(8);
        thRow.setLayoutParams(thrlp);

        EditText thEt = new EditText(this);
        thEt.setText(String.format(Locale.CHINA, "%.0f", SenseConfig.threshold(this)));
        thEt.setTextSize(18);
        thEt.setTextColor(getColor(R.color.text_primary));
        thEt.setGravity(Gravity.CENTER);
        thEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        thEt.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(4)});
        android.graphics.drawable.GradientDrawable thEtBg = new android.graphics.drawable.GradientDrawable();
        thEtBg.setColor(getColor(R.color.pill_off));
        thEtBg.setCornerRadius(dp(10));
        thEt.setBackground(thEtBg);
        thEt.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams thEtLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        thEtLp.rightMargin = dp(10);
        thEt.setLayoutParams(thEtLp);
        thRow.addView(thEt);

        TextView save = new TextView(this);
        save.setText("保存");
        save.setTextSize(15);
        save.setGravity(Gravity.CENTER);
        save.setTextColor(0xFFFFFFFF);
        save.setHeight(dp(40));
        save.setWidth(dp(76));
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setColor(getColor(R.color.primary));
        saveBg.setCornerRadius(dp(20));
        save.setBackground(saveBg);
        save.setOnClickListener(v -> {
            try {
                float v2 = Float.parseFloat(thEt.getText().toString().trim());
                SenseConfig.setThreshold(this, v2);
                thEt.setText(String.format(Locale.CHINA, "%.0f", SenseConfig.threshold(this)));
                FileLog.i(this, "LT", "set threshold=" + SenseConfig.threshold(this));
                toast("阈值已保存");
            } catch (NumberFormatException e) {
                toast("请输入数字");
            }
        });
        thRow.addView(save);
        box.addView(thRow);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(getColor(R.color.bg_card));
            bg.setCornerRadius(dp(20));
            dialog.getWindow().setBackgroundDrawable(bg);
        }
        dialog.show();

        Button btnDone = new Button(this);
        btnDone.setText("完成");
        btnDone.setTextSize(15);
        btnDone.setTextColor(0xFFFFFFFF);
        btnDone.setHeight(dp(44));
        android.graphics.drawable.GradientDrawable doneBg = new android.graphics.drawable.GradientDrawable();
        doneBg.setColor(getColor(R.color.primary));
        doneBg.setCornerRadius(dp(22));
        btnDone.setBackground(doneBg);
        btnDone.setOnClickListener(v -> dialog.dismiss());

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(18);
        btnDone.setLayoutParams(blp);
        box.addView(btnDone);

        SensorEventListener l = null;
        if (sm != null && lightSensor != null) {
            l = new SensorEventListener() {
                @Override public void onSensorChanged(SensorEvent e) {
                    cur.setText(String.format(Locale.CHINA, "%.0f", e.values[0]));
                    lastLux[0] = e.values[0];
                }
                @Override public void onAccuracyChanged(Sensor s, int a) {}
            };
            sm.registerListener(l, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            cur.setText("无");
            curUnit.setText("无光线传感器");
        }
        final SensorEventListener ll = l;
        dialog.setOnDismissListener(d -> {
            if (sm != null && ll != null) sm.unregisterListener(ll);
        });
    }

    // ── 触发记录 ──────────────────────────────────────

    /** 读取日志里的 #FIRE 行（最新在前），展示每次触发的 lux 与结果。 */
    private void showFireLog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(getColor(R.color.bg_card));
        int pad = dp(20);
        box.setPadding(pad, dp(20), pad, dp(16));

        TextView title = new TextView(this);
        title.setText("触发记录");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(12);
        title.setLayoutParams(tlp);
        box.addView(title);

        List<String> lines = FileLog.tail(this, 10, "#FIRE");

        ScrollView sv = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rows);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(340));
        box.addView(sv, svlp);

        if (lines.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无触发记录");
            empty.setTextSize(14);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(0, dp(12), 0, dp(12));
            rows.addView(empty);
        } else {
            for (String raw : lines) {
                // 原始行：10-28 23:00:00 I/LIGHT #FIRE|标签|动作|当前=x lux|阈值=y|结果
                int idx = raw.indexOf("#FIRE|");
                if (idx < 0) continue;
                String time = raw.substring(0, Math.max(0, idx))
                        .replace("I/LIGHT", "").trim();
                String[] parts = raw.substring(idx + 6).split("\\|");
                String label = parts.length > 0 ? parts[0] : "";
                String act = parts.length > 1 ? parts[1] : "";
                String lux = parts.length > 2 ? parts[2] : "";
                String th = parts.length > 3 ? parts[3] : "";
                String result = parts.length > 4 ? parts[4] : "";

                LinearLayout item = new LinearLayout(this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(0, dp(10), 0, dp(10));

                TextView line1 = new TextView(this);
                line1.setTextSize(14);
                line1.setTextColor(getColor(R.color.text_primary));
                line1.setText(String.format(Locale.CHINA, "%s  %s · %s", time, label, act));
                item.addView(line1);

                TextView line2 = new TextView(this);
                line2.setTextSize(12);
                line2.setTextColor(getColor(R.color.text_secondary));
                line2.setPadding(0, dp(2), 0, 0);
                line2.setText(lux + "  " + th + "  → " + result);
                item.addView(line2);

                rows.addView(item);
                View div = new View(this);
                div.setBackgroundColor(getColor(R.color.divider));
                rows.addView(div, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }

        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(15);
        close.setTextColor(0xFFFFFFFF);
        close.setHeight(dp(44));
        android.graphics.drawable.GradientDrawable closeBg = new android.graphics.drawable.GradientDrawable();
        closeBg.setColor(getColor(R.color.primary));
        closeBg.setCornerRadius(dp(22));
        close.setBackground(closeBg);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp2.topMargin = dp(16);
        close.setLayoutParams(clp2);
        box.addView(close);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(getColor(R.color.bg_card));
            bg.setCornerRadius(dp(20));
            dialog.getWindow().setBackgroundDrawable(bg);
        }
        dialog.show();
        close.setOnClickListener(v -> dialog.dismiss());
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density); }

    // ── 条件显示的子行 ──────────────────────────────

    /** 构造星期胶囊行（完整一~日 7 个，等宽一排）。只当 repeat==WEEKLY 时显示。 */
    private LinearLayout buildWeekRow(final boolean[] wkSel, final TextView hint,
                                      final int[] pH, final int[] pM,
                                      final int[] pRep, final Calendar onceCal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(2));

        TextView label = sectionLabel("选择星期");
        row.addView(label);

        // 紧凑横排——7 个 weight=1 均分宽度
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.CENTER);
        pillRow.setPadding(0, dp(4), 0, 0);
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            final TextView pill = buildPillCompact(WEEK_FULL[i], wkSel[idx], i, 7);
            pill.setOnClickListener(v -> {
                wkSel[idx] = !wkSel[idx];
                pill.setSelected(wkSel[idx]);
                pillSetStyle(pill);
                updateHint(hint, pH, pM, pRep, wkSel, onceCal);
            });
            pillRow.addView(pill);
        }
        row.addView(pillRow);
        return row;
    }

    /** 构造一次性日期行（简洁列表行，点击弹 DatePicker）。只当 repeat==ONCE 时显示。 */
    private LinearLayout buildDateRow(final TextView hint,
                                      final int[] pH, final int[] pM,
                                      final int[] pRep, final boolean[] wkSel,
                                      final Calendar onceCal) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView label = sectionLabel("一次性日期");
        row.addView(label);

        // 列表行：左标签 + 右当前值 + 箭头
        LinearLayout listRow = new LinearLayout(this);
        listRow.setOrientation(LinearLayout.HORIZONTAL);
        listRow.setGravity(Gravity.CENTER_VERTICAL);
        listRow.setPadding(0, dp(12), 0, dp(12));

        TextView left = new TextView(this);
        left.setText("日期");
        left.setTextSize(15);
        left.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        left.setLayoutParams(llp);
        listRow.addView(left);

        LinearLayout space = new LinearLayout(this);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        space.setLayoutParams(slp);
        listRow.addView(space);

        TextView right = new TextView(this);
        right.setTextSize(15);
        right.setTextColor(getColor(R.color.text_secondary));
        updateDateDisplay(right, onceCal);
        listRow.addView(right);

        TextView arrow = new TextView(this);
        arrow.setText("  ›");
        arrow.setTextSize(18);
        arrow.setTextColor(getColor(R.color.text_secondary));
        listRow.addView(arrow);

        listRow.setOnClickListener(v -> {
            final int y = onceCal.get(Calendar.YEAR);
            final int m = onceCal.get(Calendar.MONTH);
            final int d = onceCal.get(Calendar.DAY_OF_MONTH);
            android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this,
                    (dp, yr, mo, dy) -> {
                        onceCal.set(yr, mo, dy);
                        updateDateDisplay(right, onceCal);
                        updateHint(hint, pH, pM, pRep, wkSel, onceCal);
                    }, y, m, d);
            dlg.show();
        });

        row.addView(listRow);

        // 分割线
        View div = new View(this);
        div.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        row.addView(div, dlp);

        return row;
    }

    private void updateDateDisplay(TextView tv, Calendar c) {
        tv.setText(String.format(Locale.CHINA, "%d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)));
    }

    /** 根据重复方式显隐星期行和日期行 */
    private void updateDynamicRows(LinearLayout box,
                                   LinearLayout weekRow, LinearLayout dateRow,
                                   int[] pRep, boolean[] wkSel, Calendar onceCal) {
        int rep = pRep[0];
        weekRow.setVisibility(rep == AlarmStore.REPEAT_WEEKLY ? View.VISIBLE : View.GONE);
        dateRow.setVisibility(rep == AlarmStore.REPEAT_ONCE ? View.VISIBLE : View.GONE);
    }
}
