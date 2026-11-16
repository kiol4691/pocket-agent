// echo_tool 0.1.1 —— contributes.tools + 生命周期/动作示例（PLUGIN_API §3.1 / §9.1 / §11）。
//
// onEnable 里建立的变量是常驻作用域：后续每次工具调用都能看到，但每次调用仍然是
// 独立的函数作用域。这个插件没有任何副作用：不读文件、不联网，只回显参数。

var calls = 0;
var prefix = "";

function onEnable(ctx) {
  calls = 0;
  prefix = "echo:";
}

function onDisable(ctx) {
  calls = 0;
  prefix = "";
}

function echoTool(ctx) {
  var args = ctx.args || {};
  var text = String(args.text == null ? "" : args.text);
  if (args.upper) text = text.toUpperCase();
  calls++;
  return {
    echo: prefix + text,
    upper: !!args.upper,
    calls: calls,
    session: ctx.session && ctx.session.id ? ctx.session.id : ""
  };
}

// 全局动作 echo_tool.say：bridge.action.invoke 可以跨插件调用它。
function onAction(ctx) {
  var p = ctx.params || {};
  calls++;
  return "echo(action): " + String(p.text == null ? "" : p.text) + " #" + calls;
}
