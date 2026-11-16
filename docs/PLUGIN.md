# 插件开发指南（API v2）

> **状态**：v2 已落地（五个钩子、工具、渲染器、插槽、页面、导航、状态、动作、主题 token、
> 权限网关、`bridge.*`、插件调试通道）。实现契约以 `PLUGIN_API.md` 为准，本文保留
> 设计意图与示例。仍未实现：本地无线 adb 配对（`bridge.adb` 已走 Shizuku provider）。

Pocket Agent 的插件是**一个目录**，宿主加载后按 manifest 声明的扩展点调用你的 JS 函数。
插件只能用 JavaScript 写，只能通过 `bridge.*` 和外界打交道，界面用**声明式视图**（普通 JSON 对象）
描述、由宿主用原生控件渲染 —— 所以插件里没有 WebView，也不能直接操作 Android 控件。

一个插件可以同时是"工具插件 + 拦截插件 + 渲染插件"，不需要拆成多个包：

```
files/plugins/deploy_card/
  manifest.json     声明扩展点、权限、预算
  main.js           唯一逻辑入口
  README.md         可选，给用户看的说明
```

---

## 1. 五分钟上手

最小插件：给模型加一个 `sysinfo` 工具。

`manifest.json`

```json
{
  "id": "sysinfo",
  "name": "Sysinfo",
  "version": "1.0.0",
  "apiVersion": 2,
  "description": "读取设备型号、Android 版本和剩余空间",
  "entry": "main.js",
  "permissions": ["tool.register", "device.info"],
  "contributes": {
    "tools": [
      {
        "name": "sysinfo",
        "description": "返回手机型号、Android 版本和 /data 剩余空间",
        "inputSchema": { "type": "object", "properties": {}, "additionalProperties": false },
        "handler": "runSysinfo"
      }
    ]
  }
}
```

`main.js`

```js
function runSysinfo(ctx) {
  return "model=" + bridge.device.model()
       + " android=" + bridge.device.sdk()
       + " free=" + bridge.device.freeStorage();
}
```

放进 `files/plugins/sysinfo/`，到「设置 → 插件」里点启用（会先自检、再逐项确认权限）。
模型这一轮就能看到 `sysinfo` 工具了。

三个约定，记住就不会迷路：

1. **manifest 里的 `permissions` 只是"申请"**，真正生效要用户在插件页打开对应开关；没授权就调用，会抛 `E_PERM`。
2. **钩子函数名是固定的**（`onBeforeToolCall` 等），工具/渲染器/插槽的入口函数名在 manifest 里自己指定。
3. **每次调用都是全新的 JS 作用域**：上一轮的全局变量不会留到下一轮。要存状态用 `bridge.storage`。

---

## 2. 插件能挂在哪

| 扩展点 | 声明位置 | 用途 | 需要的权限 |
|---|---|---|---|
| 工具 | `contributes.tools` | 给模型加可调用的工具 | `tool.register` |
| 工具拦截 | `contributes.hooks: ["beforeToolCall"]` → `onBeforeToolCall` | 否决/改写工具调用 | `hook.tool` / `hook.tool.rewrite` |
| 工具结果 | `["afterToolResult"]` → `onAfterToolResult` | 改写工具输出 | `hook.tool.rewrite` |
| 用户消息 | `["userMessage"]` → `onUserMessage` | 改写或拦下用户输入 | `hook.message` |
| 上下文 | `["contextBuild"]` → `onContextBuild` | 在请求发出前改上下文 | `hook.context` |
| 助手输出 | `["assistantMessage"]` → `onAssistantMessage` | 输出后处理（脱敏、加脚注） | `hook.message` |
| 渲染 | `contributes.renderers` | 把特定格式的 AI 输出变成卡片 | `render.match` |
| 界面 | `contributes.ui` | 往固定插槽塞控件 | `ui.slot` |
| 状态 | `contributes.state` | 发布实时值给主题/其它插件绑定 | `state.provide` |
| 页面 | `contributes.pages` | 注册整页（仪表盘、面板） | `ui.page` |
| 主题 | `contributes.theme` | 换 token / 贴图 / 文案 / 组件皮肤 | 无（用户选择启用） |
| 动作 | `contributes.actions` | 把自己的能力暴露给主题和其它插件 | `action.provide` |

钩子的 manifest 值用**点名**（`beforeToolCall`），脚本函数用 `onXxx`（`onBeforeToolCall`），
对应关系见 `PLUGIN_API.md` §3.2；不要用带 `on` 的值去声明。

**依赖别的插件**：用 `requires` 声明，缺了会在插件页提示，不会自动安装：

```json
{ "requires": { "actions": ["svc.restart"], "states": ["svc_ctl.progress"] } }
```

