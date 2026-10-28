---
name: android-ir-timer
description: Android 免解锁定时红外发射完整流程，含酷控SDK逆向拿码、ConsumerIrManager发码、AlarmManager定时、无Studio纯命令行构建。用户说"定时开灯"、"定时开TV"、"定时红外"、"ConsumerIrManager"、"酷控SDK逆向"、"自建APK发红外"时触发。不适用于WiFi/蓝牙/米家等非红外家电、有root需求的场景、已安装 Automate/MacroDroid/Tasker 的场景。
---

# Android IR Timer

在**无 root、无 UI 点击、免解锁**约束下，让 Android 手机在指定时刻自动发出正确的红外时序控制家电。整条流程是通用管道：家电换了（灯 / TV / 空调 / 扫地机），只有码源那一步改动，其余零改动。

## 第一步：前置检查（必须满足才走这条路线）

| 检查项 | 验证命令 | 不通过的后果 |
|---|---|---|
| 手机有红外发射孔 | `adb shell getprop | grep consumerir` 或看设置-更多连接-红外遥控 | ConsumerIrManager.transmit 抛 UnsupportedOperationException，整条路线不通 |
| 家电是红外遥控（非WiFi/蓝牙） | 看遥控器是否有"红外线"小图标，或手机红外遥控App能否搜到 | 小米米家灯、HomeKit、WiFi插座不走红外，要用云API |
| 无 root 但有精确闹钟权限 | `adb shell dumpsys alarm \| grep exactAllowReason` | Android 12+ 精确闹钟要手动授权，首次打开APK会跳系统页 |
| Doze白名单已加 | `adb shell dumpsys deviceidle whitelist +<包名>` | 锁屏后 AlarmManager 被 Doze 抑制 |
| ColorOS/小米/魅族 自启动白名单 | **必须用户在设置里手动开**，adb 改不了 | 息屏后进程被厂商策略冻结，闹钟不会准时触发 |

## 第二步：码源——两条路径

### 路径 A：酷控云端接口（加密，需自研解密）

酷控（Kookong，包名 `com.kookong.sdk`）是 OPPO/小米/荣耀/TCL 红外 App 共同依赖的第三方码库。**离线库和云端响应都是加密的，但我们已经复现了解密算法。**

1. 按品牌/型号查 rid（设备唯一ID）：

```bash
# 搜索接口（不同厂商APPKEY不同，这里用OPPO的）
curl -s "https://sdk2.kookong.com/m/models?areaId=4403&exact=0&name=<家电型号>" \
  -H "Cookie: kksdk=1.8.12" | python3 kookong/decryptor.py --base64
```

2. 拉明文码表：

```bash
# rid 从 OPPO 红外 App 配对页面能看到（设置-我的遥控器-分享）
python3 kookong/fetch_ir.py --rid 8259 --device-type 10 --out out/rid.json
# deviceType: 1=TV, 2=卫星接收机, 4=模拟电视, 5=机顶盒, 6=DVD, 7=显示器, 10=照明
```

3. 从 JSON 里找按键时序：

```python
import json, sys
d = json.load(open(sys.argv[1]))
for key in d['keys']:
    print(key['fkey'], key['pulse'])   # fkey=功能键名, pulse=时序数组
```

### 路径 B：开源库直接抄（明文，推荐优先试）

| 库 | 地址 | 内容 | 质量 |
|---|---|---|---|
| libsigrok4DSLogic IRDB | `github.com/sigrokproject/libsigrok4DSLogic/firmware/IRDB/` | 1500+ 家电，NEC/Samsung/RC5 协议明文 | 实测可用 |
| LIRC database | `lirc.org/remotes/` | 开源 Linux 红外遥控历史库 | 老款家电覆盖好 |
| IRRemote (Arduino) | `github.com/Arduino-IRremote/Arduino-IRremote/tree/master/src/ir_KnownRemoteProtocols.h` | 协议常量 + 命令码 | 大部分是 NEC，可推算 pulse |

如果库给的是**协议+地址码+命令码**（如 Samsung TV 用 Samsung32, addr=0x12, cmd=0x34），需要把它翻译成 ConsumerIrManager 需要的 pulse 数组。`kookong/` 目录里目前没做这步，后续加脚本。

**为什么优先路径 B？** 少一层解密，码直接能用。酷控路径在开源库找不到的家电（比如国内小众品牌照明）时兜底。

## 第三步：码塞进 IrSender

`app/src/com/lighttimer/IrSender.java` 是唯一和家电绑定的文件。

```java
public class IrSender {
    public static final int FREQUENCY = 38180;    // 从码表 JSON 的 frequency 字段来
    public static final int[] PATTERN = { 8992, 4471, 558, 542, ... 72段 ... };  // 从 pulse 字段直接拷贝
}
```

**只改这两处**，其他所有文件一行不动。

