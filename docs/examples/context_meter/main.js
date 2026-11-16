// context_meter —— 跨插件数据示例：只做展示，数据由宿主发布。
//
// 宿主内置 pocket.context.* 状态，主题/插件用 bind 绑定即可，不需要自己算 token，
// 也不需要和任何"上下文插件"耦合。值变化时宿主只 patch 这一个属性。

function meter(ctx) {
  return {
    type: "row",
    align: "center",
    gap: "sm",
    fallback: "hide",                      // 没有绑定源时整行隐藏
    children: [
      { type: "progress", bind: { "value": "pocket.context.ratio" } },
      { type: "text", size: "caption", bind: { "text": "pocket.context.summary" } }
    ]
  };
}
