# UI 框架（P1 切片）

`com.dsh.android.ui.framework` —— 纯 Java、零新增依赖的声明式 UI 内核。
目标是把聊天内容区（消息、卡片、表格、代码块）从 `BubbleBuilder` 的手工 View 拼接
迁到「节点树 + 增量更新」上，让插件/主题能产出**描述**而不是 View，宿主始终掌握渲染权。

本文只描述已落地的内核：P1 四类 + 阶段 1–6（组件注册表、交互层、主题层、流式增量与窗口原语、主界面迁移、跨控件选择）。
插件 API、主题包加载、动作/状态总线见 `PLUGIN.md` / `PLUGIN_API.md`。

**本文件是视图 DSL 的唯一契约**：`PLUGIN_API.md` §6 的字段名、token、type 清单、限制都以这里为准。
插件/主题返回的节点树只允许使用第 2 节的 type 和第 3 节的 token；未知 type 静默丢弃并记 warning。
P2 控件（`badge` / `spacer` / `progress` / `bars` / `image` / `list` / `nav.list` /
`button` / `input` / `switch` / `checkbox` / `slider`）已实现，第 2 节是完整清单；
阶段 4 补了 `text.chunks` / `absolute` / `window` / `taskbar`；阶段 5 把聊天区的
表格 / 代码卡 / 工具卡迁成框架组件，并接通插件 `renderers` 覆盖链。插件插槽、页面与
应用内浮层同样注册了 `ChatComponents`，因此也能使用 `table` / `codeCard` / `toolCard`。

## 1. 四个类

| 类 | 职责 |
|---|---|
| `UiNode` | 不可变节点。`type` / `key` / `props` / `children`， Builder 构造 |
| `UiDiff` | 纯 Java 的子树对账：算出复用哪些旧节点、哪些新建、要不要重排。不碰 Android |
| `UiRenderer` | 把节点树 inflate 成原生 View，按 key 复用、只 patch 变化的属性 |
| `UiTheme` | 默认 token 表（`DefaultTokenResolver` 的基线）；换主题通过注入 `TokenResolver` 实现 |

## 2. 节点类型

| type | 字段 | 说明 |
|---|---|---|
| `column` | `children`, `gap`, `align` | 纵向容器 |
| `row` | `children`, `gap`, `align` | 横向容器；子节点 `grow: true` 时按 weight 分剩余宽度 |
| `hscroll` | `children`, `gap`, `align` | 横向滚动容器；内层 wrap_content 横排，超出视口可左右滑动，适合 chip 条 |
| `card` | `children`, `tone`, `pad`, `radius` | 圆角卡片容器（纵向） |
| `text` | `text`, `size`, `tone` | 普通文本 |
| `code` | `body` | 等宽代码块（自带底色与圆角） |
| `markdown` | `text` | Markdown（按空行分块，只重解析变化的块；走 `MarkdownRenderer.setText`）—— **不要放进流式热路径**，见第 6 节 |
| `divider` | — | 1dp 发丝线 |
| `badge` | `text`, `tone` | 小标签；`tone`: `ok`/`warn`/`info` |
| `spacer` | `space` | 固定间距，取 `pad`/`gap` 的同一组 token |
| `progress` | `value`, `tone`, `indeterminate`, `label` | 进度条；`value` 0–1，在 `row` 里占满剩余宽度 |
| `bars` | `values`, `max`, `height`, `tone` | 柱状图，最多 60 个值 |
| `image` | `src`, `height` | `asset:` 读插件资源，`workspace:` 读工作区，`https:` 需 `net.http` |
| `list` | `items`（兼容 `children`）, `keyField`, `gap`, `height` | 纵向列表，按 `keyField` 增量更新；≥30 行虚拟化（窗口 + overscan）。`height`（dp）给固定高度后列表自己滚，不再要求外层 ScrollView；否则按最近裁剪区/视口虚拟化 |
| `nav.list` | `group`, `variant` | 插件页面菜单；`variant`: `sidebar`/`tabs`/`grid` |
| `button` | `label`, `action`, `variant`, `params`, `fallback` | 按钮；事件/动作见 PLUGIN_API §6.2 |
| `input` | `value`, `placeholder`, `multiline` | 输入框；值在事件回调的 `ctx.value` 里 |
| `switch` | `checked` | 开关；切换即提交，触发 `change` |
| `checkbox` | `checked` | 复选；点击即提交，触发 `change` |
| `slider` | `value`, `min`, `max`, `step` | 拖动条；松手时触发一次 `change` |
| `text.chunks` | `text`, `size`, `tone` | 流式文本按段落打包成 ~1KB 块，只更新尾块（配合 `appendText`） |
| `absolute` | `children` | 绝对定位容器；子节点 `x`/`y`/`w`/`h`（dp），`z` 转 elevation |
| `window` | `title`, `w`, `h`, `resizable`, children | 由 `WindowLayer` 承载，不在普通渲染树里 |
| `taskbar` | `items: [{key,label,active}]`, `on` | 窗口任务栏，点击以 `value=key` 发 `tap` |
| `table` | `rows: [string[]]`, `quoted` | 聊天表格卡：按整格文本量列宽、超宽横向滚动、`quoted` 加左侧引用竖线 |
| `codeCard` | `lang`, `body` | 聊天代码卡：语言头 + 复制按钮 + 横向滚动的等宽正文 |
| `toolCard` | `glyph`, `title`, `summary`, `args` | 聊天工具卡：折叠一行 + 展开后的 IN/OUT；输出与状态胶囊由宿主按 id 更新 |

