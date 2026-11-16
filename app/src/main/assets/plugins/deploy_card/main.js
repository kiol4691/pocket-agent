// deploy_card 0.1.0 —— renderers 示例（PLUGIN_API §4）。
//
// 返回值必须是视图 DSL 节点（docs/UI_FRAMEWORK.md 第 2 节）；返回 null 表示不接管、
// 走宿默认渲染。块内容不需要完整：解析失败也返回卡片，避免示例看起来"没生效"。

function renderDeploy(ctx) {
  var body = String(ctx.body || "");
  var env = "";
  var status = "unknown";
  var lines = body.split("\n");
  for (var i = 0; i < lines.length; i++) {
    var m = /^\s*(env|status)\s*[:=]\s*(.+)$/i.exec(lines[i]);
    if (!m) continue;
    if (m[1].toLowerCase() === "env") env = m[2].trim();
    else status = m[2].trim();
  }
  if (!env) env = "未指定";

  var failed = /fail|error|失败/i.test(status);
  var running = /running|progress|进行/i.test(status);
  return {
    type: "card",
    tone: failed ? "danger" : running ? "warn" : "surface",
    pad: "md",
    children: [
      {
        type: "row", align: "center",
        children: [
          { type: "text", text: "Deploy · " + env, size: "body", grow: true },
          { type: "badge", text: status, tone: failed ? "danger" : running ? "warn" : "ok" }
        ]
      },
      { type: "text", text: body, size: "caption", tone: "caption" }
    ]
  };
}

function renderStatus(ctx) {
  var text = String(ctx.body || "").replace(/^\[status\]\s*/, "");
  return {
    type: "card", tone: "accent", pad: "sm",
    children: [{ type: "text", text: text, size: "body" }]
  };
}
