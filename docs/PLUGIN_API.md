# 插件 API 参考（v2）

> 状态：**v2 插件 API 已落地**——工具、五个钩子、渲染器、UI 插槽/页面、导航外壳、
> 宿主实时状态、动作总线、主题 token/皮肤、权限网关、`bridge.*`（exec/fs/http/tool.invoke/
> ui/nav/overlay/adb/secret/action/permission/location 等）、生命周期与调试通道。
> `contributes.tools` / `renderers` / `ui` / `pages` / `nav` / `state` / `actions` / `theme`
> 均按本文契约工作；`bridge.adb` 走 Shizuku provider。
> 仍未实现：本地无线 adb 配对。
> 配套文档：`PLUGIN.md`（上手 + 示例）、`plugin.schema.json`（manifest 校验）、
> `docs/UI_FRAMEWORK.md`（视图 DSL 的实现契约）。

## 1. manifest.json

```json
{
  "id": "deploy_card",
  "name": "Deploy Card",
  "version": "1.0.0",
  "apiVersion": 2,
  "description": "把 deploy 代码块渲染成状态卡片",
  "author": "you",
  "entry": "main.js",
  "permissions": ["render.match"],
  "androidPermissions": [],
  "net": { "allow": [] },
  "config": [],
  "limits": { "budgetMs": 300, "budgetSteps": 200000, "toolMs": 30000 },
  "onError": "deny",
  "priority": 0,
  "contributes": {
    "hooks": [],
    "tools": [],
    "renderers": [],
    "ui": [],
    "state": [],
    "actions": [],
    "pages": [],
    "nav": [],
    "theme": null
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | `[a-z0-9_]{1,32}`，目录名不一致时以 manifest 为准 |
| `name` | string | 否 | 展示名，默认用 `id` |
| `version` | string | 否 | 建议语义化版本；用于示例插件升级判断 |
| `apiVersion` | int | 是 | v2 固定为 `2`；`1` 走兼容映射 |
| `description` | string | 否 | 插件页显示的一行说明（中文/默认） |
| `description_en` | string | 否 | 非中文界面优先显示的英文说明；缺省回退 `description` |
| `author` | string | 否 | 展示用 |
| `entry` | string | 否 | 入口脚本，默认 `main.js` |
| `permissions` | string[] | 否 | 申请的宿主权限，见第 2 节 |
| `toolAccess` | object | 否 | `{allow: string[], confirm: boolean}`，`tool.invoke` 的白名单与确认策略 |
| `androidPermissions` | string[] | 否 | 需要通过权限网关申请的 Android 权限全名 |
| `net.allow` | string[] | 否 | `net.http` 允许访问的域名（仅 https） |
| `config` | ConfigField[] | 否 | 用户可配置项，见 1.1 |
| `limits.budgetMs` | int | 否 | 单次调用墙钟预算，默认 300，上限 5000 |
| `limits.budgetSteps` | int | 否 | 保留字段；QuickJS 无指令计数，**v2 不生效**（只按 `budgetMs` 墙钟） |
| `limits.memoryMb` | int | 否 | 单插件 JS 堆上限 MB，默认 16，夹在 4–128 |
| `limits.toolMs` | int | 否 | 工具 handler 超时，默认 30000 |
| `onError` | `"deny"` \| `"allow"` | 否 | 钩子异常时拦下（默认）还是跳过 |
| `priority` | int | 否 | **只决定钩子链顺序**（升序，小者先）。各贡献类型有自己的 `priority`，见对应章节 |
| `contributes.hooks` | string[] | 否 | 声明实现哪些钩子 |
| `contributes.tools` | Tool[] | 否 | 见第 3 节 |
| `contributes.renderers` | Renderer[] | 否 | 见第 4 节 |
| `contributes.ui` | UiSlot[] | 否 | 见 5.1 |
| `contributes.pages` | Page[] | 否 | 见 5.2 |
| `contributes.nav` | NavEntry[] | 否 | 只对主题生效，见 5.3 |
| `contributes.state` | StateEntry[] | 否 | 对外发布的状态，见 6.3 |
| `contributes.actions` | Action[] | 否 | 动作总线的提供方声明，见第 11 节 |
| `contributes.theme` | Theme | 否 | 主题 token/贴图/皮肤，见第 12 节 |
| `toolAccess.allow` | string[] | 否 | 允许调用的工具名白名单；为空时 `tool.invoke` 一律拒绝 |
| `toolAccess.confirm` | boolean | 否 | 危险工具每次调用是否弹确认，默认 `true` |
| `requires` | object | 否 | `{actions[], states[], pages[]}`：依赖的全局能力，缺失时插件页提示 |

### 1.1 ConfigField

用户可在插件页「设置…」里修改，插件用 `bridge.config.get(key)` 读取。

```json
{ "key": "endpoint", "label": "服务地址", "type": "string", "default": "", "hint": "https://..." }
```

`type`：`string` | `number` | `boolean` | `select`（配 `options: [{label, value}]`）。
用户值按插件 id 持久化（`SharedPreferences("plugin_config")`），插件升级不清除；
插件页「重置」恢复 manifest 默认值。

---

## 2. 权限：两层，别混用

`permissions` 一项里其实有**两类完全不同的东西**。实现和文档都按这两层分开处理，
否则会出现"`request("hook.tool")` 该弹什么窗"这种没有答案的问题。

| 层 | 是什么 | 谁决定 | 在哪管理 |
|---|---|---|---|
| **capability（能力声明）** | 插件能挂哪些扩展点、能读哪些宿主数据 | 插件作者在 manifest 声明，用户在**插件页逐项开关**（静态、持久） | 插件页 |
| **grant（外部资源授权）** | 能访问哪些外部资源（文件/网络/执行/设备特权） | 运行时由**权限网关按 scope 弹窗**询问（"仅本次 / 始终 / 拒绝"） | §7.7 网关 + 插件页 |

**第 1 层：capability**（插件页开关；关掉 = 对应扩展点不生效，不弹窗、不阻塞）

实现约定：manifest 里声明即启用；插件页「设置…」可以逐项关闭（按插件 id 持久化在
`SharedPreferences("plugin_caps")`），关闭后对应扩展点静默不生效。下表「默认」指
manifest 没声明时的状态。

| capability | 覆盖的 API | 默认 | 风险 |
|---|---|---|---|
| `tool.register` | `contributes.tools` | 关 | 低 |
| `hook.tool` | `onBeforeToolCall` 的 `veto` | 关 | 低 |
| `hook.tool.rewrite` | `onBeforeToolCall` 的 `rewrite`、`onAfterToolResult` 的 `replace` | 关 | 中 |
| `hook.message` | `onUserMessage`、`onAssistantMessage` | 关 | 中 |
| `hook.context` | `onContextBuild` | 关 | 中 |
| `render.match` | `contributes.renderers` | 关 | 低 |
| `ui.slot` | `contributes.ui`、`bridge.ui.toast/confirm/patch` | 关 | 低 |
| `ui.page` | `contributes.pages`、`contributes.nav`、`bridge.nav.*`、`nav.list` | 关 | 低 |
| `ui.overlay` | `bridge.overlay.*`（仅应用内浮层） | 关 | 低 |
| `device.info` | `bridge.device.*` | 关 | 低 |
| `secret.get` | `bridge.secret.get` | 关 | 中 |
| `state.read` | 读取 `pocket.*`（`pocket.display.*` 除外）与 `bridge.context.usage()` 等 | 关 | 低 |
| `state.provide` | 发布自己的状态供主题/其他插件绑定 | 关 | 低 |
| `action.provide` | 通过 `contributes.actions` 把自己的能力暴露给主题/其他插件 | 关 | 中 |
| `action.bind` | 调用 `action:*` 与 `bridge.action.invoke` | 关 | 中 |

**第 2 层：grant**（权限网关按范围授权，见 §7.7）

| grant | 覆盖的 API | 默认 | 风险 |
|---|---|---|---|
| `net.http` | `bridge.http.*` | 关 | 高 |
| `fs.workspace` | `bridge.fs.*`（工作区） | 关 | 中 |
| `fs.shared` | `bridge.fs.*`（`/sdcard`，另需系统"所有文件访问"） | 关 | 高 |
| `exec.guest` | `bridge.exec` | 关 | 高 |
| `tool.invoke` | `bridge.tool.*`，范围由 `toolAccess.allow` 限定 | 关 | 高 |
| `bridge.adb` | `bridge.adb.*` | 关 | 高 |
| `env.time` | `bridge.time.*`、绑定 `pocket.time.*` | 关 | 中 |
| `clipboard` | `bridge.clipboard.*` | 关 | 中 |
| `notify` | `bridge.notify` | 关 | 低 |
| `overlay.system` | 系统级悬浮窗（其他应用上层），特殊权限 | 关 | 极高 |
| `location` | `bridge.location.*`（另需 `androidPermissions` 定位权限） | 关 | 高 |
| `android.permission.*` | 系统运行时权限 | 关 | 视权限 |

规则：

- 两层都**必须先写进 manifest 的 `permissions`**；没声明的 capability 不生效，没声明的 grant
  `request()` 返回 `unsupported`、调用直接 `E_PERM`。
- **capability 不走网关**：插件页开关决定，关闭时对应扩展点静默不生效（渲染器/插槽回退默认，
  不崩界面、不弹窗）。`request("hook.tool")` 这类调用返回 `false`（`status()` 返回 `unsupported`）。
- **grant 走网关**：`permissions` 只是声明意图，实际生效靠运行时按范围授权；被拒 → `E_PERM`
  （工具/渲染/界面）或按 `onError` 处理（钩子）。
- `pocket.display.*` 与 `bridge.display.metrics()` 免 `state.read`（布局自适应必需，且不含设备标识）。
- 主题的 `skins` 需要 `render.match`；没有该 capability 时 skins 被忽略并记警告。

### 2.1 依赖声明

```json
{ "requires": { "actions": ["svc.restart"], "states": ["svc_ctl.progress"], "pages": ["svc_ctl.runtime"] } }
```

- 只用于**提示与降级**，不改变加载顺序，也不自动安装被依赖的插件。
- 依赖缺失时：插件页显示"缺少 svc.restart（由未安装的插件提供）"；绑定不到的状态按 `fallback` 处理，
  指向缺失动作的按钮按 `fallback` 隐藏/置灰。
- `plugin:lint` 会把缺失依赖列为警告；提供者后来装上后，下一次重载自动恢复。

---

## 3. 工具与钩子

### 3.1 工具

```json
{
  "name": "deploy",
  "description": "给模型看的说明：做什么、什么时候用、有什么副作用",
  "inputSchema": { "type": "object", "properties": {}, "required": [], "additionalProperties": false },
  "handler": "deployTool",
  "timeoutMs": 60000,
  "priority": 0
}
```

`handler(ctx)`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ctx.name` | string | 工具名 |
| `ctx.args` | object | 已按 `inputSchema` 校验、已填默认值 |
| `ctx.session` | `{id, title}` | 当前会话 |
| `ctx.iteration` | int | 本轮 AgentLoop 的迭代序号（从 1 开始） |

返回：`string` 或可 JSON 序列化的对象。抛异常 → 该次工具调用失败，异常信息回给模型。

实现约定（当前版本）：

- `contributes.tools` 需要 `apiVersion: 2`；插件还必须声明 `permissions: ["tool.register"]`，
  否则工具不会注册（`plugin:lint` 报 WARN）。
- 工具只在插件**启用**后出现在模型/调试通道的工具表里；禁用后立即消失。
- 参数按 `inputSchema.properties` 填 `default`，检查 `required` 与常见 `type`
  （string / boolean / integer / number / array / object）；未在 schema 声明的字段会原样透传。
- 超时取工具声明的 `timeoutMs`，未写时用 `limits.toolMs`（默认 30s），统一夹在 1s–600s。
  同一插件的所有入口串行，长工具会阻塞该插件的钩子/渲染器，但不影响其它插件。
- `priority`（可选，默认 0）只用于**跨插件工具重名**：数值小者胜；与插件级 `priority` 无关。
- `ctx.session` 为 `{id, title}`（无活动会话时为空对象）；`ctx.iteration` **当前固定为 0**
  —— AgentLoop 尚未把迭代序号透传给工具。
- 返回 `undefined`/`null` 时，模型看到 `(工具 <name> 没有返回内容)`。

字段名冲突：`name` 不能和内置工具同名（`bash` / `read` / `write` / `edit` / `glob` / `grep` / `web_search` / `web_fetch` / `ask_user_question` / `container` 等），冲突时插件工具被拒绝注册并在插件页报错。跨插件重名按**工具自己的 `priority`** 升序（Tool 的字段，缺省 0）、同优先级按插件 `id` 取第一个，其余同样记错。