`table` / `codeCard` / `toolCard` 由 `ChatComponents.install(renderer)` 注册，是**宿主自用**的
聊天组件；插件 `renderers`、插槽、页面与应用内浮层的返回值同样可以使用这三个 type
（`UiNode.fromJson` 白名单已包含，且这些表面的 `UiFramework` 都注册了 `ChatComponents`）。
工具卡内部视图 id 固定为 `TOOL_PILL_ID` / `TOOL_OUT_ID` / `TOOL_BODY_ID` / `TOOL_GLYPH_ID`，
宿主用 `findViewById` 直接更新运行态，不重建节点树。

构造：

```java
UiNode tree = UiNode.column().key("root").prop("gap", "sm")
    .child(UiNode.card().key("card").prop("tone", "surface")
        .child(UiNode.text("标题").key("t").prop("size", "title"))
        .child(UiNode.code("$ make").key("code")))
    .build();

renderer.render(tree);   // 每帧重建树也可以，diff 会复用 View
```

**key** 是增量更新的身份，同父下必须唯一。没写 key 时退化成 `#位置:type` 按位置匹配，
插入节点会让后面的位置全部错开，所以**列表/流式内容一定要给 key**。

## 3. 样式 token（不接受裸颜色 / px）

| token | 取值 |
|---|---|
| `size` | `title` 17sp · `body` 15sp · `caption` 12sp · `mono`/`code` 13sp |
| `tone` | `primary` `secondary` `caption` `brand` `ok` `warn` `danger` |
| 卡片 `tone` | `surface` `accent` `warn` `danger` |
| `pad` / `gap` | `none` `xs`(4dp) `sm`(8dp) `md`(12dp) `lg`(16dp) |
| `radius` | `sm`(8dp) `md`(12dp) `lg`(16dp) |
| `align` | `start` `center` `end` |

颜色来自 `res/values/colors.xml` 的现有暗色 token；新增了 `accent_amber` / `accent_amber_dark`。
节点只写语义 token，查表由 `TokenResolver` 承担（见 3.1），节点树和渲染器都不认识具体颜色。

### 3.1 主题层（P2 / 阶段 3）

| 类 | 职责 |
|---|---|
| `TokenResolver` | 语义 token → 实际值（字号/颜色/间距/圆角/dp），渲染器持有的唯一查表入口 |
| `DefaultTokenResolver` | 默认实现，值来自 `UiTheme` 与现有 `colors.xml`，保证不装主题时视觉零变化 |
| `ThemePack` | 主题包 token 表（纯 Java）：`color.*` 支持 `{light,dark}`，非法值返回 null |
| `PackTokenResolver` | 主题包覆盖默认解析器：没提供的项逐项回退，数值夹到契约区间 |
| `ThemeValidator` | 对比度（WCAG 4.5 / 大字 3.0）与 40dp 最小触摸目标，只警告不拦启用 |
| `UiMotion` | 动效白名单 + 时长上限 400ms + 系统「移除动画」降级 |
| `UiAssets` | `asset:` / `asset://` 贴图：单文件 256 KB、累计 4 MB、路径越界拒绝、SVG 明确拒绝 |

