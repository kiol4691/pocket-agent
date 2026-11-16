// status_panel 0.1.0 —— UI 插槽 + 自定义页面示例（PLUGIN_API §5 / §6）。
//
// 插槽 handler 返回一个视图节点；节点上的 action: "fn" 在点击时回调同名函数。
// 返回字符串 → toast，返回节点 → 替换当前插槽/页面内容。

var ticks = 0;
var lastAction = "（还没点过）";
var label = "插件面板";
var deviceName = "";
var savedTicks = 0;

function onEnable(ctx) {
  ticks = 0;
  lastAction = "（还没点过）";
  // bridge.* 的已实现子集：config / storage / device / log / state
  label = String(bridge.config.get("label", "插件面板"));
  deviceName = String(bridge.device.model());
  savedTicks = Number(bridge.storage.get("savedTicks", 0)) || 0;
  bridge.log("i", "status_panel enabled on " + deviceName);
  bridge.state.set("status_panel.ticks", 0);
}

function onDisable(ctx) {
  ticks = 0;
}

function renderHero(ctx) {
  return buildHero(ctx);
}

function refreshHero(ctx) {
  ticks++;
  lastAction = "已刷新 " + ticks + " 次";
  return buildHero(ctx);
}

function buildHero(ctx) {
  var session = ctx.session || {};
  return {
    type: "card", tone: "surface", pad: "md",
    children: [
      {
        type: "row", align: "center",
        children: [
          { type: "text", text: "插件面板", size: "caption", tone: "caption", grow: true },
          { type: "badge", text: "v0.2", tone: "ok" }
        ]
      },
      {
        type: "text",
        text: session.title ? ("会话: " + session.title) : "首页插槽 home.hero",
        size: "body"
      },
      { type: "text", text: lastAction, size: "caption", tone: "caption" },
      { type: "button", label: "刷新插槽", action: "refreshHero", variant: "ghost" }
    ]
  };
}

function renderStatusPage(ctx) {
  ticks++;
  // 发布插件状态：主题/页面可以 bind: {"text":"status_panel.ticks"}。
  bridge.state.set("status_panel.ticks", ticks);
  bridge.storage.set("savedTicks", ticks);
  return {
    type: "column", gap: "md",
    children: [
      { type: "text", text: "状态面板", size: "title" },
      {
        type: "text",
        text: "插件: " + ((ctx.page && ctx.page.plugin) || "?"),
        size: "caption", tone: "caption"
      },
      {
        type: "card", tone: "surface", pad: "md",
        children: [
          {
            type: "row", align: "center",
            children: [
              { type: "text", text: "刷新次数", size: "body", grow: true },
              { type: "text", text: String(ticks), size: "mono" }
            ]
          },
          {
            type: "text",
            text: "页面每 5 秒自动刷新；点下面的按钮会 toast。",
            size: "caption", tone: "caption"
          },
          // bind 由宿主注入实时状态（PLUGIN_API §6.3）：不需要自己算，也不需要刷新页面。
          { type: "text", text: "上下文", size: "caption", tone: "caption" },
          { type: "text", bind: { "text": "pocket.context.summary" }, size: "mono" },
          { type: "text", text: "模型", size: "caption", tone: "caption" },
          { type: "text", bind: { "text": "pocket.model.name" }, size: "mono", fallback: "hide" },
          { type: "text", text: "会话", size: "caption", tone: "caption" },
          { type: "text", bind: { "text": "pocket.session.title" }, size: "mono", fallback: "hide" },
          { type: "text", text: "运行中", size: "caption", tone: "caption" },
          { type: "text", bind: { "text": "pocket.agent.busy" }, size: "mono" },
          { type: "text", text: "插件状态", size: "caption", tone: "caption" },
          { type: "text", bind: { "text": "status_panel.ticks" }, size: "mono" },
          { type: "text", text: "设备 / 上次存储", size: "caption", tone: "caption" },
          { type: "text", text: deviceName + " · " + savedTicks, size: "caption", tone: "caption" }
        ]
      },
      { type: "button", label: "点我", action: "ping" }
    ]
  };
}

function ping(ctx) {
  return "pong · ticks=" + ticks;
}

// 全局动作（contributes.actions）：节点写 action: "action:status_panel.ping"，
// 宿主按 id 找到提供方并回调 onAction(ctx)。返回字符串会 toast。
function onAction(ctx) {
  var p = ctx.params || {};
  return "pong(action): " + String(p.text == null ? "" : p.text);
}

