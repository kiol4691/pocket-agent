// safe_guard 0.3.0 —— 执行工具前拦截明显破坏性的 bash 命令。
//
// 设计取舍：
//   1) 不用正则匹配整条命令行：嵌套量词会灾难性回溯（v0.2.0 issue #2）；
//      也拦不住变形写法。
//   2) 按 shell 元字符分词，再对每个 token 判断；删除类命令（rm / find -delete）
//      先把目标路径**规范化**（// → /、去尾斜杠、折叠 ..），再判断是不是根/家目录。
//   3) 宁可漏报也不乱拦：引号里的内容不当命令（所以 echo "rm -rf /" 放行）。
//      这是"减速带"不是沙箱 —— 存心要删根目录仍能绕过去，真正的兜底是
//      "rootfs 被破坏后应用自动重装"。
//
// 返回：{ action: "allow" } 或 { action: "veto", reason: "..." }

function onBeforeToolCall(ctx) {
  if (ctx.name !== "bash") return { action: "allow" };
  var cmd = (ctx.args && ctx.args.command) ? String(ctx.args.command) : "";
  if (!cmd) return { action: "allow" };

  var tokens = tokenize(cmd);

  for (var i = 0; i < tokens.length; i++) {
    var base = basename(tokens[i]);

    if (base === "mkfs" || base.indexOf("mkfs.") === 0 || base === "wipefs" || base === "fdisk") {
      return veto("文件系统格式化/分区工具已被拦截：" + base);
    }
    if (base === "shred") {
      return veto("shred 会不可恢复地覆写文件，已被拦截");
    }
    if (base === "dd") {
      var rest = tokens.slice(i + 1).join(" ");
      if (/of=\/dev\/(sd|hd|nvme|mmcblk|disk|mapper)/.test(rest)) {
        return veto("dd 直接写块设备已被拦截");
      }
    }
    if (base === "rm") {
      if (scanRm(tokens, i)) return veto("拒绝执行 rm -rf 作用于根目录/家目录");
    }
    if (base === "find") {
      var args = tokens.slice(i + 1);
      var del = args.indexOf("-delete") >= 0
             || (args.indexOf("-exec") >= 0 && args.join(" ").indexOf("rm ") >= 0);
      if (del) {
        for (var k = 0; k < args.length; k++) {
          if (args[k].charAt(0) === "-") continue;
          if (isProtected(args[k])) return veto("拒绝 find 在根目录/家目录上执行删除");
        }
      }
    }
  }
  return { action: "allow" };
}

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

function stripQuotes(t) {
  return String(t).replace(/^["']|["']$/g, "");
}

/** 规范化成绝对路径：// → /、去尾斜杠、折叠 . 与 ..。 */
function normalize(p) {
  var s = stripQuotes(String(p));
  if (!s) return "";
  s = s.replace(/\/+/g, "/");
  if (s.length > 1) s = s.replace(/\/+$/, "");
  var parts = s.split("/");
  var out = [];
  for (var i = 0; i < parts.length; i++) {
    var x = parts[i];
    if (x === "" || x === ".") continue;
    if (x === "..") { if (out.length) out.pop(); continue; }
    out.push(x);
  }
  return "/" + out.join("/");
}

/** 根目录 / 家目录，含 "/*" 这类通配。 */
function isProtected(t) {
  var raw = stripQuotes(String(t));
  if (raw === "~" || raw === "~/" || raw === "~//") return true;
  if (raw === "$HOME" || raw === "$HOME/" || raw === "$HOME//") return true;
  var wildcard = raw.replace(/\/\*+$/, "/");   // "/*"、"//*" 都当 "/"
  return normalize(wildcard) === "/";
}

function scanRm(tokens, i) {
  var recursive = false, force = false;
  var j = i + 1;
  while (j < tokens.length && tokens[j].charAt(0) === "-" && tokens[j].length > 1) {
    var f = tokens[j];
    if (f === "--") { j++; break; }
    if (f === "--recursive") recursive = true;
    else if (f === "--force") force = true;
    else if (f.substring(0, 2) !== "--") {
      if (f.indexOf("r") >= 0 || f.indexOf("R") >= 0) recursive = true;
      if (f.indexOf("f") >= 0) force = true;
    }
    j++;
  }
  if (!recursive || !force) return false;
  for (; j < tokens.length; j++) {
    if (isProtected(tokens[j])) return true;
  }
  return false;
}

function veto(reason) {
  return { action: "veto", reason: "safe_guard: " + reason };
}