入口只有两个：

```java
List<String> warnings = framework.applyTheme(ThemePack.fromJava(tokens), dark); // 空 = 通过
framework.resetTheme();                                                        // 恢复默认
```

`applyTheme` 只替换解析器并重设动效，**不重建节点树、不新建 View**；`UiRenderer.setTheme`
把所有 binding 的 props 缓存清空后重新 `apply`，下一次 `render` 仍走 diff。
`Builtins.retheme(view, env)` 按 View 记住上次的主题实例，create-only 的样式
（字号、颜色、代码块底色、divider、badge、spacer、markdown、nav.list、交互控件）在换主题后
也会重新取 token；容器 gap 通过 `markLayoutDirty` 重排，View 实例不变。
动效只作用于新建的 View（`Stats.created`），`reduceMotion` 时 `UiMotion.enabled()` 为假。
`image` 的 `asset:` 读 `files/plugins` 下的资源，`workspace:` 仍读工作区，两者都受体积闸门约束。

## 4. diff / patch 规则

1. `UiDiff.plan(old, desired)` 按 `(type, key)` 匹配旧子节点，一个旧节点最多复用一次。
   顺序/数量/key/类型变化 → `structural=true`。
2. `structural` 为真时父容器 `removeAllViews()` 再按新顺序 `addView` —— **View 实例仍然复用**，
   只是重新挂载；不会重新 inflate。
3. 属性只在变化时应用：`text` 变了才 `setText`，`tone`/`size` 变了才改颜色字号，
   `markdown` 文本没变就不重新解析。
4. `gap` 或子节点的 `grow` 变了也会触发重排（记录 `layoutKey`），但同样不新建 View。

硬限制（在 `UiRenderer` 里强制执行，不靠调用方自觉）：

- 单帧最多 **200 节点**、最多 **8 层**嵌套，超出部分丢弃并计入 `stats.skipped`
- 单个文本 **4096 字**上限，超出截断并记 `stats.warning`
- 只认第 2 节列出的 type，未知类型丢弃
- `render` / `patch` / `appendText` 必须在主线程调用（否则抛 `IllegalStateException`）

`UiRenderer.Stats` 每帧给出 `created / reused / skipped / nodes / lastMs / warning`。
**`created` 首帧之后应恒为 0**，这是 diff 是否生效最直接的指标。

### 4.1 流式增量（阶段 4 / P3）

- `text.chunks`：`UiText.splitChunks` 把增长中的文本按段落打包成约 **1KB** 的块，
  每块一个 TextView；只对变化的块 `setText`，块数超过 64 时把最早的块合并。
- `markdown`：`UiText.splitMarkdownBlocks` 按空行分块（``` 围栏内的空行不切），
  只有变化的块重新走 Markwon；换主题只改字号颜色，不重新解析。
- `UiRenderer.appendText(key, delta)` / `UiFramework.appendText`：拼到节点 `text` 后只 patch
  这个节点。真机 120 帧：**avg 0.77ms / max 4.19ms**（同样内容单 `text` 流式 34.4ms）。

**聊天区 prose 流式（阶段 5，`ChatBlockView`）**：静态消息先按块切分，尾块用
`text.chunks`；下一帧如果只是纯 prose 追加（delta 不含 ``` / 表格 / 引用标记），跳过切分，
直接 `appendText` 尾块。真机 60 帧：**avg 9.06ms / max 17.16ms**，59 帧走快路径，
`split 0.07ms`、`append 8.30ms`。静态块首帧约 **126–140ms**（表格量列宽 + 代码卡 + 长 prose）。

### 4.2 T3 窗口原语（阶段 4）

`WindowLayer` 是应用内的窗口层（不含系统级悬浮窗），宿主把 `view()` 盖在内容上：

