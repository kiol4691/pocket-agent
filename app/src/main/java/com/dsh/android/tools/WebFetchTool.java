package com.dsh.android.tools;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@code web_fetch}：把一个 URL 的正文取回来，HTML 只保留可读文本。
 *
 * 为什么要自己剥 HTML：模型读原始 HTML 会被标签、脚本、内联样式淹没，
 * 一个页面 200 KB 里可能有 180 KB 是噪声。这里做四件事：去掉 script/style/注释、
 * 把块级标签换成换行、剥掉其余标签、反转义常见实体。
 *
 * 限制（都是真机上的现实约束，不是偷懒）：
 *  - 只取前 3 MB，超出直接截断（手机上解析超大页面会 OOM）；
 *  - 正文最多 1.2 万字符（约 6k token），够模型判断，又不至于把上下文顶爆；
 *  - 非文本响应（图片、二进制）不返回内容，只报 content-type 和大小。
 */
public final class WebFetchTool implements Tool {

    private static final int MAX_BYTES = 3 << 20;
    private static final int MAX_CHARS = 12000;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; PocketAgent) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36";

    private final OkHttpClient http;

    public WebFetchTool() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public JSONObject schema() throws JSONException {
        JSONObject props = new JSONObject();
        props.put("url", new JSONObject()
                .put("type", "string")
                .put("description", "http:// 或 https:// 开头的完整地址"));
        props.put("max_chars", new JSONObject()
                .put("type", "integer")
                .put("description", "返回正文的最大字符数，默认 12000"));
        return Tool.function(name(),
                "抓取一个网页并返回可读正文（HTML 已剥离标签）。"
                        + "用于查文档、看 issue、读新闻；需要先知道 URL。",
                new JSONObject()
                        .put("type", "object")
                        .put("properties", props)
                        .put("required", new org.json.JSONArray().put("url")));
    }

    @Override
    public String execute(String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        String url = a.optString("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "Error: url 必须以 http:// 或 https:// 开头";
        }
        int maxChars = a.optInt("max_chars", MAX_CHARS);
        if (maxChars < 500) maxChars = 500;

        Request req = new Request.Builder().url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            ResponseBody body = resp.body();
            if (body == null) return "Error: 空响应（HTTP " + resp.code() + "）";
            String type = String.valueOf(body.contentType());
            byte[] raw = body.bytes();
            if (raw.length > MAX_BYTES) {
                byte[] cut = new byte[MAX_BYTES];
                System.arraycopy(raw, 0, cut, 0, MAX_BYTES);
                raw = cut;
            }
            String head = "URL: " + resp.request().url() + "\nHTTP: " + resp.code()
                    + "\nContent-Type: " + type + "\n\n";

            if (!isTextual(type)) {
                return head + "（非文本内容，" + raw.length + " 字节，未解析）";
            }
            Charset cs = charsetOf(body.contentType(), raw);
            String text = new String(raw, cs);
            String plain = type.contains("html") ? htmlToText(text) : text;
            plain = plain.trim();
            if (plain.length() > maxChars) {
                plain = plain.substring(0, maxChars) + "\n…（已截断，原文 " + plain.length() + " 字符）";
            }
            return head + plain;
        } catch (IOException e) {
            return "Error: 抓取失败 " + e.getMessage();
        }
    }

    private static boolean isTextual(String type) {
        String t = type.toLowerCase();
        return t.contains("text/") || t.contains("json") || t.contains("xml")
                || t.contains("javascript") || t.contains("xhtml");
    }

    /** 响应头里的 charset；没有就看 BOM，再不行按 UTF-8（国内站点也基本是 UTF-8 了）。 */
    private static Charset charsetOf(okhttp3.MediaType mt, byte[] raw) {
        if (mt != null) {
            Charset c = mt.charset(null);
            if (c != null) return c;
        }
        if (raw.length > 2 && (raw[0] & 0xff) == 0xff && (raw[1] & 0xff) == 0xfe) {
            return Charset.forName("UTF-16LE");
        }
        if (raw.length > 2 && (raw[0] & 0xff) == 0xfe && (raw[1] & 0xff) == 0xff) {
            return Charset.forName("UTF-16BE");
        }
        return Charset.forName("UTF-8");
    }

    /** HTML -> 可读文本。够用即可：目标是让模型读懂内容，不是还原排版。 */
    static String htmlToText(String html) {
        String s = html
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<noscript.*?</noscript>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article|header|footer"
                        + "|blockquote|pre|table)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ");
        s = unescape(s);
        // 压缩空白：行内多空格合一，连续空行最多留一个
        s = s.replaceAll("[ \t\u00a0]+", " ").replaceAll("\n[ \t]*\n+", "\n\n");
        return s.trim();
    }

    /** 常见实体反转义（含十进制/十六进制数字实体）。 */
    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        String r = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#39;", "'").replace("&#x27;", "'");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                cp = '?';
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
