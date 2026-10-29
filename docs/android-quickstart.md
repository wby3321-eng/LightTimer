# Android 开发快速上手与常见问题

> 全部内容来自 LightTimer（免解锁定时红外遥控灯）的开发与排障过程，只保留真正用得上、且本次踩过的点。

---

## 一、先看全局：Android 从 App 到内核长什么样

写代码之前，先知道自己的代码"站在哪一层"。Android 是一个分层的操作系统，你写的 Java 代码只占最上面薄薄一层，几乎所有"系统行为"都发生在下面几层。

```
┌─────────────────────────────────────────────────────────┐
│ ① 你的 App（Java / Kotlin，打成 APK 安装）                │
│    MainActivity · IrAlarmReceiver · AlarmStore           │
├─────────────────────────────────────────────────────────┤
│ ② Framework（Java API，开发者日常调用的就是这一层）         │
│    ActivityManager · PackageManager · AlarmManager       │
│    SensorManager · ConsumerIrManager · SharedPreferences │
├─────────────────────────────────────────────────────────┤
│ ③ System Server（系统核心进程，长期常驻、全局单例）         │
│    AMS(活动管理) · PMS(包管理) · AlarmManagerService       │
│    SensorService · PowerManagerService(电源/wakelock)     │
├─────────────────────────────────────────────────────────┤
│ ④ 原生库 + ART 运行时                                     │
│    libc · libbinder · libsqlite · Skia · ART(JIT/AOT)    │
├─────────────────────────────────────────────────────────┤
│ ⑤ HAL 硬件抽象层（厂商实现，例如红外发射器）                │
├─────────────────────────────────────────────────────────┤
│ ⑥ Linux Kernel（进程调度 · 电源管理 · 各类驱动）           │
│    红外驱动 · 光感驱动 · 闹钟 RTC 驱动                     │
└─────────────────────────────────────────────────────────┘
        ↑↓ 跨层通信靠 Binder（Android 的 IPC 机制）
```

| 层 | 职责 | 本项目在哪里碰到它 |
|---|---|---|
| ① App | 业务逻辑、界面 | 11 个 Java 文件全是这一层 |
| ② Framework | 给你调用的 Java API | `alarmManager.setAlarmClock()`、`consumerIr.transmit()` |
| ③ System Server | 真正的"管理员"，跨 App 全局唯一 | 它才是真正持有你的闹钟、决定何时投递广播的那个进程 |
| ④ 原生库 / ART | 把 Java 字节码跑起来、提供底层能力 | `d8` 把 class 转成 dex，ART 负责执行 |
| ⑤ HAL | 厂商硬件适配 | 红外发射最终由厂商 HAL 实现 |
| ⑥ Kernel | 调度 CPU、管电、驱动硬件 | `PARTIAL_WAKE_LOCK` 本质就是内核 wakelock |

### 1.1 一条定时从注册到发射的完整旅程

把本项目的主链路映射到上面这张图，就能看懂"为什么很多问题不在你的代码里"：

```
你点击保存                     ① App 层
  ↓ AlarmScheduler.schedule()
PendingIntent.getBroadcast()   ② Framework 层：把"到时执行什么"封装成凭证
  ↓ Binder 跨进程调用
AlarmManagerService.set()      ③ System Server：真正把闹钟插进系统闹钟表
  ↓ 到点，内核 RTC 唤醒设备      ⑥ Kernel
AlarmManagerService 派发广播     ③ System Server：决定唤醒哪个进程、投递广播
  ↓ 进程冷启动 + 广播投递
IrAlarmReceiver.onReceive()    ① App 层：此刻才轮到你的代码
  ↓ ConsumerIrManager.transmit() ② → ⑤ HAL → ⑥ 红外驱动
```

**结论：`schedule()` 返回成功 ≠ 到点一定会执行。** 第 ③ 层（系统）和第 ⑤⑥ 层（厂商/内核）都可能在中途拦截。本次 23:00 漏触发，问题就出在 ③ 派发广播时进程被厂商回收——App 层代码完全无辜。

---

## 二、必须懂的 10 个基础概念