```java
WindowLayer layer = new WindowLayer(activity);
root.addView(layer.view());
layer.show("deploy", UiNode.of(UiNode.WINDOW).key("deploy")
        .prop("title", "部署").prop("w", 280).prop("h", 320)
        .child(UiNode.text("服务已启动").key("body")).build());
```

| 能力 | 实现 |
|---|---|
| 窗口上限 | `MAX_WINDOWS = 3`，第 4 个 `show()` 返回 false |
| 拖动 / 缩放 | 标题栏拖动、右下角把手缩放；最小 160×120dp，边界夹在父容器内 |
| 置顶 | 点击窗口任意位置或 `bringToFront()`；z 序由父容器 child 顺序决定 |
| 最小化 / 关闭 | 标题栏 «—» / «×»；最小化后点标题栏还原 |
| 返回键 | 宿主先调 `onBackPressed()`：先最小化最上层，已最小化则关闭 |
| 几何持久化 | `SharedPreferences("window_geometry")`，键 `win.<key>`，格式 `x,y,w,h,minimized` |
| 冻结渲染 | 非活动窗口的 `update()` 只暂存，置顶时才真正渲染 |
| 任务栏 | `taskbar` 节点按 `items` 生成按钮，点击发 `tap`（`value=key`） |
| 绝对定位 | `absolute` 容器的子节点用 `x/y/w/h`（dp）叠放，`z` 转 elevation；几何变化在结构不变时也会更新 LayoutParams |
| 交互内容 | 每个窗口内容是一个独立 `UiFramework`（内置组件 + button/input/switch 等），事件/动作通过 `setEventHandler` / `setActionHandler` 交给宿主；点窗口内任意位置（含 button）都会置顶 |

窗口内部的树由独立的 `UiRenderer` 渲染，仍受 200 节点 / 8 层 / 4096 字限制。
非活动窗口的冻结只影响**内容更新**，标题、几何、最小化状态仍立即生效。

### 4.3 跨控件自由选择（`select`，阶段 6）

节点上的 `select` 属性声明选择范围，长按进入网页式的自由选择：

| 值 | 行为 |
|---|---|
| `local` | 只能在这个节点的子树里选 |
| `cross` | 和同一 hub 里其它 `cross` 范围连成一条可选流，可跨消息/工具卡连选 |
| `none` | 显式禁止（整个子树排除） |

- 宿主在 `UiRenderer.render` 过程中同步属性（父节点先于子节点），插件 renderers 返回的节点同样支持。
- 带 `on` / `action` 的交互节点（chip、按钮、可点卡片）默认不参与选择；要选文本可在该节点显式写 `select`。
  只有显式 `UiSelection.include(tv)` 的 TextView 参与复制和长按起点：`text` / `markdown` /
  `text.chunks` / 表格单元格 / 代码卡正文 / 工具卡 IN/OUT / 用户气泡都已标记。
  同一 owner 的相邻文本段（`text.chunks` 的块）直接拼接，其它段之间补换行。
- 长按先选中手指下的词，继续拖动扩展；工具条在长按成立时即弹出
  （「复制 / 全选 / 取消」），不依赖松手事件。
- 为了不把滑动/点击误判成长按：从文本到选择范围之间的所有容器都装同一个事件转发器
  （含表格/代码卡的 `HorizontalScrollView`），任何一层收到 UP/CANCEL 都能取消计时器；
  文本可能先 `include` 再挂到父容器（表格单元格），所以 attach 后还会补一次链路标记；
  移动超过约 2dp 也立即取消（比系统 touch slop 更严格）。
- 工具条是 Activity 内容区 `FrameLayout` 里的普通 View（不是 PopupWindow），位置确定、
  点击可达，也不会出现「看得见但点不到」。
- 高亮用 `BackgroundColorSpan` 直接画在 TextView 上，不额外盖 View，滚动时跟着文字走；
  TextView 被流式/工具输出更新时不会用旧文本回滚新内容（放弃高亮而不是覆盖）。