### 3.2 钩子

五个钩子点。manifest 的 `contributes.hooks` 写**钩子点名**（不带 `on`），脚本里实现的
**函数名固定**为 `on` + 点名首字母大写，一一对应，不能自定义：

| `contributes.hooks` 的值 | 脚本里的函数名 | 调用时机 | 能返回 |
|---|---|---|---|
| `beforeToolCall` | `onBeforeToolCall` | 工具执行前 | `allow` / `veto` / `rewrite` |
| `afterToolResult` | `onAfterToolResult` | 工具返回后、写转录前 | `allow` / `replace` |
| `userMessage` | `onUserMessage` | 用户消息入库前 | `allow` / `veto` / `rewrite` |
| `contextBuild` | `onContextBuild` | 每次请求发出前 | `allow` / `replace`（受限） |
| `assistantMessage` | `onAssistantMessage` | 助手输出写库/渲染前 | `allow` / `replace` |

（v1 顶层 `hooks: ["beforeToolCall"]` 的点名保持不变，只是搬进 `contributes.hooks`。）

> 五个钩子均已实现。`onUserMessage` / `onContextBuild` / `onAssistantMessage` 的返回体：
>
> - `onUserMessage`：`{action:"veto",reason}` 拦截，或 `{action:"rewrite",text:"..."}` 改写；
> - `onContextBuild`：`{action:"replace",messages:[...]}`；返回的数组必须是原数组的**子序列**
>   （只能裁剪，system 消息只能在原文后追加文本），总长度不超过原来的 120%，
>   且 `tool_calls` 与 `tool` 消息必须成对；
> - `onAssistantMessage`：`{action:"replace",text:"..."}`；只作用于**最终回答**（无 tool_calls 的那条）。
>
> 钩子抛异常时按 `onError` 处理（默认 fail-closed：本轮请求/消息被拦下）。

链式语义（对所有钩子一致）：

- 按 `priority` 升序执行，同 priority 按 `id` 字典序，保证可复现。
- `veto` 终止整条链，这次操作不发生。
- `rewrite` 的结果传给下一个插件，直到链尾；没有对应写权限的改写被忽略并记警告。
- 任何插件抛异常按 `onError` 处理（默认 `deny` = fail-closed）。

**转录一致性**：钩子改写后的**实际参数与实际返回值**才写进会话日志，并带 `by: "hook:<id>"` 标记；
原始值只进审计日志。这样重开会话回放的是当时真正执行的东西，而不是"看起来执行过"的版本。

**禁止重入与阻塞**：钩子由 agent 线程发起、持有工具执行锁，且 `budgetMs` 默认只有 300ms。
在钩子里：

- 调 `bridge.tool.invoke` / `bridge.action.invoke`（可能间接调工具）→ `E_REENTRANT`，
  不会排到队尾自锁；
- 调**任何阻塞式 bridge**（`permission.request`、`ui.confirm`、`exec`、`http.request`、
  `adb.*`、`fs.*` 触发首次授权、以及清单见 §9.2.1 的其它调用）→ `E_BLOCKED`。
  弹窗等用户 60s 会把整轮对话卡死，命令跑 30s 会超出钩子预算；
- 需要读配置用 `bridge.config.get`（capability，不弹窗、不阻塞），
  需要"跑完再让模型看到"的逻辑放到动作 + `inject:true`，不要放进工具钩子。

**`onContextBuild` 的额外限制**：只能裁剪消息、或在已有的 system 消息里追加文本；
不能凭空造 `tool` 消息、不能打乱 `tool_calls` 与 `tool` 的配对，总长度不得比原来多 20%。

---

## 4. 渲染器

```json
{ "id": "deploy", "match": { "fence": ["deploy"] }, "handler": "renderDeploy", "priority": 0 }
```

`match` 三选一：

| 形式 | 说明 |
|---|---|
| `{ "fence": ["deploy","deploy-status"] }` | 代码块语言名匹配（大小写不敏感），只匹配 fence 块 |
| `{ "regex": "^\\[chart\\]" }` | 对**纯文本块**正文做 find（宿主加 100ms 匹配预算） |
| `{ "prefix": "DEPLOY:" }` | 纯文本块前缀匹配（大小写敏感） |

`handler(ctx)`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `ctx.type` | `"fence"` \| `"text"` | 块类型 |
| `ctx.lang` | string | fence 语言，纯文本块为空串 |
| `ctx.body` | string | 块内容（已去掉围栏） |
| `ctx.data` | object \| null | 若 `body` 是合法 JSON 对象，宿主预解析好放这里；否则 `null` |

返回：视图节点（见第 6 节）或 `null`（不接管）。异常/超时 → 回退默认渲染。

**实现约定（当前宿主）**

- 链顺序：渲染器自己的 `priority` 升序 → 插件 id → 渲染器 id；第一个返回
  非空节点的插件生效。插件级 `priority` 不参与这条链（P2 起解耦）。
- 只对**已启用**且 manifest 声明了 `permissions: ["render.match"]`（或 v1 的
  `capabilities` 里含 `render.match`）的插件求值；未声明的渲染器静默不生效。
- handler 预算取 `min(limits.budgetMs, 500ms)`：它在 `plugin-render` 后台线程执行，
  不能按钩子的 800ms 等；超时/抛异常记 `lastError` 并继续尝试下一条，整块最终回退默认渲染。
- 结果按 `(type, lang, body)` 缓存 64 条，流式重排不会对同一块反复跑 JS；插件重载/启停时清空。
- **UI 主线程只读缓存**：未命中时这一帧按默认渲染，后台求值完成后再重渲染该气泡；
  同一块同时只会有一个后台任务。因此插件 renderer 永远不在主线程执行 JS。
- 流式中的**尾块不跑插件**（半截 fence 会让 handler 反复超时/抛错），定型后再接管。
- 示例插件：`app/src/main/assets/plugins/deploy_card`（fence + prefix + regex 三条规则）；
  `app/src/main/assets/plugins/weather`（`get_weather` 工具 → 定位 + Open-Meteo 查询 →
  在工具返回值里注入「原样输出 \`\`\`weather 代码块\`\`\`」的卡片渲染提示词 → 同名 fence 渲染器
  把 JSON 画成天气卡片）。
- 调试：`render:list` 列出当前链，`render:test <lang|text> <body>` 跑一遍匹配与 handler。


---

## 5. 界面：插槽、页面、导航

### 5.1 插槽

```json
{ "slot": "message.footer", "handler": "footer", "priority": 0 }
```

可用插槽：

| 插槽 | 位置 | 传入 |
|---|---|---|
| `message.header` | 消息气泡上方 | `ctx.message` |
| `message.footer` | 消息气泡下方 | `ctx.message` |
| `composer.above` | 输入框上方 | `ctx.session` |
| `settings.section` | 设置页插件区 | `ctx.session`（可能为空） |
| `drawer.footer` | 侧边栏底部 | 同上 |
| `home.hero` | 首页标题下方 | 同上 |

`handler(ctx)` 返回**一个**视图节点；返回 `null` 表示不显示。
`priority`（可选，默认 0）决定同一插槽多个插件时的求值顺序：小者先求值，第一个返回视图的生效。
常驻插槽默认不参与长按选择（chip/按钮不应被选词）；需要复制文本的插槽可在根节点显式声明 `select: "cross"`。

动作：视图节点上的 `action: "fnName"` 会在点击时调用 `fnName(ctx)`，`ctx` 为
`{ action, node, message, session, value }`。返回：

- `string` → toast
- 视图节点 → 替换当前插槽内容
- 其他 → 无操作

### 5.2 自定义页面

插件/主题可以注册整页（仪表盘、设置面板、监控页），不需要改 App 代码：

```json
"pages": [
  {
    "id": "runtime",
    "title": "运行状态",
    "icon": "asset://chart.svg",
    "handler": "renderRuntime",
    "refreshMs": 2000,
    "scroll": true
  }
]
```

| 字段 | 说明 |
|---|---|
| `id` | `[a-z0-9_-]{1,32}`，插件内唯一；全名 `<插件id>.<id>` |
| `title` / `icon` | 导航里的显示名与图标（`asset:` / 内置图标名）；**`icon` 当前只解析，默认外壳暂不显示** |
| `handler` | `handler(ctx)` 返回一个视图节点（通常是 `column`） |
| `refreshMs` | 可选，自动重新调用 handler 的间隔，最小 1000ms |
| `scroll` | 默认 `true`，宿主套一层纵向滚动；内容自己滚就设 `false` |
| `priority` | 可选，默认 0；页面列表排序（小者靠前），与插件级 priority 无关 |
| `params` | 可选，声明支持的参数（JSON Schema）；**当前只透传，不做校验** |

页面 `ctx`：`{ page, params, session, state }`；`params` 来自 `bridge.nav.push`。
生命周期事件：页面节点上的 `on: { appear, disappear }` 会按进入/离开触发，适合首次加载。
渲染失败 → 显示错误卡片 + 重试按钮，不影响其它页面。

### 5.3 导航与菜单

注册了页面就自动出现在默认外壳的「插件页面」分组里（抽屉底部），不需要额外声明。
`contributes.nav` **只影响主题外壳**：默认外壳按插件启用顺序排列，忽略这里的 group/order。
主题外壳（T3）可以自己排：

```json
"nav": [
  { "page": "runtime",   "label": "运行状态", "group": "工具", "order": 10 },
  { "page": "deploy",    "label": "部署",     "group": "工具", "order": 20 },
  { "page": "settings",  "hidden": true }
]
```

- `group` 相同则归到一个分组标题下；`order` 升序。
- `hidden: true` 只是不在主题外壳的菜单里显示，`nav.push` 仍可进入。
- 主题可以用 `nav.list` 节点在任意位置渲染菜单：

```js
{ type: "nav.list", group: "工具", variant: "sidebar" }
```

宿主组件，自动列出可用页面、当前高亮、点击切换；`style`: `sidebar` / `tabs` / `grid`。

**实现约定（当前宿主）**

- 数据来自 `NavModel`：`pocket.chat`（builtin，order 0）+ 已启用页面（order 1000）
  + 所有启用插件的 `contributes.nav` 覆盖，按 order→page 排序、按分组展示。
- `nav.list` 的 props：`group`（可选，过滤分组）、`variant` = `sidebar`（分组标题 + 行）/
  `tabs`（横向 chip）/ `grid`（两列）；当前项取 `NavShell.currentPage()`。
- 点击调用宿主导航：`pocket.chat` 关闭所有插件页面，其余按 `<plugin>.<page>` 打开页面。
- 菜单项文本可长按选中，出现「复制 / 全选 / 取消」浮窗；短按仍正常跳转。
- 插件页面/插槽的内容根节点统一带 `select: cross` 作用域与独立选择 hub，
  因此长按页面正文/插槽文本同样可以复制，且不会与聊天区的选择混在一起。

### 5.4 仪表盘

仪表盘 = 页面 + 状态绑定 + 图表节点，不需要新的机制：

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

**实现约定（当前宿主）**

- 只对**已启用**且声明了 `ui.slot` / `ui.page` 的插件接线；缺能力时 `plugin:lint` 报 WARN，
  运行时静默不挂载。
- 插槽求值顺序：**插槽声明自己的 `priority` 升序** → 插件 id 字典序；第一个返回非空视图节点的插件生效。
  插件级 `priority` 不影响插槽求值。
  handler ctx 为 `{session, message?}`（`message` 仅消息头/尾插槽有）。
- 已接线位置：`home.hero`（首页标题下方）、`composer.above`（输入框上方）、`drawer.footer`（侧栏底部）、
  `settings.section`（设置页插件区末尾）、`message.header` / `message.footer`（消息气泡上下，
  创建时与助手收尾时各求值一次；会话回放期间跳过）。
- 节点上的 `action: "fn"` 与 `on.tap: "fn"` 都会回调插件：ctx `{action|event, node, value, pointer?, session, message?}`。
  返回字符串 → toast；返回视图节点 → 替换当前插槽/页面内容；事件在后台线程求值、结果回主线程渲染，
  避免长工具调用阻塞界面（也意味着事件结果不是同步返回的）。
- 页面注册后自动出现在抽屉底部「插件页面」；handler ctx `{page, params, session, state}`；
  `refreshMs`（≥1000）到期自动重跑；首帧失败显示错误卡片 + 重试，后续刷新失败保留旧内容；
  页面在 Dialog 外壳中打开，`scroll` 控制内容区是否套 `ScrollView`。
- `contributes.nav` 现在同时作用于**默认外壳**与主题里的 `nav.list`：页面默认 group 为空
  （显示为「插件页面」）、order 1000；nav 可改 label/group/order 或 `hidden: true`。
  同一页面被多个插件覆盖时按 **nav 声明自己的 `priority`**（缺省 0）升序、再按插件 id 取第一个；
  指向未注册页面的条目被忽略，`plugin:lint` 会报 WARN。`hidden` 只隐藏菜单，直接打开页面不受影响。
  菜单里的排序仍由 `order` 决定，两者互不影响。
- `bind` 绑定 `HostState` 实时值：model/session/runtime/display/insets、context.usage、
  `pocket.nav.page`、`pocket.time.text` 等；插件 `bridge.state.set` 发布的值也会推给绑定节点。
  只有用到 `bind` 的界面才随状态变化重渲染。
- 调试：`ui:list` / `ui:test <slot> [ctxJson]`、`page:list` / `page:test <plugin.page> [paramsJson]`，
  见 §10。

自动刷新（`refreshMs`）与状态绑定可以叠加：绑定负责"值变了就更新"，`refreshMs` 负责
"主动重新取数"（比如用 `bridge.http` 拉监控接口）。两者都受页面可见性约束，离开页面即停。

---

## 6. 视图 DSL

节点是普通对象，`type` 必填。**以 `docs/UI_FRAMEWORK.md` 为唯一契约**：字段名、取值、
限制都和宿主 `UiRenderer` 一致，不要发明新字段。未知 `type` 会被静默丢弃并记一条
`warning`（不会崩界面），未知字段忽略。

**P1 已实现**（宿主 `UiRenderer` 现在就认这 7 种，见 `docs/UI_FRAMEWORK.md` 第 2 节）：

| type | 字段 | 说明 |
|---|---|---|
| `column` | `children`, `gap`, `align` | 纵向容器 |
| `row` | `children`, `gap`, `align` | 横向容器 |
| `hscroll` | `children`, `gap`, `align` | 横向滚动容器；超出视口可左右滑动 |
| `card` | `children`, `tone`, `pad`, `radius` | 圆角卡片容器（纵向） |
| `text` | `text`, `size`, `tone` | 普通文本 |
| `code` | `body` | 等宽代码块（自带底色与圆角） |
| `markdown` | `text` | Markdown；**不要放进流式热路径**，见 `docs/UI_FRAMEWORK.md` 第 6 节 |
| `divider` | — | 1dp 发丝线 |

任意子节点还可以带 `grow: true`：在 `row` / `column` 里按 weight 分剩余空间。

**P2（设计已定、渲染器还没实现）**：

| type | 字段 | 说明 |
|---|---|---|
| `button` | `label`, `action`, `variant`, `params`, `fallback` | 按钮；`variant`: `primary`/`ghost` |
| `badge` | `text`, `tone` | 小标签；`tone`: `ok`/`warn`/`info` |
| `spacer` | `space` | 固定间距，取 `pad`/`gap` 的同一组 token（不叫 `size`，避免和字号冲突） |
| `image` | `src`, `height` | `asset:` 读插件资源，`workspace:` 读工作区，`https:` 需 `net.http` 且在 `net.allow` |
| `input` | `value`, `placeholder`, `multiline` | 输入框；值在事件回调的 `ctx.value` 里 |
| `switch` | `checked` | 开关；切换即提交，触发 `change` |
| `checkbox` | `checked` | 复选；点击即提交，触发 `change` |
| `slider` | `value`, `min`, `max`, `step` | 拖动条；**松手时**触发一次 `change`（`ctx.value` 为数值） |
| `progress` | `value`, `tone`, `indeterminate`, `label` | 进度条；`value` 0–1；在 `row` 里占满剩余宽度 |
| `bars` | `values`, `max`, `height`, `tone` | 柱状图，最多 60 个值 |
| `nav.list` | `group`, `variant` | 宿主组件：插件页面菜单；`variant`: `sidebar`/`tabs`/`grid` |
| `list` | `items`（兼容 `children`）, `keyField`, `gap`, `height` | 纵向列表，按 `keyField` 增量更新；≥30 行自动虚拟化（只挂视口 + overscan）。`height`（dp）给固定视口并支持列表内滚动，不再依赖外层 ScrollView |

> 下面各节的示例展示的是**目标形态**，会用到 P2 字段（`on` / `bind` / `id` / `action` /
> `dragHandle`）。当前宿主已实现上表控件与 `text.chunks` / `absolute` / `window` / `taskbar`；
> `dragHandle` 未实现（浮层/窗口用固定拖动区域）；浮层已实现（§7.11）。插件 `renderers`
> 覆盖链已接线（第 4 节），`table` / `codeCard` / `toolCard` 三个聊天组件也可在插件插槽/
> 页面/浮层的返回值里使用。

**任意节点可以带的字段**：

| 字段 | 阶段 | 说明 |
|---|---|---|
| `key` | P1 | diff 身份；同父下必须唯一。**缺省时宿主按 `#位置:type` 生成**（不是按 `id`），插入节点会让后面的位置全部错开，所以列表/流式内容一定要给 `key` |
| `id` | P2 | 配合 `bridge.ui.patch` 定位；同一棵树内唯一 |
| `data` | P2 | 任意 JSON，不渲染，事件回调里用 `ctx.node.data` 取回 |
| `on` | P2 | 事件表，见 6.2 |
| `bind` | P2 | 状态绑定，见 6.3 |
| `select` | P3 | `none` / `local` / `cross`：长按文本进入选择；`local` 只选本节点子树，`cross` 可与同一 hub 的其它范围连选，`none` 整段排除。只有宿主 `UiSelection.include` 标记过的文本参与复制（`text` / `markdown` / `code` 节点自动标记） |
| `dragHandle` | P2 | `true` 时该子节点可作为浮层拖动把手（只在浮层里生效）；浮层未实现，T3 窗口固定用标题栏拖动 |

