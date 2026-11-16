// status_dashboard —— 自定义页面 / 仪表盘示例。
//
// 数据来自宿主内置状态（pocket.context.*），动作交给动作总线（svc.restart 由别的插件提供），
// 所以这个页面不需要知道装了哪些插件。

function renderRuntime(ctx) {
  return {
    type: "column",
    gap: "md",
    children: [
      { type: "row", align: "center", children: [
        { type: "text", text: "上下文", size: "caption" },
        { type: "text", bind: { "text": "pocket.context.summary" }, size: "mono" }
      ]},
      { type: "progress", bind: { "value": "pocket.context.ratio" } },

      { type: "text", text: "最近 12 次采样 token", size: "caption" },
      { type: "bars", values: sample(12), height: 80 },

      { type: "divider" },
      { type: "row", align: "center", children: [
        { type: "text", text: "服务", size: "body" },
        { type: "button", label: "重启 api",
          action: "action:svc.restart", params: { service: "api" },
          variant: "ghost", fallback: "disable" }
      ]}
    ]
  };
}

/** 每次页面刷新（refreshMs 到点或用户点刷新）采样一次 token 数，保留最近 n 个。 */
function sample(n) {
  var list = bridge.storage.get("tokens", []);
  var usage = bridge.context.usage();
  if (usage) list.push(usage.tokens);
  if (list.length > n) list = list.slice(list.length - n);
  bridge.storage.set("tokens", list);
  return list;
}