| 概念 | 一句话解释 | 为什么重要 |
|---|---|---|
| **APK** | 安装包，本质是个 zip，里面有 `classes.dex` + 资源 + 清单 | 你交付的东西 |
| **包名（package）** | App 的唯一身份证，如 `com.lighttimer` | 系统按包名识别你，签名变更时靠它判断冲突 |
| **UID** | 安装时分配的数字用户 ID，每个 App 一个 | Android 沙箱的基础，权限、内存、杀进程都按 UID 算 |
| **沙箱** | 每个 App 独立 UID + 独立数据目录，互相读不到 | 所以读别人数据要权限，`/data/data/...` 需 root |
| **进程 / 线程** | App 默认一个进程，主线程叫 UI 线程 | 主线程不能做耗时操作，否则 ANR |
| **Looper / Handler** | 主线程的消息循环：不断取消息、执行、再取 | `onReceive`、点击事件都是被投递到 Loop 上的消息 |
| **ANR** | 主线程卡住超过 ~5 秒，系统弹"应用无响应" | 所有网络/文件/传感器等待都不能放主线程 |
| **Context** | "当前所处环境"的句柄，能拿资源、启动组件、取系统服务 | 几乎所有 API 的第一个参数都是它 |
| **Binder / IPC** | Android 的跨进程通信机制 | 你调 `AlarmManager` 其实是在跨进程调系统服务 |
| **DEX / ART** | Java `class` 编译成 `dex`，由 ART 虚拟机执行 | 这就是为什么必须过 `d8` 这一步 |

### 2.1 生命周期（最容易被忽略的一条主线）

- **Activity**：`onCreate`（建界面）→ `onStart` → `onResume`（可交互）→ `onPause` → `onStop` → `onDestroy`。用户切走只是 `onStop`，进程还活着；系统内存紧张才杀进程。
- **BroadcastReceiver**：只有 `onReceive` 一次机会，默认约 10 秒窗口，返回后进程随时可能被回收。**所以要在里面 `goAsync()` + `WakeLock`**。
- **Service**：`onStartCommand` 后可以长期存在，前台服务还有常驻通知。

**关键认知**：生命周期回调不是"你控制的"，而是"系统通知你的"。你的代码只在被叫醒的那一小段时间里拥有执行权。

### 2.2 权限模型

| 类型 | 例子 | 怎么获得 |
|---|---|---|
| 普通权限（normal） | `TRANSMIT_IR`、`RECEIVE_BOOT_COMPLETED`、`INTERNET` | 清单声明即生效，无弹窗 |
| 危险权限（dangerous） | 相机、定位、通讯录 | 运行时弹窗申请，用户可拒绝 |
| 特殊权限 | 精确闹钟、悬浮窗、忽略电池优化 | 需跳设置页授权或系统白名单 |

本项目**只用普通权限**，所以用户装上就能用，没有任何授权弹窗——这是刻意设计的结果。

### 2.3 签名与安装

- APK 必须签名才能安装，签名证书决定"是不是同一个 App"。
- **签名不同不能覆盖安装**：必须 `adb uninstall` 再装，卸载会清空数据和所有已注册闹钟。
- 调试包与正式包签名不同，混装会直接失败。

---

## 三、先建立三个心智模型

**1. App 不是"一直运行的程序"，而是"一堆组件 + 系统按需唤起"**

Android 里没有"main 函数一直跑"这回事。系统会在需要时创建你的进程、调用某个组件，用完就可能杀掉。所以"定时任务"不能靠循环 sleep，只能**注册给系统**，让系统到点唤起你。

本项目对应关系：

| Android 组件 | 作用 | LightTimer 里的实现 |
|---|---|---|
| `Activity` | 一个界面 | `MainActivity`（全部 UI 手写 View） |
| `BroadcastReceiver` | 接收系统/其他应用发来的"广播" | `IrAlarmReceiver`（闹钟到点）、`BootReceiver`（开机） |
| `Service` | 无界面的长任务 | 本项目未用（若要抗冻结才需要） |
| `ContentProvider` | 跨应用共享数据 | 未用 |

**2. 一切都靠 `PendingIntent` 交给系统**

`PendingIntent` = "把一张写好动作的凭证交给系统，到条件时由系统代你触发"。`PendingIntent` 的身份由 **`requestCode` + `Intent` 内容** 共同决定——这是本项目第一个大坑（见 FAQ 1）。

**3. 系统会限制后台执行，且厂商会加码**

Doze、待机分组（standby bucket）、精确闹钟权限、厂商自启动/冻结/进程回收——层层叠加。你的代码"逻辑正确"不代表"一定被执行"。排查此类问题必须看**系统侧记录**，不能只看自己的日志。

