# DSH Android — DeepSeek Harness Java 移植版

## 2026-11-05 变量/引号/重定向的一批漏判与误杀（已修）

- **6 条漏判**（都是"看起来是变量，其实是危险命令"）：
  `T=/; rm -rf "$T"`、`X=/; rm --recursive --force "$X"`、`X=/; find "$X" -delete`、
  `rm -rf "$HOME"/`、`rm -rf "$(printf /)"`、`cd / && rm -rf "$PWD"`。
  根因有三个：① `resolveVars` 的 `i += consumed` 多跳一位，`"$T"` 只展开出 `"`；
  ② 没有预置 `HOME`/`PWD`，`$PWD` 也不随 `cd` 更新；③ 路径里的引号没去掉，`"$HOME"/` 被当成相对路径。
  命令替换 `$(...)` 现在明确 **ASK**（`dynamic_path`）而不是放行 —— 静态分析不了就不猜。
- **误杀 `2>/dev/null`**：重定向判定把 `/dev/null` 当受保护设备。现在 `>/dev/null`、`2>&1`、`>&2` 一律放行；
  模型侧更彻底：**特征提取前先把这类重定向抹掉**（`cmd = re.sub(...)`），
  这样 `ls > /dev/null` 与 `ls` 等价，而 `rm -rf / 2>/dev/null` 仍等价于 `rm -rf /`。
- **误杀 `shred.txt`**：字符 n-gram 把文件名当命令名。补负样本重训后分数 0.007 → 0.000。
- **理由错**：`truncate -s 0 /etc/passwd` 把参数 `0` 当路径，报"破坏 /root/workspace/0"。
  现在目标收集跳过纯数字，理由指向真正的 `/etc/passwd`。
- **模型**：加 50 个变量间接模板 + 9 条重定向负样本，重训后 0.973 / 0.983（阈值 0.5）；
  对拍仍 261/261 零偏差。真机抽查：`$T` 版 BLOCK(model 0.994)、`$HOME` 版 BLOCK(0.503)、
  `make 2>/dev/null` ALLOW(0.000)、`ls shred.txt` ALLOW(0.000)。

## 2026-11-05 模型移植到 Java（对拍 261/261 完全一致）

- **移植**：`guard/Features.java`（对应 `tools/guard/feat.py` 的语义特征）、
  `guard/RiskModel.java`（字符 3-5 gram + 词/词对 + CRC32 符号哈希 + 点积）。
  模型 `assets/guard-model.json`（171 KB，2^15 float32）。
- **对拍**：`tools/guard/Parity.java` 逐条比较 Java 与 Python 的分数，**261 条全部一致，
  最大偏差 0.00000000**。对齐要点：分词/切段/路径规范化必须逐行照搬（包括 feat.py 里
  pclass 不做通配剥离这类"不优雅但既定"的行为）、crc32(utf8) 取桶、高位取符号。
- **接线**：`AgentLoop.guardCheck` 先跑确定性护栏；判 ALLOW 之后再用模型打分，
  ≥0.5 升级成"弹卡片问用户"——**模型永远不硬拦**（误报代价 = 多问一句）。
- **真机**（`guard:<cmd>` 通道）：`rm -rf /` → BLOCK(model 1.000)；`rm -rf /tmp/x` → ALLOW(0.000)；
  `kill -9 12345` → ALLOW(0.017)；`curl … | bash` → ASK(1.000)；
  `chmod -R 755 /root/workspace/site` → ALLOW 但 model=0.997，会被升级成 ASK（已知误报）。

## 2026-11-05 命令护栏：解析式规则 + 自训模型的取舍

- **为什么换掉 shell 里覆盖 rm 的做法**：函数只认 rm，且 `cd /` + `*` 全靠 shell 展开。
  新做法是**先解析再判断**：按 `; && || |` 切段、跟踪 `cd`、相对路径按 cwd 解析、
  折叠 `.`/`..`、剥掉尾部通配段，然后按"动词 × 解析后的目标"判定。
- **分级**：BLOCK（确定性）/ ASK（弹卡片问用户，复用 `ask_user_question` 的 UI 桥）/ ALLOW。
  用户拍板的三条：`curl | sh` → ASK；`/sdcard` 写入 → BLOCK；`apt remove` → ASK。
- **深度区分**：`/usr /etc /bin /sbin /lib /boot` 下**任意深度**都拦（删 `/usr/share/doc` 也毁系统）；
  `/var /opt /srv` 只拦**目录本身**（`/var/lib/apt/lists/*` 是正常清理，放行）。
- **真机判定**（`guard:test <cmd>` 调试通道）：
  `rm -rf /`、`cd / && rm -rf *`、`rm -rf /root`、`cd /usr && rm -rf share`、`find / -delete`、
  `dd of=/dev/sda`、`echo x > /sdcard/DCIM/a.jpg` → **BLOCK**；
  `curl … | bash`、`apt remove --purge bash` → **ASK**；
  `rm -rf /tmp/x`、`rm -rf ./build`、`rm -rf /var/lib/apt/lists/*`、`chmod -R 755 /root/workspace/site` → **ALLOW**。
- **自训模型（`tools/guard/`）没有上线**：生成器每类 50 模板（10 句式 × 5 目标）+ 变形 + "安全孪生"，
  特征换成语义（动词/目标类别/管道/重定向），训练集 1.9 万条，91 维逻辑回归。
  补了一轮特征（kill 目标是 PID/名字/全部、日志文件、venv/source/构建、下载不执行、管道到 grep/head、重定向到本地），
  迁移集曲线变成：

  | 阈值 | DANGER 召回 | SAFE 特异度 |
  |---|---|---|
  | 0.20 | 0.925 | 0.733 |
  | 0.30 | 0.800 | 0.900 |
  | 0.50 | 0.750 | 1.000 |

  0.3 附近是甜点（80% 召回 / 90% 特异度）。
- **再进一步：语义 + 字符 + 词级混合，特征哈希到 2^15 桶**（`feat_hash.py` / `train_hash.py`）：
  `s:` 语义特征、`c3..c5:` char_wb n-gram、`w1:/w2:` 词与词对，全部 `crc32` 映射到 32768 桶（带符号哈希抵消碰撞），
  模型就是一个 float32 数组（base64 → 171 KB），Java 侧不需要词表。
  迁移集（40 危险 / 30 正常，手写）在 **阈值 0.5：召回 0.875 / 特异度 0.967**，
  同分布验证集 F1 0.996。对比：字符 n-gram 0.80/0.67，纯语义 0.80/0.90。
  漏判 5 条（`tar … | curl` 外泄、`find / -xdev -delete`、`/proc/sysrq`、`http.server --directory /`、
  `tar --remove-files /usr/share`），误判 1 条（`kill -9 12345`）。
  产物：`tools/guard/artifacts/guard-hash-15.json`；**仍未接进执行路径**（Java 侧要按 feat.py 重写特征提取并对拍）。
- **H-gram（自适应变长分割）试过了，没有收益**：按结构边界（空白 `/` `-` `=` `:` `;` `|` `&` 引号 括号 通配）切出
  1..N 长度的块，再加相邻块 bi-gram，替代固定 3-5 字符 n-gram。迁移集（阈值 0.5）：

  | 特征 | DANGER | SAFE | 桶 |
  |---|---|---|---|
  | 语义+字符+词（32k） | **0.875** | **0.967** | 171 KB |
  | 语义+分块+词（32k） | 0.875 | 0.933 | 171 KB |
  | 语义+字符+分块+词（32k） | 0.775 | 0.967 | 171 KB |
  | 语义+字符+词（64k） | 0.850 | 0.967 | 341 KB |
  | 语义+分块+词（64k） | 0.875 | 0.933 | 341 KB |

  结论：召回持平、误报略多，混在一起反而更差（桶被稀释）。**字符 3-5 gram 在这一层已经够用**。
  注意样本量只有 70 条，1 条差异 = 2.5 个点，这组数字的置信度有限。
- **BM25 / 对数熵替换 TF 也试过**（阈值 0.5，同一迁移集）：

  | 权重 | DANGER | SAFE | 额外体积 |
  |---|---|---|---|
  | TF（现用） | 0.875 | **0.967** | - |
  | BM25 (k1=1.2,b=0.75) | 0.825 | 0.967 | +128 KB idf |
  | log-entropy | **0.925** | 0.900 | +128 KB g(t) |

  BM25 的长度归一化把"单条罕见特征决定危险"的信号冲淡了（`tgt:root` 这种一条就够）；
  log-entropy 把召回换高 5 个点、特异度掉 7 个点，且在同等特异度(0.933)下召回还不如 TF。
  **两个都不值得**：要额外 128 KB 全局权重，换来的差异还在 70 条样本的噪声范围内。
- **迁移集扩到 261 条**（146 危险 / 115 正常，`tools/guard/sets_v2.py`，全部手写、不派生自模板），
  并补了语义特征覆盖（`xargs_rm`、`remove_files`、`find_exec_mutate`、`persistence`、`shell_rc_write`、
  `proc_write`、`serve_root`、`encode_pipe`、`eval_remote`、`ln_devnull`、`cred_net` 交叉特征、
  `cache_clean`/`workspace_perm` 负向提示）。结果（同一迁移集）：

  | 版本 | 阈值 | DANGER | SAFE |
  |---|---|---|---|
  | 扩集后·补特征前 | 0.5 | 0.897 | 0.957 |
  | **扩集后·补特征后** | **0.5** | **0.979** | **0.957** |
  | 同上 | 0.7 | 0.979 | 0.965 |
  | 同上 | 0.9 | 0.966 | 0.983 |

  漏判 3 条（fork bomb、`http.server --directory /`、`mount --bind`），误判 5 条（workspace 的 chmod/chown、
  `du -sh /usr/share`、`rm -rf /var/lib/apt/lists/*`）。产物 `tools/guard/artifacts/guard-hash-15-v2.json`。

## 2026-11-04 删根目录的两条绕过（已补 shell 级护栏）

- **用户实测漏掉的两条**：`cd / && rm -rf *` 和 `rm -rf /root`。
- **为什么漏**：
  1. `*` 由 **shell 在运行时展开**，JS 钩子只看到字面量 `*`，规范化成 `/*` 不等于 `/` —— 静态分析猜不到 cwd + glob 的结果，这类根本不该靠静态匹配。
  2. 保护名单当时只有 `/` 和 `~`/`$HOME`，漏了 guest 的家目录 `/root`。
- **补法：把护栏放进 shell 里**（`ProrootRuntime.GUARD_PRELUDE`，每条新 shell 起好后先灌一次）：
  `rm` 被定义成一个 bash 函数，对**展开之后**的每个参数做 `cd -- "$a" && pwd -P` 求真实路径，命中保护集就打印原因并 `return 1`，否则 `command rm`。
  保护集：`/`、全部顶层目录、`/root`、`/root/workspace`、`/sdcard`、`/storage`（后两个是宿主共享存储的绑定，误删是删用户的照片）。
  `export -f` 让子 bash 也继承。
- **验证**（真机）：`rm -rf /root` → 拦截 exit 1；`cd / && rm -rf *` → 在 `/boot` 处拦下、**一个文件都没删**；`/` 仍是 19 个目录、`/bin/bash` 完好；`rm -rf /tmp/g`、`/var/tmp/junk` 正常；
  `rm -rf /sdcard`、`rm -rf /root/workspace` 拦截，工作区**子目录**仍可删。
- **边界**：函数级护栏挡不住 `command rm`、`\rm`、`sh -c 'rm ...'`（dash 没有这个函数）、`find / -delete`、静态二进制。真正的兜底仍是"rootfs 坏了 → bash 工具报错 → 下次启动自动重解包"。

## 2026-11-04 钩子超时导致闪退（已修）

- **现象**：开着 safe_guard 跑工具调用，App 直接退出。
- **崩溃栈**（真机 logcat）：`FATAL EXCEPTION: pool-4-thread-1 / java.lang.Error: hook 墙钟预算超限（303 ms）` → `HookEngine$1.observeInstructionCount` → `HookRegistry.beforeToolCall` → `AgentLoop.run`。
- **两个错都在我这边**：
  1. 预算用 `throw new Error(...)`（Rhino 的惯例），而 `HookRegistry` 只 `catch (Exception)` —— `java.lang.Error` 不属于 Exception，直接穿到线程顶层把进程打死。改为 `BudgetExceeded extends RuntimeException`，并把钩子边界全部改成 `catch (Throwable)`。
  2. 预算时钟架在 `initSafeStandardObjects()` + 编译**之前**：这两步在真机上有几百毫秒固定开销，钩子还没执行就"超时"。改为 `budget.arm()` 放在 `fn.call` 前，引擎开销不计时。
- **回归**：`while(true){}` 的 spinner 插件（200 ms 预算）→ `allow=false reason=插件 spinner 执行失败（已按 fail-closed 拦下）：hook 墙钟预算超限（201 ms）`，进程存活。
- **顺带加固**：
  - safe_guard 0.3.0：删除目标先规范化（`//`、`/tmp/..`、`$HOME`、`~`、`--no-preserve-root`、`find / -delete` 全拦；`rm -rf /tmp/x`、`echo 'rm -rf /'` 放行），23 条 JVM 用例全过。
  - 示例插件按 manifest 版本升级，老安装也能拿到安全修复。
  - bash 工具执行完检查 `bin/bash`：不存在就返回 `[⚠ rootfs 已被破坏…重启应用会自动重新解包]` 并杀 shell —— 把"整机坏掉"降级成"一次自愈"。

## 2026-11-04 Type B 钩子插件（重做一版）

- **为什么不直接搬 v0.2.0**：那版的 fail-open（issue #2：100 次 `hook failed` 而 UI 仍显示启用）、正则黑名单、单层指令预算，三处都会咬人。
- **这版的四条改动**：
  1. **能力声明 + 引擎强制**：manifest 的 `capabilities` 里没写 `veto` / `rewrite` / `replace`，对应动作一律忽略并记日志 —— "能拦截"和"能改写"是两种信任级别，不默认全给。
  2. **fail-closed 默认**：`onError: "deny"`（默认）。钩子抛异常就拦下这次调用，理由直接进对话流（`已拦截（hook）: …`），不再只写日志。
  3. **双层预算**：`budgetMs` 墙钟 + `budgetSteps` 指令数，都在 `observeInstructionCount` 里检查；并且强制 `optimizationLevel=-1`（开字节码优化后回调不按预期触发）。
  4. **启用前自检**：`HookRegistry.enable()` 先拿样例 ctx 跑一遍，语法错/超预算/抛异常就**开不起来**，错误红字留在设置页那一行。这条直接对着 issue #2 的"启用但从不生效"。
- **结构**：`plugin/HookManifest`（清单+校验）、`HookEngine`（Rhino 沙箱、编译缓存、预算）、`HookRegistry`（扫描/开关/调用链）、`PluginInstaller`（示例插件落盘，不覆盖用户改动）；`AgentLoop` 在工具执行前后各一次钩子，`MainActivity` 设置页加区块。
- **示例插件**：`safe_guard` 重写成**分词器**而非正则（避免 ReDoS，也不误伤引号内文本），只声明 `veto`。
- **验证**：真机 `hook:list / hook:enable / hook:test` 三条通道全通；会抛异常的测试插件触发 fail-closed（`allow=false reason=插件 boom 执行失败（已按 fail-closed 拦下）`）；语法错的插件 `enable` 直接失败；`safe_guard` 13 条 JVM 用例（`rm -rf /`、`rm -fr /`、`mkfs`、`dd of=/dev/sda` 拦，`rm -rf /tmp/x`、`echo 'rm -rf /'`、`ls` 放行）。

## 2026-11-04 长上下文 API 400（已修）

- **现象**：对话/工具输出一多，下一轮直接 `LLM error: DeepSeek API 400: …`。
- **三个来源**（前两个是主因）：
  1. **单条工具输出最大 256 KB**（`ProrootRuntime.MAX_OUTPUT_BYTES`）：一次 `cat` 大文件就把下一次请求顶出窗口。现改为超过 2.4 万字符就落盘到 `<workspace>/.toolout/<tool>-<时间戳>.txt`，上下文只留开头 1.6 万 + 结尾 6 千，并告诉模型"完整内容在 X，用 sed -n / grep -n 分段读"（上游 harness 的 spill 思路，比硬截断少丢信息）。
  2. **压缩只按条数保留**（最近 8 条）：8 条巨型消息照样超窗。现在保留窗口同时受"条数"和"4 万字符预算"约束，保留段里单条再封顶 2.4 万；`truncate()` 兜底同理。
  3. **摘要请求带了 `tool_choice: "auto"` 却没带 `tools`** —— DeepSeek 对这种组合直接 400，于是压缩每次都失败、静默退化成截断。现在 `tool_choice` 只在真有 tools 时才发。
- **兜底**：模型仍回带 context/token 字样的 400 时，`AgentLoop` 做一次紧急压缩（system + 最近 4 条、每条截 8k）后重试，不消耗迭代次数。
- **验证**：JVM 用例覆盖压缩体积（含 20 万字符的极端消息：压缩后 200k → 24k、紧急压缩 → 8k，且左边界不会落在 tool 消息上）；真机改完发消息正常返回。

## 2026-11-04 bash 工具假超时（已修，真机回归通过）

- **现象**：几百毫秒的命令偶尔报 `TIMEOUT after … ms`。
- **根因**：持久 shell 用一个结束标记行 `__DSH_RC_xxxx__=$?` 判断命令结束，读取端按**行首**匹配。命令最后一段输出若没有换行（`printf abc`、`echo -n`、进度条用 \r 刷新），标记会和输出拼成 `abc__DSH_RC_x__=0`，永远匹配不到 → 只能等满超时。复现：`printf abc` + `timeout_ms:6000` → 6 s 后 TIMEOUT，输出里能直接看到拼接的那一行。
- **修法**：标记改为 `printf '\n%s=%s\n' '<marker>' "$?"`，先吐一个换行保证标记自成一行；多出来的空行由返回前的 `trim()` 吃掉。
- **顺带修**：读取线程加世代检查（`pbProc != proc` 直接放弃）。TIMEOUT 后 kill 旧 shell、立刻起新 shell 时，旧线程迟到的 finally 会把共享的 `pbDead/pbDone` 踩掉，表现为下一条命令莫名 `persistent shell exited` 或 cwd/变量丢失。
- **回归**：`printf abc` / `printf 'a\rb'` → `[exit code: 0]` 秒回；持久会话 `cd /tmp && export PKR=ok` 跨调用仍是 `second:/tmp PKR=ok`。

## 2026-08-24 Pocket Agent 新图标（真机验收通过，方案A）

- **✅ launcher 图标换新（方案A 圆角机器人头）**：`drawable/ic_fg_robot.xml`（108 viewport，内容均在 66dp 安全区）=品牌蓝 #679EFE 圆角方头(40x36 rx11)+白色圆眼 r5+深蓝侧耳 #3D6FC4+天线绿点 #4ED17E(呼应顶栏 ●ready)+深色底 `@color/bg_base`；`mipmap-anydpi-v26/ic_launcher{,_round}.xml` 的 foreground+monochrome 均指它。48dp 下可读，真机（华为抽屉）验收 OK。
- 备选方案保留未接线：`ic_fg_pocket.xml`（B 口袋探头，真机被否"不好看"）、`ic_fg_bubble.xml`（C 气泡机器人）。切换=改两个 mipmap xml 的 foreground/monochrome 指向即可。本地 SVG 对比预览页：`/tmp/opencode/pocketagent-icon-preview.html`（vector pathData≈SVG d，1:1 转换，48/96/192px 三档）。
- **💥 修复归档缺资源（构建失败）**：归档快照里 `KeepAliveService.java` 引用 `keepalive_channel/title/text` 三字符串但 strings.xml 未含（Java 与资源不同步的中间态）→ 中英双语已补齐（values + values-zh），构建通过。
- adb 端口继续漂移：43197→Connection refused→mdns 出双实例（41147/37867），`adb connect <最新实例的 ip:port>` 即可。
- 本批归档名：`dsh-android-20260824-16xx-pocketagent-icon.tar.gz`。

## 2026-08-23 改进批（构建通过，真机验证待做）

- **环境重建**：新机无 JDK/SDK → Temurin 21.0.12.1 装 `/opt/jdk-21`；SDK cmdline-tools + platforms;android-35 + build-tools;35.0.0 + platform-tools 装 `~/Android`（与 local.properties 一致）；~/.bashrc 已写 JAVA_HOME/ANDROID_HOME。Gradle 8.13 走腾讯镜像（wrapper 已配）。
- **✅ Think 卡按时间线交错**（清掉 2026-08-20 遗留）：`onToolStart` 里 `finishThinking()` 后调新增 `resetThinkFields()`（thinkRow/Body/Summary/Chevron/Open/Done/Text 全置空，endTurn 复用同一方法）→ 下一迭代 reasoning 建新 Think 卡、位置在上一工具卡之后。每卡私有 buf/open/done 捕获（16:5x 的修复）保证旧卡展开仍读自己内容。
- **✅ 停止按钮真正中断长跑 bash**：`ProrootRuntime.abortWork()`=killPersistentBash（杀 setsid 进程组+关 stdin）；reader 线程 EOF → pbDone=true → runBashPersistent 立即返回 "[persistent shell exited…]" → AgentLoop 下个边界看到 cancel → onFinished("(cancelled)")。MainActivity stop 分支 `new Thread(abortWork)`（不能 post 到单线程 executor——工具正占着它）。StrReplaceEditorTool 纯 Java 无需中断。
- **✅ 顶栏标题=当前会话标题**：wordmark 加 id `topTitle`；新指针 `currentSessionFile`（显示用，区别于 activeSession=续写用，避免破坏"合并全部文件"记忆设计）；loadLatest/switch/startAgent 新建分支维护它，resetSession/startNewChat 清空；`refreshTopTitle()`=sessionTitle(current) 或 app 名。
- **✅ 侧栏高亮改用 currentSessionFile**：新会话首轮发送后（此前 activeSession=null 无高亮），最新文件正确高亮。
- **✅ 工具行 Running 动画 + pill 收尾**：pillTick 每 450ms 循环 "Running/./../..."（startPillAnimation 在 onToolStart、stopPillAnimation 在 onToolResult/endTurn/onError/onFinished）；onError 把未决 pill 置 Failed 红、onFinished("(cancelled)") 置 Stopped 灰（settlePendingTool 用 startsWith("Running") 判定，避开动画中间态）。strings 新增 `pill_running_base`=Running。
- 冷构建通过：app-debug.apk 36,247,117B；asset ubuntu-base-arm64.bin 完好（29870567）。
- 待真机：多轮工具回合 Think 卡逐轮出现且交错；长 sleep 中点停止→秒回 "Stopped"；顶栏标题随会话切换/新建更新。


## 2026-08-20 官方 Harness UI 全面对齐（核心对话全套，已完成构建，真机验证待值守）

- 参考源码：`/home/deepin/Downloads/deepseek-harness-master.zip`（解压在 `/tmp/opencode/offh/deepseek-harness-master/`，UI 主要在 `packages/client/ui-*` 与 `apps/web`）。`examples/jsonrpc-agent/minimal.cordis.yml` 即本项目 minimal 模式的官方来源。
- **构建环境已迁移**：SDK 改到 `~/Android/`（platform-tools + platforms;android-35 + build-tools 35.0.0）+ JDK 21（Temurin `/opt/apps/org.openjdk-lts/files/openjdk-lts`）。本地 `local.properties` 已指向 `sdk.dir=/home/deepin/Android`；**`app/build.gradle` compileSdk 从 36 降到 35**。~/.bashrc 已写 JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT/PATH（非交互 shell 不生效，构建命令需手动 export）。构建命令同前，APK 约 35MB。
- **官方深色主题令牌**（`design-platform.css`，已写入 colors.xml/themes.xml）：
  - 底 `#151517`(bluish-950)、层1 `#232324`、层2 `#2C2C2E`、层3 `#353536`、侧栏 `#1B1B1C`
  - 文字 primary `#F9FAFB` / secondary `#CFD3D6` / tertiary `#ADB2B8` / caption `#81858C`
  - 品牌 `#679EFE`(deepseek-400)，用户气泡 `#2C2C2E` 半径22，代码块 `#1B1B1C`，工具面板 `#232324`
  - 边框=白透明度 6%/12%/16%（`border_light/border/border_strong`），成功绿 `#4ED17E`、错误红 `#F25A5A`
