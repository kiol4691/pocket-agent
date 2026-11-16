// segmented —— 点击位置示例：一整行当三段用，靠 pointer.localX 判断点了哪段。
//
// 没有连续移动事件，也不暴露原始 MotionEvent；只给这一次点击的相对坐标。

var LABELS = ["日", "周", "月"];

function control(ctx) {
  var active = bridge.storage.get("seg", 0);
  var children = [];
  for (var i = 0; i < LABELS.length; i++) {
    children.push({
      type: "text",
      text: LABELS[i],
      size: "body", tone: i === active ? "brand" : "caption"
    });
  }
  return {
    type: "row",
    id: "seg",
    gap: "lg",
    align: "start",
    on: { tap: "pick" },
    children: children
  };
}

function pick(ctx) {
  var p = ctx.pointer;
  if (!p || !p.w) return undefined;
  var i = Math.min(LABELS.length - 1, Math.floor(p.localX / p.w * LABELS.length));
  bridge.storage.set("seg", i);
  return control(ctx);          // 返回新节点树，宿主就地替换这一块
}
