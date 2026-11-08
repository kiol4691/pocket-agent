package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 对齐上游 dsh-tool-fs 的 read：行号输出、offset/limit、官方 envelope。 */
public final class ReadTool implements Tool {

    private static final int READ_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_BYTES = 32 * 1024;

    private final Path workspace;

    public ReadTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("file_path", new JSONObject().put("type", "string")
                .put("description", "Path to read, resolved by the filesystem backend."));
        props.put("offset", new JSONObject().put("type", "number")
                .put("description", "1-based first line to return. Defaults to 1."));
        props.put("limit", new JSONObject().put("type", "number")
                .put("description", "Maximum number of lines to return. Defaults to 2000."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("file_path"));
        return Tool.function("read", "Read a UTF-8 text file and return line-numbered content.", params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String fp = o.optString("file_path", "");
        Path target = FsPaths.resolve(workspace, fp);
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return "Error: not found or is a directory: " + FsPaths.display(workspace, target);
        }
        int offset = o.has("offset") && !o.isNull("offset") ? o.optInt("offset", 1) : 1;
        int limit = o.has("limit") && !o.isNull("limit") ? o.optInt("limit", READ_LIMIT) : READ_LIMIT;
        if (offset < 1) throw new IllegalArgumentException("offset must be a positive integer");
        if (limit < 1) throw new IllegalArgumentException("limit must be a positive integer");
        if (limit > READ_LIMIT) throw new IllegalArgumentException("limit must be less than or equal to " + READ_LIMIT);

        byte[] data = com.dsh.android.util.IOUtil.readBytes(target.toFile(), 512 * 1024);
        String content = new String(data, StandardCharsets.UTF_8);
        // 官方是逐行 readline：文件末尾的换行不算一行，这里对齐掉多余的空行
        java.util.ArrayList<String> all = new java.util.ArrayList<>(
                java.util.Arrays.asList(content.split("\n", -1)));
        if (!all.isEmpty() && all.get(all.size() - 1).isEmpty()) all.remove(all.size() - 1);
        int total = all.size();
        StringBuilder body = new StringBuilder();
        int bytes = 0;
        int end = Math.max(0, offset - 1);
        boolean capped = false;
        int shown = 0;
        for (int i = offset - 1; i < total && shown < limit; i++) {
            String line = all.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "... (line truncated to " + MAX_LINE_LENGTH + " chars)";
            }
            int cost = line.getBytes(StandardCharsets.UTF_8).length + (shown > 0 ? 1 : 0);
            if (bytes + cost > MAX_BYTES) {
                capped = true;
                break;
            }
            bytes += cost;
            if (shown > 0) body.append('\n');
            body.append(i + 1).append(": ").append(line);
            end = i + 1;
            shown++;
        }
        String footer;
        if (capped) {
            footer = "(Output capped. Showing lines " + offset + "-" + end + ". Use offset=" + (end + 1) + " to continue.)";
        } else if (end < total) {
            footer = "(Showing lines " + offset + "-" + end + " of " + total + ". Use offset=" + (end + 1) + " to continue.)";
        } else {
            footer = "(End of file - total " + total + " lines)";
        }
        String block = shown > 0 ? body + "\n\n" + footer : footer;
        return "<path>" + FsPaths.display(workspace, target) + "</path>\n<type>file</type>\n<content>\n"
                + block + "\n</content>";
    }
}
