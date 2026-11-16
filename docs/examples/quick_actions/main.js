// quick_actions —— 插件调用宿主工具的示例。
//
// 按钮点下去 → bridge.tool.invoke("bash", ...) → 宿主执行（危险工具会先弹确认），
// show:true 让这次调用在对话流里显示成工具卡，用户看得见是谁、跑了什么。

function quickBar(ctx) {
  return {
    type: "row",
    gap: "sm",
    align: "start",
    children: [
      { type: "button", label: "git 状态", action: "gitStatus", variant: "ghost" },
      { type: "button", label: "跑测试", action: "runTests", variant: "ghost" }
    ]
  };
}

function gitStatus(ctx) {
  var r = bridge.tool.invoke("bash", { command: "git status --short" },
                             { show: true, timeoutMs: 15000 });
  return r.ok ? ("exit " + r.exitCode) : r.error;
}

function runTests(ctx) {
  // inject:true 把结果写进下一轮上下文，方便用户接着说「分析一下失败原因」
  var r = bridge.tool.invoke("bash", { command: "make test" },
                             { show: true, inject: true, timeoutMs: 120000 });
  return r.ok ? "测试结束" : r.error;
}