---

## 四、最小工程结构（不用 Gradle / 不用 Android Studio）

```
app/
├── AndroidManifest.xml        权限、组件声明（系统据此认识你的 App）
├── lt.jks                     签名文件
├── res/                       资源（图标、颜色、主题）
└── src/com/lighttimer/*.java  Java 源码
```

- **`AndroidManifest.xml` 是唯一入口**：Activity / Receiver / 权限都要在这里登记，否则系统根本不会调用你。
- **`R.java` 是资源索引**，由 `aapt2 link` 自动生成，源码里用 `R.color.xxx` 访问。
- 本项目**没有任何 XML 布局**，UI 全在 Java 里 `new TextView()` 这样拼出来。

---

## 五、本项目用到的核心 API 速查

| 需求 | API / 类 | 关键点 |
|---|---|---|
| 定时到点执行 | `AlarmManager.setAlarmClock()` | 最强的一档：系统视为"闹钟"，状态栏显示图标；`SecurityException` 时降级 `setAndAllowWhileIdle()` |
| 把动作交给系统 | `PendingIntent.getBroadcast(ctx, requestCode, intent, FLAG_IMMUTABLE)` | **requestCode 必须每条任务唯一**，否则互相覆盖 |
| 到点后保活 | `PARTIAL_WAKE_LOCK` + `goAsync()` | CPU 保持清醒，广播执行窗口不被提前掐断 |
| 读光线 | `SensorManager` + `TYPE_LIGHT` | 采样 2s 超时、3 次取中位数；失败**一律跳过** |
| 发红外 | `ConsumerIrManager.transmit(freq, pattern)` | 频率 38180Hz，pattern 是微秒级高低电平数组 |
| 轻量持久化 | `SharedPreferences` / 自写 JSON | 定时列表存 `alarms.json`，阈值存 `sense` |
| 写文件日志 | `context.getExternalFilesDir(null)` | 落到 `/sdcard/Android/data/<包名>/files/`，锁屏后不受 logcat 清理影响 |
| 开机重排 | `BOOT_COMPLETED` 广播 | 只在**重启/覆盖安装**后触发，必须重排所有闹钟 |
| 调色/主题 | `res/values/colors.xml`、`themes.xml` | 深色主题 `#1A2226` 背景 + `#4ECDC4` 强调色 |

---

## 六、权限与系统限制（最容易被低估的部分）

### 6.1 本项目涉及的权限

| 权限 | 用途 | 备注 |
|---|---|---|
| `TRANSMIT_IR` | 发红外 | 普通权限，装上即有 |
| `RECEIVE_BOOT_COMPLETED` | 开机重排 | 普通权限 |
| `SCHEDULE_EXACT_ALARM` | 精确闹钟 | Android 12+ 必需，否则抛异常 |
| `WAKE_LOCK` | 唤醒锁 | 普通权限 |
| `INTERNET` | 拉节假日数据 | 普通权限 |
| `FOREGROUND_SERVICE` | 常驻服务（未用） | Android 14+ 还要声明具体 `foregroundServiceType` |

> 危险权限（相机/定位/存储）要运行时弹窗申请；本项目都不需要，所以**没有任何弹窗**。

### 6.2 后台执行限制的四层

```
第 1 层  Doze（打盹）：熄屏静止后延迟普通闹钟与网络
         → 解法：setAlarmClock 或 setExactAndAllowWhileIdle；加 Doze 白名单
第 2 层  待机分组：按使用频率分 ACTIVE/WORKING_SET/RARE/RESTRICTED
         → 解法：adb shell am get-standby-bucket 查看，别让 App 掉进 RESTRICTED
第 3 层  厂商自启动权限：ColorOS/EMUI 等禁止后台被拉起
         → 解法：设置里手动允许"自启动 + 允许后台活动"，adb 改不了
第 4 层  厂商进程回收 / 冻结：athena 的 o-kill、virtualFreeze
         → 解法：多任务卡片"加锁"，或改成前台服务
```

**关键认知：白名单 ≠ 万事大吉。** 你设了"完全允许后台行为"，它只管第 1、2 层；第 4 层的 `o-kill` 照样会把进程杀掉（本次事故根因）。

---

## 七、无 Gradle 构建：四步流水线