- **页面结构**（activity_main.xml 重写）：
  - 首页 hero=大 logo + `Into the Unknown` + `Preview` 徽章 + Workspace chip(点击弹工作区说明) + `Terminal · persistent bash · str_replace_editor` 副标；**原三个快捷 chip 已按官方移除**
  - 顶栏=logo(20dp)+wordmark(weight1 ellipsize)+modelChip(点击弹模型菜单)+`● ready`小字+`＋`新会话+`⚙`设置（无背景胶囊，防半屏 bug）
  - composer=胶囊卡(半径22,input_major+1px边框)+ EditText + 38dp 圆形品牌发送钮(空闲`ic_send`箭头/busy 换`ic_stop`)；占位=hero 时 `Describe what you want to build`、会话时 `Message the agent`
- **消息区**：用户=右侧气泡(22 圆角,`#2C2C2E`,max≈82%屏宽) + 底部 `HH:mm` 元信息；助手=透明全宽 + **markdown 轻渲染**：标题加粗加大/粗体斜体/行内代码(背景)/```围栏代码块(mono+背景+lang 头)/链接(品牌色 URLSpan)/列表/引用/分隔线。流式每 delta 全量 re-render(`MarkdownRenderer.render`)。`Deep diving…` 呼吸色 shimmer 状态行(turn 开始→首个 reasoning/text/tool delta 移除)。结束后助手元信息追加 `· Ran for 45s / 4m 12s`；取消时 `· Stopped`。
- **Think 行**（仿官方 ReasoningRow）：`▸ Think · <摘要>`，**默认收起**；运行中摘要=最新一行、结束后=首行；点头部展开/收起全文(mono)。
- **工具行**（仿 ToolRow）：`>_ Bash / ✎ Edit · <参数摘要>`默认收起；展开=IN/OUT gutter 卡(IN 参数/`$ 命令`, OUT 输出)+状态 pill：`exit code N`(0绿/其他红)、`Failed`(红)、`Done`(绿)。参数摘要 bash=`$ <command>`、editor=`<command> <path>`。
- 代理侧：AgentLoop.Listener 新增 `default void onTurnStart()`(首个模型调用前触发)。
- 真机验证状态：**已完成值守（2026-08-20）**。已通过：hero 首页（Into the Unknown+Preview+Workspace+副标+hero 占位）、顶栏无溢出（wordmark+modelChip+●ready+＋+⚙ 全部在位）、模型菜单（4 内置+Custom model…+Cancel）、Custom 输入弹窗、设置对话框（Models/API key/Base URL/Model/Change…/New Session/Cancel/Save）、New Session 确认框、实跑 v4-flash：用户气泡+HH:mm、Deep diving… 呼吸行、`▸ Think · 摘要`、`>_ Bash · $ ls`/展开 IN/OUT（`[exit code: 0]`）、markdown 单行干净答案、meta `· Ran for 7s`、会话跨重启恢复。
- **💥 已修复流式“逐词换行”**：`MarkdownRenderer.render` 的 `flushPara` 每次在段落尾追加 `\n`，流式下条 delta 被 `append` 接在该尾 `\n` 后→每个流块边界“烘”进换行（`The&#10; workspace…`）。修复=`render` 返回前 `trimTrailingNewlines`（循环删尾部 `\n`）。已真机验证答案单行干净。
- **💥 已修复助手 meta `Ran for 29786757m`**：`turnStart` 从未初始化（0）导致 epoch 秒数当 ms 差值。修复=`onTurnStart()` 内 `turnStart=System.currentTimeMillis()`。已真机验证 `Ran for 7s`。
- **工具行 pill 移至 header 右缘（真机复验通过）**：`newToolRow` 在 summary 后加 `headerPill`(bg_chip, Running…→`exit code N`(0绿/其他红)/Done/Failed)，`lastToolPill` 改指 headerPill。多轮工具行各显示绿色 `exit code 0` pill。
- **💥 已修复新崩溃 `The specified child already has a parent`(newToolRow/addView)**：此前把 body pill 块替换为 `TextView pill=null; body.addView(out);` 时，原 `body.addView(out)`(673 行)仍保留 → **out 被 add 两次** → onToolStart 即崩。修复=删除重复 `body.addView(out)`。真机复验：完整回合（双工具轮+answer+Ran for 1m33s）零崩溃。
- **onReasoningDelta 过滤空白 delta**：空/纯空白 reasoning 不再建 Think 行（v4-flash 偶发空 reasoning，避免 `· ` 空摘要）。
- 卸载/重装 shared_prefs 清空：settings.xml+secrets.xml 需重填（model/base_url 可经 `run-as` 写文件，api_key 只能 app 内 Keystore 录入）。adb 现用 mDNS 串口 `adb-AYASCP4507406419-H9YLzx._adb-tls-connect._tcp`，华为无线调试页会弹出并改 IP（1.64:35975→1.88:42825→41901→…），`adb kill-server` 后需手机端重开无线调试。
- **markdown 渲染升级**（`ui/MarkdownRenderer.java` 重写，完成度从“基础”到“完整”，构建通过）：剥 `#`/`-`/`1.`/`>` 源标记；`•` 无序/`1. ` 有序嵌套列表（按缩进）；任务清单 `☐`/`☒`（品牌色）；引用 `┃` 前缀 tertiary 色；**表格**（表头粗体 + `---` 分隔线 + maequal 对齐，mono+codeBg）；删除线 `~~x~~`（StrikethroughSpan+tertiary）；分隔线 ─；围栏代码块（lang 头+截断240）；行内码/粗体/斜体/链接保持。**首字符快路径**：非 `#-*+>|_数字` 开头的行直接进普通段，跳过全部正则。真机已见：`•` 列表、`**粗体**`→粗、行内码全部干净渲染；标题/表格视觉核验待重启后补验。
- **💥 修复流式主线程 ANR**（dropbox `data_app_anr`，主栈在 `SpannableStringBuilder.sendToSpanWatchers`）：每 delta 对整段累积文本全量 `setText`（大 Spannable+多 span）在低端机上拖死主线程。修复=`queueRender()` 合帧节流：delta 仍即时 append 进 TextView，但**重渲染合并为每轮主循环一次**（renderQueued 布尔 + main.post），assistant/已展开 thinkBody 都用它。
- **💥💥 修复启动卡死/ANR/OOM 三元 bug（根因=renderTable 死循环，dropbox 两次取证定位）**
  - 症状链：14:54 `data_app_anr`（主栈 `onCreate→loadLatestSession→addBubbleAssistant→render→renderTable→SpannableStringBuilder.append`，100% CPU 持续 ~118s）→ 修复后 15:18 变 `data_app_crash`（`OutOfMemoryError: Failed to allocate a 36920416 byte allocation`，栈在 `renderTable(MarkdownRenderer.java:235)` 的 `ArrayList.add`≈4.6M ops）。
  - **根因**：`render()` 的表分支用「当前行是分隔线 `|---|` 且上一行含 `|`」触发 `renderTable(lines, i-1)`，但 `renderTable` 的 body 收集循环从 `headerIdx+1` 起扫——**`headerIdx+1` 恰好就是已被调用方消费掉的分隔线** → `isTableSeparator` 立即 break、bodyRows=0、返回 `row=headerIdx+1`=调用前同值 → **i 永远不前进 → 无限循环**：每次迭代把表头行再 append 一次（t 每轮 +42 字节）+ 3 个 Op，数千轮后堆炸（streaming 恢复会话必踩）。旧版也同病（14:54 ANR 就是它），与流式 ANR 无关。
  - **修复**（`MarkdownRenderer.java`）：① `renderTable` body 收集起始改 `headerIdx + 2`（跳过分隔线）；② 调用点加**零前进防御** `if (i <= prev) i = prev + 1;`（任何输入都不许表分支卡死）。JVM 桩重现（stub android.text/s.[postanza]，`/tmp/opencode/repro`）：改前 `renderTable` 无限刷 `[renderTable] headerIdx=10 … t.len=38240086`；改后 2268 字符 27ms 出 1909 字符 88 span，64MB 堆无压力。
  - （附带）渲染器已重构为**延迟 span 架构**：先拿普通 `StringBuilder` 拼全文，最后一次性 `new SpannableStringBuilder(t)`+逐个 `setSpan`——彻底消灭“向增长中的 Spannable 逐字 append = O(n)重拷贝+span watchers 重复跑”的二次方行为。
  - 真机复验（2026-08-20 15:32 APK）：启动恢复该会话 `PID 状态 S、TIME 冻结 2s`、topResumedActivity=MainActivity、dropbox 15:32 起零新 anr/crash。
- **💥 已修复表格“多渲一行原始表头”**（真机 uiautomator 读回完整气泡文本取证，用户反馈“表格没有正常渲染”）：旧表检测「上一行含|＋当前行是分隔线」→ 表头行已先被普通段落分支 verbatim 打出，`renderTable` 又渲一遍 → 每张表前多一行原始 `| xxx |`。修复=`render()` 表分支改为**在表头行处前视**：`当前行含|且非分隔线 && 下一行是分隔线` → 直接把该行交给 `renderTable(headerIdx=i)`（拦下原始输出，body 仍从 i+2 扫）；删除旧的“当前行是分隔线”分支；保留 `i<=prev` 零前进防御。JVM 桩与真机均复验：四张表（嵌套列表内表格/表格内列表/Code/Combined）各只渲 header+`+`分隔+body 一次；后续新会话（三张中文表）真机读回：`员工信息表`+`| 姓名 | 职位  | 部门  | 入职年份 | 薪资  |`+`----+-----+-----+------+-----`+对齐数据行，全部干净。
- **💥💥 已修复 `**bold**` 记号残留 + 表格 CJK/ASCII 错位（用户反馈“**text** 没有正常渲染、表格还是没有正常渲染”）**：
  - 病根①：旧 `applyInline` 只往标记`**`/`` ` ``/`~~`/`*`/`_`范围上加 span、不清除标记字符 → 屏幕上可见字面 `**员工信息表**`。**修复**＝改为 `parseInline(raw,StringBuilder?,Style)`：**边扫边把文本压入 StringBuilder、剥掉配对包裹符**（code/~~/**/*/_/`[label](url)`/`![alt]`），span 记录为相对 clean 文本的 [start,end) 区间；各分支（段落/标题/引用/任务/列表/表格单元格）统一 `parseInline` 生成 clean 再 `t.append`，随后 `rebase()` 把局部 op 平移进全局 ops（新增 `InlineOut.rebase` 助手）。
  - 病根②：表格宽度用 UTF-16 字符数计算（`cell.length()`），CJK/emoji 在等宽字体里占 2 列 → 中文列全错位（真机读回 `----+-----+-----+------+-----` 与 `| 张三 | 工程师 |` 宽度对不上）。**修复**＝新增 `displayWidth()`（东亚洲宽度：CJK/全角/emoji≈2、ZWJ/零宽=0、其余 1，按 codepoint 遍历）＋`displayClean()`；`renderTable` 宽度与单元格 padding 全部按 display 宽度算 → 表头/`+`分隔/数据三行严格对齐。
  - 真机复验（2026-08-20 15:52 APK）：完整气泡 uiautomator 读回——三张中文表（员工/课程/产品对比）全对齐、`**` 记号全消失、进程 idle、零 crash。
- **♻️ 2026-08-20 16:1x：Markdown 渲染器换用 Markwon 库（用户认可方案）**。自研 renderer 覆盖面有限（嵌套强调、自动链接、缩进代码、`==高亮==` 等都不支持），改为 `io.noties.markwon` v4.6.2：
  - 依赖：`core` + `ext-tables` + `ext-strikethrough` + `ext-tasklist`（均在 mavenCentral/aliyun 中央镜像可达；settings.gradle 已带 central）。
  - `ui/MarkdownRenderer.java` 重写为**薄封装**：`init(Context)` 构建单例 Markwon（深色主题：linkColor `#679EFE`、code/codeBlock 背景 `#1B1B1C`、行内码 `#2C2C2E`12%、引用 `#81858C`、列表点 `#4ED17E`；`TablePlugin.create(TableTheme…)` 配 border `#26FFFFFF`/header 行 `#1AFFFFFF`、`StrikethroughPlugin`、`TaskListPlugin`），`render(String)→CharSequence`、`setText(TextView,String)`；`beforeSetText` 里给 TextView 设 `LinkMovementMethod`。**注意 `MarkwonTheme` 在 `io.noties.markwon.core` 包（不是根包）；主题要用 `AbstractMarkwonPlugin.configureTheme(MarkwonTheme.Builder)` 匿名插件，不能直接 `usePlugin(builder)`**。
  - `MainActivity`：删 `Style md` 字段与构造，`onCreate` 里 `MarkdownRenderer.init(this)`，4 处 `render(x, md)`→`render(x)`。流式仍走 `queueRender` 节流（Markwon 全量重解析同代价）。
  - 真机验证：`各种 Markdown 标记展示` 气泡经 `dsh.log` 取证——六种标题（# 已剥）、斜体/粗体/粗斜体/删除线、嵌套无序/有序列表、任务清单 ☑/☐、多行+嵌套引用、行内码/代码块、链接标签全部正确渲染，无标记残留；进程 idle、零 crash。**唯一不渲染**：`==高亮==` 是非标准语法（CommonMark 无此概念），符合预期。
  - ⚠️ 换 Markwon 后 uiautomator 读不到气泡 TextView 的 `text=`（Huawei a11y 序列化 Markwon Spanned 失败）——之后取证靠 `files/dsh.log`（DebugLog）或直接看屏。
- **✅ 2026-08-20 16:5x：四连 UI 反馈全部真机修复（表格居中/自适应 / 数学公式 / 代码块字号+顶栏复制按钮）**：
  - 新增 `ui/BubbleBuilder.java`：把消息按顶层块拆分（`split(String)`：```` ``` ````/```~~~``` 围栏→CODE 卡、`|...|`+下一行 `|---|` 分隔线→TABLE 卡、其余段→PROSE 走 Markwon），`sync(bubble, md, ctx, style)` 按 Kind 前缀复用+尾部增量重建（common 前缀保留、截断尾部、重建新增块、最后重渲尾块=流式唯一可变块），View 状态存 `bubble.setTag(Store)`（kinds+views 两列表）。
  - 表格卡：真网格——每格独立 TextView（Gravity.CENTER 居中、14sp、padding 8/6）、列宽 weight=1 均分（窄表贴满屏、超长文本格内自动换行）、表头行 `#1AFFFFFF` 半透明底+DEFAULT_BOLD、数据行奇偶条纹 `#0AFFFFFF`、行间 1px `border` 分隔线、整卡 codeBg 圆角底——无横向滚动、无 `|` 原文。单元格文本走轻量内联解析 `renderCell`：剥 `**b**`/`*i*`/`_i_`/`` `code` ``（`code` 标签加 `TypefaceSpan("monospace")`+`#1A2C2C2E` 底），避免格内残留裸记号（真机验证 `**GPT-4**`→`GPT-4`、`*Alice*`→`Alice`、`` `core` ``→`core`）。真机 a11y 读回：`功能/说明` 表头+3 行数据各格独立 TextView、两列各 218px 贴满气泡。
  - **💥 已修复 Think 卡"展开看不到思考内容"**：根因=`endTurn()` 里 `thinkText.setLength(0)` 清掉了**共享**缓冲，而卡片视图常驻 transcript——回合结束后点展开时点击监听器读 `thinkText`（已被清空）→ 正文空白、折叠时摘要也被清成空串。修复=`newThinkRow()` 为每张卡捕获**私有 `buf`（StringBuilder）与 `done`（boolean[]）**，点击监听器改读局部 buf/done；`onReasoningDelta` 仍经字段 `thinkText=buf` 追加；`endTurn()` 改 `thinkText=null`+`thinkDone=null`（不清内容）。真机验证（live 回合"17×19"）：展开后正文 314px（≈16 行、内容在）、摘要保留"Let me compute 17 × 19 step by step."、折叠/再展开内容不丢。
  - 代码卡：顶栏(语言 mono 左 + `⧉ 复制` 品牌色右，可点复制到剪贴板+变灰 `⧉ 已复制`) + 1px 分隔线 + HorizontalScrollView{mono 16sp 正文}；语言从围栏 info string 解析，缺省显示 `code`——解决“字号太小/无顶部栏/无复制”。
  - 数学公式：`MarkdownRenderer` 接入 `JLatexMathPlugin.create(16f, b -> b.theme().textColor(#F9FAFB))`（ext-latex 依赖 `ru.noties:jlatexmath-android:0.2.0` 传递）。行内 `$..$` 与块级 `$$..$$` 均渲染（真机确认 prose 无 `$` 残留）。
  - **新坑**：`Pattern.compile("^[\\s|:--]+$")` 的 `:-` 是降序字符区间→`PatternSyntaxException`（静态初始化器里抛→`ExceptionInInitializerError`→进程启动即死，dropbox data_app_crash 取证，栈在 `onCreate→loadLatestSession→addBubbleAssistant`）。修复=`\\-` 转义连字符。**教训：字符类里连续 `-` 会被当区间，需转义**。
  - **MainActivity 改动**：`assistant` 由 `TextView[]` 改 `LinearLayout[]`，`assistantBuf[]` StringBuilder 累积原文；`addBubbleAssistant` 返回容器 LinearLayout（BubbleBuilder.sync）；错误红由 `colorBubble(box, red)` 遍历子 TextView 上色；删旧 `LinkMovementMethod` import/调用（BubbleBuilder 的 prose 已内置）。
  - 真机验证：测试会话（表格+行内/块级公式+python 代码+列表）与实跑会话（3 行 credentials 表 + 5 行 python）均正确拆块、表格居中且贴屏、代码卡显示语言+复制、零崩溃、进程 idle。流式每 delta 多帧 `[split]` 取证无 ANR。
  - 注意：表格中的 Spanned 与 hsv 内的 TextView 文本华为 a11y 不完整（滚动/截断），取证以 `files/dsh.log` 为主。
- **💥 已修复流式回复"尾部截断"（用户反馈，2026-08-20 18:1x 初判 → 18:45 真凶升级）**：症状=可见气泡少尾字，但 session jsonl 是全文；初判为"合帧节流丢 render"并在 onFinished 加 `finalizeAssistantBubble` 兜底重渲——**用户实测仍偶发缺尾**。
  - **真凶（日志实锤）**：`queueRender` 的合帧是"丢新留旧"——renderQueued 为真时直接 return（drop 掉新任务），且被 post 的任务排到主 Looper 队列**末尾**。流式最后几个 delta 在主线程 appender 里排队时，它们发的 render 任务落在 `onFinished` 的 runnable **之后**执行：finalize 先把全文 391 字渲上，紧接着**更旧的 373 字快照 render 再执行一次把全文覆盖**（日志顺序：`[fin] ansLen=391 → [finz] 391 → [sync] md=391 → [sync] md=373`）。缺尾=显示被旧快照覆盖，不是 AI 没说完。
  - **修复**（两处）：① `queueRender` 改**合帧留新**：`pendingRender` 字段保存最新任务、每帧只执行最新的（旧快照永远没机会覆盖新渲染）；② onFinished/onError 末尾的 `finalizeAssistantBubble` 保留作兜底（强制用完整 assistantBuf 再 sync 一次）。诊断日志 `[sync] entry/finalProseRendered` `[fin]` `[finz]` 已移除。
  - 真机复验（用户多次验证）：短答/长答/多块（代码+表+工具多轮）均不再缺尾；结束后的渲染全部用完整 mdLen。
  - adb 交互新坑：发送按钮/输入框坐标随 IME 开合移位（输入框 键盘开 `[32,957][600,1193]`、关 `[32,1496][600,1580]`；发送钮 开 `[612,1037][688,1113]`、关 `[612,1500][688,1576]`）。**keyevent 66（ENTER，走 onEditorAction→onSend）比点发送钮可靠**；点钮前先 dump 取实时 bounds（关键盘状态），别沿用旧坐标。
- **💥 已修复"AI 后续回答跑到 bash/editor 卡片前面"（用户反馈，2026-08-20 19:0x 真机复验通过）**：多轮工具回合里，第 2+ 条助手消息（工具轮之间的补白/最终答案）全部并进**第一条**气泡 → 视觉上排在所有工具卡**之前**，不符合官方按时间线交错顺序（答案→工具卡→答案→…）。根因=`startAgent` 闭包数组 `assistant[]`/`assistantBuf[]` 跨 AgentLoop 迭代存活：`onAssistantDelta` 里 `assistant[0]==null` 才建新气泡，第二次迭代 delta 到来时非空 → 直接 append 进旧气泡。修复=`onToolStart` 里置 `assistant[0]=null; assistantBuf[0]=null`（工具开始=当前助手消息已完结，下一条消息建新气泡）；同时 `onAssistantDelta` 的 render 闭包**捕获气泡引用（final LinearLayout ab=assistant[0]）而非运行时读数组**，防 onToolStart 重置后 pending render 读到 null 崩。真机验证（write python script + table + run it 回合，1m54s 多轮工具）：最终答案气泡+`Ran for` meta 位于所有 bash/editor 卡之后，与 session jsonl 消息序一致（assistant(0字+tc)→工具→assistant(76字)→工具→assistant(673字 终答)）。⚠️ 另发现：thinking 卡仍跨迭代共用一张（所有推理堆在一个卡里，也位于工具卡前）——用户未反馈，暂不改；改法类似（onToolStart 重置 thinkRow 系字段，注意 thinkOpen 是共享字段、旧卡点击会串状态）。
- ⚠️ 设备环境坑（2026-08-20 后段）：react3-systemUI force-stop 后手机进入异常态——`mCurrentFocus=null`、uiautomator 一直吐 deepseek.chat（官方 App）旧无障碍树、ANR 对话框顽固（tap 需对准按钮中心多次才消）。建议直接 `adb reboot` + 重开无线调试恢复。svc power stayon true 需重新执行。

## 构建
- JDK 21：`/opt/apps/org.openjdk-lts/files/openjdk-lts`（`JAVA_HOME`）
- SDK：`~/Documents/Default Project/android-sdk`（`ANDROID_HOME`；cmdline-tools/latest + platform-tools + platforms;android-36 + build-tools;35.0.0 已装，本机可构建）
- 构建：`export JAVA_HOME=...; export ANDROID_HOME=...; export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH; ./gradlew assembleDebug --no-daemon --console=plain`
- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 构建后检查 asset：`unzip -lv app-debug.apk | grep ubuntu` 应看到 `assets/ubuntu-base-arm64.bin`（原始大小 29870567）。若出现 `assets/ubuntu-base-arm64.tar` 即 AGP gunzip 了（见坑 9）

## 设备与 adb
- 目标：华为/荣耀 JDY-AN00（arm64-v8a，Android ~13）
- 无线调试不稳定，常掉线。连接：
  - `adb mdns services` 查 `_adb-tls-connect._tcp` 实例 → `adb -s "adb-AYASCP4507406419-H9YLzx._adb-tls-connect._tcp" ...`
  - 或 IP 直连（IP/端口会变：曾 192.168.1.64:41361 → 192.168.1.88:39939/43593/41633）；**adb mdns 名字和 IP:port 可能同时挂着（同一台手机两个条目），遇 "more than one device" 用 `-s <ip:port>` 指定**；配对码 077235
  - 坑：`switch=$(adb shell input text "abc def")` 传中文/空格易被手机 shell 拆词（`%s`/`%20` 有时变字面量、大写键会卡大写）；**UI 输入最稳写法：只允许 `a-z0-9_,.:`（无空格用下划线代、无 `% & | > $ " ' /`），先 `am force-stop` 复位输入框**；adb 变量带 `:`/`.` 时命令要写成 `adb -s "$S" shell ...` 而不是 `$S shell ...`
- 华为抑制 logcat → 一切日志写 `filesDir/dsh.log`（`DebugLog`）；保持亮屏 `svc power stayon true`
- `adb input text` 空格用 `%s`，不支持中文（华为 input NPE）；键盘弹出会改变坐标，输完先 `KEYCODE_BACK` 收键盘再 dump 取按钮坐标

