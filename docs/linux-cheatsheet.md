# Linux 命令学习归档

> 按用途分类，每条命令都标注了本次 LightTimer 开发/排障中的真实用法，可当手册反查。

---

## 零、三个基础认知

**1. 一切皆文件**：设备、进程信息、网络状态都能当文件读，比如 `/proc/<pid>/cmdline`、`/sdcard/...`。

**2. 每个命令都是"过滤器"**：默认读标准输入、写标准输出，所以可以用 `|` 任意拼接。

**3. 参数顺序决定一切**：`grep 模式 文件` 和 `sed -n '起始,结束p' 文件` 的顺序弄反就什么都查不到。

---

## 一、文件与目录

| 命令 | 作用 | 本次用法 |
|---|---|---|
| `pwd` | 当前目录 | 确认在仓库根目录 |
| `ls -la` | 列目录含隐藏文件、权限、大小、时间 | `ls -la` 看 `log.txt` 大小与修改时间 |
| `cd <dir>` | 切换目录 | 进入 `app/` 构建 |
| `mkdir -p a/b` | 递归建目录 | `mkdir -p build/flat` |
| `cp -a src dst` | 递归复制并保留属性（含时间戳） | `cp -a .trae /tmp/lt-keep/` 备份 skill |
| `mv` | 移动/重命名 | 移动构建产物 |
| `rm -rf dir` | 递归强删（**高危，先确认路径**） | 只在 `rm -rf build` 清理产物时使用 |
| `du -sh dir` | 目录占用 | 看 APK 与日志体积 |
| `df -h` | 磁盘剩余 | 排查写入失败 |
| `find . -type f` | 递归找文件 | `find /tmp/lt-keep -type f` 确认备份完整 |
| `file xxx` | 判断文件真实类型 | `file *.jpg` 读出 `1080x2414` 尺寸 |

**易错点**
- `cp -a src/ dst/`：dst 不存在则创建为 dst；已存在则变成 `dst/src`。跨目录恢复文件时用 `cp -a src/. dst/` 更稳。
- `rm -rf` 不可恢复，路径变量为空时（如 `rm -rf $DIR/` 而 `$DIR` 未定义）会删当前目录。

---

## 二、查看与检索（排障主力）

| 命令 | 作用 | 本次用法 |
|---|---|---|
| `cat f` | 全量输出 | 读小文件 |
| `head -n 20 f` | 前 20 行 | 看日志开头 |
| `tail -n 40 f` / `tail -f f` | 后 40 行 / 持续跟踪 | `tail -5 log.txt` 看最新触发 |
| `wc -l f` | 行数 | 日志 6454 行，判断信息量 |
| `grep 模式 f` | 按正则过滤行 | `grep '^10-28 23:' log.txt` |
| `grep -c` | 只输出匹配行数 | 23 点这一小时有 1466 行 |
| `grep -v` | **反选**（排除） | `grep -v 'cancel uid='` 去掉噪音 |
| `grep -A7 / -B2 / -C3` | 带上下文行 | `grep -A8 'com.lighttimer'` 看完整闹钟块 |
| `grep -i` | 忽略大小写 | `grep -iE 'holiday'` |
| `grep -E 'a|b'` | 扩展正则多选 | `grep -E 'FIRE|lux|skip'` |
| `sed -n '1,40p' f` | 取第 1~40 行 | 截取日志片段 |
| `sed -n '5,13p' f` | 取区间（脚本里常用） | 采样器截 `exit-info` |
| `awk '{print $3}'` | 按空白切列取第 3 列 | 取日志里的 tag |
| `cut -c1-5` | 按字符位置截取 | 从 `23:01:21` 取 `23:01` |
| `sort` / `sort -rn` | 排序 / 数值倒序 | 配合 `uniq -c` 做统计 |
| `uniq -c` | 相邻去重并计数 | `sort | uniq -c | sort -rn` 统计频次 |
| `tr '\0' ' '` | 字符替换 | 读 `/proc/<pid>/cmdline` 去掉 NUL |

**最常用的三段组合**

