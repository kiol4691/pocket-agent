package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 对齐上游 dsh-tool-fs 的 write：创建或整体替换 UTF-8 文本文件。 */
public final class WriteTool implements Tool {

    private final Path workspace;

    public WriteTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "write";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("file_path", new JSONObject().put("type", "string")
                .put("description", "Path to write, resolved by the filesystem backend. "
                        + "Provide `file_path` before `content` in the arguments."));
        props.put("content", new JSONObject().put("type", "string")
                .put("description", "Full UTF-8 text content to write."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("file_path").put("content"));
        return Tool.function("write", "Create or fully replace a UTF-8 text file.", params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        Path target = FsPaths.resolve(workspace, o.optString("file_path", ""));
        boolean created = !Files.exists(target);
        if (Files.isDirectory(target)) {
            return "Error: path is a directory: " + FsPaths.display(workspace, target);
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(target, o.optString("content", "").getBytes(StandardCharsets.UTF_8));
        return "<path>" + FsPaths.display(workspace, target) + "</path>\n<type>file</type>\n<content>\n"
                + (created ? "Created file" : "Updated file") + "\n</content>";
    }
}
