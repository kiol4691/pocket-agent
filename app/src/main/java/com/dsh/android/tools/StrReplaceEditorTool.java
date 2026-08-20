package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Aider-style str_replace_editor (view / create / str_replace / insert) scoped to the agent
 *  workspace at /root/workspace. Replicates the minimal-mode editor from deepseek-harness
 *  (tool-str-replace-editor): commands receive absolute guest paths under /root/workspace. */
public final class StrReplaceEditorTool implements Tool {

    private static final String ROOT = "/root/workspace";
    private static final int DEFAULT_MAX_OUTPUT_CHARS = 16000;

    private final Path workspace;

    public StrReplaceEditorTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "str_replace_editor";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("command", new JSONObject()
                .put("type", "string")
                .put("description", "The operation to perform")
                .put("enum", new JSONArray().put("view").put("create").put("str_replace").put("insert")));
        props.put("path", new JSONObject()
                .put("type", "string")
                .put("description", "Absolute path to the file or directory, e.g. /root/workspace/main.py"));
        props.put("file_text", new JSONObject()
                .put("type", "string")
                .put("description", "Required for create: the full content of the new file."));
        props.put("old_str", new JSONObject()
                .put("type", "string")
                .put("description", "Required for str_replace: the existing text to replace; it must occur exactly once in the file."));
        props.put("new_str", new JSONObject()
                .put("type", "string")
                .put("description", "Required for str_replace (the replacement text) and insert (the text to insert)."));
        props.put("view_range", new JSONObject()
                .put("type", "array")
                .put("description", "For view: 1-indexed [startLine, endLine] to display; endLine -1 means through the end of the file.")
                .put("items", new JSONObject().put("type", "number")));
        props.put("insert_line", new JSONObject()
                .put("type", "number")
                .put("description", "For insert: 0-indexed line number AFTER which to insert new_str (-1 inserts at the top of the file)."));
        props.put("maxOutputChars", new JSONObject()
                .put("type", "number")
                .put("description", "Maximum characters to return from a view (default 16000)."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("command").put("path"));
        return Tool.function("str_replace_editor",
                "View, create, replace and insert text in files inside the agent workspace (/root/workspace). "
                        + "Paths are absolute guest paths under /root/workspace. This is the preferred way to read and edit "
                        + "source files: first use view to inspect the exact text, then use str_replace with a unique old_str "
                        + "(old_str must match exactly and appear only once; otherwise it fails). Files made here also appear "
                        + "in the persistent bash shell at their guest path.",
                params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String cmd = o.optString("command", "");
        Path host = resolveGuest(o.optString("path", "/root/workspace"));
        switch (cmd) {
            case "view":
                return view(host, o);
            case "create":
                return create(host, o);
            case "str_replace":
                return strReplace(host, o);
            case "insert":
                return insert(host, o);
            default:
                return "Error: unknown command '" + cmd + "'. Valid: view, create, str_replace, insert";
        }
    }

    private Path resolveGuest(String guest) throws IOException {
        String p = guest == null ? "" : guest.replace('\\', '/');
        if (!p.startsWith(ROOT)) {
            throw new IOException("Path must be an absolute path under " + ROOT + ": " + guest);
        }
        String rel = p.length() == ROOT.length() ? "" : p.substring(ROOT.length());
        while (rel.startsWith("/")) rel = rel.substring(1);
        Path host = workspace.resolve(rel).normalize();
        if (!host.startsWith(workspace)) throw new IOException("Path escapes workspace: " + guest);
        return host;
    }

    private String guestPath(Path host) {
        Path rel = workspace.relativize(host);
        return rel.toString().isEmpty() ? ROOT : ROOT + "/" + rel.toString().replace('\\', '/');
    }

    private String view(Path host, JSONObject o) throws IOException {
        if (!Files.exists(host)) return "Error: path does not exist: " + guestPath(host);
        if (Files.isDirectory(host)) return listDir(host);
        int maxChars = Math.max(1, o.optInt("maxOutputChars", DEFAULT_MAX_OUTPUT_CHARS));
        String content = new String(Files.readAllBytes(host), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int total = content.endsWith("\n") ? lines.length - 1 : lines.length;
        if (total < 0) total = 0;
        int start = 1, end = total;
        JSONArray range = o.optJSONArray("view_range");
        if (range != null && range.length() >= 2) {
            start = range.optInt(0, 1);
            end = range.optInt(1, -1);
            if (start < 1) start = 1;
            if (end == -1 || end > total) end = total;
            if (start > end) return "Error: view_range start (" + start + ") is after end (" + end + ")";
        }
        StringBuilder sb = new StringBuilder();
        boolean clipped = false;
        for (int i = start; i <= end; i++) {
            sb.append(i).append(": ").append(lines[i - 1]).append('\n');
            if (sb.length() >= maxChars) {
                clipped = true;
                break;
            }
        }
        if (sb.length() > maxChars) {
            sb.setLength(maxChars);
            clipped = true;
        }
        if (clipped) sb.append("\n…[output truncated, file line count: ").append(total).append("]");
        String out = sb.toString().trim();
        return out.isEmpty() ? "(file is empty, " + total + " lines)" : out;
    }

    private String listDir(Path host) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Directory: ").append(guestPath(host)).append('\n');
        try (Stream<Path> s = Files.list(host)) {
            s.sorted().forEach(p -> {
                try {
                    if (Files.isDirectory(p)) sb.append(workspace.relativize(p).toString().replace('\\', '/')).append("/\n");
                    else sb.append(workspace.relativize(p).toString().replace('\\', '/'))
                            .append('\t').append(Files.size(p)).append(" bytes\n");
                } catch (IOException ignored) {
                    sb.append(p.getFileName()).append('\n');
                }
            });
        }
        return sb.toString().trim();
    }

    private String create(Path host, JSONObject o) throws IOException {
        if (Files.exists(host)) {
            return "Error: file already exists: " + guestPath(host) + " (use str_replace to modify it)";
        }
        String text = o.optString("file_text", "");
        Path parent = host.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(host, text.getBytes(StandardCharsets.UTF_8));
        return "File created: " + guestPath(host) + " (" + text.length() + " chars)";
    }

    private String strReplace(Path host, JSONObject o) throws IOException {
        if (!Files.exists(host) || Files.isDirectory(host)) {
            return "Error: file does not exist or is a directory: " + guestPath(host);
        }
        String oldStr = o.optString("old_str", "");
        if (oldStr.isEmpty()) return "Error: old_str is required for str_replace";
        String newStr = o.optString("new_str", "");
        String content = new String(Files.readAllBytes(host), StandardCharsets.UTF_8);
        int count = 0, idx = -1;
        int from = 0;
        while ((from = content.indexOf(oldStr, from)) != -1) {
            count++;
            if (idx < 0) idx = from;
            from += oldStr.length();
        }
        if (count == 0) return "Error: old_str not found in file " + guestPath(host);
        if (count > 1) return "Error: old_str appears " + count + " times in " + guestPath(host)
                + "; expected exactly one match. Include more context in old_str.";
        String updated = content.substring(0, idx) + newStr + content.substring(idx + oldStr.length());
        Files.write(host, updated.getBytes(StandardCharsets.UTF_8));
        return "The file " + guestPath(host) + " has been updated. " + oldStr.length()
                + " old chars were replaced with " + newStr.length() + " chars.";
    }

    private String insert(Path host, JSONObject o) throws IOException {
        if (!Files.exists(host) || Files.isDirectory(host)) {
            return "Error: file does not exist or is a directory: " + guestPath(host);
        }
        String newStr = o.optString("new_str", "");
        int insertLine = o.optInt("insert_line", 0);
        String content = new String(Files.readAllBytes(host), StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int total = content.endsWith("\n") ? lines.length - 1 : lines.length;
        if (insertLine < -1 || insertLine >= total) {
            return "Error: insert_line " + insertLine + " out of range; file " + guestPath(host)
                    + " has " + total + " lines (valid: -1.." + (total - 1) + ")";
        }
        String updated;
        if (insertLine == -1) {
            updated = newStr + "\n" + content;
        } else {
            int pos = 0;
            for (int i = 0; i <= insertLine && pos <= content.length(); i++) {
                int nl = content.indexOf('\n', pos);
                pos = nl < 0 ? content.length() : nl + 1;
            }
            updated = content.substring(0, pos) + newStr + "\n" + content.substring(pos);
        }
        Files.write(host, updated.getBytes(StandardCharsets.UTF_8));
        return "Inserted after line " + insertLine + " in " + guestPath(host) + ": " + newStr.length() + " chars added.";
    }
}