**启用/禁用回调**：`onEnable` 只跑一次，期间建立的变量会被后续调用看到（适合读配置、建索引）；
`onDisable` 在禁用/重载前调用，用来释放。`onEnable` 失败则插件开不起来。

```js
var cache = null;
function onEnable(ctx) { cache = JSON.parse(bridge.fs.read("config.json")); }
function onDisable(ctx) { cache = null; }
```

---

## 3. 写一个工具

工具对模型可见，`description` 和 `inputSchema` 直接进系统提示 / function calling schema，所以要写得像给模型看的说明。

```json
{
  "name": "deploy",
  "description": "把 /root/workspace 下的构建产物发布到测试环境。只在用户明确要求发布时调用。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "service": { "type": "string", "description": "服务名，如 api / web" },
      "dryRun":  { "type": "boolean", "description": "只打印命令不执行", "default": true }
    },
    "required": ["service"],
    "additionalProperties": false
  },
  "handler": "deployTool"
}
```

```js
function deployTool(ctx) {
  var args = ctx.args;                       // 已按 inputSchema 校验过
  bridge.log("i", "deploy " + args.service); // 写进插件日志，设置页能看
  if (args.dryRun !== false) {
    return "dry-run: ./scripts/deploy.sh " + args.service;
  }
  var r = bridge.exec("./scripts/deploy.sh " + args.service, { timeoutMs: 120000 });
  return "exit=" + r.exitCode + "\n" + r.output;
}
```

- 返回值：字符串（原样作为工具结果）或对象（自动 JSON 序列化）。
- 抛异常：这次工具调用失败，错误文本会回给模型；模型可以重试或换方法。
- 默认超时 `limits.toolMs`（见 manifest，默认 30000），超时抛 `E_BUDGET`。

---

## 4. 写一个拦截器

```json
{
  "permissions": ["hook.tool"],
  "contributes": { "hooks": ["beforeToolCall"] }
}
```

```js
function onBeforeToolCall(ctx) {
  if (ctx.name !== "bash") return { action: "allow" };
  var cmd = String((ctx.args && ctx.args.command) || "");
  if (/\brm\s+(-[a-zA-Z]*r[a-zA-Z]*)\s+\/(\s|$)/.test(cmd)) {
    return { action: "veto", reason: "拒绝在根目录执行递归删除" };
  }
  return { action: "allow" };
}
```

链式语义：

- `beforeToolCall` 按 `priority` 升序执行，**遇到第一个 veto 就停**，这次工具不会执行。
- `rewrite` 只改写参数，改造后的参数交给下一个插件继续看；没有 `hook.tool.rewrite` 权限时改写会被忽略并记一条警告。
- 插件抛异常时按 manifest 的 `onError` 处理：`deny`（默认，fail-closed，拦下这次调用）或 `allow`（跳过这个插件）。
- 钩子改写后的**实际参数与实际结果**才写进会话转录，并标 `by: hook:<id>`；原始值只进审计日志。
  这样回放的是当时真正执行的东西。
- **钩子里不能调 `bridge.tool.invoke` / `bridge.action.invoke`**：钩子持有工具执行锁，重入会抛
  `E_REENTRANT`。要"跑完再让模型看到"，用动作 + `inject:true`。
- **钩子里也不能调阻塞式 bridge**（`permission.request` / `ui.confirm` / `exec` / `http` / `adb` / 
  会触发首次授权的 `fs.*`）→ `E_BLOCKED`。弹窗等 60s 会把整轮对话卡死。读配置用
  `bridge.config.get`。清单见 `PLUGIN_API.md` §9.2.1。

---

## 5. 渲染器：把 AI 输出变成卡片

场景：模型输出

````
```deploy
{"service":"api","env":"staging","status":"ok"}
```
````

你想渲染成一张带按钮的卡片。

```json
{
  "permissions": ["render.match"],
  "contributes": {
    "renderers": [
      { "id": "deploy", "match": { "fence": ["deploy"] }, "handler": "renderDeploy", "priority": 0 }
    ]
  }
}
```

```js
function renderDeploy(ctx) {
  var d = JSON.parse(ctx.body);
  return {
    type: "card",
    children: [
      { type: "row", align: "center", children: [
        { type: "text", text: d.service, size: "title" },
        { type: "badge", text: d.status, tone: d.status === "ok" ? "ok" : "warn" }
      ]},
      { type: "text", text: "env: " + d.env, size: "caption" },
      { type: "button", label: "重新部署", action: "redeploy", variant: "primary" }
    ]
  };
}

function redeploy(ctx) {
  var d = ctx.node.data || {};                      // 渲染时挂在节点上的数据
  var r = bridge.exec("./scripts/deploy.sh " + d.service, { timeoutMs: 120000 });
  return r.exitCode === 0 ? "已触发部署" : "部署失败：" + r.output;
}
```