### 6.1 样式 token（不接受裸颜色 / px）

| 维度 | 字段 | 取值 |
|---|---|---|
| 字号 | `size` | `title` 17sp · `body` 15sp · `caption` 12sp · `mono`/`code` 13sp |
| 文字颜色 | `tone` | `primary` `secondary` `caption` `brand` `ok` `warn` `danger` |
| 卡片底色 | `tone`（`card` 上） | `surface` `accent` `warn` `danger` |
| 内边距 | `pad` | `none` `xs`(4dp) `sm`(8dp) `md`(12dp) `lg`(16dp) |
| 间距 | `gap` | 同 `pad` 的一组 token |
| 圆角 | `radius` | `sm`(8dp) `md`(12dp) `lg`(16dp) |
| 对齐 | `align` | `start` `center` `end`（`space-between` 属 P2） |

P1 由 `UiTheme` 硬编码查 `res/values/colors.xml`；主题包接入后宿主重映射这些 token
（见 §12.1），插件始终只写语义名、不写死值。

限制：单次最多 **200 节点**（`list.items` / `list.children` 单独计数：最多 **1000 项 / 4000 节点**；两者同时存在时以 `items` 为准）、
嵌套 **8 层**、单个文本 **4096 字**（超出截断并记 `warning`）；P2 的 `image` 高度上限 600dp。

### 6.2 事件

```js
{
  type: "card", id: "svc", data: { service: "api" },
  on: { tap: "openDetail", longPress: "showMenu", appear: "onVisible" },
  children: [ /* ... */ ]
}
```

| 事件 | 可用节点 | 触发时机 |
|---|---|---|
| `tap` | 任意 | 点击；子节点先处理，未被消费才冒泡给父节点 |
| `longPress` | 任意 | 长按 500ms |
| `appear` / `disappear` | 任意 | 首次进入/离开屏幕，每个节点各一次 |
| `change` | `input` `switch` `checkbox` `slider` | **input 只在提交或失焦时发**；switch/checkbox 点击即发；slider 松手才发 |
| `submit` | `input` | 键盘回车 |
| `focus` / `blur` | `input` | 获得/失去焦点 |
| `scroll` | `list` | 节流后触发（≥100ms），`ctx.value` = `{first, last, total}`；**宿主尚未接线**（list 已窗口虚拟化，但滚动事件仍不回调） |

回调 `ctx`：

```js
{
  event: "tap",
  node: {
    id: "seg-2", key: "seg-2", type: "row",
    data: { value: "b" },
    x: 24, y: 160, w: 300, h: 44          // 相对窗口，dp
  },
  pointer: {                               // 只有 tap / longPress 有
    x: 180, y: 182,                        // 相对窗口左上角，dp
    localX: 156, localY: 22,               // 相对节点左上角，dp
    w: 300, h: 44                          // 节点当下的尺寸，dp
  },
  value: undefined,                        // input/switch/checkbox/slider 的值
  message, session
}
```

坐标一律是 **dp**（不是像素），在事件发生那一刻取快照；节点滚动/重排后旧值不保证仍然有效。
需要主动查询时用 `bridge.ui.bounds(id)`（见 7.5）。

常见用法：

```js
// 一整条当作分段控件：点哪一段由相对位置算出来
function pick(ctx) {
  var ratio = ctx.pointer.localX / ctx.pointer.w;       // 0–1
  var i = Math.min(2, Math.floor(ratio * 3));
  return { type: "row", /* 高亮第 i 段 */ };
}
```

返回值：

- 视图节点 → 替换该节点所在子树
- `undefined` → 无操作
- 字符串 → toast

或者调用 `bridge.ui.patch(id, props)` 只改属性（适合开关、进度、选中态这种高频小改动）。

规则：

- 单次事件预算 50ms，超时丢弃并记日志；App 在后台时不派发。
- 事件处理期间不能创建 View、不能阻塞超过预算；需要长任务就返回后异步用 `bridge.ui.patch` 更新。
- **事件回调里禁止调用阻塞式 bridge**（名单见 §9.2.1）→ `E_BLOCKED`。特别是 `bridge.ui.confirm`
  虽然标着"同步"，但它会阻塞最长 60s 等用户点弹窗，在 50ms 预算的事件里必然超时且弹窗已经弹出，
  行为不可控。确认类交互请改用：事件返回一个带确认按钮的卡片，由按钮的 `tap` 走动作回调。
- 节点被移除后其监听自动注销。
- 宿主组件的内部手势（消息长按、输入法、列表滚动）不对外开放；主题只能在自己的节点上加事件。
- **不提供连续移动事件**（`touchMove` / `drag`）：高频回调会把主线程打满。拖拽类交互用浮层的
  `dragHandle` 或 T3 窗口原语，宿主自己处理手势。
- 点击会先给最内层节点，未被消费才冒泡给父节点，所以 `pointer.localX` 相对的是**实际收到事件的那个节点**。

### 6.3 状态绑定

主题要显示"上下文占用率"这类**实时值**时，不要自己算、也不要和某个插件硬绑：宿主和插件把值
发布成具名状态，主题节点按名字绑定。

宿主内置的（不需要装任何插件就能用）：

| 状态 | 类型 | 说明 |
|---|---|---|
| `pocket.context.tokens` | number | 当前上下文估算 token 数 |
| `pocket.context.limit` | number | 上限（100000） |
| `pocket.context.ratio` | number | 0–1，`tokens / limit` |
| `pocket.context.summary` | string | 现成的展示文本，如 `62% · 62.0k / 100k` |
| `pocket.context.messages` | number | 消息条数 |
| `pocket.context.compacting` | boolean | 是否正在压缩 |
| `pocket.model.name` | string | 当前模型名 |
| `pocket.session.id` | string | 当前会话 id |
| `pocket.session.title` | string | 当前会话标题 |
| `pocket.runtime.state` | string | `ready` / `setup` / `error` |
| `pocket.runtime.backend` | string | 如 `pocketroot` |
| `pocket.agent.busy` | boolean | 是否正在跑一轮对话 |
| `pocket.agent.iteration` | number | 本轮迭代序号 |
| `pocket.display.width` / `height` | number | 内容区尺寸（dp） |
| `pocket.display.dark` | boolean | 当前是否深色 |
| `pocket.display.orientation` | string | `portrait` / `landscape` |
| `pocket.display.fontScale` | number | 系统字体缩放 |
| `pocket.display.reduceMotion` | boolean | 系统「移除动画」 |
| `pocket.display.insets` | object | `{top,bottom,left,right}`（dp，含刘海/手势条） |
| `pocket.theme.id` / `name` | string | 当前主题 |
| `pocket.nav.page` | string | 当前页面全名，聊天页为 `pocket.chat` |
| `pocket.time.text` / `date` | string | `14:32` / `2026-11-13`，每 30s 更新 |

完整清单（含更新时机）见第 13 节。

主题侧：

```js
{
  type: "row", align: "center", gap: "sm",
  fallback: "hide",
  children: [
    { type: "progress", bind: { "value": "pocket.context.ratio" } },
    { type: "text", size: "caption",
      bind: { "text": "pocket.context.summary" } }
  ]
}
```

