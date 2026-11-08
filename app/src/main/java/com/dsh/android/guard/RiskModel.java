package com.dsh.android.guard;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 风险打分模型（哈希混合特征 + 逻辑回归）。
 *
 * 模型文件由 tools/guard/train_hash.py 产出：固定 2^bits 个 float32 权重（base64）。
 * 特征 = 语义特征 + 字符 3-5 gram（char_wb）+ 词/词对，全部 CRC32 哈希到桶，
 * 用最高位当符号（碰撞互相抵消而不是单向偏移）。
 *
 * **与 Python 训练端逐位对齐**是这里唯一的硬要求：
 *   - 分词/切段/路径规范化在 {@link Features}（对应 feat.py）
 *   - 字符与词 n-gram 在这里（对应 feat_hash.py）
 *   - 哈希：crc32(utf8(key)) & mask，符号取 (h >> 31) & 1
 * 任何一处不一致，分数就会整体漂移，而不会报错。
 */
public final class RiskModel {

    /** 语义特征权重倍数，必须与 tools/guard/feat_hash.py 的 SEM_W 完全一致。 */
    private static final int SEM_W = 2;


    private static final Pattern P_BITS = Pattern.compile("\"bits\"\\s*:\\s*(\\d+)");
    private static final Pattern P_INTERCEPT = Pattern.compile("\"intercept\"\\s*:\\s*(-?[0-9.eE+\\-]+)");
    private static final Pattern P_MODE = Pattern.compile("\"mode\"\\s*:\\s*\"([a-z]+)\"");
    private static final Pattern P_W = Pattern.compile("\"weights_b64\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern P_VIEWS = Pattern.compile("\"views\"\\s*:\\s*(\\[.*?\\])");
    private static final Pattern P_OBJ = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern P_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_NUM = Pattern.compile("\"(bits|offset)\"\\s*:\\s*(\\d+)");
    private static final Pattern P_ALPHA =
            Pattern.compile("\"alpha\"\\s*:\\s*(-?[0-9.eE+\\-]+)");
    private static final Pattern P_SEMW = Pattern.compile("\"sem_w\"\\s*:\\s*(\\d+)");

    /** 一个视图 = 一段独立的权重空间；旧模型没有 views，走单段路径。 */
    private static final class View {
        final String name;
        final int bits;
        final int offset;
        final double alpha;

        View(String name, int bits, int offset, double alpha) {
            this.name = name;
            this.bits = bits;
            this.offset = offset;
            this.alpha = alpha;
        }
    }

    private static List<View> parseViews(String json) {
        Matcher m = P_VIEWS.matcher(json);
        if (!m.find()) return null;
        List<View> out = new ArrayList<>();
        Matcher om = P_OBJ.matcher(m.group(1));
        while (om.find()) {
            String o = om.group(1);
            Matcher nm = P_NAME.matcher(o);
            if (!nm.find()) continue;
            int bits = 0, offset = 0;
            Matcher nums = P_NUM.matcher(o);
            while (nums.find()) {
                int v = Integer.parseInt(nums.group(2));
                if (nums.group(1).equals("bits")) bits = v;
                else offset = v;
            }
            Matcher am = P_ALPHA.matcher(o);
            double alpha = am.find() ? Double.parseDouble(am.group(1)) : 1.0;
            out.add(new View(nm.group(1), bits, offset, alpha));
        }
        return out.isEmpty() ? null : out;
    }

    public final int bits;
    public final String mode;
    public final double intercept;
    private final float[] weights;
    private final List<View> views;
    private final int semW;

    private RiskModel(int bits, String mode, double intercept, float[] weights,
                      List<View> views, int semW) {
        this.bits = bits;
        this.mode = mode;
        this.intercept = intercept;
        this.weights = weights;
        this.views = views;
        this.semW = semW;
    }

    public static RiskModel parse(String json) throws IOException {
        Matcher mb = P_BITS.matcher(json);
        Matcher mi = P_INTERCEPT.matcher(json);
        Matcher mm = P_MODE.matcher(json);
        Matcher mw = P_W.matcher(json);
        if (!mb.find() || !mi.find() || !mw.find()) throw new IOException("模型文件缺字段");
        int bits = Integer.parseInt(mb.group(1));
        double b = Double.parseDouble(mi.group(1));
        String mode = mm.find() ? mm.group(1) : "char";
        byte[] raw = Base64.getDecoder().decode(mw.group(1));
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        List<View> views = parseViews(json);
        int n = views == null ? (1 << bits) : raw.length / 4;
        float[] w = new float[n];
        bb.asFloatBuffer().get(w, 0, Math.min(w.length, bb.remaining() / 4));
        Matcher sw = P_SEMW.matcher(json);
        int semW = sw.find() ? Integer.parseInt(sw.group(1)) : SEM_W;
        return new RiskModel(bits, mode, b, w, views, semW);
    }