- `match` 支持 `fence`（代码块语言）、`regex`（对块文本匹配）、`prefix`（以某段开头）。
- 同一块被多个插件匹配时按**渲染器自己的 `priority`** 升序、再按插件 id，第一个返回视图的生效
  （插件级 priority 不参与）。
- 返回 `null` 表示"我不接这个块"，交回默认 Markdown 渲染。
- 节点上可以带任意 `data`：不参与渲染，动作回调里用 `ctx.node.data` 取回。
- 渲染超时（默认 300ms）或抛异常 → 自动回退默认渲染，并把错误记到插件日志，不会打断对话。

---

## 6. 往界面加控件

插槽是宿主预留的固定位置，插件只能在插槽里放**声明式视图**，不能改布局、不能拿 View。

可用插槽：`message.header`、`message.footer`、`composer.above`、`settings.section`、`drawer.footer`、`home.hero`。

```json
{
  "permissions": ["ui.slot", "clipboard"],
  "contributes": {
    "ui": [{ "slot": "message.footer", "handler": "footer" }]
  }
}
```

```js
function footer(ctx) {
  return { type: "row", align: "end", children: [
    { type: "button", label: "复制原文", action: "copyRaw", variant: "ghost" }
  ]};
}

function copyRaw(ctx) {
  bridge.clipboard.write(ctx.message.text);
  return "已复制";
}
```

按钮点击会把 `action` 对应的函数调回来，`ctx` 里带 `message`、`session`、`node`。动作函数返回：

- 字符串 → 以 toast 显示
- 视图对象 → 就地替换这个插槽内容
- 其他/不返回 → 什么都不做

### 6.1 可用控件

**视图 DSL 以 `docs/UI_FRAMEWORK.md` 为唯一契约**（字段名、token、限制都和宿主 `UiRenderer`
一致）。P1 已实现 7 种：`column` / `row` / `card` / `text` / `code` / `markdown` / `divider`。
下面的 `button` / `badge` / `input` / `switch` / `checkbox` / `slider` / `progress` / `bars` /
`list` / `spacer` / `image` / `nav.list` 是 P2 目标形态，渲染器还没实现；`on` / `bind` / `id`
等字段同理。落到 P1 宿主时未知 `type` 会被忽略并记 warning。

| 维度 | 字段 | 取值 |
|---|---|---|
| 字号 | `size` | `title` `body` `caption` `mono` `code` |
| 文字颜色 | `tone` | `primary` `secondary` `caption` `brand` `ok` `warn` `danger` |
| 卡片底色 | `tone`（card 上） | `surface` `accent` `warn` `danger` |
| 内边距 / 间距 / 圆角 | `pad` / `gap` / `radius` | `none` `xs` `sm` `md` `lg` / `sm` `md` `lg` |
| 对齐 | `align` | `start` `center` `end` |

样式只能用 token，不接收像素和色值，所以插件换主题不会把界面撑坏。
子节点 `grow: true` 在 `row` / `column` 里按 weight 分剩余空间；`key` 是 diff 身份，
缺省按 `#位置:type` 生成（不是按 `id`）。单帧上限 200 节点 / 8 层 / 文本 4096 字。

### 6.2 事件

节点用 `on` 声明事件，不用写监听器对象：

```js
{
  type: "column", id: "job-42", data: { id: 42 },
  on: { tap: "openJob", appear: "onVisible" },
  children: [
    { type: "progress", value: 0.62, label: "62%" },
    { type: "slider", value: 3, min: 0, max: 8, on: { change: "setReplicas" } },
    { type: "switch", checked: true, on: { change: "toggleAuto" } },
    { type: "input", placeholder: "备注…", on: { change: "saveNote", submit: "submitNote" } }
  ]
}
```

- `change` 在 **input 提交/失焦**、**switch/checkbox 点击**、**slider 松手**时触发，不是每次输入都发。
- `scroll` 只给 `list`，节流到 100ms 以上，`ctx.value` 是 `{first, last, total}`；宿主尚未接线（list 已窗口虚拟化，滚动事件仍不回调）。
- 单次回调预算 50ms，超时丢弃；App 在后台不派发。
- **事件里不能调阻塞式 bridge**（`permission.request` / `ui.confirm` / `exec` / `http` / `adb` 等）
  → `E_BLOCKED`。`ui.confirm` 会阻塞 60s 等用户点弹窗，在 50ms 预算里必然超时；确认类交互
  改成"事件返回带确认按钮的卡片，按钮 `tap` 走动作回调"。清单见 `PLUGIN_API.md` §9.2.1。
- 想只改属性（进度、开关、选中态），用 `bridge.ui.patch(id, props)` 比返回整棵树便宜。
- 拖拽、缩放、长按拖动排序这类手势属于后续的窗口原语，不在事件表里。

