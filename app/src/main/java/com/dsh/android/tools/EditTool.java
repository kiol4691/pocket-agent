package com.dsh.android.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 对齐上游 dsh-tool-fs 的 edit（src/edit.ts）：
 * 字面量替换、默认唯一匹配、old_string 非空且不等于 new_string。
 */
public final class EditTool implements Tool {

    private final Path workspace;

    public EditTool(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        props.put("file_path", new JSONObject().put("type", "string")
                .put("description", "Path to edit, resolved by the filesystem backend. "
                        + "Provide `file_path` before `old_string` and `new_string` in the arguments."));
        props.put("old_string", new JSONObject().put("type", "string")
                .put("description", "Literal text to replace."));
        props.put("new_string", new JSONObject().put("type", "string")
                .put("description", "Literal replacement text. Use an empty string to delete the match."));
        props.put("replace_all", new JSONObject().put("type", "boolean")
                .put("description", "Replace all matches. Defaults to false; when false, old_string must appear exactly once."));
        params.put("properties", props);
        params.put("required", new JSONArray().put("file_path").put("old_string").put("new_string"));
        return Tool.function("edit", "Edit an existing UTF-8 text file by replacing literal text.", params);
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject o = new JSONObject(argsJson);
        String fp = o.optString("file_path", "");
        String oldStr = o.optString("old_string", "");
        String newStr = o.optString("new_string", "");
        boolean replaceAll = o.optBoolean("replace_all", false);

        if (fp.trim().isEmpty()) return "Error: file_path must be a non-empty string";
        if (oldStr.isEmpty()) return "Error: old_string must be a non-empty string";
        if (oldStr.equals(newStr)) return "Error: old_string and new_string must differ";

        Path target = FsPaths.resolve(workspace, fp);
        if (!Files.exists(target)) {
            return "Error: The path " + FsPaths.display(workspace, target)
                    + " does not exist. Please provide a valid path.";
        }
        if (Files.isDirectory(target)) {
            return "Error: The path " + FsPaths.display(workspace, target)
                    + " is a directory and only the `view` command can be used on directories";
        }
        String content = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        int count = countOccurrences(content, oldStr);
        if (count == 0) {
            return "Error: No replacement was performed, old_string `" + brief(oldStr)
                    + "` did not appear verbatim in " + FsPaths.display(workspace, target) + ".";
        }
        if (!replaceAll && count != 1) {
            return "Error: No replacement was performed. Multiple occurrences of old_string `"
                    + brief(oldStr) + "` found (" + count + "). Please ensure it is unique.";
        }
        String updated = replaceAll ? content.replace(oldStr, newStr)
                : content.replaceFirst(java.util.regex.Pattern.quote(oldStr),
                        java.util.regex.Matcher.quoteReplacement(newStr));
        Files.write(target, updated.getBytes(StandardCharsets.UTF_8));
        String path = FsPaths.display(workspace, target);
        return replaceAll
                ? "The file " + path + " has been updated. All occurrences were successfully replaced."
                : "The file " + path + " has been updated successfully.";
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static String brief(String s) {
        String oneLine = s.replace("\n", "\\n");
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "…" : oneLine;
    }
}