- `bind` 是"属性名 → 状态名"的映射，节点上的原值当初始值。
- 状态变化时宿主**只 patch 这一个属性**，合并到下一帧，最多 10 次/秒；节点不可见时暂停。
- 找不到提供者时按 `fallback`：`hide`（隐藏节点）或 `keep`（保持初始值，默认）。
- 类型不匹配忽略并记日志，不会让整棵树渲染失败。
- 需要格式化时用对象形式，省掉一个只做格式化的插件：

```js
bind: {
  "value": { "state": "pocket.context.ratio", "format": "percent" },
  "text":  { "state": "pocket.context.tokens", "format": "number", "decimals": 1, "suffix": "k" }
}
```

支持 `percent`（0–1 → `62%`）、`number`（配 `decimals`/`suffix`）、`duration`（毫秒 → `1m 20s`）、
`bytes`（→ `1.2 MB`）。字符串简写 `"pocket.context.summary"` 仍然可用。
- 绑定是**只读**的：要写回，用事件 → 动作 → `bridge.state.set`（单向数据流），不做双向绑定。

插件也可以发布自己的状态给主题绑定：

```json
{
  "permissions": ["state.provide"],
  "contributes": {
    "state": [
      { "id": "deploy.status", "label": "部署状态", "type": "string" },
      { "id": "progress", "label": "部署进度", "type": "number" }
    ]
  }
}
```

```js
bridge.state.set("svc_ctl.progress", 0.4);  // 全名 = <插件id>.<id>；主题里 bind: {"value":"svc_ctl.progress"}
```

- 插件状态 `id` 只能是小写字母/数字/下划线（不能含点），全名由宿主拼成 `<pluginId>.<id>`；
  `pocket.*` 保留给宿主。
- 两个插件发布同一个 id → `plugin:lint` 报错，运行时按 **状态声明自己的 `priority`** 升序、
  再按插件 id 取第一个（此前是后加载覆盖，方向不一致），并在插件页标冲突。
- 值上限 4 KB（JSON 序列化后），超了抛 `E_LIMIT`。

**实现约定（当前宿主）**

- `contributes.state` 需要 `state.provide`；全名 `<pluginId>.<id>` 冲突时按**状态自己的
  `priority`**（缺省 0）升序、再按插件 id 取一个，lint 报 WARN。插件只能写自己声明的状态；
  `bridge.state.set` 会校验声明、类型（string/number/boolean/object）与 4 KB 上限，
  然后发布到全局 `UiStateBus`，主题/页面可直接 `bind: {"text":"<pluginId>.<id>"}`。
- 读状态：自己声明的状态免授权；`pocket.*` 与其它插件的状态需要 `state.read`
  （`bridge.state.get` / `bridge.state.all`）。
- 禁用/重载插件会清掉它发布的状态值；`storage` 与 `config` 保留。
- 当前 bridge 回调执行在 QuickJS 事件线程，宿主侧只做无锁读取，避免与 reload 的 registry 锁互锁。
- 调试：`state:list` 会包含插件状态；`plugin:lint` 输出 `OK 状态 <plugin>.<id> <type>`。
- 全局只有一个 `UiStateBus`（`HostState.bus()`）：聊天流、插件插槽/页面、设置页共用；
  每个 `UiFramework` 注册一个监听，值变化后只重渲染「树里真的用了 `bind`」的界面（≤10 次/秒）。
- `pocket.context.*`：每轮模型调用前由 AgentLoop 回调真实估算 token / 消息条数；
  `limit` 取硬上限 100000，`ratio` 夹在 0–1，`summary` 为 `62% · 62.0k / 100k` 形式。
- `pocket.agent.busy` 由发送/结束设置，`iteration` 每轮模型调用从 1 递增。
- `pocket.model.name` 随设置切换更新；`pocket.session.*` 随新建/切换会话更新；
  `pocket.runtime.*` 取运行时真实状态与 workspace；`pocket.nav.page` 随页面打开/关闭更新。
- `pocket.display.*` 在启动与 `onConfigurationChanged` 时重算（含 insets dp）；
  `pocket.time.*` 每 30s 更新；`pocket.theme.*` 随主题应用/恢复更新。


---

## 7. bridge

所有 `bridge.*` 调用都是**同步**的（`permission.request` / `ui.confirm` 会阻塞等用户操作）。
未授权抛 `E_PERM`；宿主不可用抛 `E_BRIDGE`。

**当前实现范围**

| 命名空间 | 方法 | 能力 / grant |
|---|---|---|
| `bridge.log` | `log(level, msg)` | 无 |
| `bridge.config` | `get(key[, def])` | 无（读 manifest config 默认值） |
| `bridge.storage` | `get` / `set` | 无（插件私有，256 KB，JSON 文件） |
| `bridge.state` | `set` / `get` / `all` | `state.provide` / `state.read` |
| `bridge.device` | `model` / `sdk` / `density` / `language` / `freeStorage` | `device.info` |
| `bridge.time` | `now` / `text` / `date` / `zone` / `format` | grant `env.time` |
| `bridge.exec` | `exec(cmd, {timeoutMs,cwd})` | grant `exec.guest` |
| `bridge.fs` | `read` / `write` / `list` / `remove` | grant `fs.workspace`（`/sdcard` 用 `fs.shared`） |
| `bridge.http` | `request({method,url,headers,body,timeoutMs})` | grant `net.http` + `net.allow` |
| `bridge.ui` | `toast` / `confirm` / `patch` / `bounds` | `ui.slot`（patch/bounds 也接受 `ui.overlay`） |
| `bridge.notify` | `notify(title, text)` | grant `notify` |
| `bridge.clipboard` | `read` / `write` | grant `clipboard` |
| `bridge.secret` | `get(key)` | `secret.get` |
| `bridge.tool` | `list` / `invoke` | `invoke` 需 grant `tool.invoke` + `toolAccess.allow` |
| `bridge.nav` | `push` / `back` / `current` | `ui.page` |
| `bridge.action` | `available` / `invoke` | `action.bind` |
| `bridge.overlay` | `show` / `update` / `hide` / `hideAll` | `ui.overlay` |
| `bridge.overlay.system` | `status` / `request` / `show` / `hide` | grant `overlay.system`（系统特殊权限） |
| `bridge.adb` | `available` / `provider` / `shell` / `exec` | grant `bridge.adb`；Shizuku provider |
| `bridge.location` | `available` / `status` / `get` | grant `location` + `androidPermissions` 定位权限 |
| `bridge.permission` | `status` / `describe` / `request` | 网关自身 |
| `bridge.context` | `usage()` | `state.read` |
| `bridge.session` | `info()` | `state.read` |
| `bridge.runtime` | `status()` | `state.read` |
| `bridge.display` | `metrics()` | 无（`pocket.display.*` 豁免） |
| `bridge.theme` | `get(name)` | 无 |
| `bridge.capabilities` | `capabilities()` | 无 |
| `bridge.plugins` | `list()` | `state.read` |

实现约定：

- 所有调用同步；阻塞式调用（exec / http / tool.invoke / action.invoke / ui.confirm /
  permission.request / adb）期间宿主暂停 QuickJS 中断，调用返回后恢复预算。`fs`/`time`/`notify`
  本身不阻塞，但首次调用若未授权，会走权限网关（因此在钩子/事件里未授权时返回 `E_BLOCKED`）。
- 钩子与**事件回调**（`on.*`）里调用阻塞式 bridge 抛 `E_BLOCKED`；节点 `action` / 全局动作 /
  工具 handler / 页面渲染允许等待。renderer 在 `plugin-render` 后台线程执行，同样不允许阻塞调用。
- 未声明 grant 直接 `E_PERM`；声明了但未授权时自动走权限网关（可用 `{auto:false}` 关闭）。
- `bridge.adb` 走 Shizuku：Shizuku 未运行/未授权时 `available()` 为 false，`shell/exec`
  抛 `E_BRIDGE`；本地无线 adb 配对尚未接线。
- `bridge.tool.invoke` 的 `show:true` 在对话流里插一张展示卡（标注调用方插件，不写 jsonl、
  不进上下文）；`inject:true` 把结果排队为下一轮 `system` 备注（需 `hook.context`，
  单次 8 KB / 单轮 16 KB，超出截断）。
- 系统浮层必须已有“在其他应用上层显示”特殊权限；同时最多 1 个/插件、2 个全局，
  展示期间有常驻通知，插件禁用/权限收回时销毁。

### 7.1 通用

```js
bridge.log(level, message)        // level: "i" | "w" | "e"；写插件日志环
bridge.storage.get(key, def)      // JSON 值；插件私有，上限 256 KB
bridge.storage.set(key, value)    // 同上；传 null 删除
bridge.config.get(key)            // manifest config 的当前值（用户改过的）
bridge.secret.get(key)            // 插件私有密钥（Keystore 加密）；需 secret.get
```

### 7.2 设备与执行

```js
bridge.device.model()             // "JDY-AN00"
bridge.device.sdk()               // 34
bridge.device.density()           // 2.75
bridge.device.freeStorage()       // "/data 剩余 82.4 GB"
bridge.device.language()          // "zh-CN"

bridge.exec(cmd, {timeoutMs, cwd})        // 在 Debian guest 执行；需 exec.guest
// -> { exitCode: 0, output: "...", timedOut: false }
```

`cwd` 缺省是 `/root/workspace`，且**守卫按这个 cwd 做静态判定**：插件不能借持久 shell
残留的 `cd` 漂移绕过路径检查（例如先 `cd /` 再执行 `rm -rf *` 会被 `E_PERM` 拦截）。

**执行管线统一**：`bridge.exec` 与 `bridge.tool.invoke("bash", ...)` 走的是**同一条**管线：
`CommandGuard` + `RiskModel` 判定 → 工具执行锁 → Debian guest 持久 shell → 结果落盘与审计。
两者只是权限不同（`exec.guest` vs `tool.invoke`）、是否在对话流插卡不同（`show`）。
拿到 `exec.guest` **不等于**能绕过守卫执行任意命令。

**钩子边界（重要）**：`beforeToolCall` / `afterToolResult` 只挂在**模型发起的工具调用**上。
插件自己发起的 `bridge.exec` / `bridge.fs` / `bridge.http` / `bridge.tool.invoke` 不经过这两个钩子
（否则会递归，且拦截插件能拦到自己）。要约束插件行为靠：权限网关拒绝、审计日志、
`toolAccess.allow` 白名单、`net.allow` 域名白名单、`fs` 路径边界。

### 7.2.1 定位

```js
bridge.location.available()       // 定位服务是否开启（不看权限）
bridge.location.status()          // -> {serviceEnabled, providers, fineGranted, coarseGranted, declared, granted}
bridge.location.get({timeoutMs, highAccuracy})
// -> {latitude, longitude, accuracy, altitude, speed, bearing, time, provider, stale, fine, coarse}
```

- manifest 需要同时声明 grant `location` 与
  `androidPermissions: ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"]`
  （至少一个定位权限；两个都写才能让系统弹“精确/大致位置”）。
- 首次 `get` 先走应用内权限网关（仅本次 / 始终 / 拒绝），再申请系统定位权限；
  拒绝返回 `E_PERM`，定位服务关闭返回 `E_BRIDGE`，`timeoutMs` 内没拿到定位返回 `E_BUDGET`。
- `highAccuracy` 默认 true：优先 GPS，其次网络；false 优先网络。先返回 5 分钟内缓存的
  `getLastKnownLocation`（`stale:true`），否则请求单次定位；超时后仍无缓存才报错。
- `accuracy` 单位米；`time` 是 epoch 毫秒；`provider` 为 `gps` / `network` / `passive`。
- 与 `bridge.exec` 一样，`bridge.location.get` 是阻塞式调用，**钩子/事件回调里返回 `E_BLOCKED`**。

### 7.3 文件（限 `/root/workspace`）

```js
bridge.fs.read(path)              // -> string（>256 KB 抛 E_LIMIT，用 readLines 分段）
bridge.fs.write(path, text)       // -> true
bridge.fs.list(path)              // -> [{name, dir, size}]
bridge.fs.remove(path)            // -> true（不递归删目录）
```

`path` 允许相对（相对 workspace 根）或 `/root/workspace/...`，越界抛 `E_ARGS`；需要 `fs.workspace`。
访问手机共享存储（`/sdcard`）用同一组函数，需要 `fs.shared` 且系统已授予"所有文件访问"。
路径边界由宿主强制，与 `bridge.exec` 一样**不经过工具钩子**；越界一律 `E_ARGS`。

### 7.4 网络

```js
bridge.http.request({method, url, headers, body, timeoutMs})
// -> { status: 200, headers: {...}, body: "..." }
```