**点击位置**：`tap` / `longPress` 会带 `ctx.pointer`（单位 dp）：

```js
// ctx.pointer = { x, y, localX, localY, w, h }
//   x/y       相对窗口左上角
//   localX/Y  相对收到事件的那个节点（子节点先处理，所以通常是最内层）
//   w/h       该节点当时的尺寸
{ type: "row", id: "seg", on: { tap: "pick" }, children: [
  { type: "text", text: "日", size: "body" },
  { type: "text", text: "周", size: "body" },
  { type: "text", text: "月", size: "body" }
]}

function pick(ctx) {
  var i = Math.min(2, Math.floor(ctx.pointer.localX / ctx.pointer.w * 3));
  bridge.ui.patch("seg", { /* 按 i 高亮 */ });
  return undefined;
}
```

需要主动查位置（比如把浮层贴在按钮下面）用 `bridge.ui.bounds(id)`，返回 `{x,y,w,h}` 或 `null`。
不提供连续移动事件，拖拽类交互走浮层的 `dragHandle` 或 T3 窗口原语。

### 6.3 显示实时数据：状态绑定

"上下文占用率"这类值不要自己算，也不要把主题和某个插件硬绑在一起。宿主和插件把值发布成
**具名状态**，控件按名字绑定；谁提供、谁显示互不依赖。

宿主内置（不装插件就能用）：`pocket.context.ratio`、`pocket.context.summary`、
`pocket.context.tokens`、`pocket.context.limit`、`pocket.context.compacting`、
`pocket.model.name`、`pocket.session.title`、`pocket.runtime.state`、`pocket.agent.busy` 等。

```js
{
  type: "row", align: "center", gap: "sm", fallback: "hide",
  children: [
    { type: "progress", bind: { "value": "pocket.context.ratio" } },
    { type: "text", size: "caption", bind: { "text": "pocket.context.summary" } }
  ]
}
```

插件也可以发布自己的状态（权限 `state.provide`）：

```json
{ "permissions": ["state.provide"],
  "contributes": { "state": [{ "id": "progress", "label": "部署进度", "type": "number" }] } }
```

```js
bridge.state.set("svc_ctl.progress", 0.4);  // 全名 = <插件id>.<id>；主题里 bind: { "value": "svc_ctl.progress" }
```

需要格式化时用对象形式，省掉一个只做格式化的插件：

```js
bind: {
  "value": { "state": "pocket.context.ratio", "format": "percent" },
  "text":  { "state": "pocket.context.tokens", "format": "number", "decimals": 1, "suffix": "k" }
}
```

支持 `percent` / `number`（配 `decimals`、`suffix`）/ `duration`（毫秒）/ `bytes`。

规则：绑定只读，写回用事件 → 动作 → `bridge.state.set`；状态变化只 patch 对应属性，
合并到下一帧（≤10 次/秒）；没有提供者时按 `fallback`（`hide` / `keep`）处理；
`pocket.*` 是宿主保留命名空间，插件的状态 `id` 不能含点，全名是 `<插件id>.<id>`。

### 6.4 浮层

应用内浮层（权限 `ui.overlay`）：显示在 App 内容之上，适合做常驻小面板。

```js
var id = bridge.overlay.show({
  id: "meter",
  view: { type: "card", children: [
    { type: "text", text: "上下文", size: "caption", dragHandle: true },
    { type: "progress", bind: { "value": "pocket.context.ratio" } },
    { type: "text", bind: { "text": "pocket.context.summary" }, size: "mono" }
  ]},
  x: 24, y: 120, draggable: true, avoidKeyboard: true
});
```

- 同时最多 3 个；默认带关闭钮；App 进后台自动隐藏，回前台由插件重新 `show`。
- 只能从 `dragHandle: true` 的节点拖动，避免和滚动/输入手势打架。
- 视图、事件、`bind`、`patch` 与普通节点一致。

系统级悬浮窗（权限 `overlay.system`）能把内容画在**其他应用**上面，属于极高风险能力：
需要用户在系统设置里单独授权、必须有常驻通知、单插件最多 1 个、最大占屏 60%、不能模拟点击。
默认关闭，插件页会明确警告，宿主保留全局关闭开关。建议只在确有需求时启用。

### 6.5 环境与状态速查

三层数据别混用：`ctx` 是这次回调发生了什么，`pocket.*` 是可绑定的持续状态，`bridge.*` 是主动查询。

```js
// 环境（dp，已换算）
var m = bridge.display.metrics();      // {width,height,density,orientation,dark,fontScale,reduceMotion,insets}
if (m.reduceMotion) { /* 不要自己加动画 */ }

// 能力降级
var cap = bridge.capabilities();       // {exec,net,clipboard,notify,adb:"shizuku"|"adb"|"none"}
if (cap.adb !== "none") { /* 显示特权按钮 */ }

// 主题色（节点外使用，比如生成 SVG）
var brand = bridge.theme.get("color.brand");

// 时间：沙箱没有 Date，必须走这里（需 env.time）
var now = bridge.time.now();
var stamp = bridge.time.format(now, "yyyy-MM-dd HH:mm");

// 主动查尺寸 / 位置
bridge.ui.bounds("seg");               // -> {x,y,w,h}
```

