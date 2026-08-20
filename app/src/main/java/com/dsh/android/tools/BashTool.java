package com.dsh.android.tools;

import com.dsh.android.runtime.ProrootRuntime;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs shell commands inside the Ubuntu (proroot) environment. */
public final class BashTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final ProrootRuntime runtime;

    public BashTool(ProrootRuntime runtime) {
        this.runtime = runtime;
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
                "Run a bash command in the shared persistent Ubuntu Linux shell (proroot) as root. "
                        + "IMPORTANT: the same long-lived shell is reused across calls, so state PERSISTS: "
                        + "the working directory, exported variables, files and installed packages all carry over. "
                        + "The shell starts in the workspace (/root/workspace); use relative paths in commands, and cd/export "
                        + "as needed to steer state. "
                        + "There are NO command restrictions: you may install any packages with apt-get install -y ... "
                        + "(common tools and python3 are already installed by setup), use git, compile and run programs. "
                        + "Phone storage: 'df -h /' always shows the phone's /data space; if All-Files-Access is granted, "
                        + "the phone's shared storage is mounted at /sdcard (use df -h /sdcard or browse files there). "
                        + "Use sed/python for edits on line ranges rather than huge-output commands, and avoid background "
                        + "long-lived processes that keep writing to the terminal. Set a larger timeout_ms for long installs "
                        + "or builds (up to 1800000).",
                params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String command = o.optString("command", "");
        if (command.trim().isEmpty()) return "Error: empty command";
        long timeout = o.optLong("timeout_ms", DEFAULT_TIMEOUT_MS);
        timeout = Math.max(5_000L, Math.min(timeout, 30 * 60_000L));
        return runtime.runBashPersistent(command, timeout);
    }
}