```bash
cd app
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH
ANDROID_JAR=/opt/homebrew/share/android-commandlinetools/platforms/android-34/android.jar
BT=/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0

# 1) 资源编译 + 链接（生成 R.java）
$BT/aapt2 compile --dir res -o build/flat/
$BT/aapt2 link -o build/base.apk -I $ANDROID_JAR --manifest AndroidManifest.xml \
    -R build/flat/*.flat --java build/gen --min-sdk-version 26 --target-sdk-version 34

# 2) 编译 Java
javac --release 11 -parameters -cp $ANDROID_JAR -d build/classes \
    src/com/lighttimer/*.java build/gen/com/lighttimer/R.java

# 3) 转 dex 并塞回 APK
$BT/d8 --lib $ANDROID_JAR --min-api 26 --output build/dex build/classes/com/lighttimer/*.class
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -uj ../unsigned.apk classes.dex)

# 4) 对齐 + 签名
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks lt.jks --ks-pass pass:lighttimer --out LightTimer.apk build/aligned.apk
```

每一步在干什么：

| 步骤 | 工具 | 产物 | 一句话解释 |
|---|---|---|---|
| 1 | `aapt2 compile` | `build/flat/*.flat` | 把 XML/图片编译成二进制资源 |
| 1 | `aapt2 link` | `base.apk` + `R.java` | 把资源和清单组装成 APK 骨架 |
| 2 | `javac` | `build/classes/*.class` | 编译 Java |
| 3 | `d8` | `classes.dex` | Java 字节码 → Dalvik 字节码 |
| 4 | `zipalign` / `apksigner` | 可安装 APK | 4 字节对齐 + 签名（换签名必须先卸载） |

---

## 八、调试方法论（比写代码更重要）

### 8.1 三个层次的信息源

| 层次 | 工具 | 能回答什么 | 持久性 |
|---|---|---|---|
| App 自己的日志 | `FileLog` 写 `/sdcard/Android/data/.../log.txt` | 我的代码走到哪一步 | 长期（>500KB 截半） |
| 系统服务快照 | `dumpsys alarm / activity / package / deviceidle / battery` | 系统认不认为你注册了、有没有执行 | 实时 |
| 事后取证 | `dumpsys activity exit-info`、`dumpsys batterystats --history` | 昨天发生了什么 | 跨天 |

**教训：`logcat` 在锁屏后会被厂商清理，真正排障必须把关键日志写文件。**

### 8.2 常用 adb 命令

```bash
# 触发链路
adb shell am broadcast -a com.lighttimer.FIRE -n com.lighttimer/.IrAlarmReceiver --ei uid 1 -f 0x20
adb shell am broadcast -a com.lighttimer.TEST  -n com.lighttimer/.IrAlarmReceiver -f 0x20

# 看 App 文件日志
adb shell "tail -30 /sdcard/Android/data/com.lighttimer/files/log.txt"
adb shell "grep '#FIRE' /sdcard/Android/data/com.lighttimer/files/log.txt | tail -10"

# 看闹钟是否注册
adb shell "dumpsys alarm | grep -A8 com.lighttimer"

# 看进程是否被谁杀的（本次定位的关键）
adb shell "dumpsys activity exit-info com.lighttimer"

# 看电源/前后台状态历史（跨天可查）
adb shell "dumpsys batterystats --history | grep -i lighttimer"

# 看后台限制
adb shell "am get-standby-bucket com.lighttimer"     # 5=WORKING_SET 正常，40=RESTRICTED 麻烦
adb shell "cmd appops get com.lighttimer"            # 看 RUN_ANY_IN_BACKGROUND 等
adb shell "dumpsys deviceidle whitelist | grep lighttimer"
```

### 8.3 排障的正确顺序

1. **先确认"有没有执行"**：看 App 日志里有没有 `onReceive` 那一行。
2. 没有 → 问题在系统调度层，看 `dumpsys alarm`（闹钟还在吗）→ `exit-info`（进程被谁杀了）→ `batterystats --history`（那一刻设备醒着吗）。
3. 有 `onReceive` 但后续断了 → 问题在自己代码，看执行到哪一步。
4. **永远不要凭直觉下结论**。本次我先怀疑 Doze，用 `batterystats --history` 证明 23:00 设备是醒着的，才把方向转到进程回收。

---