`pocket.*` 的完整清单（显示/应用/主题/导航/模型/会话/上下文/智能体/运行时/时间）见
`PLUGIN_API.md` 第 13 节；常用的有 `pocket.context.ratio`、`pocket.display.dark`、
`pocket.nav.page`、`pocket.time.text`、`pocket.agent.busy`。

`pocket.display.*`（尺寸/方向/深浅色/字体缩放/reduceMotion/insets）**不需要申请权限**，
主题和插件可以直接拿来做自适应；其它状态需要 `state.read`，时间另需 `env.time`。

---

## 7. 页面、菜单与仪表盘

插件可以注册整页，不需要 App 发版。

```json
{
  "permissions": ["ui.page", "state.read"],
  "contributes": {
    "pages": [
      { "id": "runtime", "title": "运行状态", "icon": "asset://chart.svg",
        "handler": "renderRuntime", "refreshMs": 2000 }
    ],
    "nav": [ { "page": "runtime", "label": "运行状态", "group": "工具", "order": 10 } ]
  }
}
```

```js
function renderRuntime(ctx) {
  return { type: "column", gap: "md", children: [
    { type: "row", align: "center", children: [
      { type: "text", text: "上下文", size: "caption" },
      { type: "text", bind: { "text": "pocket.context.summary" }, size: "mono" }
    ]},
    { type: "progress", bind: { "value": "pocket.context.ratio" } },
    { type: "text", text: "最近 12 轮 token", size: "caption" },
    { type: "bars", values: [12,18,9,22,30,26,18,14,20,28,24,16], height: 80 },
    { type: "button", label: "刷新", action: "refresh", variant: "ghost" }
  ]};
}
```

- 注册后自动出现在默认外壳的「插件页面」分组里；主题外壳可以用 `nav` 排序分组，
  再用 `{ type: "nav.list", group: "工具" }` 把菜单放到任意位置。
- `refreshMs` 是主动重取（比如 `bridge.http` 拉监控接口），和状态绑定可以叠加；
  离开页面自动停。
- 页面有 `appear` / `disappear` 事件，适合首次加载。
- 渲染失败只影响这一页：显示错误卡片 + 重试，不拖垮其它界面。

---

## 8. 主题

主题是**纯数据**插件（不跑 JS），换 token、贴图、文案和内置组件皮肤；一次只能启用一个。

```json
{
  "id": "midnight",
  "contributes": {
    "theme": {
      "tokens": {
        "color.bg":      { "light": "#FFFFFF", "dark": "#0B0C0E" },
        "color.brand":   { "light": "#2F6FEB", "dark": "#7C9CFF" },
        "radius.card": 14, "space.gutter": 16, "font.body": 17,
        "motion.enter": "fade-rise", "motion.duration": 180
      },
      "assets":  { "logo": "assets/logo.webp", "icon.send": "assets/send.svg" },
      "strings": { "send": "出发" }
    }
  }
}
```

- token 分类：`color.*`（支持 `{light,dark}`）、`radius.*`、`space.*`、`font.*`、`motion.*`、`icon.*`。
- 缺失/非法 token 回退默认，`plugin:lint` 会提示；改动即时生效，设置里永远能「恢复默认主题」。
- 主题**不换布局结构**，只换观感。要换布局（窗口式、任意重构）属于 T3 外壳能力，仍由宿主实现原语。
- 主题可以同时带页面和动作：页面用 `contributes.pages`，按钮用 `action:*` 调别人提供的能力。
- `contributes.theme.skins` 能替换消息气泡/工具卡的外观，**必须声明 `render.match`**；
  应用时会明确提示"此主题会替换消息与卡片渲染"（防止伪装成系统提示），并按内容指纹记住确认。
  主题代码变化后，启动恢复会先停用 skins，等用户重新确认。可用占位符：
  `{{message.text}}` `{{message.role}}` `{{message.time}}` `{{session.title}}`
  `{{tool.name}}` `{{tool.args}}` `{{tool.result}}` `{{tool.exitCode}}` `{{theme.name}}`。

---

## 9. 动作总线

主题画的按钮，功能由别的插件提供，双方只认动作名。

提供方：

```json
{
  "permissions": ["action.provide", "exec.guest"],
  "contributes": {
    "actions": [
      { "id": "svc.restart", "label": "重启服务",
        "params": { "type": "object", "properties": { "service": { "type": "string" } },
                    "required": ["service"] },
        "confirm": "确定重启 {{service}} 吗？" }
    ]
  }
}
```

