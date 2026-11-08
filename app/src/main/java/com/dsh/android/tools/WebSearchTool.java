package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * {@code web_search}：用 Bing 的 RSS 输出做搜索。
 *
 * 为什么是 RSS 而不是抓 HTML：RSS 是稳定的机器格式（title / link / description），
 * 不会因为页面改版就失效，也不需要 JS；体积只有几 KB。cn.bing.com 在国内可达。
 *
 * 为什么不接 DeepSeek Search API：那需要额外的 key 与端点配置，而本 app 的定位是
 * "填一个 OpenAI 兼容端点就能用"。搜索结果本身是公开信息，用公开接口更省事。
 */
public final class WebSearchTool implements Tool {

    private static final String[] HOSTS = {"https://cn.bing.com", "https://www.bing.com"};
    private static final int MAX_COUNT = 15;

    private static final Pattern ITEM = Pattern.compile("(?s)<item>(.*?)</item>");
    private static final Pattern TITLE = Pattern.compile("(?s)<title>(.*?)</title>");
    private static final Pattern LINK = Pattern.compile("(?s)<link>(.*?)</link>");
    private static final Pattern DESC = Pattern.compile("(?s)<description>(.*?)</description>");

    private final OkHttpClient http;

    public WebSearchTool() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("query", new JSONObject()
                .put("type", "string")
                .put("description", "搜索词，支持 site:example.com 这类限定"));
        props.put("count", new JSONObject()
                .put("type", "integer")
                .put("description", "返回条数，默认 8，最多 15"));
        return Tool.function(name(),
                "联网搜索，返回标题 / 链接 / 摘要。需要最新信息或不确定的 API 用法时先用它，"
                        + "再对有价值的链接调用 web_fetch 读正文。",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new org.json.JSONArray().put("query")));
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String query = a.optString("query", "").trim();
        if (query.isEmpty()) return "Error: query 不能为空";
        int count = Math.max(1, Math.min(MAX_COUNT, a.optInt("count", 8)));

        String q = URLEncoder.encode(query, "UTF-8");
        String lastErr = null;
        for (String host : HOSTS) {
            String url = host + "/search?q=" + q + "&format=rss&count=" + count + "&mkt=zh-CN";
            try {
                String xml = get(url);
                List<String[]> items = parse(xml);
                if (!items.isEmpty()) return format(query, items, count);
                lastErr = "没有解析到结果条目";
            } catch (IOException e) {
                lastErr = e.getMessage();
            }
        }
        return "Error: 搜索失败（" + lastErr + "）";
    }

    private String get(String url) throws IOException {
        Request req = new Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; PocketAgent)")
                .addHeader("Accept", "application/rss+xml,application/xml,text/xml")
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            return resp.body().string();
        }
    }

    /** 返回 [title, link, snippet]，按出现顺序。 */
    private static List<String[]> parse(String xml) {
        List<String[]> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        while (m.find()) {
            String block = m.group(1);
            String title = pick(TITLE, block);
            String link = pick(LINK, block);
            String desc = pick(DESC, block);
            if (link.isEmpty()) continue;
            out.add(new String[]{title, link, snippet(desc)});
        }
        return out;
    }

    private static String pick(Pattern p, String block) {
        Matcher m = p.matcher(block);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) {
            v = v.substring(9, v.length() - 3);
        }
        return WebFetchTool.unescape(v.replaceAll("(?s)<[^>]+>", "")).trim();
    }

    private static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String format(String query, List<String[]> items, int count) {
        StringBuilder sb = new StringBuilder("搜索：").append(query)
                .append("（Bing，").append(Math.min(items.size(), count)).append(" 条）\n");
        int i = 0;
        for (String[] it : items) {
            if (++i > count) break;
            sb.append('\n').append(i).append(". ").append(it[0].isEmpty() ? "(无标题)" : it[0])
                    .append('\n').append("   ").append(it[1]).append('\n');
            if (!it[2].isEmpty()) sb.append("   ").append(it[2]).append('\n');
        }
        return sb.toString();
    }
}
