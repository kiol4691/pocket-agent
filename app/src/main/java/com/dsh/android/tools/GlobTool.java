package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code glob}：按文件名模式找文件（{@code **&#47;*.java} 这种）。
 *
 * 存在的意义是把"用 bash 拼 find/ls 再解析输出"这条弯路切掉：模型要的是路径列表，
 * 而不是一屏 find 的副作用。深度与条数都有上限，避免在几十万文件的 rootfs 里走丢。
 */
public final class GlobTool implements Tool {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_RESULTS = 300;

    private final Path root;

    public GlobTool(Path workspace) {
        this.root = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("pattern", new JSONObject()
                .put("type", "string")
                .put("description", "glob 模式，如 **/*.java、*.md、src/**/*Test.java"));
        props.put("path", new JSONObject()
                .put("type", "string")
                .put("description", "起始目录，默认工作区根；相对路径按工作区解析"));
        props.put("limit", new JSONObject()
                .put("type", "integer")
                .put("description", "最多返回多少条，默认 100"));
        return Tool.function(name(), "按文件名模式查找文件，返回相对工作区的路径列表（按路径排序）。",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new org.json.JSONArray().put("pattern")));
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String pattern = a.optString("pattern", "").trim();
        if (pattern.isEmpty()) return "Error: pattern 不能为空";
        int limit = Math.max(1, Math.min(MAX_RESULTS, a.optInt("limit", 100)));

        Path base = root;
        String p = a.optString("path", "").trim();
        if (!p.isEmpty()) {
            Path cand = Path.of(p);
            base = (cand.isAbsolute() ? cand : root.resolve(p)).normalize();
            if (!base.startsWith(root)) return "Error: 只能在工作区内查找（" + root + "）";
        }
        if (!Files.isDirectory(base)) return "Error: 不是目录 " + base;

        PathMatcher m;
        try {
            m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (RuntimeException e) {
            return "Error: 非法模式 " + pattern + "（" + e.getMessage() + "）";
        }
        boolean nameOnly = pattern.indexOf('/') < 0 && pattern.indexOf("**") < 0;

        List<String> hits = new ArrayList<>();
        final Path start = base;
        try (Stream<Path> walk = Files.walk(start, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile).filter(f -> !skip(f)).forEach(f -> {
                if (hits.size() >= limit) return;
                Path rel = start.relativize(f);
                boolean ok = nameOnly ? m.matches(f.getFileName()) : m.matches(rel);
                if (ok) hits.add(root.relativize(f).toString());
            });
        } catch (IOException e) {
            return "Error: 遍历失败 " + e.getMessage();
        }
        if (hits.isEmpty()) return "没有匹配 " + pattern + " 的文件（在 " + root.relativize(base) + "）";
        Collections.sort(hits);
        StringBuilder sb = new StringBuilder("匹配 ").append(hits.size()).append(" 个文件");
        if (hits.size() >= limit) sb.append("（已达上限 ").append(limit).append("，可能不完整）");
        sb.append("：\n");
        for (String h : hits) sb.append(h).append('\n');
        return sb.toString();
    }

    /** .git 之类的目录对模型没有价值，只会把结果撑爆。 */
    private static boolean skip(Path f) {
        for (Path part : f) {
            String s = part.toString();
            if (s.equals(".git") || s.equals("node_modules")) return true;
        }
        return false;
    }
}