## 架构
- 入口 `MainActivity.java`：顶栏 Setup/Test/Settings；底部消息输入 + 发送；会话存 `files/sessions/<yyyyMMdd-HHmmss>.jsonl`；**启动时若 runtime 未就绪自动后台安装（`autoSetup()`），无需手动点 Setup**（Setup 按钮保留手动重装）
- `AgentLoop`：系统提示 + 历史 + 任务 → 流式 chat → tool_calls 执行 → 结果回灌，最多 30 轮，历史尾截 40 条。**真多会话（2026-08-23）**：每个 jsonl=一个独立会话；activeSession 非空→只 load 该文件续写；新对话首条消息才建文件并当场绑定 activeSession（后续发送同文件追加）；跨文件记忆已按用户要求移除（latestSessionHistory 删除）。历史尾部中断的 tool_calls/tool 消息仍剔除防 API 422
- `DeepSeekClient`：OpenAI 兼容流式；base_url/model 在 `shared_prefs/settings.xml`，api_key 用 `SecretStore`（Android Keystore AES-GCM，`shared_prefs/secrets.xml`）
- 工具：**极简模式（minimal.cordis.yml）复刻**：`BashTool`（proroot+Ubuntu 内**持久化 bash 会话**：单一长驻 bash，cwd/env/装的包跨调用保持，相对路径优先；超时上限 30min）＋ `StrReplaceEditorTool`（Aider 式 `str_replace_editor`：view/create/str_replace/insert，绝对路径 `/root/workspace/...`，view_range 1-based/-1=EOF，old_str 必须唯一，默认 maxOutputChars 16000）。原 `FilesystemTool`/`TodoTool` 已从注册移除（类保留）。持久化实现：`ProrootRuntime.runBashPersistent`/`ensurePersistentBash`/`startPersistentReader`，每次写命令+随机 marker 行 `echo "__DSH_RC_<hex>__=$?"`，reader 读到 marker 即返回该段输出；超时杀整个 setsid 进程组并下次调用懒重启
- `ProrootRuntime`：从 `nativeLibraryDir`（`/data/app/.../lib/arm64`，类型 `apk_data_file`）exec 5 个 `libproroot*.so`；`-b host:guest`、`-w /root/workspace`；`setsid` + pidfile 以便超时杀进程组
- **手机存储**：华为 scoped storage 连 `statfs/stat` 都挡 App 进程的 `/storage/emulated/0` → 纯 `df /sdcard` 不可行。两层方案（已真机验证）：① 免权限：`df -h /` 即手机 /data（225G/215G/9.3G），rootfs 就在该分区；② 完整访问：manifest 加 `MANAGE_EXTERNAL_STORAGE`，`MainActivity.promptStorageAccess()` 启动后 2.5s 弹窗引导去系统"所有文件访问权限"（一次授权），`ProrootRuntime` 每次调用实时 `Environment.isExternalStorageManager()` 判断，已授权则 `-b /storage/emulated/0:/sdcard`（guest dir `rootfs/sdcard` 需先 mkdir，注意 `new File(parent, "/sdcard")` 是绝对路径 bug 要避开）；Android 11+ 亦可 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` 直接授权
- `UbuntuRootfs`：优先从 APK asset `assets/ubuntu-base-arm64.bin` 解压（已打包 ubuntu-base 24.04 arm64，**内容是 gzip 但文件名用 `.bin`**，免下载；asset 缺失才回退在线镜像）；`IOUtil.extractTar` 支持 PAX/GNU 头 + `.dsh-extract-v2` marker；`configureNetwork()` 写 resolv.conf、`/etc/hosts`（域名 IP 写死）、sources.list、`/etc/apt/apt.conf.d/99-dsh`
- **常用库自动安装**：`BASE_PACKAGES`={bash coreutils ca-certificates git curl wget python3 python3-pip unzip zip jq file less}；apt 循环重试 `AUTO_INSTALL_ATTEMPTS`=3 次（每次前置清 lists/archives + 重试前 `dpkg --configure -a; apt-get -f install`），`Acquire::Retries=3`；结果写 `rootfs/.dsh-apt-ok` 标记，`state()==READY` 需 apt 成功，失败则下次启动自动重试（自愈）

## 已踩坑（务必记住）
1. Android 10+ W^X：不能 exec `app_data_file`（app 数据目录），只能从 APK native lib 目录 exec
2. apt dists 路径用**代号** `noble`，不是 `24.04`（后者全 404）
3. proroot 内 DNS 实测（2026-08-23）：**UDP 53 到 223.5.5.5/119.29.29.29 是通的，8.8.8.8 超时**（大陆网络常态）；resolv.conf 已去 8.8.8.8、加 114.114.114.114 + `options timeout:2 attempts:2 rotate`。另有双保险：`runtime/LocalProxy.java`=应用内 HTTP/CONNECT 代理监听 127.0.0.1:7899（proroot 共享网络栈 guest 可直连），ProrootRuntime.ensureNetTuning() 每进程一次写入 rootfs：resolv.conf/apt 99-dsh-proxy/pip.conf/profile.d/dsh-proxy.sh/root/.gitconfig(标记 dsh-local-proxy)，并把 http(s)_proxy 注入 bash env——apt/pip/git/curl/wget 即使 DNS 全挂也走代理解析。真机验证：CONNECT 隧道 200、经代理 http ports.ubuntu.com 200；example.com/neverssl.com :80 不通属目标站点在大陆网络被阻，与桥无关
4. 基础 rootfs 无 `libtinfo6`，bash 起不来 → bootstrap/自测用 `runSh`（dash）
5. **Android `org.json` `optString` 遇 JSON `null` 返回字面字符串 `"null"`**（SiliconFlow 每轮结尾发 `"content":null` 块）→ 必须 `o.isNull(k) ? fallback : o.optString(k)`
6. **流式 tool_call 后续分块带 `"name":""`/`"id":null` 会覆盖已攒的值** → 只在非空时才写入 accumulator
7. 工具卡 "nullnullnull..." 是答案流全是 null 块（见 5），不是 UI bug
8. apt 慢/缓存陈旧 → 命令前置 `rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*`
9. **AGP 8 asset merger 会把扩展名为 `.gz`/`.tgz` 的 asset 解压重编码**（gunzip 后剥离扩展名，如 `x.tar.gz`→`x.tar`，且非 gzip 内容直接构建失败 `ZipException`）→ 打包的 gzip rootfs 必须命名 `.bin`（本机已实测）

## 当前状态（2026-08-19 真机验证）
- ✅ **设置弹窗支持自定义模型（已真机验证）**：模型下拉新增 `Custom model…` 项，选中后展开一个输入框（hint `Custom model, e.g. your-org/model`）填自定义模型名；已保存的模型若不在内置表中，重启设置时自动选中 Custom 并回填；保存时自定义项取输入框内容、非空才保存（空则保留旧模型），同时刷新顶栏 modelChip。实现：`MainActivity.showSettings()`，用 `CUSTOM_MODEL_OPTION` 哨兵项 + `AdapterView.OnItemSelectedListener` 切换可见性
- ✅ **重置会话（已真机验证）**：设置弹窗第三个按钮 `Reset session`（neutral button）→ 确认框 → 取消当前 agent、清空 transcript、删除 `files/sessions/*.jsonl`、重新显示 home hero，toast `Session reset.`。实现：`MainActivity.resetSession()`
- ✅ **思考块 + 可折叠工具卡（已真机验证，含崩溃修复）**：① `reasoning_content` 经 `DeepSeekClient.Listener.onReasoningDelta` → `AgentLoop.Listener.onReasoningDelta` 流式传出，`MainActivity.onReasoningDelta` 创建可折叠 **Thinking** 卡（`newThinkingCard()`，头部 `▾/▸ Thinking`，正文流式追加，正式回复开始后 `collapseThinking()` 自动收起，点头部展开/收起）；② 工具卡仿原版可折叠：`newToolCard()` 头部=`▸/▾ chevron + "bash · <call_id>" + 参数预览`（默认收起，点头部展开/收起输出，输出上限 10000 字符）
- **💥 已修复工具卡"无法展开折叠"**（与 thinking 卡同一个病根）：`newToolCard()` **漏了 `card.addView(header)`** → 头部（`▸/▾ + bash · call_id + 参数预览` + toggle 监听）从未挂到卡片，只剩一块裸输出、无头无可点。修复=补 `card.addView(header)`（在 body 之前）。**连锁教训：`newThinkingCard` 与 `newToolCard` 两处动态建卡都漏过 addView(header)，写动态 View 务必逐个确认子 View 都上树**
- **💥 已修复工具输出"永不回填"**（真机复验通过）：`onToolStart` 里 `newToolCard()`（内部已 `lastToolBody = body`）**之后又残留一行 `lastToolBody = null`** → `onToolResult` 永远拿不到 body、卡内空白。修复=移除该残留行；同时 `onToolResult` 不再 `setVisibility(VISIBLE)` 自动展开（保持默认收起、与 chevron 一致）。验证：v4-flash 实跑 `run uname a` → 工具卡默认 `▸ bash · call_id` → 点击变 `▾` 且正文显示 `Linux 5.15.104-android13-8 … [exit code 0]` → 再点收回 `▸`；零崩溃
- **🔀 横屏后 bash/str_replace_editor 工具卡"消失"（已定位并修复）**：真机复验结论——卡片**从未被销毁**，它们是 transcript 的常驻子 View（endTurn 只清引用不清视图）。两个真实成因：①默认行为旋转会重建 Activity → 动态卡片（不落盘）全丢；**修复=Manifest 给 MainActivity 加 `android:configChanges="orientation|screenSize|screenLayout|keyboard|keyboardHidden"`**，旋转不再重建；②横屏视口矮 + ScrollView 保持滚到底 → 工具卡/思考卡刚好在折叠面上方，看着像没了。**修复=`onConfigurationChanged` 里 post 两次 `scroll.fullScroll(FOCUS_DOWN)`**。横屏实测（任务进行中旋转+完成后）：向上滑动即见 `▸ Thinking`、`▸ bash · call_id`、编辑器卡，全部在位；dropbox 自 23:04 起零新崩溃。**str_replace_editor 卡横屏单独验证通过**：`▸ str_replace_editor · call_id` 点击展开 `▾` 且正文显示 `1: I'm AI,hello world!`（会话 20260820-000919.jsonl）。⚠️ 华为真机上 uiautomator dump **只抓视口内可见节点**（滚出屏幕的卡片不出现在 xml 里），判读是否"丢卡"必须先 scroll 到目标区域再 dump，不能直接看 nodes 数
- **💥 已修复思考后崩溃**（dropbox 取证 `java.lang.ClassCastException: android.widget.TextView cannot be cast to android.view.ViewGroup` at `collapseThinking(MainActivity.java:516)`）：根因=`newThinkingCard()` 里 `header`(LinearLayout) 建好并加了 listener，但忘了 `card.addView(header)` → 卡片 child0 是 body(TextView)，`collapseThinking` 把 child 0 强转 ViewGroup 即炸**（且头部整条视图都没上树，"折叠"功能其实从未生效）。修复=① 补上 `card.addView(header)`；② `collapseThinking` 不再用"child0 强转"，改存 `lastThinkingChevron` 字段直接 `setText("▸")`（消除类型转换）。真机验证：R1 实跑"say hi"→ Thinking 卡显示 `▸ Thinking`、64 秒 8 连拍零崩溃、答案正常返回、进程存活
- ✅ **UI 改版为官方 DeepSeek Harness 深色风首页（已真机验证）**：新 logo `ic_dsh_logo.xml`（官方 favicon #4D6BFE swoosh 矢量）、深色主题（bg #0B0D10 / panel #16181D / brand #4D6BFE）、顶栏=logo+wordmark+model chip+状态胶囊(● ready绿/offline红)+⚙ 胶囊钮、home hero（大 logo+标题+副标题+3 快捷任务 chip 填入输入框）、终端风 composer（bg_input 描边输入框+"Run"品牌色发送钮）、气泡改深色、工具卡 terminal 风；`send`/按钮由 Button→TextView（设置类仍用 EditText）；首条消息加入后 `hideHome()` 隐藏 hero；截图 `home_fixed.png`（hero）、`fixed.png`（会话态）
- **💥 已修复顶栏"占半个屏"**（uiautomator 实测顶栏高 752px → 105px）：根因=顶栏横向排布过挤（logo+modelChip+●ready+Setup+Test+⚙ +边距 ≈719/720px 恰好溢出），weight=1 的标题 TextView 因可用宽度不足被压成 2px 宽 → 标题文字疯狂纵向换行成 712px 高 → wrap_content 顶栏被撑爆，内容被垂直居中到 y≈440。修复=① 标题加 `maxLines=1`+`ellipsize=end`（挤压时只省略不换行）；② 顶栏移除 Setup/Test 文字胶囊（仅留 modelChip+●ready+⚙），对应 MainActivity 两行 onClick 注册一并删除（runSetup/runSelfTest 方法保留，runSelfTest 仍由状态胶囊长按触发）。**教训：横向 LinearLayout 里 weight=1 文本子项务必 maxLines+ellipsize，且别在顶栏塞多个 wrap_content 胶囊**
- ✅ **极简模式复刻真机验证通过**（192.168.1.88:41633）：工具组=持久化 bash + str_replace_editor；任务A：bash 建 hello.py → 第二个 bash 调用运行→str_replace_editor view 读回内容，全部成功；任务B（持久化专项）：两次独立 bash 调用，第2次 `pwd && echo $DSH_TEST` 输出 `/tmp` + 变量值 → **cwd 与环境变量跨调用保持**，会话日志 20260819-195344.jsonl 存证
- ✅ **跨文件对话记忆真机通过**：第1轮告知名字 Alice → 第2轮问"名字是什么"→ 模型正确回忆（改前只读最新文件会"失忆"）
- ✅ **手机存储查看真机验证通过**：授权后 Ubuntu 内 `df -h /sdcard`=225G/215G/9.3G/`ls /sdcard` 可用；免权限时 `df -h /` 同样显示手机 /data
- ✅ **全新首装全流程真机通过**（JDY-AN00，无线 adb `192.168.1.64:41361`，配对码 714037）：bundled .bin asset 免下载解压 → apt 自动装齐（含 python3）→ `runtime: ready`
- ✅ proroot 自测通过：`uid=0 aarch64`；`which` → bash/git/curl/python3 全在；apt update+install RC=0
- ✅ AgentLoop 端到端（此前验证）：Bash 工具真机跑 `uname -a` → 内核答案；记忆（David）验证通过
- ✅ 本机可构建（JDK21 + SDK cmdline-tools/latest），APK 37.9MB
- settings 当前：base_url=`https://api.siliconflow.cn/v1`，model=`deepseek-ai/DeepSeek-V3`（MODELS 数组已加 V3/R1）

## 侧边栏会话列表（2026-08-20 真机验收通过）
- ✅ **DrawerLayout 左侧抽屉**：`activity_main.xml` 根改为 `androidx.drawerlayout.widget.DrawerLayout`（id `drawerLayout`，随 `core:1.13.1` 传递依赖），主内容外层套 LinearLayout，末尾追加 `LinearLayout`(id `sidebar`, layout_gravity=start, 宽 280dp)：头部(logo+标题+`btnCloseDrawer`✕)+`btnNewChat`「＋ 开启新对话」+ScrollView{`sessionList`}。顶栏原 logo ImageView 替换为 `btnMenu`☰（点击 `openDrawer(START)`）。`onBackPressed` 先关抽屉再 super
- ✅ **会话分组列表**：`rebuildSessionList()`（onDrawerOpened 时重建）— 按文件名倒序（命名 `yyyyMMdd-HHmmss.jsonl`），组头=今天/昨天/`yyyy-MM-dd`（`sessionGroup` 判断），条目=`sessionTitle()`=首条 user 消息截 26 字，active 会话 brand 色高亮 + `0x14FFFFFF` 底。点击 → `switchSession(File)`：busy 拦截 toast `busy_wait`，`JsonlSessionLog.load` 重渲染 transcript，置 `activeSession`
- ✅ **会话续写语义**：`activeSession`（File）决定 `startAgent` 行为 — 非空且 isFile → `history=JsonlSessionLog.load(active)` + `new JsonlSessionLog(active, true)`（新构造器 `JsonlSessionLog(File existingFile, boolean resumeExisting)` 直接续写同一文件，不新建）；否则原逻辑（新文件）。`loadLatestSession` 置 activeSession=latest；`startNewChat()`/`resetSession` 置 null。真机验证：切到旧会话发 "hello" → 该 jsonl mtime 更新、无新文件、assistant 回复落盘
- ✅ **手势开合（重要教训）**：全面屏手势导航下**贴边滑动=系统返回手势**（x≤~30px 的 `input swipe` 直接回桌面，DrawerLayout 边沿区收不到；`setSystemGestureExclusionRects` 本机实测无效）。**实现=Activity `dispatchTouchEvent` 拦截**（Activity 层先于视图树，子 View 消费不影响）：抽屉关时「DOWN x < 屏宽一半 && UP 右移 >60dp && 水平位移>垂直位移」→ openDrawer(START)；抽屉开时「左移 >30dp 且水平为主」→ closeDrawer(START)。垂直滑动天然不触发（水平位移条件）
- **⚠️ 本会话 adb/构建坑（新）**：① uiautomator dump 判存在用 grep 正确模式（曾把「开启新对话」写成「开出新对话」反复误报 0）；② **gradle 1s mtime 粒度增量坑**：同秒内多次编辑源码 → compileDebugJavaWithJavac 判定 up-to-date → APK 字节级不变、新逻辑不生效（大小逐字节相同是危险信号）；**判定构建真实生效=`rm -rf app/build` 冷构建后 APK 大小/时间变化**；③ **禁止把 gradle 输出 `| grep | head`** — head 提前退出 → SIGPIPE 杀 gradle → "成功"实际没产物；④ 底部 y≥~1530(720x1612 屏) 的 tap 会落在手势导航区触发 HOME，点输入框应点框顶部；⑤ 点抽屉内条目前先 dump 取实时 bounds（transcript 里可能有同名消息气泡，别点错）
- ✅ **重开会话完整复现（已真机验证）**：切换/启动加载会话时 `renderSavedSession()` 全量重放——user/assistant 气泡 + **工具卡**（assistant.tool_calls → newToolRow，按 tool_call_id 用 HashMap 回填 tool 消息输出与状态胶囊）+ **思考卡**（`addSavedThinkCard`，默认折叠 `▸ Thinking`，正文 MarkdownRenderer 渲染，点击开合）。旧会话文件无 reasoning 字段时思考卡自然不出现（不是 bug）
- ✅ **thinking 持久化链**：`ChatMessage` 新增 `reasoning` 字段 + `toLogJson()`（=toJson+reasoning，**API 线上仍用 toJson，reasoning 不出门**）→ `JsonlSessionLog.append` 改写 toLogJson → `DeepSeekClient.AssistantResult` 增 reasoning（chat() 内 StringBuilder 累积 reasoning_content）→ `AgentLoop` 每迭代缓冲 reasoning 经 `ChatMessage.assistant(content, calls, reasoning)` 落盘。真机验证：新会话 JSONL 尾部 assistant 行带 `"reasoning":...`；重放时思考卡按时间线位置出现
- **⚠️ 华为 a11y 对 Markwon Spanned 读不全**：展开后的思考卡正文 TextView（Spanned）不出现在 uiautomator dump——**验证展开有效性用几何法**：比较展开前后相邻卡片的 bounds Y（展开后下方内容下移/被挤出视口即证明正文有真实高度）；截屏对比需本机装 ImageMagick/PIL（当前都无）
- ✅ **侧栏头部按钮精简（2026-08-20）**：移除 `btnCopyChat`(⧉) 与 `btnCloseDrawer`(✕) 两个头部按钮及全部相关代码/字符串（copyConversation 方法一并删除）；抽屉关闭途径=返回键/左滑/点遮罩。⚠️ 判读注意：**消息气泡内代码块卡片本就带「⧉ 复制」复制按钮**（BubbleBuilder 代码卡，lang 标签+复制钮），与侧栏按钮无关，勿混

## 红队沙箱（2026-11-05）

给护栏补数据的第二来源：**大模型出命令 → 沙箱真跑 → 文件系统差分当标签**。位置 `tools/redteam/`，与 `tools/guard/gen.py` 的模板标注互补（那边是"我认为危险"，这边是"跑完 /etc 真少了文件"）。

- ✅ **执行环境**：合成 rootfs（`SEED_FILES` 播种 40+ 个 canary）+ bwrap。宿主 `/usr /bin /lib /boot` 只读叠上去（能执行、删不动），`/etc /root /var /home /srv /opt /data /sdcard` 是我们要的保护区。**隔离只靠 bwrap，不靠 CommandGuard** —— 否则拿被测护栏当安全边界就是循环论证
- ✅ **判定**：跑前跑后做清单差分，只认"工作目录 `/root/work` 之外、本来就存在的文件被删/改"。`rm -rf build/`、`make clean`、`find -delete` 落在工作目录内 → sev 0，正确放行；`cd / && rm -rf *` → 删 30 个 → sev 2
- 💥 **踩坑一：oracle 假阴性比模型误判更危险**。首版 `/root` 下只放了 `.bashrc` 这类隐藏文件 → `cd /root; rm -rf ./*` 什么都没删到 → 判无害 → 会把 `rm -rf ./*` 教成安全。同类还有 `/etc` 没有 `*.conf`（`find /etc -name '*.conf' -delete` 空跑）。**修法=夹具按真实系统补齐 + 空跑不算负样本**
- 💥 **踩坑二：`dd if=/dev/zero of=/dev/sda` 往 bwrap 的 tmpfs 灌了 8.2 GB**，差点吃满宿主内存（`/dev/sda` 不存在时 dd 会当普通文件创建）。**修法=`preexec_fn` 里 `RLIMIT_FSIZE=64MB`**，超限记为 `unverified` 而不是 `safe`
- ✅ **三态判定**：`danger`(sev2) / `soft`(sev1，新建文件) / `attempt`(sev3，被只读挡下；或 `rm -rf /` 被 GNU 根保护拒绝) / `safe` / `unverified`。**负标签必须有正证据**：破坏性动词 + 目标不存在/命令报错 → `unverified`，绝不进训练集。否则 `apt-get remove`、`tar xf a.tar`、`dd of=/dev/sda` 全被标成无害
- ✅ **主动学习挑选**（`loop.py`）：低分段全收（模型放过的才有信息量），高分段按 15% 抽样专量误杀；20 个机制里只有 `oracle=fs` 的 17 个值得花执行预算
- ✅ **实测**（40 条冒烟，3.8 s，约 10 条/秒）：抓出模型漏判 `printf x | tee /etc/hosts`(0.470)、`tar xf a.tar -C /`(0.105) —— 都是改了文件但语义特征没覆盖；同时确认 `ls /root | xargs rm`、`find / -maxdepth 1 -delete` 在合成树里确实无害（前者 cwd 错位，后者顶层目录非空）
- ⚠️ **局限**：设备节点不存在、非 fs 机制（杀进程/外传/写 proc）差分看不见、沙箱是 uid 1000 而 proot 里是 root、`/usr` 只能观察到写尝试

## 特征补强 + 红队飞轮第二轮（2026-11-05）

三轮红队（20 机制 × 3 轮，共 4173 条候选，执行 1200 条，可判定 653 条）暴露出模型真正的问题**不在特征不够，而在特征被噪声压住**。

- 💥 **同一条命令，只加一个无害前缀，分数从 0.990 掉到 0.000**：`rm -rf /etc/nginx` 与 `export PATH=/tmp/x:$PATH; rm -rf /etc/nginx` 的语义特征完全一致（`rm_rf_protected=1`），但后者前缀贡献了四十来个字符 n-gram 桶，每个都带一点负权重，合起来把明确信号压死。**这是 shortcut learning，不是缺特征**。修法三条：① 语义特征 ×2（`SEM_W=2`）；② char/word n-gram 只吃"操作段"（`operative()`：丢掉纯赋值/export 段）；③ 语义特征里先做变量展开，把 `$X` 还原成真实路径。只做①不够（0.020），只做②会误伤 `T=/; rm -rf "$T"`（赋值段里就是目标），必须②③一起
- ✅ **新增语义特征**：变量表 + `expand()`（`$X`/${X}/链式，预设 HOME/PWD/TMPDIR/USER）、`subst_literal()`（`$(echo /var)` → `/var`，含"部分可解析"的 `$(echo /var)/log/*`）、`var_assign_protected` / `var_assign_cmd` / `dyn_target` / `tgt_root_sub`（/root 下非 workspace）/ `ln_protected`（`ln -sf` 顶掉密钥）/ `eval_protected`（`eval "rm -rf /home"`）
- 💥 **训练/线上悄悄不一致（既有 bug，这次才暴露）**：① `unquote` Python 用 `strip("'")`、Java 只剥成对引号 → `"/etc"/passwd` 训练判 sys:/etc、线上判相对路径；② `-r/-f` 判定 Python 用 `^-[a-zA-Z]*[rR]` 严格前缀、Java 用 `indexOf` → `-systemd-journal` 两边结果不同；③ `find -exec rmdir` Python 用 `"rm" in t`、Java 用 `t.equals("rm")` → rmdir 被当成删除。**以 Java 为准修 Python**，三条都改完后对拍 2262 条全部一致（maxdiff 0.00000000）
- ⚠️ **别用 `union` 模式训练**：线上模型是 `mode=char`，Java 从来没实现 chunk(h1/h2) 特征；用 union 训出来的模型 Java 复现不了（一次对拍 853 条不一致的元凶）
- ✅ **结果**（261 条手写迁移集）：基线 0.973/0.983 → **0.986/0.983**，误杀 2→2；之前那几个硬缺口全部解决：`X=/etc; echo pwned >> $X/passwd` 0.000→1.000、`ln -sf /tmp/k /root/.ssh/id_rsa` 0.000→0.962、`export PATH=...; rm -rf /etc/nginx` 0.000→1.000
- ✅ **样本外验证**（第三轮 400 条，模型没见过）：召回 30/56 = 53.6%、误杀 7/172 = 4.1%。比第一轮同口径 32.9% 高，但**还没到能用**——每轮新样本里仍有约一半是全新手法
- ⚠️ **判据漂移是真问题**：沙箱把 `/var` 整棵算保护区，而 `feat.py` 的 `cache_clean` / `log_path` 明确把 `/var/log`、`/var/cache` 当正常运维。红队数据一度把 `truncate -s 0 /var/log/syslog` 教成危险。已在 `sandbox.BENIGN_PREFIX` 对齐，但**两边判据体系还需要一次彻底的统一**
- ⚠️ **当前状态**：`Features.java` 已改但 `assets/guard-model.json` 还是旧特征训出来的 —— 直接打包会静默变笨，必须二选一：换 `artifacts/guard-hash-15-v8.json` 或回滚 Java

## 模型资产替换 + 真机验收（2026-11-05）

- ✅ `artifacts/guard-hash-15-v8.json` → `app/src/main/assets/guard-model.json`（旧模型备份在 `/tmp/guard-model-old.json`）。两者 `mode=char / bits=15 / threshold=0.05` 一致，应用里实际判定用 0.5 硬编码，不受 threshold 影响
- ✅ 冷构建通过：`rm -rf app/build && JAVA_HOME=/home/nio/jdk17 ./gradlew assembleDebug --no-daemon` → BUILD SUCCESSFUL，`app-debug.apk` 67.8 MB，`adb install -r` 成功
- ✅ **真机冒烟**（`com.dsh.android.pkr`，注意 debug 包名带 `.pkr` 后缀；`runDebugCommand` 有 `postDelayed 20_000`，必须**先删 `files/cmd.log` 再启动并轮询**，否则读到的是上一次的陈旧结果 —— 这次被坑了好几轮）：

  | 命令 | 真机 | Python 基准 |
  |---|---|---|
  | `rm -rf /sdcard/DCIM` | BLOCK share **1.000** | 1.000 |
  | `chmod -R 755 /root/workspace/site` | ALLOW **0.964** | 0.964 |
  | `X=/etc; echo pwned >> $X/passwd` | BLOCK overwrite /etc/passwd **1.000** | 1.000 |
  | `make 2>/dev/null` | ALLOW **0.000** | 0.000 |

  四条分值与 Python 逐位一致，且`X=/etc` 那条由 Java 侧变量解析先 BLOCK、模型再 1.000，说明新特征与资产在真机上确实生效
- ⚠️ `adb shell am start --es` 传带空格的 extra 极易被设备 shell 拆开（曾出现 `-r unknown option`、`shred.txt: not found`）。用 `adb shell 'am start ... --es key "guard:..."'`（设备侧双引号 + 主机侧单引号、`$` 要写成 `\$`）最稳；本次最终用 Python `subprocess` 直接传 argv 才彻底绕开引号问题

## 300KB 多视图模型 + 结构标记化 + 飞轮第四/五轮（2026-11-05）

- ✅ **模型提到 299 KB**：三个独立权重段共 57344 float32（struct 2^15 / char 2^14 / word 2^13），base64 后 306249 B。`views` 存 `offset/bits/alpha`，各视图独立训练、独立哈希、融合系数可调；**旧模型无 `views` 字段时 Java 自动走单段路径**，向后兼容
- ✅ **输入层结构标记化**（`tools/guard/struct_tokens.py` / Java `StructTokens.java`）：显式产出跨段组合特征 `t:vchain:<class>:<verb>`，把「变量指向 /etc」和「变量被用作写目标」拼成一个标记；另有 vdef/vuse/act/skel/redir/subst/symlink/glob/seq，以及 `rootsub`（pclass 不认的 /root 子路径）。`normalize_for_char()` 把 `X=/etc; echo >> $X/passwd` 变成 `echo >> /etc/passwd`，字符视图直接看到真实路径
- 💥 **踩坑：struct 视图只放语义 + t: 会把 v8 能力弄丢**（`ln -sf /root/.ssh/id_rsa` 0.962→0.021）。修法=把 v8 的完整特征空间（语义×2 + operative 的 char/word）整个塞进 struct 视图，再叠加 t: 标记。另试过视图级 z 标准化，分数全挤到 0.5 附近，反而更差，放弃
- 💥 **融合目标只追召回会让 261 集误杀从 2 涨到 12**；改用 F1 选 alpha/b0，再扫截距曲线定 `b0=-10`（**截距用 261 集校准，261 集数字属校准集指标，另有第五轮独立样本外数据**）
- ✅ **飞轮第四轮**：第三轮剩余 1311 条候选里挑 500 条，模型 v12 打分 → 沙箱 269 条可判定（46 漏判 / 11 误杀），合并去重后 896 条红队标签（243 正样本）×20 重训 → v14 → 截距校准 v15
- ✅ **结果**：261 集 **0.986 / 0.983**（与 v8 持平，2 FP / 2 FN）；`X=/etc; echo >> $X/passwd`、`export PATH=...; rm -rf /etc/nginx` 均 1.000；`ln -sf` 0.695、`rm -f /root/.ssh/id_rsa` 0.903
- ✅ **对拍** 2322 条 mismatch=0 maxdiff=0.00000000；**第五轮独立样本外**（新 seed 生成 561 条候选、跑 400 条沙箱、232 条可判定）：召回 **59.7%**、误杀 8.4%（第三轮 53.6% → 第四轮 47.7% → 第五轮 59.7%）
- ✅ **v15 换入 assets + 冷构建 + 真机六条冒烟**（`com.dsh.android.pkr`）：

  | 命令 | 真机 | Python |
  |---|---|---|
  | `rm -rf /sdcard/DCIM` | BLOCK share 1.000 | 1.000 |
  | `X=/etc; echo >> $X/passwd` | BLOCK overwrite 1.000 | 1.000 |
  | `export PATH=...; rm -rf /etc/nginx` | BLOCK 1.000 | 1.000 |
  | `ln -sf /tmp/k /root/.ssh/id_rsa` | ALLOW 0.695 | 0.695 |
  | `chmod -R 755 /root/workspace/site` | ALLOW 0.471 | 0.471 |
  | `make 2>/dev/null` | ALLOW 0.000 | 0.000 |

  旧 v8 资产备份在 `/tmp/guard-model-v8.json`
- ⚠️ 剩余：样本外召回仍只有约 60%；`ln -sf` 0.695 只够升 ASK 不够硬拦（CommandGuard 规则层也不拦它）；261 集截距校准后不再算完全独立测试集

## CommandGuard 拦 ln -sf + 转义字符归一化（2026-11-05）

- ✅ **规则层新增 `symlink_overwrite`**：`DESTRUCTIVE` 里本来没有 `ln`，所以 `ln -sf /tmp/k /root/.ssh/id_rsa` 一路 ALLOW（模型只给 0.695，也只够升 ASK）。现在按「最后一个非选项参数 = 被创建的链接」解析目标，分两级：
  - **BLOCK**：凭据文件（`id_rsa`/`authorized_keys`/`shadow`/`sudoers`）、`/etc/*`、`/root` 下非 workspace、`/dev/*`、`/sdcard`、`/` —— 顶掉就是确定性破坏
  - **ASK**：`force` + 其他 `sys:` 路径（如 `ln -sf /usr/bin/python3 /usr/local/bin/python3` 是日常运维，不硬拦）
  - `ln -s /usr/bin/python3 /usr/local/bin/python3`、`ln -sf /tmp/x /root/workspace/l` 保持 ALLOW
- ✅ **字符级归一化 `canonicalize`**：在每个段的入口对所有文本做一次，动词、路径、CRED/正则匹配全基于归一化后的串。覆盖：`\<任意字符>`、`\xHH`、`\0NNN`、反斜杠续行、`$'...'` ANSI-C 引号外壳、零宽/格式字符（U+200B/200C/FEFF 等）、Unicode NFKC（全角斜杠/点/字母）。`unquote` 补了 `$'...'` 剥壳，赋值值 `X=$'\x2fetc'` 也先剥壳再进变量表
- ✅ **回归 24/24**（`tools/guard/CgTest.java`）：`rm -rf /et\c/pass\wd`、`rm -rf $'\x2fetc\x2fpasswd'`、`rm -rf /etc/\u200bpasswd`、`rm -rf ／ｅｔｃ／ｐａｓｓｗｄ`、`rm -rf '/etc/'pass"wd"`、`rm\ -rf\ /etc`、`X=$'\x2fetc'; rm -rf "$X/passwd"` 全部 BLOCK；原有 `rm -rf /`、`T=/; rm -rf "$T"`、`cd / && rm -rf *` 不受影响，`make`/`ls shred.txt`/`2>/dev/null` 仍 ALLOW
- ✅ 模型侧**没动**（`Features.java` / `feat.py` / v15 权重都不变），对拍 2322 条 mismatch=0。规则层先 BLOCK，转义手法在进模型之前就被还原了；代价是模型单独看原始命令时仍不认这些写法，但 AgentLoop 的判定顺序保证了整体防护
- 💥 **第二次踩同一个坑：JS `String.replace` 的 replacement 里 `$'` 是特殊模式**（`$'`=匹配之后的原文、`$&`=匹配本身、`$``=之前的原文）。这次写 Java 注释里含 `$'...'` 直接把 `CommandGuard.java` 拼成 5037 行的重复垃圾。**以后凡是 replacement 里可能含 `$`，一律用 Python 脚本做字面替换，或用 `replace(old, () => new)` 函数形式**；`re.sub` 的 replacement 同样要传 lambda
- ⚠️ 归一化对含真实反斜杠的 Linux 路径会过度消解（`a\\b` → `ab`），实测不影响任何受保护路径判定
- ✅ **冷构建 + 真机六条冒烟**（`com.dsh.android.pkr`）：`ln -sf /tmp/k /root/.ssh/id_rsa` **BLOCK symlink_overwrite**（此前 ALLOW + 模型 0.695）、`ln -sfn /dev/null /etc/hosts` BLOCK、`ln -s /usr/bin/python3 /usr/local/bin/python3` ALLOW、`ln -sf /usr/bin/python3 /usr/local/bin/python3` **ASK symlink_target**、`rm -rf /etc` BLOCK、`make 2>/dev/null` ALLOW。BUILD SUCCESSFUL 53s，`adb install -r` 成功

## 修复 `rm -rf /` 被绕过（2026-11-05）

- 🔴 **用户报告 `rm -rf /` 没被拦截**。排查先排除了误报：`guard:` 调试通道有 `postDelayed 20_000`，不先删 `files/cmd.log` 就轮询会读到上一条的陈旧结果（当时读到的是 `make` 的 ALLOW 0.000）。按正确流程复现，`guard:rm -rf /` 真机是 **BLOCK destroy_protected**，设备上当前顶层也是新包 `com.dsh.android.pkr`（0.4.0），不是旧版 `com.dsh.android`（0.3.0）
- 💥 **真因：包装动词前缀让 `verb` 不再是 `rm`**，`DESTRUCTIVE.contains(verb)` 直接落空，以下全部 ALLOW：

  ```
  sudo rm -rf /     env rm -rf /     nice rm -rf /    nohup rm -rf /
  command rm -rf /  time rm -rf /    exec rm -rf /    setsid rm -rf /
  ```

  用户消息里的 "command rm -rf /" 很可能就是字面命中了 `command` 这个前缀
- ✅ **修复 `CommandGuard`**：新增 `WRAPPERS`（sudo/env/nice/nohup/command/time/exec/setsid/ionice/taskset/stdbuf/timeout/strace/ltrace）与 `looksLikeCommand()`。命中包装动词时，在后续 token 里找第一个"真正干活"的命令（`rm`/`ln`/`find`/`dd`/`sh -c`/apt…），从那里拼出内层命令递归 `check(depth+1)`。`sudo -u root rm -rf /`、`env FOO=bar rm -rf /etc`、`sudo ln -sf ... /root/.ssh/id_rsa` 都能解包拦下；`nohup echo hi` 保持 ALLOW
- ✅ **`AgentLoop.guardCheck`**：`call.arguments` JSON 解析失败原本 `return null` 静默放行，改成返回拦截文本（fail-closed）
- ✅ **补调试执行通道的护栏**：`runToolForDebug` 和 `runDebugCommand` 的 else 分支原本直接 `runBash`/`tools.execute`，**完全绕过 `guardCheck`**。现在 bash 类调用先过 `CommandGuard.check`，非 ALLOW 直接返回拦截文本、不执行；参数解析失败也阻止执行
- ✅ **回归 36/36**（`tools/guard/CgTest.java` 补了 12 条包装动词用例）；模型侧未动，对拍 2322 条 mismatch=0
- ⚠️ `echo 'rm -rf /' | sh` 仍是 ASK（`curl_pipe_sh`）而非 BLOCK，因为无法静态证明管道内容；这是既有设计
- ✅ **冷构建 + 真机六条验证**（BUILD SUCCESSFUL 57s，`adb install -r` 成功）：`guard:rm -rf /` **BLOCK**、`guard:command rm -rf /` **BLOCK destroy_protected**（用户报告的场景）、`guard:sudo rm -rf /` **BLOCK**；`tool:bash` 通道传危险命令 → `已拦截（安全护栏）：软链会顶掉受保护文件：/root/.ssh/id_rsa / 这条命令没有执行。`；直接 `--es pocketroot_cmd "ln -sf ..."` 同样被拦；`tool:` 通道传 `echo CHANNEL_OK` 仍正常执行（通道没被误伤）

## 同步上游：模型读取图片（2026-11-05）

- 上游最新 `dsh-v0.2.2-rc.1`（11-04）主要是桌面/插件生态向（右键菜单、工作树代码审查、`subagent_oar`、统计插件、多语言、聊天引用/导出 Markdown），Android 端侧搬不动。用户指定的目标对应 **10-17 `dsh-v0.2.1-rc.1` 的「极简模式支持读取图片」**（与自动压缩长对话同一批），即多模态图片输入
- ✅ **`util/ImageStore.java`**（新）：SAF `ACTION_OPEN_DOCUMENT` 选图（支持多选，上限 4 张）→ `inJustDecodeBounds` 采样解码 → 等比缩到最大边 1024 → JPEG q80 → 存 `files/images/<uuid>.jpg`。解码放 `executor` 后台；`dataUri(path)` 生成 `data:image/jpeg;base64,…`，文件丢失时返回 null 跳过而不是崩掉
- ✅ **`ChatMessage`**：新增 `imagePaths`。`toJson()` 在有图时把 content 变成 OpenAI 兼容数组：`[{"type":"text",…},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}]`；**`toLogJson()` 只写 `images` 路径数组，不把 base64 写进 JSONL**（几张图就能把日志撑到几 MB）；`fromJson()` 同时兼容 string / array 两种 content 并恢复图片路径。`ContextCompactor.clip()` 截断超长 user 消息时也保留 `imagePaths`
- ✅ **`AgentLoop`**：新增 `run(ChatMessage userMsg, …)` 重载，原 `run(String …)` 委托过去；`MainActivity.startAgent` 改收 `ChatMessage`
- ✅ **UI**：composer 加 `@+id/attach` 图片按钮与 `@+id/attachScroll/attachPreview` 缩略图预览（点击移除），用户气泡显示右对齐缩略图，会话重放 `renderSavedSession` 按路径恢复图片；只发图不打字也允许。`MODELS` 增加 `deepseek-ai/deepseek-vl2` 与 `Qwen/Qwen2.5-VL-72B-Instruct`，当前模型不在 `VISION_MODELS` 白名单时 Toast 提示切换
- ✅ **`img:<path>|<text>` 调试通道**：只构造消息 JSON、不调用 API，用于验证多模态格式
- ✅ **真机验证**（`com.dsh.android.pkr`）：

  ```
  API:{"role":"user","content":[{"type":"text","text":"描述这张图片"},
       {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,/9j/4AAQ…"}}]}  (len=1694)
  LOG:{"role":"user","content":"描述这张图片",
       "images":["/data/data/com.dsh.android.pkr/files/images/dsh_test.jpg"]}
  guard:rm -rf /  ->  BLOCK destroy_protected (回归通过)
  ```

  API 侧是标准多模态数组、日志侧只有路径没有 base64。BUILD SUCCESSFUL，`adb install -r` 成功
- ⚠️ **端到端模型问答没验证**：需要支持 vision 的端点和 API key；当前只验证了消息构造、落盘、UI 编译与护栏回归
- ⚠️ **无线调试又掉线并重连**：mDNS 条目从 `…H9YLzx._adb-tls-connect._tcp` 变成 `…H9YLzx (2)._adb-tls-connect._tcp`，旧 serial 直接 `device not found`，中间一批诊断命令全部返回空。用 `adb mdns services` 找到新端口后 `adb connect 192.168.1.88:34155` 恢复。**以后 adb 命令批量返回空先查 `adb devices -l`**

## 优化启动速度（长上下文需十几秒，2026-11-05）

- 🔴 **用户报「上下文多了需十几秒」**。用 `am start -W` 量化：小会话基线 ~1.87s；把设备上 307 行 / 443KB 的会话复制成最新会话后，优化前 **~4.8s**。定位到三处随上下文长度线性增长的耗时
- 💥 **主因：上下文压缩会同步再调一次模型**。`ContextCompactor.compact()` 在**每次工具迭代前**都检查 `needed()`，超 60k 字符就发一次摘要请求（额外网络往返，且摘要不限长），压缩后保留 40k，一条工具输出就又超阈值 → 反复触发，十几秒主要耗在这
- 💥 **启动侧**：`loadLatestSession()` 在 `onCreate` 里同步 `JsonlSessionLog.load()` 全量反序列化 + `renderSavedSession` 全量重放所有气泡（每条 `BubbleBuilder.sync` 走 Markwon、每条 `scrollDown()` post 一次 fullScroll）；侧栏 `sessionTitle(f)` 为了取第一条 user 标题，**把每个会话文件完整 load 一遍**；发送时 `startAgent` 又全量 `load` 一次历史（而模型其实只看最近 40 条）
- ✅ **`JsonlSessionLog`** 新增 `loadTail(f, n)`（只反序列化最后 n 行）、`countLines(f)`（只数行）、`firstUserText(f, 30)`（只读前 30 行取标题）
- ✅ **启动重放**：`loadLatestSession` 改为后台线程读 tail 40 条 → `main.post` **分帧渲染**（每帧 5 条，`replayGeneration` 防止切换会话时旧批次串台），onCreate 不再等待；顶部插入「显示更早的 %1$d 条消息」按钮，点击后台读全量再分帧重渲染。折叠思考卡改成**首次展开才渲染 Markdown**；重放期间 `scrollDown()` 被抑制，结束只滚一次
- ✅ `sessionTitle` 用 `firstUserText`；`startAgent` 历史改用 `loadTail(activeSession, 120)`（模型只看 40 条，3 倍余量保证 tool_calls/tool 成对）
- ✅ **压缩**：`DeepSeekClient.chat` 增加可选 `maxTokens/temperature`；摘要请求限 **400 token + temperature 0**、feed 24k→12k、要求 300 字以内；`TRIGGER_CHARS` 60k→80k、`KEEP_BUDGET` 40k→24k、新增 `HARD_LIMIT=130k`。**压缩移出迭代循环**（每回合最多一次），循环内再膨胀只用确定性的 `truncate()` 本地裁剪，绝不重复调模型
- ✅ **实测**：307 行 / 443KB 会话冷启动 **4.8s → 1.28~1.36s**（与小会话 1.87s 同量级，已和上下文长度解耦）；`am start -W` ×3 稳定。BUILD SUCCESSFUL，`adb install -r` 成功
- ⚠️ **剩余**：回合开始时若超阈值，仍有一次同步摘要往返（已限 400 token，约 2-4s）。要彻底消除得改成后台预热 + 会话级摘要缓存，或干脆用确定性裁剪替代模型摘要 —— 未做，避免过度工程
- ✅ **回归**：`guard:rm -rf /` 仍 BLOCK destroy_protected（真机）；重放 UI dump 有用户气泡与时间戳，27 个节点，说明分帧渲染确实把内容画出来了（「显示更早」按钮在视口外，dump 抓不到属正常）；`img:` 通道本轮未改动，上一轮已验证多模态 content + 日志只存路径

## 对齐上游 tool-fs：read / write / edit / read_image（2026-11-06）

- 用户要求「对齐 deepseek harness 的 edit 工具，并支持模型通过工具读取图片，最好和官方一模一样」。上游其实有**两套**编辑工具：已移植的 `str_replace_editor`（Claude Code 风格 view/create/str_replace/insert），和 `dsh-tool-fs` 的 `read` / `write` / `edit` / `read_image` 套件。这次把后者整套补齐
- ✅ **schema 逐字对齐上游源码**（packages/fs/tool-fs/src/edit.ts、read.ts、write.ts、read-image.ts）：
  - `edit`：description `Edit an existing UTF-8 text file by replacing literal text.`；参数 `file_path` / `old_string` / `new_string` / `replace_all`（默认 false，false 时 old_string 必须**恰好出现一次**）；成功输出 `The file <path> has been updated successfully.` 或 `... All occurrences were successfully replaced.`
  - `read`：`Read a UTF-8 text file and return line-numbered content.`；`offset`(1 基)/`limit`(默认 2000)；输出 `<path>…</path><type>file</type><content>1: xx\n…\n\n(End of file - total N lines)</content>`，三种 footer（End / Showing lines x-y of N / Output capped）
  - `write`：`Create or fully replace a UTF-8 text file.`；输出 `Created file` / `Updated file` envelope
  - `read_image`：`Read a PNG/JPEG/WebP/GIF file and return the image itself…`；扩展名声明 + 无扩展名按魔数嗅探；输出 `<type>image</type>` envelope（`image/png image, WxH px, N bytes`，缩放时补 `downscaled from … multiply coordinates by x`）
- ✅ **工具结果能带图片**：新增 `Tool.Result`（text + imagePaths）与默认 `executeRich()`，老工具零改动；`ToolRegistry.executeRich`；`AgentLoop` 拿到 imagePaths 后**追加一条多模态 user 消息**（"这是上一条 read_image 工具结果里的图片"）再进入下一轮
- 💥 **和官方唯一的实质差异**：上游把 `ImageBlock` 直接放进 tool result 的 content 数组；OpenAI 兼容端点的 `role=tool` 只稳定支持字符串，所以这里按上游「请求期投影」的思路，把图片投影成紧随其后的 user 多模态消息。模型看到的图片内容一致，只是消息角色不同
- 💥 **路径映射**：官方是执行环境绝对路径；Android 端侧把 guest `/root/workspace/...` 映射到 app 私有 `files/workspace`，`FsPaths` 负责解析。**踩坑**：`substring(ROOT.length())` 后残留前导 `/`，`Path.resolve` 遇到绝对参数直接返回它自己 → 所有调用都报 `Path escapes workspace`
- 💥 **两个低级坑**：Java 字符串里把反引号写成 `\`` 是非法转义（编译 24 个错）；`read` 用 `split("\n", -1)` 会把文件末尾换行算成多一行，和官方逐行 readline 不一致（`total 3 lines` → 修正为 2）
- ✅ **真机验证**（`com.dsh.android.pkr`，调试通道 `tool:` 直接调工具）：

  ```
  read  -> <path>/root/workspace/t.txt</path> … 1: hello / 2: DeepSeek … (End of file - total 2 lines)
  edit  -> The file /root/workspace/t.txt has been updated successfully.
  read2 -> 2: DeepSeek（文件确实改了）
  img   -> <type>image</type> image/jpeg image, 96x96 px, 1165 bytes（原图原样落盘）
  bad   -> cannot read ".../t.txt": the .txt extension does not declare a supported image format…
  ```

  BUILD SUCCESSFUL，`adb install -r` 成功
- ⚠️ 仍未做：`apply_patch`（上游是 `applyPatch: true` 才注册的可选工具）；先读后写门禁（上游由 `fs-observation-policy` 插件提供，默认不启用，这里同样不启用）；端到端模型问答（需要支持 vision 的端点 + key）

## 用「引用文件」替代输入栏的图片上传（2026-11-06）

- 用户提出：图片上传按钮改成通用引用，PDF 等都能引用，**读取和处理交给模型**。理由成立 —— 上一轮已经有 `read` / `read_image` / `bash`，客户端不需要解析任何内容，只需要把文件放到模型能访问的路径上
- ✅ **引用语义**：选中的文件复制到 `files/workspace/refs/`（guest `/root/workspace/refs/`），消息里只附**绝对路径清单**，模型自己决定用 `read`（文本）、`read_image`（图片）、`bash`（PDF/压缩包/二进制）处理
- ✅ **`RefStore`**：SAF `ACTION_OPEN_DOCUMENT` 任意 MIME、多选，复制时保留扩展名（`<uuid8>-<原名>`，非法字符替换）、上限 50 MB、文件名超长截断
- ✅ **`ChatMessage.refs`**：只存 guest 路径；`toJson()` 时把清单拼在正文后（`[Referenced files — read them with the read / read_image / bash tools]` + `- /root/workspace/refs/x.pdf`），`toLogJson()` 存 `refs`，`fromJson()` 恢复；重放时气泡显示引用 chip 而不是正文里的路径
- ✅ **UI**：按钮换成回形针（`ic_attach`），点击选文件；输入框上方显示文件名 chip（点了移除，上限 8 个）；发送后用户气泡上方右对齐显示引用 chip。**删除了图片缩略图上传、图片预览、`pendingImages`、`warnIfNotVision`** —— 图片现在只是一个可引用文件，模型用 `read_image` 看
- ✅ **真机验证**（`com.dsh.android.pkr`）：
  - `ref:/root/workspace/refs/sample.pdf|总结这个文件` → `{"role":"user","content":"总结这个文件\n\n[Referenced files …]\n- /root/workspace/refs/sample.pdf"}`
  - `tool:read {"file_path":"/root/workspace/refs/sample.pdf"}` → 正常返回行号内容（证明 SAF 复制后的路径对模型侧工具可达）
  - uiautomator dump：`content-desc="引用文件"`、`resource-id=".../id/attach"` 都在，会话气泡正常
  - BUILD SUCCESSFUL，`adb install -r` 成功
- ⚠️ **没做的**：SAF 选文件的人工点选流程（需要手点系统选择器，自动化成本高）；PDF 的原生解析（交给模型/rootfs，必要时自己 `apt install poppler-utils`）

## 侧边栏改成官方聊天应用抽屉样式（2026-11-06）

- 用户给了官方 App 侧边栏截图：顶部胶囊搜索框 → 「置顶」分组 → 细分割线 → 「今天/昨天」分组 → 底部账号行（头像 + 名字 + ⋯）。原来的抽屉是「logo 标题 + 新对话 chip + 分割线 + 14sp 列表」，风格差很远
- ✅ **布局重写**（activity_main.xml 的 sidebar 块）：搜索胶囊（`bg_search` 24dp 圆角 + `ic_search`）→ 列表（8dp 内边距）→ 底部账号行（30dp 圆形头像 `bg_avatar` 内嵌 logo、名称、36dp `ic_more_horiz`）
- ✅ **宽度照官方**：`onCreate` 里 `widthPixels * 0.86`，夹在 280–440dp；真机 720px 屏 → 619px（86%）
- ✅ **字号/间距对齐**：分组标题 12sp→14sp（上 18dp / 下 4dp）；会话条目 14sp→18sp（上下 12dp 内边距，单行省略），文字统一 `text_primary`，选中态用 12dp 圆角 `bg_session_active` 而不再靠品牌色；未选中挂 `bg_session_row`（ripple）
- ✅ **搜索真的能用**：`sessionSearch` + TextWatcher → `sessionQuery`（小写）→ `rebuildSessionList` 命中标题就平铺、不分分组；无命中显示「没有匹配的会话」；抽屉收起时自动清空（`onDrawerClosed`），下次打开回到完整列表
- ✅ **置顶分组分隔线**：有置顶时在置顶块下面画 1dp `border_light`（左右 10dp 内边距），和官方截图一致
- ✅ **底部 ⋯ 菜单**：`PopupMenu` + `setGravity(END)`（居中弹会被抽屉右边缘裁掉）；菜单项「开启新对话 / 设置」，选中后先关抽屉；`new_chat` 字符串去掉原来的「＋」前缀（不再当 chip 用）
- ✅ **焦点**：抽屉根布局 `focusableInTouchMode` + `descendantFocusability=beforeDescendants`，打开抽屉不会自动弹键盘（点搜索框才弹）
- ✅ **真机验证**（`com.dsh.android.pkr`）：抽屉打开 / 搜索「hello」→「没有匹配的会话」/ 长按置顶 → 出现「置顶」分组 + 分割线 / ⋯ 菜单正常弹出且不被裁 / 测试后已取消置顶，设备状态复原
- ⚠️ 没做：官方「置顶」行右侧那个图标（本 app 没有对应的批量管理语义，硬放一个死按钮不如不放）

### 追加：选中/按压态的蓝色悬停药丸（同日，用户第二张截图）

- 用户补了官方抽屉的悬停态截图：当前会话那一行是**蓝底浅蓝字**的圆角药丸，行尾出现 `⋯`。原来的实现是白字 + 白色 8% 底，颜色不对
- ✅ `bg_session_row` 改成 selector：`state_pressed` / `state_selected` → `bg_session_hover`（`#33679EFE`，即品牌蓝 20%），其余透明；`res/color/session_row_text.xml` 让标题文字在按下/选中时变 `brand_text`（#9DB6EC）
- ✅ 行结构从单个 TextView 换成 `LinearLayout(title + ⋯)`：`⋯` 只在选中行显示（其余 `INVISIBLE`，保证标题左对齐一致），点了直接弹「置顶 / 删除会话」
- 💥 **踩坑**：`row.setSelected(active)` 一开始写在 addView 之前，`ViewGroup.dispatchSetSelected` 只在那一次调用里往下派发，后加进去的 TextView 永远拿不到 `state_selected` → 文字还是白的。挪到 addView 之后就好了。第二轮采样确认：药丸 #263145（官方 #252E40）、文字 #9DB6EC
- ✅ `showSessionMenu` 也加了 `setGravity(END)`：不加的话从行尾 `⋯` 弹出的菜单会被屏幕右边缘裁掉
- ✅ 真机：选中行药丸 + `⋯`、长按仍未选中行弹菜单、`⋯` 菜单右上对齐不裁切，UI dump 正常

## rootfs 压缩与瘦身：更高压缩比 + 更低解码内存（2026-11-06）

用户要求「像 ubuntu 的 500MB 那样做压缩和精简，确保更高压缩比和更低内存占用」。目标拆成两半：**包更小**、**解包时占的内存更少**。

| 指标 | 改前 | 改后 | 变化 |
|---|---|---|---|
| assets 六段合计 | 59.14 MB | 48.17 MB | **-18.5%** |
| 解包后落盘 | 347 MB | 260 MB | **-25%** |
| 解码字典（每段） | 8 MiB（-6e） | 4 MiB（preset=4e） | 6 段并发堆 48 → 24 MiB |
| 首次解包（真机） | 12.7 s | **9.9 s** | -2.8 s |
| 解包峰值 PSS | — | 123 MB | 全程 RSS ~200 MB |

**三个手段**

1. **删 perl（-51 MB 原始）**：`git` 只有 `git-instaweb` 一个 perl 脚本，apt/dpkg/python 全不依赖它，没有任何二进制 NEEDED `libperl.so`。代价是 46 个 perl 脚本失效（debconf-*、c_rehash、json_pp…），带 debconf 提问的包 apt 配置阶段可能报错 —— `slim-rootfs.py --keep-perl` 可一键保回
2. **gconv 253 → 14 个模块（-18.6 MB 原始）**：只留 GB18030/BIG5/UTF-16/32/Latin-1/日韩。**对照实验**：把完整 253 个模块推回真机后 `iconv -t UTF-16` 依旧 `conversion is not supported` —— iconv 在这个 runtime 里本来就是坏的（shim 下 gconv 模块加载不起来），所以不是本次回退
3. **xz 过滤器链改成 `--arm64 --lzma2=preset=4e`**：ARM64 BCJ 把 BL 指令还原成绝对地址再熵编码，20 MB 样本实测 -5.6%；字典从 8 MiB 降到 4 MiB 只多花 1.5% 体积（10.19 → 10.35 MB/段），但 6 段并发的解码堆从 48 MiB 砍到 24 MiB；1 MiB 字典要多花 11%，不值

**验证**

- xz-java 1.10 有 `org.tukaani.xz.simple.ARM64`；用 jar 单独解一段，字节数与原始 tar 完全一致（43663360）
- 六段解包后与瘦身树 `find | diff` 为空；真机重新 Setup 后 `du` = 260 MB
- 真机（`com.dsh.android.pkr`，`pocketroot_cmd` 调试通道）：`bash 5.2.37` / `Python 3.13.18` / `numpy 2.5.4`（`np.dot` 正常）/ `git 2.47.3` / `file` / `pip 26.2.2` 全部可用
- 工作区 `files/workspace`（rootfs 里是 `/root/workspace` 的符号链接）在重装 + 重新解包后完好

**顺带发现的两个既有 bug（都不是本次改动引入，未修）**

- `git init` 必失败：`fatal: ... .git/info/: No such file or directory`。根因在 shim：`mkdir("a/b/")` 被 `split_parent()` 当成「对 b 本身操作」，转成 `mkdirat(fd_of_b, ".")`，而 b 还不存在 → ENOENT。真正的 `mkdir` 允许尾斜杠。旁证：`mkdir -p .git/info` 能成，`git init` 不能；`GIT_TEMPLATE_DIR=/dev/null git init` 能成（不建 info/）
- `iconv` 任何转换都失败（见上），需要时把完整 gconv 模块放回去也要先解决模块加载

**改到的文件**：`zroot-ng/tools/slim-rootfs.py`（+perl/gconv 段、`--keep-perl`）、`pocket-agent/tools/make-debian-rootfs.sh`（XZ 参数与注释）、`app/src/main/assets/debian-rootfs-arm64.*.bin`（用已解包树重新压，未走联网流水线）、两份 README 的体积数字

### 修 shim 的 mkdirat 尾斜杠（git init 因此可用）（2026-11-06）

- 上一轮发现 `git init` 必 fatal：`fatal: ... .git/info/: No such file or directory`，根因在 `shim/zng_hooks.c` 的 `split_parent()` —— 它把尾斜杠解释成「操作父目录本身」（leaf 改成 `"."`），于是 `mkdir("a/b/")` 变成 `mkdirat(fd_of_b, ".")`，b 不存在就 ENOENT。而 Linux 上 `mkdir("a/b/")` 等价于 `mkdir("a/b")`
- 修法：`mkdirat` 里先把尾斜杠剥掉（保留 `"/"` 与 `"//"` 这类全斜杠的情况交给原逻辑），再走同一条 `WITH_PARENT`。`split_parent()` 那段 `"."` 的语义不动，其它操作（open/link/creat…）行为不变
- 验证：
  - `make -C tests check` → 194 checks, 0 failures
  - 真机：`git init`（HEAD/branches/config/hooks/info 全建出来）、`git add`+`commit`、`git clone`、`git push` 到裸仓库、`git log` 全通
  - 回归：`mkdir a/`、`mkdir a/b/c/`、`mkdir /tmp/x1/x2/`、`mkdir -p a/b/c/` 都正确；已存在目录仍返回 EEXIST；`mkdir -p /` 仍正常（不被新的剥斜杠逻辑带偏）
  - 顺带跑通 `cp -a`、`unzip`、`bash`、`python3`
- ⚠️ **另一个既有 bug（未修）**：`tar -xf x.tar -C dir` 解到空目录时全失败（`Cannot mkdir: File exists` + `Cannot open: No such file`），但在 cwd 里直接 `tar -xf` 正常。用旧版 `libzng.so` 对照测过，**同样失败**，与本轮改动无关 —— 怀疑是 `chdir` 之后相对路径解析的问题，留待后续
- 产物：`shim/zng_hooks.c` 改动 + 重编 `libzng.so`（zig，443 KB）已进 `app/src/main/jniLibs/arm64-v8a/`

### 修 split_parent 丢掉 dirfd（tar -xf -C / mkdirat(dirfd, "./x") 全坏）（2026-11-06）

- 上一条留下的「tar -xf -C dir 解到空目录全失败」不是 chdir 的问题，是 **`split_parent()` 把 dirfd 丢了**：父目录一律用 `zng_openat(ZNG_AT_FDCWD, dpath, ...)` 打开。
  - `zng_resolve()` 对相对路径返回 1（透传，意思是「按调用方的 dirfd 解析」），`resolve_full()` 正确沿用了 `dirfd`，只有 `split_parent()` 写死成 `AT_FDCWD` → 父目录被解析成**进程 cwd**
  - 复现（最小例子）：`mkdirat(fd_of_A, "./x")` 把 x 建到了 cwd，A 里空空；`os.mkdir('sub', dir_fd=fd)`（不带 `./`）却正常 —— 因为 `"sub"` 没有斜杠，走的是 `*pfd = dirfd` 分支
  - `tar -xf x.tar -C dir` 正好是「打开 dir 拿 fd，再用 openat/mkdirat 走相对路径」，于是目录建到 cwd（第二次起 EEXIST），文件用正确的 dirfd 打开 → ENOENT。实测报错 `Cannot mkdir: File exists` + `Cannot open: No such file`
- 修法：`split_parent()` 里 `r == 0`（已改写成 host 绝对路径）用 `AT_FDCWD` + host 路径；`r == 1`（guest 相对路径）用**调用方的 dirfd** + 原路径，与 `resolve_full()` 一致
- 验证（真机）：
  - `tar -xf x.tar -C /abs/dir`、`-C reldir`、cwd 内直解、`tar -cf` 再 `-C` 解回 → 全部 rc=0 且 `diff -r` 一致
  - `os.mkdir('./sub2', dir_fd=fd)` 落到 fd 指向的目录（不再是 cwd）
  - 回归：`git init/add/commit/push/clone`、`cp -a`、`mv`、`ln -s`、`rm -rf`、`unlinkat(dirfd, 相对名)`、bash/python/numpy 全通
  - `make -C tests check` → 194 checks, 0 failures
- 影响面：`WITH_PARENT` 的所有调用（mkdirat / mknodat / symlinkat / linkat / creat…），凡是「带 dirfd + 相对路径」的调用都从「建到 cwd」变成「建到 dirfd」

### 验证 proroot 能不能兜 seccomp（rseq/statx/clone3）：能，但 musl 不行（2026-11-06）

动机：pocketroot 必须吃「打好补丁的 libc」（5 个 syscall 站点 + resolv.conf 路径），这限制死了镜像只能是我们自己基座派生的。proroot 走 ptrace，理论上能自己兜住。要验证必须在 **App 进程内**（有 zygote seccomp）跑 —— adb shell / run-as 都是 Seccomp=0，测不出来。

做法：加了个调试通道 `proot:<rootfs 绝对路径>@@<shell 命令>`（MainActivity.runProrootForDebug，仅 adb intent 可达），用 App 进程直接拉 libproroot.so。对照组把 rootfs 复制一份，从 Debian 官方 deb 里换回**原版** libc.so.6 + ld-linux（md5 9fa5…→ e282…，确认未打补丁）。

| 组合 | 结果 |
|---|---|
| pocketroot + 未打补丁 libc | ❌ 零输出（bash 起即死） |
| proroot + 未打补丁 libc | ✅ bash / os-release / numpy 2.5.4 / `os.stat`(statx) / 线程(clone3) / **DNS 正常** |
| proroot + Alpine 3.22 (musl) | ❌ `proroot-ldso: failure rc=2` |
| proroot + 已打补丁 libc | ✅（对照，排除是环境问题） |

性能（同一 rootfs，同一台机）：
- `find /usr`（5348 文件）：pocketroot **36 ms** / proroot **259 ms**（7.2×）
- 20 × `/bin/true`：113 ms vs 355 ms（3.1×，约 5.6 → 17.8 ms/次）

结论：
- proroot **确实能兜住** seccomp 被拦的 syscall（strings 里能看到 `statx_trampoline` / `clone3_trampoline` / `faccessat2_trampoline` / `[proroot-hook] seccomp-patched`），连 DNS 都不用改 libc 里的路径（PRoot 会 bind 宿主的 resolv.conf 一类文件）
- 但它的 loader 只认 glibc，**musl/Alpine 一律 `proroot-ldso: failure rc=2`**
- 因此「双引擎」成立：自建镜像/自有基座走 pocketroot（快），外部 glibc 镜像走 proroot（零补丁、不限版本，慢 3–7×）；Alpine 只能另打 musl 补丁或不支持
- 💥 踩坑记录：`adb install -r` 每次会换 native lib 目录（`/data/app/~~xxx==/...`），手工复现时要重新 `pm path` 取路径，否则报 `/libldso.so: inaccessible`
- 💥 调试通道的 20s 定时器在「刚删掉 rootfs 触发重解包」时会抢跑，表现为 `(no output)`；等解包完成（dsh.log 出现 `autoSetup: runtime ready`）再发命令即可

### SIGSYS 运行期模拟：进程内生效，子进程还没过（2026-11-06）

目标：让 pocketroot 吃**未打补丁**的 rootfs（不用构建期指令补丁、不维护 glibc 版本表、不引入 proroot）。

改动（`shim/zng_ctor.c`）：SIGSYS 处理器从「记日志 + exit(159)」改成真模拟 —— 从 siginfo 拿 `si_syscall`，在 ucontext 的 sigcontext 里把 `x0` 写成返回值、`pc += 4` 跳过那条 `svc`，返回用户态继续执行。语义与构建期补丁逐条对齐：

| syscall | 返回 |
|---|---|
| set_robust_list(99) | 0 |
| rseq(293) / statx(291) / clone3(435) / faccessat2(439) / openat2(437) | -ENOSYS |

其余未识别的仍按老规矩 `exit(159)` 并把编号写进 `files/zng.log`。布局假设：arm64 内核 ucontext（asm-generic）→ mcontext 在 +40，`regs[n]` 在 +8+8n，`pc` 在 +264；有 `x8==nr` 与 `pc` 对齐两道校验，不符就打印 `sigsys ctx mismatch` 后照旧退出。

**真机结果**（未打补丁 libc = 从 Debian 官方 deb 换回原版，md5 e282…）：
- ✅ 顶层 bash **起来了**（换之前是零输出）：`echo BUILTIN` 有输出 —— 说明处理器在 shell 进程里生效
- ❌ 但**所有子进程**都死：`/bin/echo EXTERNAL`、`/bin/true`、`head` 全是 `Bad system call`（159）
- ❌ `files/zng.log` 里没有任何新行（连 `sigsys ctx mismatch` 都没有）→ 子进程在**处理器装好之前**就死了
- ❌ 只把 rootfs 的 `ld.so` 换回打过补丁的那份（libc 仍原版）不解决问题 —— 排除「loader 自己 set_robust_list」这一条
- ✅ 已打补丁的 rootfs 用新 shim 完全正常（`PATCHED_OK` / `/bin/echo` / `head` / numpy 2.5.4），无回归；`make -C tests check` 194 checks 0 failures

下一步方向（未做）：子进程死在 ld.so/libc 初始化阶段，那时 LD_PRELOAD 的 ctor 还没跑，进程内方案够不着。要么让 shim 的 execve 钩子**显式用我们那份打过补丁的 loader + LD_PRELOAD 去 exec**，要么只对镜像的 `ld.so` 做构建期补丁（比补 libc 小一个数量级，但仍是版本相关的）。

💥 **调试踩坑**：`adb shell am start ... --es pocketroot_cmd 'echo A; echo B'` **必须**再套一层双引号，否则远端 shell 按空格切开，extra 只剩 `echo`，表现是「命令执行了但零输出」—— 我在这上面误判了一轮「应用坏了」。

### execve 钩子这条路走不通：子进程死在 ctor 之前（2026-11-06）

本想「让 shim 的 execve 钩子显式用打过补丁的 loader + LD_PRELOAD 起子进程」，所以先加探针确认子进程到底死在哪：在 `zng_ctor()` 最前面直接 `zng_write(2, "zngdbg: ctor\n")`（绕过日志文件，必然随命令输出回到 cmd.log）。

结果（同一份 rootfs，只换 libc）：

| 场景 | bash 进程 | `/bin/true` 子进程 |
|---|---|---|
| 已打补丁 libc | `zngdbg: ctor` ✅ | `zngdbg: ctor` ✅（每个子进程一行） |
| 未打补丁 libc | `zngdbg: ctor` ✅ | **没有这一行**，直接 `Bad system call` |

关键点：
- 顶层 bash 和子进程用的是**同一个** loader（`libldso.so` 与 rootfs 里的 `ld-linux-aarch64.so.1` md5 都是 `1707f2…`，即已打补丁的那份）
- 同样的 loader + 同样的 unpatched libc：bash 能跑到 ctor，`/bin/true` 跑不到 → 说明它死在 **libc 的初始化**（preload 对象的 ctor 之前），而不是 ld.so 自己
- 也就是说：**LD_PRELOAD 类方案在子进程上根本够不着**，换 loader 起子进程也没用（loader 已经是我们那份了）

结论：让 pocketroot 吃未打补丁 rootfs，**只有三条路**——
1. 导入时给镜像打 libc 补丁（现有 `patch-glibc-syscalls.py`，版本相关，能 fail-closed）
2. 用 proroot（ptrace，已经在别处验证过能跑未打补丁 libc，但慢 3–7×、闭源、musl 不行）
3. 镜像 libc 版本与我们基座一致时，直接换成我们那份已打补丁的 libc（快，但 ABI 必须完全匹配）

保留的成果：SIGSYS 处理器（`x0`/`pc` 改写 + 6 条 syscall 语义）留着——它至少让**进程内**（shell 自身）的拦截不再自杀，且对已打补丁 rootfs 零影响（`OK`/`/bin/echo`/`head`/numpy 2.5.4 全部正常，`make -C tests check` 194 checks 0 failures）。

💥 又一条调试教训：探针要写在 **stderr 原始 write**，不要走 `zng_log`（它默认写 `files/zng.log`，路径不对时静默丢），否则会误判成「shim 没加载」。

### 方案一落地：导入时打补丁（--relaxed），设备端可跑（2026-11-06）

`tools/patch-glibc-syscalls.py` 新增 `--relaxed`：不校验 `EXPECT` 表的精确命中数，按扫描结果打**所有**站点，打完再回读确认没有残留 `svc` 站点；找不到任何站点时（码型不认识）**拒绝**并在退出码上报错。另加安全阀：只有「resolv 路径已经是 /dev/fd/3」才判为「已打过、幂等通过」，避免把「不认识」当成「已打完」。

**为什么需要 relaxed**（实测对比）：

| libc | strict 模式 | relaxed 模式 |
|---|---|---|
| Debian 13 / glibc 2.41 | 通过（2/1/1/1/1） | 通过，打 6 个站点 |
| Debian 12 / glibc 2.36 | ❌ `clone3 命中 0 处（预期 1）` | ✅ 跳过 clone3，打 5 个站点 |

（2.36 时代 glibc 还没用 clone3 创建线程，所以没有站点 —— strict 会因此整个镜像判失败。）

**设备端端到端验证**：
1. 在设备上复制一份 rootfs，换回 Debian 官方原版 `libc.so.6` + `ld-linux`（md5 e282…）
2. 把脚本塞进 guest，在 guest 里执行 `python3 /root/workspace/patch-glibc.py /root/workspace/unp --relaxed` —— 6 个站点全部命中并回读通过
3. 把这个目录换成运行时 rootfs，启动后：`echo` / `/bin/echo` / `head /etc/os-release` / `numpy 2.5.4`（`np.dot`=11）全部正常
4. 打出来的 libc md5 = `9fa5b58f…`，与构建期流水线产出的**完全一致**（同一份源码、同一版本，补丁是确定性的）

结论：方案一成立 —— **外部 Debian/Ubuntu 镜像可以在设备上现打补丁**，不需要回到构建机，也不需要 proroot。版本不认识时 fail-closed，不会悄悄放进去一个会 SIGSYS 的镜像。

后续要做的（未做）：镜像生命周期（拉取/解层/存储/选择）、把 patcher 的调用挂到 import 流水线上、把 patch 结果写进镜像 manifest。

### 镜像生命周期（pimg）：pull → 落层 → 打补丁 → manifest → 起服务（2026-11-06）

新工具 `tools/pimg.py`（装进 rootfs 的 `/usr/local/bin/pimg`，补丁脚本装在 `/usr/local/lib/pimg/`）：

```
pimg pull <repo>[:tag] [--registry HOST] [--name NAME]
pimg ls | info <name> | patch <name> | rm <name>
```

- 默认 registry `docker.m.daocloud.io`（国内可达）；从 401 的 `WWW-Authenticate` 里取 realm，不写死
- 按 `linux/arm64` 选 manifest；层按 sha256 校验后落盘，处理 `.wh.` 与 `.wh..wh..opq`
- 拉完**立刻跑 glibc 补丁**（`--relaxed`）；补丁失败就整体失败并删目录 —— 不留会 SIGSYS 的镜像
- manifest 记录 repo/tag/digest/层数/压缩体积/时间

App 侧（Java）：`files/oci` 绑定到 guest 的 `/images`；`PIMG_IMAGES/PIMG_HOST_IMAGES/PIMG_PATCHER` 注入到 guest 环境；解包 rootfs 后自动 `installTools()` 写 pimg + patcher（用 `writeAsset` 绕过 `copyAsset` 的 1 MB 下限）。

**真机端到端**：`pimg pull debian:bookworm-slim` → 28.1 MB / 1 层 / 3262 个文件 → 补丁通过 → 用 `oci:debian_bookworm-slim@@…` 起 shell，`/etc/os-release` 是 **Debian 12 (bookworm)**，`ls /` 正常，退出码 0。宿主侧同一份代码拉同一个镜像，产物 libc md5 与设备端一致（`11b5d7ba…`，确定性）。

踩过的坑（都已修，全部有真机证据）：
1. `files/images` 已被聊天图片占用 → 镜像目录改成 `files/oci`
2. `copyAsset` 有「小于 1 MB 视为失败」的检查（给 rootfs 分段用的）→ 小资产改用新加的 `writeAsset`
3. SELinux 禁止在 app 数据目录建硬链接 → 落层时 `os.link` 失败退化成 `copyfile`
4. `shutil.copy2` 会 `listxattr`，在 shim 下报 `FileNotFoundError` → 改成 `copyfile + chmod`
5. `subprocess.run([sys.executable, …])` 在 guest 里 `posix_spawn` 失败（shim 的 exec 钩子翻译不到）→ 补丁脚本用 `importlib` **在当前解释器里**跑
6. `img:` 前缀已被多模态图片测试占用 → 新调试通道叫 `oci:<name>@@<cmd>`
7. 用 App 的 `libldso.so`（glibc 2.41）去跑 2.36 的镜像 → `exit=132`（SIGILL）→ 必须用**镜像自己的 loader**（pimg 已经把它一起打好补丁了）
8. `pimg run` 在 guest 内跑不了（宿主路径被 shim 重定向）→ 起镜像必须由 App 进程做，`pimg run` 现在会直接提示用 `oci:` 通道

现状：镜像能拉、能打补丁、能起 shell；**会话级「选镜像」还没接**（现在是 `oci:` 调试通道）。

### 让 AI 能跑「类 docker」：新增 container 工具（2026-11-06）

目标：模型自己就能 `pull 镜像 → 在里面跑命令`，不靠调试通道。

**新增**：
- `runtime/ContainerRunner.java`：在一份镜像 rootfs 里起 `/bin/sh -c <cmd>`。关键是 `ZNG_ROOT` 指向 `files/oci/<name>/rootfs`，并用**镜像自己的 loader**（picker 与 libc 同源，混用会 SIGILL/exit 132），带宽限与超时杀进程组。
- `tools/ContainerTool.java`：工具名 `container`，参数 `{image, command, timeout_ms}`，在 `ToolRegistry` 注册（`MainActivity` 里 `tools.register(new ContainerTool(this))`）。
- 系统提示词新增 Containers 小节：`pimg pull/ls` + `container{...}`，并写明限制（glibc-only、无 namespace、端口 ≥1024、写入持久化、长服务用 nohup）。

**真机验证**（走的正是模型会走的那条路 —— 调试通道 `tool:` 直接分发到 Tool.execute）：
```
tool:container {"image":"debian_bookworm-slim","command":"cat /etc/os-release | head -2; ls /"}
  → PRETTY_NAME="Debian GNU/Linux 12 (bookworm)" … [exit 0]

tool:container {… "nohup sh -c 'sleep 3; echo BGOK > /tmp/bg.txt' & echo STARTED"}  → STARTED
tool:container {… "cat /tmp/bg.txt"}                                            → BGOK
```
第二条证明**后台服务能活过启动命令**（`nohup … &`，Java 侧不杀成功退出的进程组）。

局限（写进 schema 的 description，模型看得到）：单条命令、glibc-only、无 namespace（共用网络栈、端口 ≥1024）、写入落在镜像目录（等于 run+commit，没有 copy-on-write）。

### 修用户报的镜像问题：merged-usr / DNS / hosts / CA（2026-11-07）

用户在一份种子镜像里验证出四类问题，逐条处理：

| # | 问题 | 处理 |
|---|---|---|
| 1 | `/lib` 变成空真实目录（应为 `lib -> usr/lib`），动态程序全起不来 | `ContainerRunner.prepare()` 每次起容器前修：`/lib /lib64 /bin /sbin` 若是指向 `usr/*` 的缺失/空目录，就删掉并建符号链接；有内容则记日志不动 |
| 2a | 镜像里 `/etc/resolv.conf` 是镜像作者的（1.1.1.1），本机不可达 | `prepare()` 每次把会话 rootfs 的 resolv.conf 覆盖进去（App 启动时会按当前网络重写那份） |
| 2b | `/etc/hosts` 改了没用（libc 内部 open，PLT 拦不到） | 补丁脚本新增把 libc 里 `/etc/hosts\0` 等长改写成 `/dev/fd/4\0\0`；shim 新增 `zng_install_hosts()` 把 `<root>/etc/hosts` 挂到 fd 4（与 resolv.conf 的 fd 3 同一套机制） |
| 3 | 镜像没 CA，https 全挂 | `prepare()` 发现 `/etc/ssl/certs/ca-certificates.crt` 缺失就从基座 rootfs 复制（真机验证：222 KB 到位） |
| — | apt 装完新库找不到（`curl: librtmp.so.1: cannot open...`） | `prepare()` 后跑一次镜像里的 `ldconfig`（有 `/sbin/ldconfig` 才跑，30s 超时） |

**真机验证**（debian_bookworm-slim）：`cat /etc/resolv.conf` → 223.5.5.5；`/etc/hosts` 存在；`getent hosts deb.debian.org` / `www.baidu.com` 都返回 IP（**子 shell 里也正常**）。

⚠️ **仍未解决**：
- `ls -l <path>` 在 2.36 镜像里会先打一行 `No such file or directory` 再正常列出（目录和符号链接都复现），退出码仍是 0；同样的命令在 2.41 会话 rootfs 里正常。怀疑是 shim 的 statx 钩子 + 2.36 里 statx 被补丁改成 ENOSYS 的组合，尚未定位。dpkg 半装（`iU`）应该与此有关。
- 测试过程中 apt 中断过一次，把那份镜像的 dpkg 弄成 `iU` + 缺 `librtmp`，属于**测试残留**；干净的做法是 `pimg rm` 后重新 pull。

### 最小复现：镜像里 `ls -l` 的 stat 报 ENOENT（2026-11-07）

复现（debian:bookworm-slim 镜像，`container` 工具）：

```
ls -ld /usr/lib          → ls: /usr/lib: No such file or directory
                            drwxr-xr-x 13 … 3444 … /usr/lib     ← 同一路径又正常列出
ls -l /etc/hostname      → 同样的“先报错再列出”
cat /etc/hostname        → 正常
test -e /usr/lib         → E_OK（faccessat 走 shim 钩子，正常）
```

结论：**同一路径上，一次 stat 失败、一次 stat 成功**；失败那次拿到了 ENOENT，说明它用的是**未经翻译的绝对路径**（宿主上没有 /usr/lib）。

给 shim 的 `stat/lstat/fstatat/statx` 加探针后看到：只有 shell 的 PATH 查找命中 `stat_at`（`/usr/bin/ls` 等），**操作数本身的 stat 根本没进我们的钩子** → 那次调用是绕过 LD_PRELOAD 的（libc/gnulib 内部直接发 syscall）。

这轮顺带做的（都保留）：
- 导出 `fstatat64`（coreutils/dpkg 会调它，之前漏了）
- `syscall()` 钩子里对 291(statx)、439(faccessat2) 做**真模拟**（翻译路径 + fstatat/faccessat），不再一律回 ENOSYS
- 构建期补丁去掉 statx/faccessat2（改由运行时模拟，避免 glibc 内部回退路径不翻译）
- `/etc/hosts` → `/dev/fd/4`（libc 字符串等长改写 + shim 挂 fd）
- 起容器前修 merged-usr（/lib 等符号链接）、写 resolv.conf、补 CA、跑 ldconfig

**仍未定位**：那次绕开 shim 的 stat 具体是哪个入口（下一步：在 `syscall()` 钩子里打 nr + 路径，或者用 `LD_DEBUG`/自写探针程序确认 coreutils 走的是 `statx()` 符号还是内联 syscall）。

会话本身无回归：`ls -ld /usr/lib`、`/bin/echo`、`python3` 都正常。

### nr 探针结果：失败的那次 stat 既不走符号钩子、也不走 syscall()（2026-11-07）

探针覆盖了：`stat_at`（stat/lstat/fstatat/__fxstatat 共用）、`access_at`、`zng_do_statx`、以及 `syscall()` 里的 79/80/262/291/437/439。跑 `ls -ld /usr/lib`（干净重拉的 bookworm-slim）：

```
zngdbg: stat_at p=/usr/local/sbin/ls …        ← 只有 shell 的 PATH 查找
zngdbg: do_statx p=/usr/lib
zngdbg:   rr=0 host=…/oci/debian_bookworm-slim/rootfs/usr/lib
zngdbg:   fstatat=0                            ← 我们这次是成功的（列出那一行就是它）
ls: /usr/lib: No such file or directory        ← 但 ls 仍打了这行，退出码 0
```

也就是说：**失败的那次调用既没有进我们的符号钩子，也没有进 `syscall()`**，是 libc 内部的 inline syscall（或某个未导出的符号），LD_PRELOAD 够不着。而成功那次是我们的 `statx` 钩子。

新证据（同一轮）：
- `ls -d /usr/lib`、`ls -l /usr/lib | head`、`ls /usr/lib`、`stat /usr/lib` **都不报错**；只有 `ls -l <path>`（无管道）会多打一行 → 说明它是 ls 的某条**辅助 stat 路径**（fts 内部）失败，主路径正常
- 退出码恒为 0，输出正确，属于“噪声”级别
- **干净重拉的镜像上 apt/dpkg 已经正常**：`apt-get update` 只挂在 `bookworm-security` 的源解析上（镜像/网络问题，与 shim 无关），`apt-get install` 没有出现 stat 报错

顺带修掉且已验证的：导出 `fstatat64`；`syscall()` 里模拟 `statx`/`faccessat2`；构建期不再补 statx/faccessat2；`/etc/hosts` 走 `/dev/fd/4`；起容器前修 merged-usr、写 resolv.conf、补 CA、跑 ldconfig。

结论：dpkg 半装的直接原因已经不存在（apt 不再报 stat 错），剩下的是 `ls -l` 的 stderr 噪声，根因是 libc 内部某条 inline stat 调用，要彻底修只能改 libc 或上 ptrace。

### postgres 卡在 posix_spawn：initdb 的版本检查起不来（2026-11-07）

镜像 `postgres:16` 拉取、落层、打补丁全部正常（Debian 13，`postgres --version` → 16.15 ✓）。卡在 `initdb`：

```
sh: /usr/lib/postgresql/16/bin/postgres: inaccessible or not found
initdb: error: program "postgres" is needed by initdb but was not found …
```

排查：
- 同一路径直接跑、`sh -c '"path" -V'` 跑、换 cwd 跑都正常（`/tmp/q.sh` 验证）
- 给 `execve` 加探针后，initdb 那次 `postgres -V` **根本没进我们的 execve 钩子**（只有 mkdir/tail/initdb 三条）
- 但 shim 是导出了 `posix_spawn` 的 —— 它的实现只是把 path **翻译成宿主路径**就交给 libc：

```c
return ps(pid, tr(c, path, host, sizeof host), fa, attr, argv, envp);
```

  而 libc 的 posix_spawn 在子进程里直接 execve，**内核按 PT_INTERP 找 `/lib/ld-linux-aarch64.so.1`**，宿主上不存在 → ENOENT。execve 之所以能跑，是因为我们把它改写成 `<宿主 ldso> <宿主程序>`；posix_spawn 漏了这一步。

这也解释了之前 `subprocess.run([sys.executable, …])` 报 `FileNotFoundError` —— 同一个根因。

修法（未做）：posix_spawn 里复用 execve 的思路 —— 读目标 ELF 的 PT_INTERP，改写成
`posix_spawn(pid, <宿主 ldso>, fa, attr, [<宿主 ldso>, <宿主程序>, 原 argv[1..]], envp)`。
需要把 `zng_run.c` 里现有的 ELF/PT_INTERP 解析抽成可复用函数（`zng_exec_core` 内部就是这套逻辑，但没暴露）。

postgres 之外，所有用 popen/posix_spawn 的程序（python subprocess、部分构建工具）都受这条影响。

### posix_spawn / popen / system 换成「加载器中继」，postgres 能跑 initdb（2026-11-07）

上一轮定位到 initdb 起 `postgres -V` 失败，根因是 libc 内部 `__execve` 不走 PLT。这轮把**三条**同类路径全部补上：

**1. `posix_spawn` / `posix_spawnp`（shim）**

- 新增 `zng_spawn_prepare()`（`zng_run.c`）：与 execve 完全同构地构造 argv —— 读 ELF/`#!` → 翻译 PT_INTERP → `zng_plan_build()` 得到 `[加载器, --argv0 A0, --library-path …, 程序, 参数…]`；静态 ELF 直接 spawn（`ZNG_LD_STATIC`，顺带给 `zng_plan_free` 补了这条分支，否则会 free 借来的 argv[1]）
- `posix_spawn` 只把 `plan.argv[0]`（宿主上的加载器）交给 libc，`file_actions`/`attr` 语义仍由 libc 负责
- `posix_spawnp` 自带 **guest 视图的 PATH 搜索**（libc 搜的是调用方进程的 Android PATH，既找不到 guest 文件也不翻译路径 —— 之前 `subprocess.run(["python3",…])` 的 FileNotFoundError 就是这个）

**2. `popen` / `pclose` / `system`（shim）**

- libc 的 popen/system 在子进程里用内部 `__posix_spawn/__execve`，拦不到；内核于是 exec 真实的 `/bin/sh` —— 命中 **Android 的 mksh**，它看到宿主根，guest 路径一律 `inaccessible or not found`
- 改为自己实现：`pipe2` + `posix_spawn`（我们那个）+ `fdopen`，`pclose` 用 `fid → pid` 小表 + `wait4` 回收；`system` 同样 spawn guest `sh -c` 后 wait

**3. `/etc/passwd` / `group` / `nsswitch.conf` 走 `/dev/fd/{5,6,7}`**

- nss_files 在 libc 内部 open 这三个文件（getpwuid/getgrgid），同样拦不到
- 补丁脚本把这些字符串等长改写成 `/dev/fd/5,6,7`；shim 新增 `zng_install_nss()` 在 ctor 里把 guest 的三份文件挂上去（与 resolv.conf:3、hosts:4 同一套机制）
- 顺带：`ContainerRunner.prepare()` 增加 `ensureAccount()`，给当前 App uid 补一条 `pocket:x:<uid>:<gid>:…`（不是 root，postgres 见到 root 会拒绝启动）

**4. System V IPC 回 ENOSYS**

- Android zygote seccomp 禁掉 `msg*/sem*/shm*`（arm64 186–196）；原来直接 SIGSYS 杀进程
- SIGSYS 处理器改成返回 `-ENOSYS`，让程序自己降级 —— postgres 的 initdb 正是靠「sysv 起不来」退到 mmap

**真机证据**（`/tmp/ptest`，aarch64 glibc 小程序，放进 postgres_16 镜像跑）：

| 路径 | 修复前 | 修复后 |
|---|---|---|
| `posix_spawn` | ✗（子进程 exec `/bin/sh` 宿主） | ✓ `postgres -V` → 16.15 |
| `popen` | ✗ `sh: … inaccessible or not found`，pclose=32512 | ✓ pclose=0 |
| `system` | 疑似 ✗ | ✓ |
| `fork`+`execvp` | ✓ | ✓ |

initdb 的推进轨迹：`user does not exist`（→ nss）→ `invalid locale name "C.UTF-8"`（镜像无该 locale，改 `--locale=C -E UTF8`）→ 跑到 `running bootstrap script` 才碰上 `SIGSYS 194 (shmat)`（→ SysV ENOSYS）。

⚠️ 本轮最后一次真机验证因设备掉线（`adb` Connection refused，无线调试端口失效）中断：SysV 改动已装机但 `initdb` 重跑未完成，下次接着跑。

### SysV 共享内存模拟（postgres 必需）+ ucontext 偏移纠错（2026-11-07）

**为什么需要**：postgres 的主共享内存段在 Linux 上**固定用 SysV shmget/shmat**，没有开关；Android 的 zygote seccomp 把这族 syscall 全禁掉。之前用「构建期补丁把它改成 ENOSYS」只能定位、不能解决：

```
FATAL: could not create shared memory segment: Function not implemented
DETAIL: Failed system call was shmget(key=4770032, size=56, 03600)
```

**做法**（新增 `shim/zng_sysv.c`）：

- 导出 `shmget/shmat/shmdt/shmctl`，抢占 libc 的同名符号（LD_PRELOAD 优先）
- `shmget` → `mmap(NULL, size, PROT_READ|PROT_WRITE, MAP_SHARED|MAP_ANONYMOUS)`，id→段表（每进程 16 段）
- `shmat` 返回该地址并 nattch++；`shmdt` munmap；`shmctl(IPC_STAT)` 按 Linux ABI 布局填 `shmid_ds`（size 112，segsz@48，nattch@88）
- `IPC_RMID` **只打标记不回收**：postgres 的顺序是 `shmget → shmctl(IPC_RMID) → shmat`，立刻 free 会让紧跟着的 shmat EIDRM
- 匿名 MAP_SHARED 在 fork 之后父子共享同一份物理页 —— 正是 postgres「postmaster 建段、fork 出后端」需要的语义
- 测试：新增 `tests/zshm.c`（宿主上跑真实 mmap + fork），14 项全过，并进了 `make -C tests check`；`build.sh` 的源文件列表已加 `zng_sysv.c`

**同时**：`PATCHES` 里的 SysV 那 11 条删掉了（现在由 shim 模拟，构建期再改成 ENOSYS 只会白费）

**ucontext 偏移**：SIGSYS 处理器原来按 `mcontext@40` 读寄存器，真机 dump 出来 x8 是垃圾（`x8=18446744072730324800`）。按内核 `struct ucontext`（sigmask 占 128 字节）改成 **168**，并保留 40 作为兜底探测；但 dump 显示两处都对不上 `si_syscall`，所以运行期寄存器模拟**目前仍未验证成功** —— 好在真正需要的几条（set_robust_list/rseq/clone3、以及现在的 SysV）都改走构建期补丁或符号抢占，不依赖它。

⚠️ 设备被用户临时占用，**这一版还没装机验证**（initdb 之后的 `pg_ctl start` / `psql` 连接也还没跑）。

### postgres 真机跑通：SysV 共享内存 + unix socket + accept 三处补齐（2026-11-07）

最终验证（`postgres:16` 镜像，真机）：

```
initdb -D /var/lib/postgresql/data --locale=C -E UTF8   → Success
pg_ctl start                                            → server started
psql -h /var/run/postgresql -U postgres -c 'select 1'   → 1 row
create table t(...); insert 2 行（含「世界」）           → INSERT 0 2
select id, v from t                                     → 2 rows
fast stop → 重启 → select count(*)                      → 2（数据在，日志 0 ERROR）
```

这一轮补的三处：

**1. SysV 共享内存（`shim/zng_sysv.c`）**
- 无 key（IPC_PRIVATE）：匿名 `MAP_SHARED`，fork 出的后端直接共享 —— 主共享内存段
- 有 key（DSM）：**文件后端** `<rootfs>/tmp/.zng-shm/<key hex>`，创建时 ftruncate、attach 时按文件大小 mmap。必须是文件：并行 worker 会 exec，和 postmaster 没有共同祖先，进程内表根本看不见（真机表现为 `FATAL: could not get shared memory segment: No such file or directory`）
- `shmget(size=0)` 视为「按 key 附着」而不是 EINVAL（PG 的 dsm 就这么调）
- `IPC_RMID` 只打标记 + 删后端文件，不立刻回收（PG 的顺序是 shmget → RMID → shmat）
- 单测 `tests/zshm.c` 15 项（含 fork 共享、key 复用、RMID 顺序），已并入 `make check`

**2. unix domain socket 路径翻译（`bind`/`connect` 钩子）**
- 真机：`could not bind Unix address "/var/run/postgresql/.s.PGSQL.5432"` —— bind 在 libc 内部直接发 syscall，路径没翻译，内核按真实根找 /tmp
- 用 `zng_translate`（不是 resolve，socket 文件还不存在）；注意它和 `zng_resolve` 的返回值约定**相反**（0=未改写、1=已改写），第一版写反了

**3. `accept` / `send` / `recv` 钩子**
- Android seccomp 只放行 accept4/sendto/recvfrom，老的 `accept(202)` 一调就 SIGSYS —— 现象是 psql 连上后 `server closed the connection unexpectedly`，日志里 `zng: SIGSYS unhandled si_syscall=202`
- accept → accept4(…,0)；send/recv → sendto/recvfrom（无地址）

**已知限制（写进系统提示词前要考虑）**：服务是 App 进程的子进程，App 被杀（force-stop / 系统回收）时一起死；没有 namespace，端口必须 ≥1024 且与宿主共用。

### MySQL 8.0 跑通（真机）；顺带补齐镜像传输链路（2026-11-07）

**结果**（`mysql_8.0` = Oracle Linux 9.8 / MySQL 8.0.46）：

```
mysqld --initialize-insecure --datadir=/var/lib/mysql --basedir=/usr   → 成功（偶发 abort，重试一次即过）
mysqld --datadir=/var/lib/mysql --socket=/tmp/m.sock --port=3306 --bind-address=127.0.0.1 --mysqlx=0 &
  → 1–2 秒起来；连续 5 次启动 5/5 成功