// nav.list 由宿主渲染：数据来自「聊天 + 已启用页面 + contributes.nav」合成的导航模型。
// variant: sidebar | tabs | grid；group 为空表示全部。
function renderNavPage(ctx) {
  return {
    type: "column", gap: "md",
    children: [
      { type: "text", text: "导航菜单", size: "title" },
      {
        type: "text",
        text: "点击条目跳转；长按文字可复制。",
        size: "caption", tone: "caption"
      },
      { type: "text", bind: { "text": "pocket.nav.page" }, size: "caption", tone: "caption" },
      { type: "text", bind: { "text": "pocket.time.text" }, size: "mono" },
      {
        type: "button", label: "Ping 动作",
        action: "action:status_panel.ping", params: { text: "hi" }, variant: "ghost"
      },
      { type: "nav.list", variant: "sidebar" }
    ]
  };
}

// ------------------------------------------------------------------ bridge 演示
// 首次使用某个 grant 会弹「仅本次 / 始终允许 / 拒绝」；拒绝后可在设置里清除授权。

function renderBridgePage(ctx) {
  var status = "";
  try {
    var ls = bridge.location.status();
    status = "exec=" + bridge.permission.status("exec.guest")
      + " · fs=" + bridge.permission.status("fs.workspace")
      + " · tool=" + bridge.permission.status("tool.invoke")
      + " · overlay=" + bridge.overlay.system.status()
      + " · loc=" + (ls.serviceEnabled ? "on" : "off")
      + "/" + (ls.granted ? "granted" : "notAsked");
  } catch (e) {
    status = "status 失败: " + e;
  }
  return {
    type: "column", gap: "sm",
    children: [
      { type: "text", text: "Bridge 演示", size: "title" },
      {
        type: "text",
        text: "逐个按钮验证 bridge.*；未授权的第一次调用会弹权限网关。",
        size: "caption", tone: "caption"
      },
      { type: "card", tone: "surface", pad: "md", children: [
        { type: "text", text: status, size: "caption", tone: "caption" }
      ]},
      { type: "card", tone: "surface", pad: "md", children: [
        { type: "text", id: "bridge-patch-target", text: "patch 目标", size: "mono" }
      ]},
      { type: "button", label: "ui.patch / bounds", action: "bridgePatch", variant: "ghost" },
      { type: "button", label: "ui.toast", action: "bridgeToast", variant: "ghost" },
      { type: "button", label: "ui.confirm（阻塞）", action: "bridgeConfirm", variant: "ghost" },
      { type: "button", label: "clipboard 写 + 读", action: "bridgeClipboard", variant: "ghost" },
      { type: "button", label: "notify", action: "bridgeNotify", variant: "ghost" },
      { type: "button", label: "exec: echo", action: "bridgeExec", variant: "ghost" },
      { type: "button", label: "fs: 写 + 读工作区", action: "bridgeFs", variant: "ghost" },
      { type: "button", label: "http: httpbin", action: "bridgeHttp", variant: "ghost" },
      { type: "button", label: "location.get", action: "bridgeLocation", variant: "ghost" },
      { type: "button", label: "tool: echo", action: "bridgeTool", variant: "ghost" },
      { type: "button", label: "action: echo_tool.say", action: "bridgeAction", variant: "ghost" },
      { type: "row", gap: "sm", children: [
        { type: "button", label: "显示浮层", action: "bridgeOverlay", grow: true, variant: "ghost" },
        { type: "button", label: "隐藏浮层", action: "bridgeOverlayHide", grow: true, variant: "ghost" }
      ]},
      { type: "row", gap: "sm", children: [
        { type: "button", label: "系统浮层", action: "bridgeSystemOverlay", grow: true, variant: "ghost" },
        { type: "button", label: "关闭系统浮层", action: "bridgeSystemOverlayHide", grow: true, variant: "ghost" }
      ]},
      { type: "button", label: "permission.describe(exec.guest)", action: "bridgePerm", variant: "ghost" },
      { type: "button", label: "nav.push 状态页", action: "bridgeNav", variant: "ghost" },
      { type: "button", label: "宿主信息", action: "bridgeInfo", variant: "ghost" }
    ]
  };
}

function bridgeToast(ctx) {
  bridge.ui.toast("bridge.ui.toast ok");
  return "toast 已发送";
}