域名必须在 `net.allow`，只允许 `https`，响应体上限 256 KB，默认超时 15s。
首次调用会自动走权限网关（`net.http`）；访问 `net.allow` 之外的新域名会重新询问。
`net.allow` 是授权范围的一部分，改动会触发重新授权（§7.7）。不经过工具钩子。

### 7.5 交互

```js
bridge.ui.toast(text)                          // 需 ui.slot
bridge.ui.confirm(title, text)                 // -> boolean，阻塞最长 60s（阻塞式，见 §9.2.1）
bridge.ui.patch(id, props)                     // 只更新该节点的属性（不重建子树）
bridge.ui.bounds(id)                           // -> {x,y,w,h} | null，相对窗口，dp
bridge.notify(title, text)                     // 系统通知；需 notify
bridge.clipboard.read() / write(text)          // 需 clipboard
```

`patch` 只接受该节点已有的属性（如 `text`、`checked`、`value`、`label`、`tone`、`src`），
改结构请返回新的节点树。对不存在的 `id` 调用会静默忽略并记一条警告。

### 7.6 状态

```js
bridge.state.get(id)                 // 读状态；读自己的永远允许，读 pocket.* / 别人的需 state.read
bridge.state.set(id, value)          // 发布自己的状态；需 state.provide
bridge.context.usage()               // -> {tokens, limit, ratio, triggerRatio, messages, compacting}
bridge.session.info()                // -> {id, title, messages}
bridge.runtime.status()              // -> {state, backend, workspace}
```

`context.usage()` 与 `pocket.context.*` 是同一份数据，一个给 JS 读、一个给主题绑定。
`state.set` 的值会和 `pocket.*` 一样推给绑定它的节点，但只有 `state.provide` 权限的插件能写。

### 7.7 权限网关

这里只管**外部资源授权（grant）**，即第 2 节表格的第二层。capability（扩展点/宿主数据读取）
由插件页静态开关决定，不走网关、`request()` 对它返回 `unsupported`。

网络、存储、执行、adb、剪贴板、工具调用、系统悬浮窗、Android 运行时权限——所有外部资源访问
都从这里过。manifest 里的 `permissions` 只是"声明意图"，真正生效靠运行时授权：第一次用到时
宿主弹窗，用户可选"仅本次 / 始终 / 拒绝"，之后在插件页可随时收回。

```js
bridge.permission.status("net.http")        // "granted" | "denied" | "notAsked" | "needsSettings" | "unsupported"
bridge.permission.describe("fs.workspace")  // -> {label, scope, risk, granted}
bridge.permission.request("exec.guest", { reason: "部署脚本需要执行命令", timeoutMs: 60000 })
// -> boolean；阻塞等用户操作，最长 60s
```

`request` 是阻塞式 bridge：**钩子与事件回调里会返回 `E_BLOCKED`**（§3.2 / §6.2）。
首次授权应在启用期或插件页预完成；运行时只做已授权检查。

可申请的能力名：

| 能力 | 授权范围（弹窗里展示） |
|---|---|
| `net.http` | 域名白名单（来自 `net.allow`），仅 https |
| `fs.workspace` | 读写 `/root/workspace` |
| `fs.shared` | 读写手机共享存储（需系统"所有文件访问"） |
| `exec.guest` | 在 Debian 环境执行命令 |
| `tool.invoke` | `toolAccess.allow` 里列出的工具 |
| `bridge.adb` | Shizuku（本地无线 adb 配对未接线） |
| `env.time` | 读取系统时间与时区 |
| `overlay.system` | 系统"在其他应用上层显示"（特殊权限，见下） |
| `location` | GPS / 网络定位（另需 `androidPermissions` 里的定位权限） |
| `clipboard` / `notify` | 读写剪贴板 / 发通知 |
| `android.permission.CAMERA` 等 | 系统运行时权限（必须先在 `androidPermissions` 里声明） |

规则：

- **自动代理**：`bridge.http` / `bridge.fs` / `bridge.exec` / `bridge.adb` / `bridge.tool.invoke`
  在首次调用时会自动 `ensure`（等价于 `request`），用户同意后继续执行，拒绝则抛 `E_PERM`。
  不想弹窗就先用 `status()` 判断，或用 `{ auto: false }` 让调用直接失败。
- **按范围授权**：授权绑定具体范围。`net.http` 放行 `api.example.com` 后，插件访问新域名会重新询问；
  `fs.workspace` 只覆盖工作区，越界路径仍然 `E_ARGS`。
- **不被 `permissions` 声明覆盖**：manifest 没写的能力，`request` 直接 `unsupported`，不弹窗。
- **特殊权限**（系统"在其他应用上层显示"、`fs.shared` 需要的"所有文件访问"）不是运行时弹窗：
  `status()` 返回 `needsSettings`，`request()` 跳到对应系统设置页并立即返回 `false`，
  用户回来后重新 `status()` 才算数。`bridge.overlay.system.request()` 是它的语法糖。
- 插件页显示每个能力的当前状态与范围，提供「Grant…」逐项申请和「Clear access」整体收回；
  逐项收回用调试命令 `grant:clear <id> <cap>`。最近一次使用时间暂未记录（见 §14）。
  收回后正在进行的调用下次即失败。
- 授权与拒绝都写审计日志，可用于排查"为什么这个按钮没反应"。
- **授权绑定插件身份**：每条 grant 记录存 `<id, 来源哈希, capability, scope, 决定>`。
  来源哈希由宿主对插件目录内容（manifest、入口脚本、声明的 assets、`net.allow`、
  `toolAccess.allow`、`androidPermissions`）计算，**不是插件自己写的字段**。
  哈希变化即视为新插件：所有 grant（包括"始终允许"）作废，首次使用重新询问。
  这样"挑一个已装插件的 id 覆盖目录"无法继承它的 `exec.guest` 授权。
- 卸载/禁用**不清除**授权记录（便于原样重装恢复）；但同 id 且哈希不同时强制重新授权。
  插件页提供「清除授权」手动清空该插件的全部 grant。

**当前实现（`PluginGrants`）**

- 持久化在 `SharedPreferences("plugin_grants")`：`g_<id>/<cap>` = `always` / `deny`，
  「仅本次」只存在进程内存。来源哈希 `h_<id>` 由 manifest.json + 入口脚本的 SHA-256 计算，
  文件 mtime 变化时重算；哈希变化清空该插件全部决定。
- 弹窗三选一：仅本次 / 始终允许 / 拒绝；超时 60s（可传 `timeoutMs`）返回 `E_BUDGET`。
  **「仅本次」只覆盖紧接着的一次调用**，调用后立即失效；下一次调用会重新询问。
  用户拒绝后写入 `deny`，后续自动代理直接 `E_PERM`，直到在插件页/调试命令里清除授权。
- `permission.status` 返回 `granted` / `denied` / `notAsked` / `needsSettings` / `unsupported`；
  `needsSettings` 只用于 `fs.shared` / `overlay.system` 这类系统特殊权限。
- 特殊权限 `request()` 跳系统设置并返回 false；用户返回 App 后重新 `status()` 才算数。
  `android.permission.*` 走系统运行时权限弹窗，结果回调后返回 granted/denied。
- 插件页每行显示已声明 grant 的当前状态，并提供「清除授权」；调试命令
  `grant:list <id>` / `grant:clear <id> [cap]` 可免 UI 查看/收回。

### 7.8 adb / Shizuku 桥

```js
bridge.adb.available()            // Shizuku 已运行且已授权
bridge.adb.provider()             // "shizuku" | "none"（本地无线 adb 配对暂未接线）
bridge.adb.shell(cmd, {timeoutMs})        // 经 shell 执行（注意二次解析）
bridge.adb.exec(program, args)            // 不经过 shell，推荐
// -> { exitCode: 0, output: "...", timedOut: false }
```

限制：输出 64 KB 截断，`timeoutMs` 默认 15s、上限 60s；Shizuku 未运行/未授权时
`available()` 为 false，`shell/exec` 抛 `E_BRIDGE`。首次调用走权限网关（`bridge.adb`）；
系统层授权在插件页「Authorize Shizuku」（`adb:request` 调试命令等价）。
`adb.shell` / `adb.exec` 默认**逐条确认**完整命令（shell uid 权限过大）；只有插件页
显式「信任此插件」后才跳过逐条确认，仅保留 `bridge.adb` 的一次性 grant。
设备重启后 Shizuku 需要重新启动，插件页会显示当前 provider 与最近一次错误。

### 7.9 调用工具

插件可以直接跑宿主的工具（`bash` / `read` / `write` / `edit` / `glob` / `grep` / `web_search` /
`web_fetch` / `ask_user_question` / `container` 等），也可以跑其它插件注册的工具。

```js
bridge.tool.list()                       // -> [{name, description, source, schema}]
bridge.tool.invoke("bash", { command: "git status --short" }, {
  timeoutMs: 30000,   // 默认取 limits.toolMs
  show: true,         // 在对话流里插一张工具卡（标注是哪个插件调的）
  inject: false,      // 是否把结果写进下一轮上下文（需 hook.context）
  wait: true          // agent 正忙时排队等待；false 则立刻返回 E_BUSY
})
// -> { ok: true, result: "...", exitCode: 0 }
// -> { ok: false, error: "E_BUSY" }
```

规则：

- 必须声明 `tool.invoke`，并在 `toolAccess.allow` 里列出允许的工具名；没列的一律 `E_PERM`。
  这条路径与 `bridge.exec` 共用 CommandGuard / 执行锁 / 审计（§7.2）。
  首次调用会走权限网关，弹窗展示"插件 X 请求调用 bash / read"，用户同意后按范围生效。
- 危险工具（`bash`、`write`、`edit`、`container`）默认每次弹确认框，展示工具名与参数摘要；
  `toolAccess.confirm: false` 只有在插件页「设置…」勾选「信任此插件」后才生效
  （信任记录按插件 id 存，插件来源哈希变化会自动清除）。
- `tool.invoke("bash", …)` 与 `bridge.exec` 共用 `CommandGuard`：BLOCK 直接 `E_PERM`，
  ASK 会展示完整命令与风险并确认；`confirm:false` / 信任插件**不能跳过** CommandGuard
  的 ASK。
- 与 agent 自身的工具调用**串行**：同一个持久 shell 不能被并发占用。agent 忙时按 `wait` 排队或返回 `E_BUSY`。
- **不能在钩子里调用**（`onBeforeToolCall` / `onAfterToolResult` 等）：钩子持有工具执行锁，
  再调会 `E_REENTRANT`。要"跑完给模型看"用动作 + `inject:true`。
- 结果超过 24k 字符按现有规则落盘（`.toolout/`），返回内容带分段读取提示。
- 每次调用写审计日志（调用方插件、工具名、参数摘要、耗时、退出码、结果大小），插件页可查。
- `inject: true` 会把结果作为一条 **system 备注**追加到下一轮上下文，需要 `hook.context` 权限。
  这条备注**不受** `onContextBuild` 的「总长度不多 20%」限制（那条只约束钩子改写已有 messages），
  但受单独的注入预算：单次 ≤ 8k 字符、每轮合计 ≤ 16k，超出截断并记警告。
  原始结果超过 24k 时注入的是"已落盘 + 分段读取提示"的摘要，不是全文。
- 注入队列**按会话隔离**：入队时记下当前会话 id，只有同一会话的回合会取走它；
  切会话不会把上一段对话的备注带过去（下一轮只取本会话排队的备注，旧会话队列最多保留 8 个）。
- `show: true` 插的是**展示用工具卡**，不是 `role=tool` 消息：
  不写进 jsonl 转录、不参与模型上下文、重开会话不还原；卡片上标注"由插件 `<id>` 调用"，
  与模型自发的工具卡视觉上可区分。因此它不受 §3.2 转录一致性规则约束，也不带 `by: hook` 标记。
  **想让模型看到结果必须同时 `inject: true`**（或让模型自己调工具）；`show` 只给人看。

内置工具是否危险：

| 工具 | 危险 | 说明 |
|---|---|---|
| `bash` `container` | 是 | 执行命令 |
| `write` `edit` | 是 | 改工作区文件 |
| `read` `glob` `grep` | 否 | 只读 |
| `web_search` `web_fetch` | 中 | 外网访问，另需 `net.http` 才能由插件自建请求 |
| `ask_user_question` | 否 | 会弹问题卡片，阻塞到用户回答 |

### 7.10 导航与动作

```js
bridge.nav.push(page)                 // 打开页面（本插件或别人的，按全名）；需 ui.page
bridge.nav.back()                     // 返回上一页
bridge.nav.current()                  // -> {page, params} | null

bridge.action.available(id)           // -> boolean
bridge.action.invoke(id, params)      // -> {ok, value?, error?}；需 action.bind
```