    public static RiskModel load(File f) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return parse(sb.toString());
        }
    }

    /** 原始决策值（未过 sigmoid），对拍时用它更能看出偏差。 */
    public double raw(String cmd) {
        if (views != null) {
            double z = intercept;
            for (View v : views) {
                Map<Integer, Double> acc = new HashMap<>();
                for (String key : viewKeys(cmd, v.name)) {
                    long h = crc(key);
                    int idx = (int) (h & ((1L << v.bits) - 1)) + v.offset;
                    double sign = ((h >> 31) & 1L) == 1L ? 1.0 : -1.0;
                    acc.merge(idx, sign, Double::sum);
                }
                double s = 0;
                for (Map.Entry<Integer, Double> e : acc.entrySet()) {
                    s += weights[e.getKey()] * e.getValue();
                }
                z += v.alpha * s;
            }
            return z;
        }
        Map<Integer, Double> acc = new HashMap<>();
        for (String key : featureKeys(cmd)) {
            long h = crc(key);
            int idx = (int) (h & ((1L << bits) - 1));
            double sign = ((h >> 31) & 1L) == 1L ? 1.0 : -1.0;
            acc.merge(idx, sign, Double::sum);
        }
        double z = intercept;
        for (Map.Entry<Integer, Double> e : acc.entrySet()) {
            z += weights[e.getKey()] * e.getValue();
        }
        return z;
    }

    public double score(String cmd) {
        double z = raw(cmd);
        if (z < -30) return 1e-13;
        if (z > 30) return 1 - 1e-13;
        return 1 / (1 + Math.exp(-z));
    }

    /** 某个视图的特征键（对应 mv.py 的 view_keys）。 */
    private List<String> viewKeys(String cmd, String name) {
        List<String> out = new ArrayList<>();
        if (name.equals("struct")) {
            // 完整继承旧单段模型的特征空间，再叠加结构标记
            out.addAll(featureKeys(cmd));
            for (String t : StructTokens.tokens(cmd)) out.add("t:" + t);
            return out;
        }
        String text = StructTokens.normalizeForChar(cmd).toLowerCase(Locale.ROOT);
        String[] words = text.split("\\s+");
        if (name.equals("word")) {
            for (String w : words) {
                if (!w.isEmpty()) out.add("w1:" + w);
            }
            for (int i = 0; i + 1 < words.length; i++) {
                if (!words[i].isEmpty() && !words[i + 1].isEmpty()) {
                    out.add("w2:" + words[i] + " " + words[i + 1]);
                }
            }
        } else {
            for (String w : words) {
                if (w.isEmpty()) continue;
                String t = " " + w + " ";
                for (int n = 3; n <= 5; n++) {
                    for (int i = 0; i + n <= t.length(); i++) {
                        out.add("c" + n + ":" + t.substring(i, i + n));
                    }
                }
            }
        }
        return out;
    }

    /** 特征键序列（顺序无关，但内容必须与 Python 的 all_features 一致）。 */
    java.util.List<String> featureKeys(String cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        // 语义特征每条只有 1~3 个，字符/词 n-gram 有几十上百个；不加权时
        // 一个无害前缀就能把 "rm -rf /etc" 压到 0.02。倍数与 feat_hash.SEM_W 一致。
        for (int rep = 0; rep < SEM_W; rep++) {
            for (Map.Entry<String, Integer> e : Features.features(cmd).entrySet()) {
                if (e.getValue() != null && e.getValue() != 0) out.add("s:" + e.getKey());
            }
        }
        // n-gram 只吃"操作段"，环境准备语句不参与（同 feat_hash.operative）
        String[] words = Features.operative(cmd).toLowerCase(java.util.Locale.ROOT).split("\\s+");
        for (String w : words) {
            if (w.isEmpty()) continue;
            out.add("w1:" + w);
        }
        for (int i = 0; i + 1 < words.length; i++) {
            if (words[i].isEmpty() || words[i + 1].isEmpty()) continue;
            out.add("w2:" + words[i] + " " + words[i + 1]);
        }
        if (!"chunk".equals(mode)) {
            for (String w : words) {
                if (w.isEmpty()) continue;
                String t = " " + w + " ";
                for (int n = 3; n <= 5; n++) {
                    for (int i = 0; i + n <= t.length(); i++) {
                        out.add("c" + n + ":" + t.substring(i, i + n));
                    }
                }
            }
        }
        return out;
    }

    private static long crc(String key) {
        CRC32 c = new CRC32();
        c.update(key.getBytes(StandardCharsets.UTF_8));
        return c.getValue();
    }
}