function bridgePatch(ctx) {
  var ok = bridge.ui.patch("bridge-patch-target", { text: "patch ok " + ticks });
  var b = bridge.ui.bounds("bridge-patch-target");
  return "patch=" + ok + " bounds=" + JSON.stringify(b);
}

function bridgeConfirm(ctx) {
  var ok = bridge.ui.confirm("Bridge 确认", "这是 bridge.ui.confirm 的阻塞确认框。");
  return ok ? "confirm: ok" : "confirm: cancel";
}

function bridgeClipboard(ctx) {
  var ok = bridge.clipboard.write("pocket bridge " + ticks);
  var text = bridge.clipboard.read();
  return "write=" + ok + " read=" + text;
}

function bridgeNotify(ctx) {
  var ok = bridge.notify("Bridge 通知", "来自 status_panel");
  return "notify=" + ok;
}

function bridgeExec(ctx) {
  var r = bridge.exec("echo bridge-exec-ok");
  return JSON.stringify(r);
}

function bridgeFs(ctx) {
  bridge.fs.write("bridge-demo.txt", "hello from bridge " + ticks);
  var list = bridge.fs.list(".");
  var text = bridge.fs.read("bridge-demo.txt");
  return "read=" + text + " files=" + list.length;
}

function bridgeHttp(ctx) {
  try {
    var r = bridge.http.request({
      method: "GET", url: "https://httpbin.org/get", timeoutMs: 15000
    });
    return "http=" + r.status + " bytes=" + String(r.body).length;
  } catch (e) {
    return "http 失败: " + e;
  }
}

function bridgeLocation(ctx) {
  try {
    var r = bridge.location.get({ timeoutMs: 15000, highAccuracy: true });
    return "location=" + Number(r.latitude).toFixed(5) + ","
      + Number(r.longitude).toFixed(5)
      + " acc=" + Math.round(Number(r.accuracy)) + "m "
      + String(r.provider) + (r.stale ? " (stale)" : "");
  } catch (e) {
    return "location 失败: " + e;
  }
}

function bridgeTool(ctx) {
  var r = bridge.tool.invoke("echo", { text: "from-bridge" });
  return JSON.stringify(r);
}

function bridgeAction(ctx) {
  var r = bridge.action.invoke("echo_tool.say", { text: "from-bridge" });
  return JSON.stringify(r);
}

function bridgeOverlay(ctx) {
  var id = bridge.overlay.show({
    id: "demo",
    x: 24, y: 160,
    draggable: true,
    view: {
      type: "card", tone: "surface", pad: "md",
      children: [
        { type: "text", text: "插件浮层", size: "body" },
        { type: "text", text: "可拖动，点关闭按钮消失", size: "caption", tone: "caption" },
        { type: "button", label: "隐藏", action: "bridgeOverlayHide", variant: "ghost" }
      ]
    }
  });
  return "overlay id=" + id;
}

function bridgeOverlayHide(ctx) {
  bridge.overlay.hide("demo");
  return "overlay hidden";
}

function bridgeSystemOverlay(ctx) {
  try {
    var st = bridge.overlay.system.status();
    if (st !== "granted") {
      bridge.overlay.system.request();
      return "系统浮层权限: " + st + "（已跳系统设置，授权后重试）";
    }
    bridge.overlay.system.show({
      x: 24, y: 180, draggable: true,
      view: {
        type: "card", tone: "surface", pad: "md",
        children: [
          { type: "text", text: "系统浮层", size: "body" },
          { type: "text", text: "显示在其他应用上层", size: "caption", tone: "caption" },
          { type: "button", label: "关闭", action: "bridgeSystemOverlayHide", variant: "ghost" }
        ]
      }
    });
    return "系统浮层已显示";
  } catch (e) {
    return "系统浮层失败: " + e;
  }
}

function bridgeSystemOverlayHide(ctx) {
  bridge.overlay.system.hide();
  return "系统浮层已关闭";
}

function bridgePerm(ctx) {
  return JSON.stringify(bridge.permission.describe("exec.guest"));
}

function bridgeNav(ctx) {
  var ok = bridge.nav.push("status_panel.status");
  var cur = bridge.nav.current();
  return "push=" + ok + " current=" + JSON.stringify(cur);
}

function bridgeInfo(ctx) {
  return JSON.stringify({
    usage: bridge.context.usage(),
    session: bridge.session.info(),
    runtime: bridge.runtime.status(),
    display: bridge.display.metrics(),
    brand: bridge.theme.get("color.brand"),
    caps: bridge.capabilities(),
    plugins: bridge.plugins.list()
  });
}