`action.invoke` 是同步的：不能调用本插件的动作，也不能调用当前线程正在执行的插件动作
（自调用/环），否则返回 `{ok:false, error:"E_REENTRANT"}`；跨插件调用正常。
`bridge.tool.invoke` 对"正在执行的插件"的工具同样返回 `E_REENTRANT`。

### 7.11 浮层

应用内浮层（显示在 App 内容之上，权限 `ui.overlay`）：

```js
var id = bridge.overlay.show({
  view: { type: "row", gap: "sm", children: [ /* 复用视图 DSL */ ] },
  x: 24, y: 120,          // 初始位置（dp），默认右上
  draggable: true,        // 只能从带 dragHandle:true 的子节点拖动
  avoidKeyboard: true,    // 键盘弹出时上移
  id: "meter"             // 同 id 会替换已有浮层
});
bridge.overlay.update(id, { view: {...}, x: 40 });
bridge.overlay.hide(id);
bridge.overlay.hideAll();
```

- 同时最多 3 个；必须能关闭（默认右上角有关闭钮，或 `dismissible:false` 时由插件页提供"移除"）。
- App 进入后台时自动隐藏，回到前台由插件重新 `show`。
- 浮层不抢焦点、不覆盖输入框；点浮层外部不穿透（避免误触底下的按钮）。
- 事件、`bind`、`patch` 与普通节点完全一样。

系统级悬浮窗（显示在**其他应用**上层，权限 `overlay.system`）：

```js
bridge.overlay.system.status()      // "granted" | "denied"（含系统权限与插件级授权两层）
bridge.overlay.system.request()     // 跳系统设置页授权（Android 的特殊权限，不是运行时权限）
bridge.overlay.system.show({ view, x, y, draggable: true })
bridge.overlay.system.hide()
```

限制（这些是硬性的，不开放配置）：

- 必须由用户逐插件授权；首次开启弹警告，说明"该插件可以在其他应用上层绘制内容"。
  实现是两层：系统"在其他应用上层显示"权限 + 该插件的 grant（仅本次 / 始终 / 拒绝），
  只打开系统权限不会自动放行所有插件。
- 运行时必须有常驻通知，写明插件名与"关闭"操作；没有通知就拒绝显示。
- 一个插件最多 1 个系统悬浮窗，全设备最多 2 个；最大尺寸为屏幕的 60%，不允许全屏。
- 悬浮窗内不能读取其他应用的内容，也不能模拟点击（只绘制 + 接收自己的触摸）。
- 屏幕锁定、插件被禁用或权限被收回时立即销毁。
- 这个能力本质可被用于钓鱼/遮挡，插件页会把它标为"极高风险"；暂时通过撤销系统权限或
  清除插件授权来全局关闭（专用总开关未提供）。

### 7.12 环境

```js
bridge.display.metrics()          // {width,height,density,orientation,dark,fontScale,reduceMotion,insets}
bridge.theme.get(name)            // 解析当前主题 token：颜色 "#RRGGBB"，尺寸为 dp 数
bridge.capabilities()             // {exec,net,clipboard,notify,adb,...}，按已授权权限返回
bridge.plugins.list()             // [{id,name,version,enabled}]；需 state.read

bridge.time.now()                 // epoch 毫秒；需 env.time
bridge.time.zone()                // "Asia/Shanghai"；需 env.time
bridge.time.format(epoch, "yyyy-MM-dd HH:mm")   // 宿主按本地时区格式化
```

`format` 只支持常用子集：`yyyy` `MM` `dd` `HH` `mm` `ss` `SSS`，以及字面量分隔符；
不支持时区名字/星期/月份名称（避免引入完整格式化引擎的兼容面）。

`metrics()` 的尺寸已经是 dp，不要自己除 density；`insets` 是 `{top,bottom,left,right}`。
`capabilities()` 用于降级：没有 `adb` 就不渲染相关按钮，没有 `net` 就别发请求。
`theme.get()` 只在需要在节点外使用颜色时用（例如生成 SVG），节点样式仍只认 token。

---

## 8. 错误码

| 码 | 触发 | 宿主表现 |
|---|---|---|
| `E_PERM` | 权限未授权 | 工具报错给人看；钩子按 onError；渲染回退 |
| `E_ARGS` | 参数不合法 / 路径越界 | 同上 |
| `E_BUDGET` | 超时或超指令预算 | 同上 |
| `E_BRIDGE` | 桥不可用（如没有 Shizuku） | 同上 |
| `E_LIMIT` | 超出大小/节点数限制 | 同上 |
| `E_STATE` | 未启用时调用 | 记录并忽略 |
| `E_BUSY` | agent 正在跑一轮，且调用方没排队 | 返回错误，不阻塞 |
| `E_REENTRANT` | 在钩子/动作回调里重入工具或动作 | 直接失败，避免自锁 |
| `E_BLOCKED` | 在钩子/事件回调里调用阻塞式 bridge（见 §9.2.1） | 直接失败，不弹窗、不执行 |
| `E_CYCLE` | 动作互相调用形成环，或状态写入触发自身 | 中断并记日志 |
| `E_CANCELLED` | 动作被用户取消 | 按取消处理，不报错弹窗 |
| `E_INTERNAL` | 插件抛异常 | 附原始 message |

## 9. 生命周期与调用约定

### 9.1 插件实例

`onEnable(ctx)` 在插件被启用时调用一次，`onDisable(ctx)` 在禁用/卸载/重载前调用一次。
这两个是**可选函数**，实现了就会被调用，不需要写进 `contributes.hooks`。

- **启用期间有一个常驻作用域**：`onEnable` 里定义的变量、缓存、解析好的配置，后续每次钩子/工具/渲染
  调用都能看到（每次调用仍然是独立的**函数作用域**，不会共享局部变量）。
- 重载脚本、禁用插件、改权限后重新启用 → 常驻作用域销毁重建，`onEnable` 重新跑。
- `onEnable` 的预算 500ms，失败则插件**开不起来**（和自检同一个机制），错误显示在插件页。
- `onDisable` 预算 200ms，异常只记日志；宿主会先取消该插件的定时器、浮层、页面刷新，再调它。
- 不要在 `onEnable` 里做网络/文件/长任务：需要 grant 的调用会触发首次授权弹窗，阻塞启用流程。
  用 `bridge.config.get()`（capability，不弹窗）或把预热放到下一次调用。

```js
var cache = null;

function onEnable(ctx) {
  cache = JSON.parse(bridge.config.get("rules") || "{}");   // 只在启用时读一次
}

function onDisable(ctx) {
  cache = null;
}
```

#### 9.1.1 并发模型（实现约束，不是建议）

QuickJS 的 `JSRuntime` **不是线程安全的**，而且宿主 fork 的 `setTimeoutMs` 把中断截止时间
挂在 **runtime** 上（`JS_SetInterruptHandler` + 单调时钟）。因此 v2 引擎必须是：

> **每个插件一个 runtime + 一条专用执行线程（事件队列）。所有 JS 入口都投递到这条队列串行执行，
> 调用方线程同步等待结果。**

进入队列的入口包括：五个钩子、工具 handler、renderer、插槽/页面渲染、事件回调、动作回调、
浮层回调、`onEnable` / `onDisable`。这样：

- 常驻作用域放在该 runtime 的**全局对象**上，所有入口共享；`onEnable` 之外不保留局部变量。
- 队列里同一时刻只有一个 JS 调用，runtime 级的中断不会互相打断；`budgetMs` 可以逐调用设置。
- 长任务（工具 handler 默认 30s）会阻塞**该插件**的其它入口，但不影响其它插件和宿主主线程。
- 与"工具执行锁串行"的设计天然对齐：`bridge.tool.invoke` 仍受 §7.9 的锁约束。
- **UI 事件回调不要等长任务**：事件有 50ms 预算（§6.2），需要长任务就返回后异步 `patch`。

> 实现状态：`HookEngine` 已是「每插件一个 runtime + 一个常驻 context」：入口脚本只执行一次，
> 所有入口共用该 context 的全局对象，并在 runtime 上串行（`synchronized`）。
> 因 OOM / 超时被重建后，若插件仍处于启用状态，下一次调用会自动重放 `onEnable` 恢复常驻作用域；
> 普通 JS 异常不重建 runtime。桥接层在**每次 bridge 调用期间暂停中断**（`setTimeoutMs(0)`），
> 返回后按本次预算恢复；钩子/事件回调通过 ThreadLocal 标记为 `E_BLOCKED`。
> 尚未实现的是独立的「每插件事件队列线程」——目前所有入口仍由调用方线程在 runtime 锁上串行，
> 但 UI 渲染/事件固定走 `PluginUiHost` 的 `plugin-ui` 线程池（4 线程，慢插件不会堵死其它插件），
> renderer 固定走 `plugin-render` 线程（UI 主线程只读缓存），主线程不会被 JS 占住。
> `bridge.*` 已全部注入；`bridge.adb` 走 Shizuku provider，`tool.invoke` 的 `inject/show`
> 已接线（注入走按会话隔离的 `PluginInjections` 队列，展示卡只进对话流）。

### 9.2 调用约定

- `plugin:reload` 或改文件后重启宿主才会重新编译（脚本按 `path+mtime` 缓存）。
- 除 `onEnable` 建立的常驻作用域外，每次调用不保留局部变量。需要跨会话状态用 `bridge.storage`。
- 沙箱**不提供** `Date` / `Intl.DateTimeFormat`：拿时间必须走 `bridge.time.*`（需 `env.time`），
  否则"时间需要授权"这个约束形同虚设。`Math.random()` 仍然可用。
- 钩子由宿主 agent 线程发起、在插件队列线程执行；不要在钩子里做长耗时操作（预算默认 300ms），
  也**不能调用任何阻塞式 bridge**（见下）。
- 工具 handler 同样投递到插件队列，但超时更长（默认 30s）；调用方同步等待结果。
- 插件卸载/禁用时，宿主会清掉它的 UI 插槽内容；`storage`/`config`/`secret` 保留到用户手动删除插件。
- `bridge.tool.invoke` 与 agent 的工具调用共享同一把执行锁：同一时刻只有一个工具在跑，避免持久 shell 串味。
- **看门狗**：同一插件连续 5 次运行时失败（钩子 / 工具 / renderer / 页面 / 动作）自动禁用，
  并在插件页红字提示（可手动重新启用）；任意一次成功即清零。预算超时也计入一次失败 ——
  QuickJS 中断在 1× 预算处硬停，不存在跑满 3× 的情况。
  **权限/策略拒绝不计入**（`E_PERM` / `E_BLOCKED` / `E_BUDGET`）：用户点「拒绝」或授权超时
  不是插件缺陷，只记日志，下一次调用重新申请；因此连续拒绝不会把插件自动禁用。

#### 9.2.1 阻塞式 bridge 必须暂停 JS 中断

`limits.budgetMs` 实现为 QuickJS 的**墙钟中断**（`JS_SetInterruptHandler`，挂在 runtime 上）。
但下面这些 `bridge.*` 会长时间阻塞在**宿主侧**，JS 一直在等返回：

| 阻塞式调用 | 典型耗时 |
|---|---|
| `bridge.exec` / `bridge.adb.shell` / `bridge.adb.exec` | 默认 30s，`limits.toolMs` 上限 10 分钟 |
| `bridge.tool.invoke` / `bridge.action.invoke` | 同上 / 默认 5s |
| `bridge.http.request` | 默认 15s |
| `bridge.permission.request` / `bridge.ui.confirm` | 最长 60s（等用户点弹窗） |
| `bridge.overlay.system.request` | 等用户从系统设置页返回 |

如果不在这些调用期间暂停中断，JS 会在 bridge 返回前就被打断，抛出 `InternalError: interrupted`
（看起来像脚本死循环，实际是宿主在干活），而且真实错误原因丢失。

宿主规则：

1. 进入阻塞式 bridge **前暂停 interrupt deadline**，返回后按剩余预算重设；
   `budgetMs` 只计 JS 自己执行的墙钟时间。
2. 阻塞调用另受各自的 `timeoutMs` 约束，超时返回 `E_BUDGET` / `E_BRIDGE`，不算脚本超时。
3. **钩子与事件回调里禁止调用阻塞式 bridge**（§3.2 / §6.2）：它们持有工具执行锁或占用主线程，
   弹窗等 60s 会把整轮对话或界面卡死。首次授权应在启用期或插件页预完成。
4. 长任务优先走「JS 提交 → 宿主异步执行 → 回调 `patch`」；全同步 API 只在工具 handler / 动作 /
   页面渲染这类允许等待的入口使用。

- **硬闸门已到位**：v1 钩子引擎已换成 fork 的 QuickJS（`qjsfork/`），每个插件一个 runtime，
  `JS_SetMemoryLimit`（默认 16 MB，`limits.memoryMb` 可调）+ `JS_SetMaxStackSize` +
  `JS_SetInterruptHandler`。OOM/超时都变成 JS 异常，进程存活。
  v2 复用这套引擎时，除接预算外还要完成 9.1.1 的队列化改造与 9.2.1 的中断暂停，
  不是"接个预算"就能跑。