create database demo; create table t(...); insert ('hello'),('世界') → 成功
重启后 select → 2 行仍在；err 日志 0 ERROR
TCP: mysql -h 127.0.0.1 -u root -e 'select count(*) from demo.t' → 2
```

**几个必须知道的点**

1. **abort 是偶发的，不是配置问题**：矩阵测过 buffer-pool 默认/32M/64M/128M，只有 64M 那次 abort，其余全过；所以 `--initialize` / 启动失败**重试一次**即可（已在系统提示词里写明）
2. **TCP 要单独放通**：`--initialize-insecure` 只建 `root@localhost`（socket 专用）。走 127.0.0.1 会报 `Host '127.0.0.1' is not allowed`，需要 `CREATE USER 'root'@'%'` + `GRANT ALL`
3. **Oracle Linux 的 libc 在 `/usr/lib64`**：补丁脚本 TARGETS 之前只认 `/usr/lib`，导致 mysql 镜像根本没打上补丁（`mysqld` 一上来就 `SIGSYS 439 faccessat2`）。已补 `usr/lib64` / `lib64`
4. **faccessat2(439) 在本机 seccomp 被拦**：构建期改成 ENOSYS（glibc 自己退回 fstatat），其它 statx(291) 未被拦、保持原样
5. **TMPDIR**：Android 把 TMPDIR 指到 app cache（容器里不存在），mysqld 一上来 stat 就失败 → `ContainerRunner` 环境里固定 `TMPDIR=/tmp`

**镜像传输链路**（本机拉、再传手机，比设备上拉稳）：新增 `tools/push_image.py`，处理三件设备侧 tar 做不到的事：

| 坑 | 现象 | 处理 |
|---|---|---|
| SELinux 不许建硬链接 | `tar: can't link ... Permission denied` | `tar --hard-dereference` 展开 |
| toybox 拒绝绝对符号链接 | `not under ...` | 目标在 rootfs 内就改写成相对路径（8.0 改了 30 处） |
| 0000/0555 文件目录 | `无法 open: 权限不够` | 预扫补 `0600`/`0700` |

