package com.dsh.android.tools;

import android.content.Context;

import com.dsh.android.runtime.ContainerRunner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 在 OCI 镜像里执行命令 —— 没有 docker daemon 时的 "docker run"。
 *
 * 语义说明（会原样写进 schema 的 description，模型看得到）：
 *  - 镜像用 pimg 拉取（bash 里跑 `pimg pull debian:bookworm-slim`）；`pimg ls` 看有哪些
 *  - 只支持 glibc 系（Debian 系发行版），Alpine/musl 起不来
 *  - 没有 namespace：所有镜像共用网络栈，端口必须 >= 1024
 *  - 写入落在镜像目录里，会持久化（相当于 docker run 后 commit）
 */
public final class ContainerTool implements Tool {

    private static final long DEFAULT_TIMEOUT_MS = 300_000L;

    private final Context context;

    public ContainerTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String name() {
        return "container";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("image", new JSONObject()
                .put("type", "string")
                .put("description", "Image name as shown by `pimg ls`, e.g. debian_bookworm-slim."));
        props.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "Shell command to run inside the image (via /bin/sh -c)."));
        props.put("timeout_ms", new JSONObject()
                .put("type", "number")
                .put("description", "Optional timeout in milliseconds (default 300000)."));
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", new JSONArray().put("image").put("command"));
        return Tool.function("container",
                "Run a command inside an OCI image (docker-run equivalent).\n"
                        + "* Images: `pimg pull <repo>:<tag>` in bash, `pimg ls` to list.\n"
                        + "* Only Debian-family glibc images work; Alpine/musl is not supported.\n"
                        + "* No namespaces: services share the host network, so bind ports >= 1024.\n"
                        + "* Changes persist in the image directory (like run + commit).\n"
                        + "* For long-running services use: nohup CMD > /tmp/x.log 2>&1 & then read the log.",
                params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String image = a.optString("image", "").trim();
        String command = a.optString("command", "").trim();
        long timeout = (long) a.optDouble("timeout_ms", DEFAULT_TIMEOUT_MS);
        if (image.isEmpty()) {
            return "ERROR: missing image. Available:\n" + ContainerRunner.list(context);
        }
        if (command.isEmpty()) return "ERROR: missing command";
        return ContainerRunner.run(context, image, command, timeout);
    }
}
