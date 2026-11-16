// deploy_card —— 渲染器示例：把 ```deploy 代码块变成卡片。
//
// AI 输出：
//   ```deploy
//   {"service":"api","env":"staging","status":"ok"}
//   ```
// 渲染成：服务名 + 状态徽标 + 环境 + 「重新部署」按钮。

function renderDeploy(ctx) {
  var d = ctx.data;
  if (!d || !d.service) return null;            // 不是我要的格式，交回默认渲染

  var ok = d.status === "ok";
  return {
    type: "card",
    tone: "surface",
    children: [
      { type: "row", align: "center", children: [
        { type: "text", text: d.service, size: "title" },
        { type: "badge", text: d.status || "unknown", tone: ok ? "ok" : "warn" }
      ]},
      { type: "text", text: "env: " + (d.env || "-"), size: "caption" },
      { type: "divider" },
      { type: "row", align: "end", children: [
        { type: "button", label: "重新部署", action: "redeploy", variant: "primary" }
      ]}
    ],
    // 动作回调里要用到的数据，原样带回
    data: d
  };
}

function redeploy(ctx) {
  var d = ctx.node.data || {};
  var script = bridge.config.get("script") || "./scripts/deploy.sh";
  var r = bridge.exec(script + " " + d.service, { timeoutMs: 120000 });
  if (r.exitCode !== 0) {
    bridge.log("e", "deploy failed: " + r.output.slice(0, 400));
    return "部署失败（exit " + r.exitCode + "）";
  }
  return "已触发 " + d.service + " 部署";
}