## 九、常见问题 FAQ（全部来自本次真实踩坑）

**Q1：多条定时只有最后一条生效？**
`AlarmManager` 用 `requestCode + Intent` 区分 `PendingIntent`。如果都传同一个 `requestCode`、`Intent` 内容又只差在 extra 里，系统认为是同一个凭证，新的会覆盖旧的。**每条任务用独立的 `requestCode`**（本项目直接用 item id）。

**Q2：锁屏后到点没反应 / CPU 睡着了？**
`RTC_WAKEUP` 只保证"唤醒 CPU 一下"，`onReceive` 返回后 CPU 可能立刻又睡。必须 `PARTIAL_WAKE_LOCK`（本项目 10s）+ `goAsync()` 延长时间窗，并在 `finally` 里释放，否则会漏电。

**Q3：logcat 里什么都看不到？**
锁屏后厂商会清 logcat 缓存。改为**写文件日志**到 `getExternalFilesDir()`，再 `adb shell tail` 读。

**Q4：编译报 `Cannot invoke "String.length()" because ... is null`（d8 阶段）？**
JDK 27 给匿名内部类生成空的 `MethodParameters` 名称，build-tools 34 的 `d8` 读到空名就 NPE。解法：`javac` 加 **`-parameters`**。

**Q5：`aapt2 link` 报找不到 `*.flat`？**
`aapt2 compile -o` 必须是**带尾斜杠的目录**（`-o build/flat/`）；不带斜杠会生成同名 zip 文件，导致 `build/flat/*.flat` 匹配不到。

**Q6：定时到点完全没动静，但电池设置已全部放开？**
逐层排除：`dumpsys alarm` 还在不在 → `exit-info` 有没有 `reason=13 (OTHER KILLS BY SYSTEM)` / `o-kill(4010)` → 是否被厂商进程回收。**"完全允许后台行为"管不到 `o-kill`**（本次根因）。解法：多任务卡片加锁，或前台服务。

**Q7：`setAlarmClock` 抛 `SecurityException`？**
Android 12+ 没声明/没授权精确闹钟。本项目已做 `try/catch` 降级到 `setAndAllowWhileIdle`，先保证能跑。

**Q8：光线传感器读不到值怎么办？**
`onSensorChanged` 可能永远不来（无传感器/被占用）。必须设超时（本项目 2s），超时**跳过不执行**——宁可漏触发，不可误触发（toggle 码重发会反向）。

**Q9：想改系统 `NumberPicker` 的样式？**
ColorOS 的 `NumberPicker` 内部字段反射不可靠，改不动。结论：**自绘一个滑动的选择器**，别和厂商控件较劲。

**Q10：换签名后 `adb install -r` 失败？**
签名不同不能覆盖安装。先 `adb uninstall com.lighttimer` 再装，注意卸载会清掉数据和已注册的闹钟。

**Q11：为什么不给"每周"和"工作日"写两套判断？**
工作日本质就是"按法定节假日表判断"，和周末规则是同一件事。内置一份年度放假/补班表兜底，在线数据只用于补数据，断网不影响判定。

---

## 十、学习路径建议

1. **先懂组件生命周期**：Activity 的 `onCreate/onResume/onPause`、Receiver 的 `onReceive` 执行窗口、Service 的前台/后台。
2. **再懂 `PendingIntent` 和 `Intent`**：这是 Android 所有"跨进程调用"的基础。
3. **然后懂后台限制**：Doze、精确闹钟、前台服务、厂商定制。写"定时类"App 绕不开。
4. **最后学构建**：先用 Android Studio 把 Gradle 跑通，再理解 `aapt2/javac/d8` 各自替代 Gradle 的哪一步。
5. 官方文档优先：`developer.android.com` 的 "Guide to app architecture" 与 "WorkManager / AlarmManager" 章节。

---

## 十一、一页速记

- 组件不是线程，系统随时会杀你 → 定时靠注册，不靠循环。
- `requestCode` 不唯一 → `PendingIntent` 互相覆盖。
- `onReceive` 里先拿 `WakeLock` + `goAsync`，`finally` 里释放。
- 排障先看"有没有执行"，再看"为什么没执行"。
- 白名单有分层：Doze / 待机分组 / 自启动 / 进程回收，四层各不相同。
- 日志写文件，`dumpsys` 看真相，`exit-info` 定死因。
