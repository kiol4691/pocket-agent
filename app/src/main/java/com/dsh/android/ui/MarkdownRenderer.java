package com.dsh.android.ui;

import android.content.Context;
import android.text.Spanned;
import android.widget.TextView;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.CorePlugin;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tables.TableTheme;
import io.noties.markwon.ext.tasklist.TaskListPlugin;

/**
 * Markdown rendering backed by the Markwon library (CommonMark + GFM tables,
 * strikethrough and task-list items). Styled to match the app's dark theme.
 *
 * The instance must be initialised once with the application context
 * ({@link #init(Context)}) before any render call.
 */
public final class MarkdownRenderer {

    private static Markwon markwon;

    private MarkdownRenderer() {
    }

    public static void init(Context appContext) {
        if (markwon != null) return;

        // JlatexMathPlugin 的 textSize 参数是 px，不是 sp：直接传 16f 会得到 16px(8dp)，
        // 公式只有正文一半高。这里按 scaledDensity 换算成真正的 sp 像素值。
        final float latexPx = 18f * appContext.getResources().getDisplayMetrics().scaledDensity;
        final int brand = 0xFF679EFE;
        final int secondary = 0xFFCFD3D6;
        final int caption = 0xFF81858C;
        final int codeBg = 0xFF1B1B1C;
        final int inlineBg = 0x1A2C2C2E;
        final int borderAlpha = 0x26FFFFFF;

        TableTheme tableTheme = TableTheme.buildWithDefaults(appContext)
                .tableCellPadding(8)
                .tableBorderColor(borderAlpha)
                .tableBorderWidth(2)
                .tableHeaderRowBackgroundColor(0x1AFFFFFF)
                .tableOddRowBackgroundColor(0x0AFFFFFF)
                .tableEvenRowBackgroundColor(0x00151517)
                .build();

        markwon = Markwon.builder(appContext)
                // 显式把 CorePlugin 放最前，避免依赖 Markwon「没显式加就自动追加到末尾」
                // 的隐式行为（那会让插件顺序随版本漂移）。
                .usePlugin(CorePlugin.create())
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(MarkwonTheme.Builder builder) {
                        builder
                                .linkColor(brand)
                                .isLinkUnderlined(false)
                                // 默认倍数是 {2, 1.5, 1.17, 1, 1, 1}，正文 17sp 时 H1 = 34sp，
                                // 中文一行放不下十个字。收窄到 1.25/1.15/1.06（H1≈21sp）。
                                .headingTextSizeMultipliers(new float[]{1.25f, 1.15f, 1.06f, 1f, 1f, 1f})
                                .codeTextSize(17)
                                .codeBlockTextSize(14)
                                .codeTextColor(secondary)
                                .codeBlockTextColor(secondary)
                                .codeBackgroundColor(inlineBg)
                                .codeBlockBackgroundColor(codeBg)
                                .blockQuoteColor(caption)
                                .blockQuoteWidth(6)
                                .listItemColor(0xFF4ED17E)
                                .headingBreakColor(borderAlpha)
                                .thematicBreakColor(borderAlpha);
                    }

                    @Override
                    public void beforeSetText(TextView textView, Spanned markdown) {
                        // links still need a movement method to be clickable
                        textView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    }
                })
                .usePlugin(TablePlugin.create(tableTheme))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TaskListPlugin.create(appContext))
                .usePlugin(JLatexMathPlugin.create(latexPx, latexPx, builder -> builder
                        .theme()
                        .textColor(0xFFF9FAFB)
                        .inlinePadding(io.noties.markwon.ext.latex.JLatexMathTheme.Padding.of(6, 2, 6, 2))))
                .build();
    }

    private static final java.util.regex.Pattern PAREN_LATEX = java.util.regex.Pattern
            .compile("\\\\\\((.+?)\\\\\\)", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern BRACKET_LATEX = java.util.regex.Pattern
            .compile("\\\\\\[(.+?)\\\\\\]", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern TEX_CMD = java.util.regex.Pattern
            .compile("\\\\[a-zA-Z]{2,}");
    private static final String[] TEX_STRONG = {
            "\\frac", "\\dfrac", "\\tfrac", "\\sqrt", "\\sum", "\\prod", "\\int", "\\oint",
            "\\lim", "\\begin{", "\\left", "\\overline", "\\underline", "\\vec{", "\\hat{",
            "\\binom", "\\cases", "\\begin{align", "\\begin{equation"
    };

    /** Models often emit TeX with \\(..\\) / \\[..\\] delimiters; Markwon's latex plugin only
     * parses $..$ / $$..$$. Rewrite the delimiters before rendering. */
    private static String normalizeLatexDelimiters(String md) {
        String s = PAREN_LATEX.matcher(md).replaceAll("\\$$1\\$");
        return BRACKET_LATEX.matcher(s).replaceAll("\\$$$1\\$\\$");
    }

    /** Some models write formulas completely bare (no $, no fence). Detect standalone
     * LaTeX-looking lines and wrap them as display math so they render. */
    private static String wrapBareLatexLines(String md) {
        String[] lines = md.split("\n", -1);
        boolean inFence = false;
        boolean inMath = false;          // 已在 $$ ... $$ 块内
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String t = raw.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            // 独立的 $$ 行是块级公式的边界；块内文本不能再被包一层，
            // 否则会变成 $$ / $$int...$$ / $$，把 Markdown 块结构整个打乱
            // （后续标题会退化成原始的 "### xxx"）。
            if (t.equals("$$") || t.equals("$$$")) {
                inMath = !inMath;
                continue;
            }
            if (inMath || t.isEmpty() || t.contains("$") || t.contains("|")) continue;
            char c0 = t.charAt(0);
            if (c0 == '#' || c0 == '>' || c0 == '-' || c0 == '*' || c0 == '+') continue;
            if (t.length() > 2 && Character.isDigit(c0) && t.charAt(1) == '.') continue;
            if (looksLikeLatexLine(t)) lines[i] = "$$" + t + "$$";
        }
        StringBuilder sb = new StringBuilder(md.length() + 16);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static boolean looksLikeLatexLine(String t) {
        for (String sig : TEX_STRONG) {
            if (t.contains(sig)) return true;
        }
        boolean cmd = TEX_CMD.matcher(t).find();
        boolean caret = t.contains("^");
        boolean eqish = t.contains("=") || t.contains("≈") || t.contains("≠")
                || t.contains("≤") || t.contains("≥")
                || (t.contains("<") && t.length() > 2 && !t.startsWith("<"))
                || (t.contains(">") && !t.endsWith(">"));
        return (cmd && eqish) || (caret && eqish);
    }

    public static CharSequence render(String md) {
        return markwon.toMarkdown(preprocess(md));
    }

    /** 渲染进 TextView。必须用它而不是 setText(render(...))：
     *  Markwon 的插件钩子只在 setMarkdown/setParsedMarkdown 时触发，
     *  LaTeX 的 AsyncDrawableScheduler.schedule() 就在钩子里。 */
    public static void setText(TextView textView, String md) {
        markwon.setMarkdown(textView, preprocess(md));
    }

    private static String preprocess(String md) {
        String s = normalizeLatexDelimiters(md == null ? "" : md);
        s = wrapSingleDollarMath(s);
        return wrapBareLatexLines(s);
    }

    /**
     * Markwon 4.6.2 的行内 latex 实测完全不解析（只有独立成行的块级 $$ 才会渲染），
     * 所以把所有「看起来像公式」的 $...$ 统一升级成独立块级：
     *
     *     $x^2$  ->  

$$
 x^2 
$$


     *
     * 价格（$5 到 $10）、shell 变量（$R/$@）这类含中文或纯数字的内容保持原样。
     */
    private static final java.util.regex.Pattern SINGLE_DOLLAR =
            java.util.regex.Pattern.compile("(?<!\\$)\\$(?!\\$)([^$\\n]{1,120}?)(?<!\\$)\\$(?!\\$)");

    private static String wrapSingleDollarMath(String md) {
        java.util.regex.Matcher m = SINGLE_DOLLAR.matcher(md);
        StringBuilder sb = new StringBuilder(md.length() + 64);
        int last = 0;
        while (m.find()) {
            String body = m.group(1);
            sb.append(md, last, m.start());
            if (looksLikeMath(body)) {
                sb.append("\n\n$$\n").append(body).append("\n$$\n\n");
            } else {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(md, last, md.length());
        return sb.toString();
    }

    private static boolean looksLikeMath(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 120) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return false;   // 含中文 → 不是公式
        }
        if (t.length() <= 2) {                               // $x$ / $n$ 这类变量
            for (int i = 0; i < t.length(); i++) {
                if (!Character.isLetterOrDigit(t.charAt(i))) return false;
            }
            return true;
        }
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' || c == '^' || c == '_' || c == '{' || c == '}'
                    || c == '=' || c == '<' || c == '>' || c == '+'
                    || c == '*' || c == '/') {
                return true;
            }
        }
        return false;
    }
}