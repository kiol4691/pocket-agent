// guard_rm —— 钩子插件示例：否决危险命令。
//
// 取舍：这是"减速带"不是沙箱。按 shell 元字符分词、规范化路径后再判断，
// 引号里的内容不当命令（echo "rm -rf /" 放行）。存心绕过仍能绕。

var PROTECTED = ["/", "/root", "/root/workspace", "/sdcard", "/storage"];

function onBeforeToolCall(ctx) {
  if (ctx.name !== "bash") return { action: "allow" };
  var cmd = String((ctx.args && ctx.args.command) || "");
  var tokens = tokenize(cmd);

  for (var i = 0; i < tokens.length; i++) {
    var base = basename(tokens[i]);
    if (base === "mkfs" || base.indexOf("mkfs.") === 0 || base === "wipefs") {
      return veto("拒绝执行文件系统格式化：" + base);
    }
    if (base === "dd" && /of=\/dev\/(sd|hd|nvme|mmcblk|disk|mapper)/.test(tokens.join(" "))) {
      return veto("拒绝 dd 直接写块设备");
    }
    if (base === "rm" && rmHitsProtected(tokens, i)) {
      return veto("拒绝递归删除根目录 / 家目录 / 共享存储");
    }
  }
  return { action: "allow" };
}

function veto(reason) {
  return { action: "veto", reason: "guard_rm: " + reason };
}

/** 按空白与 shell 元字符切分，引号内不切。 */
function tokenize(cmd) {
  var out = [], cur = "", quote = "";
  for (var i = 0; i < cmd.length; i++) {
    var c = cmd.charAt(i);
    if (quote) {
      cur += c;
      if (c === quote) quote = "";
      continue;
    }
    if (c === '"' || c === "'") { quote = c; cur += c; continue; }
    if (" \t\n;|&()".indexOf(c) >= 0) {
      if (cur) { out.push(cur); cur = ""; }
      continue;
    }
    cur += c;
  }
  if (cur) out.push(cur);
  return out;
}

function basename(t) {
  var i = t.lastIndexOf("/");
  return i >= 0 ? t.substring(i + 1) : t;
}

function normalize(p) {
  var s = String(p).replace(/^["']|["']$/g, "").replace(/\/+/g, "/");
  if (s.length > 1) s = s.replace(/\/+$/, "");
  var parts = s.split("/"), out = [];
  for (var i = 0; i < parts.length; i++) {
    var x = parts[i];
    if (x === "" || x === ".") continue;
    if (x === "..") { if (out.length) out.pop(); continue; }
    out.push(x);
  }
  return "/" + out.join("/");
}

function rmHitsProtected(tokens, i) {
  var recursive = false;
  var j = i + 1;
  while (j < tokens.length && tokens[j].charAt(0) === "-" && tokens[j].length > 1) {
    var f = tokens[j];
    if (f === "--") { j++; break; }
    if (f.indexOf("r") >= 0 || f.indexOf("R") >= 0) recursive = true;
    j++;
  }
  if (!recursive) return false;
  for (; j < tokens.length; j++) {
    var p = normalize(tokens[j].replace(/\/\*+$/, "/"));
    if (PROTECTED.indexOf(p) >= 0) return true;
  }
  return false;
}
