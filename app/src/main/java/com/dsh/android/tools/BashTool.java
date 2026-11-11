package com.dsh.android.tools;

import android.content.Context;

import com.dsh.android.runtime.ProrootFallback;
import com.dsh.android.runtime.ProrootRuntime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs shell commands inside the pocketroot Debian environment. */
public final class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final ProrootRuntime runtime;
    private final Context context;

    public BashTool(ProrootRuntime runtime, Context context) {
        this.runtime = runtime;
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "The bash command to run. Relative paths are preferred."));
        props.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "Optional per-call timeout in milliseconds (default 300000; on timeout the shell restarts)."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("command"));
        return Tool.function("bash",
                "Run commands in a bash shell\n"
                        + "* When invoking this tool, the contents of the \"command\" parameter does NOT need to be XML-escaped.\n"
                        + "* You have internet access in this environment: apt-get install, pip install, git clone and curl all work; "
                        + "bash, coreutils, git, curl, wget, python3 and pip are preinstalled.\n"
                        + "* State is persistent across command calls and discussions with the user: the working directory, "
                        + "exported variables, files and installed packages carry over between calls. The shell starts in "
                        + "/root/workspace; prefer relative paths there.\n"
                        + "* To inspect a particular line range of a file, e.g. lines 10-25, try 'sed -n 10,25p /path/to/the/file'.\n"
                        + "* Please avoid commands that may produce a very large amount of output.\n"
                        + "* Please run long lived commands in the background, e.g. 'sleep 10 &' or start a server in the background.\n"
                        + "* Deploying a web app: start the server detached with a log file, e.g. "
                        + "'nohup python3 -m http.server 4680 --directory /root/workspace/site > /root/workspace/server.log 2>&1 &'.\n"
                        + "* A backgrounded server keeps running across tool calls. Verify locally with "
                        + "'curl -s --noproxy \"*\" http://127.0.0.1:<port>' (never use a proxy for localhost).\n"
                        + "* Loopback ports are shared with Android: the site is openable in the phone browser at "
                        + "http://127.0.0.1:<port> while the app is running.\n"
                        + "* Phone storage: 'df -h /' shows the phone's /data partition; when All-Files-Access is granted, "
                        + "the phone's shared storage is mounted at /sdcard.\n"
                        + "* Go (go build / go test / go vet etc.) runs through a slower proroot fallback; "
                        + "it works but does not share the persistent shell's cwd, so 'cd ... && go build' "
                        + "should be one command.",
                params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String command = o.optString("command", "");
        if (command.trim().isEmpty()) return "Error: empty command";
        long timeout = o.optLong("timeout_ms", DEFAULT_TIMEOUT_MS);
        timeout = Math.max(5_000L, Math.min(timeout, 30 * 60_000L));
        // Go 工具链是静态二进制，pocketroot 的 LD_PRELOAD shim 够不着它
        // （faccessat2 撞 seccomp、路径不翻译）。编译类命令自动切到 proroot 兜底。
        if (ProrootFallback.needsFallback(command)) {
            // 标记走的是哪条后端，便于排查（proroot 慢 3–7×，不该被静默触发）。
            return "[proroot-fallback]\n" + ProrootFallback.run(context,
                    runtime.rootfsDir(), runtime.runDir(), command, timeout);
        }
        return runtime.runBashPersistent(command, timeout);
    }
}