# DSH Android — DeepSeek Harness Java 移植版

本机不装 git（UOS 精简源），项目以 tar.gz 归档到 `/media/deepin/_dde_data/dsh-android-*.tar.gz`。

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