## 第四步：构建（无 Gradle，纯命令行）

依赖一次装好（Mac）：

```bash
brew install openjdk
brew install --cask android-commandlinetools
sdkmanager "build-tools;34.0.0" "platforms;android-34"
# ANDROID_JAR = $ANDROID_HOME/platforms/android-34/android.jar
# ANDROID_HOME = $HOME/Library/Android/sdk
```

构建：

```bash
cd app
rm -rf build && mkdir -p build/classes build/dex

javac --release 11 -cp $ANDROID_JAR -d build/classes \
  src/com/lighttimer/*.java

d8 --lib $ANDROID_JAR --min-api 26 --output build/dex \
  build/classes/com/lighttimer/*.class

aapt2 compile --dir res -o build/res.zip       # 没有res可以省略这步
aapt2 link -o unsigned.apk \
  --manifest AndroidManifest.xml \
  -I $ANDROID_JAR \
  --min-sdk 26 --target-sdk 34 \
  build/res.zip 2>/dev/null || \
  aapt2 link -o unsigned.apk \
    --manifest AndroidManifest.xml \
    -I $ANDROID_JAR \
    --min-sdk 26 --target-sdk 34

zipalign -f 4 unsigned.apk aligned.apk

# lt.jks 必须保留，卸载重装要同一签名
apksigner sign --ks lt.jks --ks-key-alias lt \
  --ks-pass pass:lt123 --key-pass pass:lt123 \
  --out LightTimer.apk aligned.apk
```

安装：

```bash
adb uninstall com.lighttimer   # 先卸（lt.jks 不换的话也可以 -r 覆盖）
adb install -r LightTimer.apk
```

## 第五步：权限 & 白名单

```bash
# Manifest 权限（缺一不可）
# TRANSMIT_IR / RECEIVE_BOOT_COMPLETED / SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / WAKE_LOCK

# Doze 白名单（shell 可直接加）
adb shell dumpsys deviceidle whitelist +com.lighttimer

# 精确闹钟授权（首次打开 APK 后，系统设置里自动跳出）
# 或者 adb 打开设置页：
adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM \
  -d package:com.lighttimer

# ColorOS/小米 自启动白名单 → 用户手动：
# 设置 → 应用管理 → LightTimer → 自启动 = 允许 + 后台活动 = 允许
```

## 第六步：验证（从快到慢）

### 6a. adb 直接发广播（绕开 AlarmManager，测 Receiver 链路）

```bash
# 模拟精确闹钟触发 IrAlarmReceiver（TEST 动作会发红外一次并写文件日志）
adb shell am broadcast -a com.lighttimer.TEST \
  -n com.lighttimer/.IrAlarmReceiver -f 0x20
```

### 6b. 文件日志（锁屏后 logcat 可能被清理，外部存储不限制）

```bash
# IrSender.send() 会写 log.txt，路径是 getExternalFilesDir(null)/log.txt
adb shell cat /sdcard/Android/data/com.lighttimer/files/log.txt | tail -20

# 预期看到：
#   trigger uid=1 @13:30
#   ir sent OK (frequency=38180, pattern.len=72)
#   schedule uid=1 -> 明天同时间
```

### 6c. 锁屏定时完整闭环

1. 打开 APK，加一条**最近 2 分钟**的定时
2. 锁屏 + 熄屏 + 把手机放口袋
3. 等到点，看灯/TV 是否响应
4. 拉文件日志确认完整链路

### 6d. dumpsys alarm 检查注册状态

```bash
adb shell dumpsys alarm | grep -A 10 com.lighttimer
# 必须看到 exactAllowReason=policy_permission
# origWhen 是下一次触发时刻（不是当前时间）
```

## 关键踩坑（每个都实踩过，别再犯）

### 1. BroadcastReceiver 无 WakeLock → CPU 在发红外前睡着

`RTC_WAKEUP` 只保证 `onReceive` 执行 **10 秒内**，`onReceive` 返回时 WakeLock 自动释放，红外还没发 CPU 又睡了。

```java
// IrAlarmReceiver.onReceive 必须这么写：
public void onReceive(Context ctx, Intent intent) {
    PowerManager.WakeLock wl = ((PowerManager) ctx.getSystemService(POWER_SERVICE))
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightTimer:IrAlarm");
    wl.setReferenceCounted(false);
    wl.acquire(10_000);                           // 10 秒窗口
    PendingResult pr = goAsync();                  // 延长广播窗口
    new Thread(() -> {
        try { /* 节假日判断 → 发红外 → reschedule */ }
        finally { wl.release(); pr.finish(); }
    }).start();
}
```

### 2. cancelAll 把"今天这条"重挂成"明天"

旧版 `rescheduleAll` 先 `cancelAll` 再逐条 `setExact`，锁屏前某次触发后，某条 alarm 被重挂成"明天"。**改成智能 diff**：对比持久化列表和当前已注册列表，只对变化的 uid 执行 set/cancel。不动没变化的 uid。