- 同一 renderer 重渲染（流式帧、主题切换）会清掉落在它上面的选择，避免偏移错位。
- hub：主界面把 `transcript` 注册为 hub；助手气泡与工具行是 `cross`，用户气泡是 `local`，
  因此跨选择只在助手内容之间连选。
- 选择期间宿主用 `UiSelection.isSelecting(view)` 判断，屏蔽主界面的「右滑打开侧栏」全局手势，
  否则拖选到右侧松手会误开抽屉。

## 5. JVM 单测

```bash
./gradlew :app:testDebugUnitTest
```

98 个用例，不依赖真机：

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `UiDiffTest` | 11 | 相同树、追加、删除、重排、改 key/type、重复 key |
| `UiNodeTest` | 8 | Builder 不可变快照、类型化取值、深层树 |
| `UiNodeJsonTest` | 10 | `fromJson` 白名单、节点/深度/文本限额 |
| `UiStateFormatTest` | 5 | `bind` 的 percent/number/duration/bytes 格式化 |
| `UiStateInjectorTest` | 8 | 状态注入、`fallback:hide`、不可变树 |
| `UiComponentRegistryTest` | 5 | 注册/覆盖/未知 type 回退 |
| `ThemePackTest` | 6 | `{light,dark}`、6/8 位颜色、非法值 |
| `PackTokenResolverTest` | 4 | 覆盖、逐项回退、数值夹取 |
| `ThemeValidatorTest` | 8 | 对比度、caption 阈值、状态色、缺失 token、40dp |
| `UiMotionTest` | 4 | 白名单、400ms 上限、reduceMotion |
| `ThemeSwitchTest` | 2 | 切主题不触碰节点树、nodes props 不可变 |
| `UiTextTest` | 8 | 段落打包、1KB 硬切、Markdown 围栏、块合并、代理对截断 |
| `WindowGeometryTest` | 4 | 几何解析/回退/下限夹取/with 辅助 |
| `RendererRuleTest` | 7 | fence 大小写、regex/prefix 只认纯文本、非法正则、链排序 |
| `SelectionModelTest` | 8 | 跨段偏移映射、同 owner 拼接、跨段换行、越界夹取 |


## 6. 真机实测（JDY-AN00 / Android 14，debug）

调试对照页 `UiDemoActivity`（只在 debug sourceSet / debug manifest，release 不含），
顶部六个 tab（组件 / 交互 / 主题 / 流式 / 窗口 / 聊天），每页自动执行并把统计印在屏幕上：

```
adb shell am start -n com.dsh.android.pkr/com.dsh.android.ui.framework.UiDemoActivity
```

| 页面 | 结果（JDY-AN00 / Android 14，debug） |
|---|---|
| 组件 | 注册 **22** 种；首帧建 17；复渲染 created=**0** reused=17；diff 100 帧 avg **8.72ms** / max 9.8ms；预算 320→**200** skipped=121；absolute 几何更新 ✓；未知 type 丢弃 ✓ |
| 交互 | 动作 3（`count=3`）；事件 4；状态推送 3；节流 0；bind 注入后计数显示 3 |
| 主题 | 展示树建 15；切换后 created=**0**、恢复后 created=**0**；标题 39.7→54.3→39.7px（resolver 26/17sp；Android 14 非线性字体缩放，px 比不等于 sp 比）；低对比主题警告 **4** 条、正常主题 0 条 |
| 流式 | 单 `text` 120 帧 avg **26.2ms** / max 46.4ms；`text.chunks` 120 帧 avg **0.774ms** / max 4.14ms，末尾 created=**0**；Markwon 全文 3441 字 avg 56.5ms/次 |
| 窗口 | 3 个 ✓ / 第 4 个拒绝；返回键 最小化→关闭→再最小化 全通过；冻结更新 true/true；几何 24,120,300,360 关闭重开一致；拖动与任务栏还原截图通过；窗口内 button 正常渲染，点被遮挡窗口的「执行」后升到最上层 ✓ |
| 聊天 | 静态块 ~140ms（prose + 表格 + 引用表 + 代码卡）；流式 60 帧 avg **9.06ms** / max 17.16ms（59 帧走 appendText 快路径，split 0.07ms、append 8.30ms/帧）；工具卡 running→完成、展开 IN/OUT、收起后 glyph 还原 ✓ |

