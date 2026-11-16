// copy_raw —— 界面插槽示例：给每条 AI 消息加一个复制按钮。

function footer(ctx) {
  // 只给 AI 消息加，用户消息不加
  if (!ctx.message || ctx.message.role !== "assistant") return null;
  return {
    type: "row",
    align: "end",
    children: [
      { type: "button", label: "复制原文", action: "copyRaw", variant: "ghost" }
    ]
  };
}

function copyRaw(ctx) {
  bridge.clipboard.write(ctx.message.text || "");
  return "已复制";
}