```bash
# 1) 统计某小时内各类日志出现次数
grep '^10-28 23:' log.txt | awk '{print $3}' | sort | uniq -c | sort -rn | head -20

# 2) 按分钟聚合事件发生频率
grep 'rescheduleAll ok' log.txt | awk '{print $2}' | cut -c1-5 | uniq -c

# 3) 过滤噪音看真实业务事件
grep -E '^10-28 23:' log.txt | grep -vE 'cancel uid=|rescheduleAll ok|schedule uid='
```

---

## 三、管道与重定向

| 写法 | 含义 |
|---|---|
| `cmd1 \| cmd2` | 前一个的输出作为后一个的输入 |
| `> f` | 覆盖写 |
| `>> f` | 追加写 |
| `2>&1` | 把错误输出合并到标准输出 |
| `2>/dev/null` | 丢弃错误输出（只保留正常结果） |
| `>/dev/null 2>&1 &` | 静默后台运行 |

本次用法：

```bash
# 收集 stdout+stderr 一起写日志
dumpsys activity exit-info com.lighttimer >> "$OUT" 2>&1

# 跳过不存在的服务报错
adb shell "dumpsys athena 2>/dev/null | head -40"

# 让脚本脱离终端在后台跑
adb shell "setsid nohup /system/bin/sh /data/local/tmp/lt_night_watch.sh >/dev/null 2>&1 &"
```

---

## 四、进程与后台任务

| 命令 | 作用 | 本次用法 |
|---|---|---|
| `ps -A` | 列出所有进程 | 找 pid |
| `ps -A -o PID,NAME` | 自定义输出列 | 确认脚本进程 |
| `ps -A \| grep xxx` | 按名字过滤 | 找 `com.lighttimer` |
| `pidof xxx` | 按名字取 pid | `pidof com.lighttimer` 判断进程存活 |
| `pgrep -f xxx` | 按命令行匹配 | 找脚本 |
| `kill <pid>` / `kill -9` | 终止（先温和再强杀） | 结束采样脚本 |
| `cat /proc/<pid>/cmdline` | 看进程真正的启动命令 | 确认脚本是否还活着 |
| `top` / `dumpsys cpuinfo` | 实时资源占用 | 排查耗电 |
| `nohup cmd &` | 忽略挂断信号后台运行 | 长任务 |
| `setsid cmd` | 新会话运行，彻底脱离终端 | **关键**：adb 断开也不死 |
| `&` `jobs` `fg` | 后台/查看作业/调回前台 | 单次调试 |

**为什么必须 `setsid`**：普通 `adb shell "cmd &"` 的进程挂在 adbd 会话下，连接一断就被清理；`setsid` 建立独立会话，本次用它让采样脚本在手机离开电脑后继续跑。

---

## 五、权限

```bash
chmod 755 script.sh     # 所有者可读写执行，其他可读可执行（脚本标准写法）
chmod 644 file          # 所有者可读写，其他只读（普通文件）
ls -l                   # 看权限位：drwxr-xr-x
```

本次：`chmod 755 /data/local/tmp/lt_night_watch.sh`。

权限位速记：`r=4 w=2 x=1`，三位分别是 **所有者 / 组 / 其他**。

---

## 六、环境变量与 PATH

```bash
export PATH=/opt/homebrew/opt/openjdk/bin:$PATH    # 把 JDK 加到最前面
ANDROID_JAR=/path/android.jar                      # 定义变量供后续复用
echo $ANDROID_JAR                                  # 验证
```

**为什么总是 `...:$PATH`**：保证新路径优先匹配，同时不丢掉原有路径。

---

## 七、时间与日期

```bash
date '+%F %T'                  # 2026-10-29 10:49:06
date +%H%M                     # 1049，适合数值比较
date -d @1793280300 '+%F %T'   # 把 epoch 秒转成本地时间
uptime                         # 开机的绝对时间 + 已运行时长
cat /proc/uptime               # 秒级运行市场
```

本次用途：把 `deep_sleep_predict_start_time=1793280300000` 换算成 `2026-10-29 21:25:00`，从而确定夜间窗口。

---

## 八、网络与文件传输

