// floating_meter —— 应用内浮层示例。
//
// 输入框上方一个开关按钮：打开后出现可拖动的小浮层，实时显示上下文占用；
// 浮层右上角有默认关闭钮，也可以点按钮收回。

function toggleButton(ctx) {
  var open = bridge.storage.get("open", false);
  return {
    type: "button",
    label: open ? "收起悬浮窗" : "悬浮窗",
    action: "toggle",
    variant: "ghost"
  };
}

function toggle(ctx) {
  var open = bridge.storage.get("open", false);
  if (open) {
    bridge.overlay.hide("meter");
    bridge.storage.set("open", false);
    return "已收起";
  }
  bridge.overlay.show({
    id: "meter",
    x: 24, y: 96,
    draggable: true,
    avoidKeyboard: true,
    view: {
      type: "column",
      gap: "xs",
      children: [
        { type: "text", text: "上下文", size: "caption", dragHandle: true },
        { type: "progress", bind: { "value": "pocket.context.ratio" } },
        { type: "text", size: "mono", bind: { "text": "pocket.context.summary" } }
      ]
    }
  });
  bridge.storage.set("open", true);
  return "已打开";
}