主界面迁移（`MainActivity`）也用同一套组件验证过：会话回放里的历史工具卡正常显示「完成」，
`chat:demo` 注入的消息里表格、deploy 代码块（被 `deploy_card` 插件渲染成卡片）、工具卡
（状态胶囊显示「退出码 0」）全部正确，logcat 无 FATAL/ANR。

跨控件选择在真机上验证了：长按 prose 或**表格单元格**都会选词并弹出「复制 / 全选 / 取消」；
点击表格、慢速滑动表格不会出现工具条；从 prose 长按拖进表格可以连选，点「复制」成功写入
25 字；长按表格 `toolCard` 复制 8 字；拖选右滑松手不会打开侧栏。
选择流共 15 段 / 626 字（助手气泡 + 工具行的 `cross` 范围）。

`testDebugUnitTest` 98 个全过；`:app:assembleDebug` 与 `:app:compileReleaseJavaWithJavac` 通过；
六页切换 + 冷启动 logcat **无 FATAL / ANR**。

**两个必须记住的结论**

1. 流式那 26ms 不是 diff 的锅（树不变只要 4–9ms），而是**一个 TextView 承载不断增长的全文**
   时 `setText` + 整树重新 measure/layout 的成本。`text.chunks` 按 ~1KB 分块后降到 **0.77ms**；
   主界面的 prose 块迁移时直接复用它。
2. **Markdown 不能进流式热路径**：Markwon 每次都全文重解析，单次约 60ms，120 帧会拖到 26 秒。
   阶段 4 的 `markdown` 已改成**按空行分块、只重解析变化的块**；单块内部仍是全文重解析，
   所以真正的 prose 流式仍然走 `text.chunks`，Markdown 块只适合已定型的段落。

## 7. 还没做（按计划分阶段）

阶段 1（组件注册表）、阶段 2（事件/动作/状态绑定/patch）、阶段 3（主题层）、
阶段 4（流式增量 + T3 窗口原语）、阶段 5（主界面迁移 + 插件 renderers + 真机验收）、
阶段 6（`select` 跨控件自由选择）已完成。剩余：

- **系统级悬浮窗**：最小闭环已实现（`SYSTEM_ALERT_WINDOW` + 常驻通知 + 每插件 1 个/全局 2 个 +
  60% 尺寸上限，见 `PLUGIN_API.md` §7.11）；通知上的「关闭」动作与全局总开关仍未做
- **主题 `skins`**：`message.bubble` / `tool.card` / `code.card` / `table` 已接线；主题 `assets` /
  `strings` / `icon.*` 已接原生外壳（顶栏图标、输入框 hint、代码卡复制文案）
- **滚动回调**：`list` 已有窗口虚拟化，但 `scroll` 事件仍未回调（见 PLUGIN_API §6.2）
- **选择增强**：拖到视口边缘自动滚动、手柄调整起止点尚未做；当前用「全选」覆盖长距离选择

聊天区迁移状态：`BubbleBuilder.USE_FRAMEWORK=true` 时 `sync()` 直接委托
`ChatBlockView`（`BubbleBuilder.split` 复用旧切分）；旧的手工拼接路径保留在
`USE_FRAMEWORK=false` 分支，方便真机 A/B。工具卡从 `MainActivity.newToolRow` 改为
`ChatComponents.TOOL_CARD`，运行态（输出/状态胶囊/脉冲动画）仍走原有 TextView 更新。
插件 `renderers` 覆盖链已接线：`HookManifest` 接受 `apiVersion 1|2` 与
`contributes.renderers`，`HookRegistry.renderBlock` 按 fence/regex/prefix 匹配并回退，
`render:list` / `render:test <lang> <body>` 可在调试通道验证。

跨控件选择由 `UiSelection` 实现：`select: local | cross | none`，助手气泡与工具行是 `cross`、
用户气泡是 `local`；主界面把 `transcript` 注册为 hub。复制的文本只包含
`UiSelection.include(...)` 标记过的 TextView（BubbleBuilder 旧路径不在选择体系里）。

