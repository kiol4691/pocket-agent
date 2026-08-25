// safe_guard hook: inspects every tool call before execution and vetoes clearly
// destructive bash commands. Hooks must stay side-effect free.
function onBeforeToolCall(ctx) {
  if (ctx.name !== "bash") return { action: "allow" };
  var cmd = "";
  try {
    var args = JSON.parse(ctx.args);
    cmd = (args && args.command) ? String(args.command) : "";
  } catch (e) {
    return { action: "allow" };
  }
  var patterns = [
    /(^|[^a-zA-Z0-9])rm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)[a-zA-Z]*\s+\/(\s|$)/,
    /mkfs/,
    /dd\s+[^\n]*of=\/dev\/(sd|hd|nvme|mmcblk)/
  ];
  for (var i = 0; i < patterns.length; i++) {
    if (patterns[i].test(cmd)) {
      return {
        action: "veto",
        reason: "destructive command blocked by safe_guard (" + patterns[i].source + ")"
      };
    }
  }
  return { action: "allow" };
}