## 10. 调试通道

```
hook:list
hook:enable <id> / hook:disable <id>
hook:test <argsJson>
hook:after <argsJson>
hook:user <text>
hook:assistant <text>
hook:context <messagesJson>
plugin:list
plugin:lint <id>
plugin:test <id> <hook> [ctxJson]
plugin:enable <id>
plugin:disable <id>
plugin:reload
plugin:logs <id> [n]
ui:list
ui:test <slot> [ctxJson]
page:list
page:test <plugin.page> [paramsJson]
nav:list
state:list
state:get <name>
action:list
action:test <id> [paramsJson]
theme:list
theme:apply <pluginId>
theme:reset
grant:list <id>
grant:clear <id> [capability]
grant:allow <id> <capability>
grant:deny <id> <capability>
grant:trust <id> on|off
caps:list <id>
caps:set <id> <capability> on|off
config:list <id>
config:set <id> <key> <value>
bridge:test <id> <ns.method> [argsJson]
adb:status
adb:request
adb:shell <command>
inject:list
inject:add <text>
inject:clear
```

当前实现行为：

| 命令 | 行为 |
|---|---|
| `plugin:list` | 插件 id、apiVersion、启用状态、capabilities/permissions、工具名、渲染器数量、最近错误 |
| `plugin:lint <id>` | manifest/函数实现/能力门控/工具重名检查；不改变启用状态，不启用插件 |
| `plugin:test <id> <fn> [ctxJson]` | 直接调用导出函数（钩子函数也可），返回文本/JSON；不经过 ToolRegistry |
| `plugin:enable <id>` / `plugin:disable <id>` | 等价于插件页开关：enable 先跑自检，失败返回原因 |
| `plugin:reload` | 重扫目录：先对启用插件调 `onDisable`，再重跑其 `onEnable`（失败则自动禁用） |
| `plugin:logs <id> [n]` | 最近插件日志（内存环形缓冲，默认 50 条，最多 200 条） |
| `hook:user <text>` / `hook:assistant <text>` | 走一次 `onUserMessage` / `onAssistantMessage` 链，返回 allow/reason/text |
| `hook:context <messagesJson>` | 走一次 `onContextBuild` 链，返回 allow/reason/messages（`unchanged` 表示未改写） |
| `ui:list` | 列出当前启用的插槽声明与 handler |
| `ui:test <slot> [ctxJson]` | 跑一遍插槽求值链（不渲染到界面），返回接管插件与视图 JSON |
| `page:list` | 列出当前可进入的插件页面（id/title/handler/refresh/scroll） |
| `page:test <plugin.page> [paramsJson]` | 跑一次页面 handler，返回视图 JSON |
| `page:open <plugin.page>` | 直接打开插件页面（验证真实渲染/滚动） |
| `nav:list` | 打印导航模型：分组、page/label/order、当前页 |
| `state:list` | 打印全局状态总线的当前值（排序后的 `name=value`） |
| `state:get <name>` | 读取单个状态值 |
| `action:list` | 列出全局动作 id/label/提供方/超时/是否有确认 |
| `action:test <id> [paramsJson]` | 直接调用提供方 `onAction`，返回执行结果 |
| `theme:list` | 列出已安装主题、当前主题与 skin 数量 |
| `theme:apply <pluginId>` | 应用主题并写入 `active_theme` |
| `theme:reset` | 恢复默认主题并清除 `active_theme` |
| `grant:list <id>` | 列出插件声明的 grant：状态、风险、范围（不弹窗），首行附 `trusted` |
| `grant:clear <id> [capability]` | 清除该插件的全部/单项 grant 记录 |
| `grant:allow/deny <id> <capability>` | 直接写入一条决定，等价于用户点「始终允许 / 拒绝」（调试用） |
| `grant:trust <id> on\|off` | 设置「信任此插件」（危险工具免逐次确认） |
| `caps:list <id>` / `caps:set <id> <cap> on\|off` | 查看/切换 capability 逐项开关（切换后自动 reload） |
| `config:list <id>` / `config:set <id> <key> <value>` | 查看/写入插件配置的用户值 |
| `bridge:test <id> <ns.method> [argsJson]` | 不经过 JS 直接调一次 bridge，验证执行/文件/grant 链路 |
| `adb:status` | Shizuku provider / binder / 授权状态 / 最近错误 |
| `adb:request` | 触发 Shizuku 授权（已授权返回 granted=true） |
| `adb:shell <command>` | 直接走 `AdbBridge.shell`，验证 Shizuku 执行链 |
| `inject:list` | 查看 `tool.invoke({inject:true})` 待注入队列（按会话分组：条数/字符数/预览） |
| `inject:add <text>` / `inject:clear` | 手工入队一条备注 / 清空队列，用于验证下一轮 `system` 注入 |

`plugin:test` 的 `ctxJson` 原样作为 `ctx` 传给函数，例如
`plugin:test echo_tool echoTool '{"args":{"text":"hi"}}'`。走完整工具链路
（包含参数默认值/校验、超时）请用 `tool:echo {"text":"hi"}`。

`plugin:lint` 会检查：manifest schema、入口文件存在、声明的函数是否实现、权限与扩展点是否匹配
（例如声明了 `tools` 却没申请 `tool.register`）、`net.allow` 是否为空却申请了 `net.http`、
动作/状态的 id 是否和已装插件冲突、绑定的状态是否有提供者。

---

## 11. 动作总线

> 多提供者冲突按**动作声明自己的 `priority`** 升序（缺省 0）、同优先级按插件 id 取一个；
> 与钩子链顺序、渲染器优先级互不影响（P2 已拆字段）。

让"主题画的按钮"和"插件提供的能力"解耦：双方只认动作名。

### 11.1 提供方

```json
{
  "permissions": ["action.provide", "exec.guest"],
  "contributes": {
    "actions": [
      {
        "id": "svc.restart",
        "label": "重启服务",
        "params": {
          "type": "object",
          "properties": { "service": { "type": "string" } },
          "required": ["service"]
        },
        "confirm": "确定要重启 {{service}} 吗？",
        "timeoutMs": 20000
      }
    ]
  }
}
```

```js
function onAction(ctx) {          // ctx.action / ctx.params / ctx.source
  var r = bridge.exec("systemctl restart " + ctx.params.service);
  return r.exitCode === 0 ? "已重启 " + ctx.params.service : r.output;
}
```

| 字段 | 说明 |
|---|---|
| `id` | 全局唯一 `[a-z0-9_.]{1,48}`；`pocket.*` 保留给宿主 |
| `label` | 展示用（冲突提示、插件页） |
| `params` | JSON Schema；调用前校验，不合法抛 `E_ARGS` |
| `confirm` | 可选，调用前弹确认框；支持 `{{参数名}}` 插值 |
| `timeoutMs` | 可选，默认 5000，上限 60000 |
| `priority` | 可选，默认 0；同一 id 多提供者时小者胜 |

同一 id 被多个插件提供：`lint` 报错，运行期按**动作自己的 `priority`** 取一个，插件页标出冲突。

### 11.2 使用方

主题/插件在节点上写 `action: "action:svc.restart"`：

```js
{ type: "button", label: "重启",
  action: "action:svc.restart",
  params: { service: "api" },
  fallback: "hide" }        // 没有提供者时隐藏（disable = 灰掉）
```

JS 里直接调用：

```js
bridge.action.available("svc.restart")        // -> boolean
bridge.action.invoke("svc.restart", { service: "api" })
// -> { ok, value?, error? }；需 action.bind；会走 confirm 与参数校验
```

执行中的动作可以报告进度、也可以被取消：

```js
function onAction(ctx) {
  ctx.progress(0.2, "拉取镜像…");
  var a = bridge.exec("docker pull api:latest");
  ctx.progress(0.7, "重启服务…");
  var b = bridge.exec("systemctl restart api");
  return b.exitCode === 0 ? "完成" : b.output;
}
```

- `ctx.progress(pct, text)`：更新按钮上的 pending 文案与进度，节流到 100ms；不影响返回值。
- 用户点 pending 按钮即取消：宿主给动作置取消标记，正在进行的 `bridge.exec` 会杀进程组并抛 `E_CANCELLED`；
  动作应立即返回，不要吞掉这个异常继续跑。
- 动作里再调 `bridge.action.invoke` 会被计数，**嵌套深度上限 3**，超过抛 `E_CYCLE`。

规则：

- 调用在**工作线程**执行，按钮在等待期间显示 pending 态，不阻塞界面；超时或异常返回 `{ok:false, error}`。
- 结果只允许字符串 / 可 JSON 序列化对象；返回字符串会作为 toast 显示（除非调用方自己处理）。
- 调用方必须有 `action.bind`；提供方必须有 `action.provide`。
- 动作不能再调回宿主 UI 线程的 View 操作，需要更新界面就用 `bridge.ui.patch` / `state`。

**实现约定（当前宿主）**

- `contributes.actions` 需要 `action.provide`；同一 id 按**动作自己的 `priority`**（缺省 0）升序、
  再按插件 id 取第一个提供方。
- 提供方函数固定为 `onAction(ctx)`，ctx 为 `{action, params, source}`；`params` 按声明的
  JSON Schema 做必填/常见类型校验。
- `confirm` 支持 `{{参数名}}` 插值，确认后异步执行；结果字符串 → toast，视图节点 →
  替换当前插槽/页面内容。
- 节点写 `action: "action:<id>"`，`params` 放节点属性里；本地函数动作（不带 `action:` 前缀）
  仍然是插件自己的 handler。
- 调试：`action:list` / `action:test <id> [paramsJson]`。

### 11.3 宿主内置动作

| 动作 | 参数 | 说明 |
|---|---|---|
| `pocket.chat.send` | `{text}` | 以用户身份发一条消息 |
| `pocket.chat.new` | — | 新建会话 |
| `pocket.session.open` | `{id}` | 打开会话 |
| `pocket.theme.reset` | — | 恢复默认主题 |
| `pocket.page.open` | `{page}` | 打开插件页面 |

---

## 12. 主题

主题是**纯数据**插件（不带 JS）：token + 贴图 + 字符串 + 组件皮肤，可以没有 `main.js`。
一次只能启用一个主题，设置里永远有「恢复默认主题」。

```json
{
  "id": "midnight",
  "permissions": [],
  "contributes": {
    "theme": {
      "tokens": {
        "color.bg":        { "light": "#FFFFFF", "dark": "#0B0C0E" },
        "color.surface":   { "light": "#F6F7F9", "dark": "#15161A" },
        "color.text":      { "light": "#111315", "dark": "#F2F4F6" },
        "color.brand":     { "light": "#2F6FEB", "dark": "#7C9CFF" },
        "radius.card": 14,
        "space.gutter": 16,
        "font.body": 17,
        "motion.enter": "fade-rise",
        "motion.duration": 180
      },
      "assets":  { "logo": "assets/logo.webp", "icon.send": "assets/send.webp" },
      "strings": { "send": "出发" },
      "skins":   [{ "component": "message.bubble", "view": { "type": "card", "children": [...] } }]
    }
  }
}
```

### 12.1 token 分类

贴图（`assets`）当前只解 **PNG / WebP / JPEG**，单文件 256 KB、累计 4 MB；SVG 需要矢量解析，
宿主明确拒绝并记 warning（规划在 P2 之后，不在本阶段）。

| 前缀 | 内容 | 说明 |
|---|---|---|
| `color.*` | `bg` `surface` `surfaceAlt` `text` `textSecondary` `caption` `border` `brand` `ok` `warn` `danger` `codeBg` `inlineBg` | 支持 `{light,dark}` 两套 |
| `radius.*` | `card` `button` `chip` | 单位 dp，0–32 |
| `space.*` | `xs` `s` `m` `l` `gutter` | 单位 dp，0–48 |
| `font.*` | `body` `title` `caption` `mono` | 字号 sp，10–32 |
| `motion.*` | `enter` `exit` `duration` | 动效名必须来自宿主清单 |
| `icon.*` | `menu` `send` `stop` `settings` `newChat` `back` `copy` | 内置图标名或 `asset://`；原生外壳已接 `menu` / `send` / `stop` / `settings` / `newChat`，`back` / `copy` 预留 |

- 未知 token：忽略 + `lint` 警告；非法值回退默认，不影响启动。
- 主题**不能**改布局结构；要换布局必须走 T3 外壳（`shell: "desktop"`）。
- `skins` 用节点树替换内置组件的外观（如消息气泡、工具卡），**需要 `render.match` 权限**，
  安装时插件页会明确提示"此主题会替换消息与卡片渲染"——这是最容易伪造成系统提示的地方。