```js
function onAction(ctx) {
  var r = bridge.exec("systemctl restart " + ctx.params.service);
  return r.exitCode === 0 ? "已重启" : r.output;
}
```

使用方（主题或任何插件）：

```js
{ type: "button", label: "重启", action: "action:svc.restart",
  params: { service: "api" }, fallback: "hide" }
```

- 没有提供者时按 `fallback` 隐藏/置灰；多个提供者按**动作声明自己的 `priority`** 取一个，冲突在插件页可见。
- 长任务可以报进度，也可以被取消：

```js
function onAction(ctx) {
  ctx.progress(0.3, "拉取镜像…");
  bridge.exec("docker pull api:latest");
  ctx.progress(0.8, "重启服务…");
  return bridge.exec("systemctl restart api").output;
}
```

  用户点 pending 按钮即取消，正在跑的 `bridge.exec` 会被杀并抛 `E_CANCELLED`；动作嵌套深度上限 3。
- 调用在后台线程跑（默认 5s 超时，可声明到 60s），按钮期间显示 pending，不卡界面。
- 需要确认的动作在 manifest 里写 `confirm`，宿主弹确认框后再调。
- JS 里也能调：`bridge.action.invoke("svc.restart", {...})`，需要 `action.bind`。
- 动作返回字符串会作为 toast；要更新界面请用 `state` / `bridge.ui.patch`。

### 9.1 快捷按钮：动作里调用 AI 的工具

插件可以直接跑宿主的工具（`bash`、`read`、`grep`、`web_search`…），做成一排快捷按钮：

```json
{
  "permissions": ["ui.slot", "tool.invoke", "hook.context"],
  "toolAccess": { "allow": ["bash", "read"] },
  "contributes": { "ui": [{ "slot": "composer.above", "handler": "quickBar" }] }
}
```

```js
function quickBar(ctx) {
  return { type: "row", gap: "sm", children: [
    { type: "button", label: "看状态", action: "gitStatus", variant: "ghost" },
    { type: "button", label: "跑测试", action: "runTests", variant: "ghost" }
  ]};
}

function gitStatus(ctx) {
  var r = bridge.tool.invoke("bash", { command: "git status --short" },
                             { show: true, timeoutMs: 15000 });
  return r.ok ? ("exit " + r.exitCode) : r.error;
}

function runTests(ctx) {
  // show:true 会在对话流里插一张工具卡，用户看得见是谁跑的什么命令
  var r = bridge.tool.invoke("bash", { command: "make test" },
                             { show: true, timeoutMs: 120000, inject: true });
  return r.ok ? "测试结束" : r.error;
}
```

规则：

- 必须在 `toolAccess.allow` 里列工具名，没列的调用直接 `E_PERM`；`tool.invoke` 本身是高风险权限。
- 危险工具（`bash` / `write` / `edit` / `container`）默认每次弹确认，展示工具名和参数摘要。
- `tool.invoke("bash")` 与 `bridge.exec` 共用 `CommandGuard`；`confirm:false` / 信任插件
  不能跳过危险的命令确认。
- 与 agent 自己的工具调用**串行**，不会同时占同一个持久 shell；agent 忙时默认排队（`wait:false` 立即返回 `E_BUSY`）。
- `show: true` 把这次调用渲染成工具卡；`inject: true` 把结果写进下一轮上下文（需 `hook.context`），
  适合"跑完测试让模型接着分析"。注入队列**按会话隔离**：切会话不会带走上一段的备注。
- 每次调用都有审计日志（插件、工具、参数摘要、耗时、退出码），插件页可查。

---

## 10. 权限与网关

### 10.1 权限表

`permissions` 里有**两层**，别混用（详见 `PLUGIN_API.md` 第 2 节）：

- **capability**：插件能挂哪些扩展点、读哪些宿主数据。插件页逐项开关，关掉静默不生效。
- **grant**：能访问哪些外部资源。插件页只是声明，实际由权限网关**运行时按范围弹窗**授权。