### 3. lt.jks 丢了 → INSTALL_FAILED_UPDATE_INCOMPATIBLE

签名证书和 APK 绑定。**lt.jks 必须留在仓库根目录，不能在 `rm -rf build` 时被误删**。要重装先 `adb uninstall` 再 `adb install`。

### 4. run-as: package not debuggable

ConsumerIrManager.transmit 是 normal 权限，不需要 debuggable。但调试时读不到 SharedPreferences。**改用外部存储 `getExternalFilesDir(null)` 写日志和节假日文件**，路径 `/sdcard/Android/data/com.lighttimer/files/`。

### 5. Android 14 SCHEDULE_EXACT_ALARM 权限

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

代码里降级处理：

```java
if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
    am.setAndAllowWhileIdle(RTC_WAKEUP, at, pi);     // 降级
} else {
    am.setExactAndAllowWhileIdle(RTC_WAKEUP, at, pi); // 优先用精确
}
```

### 6. 离线库 kkoffline16.db 的 pulse_data 是加密 blob

别折腾本地 SQLite，直接走云端接口。离线库只有 107 条照明、11892 条机顶盒，但 pulse_data 全走 native `dec`，RC4/XOR/zlib 全解不开。

## 技术速查

### ConsumerIrManager（normal 权限，第三方 App 直接可用）

```java
ConsumerIrManager ir = (ConsumerIrManager) ctx.getSystemService(Context.CONSUMER_IR_SERVICE);
if (ir.hasIrEmitter()) ir.transmit(frequencyHz, patternUs);
```

### AlarmManager.setExactAndAllowWhileIdle（API 23+，Doze 下也响）

每条定时用**唯一 uid 当 requestCode**（避免 PendingIntent 互相覆盖）：

```java
PendingIntent pi = PendingIntent.getBroadcast(ctx, uid, intent,
    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
```

### 自研加解密（StreamHelper_dec2，酷控 SDK 核心）

- 密钥 = 密文前 4 字节，按 `b0|b2<<8|b3<<16|b1<<24` 组合为有符号 32 位整数
- 后续数据走**块长 1..8 循环的自研变换**（非 RC4/XOR/zlib），算法在 `kookong/decryptor.py` 精确复现
- 解完 GZIP 得到 JSON

### Manifest 权限清单（缺一不可）

```xml
<uses-permission android:name="android.permission.TRANSMIT_IR" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 节假日跳过

把 `holidays.txt`（每行一个 yyyy-MM-dd）推到手机：

```bash
adb push holidays.txt /sdcard/Android/data/com.lighttimer/files/holidays.txt
```

## 关键 adb 命令速查

```bash
# 安装 / 卸载
adb install -r LightTimer.apk
adb uninstall com.lighttimer

# 直接触发 Receiver（TEST = 发一次红外；FIRE uid=N = 模拟某条精确闹钟）
adb shell am broadcast -a com.lighttimer.TEST \
  -n com.lighttimer/.IrAlarmReceiver -f 0x20
adb shell am broadcast -a com.lighttimer.FIRE \
  -n com.lighttimer/.IrAlarmReceiver --ei uid 1 -f 0x20

# 看系统注册的闹钟
adb shell dumpsys alarm | grep -A 8 com.lighttimer

# 看文件日志（锁屏后 logcat 可能被清理）
adb shell cat /sdcard/Android/data/com.lighttimer/files/log.txt | tail -30

# Doze 白名单
adb shell dumpsys deviceidle whitelist +com.lighttimer

# 精确闹钟授权跳转
adb shell am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM \
  -d package:com.lighttimer
```

## 文件结构参考

```
LightTimer/
├── .trae/skills/android-ir-timer/SKILL.md   ← 本 Skill
├── app/
│   ├── src/com/lighttimer/*.java            ← 6 个源码，只有 IrSender 换家电时要改
│   ├── AndroidManifest.xml
│   ├── LightTimer.apk                       ← 最终交付
│   └── lt.jks                               ← 签名证书（必须保留）
├── kookong/
│   ├── decryptor.py                         ← 自研 StreamHelper_dec2 解密
│   └── fetch_ir.py                          ← 拉酷控云端明文码表
├── out/                                     ← 拉到的码数据
└── project.md                               ← 完整技术梳理
```

## 换家电 Checklist

下次定时 TV / 空调 / 扫地机：

- [ ] 用 fetch_ir.py（或开源库）拿到新家电的 pulse 时序和 frequency
- [ ] 改 IrSender.java 的 FREQUENCY 和 PATTERN 两处
- [ ] 重编译（同一套命令，同一 lt.jks）
- [ ] adb install -r 覆盖安装
- [ ] 跳过 ColorOS 自启动白名单（已开过）
- [ ] adb broadcast TEST 验证红外真发出去了