- 占位符只开放这些（其它一律原样显示）：
  `{{message.text}}` `{{message.role}}` `{{message.time}}` `{{session.title}}`
  `{{tool.name}}` `{{tool.args}}` `{{tool.result}}` `{{tool.exitCode}}`
  `{{code.lang}}` `{{code.body}}` `{{theme.name}}`
- 主题仍然不跑代码；`skins` 返回 `null` 表示用默认外观。
- `strings` 只能覆盖 UI 标签，不能覆盖 AI 输出。
- 切换主题立即生效，不需要重启：token 重解析，**已渲染的消息气泡也会重渲染换肤**
  （skins 是渲染节点树时套上去的）；主题页显示「恢复默认」。

#### 12.1.1 主题 token 与视图 DSL 的映射

主题包的 `tokens` 是**全局视觉层**（同时影响原生外壳：顶栏、抽屉、输入框）；视图 DSL 里的
`size` / `tone` / `pad` / `gap` / `radius` 是**语义名**，由宿主 `UiTheme` 映射到主题 token，
插件永远不写死颜色或像素：

| DSL 语义 | 主题 token | P1 默认值 |
|---|---|---|
| `size: title` / `body` / `caption` / `mono` | `font.title` / `font.body` / `font.caption` / `font.mono` | 17 / 15 / 12 / 13 sp |
| `tone: primary` / `secondary` / `caption` | `color.text` / `color.textSecondary` / `color.caption` | 现有 `text_primary` 等 |
| `tone: brand` / `ok` / `warn` / `danger` | `color.brand` / `color.ok` / `color.warn` / `color.danger` | 现有 `brand` 等 |
| `card` 的 `tone: surface` / `accent` / `warn` / `danger` | `color.surface` / `brand` 派生 / `warn` / `danger` 派生 | 现有 `layer1` 等 |
| `pad` / `gap` | `space.*` | `none/xs/sm/md/lg` → 0/4/8/12/16 dp |
| `radius` | `radius.card` | 12 dp |
| `code` 底色 | `color.codeBg` | 现有 `code_bg` |

**实现约定（当前宿主）**

- 设置页新增「主题」选择器（点选即应用，另有「恢复默认主题」）；启动按
  `settings.active_theme` 恢复；`theme:list` / `theme:apply <id>` / `theme:reset` 可免 UI 验证。
- `tokens` 通过 `ThemePack` + `PackTokenResolver` 应用到所有 `UiFramework` 面
  （聊天流、插件插槽/页面、nav.list）；原生外壳（顶栏/抽屉/输入框）仍用资源色。
- `skins` 接 `message.bubble` / `tool.card` / `code.card` / `table`：根节点必须是 `column`/`card`，
  内置内容作为最后一个子节点追加；文本属性做占位符替换（清单见上）；需要 `render.match`，
  否则 lint 警告且不生效。`message.bubble` 目前只套助手消息，用户气泡仍是原生 View。
  主题带有 `skins` 时，应用前会弹「会替换消息/卡片外观」的确认；确认按
  manifest+entry 指纹保存，主题代码变化后启动恢复会自动停用 skins，直到重新确认。
- `assets` 可被 skin 的 `src: "asset://<assets 键>"` 引用，宿主解析成
  `asset://<themeId>/<path>` 后交给 `UiAssets`（同样受 256 KB / 4 MB 闸门）。
- `icon.*` 的值为 `asset://<assets 键>` / `asset://<相对路径>` 时覆盖原生图标，
  内置图标名（如 `send`）表示不覆盖；PNG / WebP / JPEG，失败只记 warning。
- `strings` 覆盖原生外壳文案：`input.hint`、`input.hint.hero`、`send`、`stop`、`menu`、
  `settings`、`newChat`、`copy`（同时作为对应控件的 contentDescription）。
- 主题插件可以没有 `main.js`（纯数据）；选择主题不依赖插件启用开关。

P2 阶段 3 已把查表换成可注入的 `TokenResolver`：默认实现 `DefaultTokenResolver` 保持现有视觉，
主题包经 `PackTokenResolver` 逐项覆盖；`UiFramework.applyTheme(pack, dark)` 只重解析、不重建节点树，
`resetTheme()` 恢复默认。`ThemeValidator` 对 `color.*` 做对比度校验（只警告、不拦启用），
40dp 最小触摸目标对所有交互控件生效；`motion.*` 走 `UiMotion` 白名单，单动画 ≤400ms，
系统「移除动画」时统一降级为 `none`。
`assets` 的 `asset://` 由 `UiAssets` 加载（PNG/WebP/JPEG，单文件 256 KB、累计 4 MB；
SVG 本阶段明确拒绝并记 warning）。

### 12.2 与插件/动作的关系

主题只是"摆放 + 外观"，能力来自动作总线和状态总线：

- 按钮 → `action:svc.restart`（提供者可以是任何插件）
- 数字 → `bind: {"value":"svc_ctl.progress"}`
- 新页面 → `contributes.pages`（主题自己的页面用 `ui.page` 权限）

这样主题作者不需要知道装了哪些插件，插件也不需要知道当前主题。


---

## 13. 上下文与变量

插件/主题能拿到的数据分三层，别混用：

| 层 | 形态 | 用途 |
|---|---|---|
| `ctx` | 每次回调传入的对象 | 这一次调用发生了什么（事件、工具、消息、页面参数） |
| `pocket.*` | 宿主发布的具名状态 | 可绑定、可持续显示（占用率、主题、时间、尺寸…） |
| `bridge.*` | 同步函数 | 主动查询/执行（环境、工具、网络、文件…） |

### 13.1 `pocket.*` 完整清单

尺寸单位一律 dp；时间戳为 epoch 毫秒。

| 分组 | 状态 | 类型 | 更新时机 |
|---|---|---|---|
| 显示 | `pocket.display.width` `height` | number | 旋转、分屏、窗口尺寸变化 |
| 显示 | `pocket.display.density` | number | 固定 |
| 显示 | `pocket.display.orientation` | string | 旋转 |
| 显示 | `pocket.display.dark` | boolean | 系统深浅色切换 |
| 显示 | `pocket.display.fontScale` | number | 系统字体缩放变化 |
| 显示 | `pocket.display.reduceMotion` | boolean | 系统无障碍设置变化 |
| 显示 | `pocket.display.insets` | object | 刘海/手势条/键盘变化 |
| 应用 | `pocket.app.state` | string | `foreground` / `background` |
| 应用 | `pocket.app.version` | string | 固定 |
| 应用 | `pocket.app.locale` | string | 系统语言变化 |
| 主题 | `pocket.theme.id` `name` | string | 切换主题 |
| 导航 | `pocket.nav.page` | string | 打开/返回页面 |
| 模型 | `pocket.model.name` | string | 切换模型 |
| 模型 | `pocket.model.vision` | boolean | 切换模型 |
| 会话 | `pocket.session.id` `title` | string | 新建/切换/改名 |
| 会话 | `pocket.session.messages` | number | 追加消息 |
| 上下文 | `pocket.context.tokens` `limit` | number | 每轮结束、压缩 |
| 上下文 | `pocket.context.ratio` | number | 同上 |
| 上下文 | `pocket.context.triggerRatio` | number | 固定（0.6） |
| 上下文 | `pocket.context.summary` | string | 同上 |
| 上下文 | `pocket.context.compacting` | boolean | 压缩开始/结束 |
| 智能体 | `pocket.agent.busy` | boolean | 一轮开始/结束 |
| 智能体 | `pocket.agent.iteration` | number | 每次工具迭代 |
| 运行时 | `pocket.runtime.state` | string | `setup` / `ready` / `error` |
| 运行时 | `pocket.runtime.backend` | string | 固定或切换后端 |
| 运行时 | `pocket.runtime.workspace` | string | 固定 |
| 时间 | `pocket.time.now` | number | 每 30s（需 `env.time`） |
| 时间 | `pocket.time.text` | string | 每 30s（`HH:mm`，需 `env.time`） |
| 时间 | `pocket.time.date` | string | 每 30s（`yyyy-MM-dd`，需 `env.time`） |

规则：

- `pocket.display.*` 免授权（见 6.3），其余分组需要 `state.read`。
- 只读。插件用 `bridge.state.set` 只能写自己命名空间的状态，写 `pocket.*` 抛 `E_PERM`。
- `pocket.display.*` 与 `bridge.display.metrics()` **不需要** `state.read`：布局自适应离不开它，
  而且只暴露 dp 尺寸、方向、深浅色、字体缩放、刘海/手势条 insets，不含任何设备标识。
  读取其它 `pocket.*`（上下文、会话、模型、时间等）仍需 `state.read`（时间另需 `env.time`）。
- 值变化最多每 100ms 推一次；同一帧内多次变化合并。
- 尺寸类不要自己乘 density，宿主已经换算成 dp。

### 13.2 回调 `ctx` 速查

| 回调 | 关键字段 |
|---|---|
| 钩子 `onBeforeToolCall` | `name` `args` `session` `iteration` |
| 钩子 `onAfterToolResult` | `name` `args` `result` `ok` `exitCode` `durationMs` |
| 钩子 `onUserMessage` | `text` `refs[]` `session` |
| 钩子 `onContextBuild` | `messages[]` `model` `tokens` |
| 钩子 `onAssistantMessage` | `text` `session` |
| 工具 handler | `name` `args` `session` `iteration` |
| 渲染器 | `type` `lang` `body` `data` `message{role,id}` |
| 插槽/页面 | `page` `params` `session` `state` |
| 事件 | `event` `node{id,type,data,x,y,w,h}` `pointer` `value` |
| 动作 | `action` `params` `source{plugin,page,message}` |
| 浮层 | `id` `window{system\|app}` `pointer`（拖动时） |

### 13.3 环境查询

```js
bridge.display.metrics()    // {width,height,density,orientation,dark,fontScale,reduceMotion,insets}
bridge.theme.get("color.brand")   // 解析后的值：颜色 #RRGGBB、尺寸为 dp 数
bridge.capabilities()       // {exec,net,clipboard,notify,adb:"shizuku"|"adb"|"none",...}（按已授权权限）
bridge.plugins.list()       // [{id,name,version,enabled}]；需 state.read
```

`capabilities()` 用来做优雅降级：没有 adb 就不显示相关按钮，没有网络就不请求接口。
`theme.get()` 用于需要在节点之外用颜色的地方（比如生成 SVG 图标）；节点样式仍然只用 token。

---

## 14. 暂未支持（P2，记在账单上）

这些是设计上认可、但当前版本不做的能力。写进文档是为了避免插件作者猜：

| 能力 | 现状 / 替代 |
|---|---|
| `onTurnStart` / `onTurnEnd` / `onToolError` 钩子 | 用页面 `refreshMs` + 状态绑定替代；回合级通知还没有 |
| 定时器 / 后台任务（`setInterval`、延时） | 不提供。页面刷新用 `refreshMs`，浮层用状态绑定 |
| 无障碍 `label` / RTL | 节点暂不支持；文本复制已由 `select`（无节点字段）覆盖 |
| 授权「最近使用时间」/ 插件页逐项收回 | 未记录最近使用；逐项收回目前只有 `grant:clear <id> <cap>`，插件页只能整体清除 |
| 图片 `fit` / 宽高比 / 占位图 / 失败态 / 缓存策略 | 只有 `src` 与 `height`；https 图受 `net.http` 限制 |
| 插件安装包**签名**校验 | 目前只做目录内容哈希（用于授权绑定，§7.7），没有作者签名与验签链 |
| 每回合总预算 | 只有单次预算；一轮多次工具调用没有总量闸门 |
| 审计日志脱敏 | 目前原样记录参数，含路径等；密钥类字段会打码但规则未定 |
| 插件作者本地 CLI / 预览 | 只有设备内的 `plugin:lint` / `plugin:test` |
| T3 窗口原语（拖动、缩放、多窗口、任务栏） | UI 框架内核已实现 `WindowLayer`（应用内、最多 3 个、几何持久化、非活动冻结）；`bridge.overlay` 目前用 decorView 上的简化浮层，尚未接入 WindowLayer |
| 系统级悬浮窗 | 已实现最小闭环：特殊权限状态/跳设置、单插件 1 个/全局 2 个、60% 尺寸上限、拖动、常驻通知、禁用即销毁；关闭按钮目前只做“隐藏”，通知上的关闭动作未接 |
| `bridge.adb` | Shizuku provider 已接（`newProcess`，64 KB/60s 限制）；本地无线 adb 配对未接线 |
| 插件运行时硬内存上限 | 已由 QuickJS fork 的 `setMemoryLimit` / `SetInterruptHandler` 提供 |