| 权限 | 层 | 能力 | 默认 |
|---|---|---|---|
| `tool.register` | capability | 注册工具 | 关 |
| `hook.tool` | capability | 否决工具调用 | 关 |
| `hook.tool.rewrite` | capability | 改写参数 / 替换结果 | 关 |
| `hook.message` | capability | 改写用户消息或助手输出 | 关 |
| `hook.context` | capability | 改写请求上下文 | 关 |
| `render.match` | capability | 接管块渲染 | 关 |
| `ui.slot` | capability | 插槽控件、toast、confirm | 关 |
| `device.info` | capability | 只读设备信息 | 关 |
| `env.time` | grant | 读取系统时间与时区 | 关 |
| `fs.workspace` | grant | 读写 `/root/workspace` | 关 |
| `fs.shared` | grant | 读写手机共享存储（`/sdcard`） | 关（危险） |
| `exec.guest` | grant | 在 Debian 环境执行命令 | 关（危险） |
| `net.http` | grant | HTTP 请求（限 `net.allow` 域名） | 关（危险） |
| `secret.get` | capability | 插件私有密钥 | 关 |
| `clipboard` | grant | 读写剪贴板 | 关 |
| `notify` | grant | 系统通知 | 关 |
| `bridge.adb` | grant | adb / Shizuku 桥 | 关（危险） |
| `state.read` | capability | 绑定/读取 `pocket.*` 等只读状态 | 关 |
| `state.provide` | capability | 发布自己的状态给主题绑定 | 关 |
| `ui.page` | capability | 注册页面、主题导航、`bridge.nav.*` | 关 |
| `action.provide` | capability | 暴露动作给主题/其它插件调用 | 关 |
| `action.bind` | capability | 调用 `action:*` 与 `bridge.action.invoke` | 关 |
| `tool.invoke` | grant | 调用内置/插件工具（范围受 `toolAccess.allow` 限制） | 关（危险） |
| `ui.overlay` | capability | 应用内浮层 | 关 |
| `overlay.system` | grant | 系统级悬浮窗（其他应用上层） | 关（极高风险） |

capability 不走网关（`request("hook.tool")` 返回 `unsupported`）；grant 才走 §10.2。
`pocket.display.*` 免 `state.read`。

`net.allow` 声明允许访问的域名（只允许 https，端口默认 443）：

```json
{ "permissions": ["net.http"], "net": { "allow": ["api.example.com"] } }
```

### 10.2 权限网关：网络、存储、执行都从这里过

manifest 里的 `permissions` 只是**声明意图**；真正生效靠运行时授权。第一次用到时会弹窗，
用户可以选"仅本次 / 始终允许 / 拒绝"；「仅本次」只覆盖紧接着的一次调用，
之后在插件页可随时收回。

```js
// 网络：范围是 net.allow 里声明的域名
if (bridge.permission.status("net.http") === "granted" || 
    bridge.permission.request("net.http", { reason: "同步部署状态" })) {
  var r = bridge.http.request({ method: "GET", url: "https://api.example.com/health" });
}

// 工作区文件
bridge.fs.write("notes/today.md", "hello");        // 首次自动询问 fs.workspace

// 执行命令（危险能力，弹窗会明确提示）
bridge.exec("make test");                          // 首次自动询问 exec.guest

// Android 运行时权限
var ok = bridge.permission.request("android.permission.CAMERA", { reason: "扫描二维码" });
```

- **自动代理**：`bridge.http` / `bridge.fs` / `bridge.exec` / `bridge.adb` / `bridge.tool.invoke`
  首次调用会自动走网关；不想弹窗就先用 `status()` 判断，或用 `{ auto: false }` 让调用直接失败。
- **按范围授权**：`net.http` 只放行 `net.allow` 里的域名，访问新域名会重新询问；
  `fs.workspace` 只覆盖工作区，`/sdcard` 要另一个 `fs.shared`（且系统需授予"所有文件访问"）。
- `describe(cap)` 返回弹窗里会展示的文案、范围与风险等级，可用于先做界面提示。
- manifest 没声明的能力，`request` 返回 `unsupported`，不会弹窗。
- 授权/拒绝都写审计日志；插件页能看到每项能力的范围、状态和最近使用时间。
- **授权绑定插件身份**：宿主对插件目录内容算哈希并写进每条 grant；目录一变（含 `net.allow`、
  `toolAccess.allow` 这些范围声明），所有"始终允许"作废、重新询问。挑同 id 覆盖目录不能继承
  旧插件的 `exec.guest` 授权。卸载不清记录，插件页可手动「清除授权」。
- `bridge.exec` 与 `bridge.tool.invoke("bash")` 走同一条 CommandGuard / 执行锁 / 审计管线；
  工具钩子只管模型发起的调用，拦不到插件自己的 `bridge.*`（详见 `PLUGIN_API.md` §7.2）。
- **时间也在这里**：沙箱没有 `Date`，插件要取时间必须申请 `env.time`，用 `bridge.time.now()`；
  时区（`bridge.time.zone()`）同样受这一项控制，因为它会暴露地区信息。

### 10.3 adb / Shizuku 桥（`bridge.adb`）

需要"应用权限之外"的系统操作时用这个桥：宿主优先走 Shizuku，没有 Shizuku 时退回本机 adb
（设备已开启无线调试并配对）。两者都不可用时 `available()` 返回 `false`，调用抛 `E_BRIDGE`。