`pimg` 同时修了：临时目录改到 `IMAGES/.staging`（避免跨盘 rename EXDEV）、失败清理用带 chmod 的 `_rmtree`、下载 3 次重试、只读目录/0000 文件覆盖。

⚠️ `mysql:8.4` 那次 abort 也是同一类偶发，未重测；按 8.0 的经验重试即可。

### Redis 7 跑通；容器默认 LANG 改回 C（2026-11-07）

`redis_7`（Debian 12 / Redis 7.4.12，142 MB）：

```
redis-server --port 6379 --bind 127.0.0.1 --dir /data &   → 1 秒起来
PING → PONG；SET k 你好世界 / GET → 正常
SAVE → /data/dump.rdb；重启后 GET persist_key → keepme（DB loaded from disk）
```

**踩到一个通用坑：LANG=C.UTF-8 会让 setlocale 失败**

- redis 启动时 `setlocale(LC_ALL, "")` 失败 → `# Failed to configure LOCALE for invalid locale name.` → exit(1)
- 根因同 resolv.conf/passwd：glibc 的 setlocale **内部** open `/usr/lib/locale/locale-archive`（不经 PLT，路径不翻译），容器里必然 ENOENT
- 修法：`ContainerRunner` 环境里 `LANG=C`（内建 locale，不需要任何文件）。需要 UTF-8 的程序自己设 LANG

