// composer_chips —— 输入框上方的横向 chip 条。
//
// 关键点：根节点用 hscroll（内部 wrap_content 横排），chip 用 card + row 组合；
// 数量超过一屏时左右滑动，而不是把输入框顶宽或裁掉。

function chips(ctx) {
  var labels = ["写代码", "读文件", "跑测试", "查日志", "部署", "数据库"];
  var icons = ["⌘", "▤", "▶", "≡", "⇧", "⛁"];
  var out = [];
  for (var i = 0; i < labels.length; i++) {
    out.push({
      type: "card",
      tone: "surface",
      radius: "lg",
      pad: "sm",
      on: { tap: "pick" },
      children: [{
        type: "row",
        gap: "sm",
        align: "center",
        children: [
          { type: "text", text: icons[i], size: "caption" },
          { type: "text", text: labels[i], size: "caption" }
        ]
      }]
    });
  }
  return { type: "hscroll", gap: "sm", align: "start", children: out };
}

function pick(ctx) {
  bridge.log("chip tapped");
  return "chip";
}
