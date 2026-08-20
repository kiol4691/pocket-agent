package com.dsh.android.tools;

import com.dsh.android.util.IOUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Filesystem operations inside the app workspace, done with plain Java NIO (no proroot needed). */
public final class FilesystemTool implements Tool {

    private static final int MAX_READ_BYTES = 32 * 1024;

    private final Path workspace;

    public FilesystemTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "filesystem";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("op", new JSONObject()
                .put("type", "string")
                .put("description", "list | read | write | mkdir | rm")
                .put("enum", new JSONArray().put("list").put("read").put("write").put("mkdir").put("rm")));
        props.put("path", new JSONObject()
                .put("type", "string")
                .put("description", "Path relative to the workspace root (e.g. . or src/main/java/Foo.java)."));
        props.put("content", new JSONObject()
                .put("type", "string")
                .put("description", "New file content for op=write."));
        props.put("recursive", new JSONObject()
                .put("type", "boolean")
                .put("description", "For op=rm, delete recursively."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("op"));
        return Tool.function("filesystem",
                "List, read, write, create and delete files inside the agent workspace. Paths are relative to the workspace root. "
                        + "Files created here are visible to the bash tool at /root/workspace.",
                params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String op = o.optString("op", "");
        Path target = resolve(o.optString("path", "."));
        switch (op) {
            case "list":
                return list(target);
            case "read":
                return read(target);
            case "write": {
                Files.createDirectories(target.getParent());
                Files.write(target, o.optString("content", "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return "Wrote " + workspace.relativize(target);
            }
            case "mkdir":
                Files.createDirectories(target);
                return "Created " + workspace.relativize(target);
            case "rm":
                if (o.optBoolean("recursive", false) && Files.isDirectory(target)) {
                    try (Stream<Path> s = Files.walk(target)) {
                        s.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
                    }
                } else {
                    Files.deleteIfExists(target);
                }
                return "Removed " + workspace.relativize(target);
            default:
                return "Error: unknown op '" + op + "'. Valid ops: list, read, write, mkdir, rm";
        }
    }

    private Path resolve(String rel) throws IOException {
        Path p = workspace.resolve(rel.replace('\\', '/')).normalize();
        if (!p.startsWith(workspace)) throw new IOException("Path escapes workspace: " + rel);
        return p;
    }

    private String list(Path target) throws IOException {
        if (!Files.exists(target)) return "Error: not found: " + workspace.relativize(target);
        if (!Files.isDirectory(target)) return workspace.relativize(target) + " is not a directory";
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(target)) {
            s.sorted().forEach(p -> {
                String rel = workspace.relativize(p).toString();
                try {
                    if (Files.isDirectory(p)) sb.append(rel).append("/\n");
                    else sb.append(rel).append("\t").append(Files.size(p)).append(" bytes\n");
                } catch (IOException ignored) {
                    sb.append(rel).append("\n");
                }
            });
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(empty directory)" : out;
    }

    private String read(Path target) throws IOException {
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return "Error: not found or is a directory: " + workspace.relativize(target);
        }
        byte[] data = IOUtil.readBytes(target.toFile(), MAX_READ_BYTES);
        String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        if (data.length >= MAX_READ_BYTES) return s + "\n…[truncated at " + MAX_READ_BYTES + " bytes]";
        return s;
    }
}