```js
if (!bridge.adb.available()) {
  return "需要 Shizuku 或已配对的无线调试";
}
var p = bridge.adb.provider();                       // "shizuku" | "adb" | "none"
var r = bridge.adb.shell("settings get global adb_enabled");
return p + " -> exit=" + r.exitCode + " out=" + r.output;
```

- 命令由宿主执行，插件只拿 `{exitCode, output}`；输出上限 64 KB，超时默认 15s（`timeoutMs` 可调，上限 60s）。
- `bridge.adb.exec(program, args)` 不经过 shell，参数不会被二次解析，优先用它。
- 首次启用 `bridge.adb` 权限时，宿主会要求用户完成一次授权（Shizuku 授权 / adb 配对），并在插件页显示当前 provider。
- `adb.shell` / `adb.exec` 默认逐条确认完整命令；只有插件页显式「信任此插件」后才跳过逐条确认。
- 这个权限标记为"危险"：默认关闭，打开时有明确提示"插件将能以 shell 身份操作系统"。

---

## 11. 调试

宿主内置四条调试通道（设置页的「插件 → 调试」也可以点）：

```
plugin:list                 列出插件、版本、启用状态、错误
plugin:lint <id>            校验 manifest、函数是否存在、权限是否匹配
plugin:test <id> <hook> [json]   用一段 ctx 跑某个钩子，打印返回值和耗时
plugin:reload               重新扫描目录，热加载改过的脚本
```

插件里写 `bridge.log("w", "...")` 会进 `plugin:<id>` 的日志环（保留最近 200 条），设置页能看。
脚本语法错误、超预算、权限不足都会在插件那一行红字显示，而且**启用前自检不通过就开不起来**。

---

## 12. 限制与安全

- 沙箱：无 Java 反射、无文件系统直接访问、无网络直连；一切外部能力都走 `bridge.*` 并按权限放行。
- 预算：每次调用默认 `budgetMs=300` / `budgetSteps=200000`（工具 handler 用 `limits.toolMs`）。
  超时抛 `E_BUDGET`，钩子按 fail-closed 处理。
- 视图：单次返回最多 200 个节点（`list.items` 单独计数，最多 1000 项 / 4000 节点）、
  嵌套 8 层、单个文本 4000 字，超出截断并记警告。`list` ≥30 行会自动窗口虚拟化，
  只挂视口附近的行；必须放在 ScrollView 里。
- 上下文钩子不能凭空造 `tool` 消息，不许打乱 `tool_calls` 与 `tool` 的配对，总长度不能比原来多 20% 以上。
- 插件在启用期间有一个常驻作用域（`onEnable` 里建），但**不能**据此持有界面对象；跨会话状态用
  `bridge.storage`（JSON，单插件上限 256 KB）。
- 插件不能读取别的插件的 `storage` / `secret` / `config`。
- 看门狗：连续 5 次运行时失败（钩子 / 工具 / renderer / 页面 / 动作）会自动禁用并红字提示，
  成功一次即清零；预算超时也算一次失败（引擎在 1× 预算处硬中断）。权限/预算类拒绝
  （`E_PERM` / `E_BLOCKED` / `E_BUDGET`，比如用户点了「拒绝」）只记日志，不计入自动禁用。
- **没有硬内存上限**：插件失控分配仍可能把 App 撑到 OOM。硬限额要靠自编 QuickJS native 或进程外执行，
  列为 P2；完整清单见 `PLUGIN_API.md` 第 14 节。

---

## 13. 从 v1 迁移

v1（当前线上）与 v2 的差异：

| v1 | v2 |
|---|---|
| `capabilities: ["veto"]` | `permissions: ["hook.tool"]` |
| `capabilities: ["rewrite"]` | `permissions: ["hook.tool.rewrite"]` |
| `capabilities: ["replace"]` | `permissions: ["hook.tool.rewrite"]` |
| `hooks: ["beforeToolCall"]`（顶层） | `contributes: { "hooks": ["beforeToolCall"] }` |
| 只有两个钩子 | 五个钩子 + 工具 + 渲染 + 界面 |
| 没有 bridge | `bridge.*` 按权限开放 |
| 每插件只有开/关 | 开/关 + 逐项权限 + 日志 + 删除 |
| 没有依赖声明 | `requires`（缺依赖时插件页提示） |
| 每次调用全新作用域 | `onEnable` 建立启用期常驻作用域 |

函数名不变（`onBeforeToolCall` / `onAfterToolResult`），所以只做 manifest 迁移的插件不用改逻辑。
`apiVersion: 1` 的插件在 v2 宿主里仍然加载（宿主自动映射权限），但拿不到新扩展点和 bridge。

> 再强调一次：上表是**迁移目标**，v2 宿主还没实现。当前 `HookManifest.load()` 遇到
> `apiVersion != 1` 会直接报「不支持的 apiVersion」并拒绝加载。