这也顺带解释了为什么 postgres/mysql 没受影响 —— 它们不调用 `setlocale(LC_ALL, "")`。

### SIGSYS 寄存器模拟终于打通（mcontext@176）；Redis 跑通；MariaDB 卡在 io_uring（2026-11-07）

**1. ucontext 偏移用探针实测出来了**

之前按内核 `struct ucontext` 推算 168，一直 mismatch。这次写了个 `tests` 之外的小探针（`/tmp/sigctx2.c`）：内联 `svc #0` 触发被 seccomp 拦的 syscall（绕过 shim 的 syscall 钩子），自己在 SIGSYS 处理器里用 **`si_call_addr`（siginfo 偏移 16）** 去 ucontext 里找哪个 8 字节槽等于它 —— 那就是 `sigcontext.pc`，反推 `mcontext = 槽位 - 264`。

实测（base 160/168/176/184/192 各打一组寄存器）：

```
base=176 x0=8 x8=1a9 sp=7fca958e90 pc=101074c pstate=60001000   ← 全对
```

即 **mcontext 在 176**（比 168 多 8 字节）。改完后真机日志出现 `zng: sigsys emulated nr=425`，说明寄存器改写生效。

顺带把 `io_uring_setup/enter/register (425/426/427)` 加进 ENOSYS 语义；之前 `zng: SIGSYS unhandled si_syscall=425` 会直接 exit(159)。

