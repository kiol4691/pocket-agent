package com.dsh.android.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 一条渲染器声明（manifest 的 {@code contributes.renderers[]}）和它的块匹配规则。
 *
 * 刻意不依赖 Android / org.json：匹配语义（fence / regex / prefix、大小写、排序）可以在
 * JVM 单测里直接验证，manifest 解析只负责把 JSON 填进来。
 *
 * {@code match} 三选一，语义固定：
 *  - {@code fence}：只匹配代码块，语言名大小写不敏感；
 *  - {@code regex}：只匹配纯文本块，对块正文做 find，**带 100ms 预算**；
 *  - {@code prefix}：只匹配纯文本块，正文前缀严格匹配（大小写敏感）。
 */
public final class RendererRule {

    public static final String MATCH_FENCE = "fence";
    public static final String MATCH_REGEX = "regex";
    public static final String MATCH_PREFIX = "prefix";

    /** 正则匹配预算：灾难性回溯的表达式不能把主线程拖住。 */
    public static final long REGEX_BUDGET_NANOS = 100_000_000L;

    /** 链顺序：渲染器 priority → 插件 id → 渲染器 id，保证可复现且与插件级 priority 解耦。 */
    public static final Comparator<RendererRule> ORDER =
            Comparator.comparingInt((RendererRule r) -> r.priority)
                    .thenComparing(r -> r.pluginId)
                    .thenComparing(r -> r.id);

    public final String pluginId;
    public final String id;
    public final int priority;
    public final String matchType;
    public final List<String> fences;
    public final String regexSource;
    public final String prefix;
    public final String handler;

    /** 上一次 {@link #matches} 是否因预算耗尽放弃（诊断用，不参与匹配结果）。 */
    public boolean lastTimedOut;

    private final Pattern regex;

    public RendererRule(String pluginId, String id, int priority,
                        String matchType, List<String> fences, String regexSource,
                        String prefix, String handler) throws PatternSyntaxException {
        this.pluginId = pluginId;
        this.id = id;
        // priority 与其它贡献类型一致夹在 [-100,100]。
        this.priority = HookManifest.clampPriority(priority);
        this.matchType = matchType;
        List<String> lower = new ArrayList<>();
        if (fences != null) {
            for (String f : fences) {
                if (f != null && !f.trim().isEmpty()) lower.add(f.trim().toLowerCase(Locale.ROOT));
            }
        }
        this.fences = Collections.unmodifiableList(lower);
        this.regexSource = regexSource == null ? "" : regexSource;
        this.prefix = prefix == null ? "" : prefix;
        this.handler = handler;
        this.regex = MATCH_REGEX.equals(matchType) && !this.regexSource.isEmpty()
                ? Pattern.compile(this.regexSource) : null;
    }

    public boolean matches(String type, String lang, String body) {
        lastTimedOut = false;
        if (MATCH_FENCE.equals(matchType)) {
            if (!"fence".equals(type)) return false;
            String l = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
            return fences.contains(l);
        }
        // regex / prefix 是"纯文本块"的匹配方式；代码块请用 fence。
        if (!"text".equals(type)) return false;
        String b = body == null ? "" : body;
        if (MATCH_PREFIX.equals(matchType)) return b.startsWith(prefix);
        if (regex == null) return false;
        try {
            return regex.matcher(new DeadlineSeq(b, System.nanoTime() + REGEX_BUDGET_NANOS)).find();
        } catch (BudgetExceeded e) {
            lastTimedOut = true;
            return false;
        }
    }

    @Override
    public String toString() {
        String m = MATCH_FENCE.equals(matchType) ? "fence=" + fences
                : MATCH_REGEX.equals(matchType) ? "regex=" + regexSource
                : "prefix=" + prefix;
        return pluginId + "/" + id + " [" + m + "] -> " + handler;
    }

    // ---------------------------------------------------------------- 正则预算

    private static final BudgetExceeded TIMEOUT = new BudgetExceeded();

    /** 不建栈、不可变的信号异常：每次匹配超时都复用同一个实例。 */
    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /**
     * 给 Matcher 的输入套一层截止时间：回溯会反复调 {@code charAt}，超过预算就抛出。
     * 这样不需要额外线程，也不会在超时后留下跑不完的后台任务。
     */
    private static final class DeadlineSeq implements CharSequence {
        private final CharSequence base;
        private final long deadline;

        DeadlineSeq(CharSequence base, long deadline) {
            this.base = base;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            if (System.nanoTime() > deadline) throw TIMEOUT;
            return base.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) throw TIMEOUT;
            return base.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineSeq(base.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return base.toString();
        }
    }
}
