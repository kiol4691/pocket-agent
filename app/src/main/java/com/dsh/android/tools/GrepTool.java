package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * {@code grep}：按正则搜文件内容，结果带 路径:行号。
 *
 * 和 {@link GlobTool} 一样，目的是替掉 "bash grep -rn ... | head"：
 * 直接在 Java 里做，能顺手跳过二进制、限制单行长度、限制文件大小，
 * 输出也不会因为管道缓冲而丢行。
 */
public final class GrepTool implements Tool {

    private static final int MAX_DEPTH = 12;
    private static final int MAX_FILE_BYTES = 2 << 20;
    private static final int MAX_LINE = 240;

    private final Path root;

    public GrepTool(Path workspace) {
        this.root = workspace.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("pattern", new JSONObject()
                .put("type", "string")
                .put("description", "Java 正则表达式（不是 shell 的 -E 方言，但常用写法一致）"));
        props.put("path", new JSONObject()
                .put("type", "string")
                .put("description", "起始目录或单个文件，默认工作区根"));
        props.put("include", new JSONObject()
                .put("type", "string")
                .put("description", "只搜匹配这个 glob 的文件，如 *.java"));
        props.put("ignore_case", new JSONObject()
                .put("type", "boolean")
                .put("description", "是否忽略大小写，默认 false"));
        props.put("limit", new JSONObject()
                .put("type", "integer")
                .put("description", "最多返回多少条匹配，默认 100"));
        return Tool.function(name(), "在文件内容里按正则搜索，返回 相对路径:行号: 该行内容。",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new org.json.JSONArray().put("pattern")));
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String pat = a.optString("pattern", "");
        if (pat.isEmpty()) return "Error: pattern 不能为空";
        int limit = Math.max(1, Math.min(500, a.optInt("limit", 100)));

        Pattern re;
        try {
            re = Pattern.compile(pat, a.optBoolean("ignore_case", false)
                    ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return "Error: 正则非法 " + e.getDescription();
        }

        Path base = root;
        String p = a.optString("path", "").trim();
        if (!p.isEmpty()) {
            Path cand = Path.of(p);
            base = (cand.isAbsolute() ? cand : root.resolve(p)).normalize();
            if (!base.startsWith(root)) return "Error: 只能在工作区内搜索（" + root + "）";
        }

        PathMatcher include = null;
        String inc = a.optString("include", "").trim();
        if (!inc.isEmpty()) {
            try {
                include = FileSystems.getDefault().getPathMatcher("glob:" + inc);
            } catch (RuntimeException e) {
                return "Error: include 非法 " + inc;
            }
        }

        List<String> hits = new ArrayList<>();
        boolean[] capped = {false};
        final PathMatcher includeMatcher = include;
        try {
            if (Files.isRegularFile(base)) {
                scan(base, re, include, limit, hits, capped);
            } else if (Files.isDirectory(base)) {
                try (Stream<Path> walk = Files.walk(base, MAX_DEPTH)) {
                    walk.filter(Files::isRegularFile).filter(f -> !skip(f)).forEach(f -> {
                        if (hits.size() < limit) {
                            scan(f, re, includeMatcher, limit, hits, capped);
                        }
                    });
                }
            } else {
                return "Error: 路径不存在 " + base;
            }
        } catch (IOException e) {
            return "Error: 搜索失败 " + e.getMessage();
        }

        if (hits.isEmpty()) return "没有匹配 " + pat + " 的内容";
        StringBuilder sb = new StringBuilder("匹配 ").append(hits.size()).append(" 处");
        if (capped[0]) sb.append("（已达上限 ").append(limit).append("，可能不完整）");
        sb.append("：\n");
        for (String h : hits) sb.append(h).append('\n');
        return sb.toString();
    }

    private void scan(Path f, Pattern re, PathMatcher include, int limit,
                      List<String> hits, boolean[] capped) {
        if (hits.size() >= limit) {
            capped[0] = true;
            return;
        }
        Path rel = root.relativize(f);
        if (include != null) {
            boolean ok = include.matches(f.getFileName()) || include.matches(rel);
            if (!ok) return;
        }
        try {
            if (Files.size(f) > MAX_FILE_BYTES || looksBinary(f)) return;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    Files.newInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                int n = 0;
                while ((line = r.readLine()) != null) {
                    n++;
                    Matcher m = re.matcher(line);
                    if (!m.find()) continue;
                    String t = line.trim();
                    if (t.length() > MAX_LINE) t = t.substring(0, MAX_LINE) + "…";
                    hits.add(rel + ":" + n + ": " + t);
                    if (hits.size() >= limit) {
                        capped[0] = true;
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // 单个文件读不了（权限/编码）不该让整次搜索失败
        }
    }

    /** 前 8 KB 里出现 NUL 就当二进制。比按扩展名猜可靠，也够快。 */
    private static boolean looksBinary(Path f) {
        byte[] head = new byte[8192];
        try (java.io.InputStream in = Files.newInputStream(f)) {
            int n = in.read(head);
            for (int i = 0; i < n; i++) if (head[i] == 0) return true;
        } catch (IOException e) {
            return true;
        }
        return false;
    }

    private static boolean skip(Path f) {
        for (Path part : f) {
            String s = part.toString();
            if (s.equals(".git") || s.equals("node_modules")) return true;
        }
        return false;
    }
}