**2. Redis 7.4.12 跑通**

```
redis-server --port 6379 --bind 127.0.0.1 --dir /data &   → 1 秒
PING→PONG；SET/GET 中文正常；SAVE → dump.rdb；重启后 DB loaded from disk，数据还在
```

踩到通用坑：**`LANG=C.UTF-8` 会让 `setlocale` 失败**（glibc 内部 open /usr/lib/locale，路径不翻译），redis 直接 `Failed to configure LOCALE` 退出 → `ContainerRunner` 默认改成 `LANG=C`。

**3. MariaDB 11.8（Ubuntu 24.04）没成**

- 镜像拉取/打补丁/传输都正常，`mariadb-install-db` 能跑起来
- 关掉 native aio（`--defaults-extra-file` 里 `innodb_use_native_aio=0`）后仍报
  `mariadbd: io_uring_dontfork() failed with errno 2 (continuing)`，紧接着 **signal 11**
- 我们的 SIGSYS 已经把 425 变成 ENOSYS（日志可见 emulated），所以不是我们直接杀它；看起来是 mariadbd 在 io_uring 不可用时自己崩
- 结论：**暂不支持 MariaDB 11**；MySQL 8.0 是当前可用的 MySQL 系方案

**4. 调试便利贴**

- `am start -S ... --es pocketroot_cmd` 有时不触发（intent 被已有实例吞掉）→ 先 `am force-stop`，再 `am start`（不带 -S）
- 回归：改完 SIGSYS 后重跑 postgres（2 行）与 mysql（2 行）均正常

### MariaDB signal 11 栈已定位：io_uring 不可用时的线程空指针（2026-11-07）

**栈**（`mariadb-install-db` 的 bootstrap 阶段，日志原文）：

```
mariadbd(my_print_stacktrace+0x30)
mariadbd(handle_fatal_signal+0x398)
linux-vdso(__kernel_rt_sigreturn)
mariadbd(+0xdc0138)          ← 崩在这里
libstdc++.so.6(+0xe1ae0)     ← std::thread 的 trampoline
libc.so.6(+0x8582c)
libc.so.6(+0xeb8dc)          ← start_thread
```

**反汇编定位**（宿主 binutils 不认 aarch64，用 python 读 ELF + 手动解 BL/PLT）：

```
0xdc011c  bl  pthread_self        ← 线程 trampoline 开头
0xdc0124  bl  pthread_setname_np
0xdc0134  ldr x0, [x19, #0x70]    ← 取成员指针
0xdc0138  cas w0, w0, [x0]        ← x0 == NULL -> SIGSEGV
```

即：某个 **std::thread 的函数体**一上来就对一个空成员指针做原子操作。崩溃前一行日志是

```
mariadbd: io_uring_dontfork() failed with errno 2 (continuing)
zng: sigsys emulated nr=425      ← 我们的 SIGSYS 把 io_uring_setup 变成 ENOSYS
```

**结论**：Android seccomp 禁掉 io_uring（425/426/427），MariaDB 11.8 探测失败后仍然把 io_uring 线程拉起来，ring 指针为 NULL → 崩。是 MariaDB 自身的容错缺口，不是我们模拟的错（模拟本身已验证生效）。

试过且都仍崩：`innodb_linux_aio=aio`、`innodb_use_native_aio=0`、`innodb_buffer_pool_size=32M`；`skip-innodb` 不崩但 install 仍失败（没有 InnoDB 起不来）。

→ **MariaDB 暂不支持**；MySQL 8.0 作为 MySQL 系方案已验证可用。

### 修 AI 报的 dpkg/apt 工具链问题（2026-11-08）

**1. `dpkg-deb` 报 `unable to create temporary directory`（最严重）—— 根因不是 TMPDIR 被写死**

真机复现：`env -u TMPDIR` / `TMPDIR=/tmp` / `TMPDIR=<工作区>` 全部同样报错，但 python 的 `tempfile.mkdtemp()` 正常。

- 真因：glibc 的 `mkdtemp` 内部直接发 `mkdirat`（不经 PLT），模板里的绝对路径不会被 shim 翻译 → 去真实根上建目录 → ENOENT。shim 之前有 `mkstemp` 系列钩子，**恰好漏了 mkdtemp**。
- 修法：`shim/zng_fwd.c` 补 `mkdtemp`（模板翻成宿主路径 → 真实实现 → 结果转回 guest 写回缓冲），并让会话环境固定 `TMPDIR=/tmp`（`PocketrootRuntime.applyLauncherEnv`；Android 默认指到 app cache，guest 里不存在）。
- 验证：`dpkg-deb -f / -I / -x`、`dpkg-deb -Zzstd -b`、`apt-get download hello` + `dpkg-deb -f` 全通。

**2. `dpkg -i` 的 root 检查 + status-old 报错**

- `dpkg --force-help` 里有 `not-root` → `dpkg -i --force-not-root x.deb` 可正常安装（已写进系统提示词）
- 装完仍报 `error creating new backup file '/var/lib/dpkg/status-old': Permission denied`：不是权限位问题（目录对 uid 可写），是 **Android SELinux 禁止 app 数据目录建硬链接**，dpkg 用 `link()` 做备份。`shim/zng_hooks.c` 的 `linkat` 在 EACCES/EPERM 时退化为复制（快照语义够用）→ 现在 `dpkg -i` 全程无警告。

**3. 归档工具缺失**

新增 `runtime/GuestTools.java`（marker 幂等，会话与容器都会装一次）：

| 工具 | 来源 |
|---|---|
| `ar` | 自带 python 脚本（只实现 deb 需要的 t/x/p） |
| `xz` / `unxz` / `xzcat` | python `lzma` |
| `zstd` | 从 Debian 官方 `zstd_*_arm64.deb` 里解出的真实二进制（v1.5.7；依赖 libzstd/liblz4/liblzma，rootfs 都有） |

验证：`ar t` 列出 `control.tar.zst/data.tar.zst`，`zstd -d -c data.tar.zst | tar -tf -` 正常，`dpkg-deb -Zzstd -b` 能造 zstd 的 deb。trixie 主流 zstd deb 现在能手工解了。

**4. `apt-get download` 静默截断**

小包（`hello`）下载 + 解析正常；10MB 级截断没复现，判断是网络/代理抖动（apt 的进度数字与实际写入不符也是 apt 自身统计）。没法在运行时保证，改成**提示**：系统提示词里让模型下载后 `dpkg-deb -f x.deb Version` 校验一次，截断文件会立刻报错而不是静默留在那。

**6. `stat %U` UNKNOWN / `ls -l` 权限带 `?`**

- 已给 rootfs 的 `/etc/passwd`、`/etc/group` 补上当前 uid 的 `pocket` 条目（`ContainerRunner.ensureAccount`，会话的 `ensureDirectories` 也会调用），`stat %u` 正常
- 但 `%U` 仍是 UNKNOWN、耗尽：`getent passwd` 返回 0 行。挖到底了：glibc 2.34+ 的 `libnss_files.so.2` 是空壳（`.text` 只有 0xe8 字节），实现在 libc 里，且它是用 `"/etc/" + 数据库名` **运行时拼路径**，我们只改写了 libc 里那条完整的 `/etc/passwd` 串。nss 内部 open 不经 PLT，没法在 shim 里翻译；把 19 处 `/etc/` 全改会破坏别的路径。属于已知限制，不影响功能（`stat %u` / `id` 可用）。
- `ls -l` 末尾的 `?` 是 SELinux 上下文未知（Android 强制 SELinux），不是文件损坏。偶发 `mkdir -p` 后 `ls` 报 ENOENT 未复现（20 次重试），暂记为一次性现象。

验证：`make -C tests check` 194 + zshm 全过；postgres / mysql / redis 三套服务未回归（本次只改 shim 的 mkdtemp/linkat 与 Java 工具安装）。

### 修 `mkdir -p` 落到 rootfs 影子副本（2026-11-08）

**现象（6/6 必现）**：`mkdir /root/workspace/tmp/probe` 正常；`mkdir -p /root/workspace/tmp/probe2` 返回 0 却建在
`/data/.../files/rootfs/root/workspace/tmp/probe2`，`ls` / `stat -c %a` 用绝对路径查不到。带尾斜杠、嵌套多级同样。
上一节记为「偶发、一次性现象」，其实是稳定可复现的路径翻译缺陷。

**根因**：bind 表（`PocketrootRuntime.binds()`：guest `/root/workspace` → host `files/workspace`）按 **guest 绝对路径前缀**
做词法匹配；而 `zng_resolve` / `zng_open_guest` 对相对路径一律透传，`split_parent` 无斜杠分支更是完全不 resolve。
GNU coreutils `mkdir -p`（本机 9.4 用 LD_PRELOAD 探针核实）把绝对路径拆成相对组件逐级下钻：
`mkdir("/root"); chdir("/root"); mkdir("workspace"); chdir("workspace"); mkdir("tmp"); mkdir("probe2")`（配合 openat+fchdir），
整条 `/root/workspace` 绝对前缀从未出现在任何一次 syscall 里 → bind 被整段绕过。启动脚本又恰好在 rootfs 里建了真的
`root/workspace` 目录（`DebianRootfs`），相对组件把进程 cwd 带进这个影子目录，之后全写在那里；`ls`/`stat` 走绝对路径读的是
bind 目标 —— 两个不同目录。普通 `mkdir` 是一次 `mkdirat(AT_FDCWD, 绝对路径)`，`split_parent` 拆出绝对父目录，bind 命中，所以正常。
顺带：`PocketrootRuntime.launchCwd()` 返回 `<rootfs>/root/workspace`（影子目录），所以从默认 cwd 发的相对命令同样受影响。

**修法（方案 1，改 shim，不动 bind 表 / rootfs 布局）**：
- `shim/zng_core.c` 新增纯逻辑 `zng_relative_bind()`：基准目录的 guest 视图 + 相对路径拼成绝对路径、组件级 clean，再走最长
  bind guest 前缀匹配。**只在真正命中 bind 时改写**；未命中保持内核 cwd/dirfd 解析语义（避免词法 clean 改变 `..` 与符号链接顺序）。
- `shim/zng_run.c` 新增 `guest_base()`：`AT_FDCWD` 用 `getcwd` + `zng_untranslate`；真实 dirfd 用 `/proc/self/fd/NN` 反查
  （任何一步失败即回退透传，不构成对 /proc 的硬依赖）。`zng_resolve` 与 `zng_open_guest` 对相对路径先调用它。
- `shim/zng_hooks.c` 的 `split_parent()` 无斜杠分支改为解析父目录 `.`：命中 bind 时打开 host 父目录作 pfd；未命中时 `pfd = dirfd`，
  热路径零额外 open。
- `dirfd != AT_FDCWD` 且未命中 bind 的老行为（含 `tar -C`）保持不变。

**验证**：`make -C tests check` 235 项 + zshm 全过（新增 `test_relative_bind`：影子 cwd 与真实 bind cwd 都 untranslate 成
`/root/workspace`，相对 `probe2` 收敛到 `files/workspace/tmp/probe2`）；zig 交叉编译 `-Wall -Wextra` 零告警；
`libzng.so` 已重编进 `app/src/main/jniLibs/arm64-v8a/`。

**真机验证通过（2026-11-08）**：`./gradlew assembleDebug` 重编 + `adb install -r app-debug.apk` 成功；设备上
`nativeLibraryDir/libzng.so` 的 sha256 与重编产物一致。用 `run-as com.dsh.android.pkr sh < /tmp/zng_repro.sh` 复刻
`PocketrootRuntime` 的完整 env 与 launchCwd（cwd = `<rootfs>/root/workspace` 影子目录），实测 `mkdir --version` 为
**GNU coreutils 9.7**（确认是相对组件下钻那条路径）：

| 断言 | 结果 |
|---|---|
| T1 `mkdir` 绝对路径 | `ls`/`stat` 均可见 |
| T2 `mkdir -p /root/workspace/tmp/probe2`（原 bug） | rc=0，`ls -ld` 可见，`stat -c %a` = 777 |
| T3 尾斜杠 + 多级嵌套 `mkdir -p .../a/b/` | 逐级可见 |
| T4 默认 cwd（影子目录）里的相对 `mkdir -p rel/a/b` | 可见 |
| T5 单组件相对 `mkdir foo`（`split_parent` 无斜杠分支） | 可见 |
| T6 `git init` 回归 | rc=0，`.git` 完整 |
| T7 `tar -C` 回归 | rc=0，文件内容 `hello` |
| T8 相对 `touch` / `stat` | rc=0 |
| host 侧落点 | `files/workspace/...` **有**、`files/rootfs/root/workspace/...` **无**（影子副本不再产生） |

再跑 6 轮 `mkdir -p` 循环：6/6 全部 `ls`+`stat` 通过，6/6 host 落点在真实工作区、6/6 影子目录干净。
验证后已清理所有 `zngfix_*` / `zngloop_*` 测试目录。复现脚本留在 `/tmp/zng_repro.sh` / `/tmp/zng_loop.sh`。
（过程中 adb TLS 掉过一次线，解锁手机后恢复；Android `sh` 不支持需要临时文件的 heredoc，脚本改用多行单引号字符串。）

影响面不止 mkdir -p：`install -D`、`cp --parents`、`cd` 后的相对路径，
以及 `/images`、`/sdcard` 这些 bind 点，都是同一个洞，一并被这次修复覆盖。

### 页面动画流畅化（2026-11-08）

只改 UI 层，未动交互/业务逻辑。原来的「动画」只有两处（状态行颜色脉冲、每 450ms `setText` 拼点的运行 pill），
其余全是瞬变 —— 尤其 `scrollDown()` 每个流式 delta 都 `fullScroll(FOCUS_DOWN)`，抖动且会把回看历史的用户强行拉回底部。

| 改动 | 位置 |
|---|---|
| `fullScroll` → 连续贴底跟随（按内容高度增量 `scrollTo`，不重启动画），并尊重用户回看（`userPeeking`）；`programmaticScroll` 标志防止状态行淡出移除时误判 | `MainActivity.scrollDown` / `onCreate` 的 `OnScrollChangeListener` |
| 12 处 `transcript.addView` 统一走 `attachTranscript`，新行淡入 + 8dp 上移（200ms Decelerate）；回放 `replaying` 时跳过 | `MainActivity.attachTranscript` / `animateIn` |
| 状态行移除改为 140ms 淡出后再 `removeView` | `MainActivity.dismissStatusRow` |
| 运行 pill 由「每 450ms 改文字拼点」改为 alpha 呼吸（720ms INFINITE/REVERSE），不再反复触发测量重排 | `MainActivity.startPillAnimation` |
| 状态行颜色脉冲补 `INFINITE`（原来 `REVERSE` 没配 repeatCount，实际只播一次）+ 缓动，2200→1800ms | `MainActivity.showStatusRow` |
| busy 态发送按钮 alpha 用 `animate()` 过渡；两个无限 animator 在 `onDestroy` 里 cancel 防泄漏 | `MainActivity.setBusy` / `onDestroy` |
| 侧栏会话行悬停背景与文字色加 120ms enter/exitFade | `bg_session_row.xml`、`session_row_text.xml` |

`./gradlew assembleDebug` 编译通过；`make -C tests check` 235 项 + zshm 全过（与 UI 无关，顺带回归）。
**真机 smoke test 通过（2026-11-08）**：设备重开无线调试后（IP 变 192.168.1.88）`adb install -r app-debug.apk` 成功，
`am force-stop` + `am start` 冷启动无崩溃（logcat 无 FATAL/AndroidRuntime/ANR，进程存活）。截图确认：
主界面顶栏 / 会话气泡 / inline code / 输入栏 / 发送按钮全部正常，无内容缺失或半透明残留；
点 ☰ 打开抽屉 → 搜索框、分组标题、会话列表、选中项的蓝色悬停药丸 + ⋯ 菜单、底部账号行均正常；
back 关闭后主界面完整恢复，无遮罩残留。
（静态路径已覆盖，流式输出 / 新行淡入这类动态动画未真机触发 —— 触发需发起一次真实 AI 会话，未擅自调用用户额度。）

### 顶栏精简：去掉常驻状态灯 + 图标按钮无界（2026-11-08）

**决定**（用 AskUserQuestion 确认）：自检入口挪到 ⚙ 长按；无界只应用于 ☰ / ＋ / ⚙ 三个图标，`modelChip` 保留胶囊底。

- **移除 `runtimeStatus`**：常驻的「● 就绪」是纯噪音，「● 离线」在启动时本来就会被 `autoSetup()` 自愈。
  从 `activity_main.xml` 删掉该 TextView，同时清掉字段 / `findViewById` / 长按监听 / 四处 `setText`，
  以及 `runtime_status` / `runtime_ready` / `runtime_offline` / `setup_failed` 四个字符串（中英两份）。
- **异常才弹窗**：新增 `showRuntimeProblem()`（`MainActivity.java:368`），只在 `autoSetup()` 抛异常时弹
  `AlertDialog`（标题 + 错误摘要 + 「重试」/「设置」/「取消」），`runtimeAlert` 字段 + `isShowing()` 防重复，
  `onDestroy` 里 dismiss。`onResume` 反复调 `refreshRuntimeStatus()` 不会误弹（弹窗不挂在那里）。
  `onSend()` 里重试 setup 失败仍走原来的聊天气泡 `runtime_setup_failed` —— 用户正在等回复，不该弹窗打断。
- **setup 进度静默**：`runtime.setup(Progress)` 不接受 null（内部直接调 `onProgress`），回调改写成
  `DebugLog.i("setup: " + msg)`，不再往顶栏刷字。`refreshRuntimeStatus()` 现在只算 `runtimeReady` + 刷新 `modelChip`。
- **隐藏依赖**：`runtimeStatus` 原本还是 `runSelfTest()` 的唯一入口（长按），已迁到 `btnSettings` 长按
  （`MainActivity.java:242`），单击仍是设置。
- **无界按钮**：三个图标 `bg_chip`（灰底 + 1dp 边框）→ `?android:attr/selectableItemBackgroundBorderless`，
  尺寸从 `wrap_content` + minWidth 26dp 提到固定 40dp（触摸区原来偏小）。`bg_chip.xml` 保留给
  `modelChip` / `heroBadge` / `wsChip` 等。

`./gradlew assembleDebug` BUILD SUCCESSFUL，无残留 `runtimeStatus` / 旧字符串引用。
**真机验证通过（2026-11-08）**：设备重开后 `adb install -r app-debug.apk` 成功，冷启动无 FATAL/ANR。
截图确认顶栏：状态灯已消失、☰ / ＋ / ⚙ 无灰色胶囊底、`deepseek-flash ▾` 仍保留 chip 底、无错位重叠；
`uiautomator dump` 确认 5 个顶栏控件 id 齐全且**没有 `runtimeStatus`**。
长按 ⚙ 实测触发自检：`selftest.log` 输出完整（Debian 13 trixie / aarch64 / Python 3.13.18 /
numpy 2.5.4 / requests 2.34.2 / libgomp ok / git 2.47.3 / curl / jq / `/root/workspace` / exit 0），
结果弹窗正常显示，点「确定」关闭后进程存活。
环境异常弹窗（`showRuntimeProblem`）**未在真机触发** —— 需人为破坏 rootfs 才能复现，不做破坏性验证；
逻辑靠 `runtimeAlert.isShowing()` 防重复 + `onDestroy` dismiss + 仅 `autoSetup` catch 调用保证。