| 命令 | 作用 | 本次用法 |
|---|---|---|
| `adb devices -l` | 列出已连接设备及型号 | 确认 `PJJ110` 在线 |
| `adb push a /path/` | 电脑 → 手机 | 推送采样脚本 |
| `adb pull /path/ a` | 手机 → 电脑 | 拉日志 |
| `adb shell "cmd"` | 在手机上执行命令 | 全部排查命令 |
| `curl -I url` | 看 HTTP 头 | 验证数据源可达 |

**引号规则（最容易踩）**
- 从电脑传给手机的命令要整体加双引号：`adb shell "grep '^10-28 23:' /sdcard/x.txt"`
- 手机侧还要用 `$` 取值时，电脑侧要转义：`adb shell "awk '{print \$3}'"`，否则电脑 shell 先把它替换掉。

---

## 九、Git 常用（本次解冲突实战）

| 命令 | 作用 |
|---|---|
| `git status` / `git status -sb` | 工作区状态 / 带分支信息 |
| `git log --oneline --graph --all -10` | 一眼看出是否分叉 |
| `git fetch origin` | **只取远端**，不动工作区 |
| `git diff --name-status HEAD origin/main` | 列出差异文件，`D`=删除 `M`=修改 `A`=新增 |
| `git diff --name-only --diff-filter=D HEAD origin/main` | 只列被删除的文件 |
| `git pull --rebase origin main` | 把本地提交叠到远端之上，历史保持线性 |
| `git stash push -m msg` / `git stash pop` | 暂存/恢复未提交改动 |
| `git show --stat --oneline <hash>` | 看某个提交改了哪些文件 |
| `git log --oneline -3 origin/main` | 远端最新提交 |
| `git check-ignore -v <path>` | 查某文件被哪条规则忽略 |
| `git ls-tree -r --name-only <ref> -- dir/` | 查远端某目录还跟踪哪些文件 |
| `git push origin main` | 推送 |
| `git reflog` | 找回被 rebase/reset 丢掉的位置 |

**本次核心流程（远端删了文件，本地要保住）**

```bash
cp -a . /tmp/backup-$(date +%Y%m%d%H%M)          # 1 全量备份
git fetch origin                                  # 2 取远端
git diff --name-status HEAD origin/main           # 3 看清删了什么
git pull --rebase origin main                     # 4 叠加到远端之上
cp -a /tmp/backup-xxx/文件 .                       # 5 把要留的文件拷回磁盘
git check-ignore -v <path>                        # 6 确认已被忽略
```

**红线**：不要 `git reset --hard`、不要 `git clean -fd`（会把拷回的未跟踪文件删掉）、不要 `push --force` 到 main。

---

## 十、报错速查

| 报错 | 原因 | 解法 |
|---|---|---|
| `Broken pipe` | 输出方还在写，接收方（如 `head`）已退出 | 正常现象，忽略；或去掉 `head` |
| `ambiguous argument 'xxx'` | 路径/分支名不存在或拼错 | `git branch -a` 对照 |
| `non-fast-forward` | 本地与远端分叉 | `git pull --rebase` 后再 push |
| `unknown cmd [--help]` | 该服务不支持该 dump 参数 | 换 `dumpsys` 子服务或看源码 |
| `No matches found` | `grep` 没命中 | 先确认路径、再确认模式 |
| `command not found` | PATH 没包含该工具 | `export PATH=...:$PATH` |
| `Permission denied` | 权限位不足 | `chmod 755`，或确认是否需 root |
| 脚本断开连接就死 | 未脱离 adbd 会话 | `setsid nohup ... &` |

---

## 十一、一页速记

```
找东西：grep -rn 模式 目录       看成败：echo $?
看大小：ls -la / du -sh         看时间：date '+%F %T'
去噪音：grep -v 模式            看上下文：grep -A/B/C
统计：sort | uniq -c | sort -rn 截取：sed -n 'a,bp'
后台不死：setsid nohup cmd &    静默：>/dev/null 2>&1
```

**安全习惯**
1. 危险命令（`rm -rf`、`git reset --hard`、`git clean -fd`）执行前先 `echo` 一遍路径。
2. 任何"要不要删文件"的操作前，先 `cp -a` 做备份。
3. 命令先小范围试（`head`、`--dry-run`），确认无误再全量跑。