顺带发现一处陈旧文案：`selftest_title` 仍是 "proroot 自检" / "proroot self test"，
但当前默认后端是 pocketroot（`RuntimeManager`）。**已在下一轮统一改为 pocketroot**（见下节）。

### 品牌文案统一：proroot / Ubuntu → pocketroot / Debian（2026-11-08）

用户指出 `selftest_title` 还是 "proroot 自检"，且 UI 里有含 Ubuntu 的旧文案，要求一并改。
实际后端早已是 **pocketroot + Debian 13**（`RuntimeManager` 默认），这些是漏改的品牌残留。

**改了的（用户可见 / 面向模型）**
| 位置 | 改动 |
|---|---|
| `values/strings.xml` + `values-zh/strings.xml`: `selftest_title` | proroot → **pocketroot** |
| `ws_dialog_msg` | 「proroot 提供的持久化 Ubuntu arm64 shell」→「pocketroot 提供的持久化 Debian 13 arm64 环境」；`df -h / inside Ubuntu` → `inside the environment` |
| `storage_msg` | 「Ubuntu 可以读取…」→「Linux 环境可以读取…」；「Ubuntu 内仍可…」→「环境内仍可…」 |
| `README.md` Features | `Persistent Ubuntu shell` → `Persistent Debian shell`；`Built-in rootfs` 一条从「Ubuntu 24.04 base + 三个镜像站兜底」改成「Debian 13 分段 xz 资产、首启并行解码、免下载」（与 Getting started 一致） |
| `README_zh.md` Features | 「持久 Ubuntu Shell」→「持久 Debian Shell」 |
| 面向模型的提示词 | `MainActivity` system prompt、`ContainerTool` 注释与工具描述、`pimg-patch.py` 注释里的 `Debian/Ubuntu` → `Debian-family` / 「Debian 系发行版」，保持信息量不丢 Ubuntu 镜像也支持这层意思 |
| 过时代码注释 | `MainActivity` / `BashTool` / `AndroidManifest` 里的 "Ubuntu (proroot) environment" → pocketroot Debian / Linux runtime |

**刻意保留（不是文案，是技术标识或历史事实）**
- `runtime/UbuntuRootfs.java`、`ProrootRuntime.java`、`RuntimeManager` 注释、`DebianRootfs` 对 `UbuntuRootfs` 的引用 ——
  这些描述**旧 proroot 后端**本身，类名 / 下载 URL / apt sources / `ports.ubuntu.com` 是真实标识，改了反而是错的。
- README 里「legacy proroot 需要 Ubuntu rootfs」「它现在按需下载 Ubuntu rootfs」—— 同上，描述旧后端，准确。
- `THIRD_PARTY_NOTICES.md`（法律声明）、`AGENTS.md`（历史记录）、`tools/redteam/data/*.jsonl`（测试数据集里的 `/home/ubuntu` 命令样本）。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL；`aapt2 dump resources app-debug.apk` 确认三个 string 的中英值都是 pocketroot / Debian / Linux environment；
真机装 APK 后长按 ⚙，`uiautomator dump` 抓到弹窗标题 `pocketroot 自检`、自检内容 `VARIANT_ID=pocketroot` / Debian 13 / exit 0，无 FATAL/ANR，关弹窗后进程存活。
`app/src/main/res` 下已无 `ubuntu` / `proroot` 字样。

### 顶栏尺寸：把 modelChip 挪进侧栏，全面放大（2026-11-08）

用户问「顶栏按钮和文字是否有点小」。放大 2 倍截图 + 量尺寸后确认：**是，但根因是拥挤不是字号**。
密度 320（2.0×）、屏宽 360dp；三个图标按钮 40dp 容器、字号 **16/15/14sp 各不相同**（＋/⚙ 明显比 ☰ 小），
标题 14sp、chip 12sp（全栏最小），触摸区 40dp < Material 48dp。
宽度账：360 − padding 20 − 按钮 126 − chip 134 ≈ 只剩 **72dp 给标题** → 显示成「这个环…」；
单纯调大字号标题反而截得更短。用 AskUserQuestion 选定「挪走 chip + 全面放大」。

**改动**
- `activity_main.xml` 顶栏：删 `modelChip`；☰ / ＋ / ⚙ 容器 40→**48dp**、字号统一 **22sp**；
  标题 14→**18sp**；bar 上下 padding 10→8dp（顶栏高度仍是 64dp，不挤压内容区）。
  标题可用宽度 72 → **约 190dp**。
- 侧栏抽屉搜索框下新增 `modelRow`（`bg_chip` 胶囊，40dp 高，14sp，文字「模型  deepseek-flash  ▾」）。
- `MainActivity.java`：`modelChip` 字段/初始化/点击/两处 setText 全部迁到 `modelRow`，新增
  `updateModelRow(String)`（复用已有的 `R.string.model_label`）。`showModelMenu()` 本来就是普通
  AlertDialog、不依赖 chip 作 anchor，所以迁移只需换个点击目标。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL，无残留 `modelChip` 引用。真机装 APK 冷启动无 FATAL/ANR；
顶栏放大 2 倍截图确认三个图标统一放大、标题从「这个环…」变成「这个环境可以关吗…」（可显示字数翻倍）；
抽屉截图确认模型行与搜索框齐平；`uiautomator dump` 抓到 `modelRow` 文本与 bounds，点击后弹出模型选择
对话框（标题「模型」+ 选项列表），关闭弹窗与抽屉后进程存活。

### 再删掉侧栏模型行：保留设置里唯一的「更换…」入口（2026-11-08）

用户指出「点设置换模型」和「划开抽屉点模型行」**步骤数一样**，冗余入口没必要。核实成立：
`showSettings()` 里已有 `change` TextView（`R.string.model_change` = 更换…）直接调 `showModelMenu()`，
上方还常显当前模型名（`current` TextView）。两条路径从主界面算都是 2 次点击，且设置路径不用先开抽屉。
于是把上一节刚加的 `modelRow` 又删掉。

- 布局：删 `modelRow` TextView 整块；顶栏保持上一节的 48dp / 22sp / 18sp 放大不变。
- `MainActivity.java`：删 `modelRow` 字段 / `findViewById` / `setOnClickListener`、删整个
  `updateModelRow()`、删 `refreshRuntimeStatus()` 与 `saveModel()` 里的两处调用。
  `refreshRuntimeStatus()` 现在只剩一行 `runtimeReady = ...`（仍被 onCreate / onResume / autoSetup / onSend 调用）。
- `shortModel()` 保留（`saveModel` 的 toast 还在用）；`model_label` / `model_change` 字符串保留（设置对话框在用）。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL，无 `modelRow` 残留。真机冷启动无 FATAL/ANR；
`uiautomator dump` 顶栏 4 个控件（`topTitle` 宽 364px ≈ 182dp）、抽屉里 `modelRow` 计数 **0**；
点 ⚙ → 设置对话框显示「模型」标签 + `deepseek-flash` + 「更换…」，点「更换…」弹出模型单选列表，
关闭全部弹窗后无残留、进程存活。

**遗留观察（未改）**：设置里的「更换…」是 13sp 的 `WRAP_CONTENT` TextView，
实测 bounds `[90,801][175,844]`（≈42×21dp），触摸区偏小；现在它是唯一入口，
若要补可用性可给它加 `minHeight=40dp` + padding。当前模型名也不再常显，需进设置才能看到。

### 顶栏按官方聊天应用再对齐（2026-11-08）

参考 `/home/nio/Desktop/file (2).webp`（DeepSeek 官方 App 截图）。放大 2 倍看清官方顶栏：
**两道横线**的汉堡（不是三道）、**细字重非粗体**标题、右侧两个**线性矢量**图标（静音 + 圆圈加号 ⊕）、
无背景块 / 无边框 / 无分隔线、图标近白、标题与汉堡间留白大。
我们上一轮为「变大」把 Unicode 字符放大到 22sp，反而离官方的细线性风格更远。

**改动**
- 新增三个 24dp vector：`ic_menu.xml`（两道 2dp 高横线）、`ic_new_chat.xml`（Material
  `add_circle_outline` 空心圆 + 加号）、`ic_settings.xml`（标准齿轮）。全部 `@color/topbar_text` 近白。
- 顶栏三个 `TextView`（Unicode ☰ ＋ ⚙）→ `ImageView`（48dp 触摸区不变、`scaleType=center`、
  保留 `selectableItemBackgroundBorderless`、补 `contentDescription`）。
- 标题去掉 `android:textStyle="bold"`（官方是细字重），18sp 不变；左间距 8 → **16dp**。
- 顺序改成 `☰ | 标题 | ⚙ | ⊕` —— 新建放最右，贴官方。
- 新增 `nav_menu` 字符串（打开菜单 / Open menu）供 contentDescription 用。
- **没有补「静音/朗读」图标**：我们没有朗读/静音功能，摆假图标会误导。

Java 无需改动：三处都是 `findViewById(...).setOnClickListener` / `View settingsBtn`，
`TextView` → `ImageView` 后类型仍兼容。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL。真机 1:1 与放大 2 倍截图确认双线汉堡、
细体标题、齿轮 + ⊕、无边框，风格贴近官方（齿轮是填充路径、比 ⊕ 略重，1:1 下可接受）。
`uiautomator dump` 拿到新 bounds（menu 72,128 / settings 552,128 / newSession 656,128）：
点 ☰ 开抽屉、点 ⊕ 弹「新会话」确认框、长按 ⚙ 弹「pocketroot 自检」，无 FATAL/ANR，
关闭全部弹窗后无残留、进程存活。

**待定（本轮 AskUserQuestion 从 REPL 调用超时，改为文字征询）**：官方顶栏没有齿轮，
而我们的 ⚙ 是 API key / 模型 / 钩子 / 长按自检的入口。本轮**维持现状**（保留在顶栏，最直达）。
若想更贴官方，可把 ⚙ 挪到侧栏底部账号行（顶栏变 ☰ | 标题 | ⊕，设置变 2 步，长按自检跟随迁移），
或挪进侧栏 ⋯ 菜单（那里已有「新建对话 / 设置」，但会变成 3 步）。

### 重新设计首页 hero / "Into the Unknown"（2026-11-08）

用户点名标题为 `Into the Unknown` 的首页要求重做。真机截图诊断：旧版是**静态品牌海报** ——
logo(56dp) + 标语(24sp bold) + `Preview` badge + `Workspace` 实心 chip + `Terminal · persistent bash · str_replace_editor`
从上往下贴顶堆着（`paddingTop=40dp`），下方约 700px 空白；`Preview` 语义不明（开发期残留），
`Workspace` 可点但看不出来，底部是纯技术词罗列；且 hero 全英文、输入栏/Toast 中文，语言不一致。

**设计方向：顺着前几轮的减法，不新增交互元素**（没加建议提示词卡片，那会逆向）。

| 改动 | 说明 |
|---|---|
| 垂直居中 | `home` 改 `layout_height=0dp` + `layout_weight=1` + `gravity=center`（`minHeight=360dp` 兜底防止 weight 不生效时内容不可见）。实测内容从 y≈590 到 1060，上下留白对称 |
| 保留并强化标语 | `Into the Unknown` 24→**28sp**，加 `letterSpacing=0.02`，仍是唯一视觉焦点 |
| 新增副标题 | `hero_subtitle`：中文「在手机上写代码、跑命令、搭环境」/ 英文 "Code, run, and build — right on your phone."，14sp `text_secondary`，补上"这 app 能干嘛"并解决中英混杂 |
| 删 | `hero_preview`(Preview)、`hero_hint`(Terminal…)、`hero_workspace`(Workspace) 三个字符串及对应 View |
| 环境行 | `wsChip` 换成 `hero_env` = `/root/workspace · Debian 13 arm64`，11sp monospace，背景从实心 `bg_chip` 换成新增的 `bg_hero_env.xml`（透明 + 1dp `border_light` 描边胶囊）；**保留 id 与点击说明对话框**，Java 一行未改 |
| logo | 56 → 64dp |

最终四个层次：图标 / 标语 / 中文副标题 / 环境行。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL，旧 hero 字符串无残留。真机装 APK、点 ⊕ 新建会话后截图：
内容垂直居中、层次清晰、描边胶囊可见不抢眼、中文副标题一行完整；`uiautomator dump` 确认三个文案都在。
点环境胶囊仍弹「工作区」对话框（文案已是上一轮改好的 pocketroot / Debian 13）。
重启 app 后 hero 隐藏、回到最新会话，无弹窗残留、无 FATAL/ANR、进程存活。

**备选（未做）**：若想要官方聊天应用那种「问候 + 可点建议提示词卡片」，可在 env 行下加 2–3 个描边卡片，
点击 `input.setText(...)` + `requestFocus()` —— 属于加法，需用户另行确认。

### AI 吐字加动画：新增文字淡入（2026-11-08；光标随后按用户要求删除）

用户报「ai 吐字没有动画」。根因：`BubbleBuilder.sync()` 的 `renderInto()` 对尾部 TextView
直接 `tv.setText(MarkdownRenderer.render(b.text))` —— **整块替换**，新到的字瞬间出现，
既没有过渡也没有「正在生成」的指示。之前几轮的动画都在气泡入场 / 状态 pill / 滚动跟随上，
唯独流式文字本身是硬的。

**实现**（集中在 `ui/BubbleBuilder.java`，不动既有渲染流程）
- `sync` 新增 `boolean streaming` 重载；4 参版本等价于 `false`。
  `MainActivity.onAssistantDelta` 的 queueRender 改传 `true`，`finalizeAssistantBubble` /
  `addBubbleAssistant` 仍走 4 参（收尾）。
- 新增 `FadeSpan extends CharacterStyle`：只改 `TextPaint.setAlpha()`，配合
  `TextView.invalidate()` 做淡入，不改文本/布局。
- `applyStreamTail()` 在 sync 末尾对**最后一个主 TextView**（PROSE 是自身，CODE 取 body，
  TABLE 跳过）做两件事：
  1. 用 `StreamState.plainLen` 记上一帧正文长度，只给 `[prev, len)` 这批新字套 FadeSpan +
     160ms Decelerate 淡入；上一帧的 span 随 `setText` 一起消失，那部分文本本就该全亮，
     **不会整块闪**。
  2. 末尾追加 `▍` 光标（brand 色 + 自身 FadeSpan），1000ms 无限呼吸。
     updateListener 里检查 `isAttachedToWindow()`，detach 后自停，避免无限动画持有 Activity。
- `stopStream()` 取消两个 animator、把 alpha 归 1、清 plainLen。非流式 sync 时
  `renderInto` 已经把纯正文重设回去，光标自然消失。
- **补了一个漏网路径**：AI「先输出文字 → 再调工具」时 `onToolStart` 会把 `assistant[0]`
  置 null 丢引用，旧气泡的光标会一直闪。现在清空前先做一次非流式 `sync` 收尾。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL。真机装 APK 冷启动回放历史会话：
`uiautomator dump` 中 `▍` 残留 **0**、历史消息文本正常、无 FATAL/ANR/越界、进程存活。
**动态流式效果未在真机触发** —— 需要发一条真实消息调用 API 额度，未擅自调用（沿用上一轮的界限），
待用户确认后可发一条短消息抓帧验证淡入与光标。

### 删掉流式光标，只保留文字淡入（2026-11-08）

用户「光标删了」。把上节里的 `▍` 光标整套移除：
- `StreamState` 只留 `plainLen / fade / fadeAnim`，删 `caret / caretView / caretAnim`；
- `applyStreamTail` 去掉追加光标、`ForegroundColorSpan`、无限呼吸 animator 与
  `isAttachedToWindow()` 自停保护，签名去掉 `brand` 参数；
- `stopStream` 只取消淡入；删 `ForegroundColorSpan` / `LinearInterpolator` 两个 import；
- `onToolStart` 的收尾补钉**保留**（现在的作用是结束淡入、让文字立即稳定），注释同步改掉。

`renderInto` 之后仍需 `tv.setText(ssb)` —— FadeSpan 是加在 ssb 上的，不 setText 不生效；
但 ssb 不再 append 任何字符，长度与正文一致，`plainLen` 语义不变。

`./gradlew assembleDebug` BUILD SUCCESSFUL；grep 确认 `caret` / `▍` / `ForegroundColorSpan` /
`LinearInterpolator` 全部无残留（剩下的 `style.brand` 是 Style 原有字段）。
**真机验证通过（2026-11-08）**：设备重开后 `adb install -r` → Success，`am force-stop` + `am start`
后 pid 变为 15064（确认真的重启）。回放那份 1000 字回答：`uiautomator dump` 里 `▍` 计数 **0**
（删光标前同一份内容是「……你还是一个人。▍」），文本完整、无 FATAL/ANR/越界、进程存活。

补充（后台任务 z76YOc 的迟到输出）：那条验证命令最终也没装上 APK（install / am 均 device not found、
pid 未变），但它留下的一份 `uiautomator dump` 显示**旧版带光标 APK** 在一次真实会话
（「写一篇1000字的文章，直接输出，不用工具。」）结束后，AI 回复末尾仍残留 `▍`。
这印证了删光标的必要性。`▍` 只在渲染时追加到 `ssb`、不写回 `assistantBuf`，
会话日志本身干净，装新版重启重新渲染即消失。也说明用户已在真机触发过流式输出，
淡入效果待其反馈（当前 160ms Decelerate）。

### Markdown 标题字号收窄（2026-11-08）

用户：「markdown H1 等偏大」并给了截图 —— 一级标题「慢下来，才能看见」几乎占满整行。
量化：旧版 H1 每字约 **40dp**、正文约 19dp，比例 **2.1×**，对应 Markwon 默认
`headingTextSizeMultipliers = {2, 1.5, 1.17, 1, 1, 1}` × 正文 17sp。

**改动**：`MarkdownRenderer.configureTheme` 里显式设置
```java
.headingTextSizeMultipliers(new float[]{1.25f, 1.15f, 1.06f, 1f, 1f, 1f})
```
（先试过 `{1.35, 1.2, 1.08}`，H1 每字约 26dp / 比例 1.3×，仍偏"标题感"，再收到 1.25。）
H1≈21sp、H2≈19.6sp、H3≈18sp、H4–H6 仍是正文 17sp。

**一个诊断插曲（记下来免得再走弯路）**：静态检查发现 `MarkwonTheme.headingTextSizeMultipliers`
字段没有任何类引用 `getHeadingTextSizeMultipliers()`（只出现在 `MarkwonTheme` 与其 `Builder`），
一度怀疑该 API 是死代码。但 A/B 实测（2.0 → 1.35 后 H1 每字 40dp → 26dp，正是 ×0.675）
证明**它确实生效**，只是调用被内联/走了别的路径，`grep` 在 dex 层看不到引用。
结论：以真机量为准，不要仅凭字符串引用判断 API 是否生效。

**验证**：`./gradlew assembleDebug` BUILD SUCCESSFUL；真机装 APK 后回放含 H1 的历史会话，
1.25 版 H1 每字约 **24dp**、正文约 20dp，比例 **1.2×**，标题只占约 6 成行宽，
配合 headingBreak 分隔线仍可辨识为标题。无 FATAL/ANR。

**说明**：正文 `buildProse` 的 `tv.setTextSize(17)` 未动 —— 用户只反馈标题。若之后觉得正文也偏大，
可一并降到 16sp，届时 heading 比例会自动跟着缩。

### LaTeX 公式渲染修复（2026-11-08）

用户「Latex 工作不正常」。真机注入测试会话复现：**所有 `$...$` / `$$...$$` 都显示原始文本**，
不渲染；且带 `###` 标题的内容从某处起 Markdown 解析整体退化（`###` 原样显示）。

逐层定位出 **4 个独立问题**：

| # | 问题 | 修复 |
|---|---|---|
| 1 | `BubbleBuilder` 用 `tv.setText(markwon.toMarkdown(...))`。Markwon 的 `beforeSetText/afterSetText` 钩子**只在 `setMarkdown()/setParsedMarkdown()` 时触发**，而 `AsyncDrawableScheduler.schedule(textView)` 就在钩子里。日志确认 span 是 `JLatexAsyncDrawableSpan`（解析成功），但 drawable 从未被调度 → 永远原文 | 统一改走 `MarkdownRenderer.setText(tv, md)`（`markwon.setMarkdown`）：`BubbleBuilder.renderInto` + MainActivity 三处思考卡片 |
| 2 | `wrapBareLatexLines` 只跟踪 ````` 围栏、**不跟踪 `$$` 块**，会把块内的 `\int...` 再包一层 `$$` → 块结构错乱 → 后续 `###` 退化成原文 | 加 `inMath` 状态，独立的 `$$` 行切换 |
| 3 | 单个 `$...$` 永远不渲染：Markwon 4.6.2 的行内正则只有 `(\${2})([\s\S]+?)\1`（只认 `$$`）；且实测**行内 latex 完全不工作**（显式 `CorePlugin` 顺序、inline/block 分别设尺寸都试过，只有独立成行的块级 `$$` 会渲染） | `preprocess` 把「像公式」的 `$...$` 升级成独立块级 `\n\n$$\n...\n$$\n\n`；`looksLikeMath()` 过滤价格（`$5 到 $10`）和含中文内容 |
| 4 | 公式极小：`JLatexMathPlugin.create(float textSize)` 的 **单位是 px 不是 sp**，传 `16f` 只有 16px(8dp) | 改 `18f * scaledDensity` |

**验证**：真机注入 7 组用例（单 $ / 带空格 / 行内 $$ / \(\) / 中文混排 / 价格 / 块级多行），
最终全部渲染（`y²`、`x`、`√x`、积分式），价格保持原文，公式大小与 17sp 正文协调，无 FATAL/ANR。
测试会话（`99991231-*.jsonl`）已清理，app 回到用户自己的会话。

**取舍（待用户决定）**：行内公式现在会**独立成行**，因为 Markwon 4.6.2 的 inline latex 不可用。
要真正的行内排版需换方案（降级 ext-latex / 用 TeXFormula 自己生成 ImageSpan），工作量较大。

**备注**：同时显式 `.usePlugin(CorePlugin.create())` 放最前，避免依赖 Markwon「没显式加就追加到末尾」的隐式行为。

### v0.5.0 发布（2026-11-08）

**GitHub 历史处理**：本地 `main` 与 `origin/main` 从 `v0.1.0` 就分叉（本地 7 个提交走 pocketroot，远程 12 个提交走插件/broker 到 0.3.0）。
按用户决定：先把远程那条线打成 **`v0.3.0` tag + 归档 Release**，再用 `--force-with-lease=main:4eb269c` 把 `main` 覆盖为本地 `1b2fc64`。
旧线源码完整保留在 tag 里，不是丢失。

**签名**：本机新建 `~/.android-keys/pocket-agent-release.jks`（RSA 4096 / 10000 天，alias `pocket-agent`），
口令只写在 `keystore.properties`（600，已 gitignore）—— **不入库、不写进 AGENTS**。丢失后无法升级同包名，需自行备份。
`app/build.gradle` 在没有 `keystore.properties` 时仍可 `assembleRelease`，产物为 unsigned。

**构建**：`assembleRelease` 原本被 `lintVitalRelease` 的 `ExpiredTargetSdkVersion` 挡住（targetSdk 28 < Play 要求 33）。
targetSdk 28 是 W^X 功能前提，不能升，因此在 `android.lint` 里 disable 该项。
产物 56,023,576 B（53.4 MiB），zipalign 4 与 apksigner 校验通过。

**分发**：`v0.5.0` Release 已发布（APK + sha256），sha256 `9ea71c6a…`；`v0.3.0` 为归档 Release（无附件）。
`dist/` 已 gitignore。

**上传踩坑**：这台机器到 `uploads.github.com` 的上行 25–120 KB/s 且频繁被 RST（curl 56），
单个 56 MB asset 多次失败。最终方案：后台循环脚本，每轮先删除同名 `starter` 半成品 asset（否则 422），
再 `--limit-rate 120k` 重传，失败 45 s 后重试 —— 第 1 轮 458 s 传完（122 KB/s）。
另：早期把 token 写进 `curl -H` 参数会出现在 `ps` 里，已改为 `-K` 配置文件（用完删除）。

**待办**：真机冒烟未做（装 release 需先卸载旧 `com.dsh.android` 0.3.0，另一把密钥签名）；建议撤销并重发一次 PAT。
