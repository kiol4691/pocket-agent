package com.dsh.android.ui;

import com.dsh.android.R;
import com.dsh.android.ui.framework.HostTheme;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a markdown message into top-level blocks and builds bubble views for
 * them. GFM tables and fenced code blocks become dedicated, self-contained
 * cards (adaptive width, centered cells, language header + copy button for
 * code): the remaining prose goes through {@link MarkdownRenderer}.
 *
 * <p>Streaming support: {@link #sync} keeps a prefix of already-built views,
 * drops the changed tail and reappends it, so only the last mutable block is
 * re-rendered on each delta.
 */
public final class BubbleBuilder {

    public static final class Style {
        public final int text, secondary, caption, brand, inlineBg, codeBg, border;
        public Style(int text, int secondary, int caption, int brand,
                     int inlineBg, int codeBg, int border) {
            this.text = text;
            this.secondary = secondary;
            this.caption = caption;
            this.brand = brand;
            this.inlineBg = inlineBg;
            this.codeBg = codeBg;
            this.border = border;
        }
    }

    public enum Kind { PROSE, TABLE, CODE }

    /** 引用表格的卡片 id：外层是「竖线 + 卡片」，表格本体靠它定位。 */
    private static final int TABLE_CARD_ID = 0x44544155;

    /** 一段顶层内容：prose / 表格 / 代码块。框架渲染（ChatBlockView）也用它。 */
    public static final class Block {
        public final Kind kind;
        public final String text;          // PROSE source or CODE source
        public final String lang;          // CODE language, may be empty
        public final List<String[]> rows;  // TABLE: rows[0] = header
        public final boolean quoted;       // TABLE: came from a `> ` blockquote

        private Block(Kind kind, String text, String lang, List<String[]> rows) {
            this(kind, text, lang, rows, false);
        }

        private Block(Kind kind, String text, String lang, List<String[]> rows, boolean quoted) {
            this.kind = kind;
            this.text = text;
            this.lang = lang;
            this.rows = rows;
            this.quoted = quoted;
        }
    }

    private static final class Store {
        final List<Kind> kinds = new ArrayList<>();
        final List<View> views = new ArrayList<>();
        final StreamState stream = new StreamState();
    }

    /** 只改绘制期 alpha，不动文本/布局；配合 TextView.invalidate() 做淡入。 */
    private static final class FadeSpan extends CharacterStyle {
        float alpha = 0f;
        @Override public void updateDrawState(TextPaint tp) {
            tp.setAlpha(Math.round(alpha * 255f));
        }
    }

    private static final class StreamState {
        int plainLen;                 // 上一帧正文长度
        FadeSpan fade;                // 本次新增文字
        ValueAnimator fadeAnim;
    }

    private BubbleBuilder() {
    }

    private static int dp(float v) {
        return (int) (v * Resources.getSystem().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------------ split

    private static final Pattern FENCE_START =
            Pattern.compile("^(`{3,}|~{3,})\\s*(.*)$");
    private static final Pattern FENCE_END =
            Pattern.compile("^(`{3,}|~{3,})\\s*$");
    private static final Pattern TABLE_SEP =
            Pattern.compile("^[\\s|:\\-]+$");

    public static List<Block> split(String md) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = (md == null ? "" : md).split("\n", -1);
        int i = 0, n = lines.length;
        int holdFrom = n - 1;
        while (holdFrom >= 0 && lines[holdFrom].trim().isEmpty()) holdFrom--;
        StringBuilder prose = new StringBuilder();
        boolean hasProse = false;

        while (i < n) {
            String trim = lines[i].trim();
            Matcher fence = FENCE_START.matcher(trim);
            if (fence.matches()) {
                flushProse(blocks, prose, hasProse);
                hasProse = false;
                String lang = fence.group(2).trim();
                String langLower = lang.toLowerCase(java.util.Locale.US);
                StringBuilder code = new StringBuilder();
                int j = i + 1;
                while (j < n && !FENCE_END.matcher(lines[j].trim()).matches()) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[j]);
                    j++;
                }
                if (langLower.equals("math") || langLower.equals("latex") || langLower.equals("tex")) {
                    blocks.add(new Block(Kind.PROSE, "$$\n" + code + "\n$$\n", null, null));
                } else {
                    blocks.add(new Block(Kind.CODE, code.toString(), lang, null));
                }
                i = j + 1;
                continue;
            }
            // 引用块里的表格：去掉 `>` 引子按表格渲染，竖线由卡片左边缘补上（见 buildTableCard），
            // 这样线紧贴表格、右边不留缝，引用里的普通文字则保持原来的缩进。
            if (isQuoteLine(lines[i]) && i + 1 < n) {
                String head = quoteContent(lines[i]);
                if (head.contains("|") && isTableSeparator(quoteContent(lines[i + 1]).trim())) {
                    flushProse(blocks, prose, hasProse);
                    hasProse = false;
                    String[] header = splitRow(head);
                    List<String[]> rows = new ArrayList<>();
                    int j = i + 2;
                    while (j < n && isQuoteLine(lines[j])) {
                        String r = quoteContent(lines[j]).trim();
                        if (r.isEmpty() || isTableSeparator(r) || !r.contains("|")) break;
                        rows.add(splitRow(r));
                        j++;
                    }
                    List<String[]> all = new ArrayList<>(rows.size() + 1);
                    all.add(header);
                    all.addAll(rows);
                    blocks.add(new Block(Kind.TABLE, null, null, all, true));
                    i = j;
                    continue;
                }
            }
            if (trim.contains("|") && i + 1 < n && isTableSeparator(lines[i + 1].trim())) {
                flushProse(blocks, prose, hasProse);
                hasProse = false;
                String[] header = splitRow(trim);
                List<String[]> rows = new ArrayList<>();
                int j = i + 2;
                while (j < n) {
                    String r = lines[j].trim();
                    if (r.isEmpty() || isTableSeparator(r) || !r.contains("|")) break;
                    rows.add(splitRow(r));
                    j++;
                }
                List<String[]> all = new ArrayList<>(rows.size() + 1);
                all.add(header);
                all.addAll(rows);
                blocks.add(new Block(Kind.TABLE, null, null, all));
                i = j;
                continue;
            }
            // 还没等到分隔行的表格（含引用块里的）先按住不渲染，避免流式过程中
            // 把半截 `| a | b |` 原样吐到界面上。
            String cell = isQuoteLine(lines[i]) ? quoteContent(lines[i]) : trim;
            if (i >= holdFrom && cell.trim().startsWith("|") && !isTableSeparator(cell.trim())) {
                break;
            }
            prose.append(lines[i]).append('\n');
            hasProse = true;
            i++;
        }
        flushProse(blocks, prose, hasProse);
        return blocks;
    }

    private static void flushProse(List<Block> blocks, StringBuilder prose, boolean hasProse) {
        if (hasProse) {
            String s = prose.toString().trim();
            if (!s.isEmpty()) blocks.add(new Block(Kind.PROSE, s + "\n", null, null));
            prose.setLength(0);
        }
    }

    private static boolean isTableSeparator(String line) {
        return line.contains("-") && TABLE_SEP.matcher(line).matches();
    }

    private static boolean isQuoteLine(String line) {
        return line.trim().startsWith(">");
    }

    /** 去掉一层或多层 `>` 引子，返回引用里的正文。 */
    private static String quoteContent(String line) {
        String s = line.trim();
        while (s.startsWith(">")) {
            s = s.substring(1);
            if (s.startsWith(" ")) s = s.substring(1);
        }
        return s;
    }

    private static String[] splitRow(String line) {
        String s = line.trim();
        if (s.length() > 1 && s.charAt(0) == '|') s = s.substring(1);
        if (s.length() > 1 && s.charAt(s.length() - 1) == '|') s = s.substring(0, s.length() - 1);
        String[] parts = s.trim().split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    // --------------------------------------------------------------- sync

    /** true = 用框架节点树渲染（阶段 5 迁移）；false = 旧的自建 View 路径，真机 A/B 用。 */
    public static boolean USE_FRAMEWORK = true;

    public static void sync(LinearLayout bubble, String md, Context ctx, Style style) {
        sync(bubble, md, ctx, style, false);
    }

    /** streaming=true 时给尾部新增的文字做淡入；false 时收尾清理。 */
    public static void sync(LinearLayout bubble, String md, Context ctx, Style style,
                            boolean streaming) {
        if (USE_FRAMEWORK) {
            ChatBlockView.render(bubble, md, streaming);
            return;
        }
        List<Block> blocks = split(md);
        Store store = (Store) bubble.getTag();
        if (store == null) {
            store = new Store();
            bubble.setTag(store);
        }
        int preViews = store.views.size();
        int common = 0;
        while (common < store.kinds.size() && common < blocks.size()
                && store.kinds.get(common) == blocks.get(common).kind) {
            common++;
        }
        while (store.views.size() > common) {
            bubble.removeView(store.views.remove(store.views.size() - 1));
            store.kinds.remove(store.kinds.size() - 1);
        }
        // A kind-equal prefix can still hide a re-segmentation: while streaming, the growing
        // PROSE/CODE tail gets "peeled" once a table/fence completes inside it (old [P_big]
        // becomes new [P_small, TABLE]). Nothing mismatches, yet the kept former-tail view
        // must be re-rendered from its now-smaller block or stale raw markdown stays visible.
        boolean peeled = preViews > 0 && common == preViews && blocks.size() > preViews;
        for (int i = common; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            View v = buildView(ctx, b, style);
            bubble.addView(v);
            store.views.add(v);
            store.kinds.add(b.kind);
        }
        if (store.views.isEmpty()) { stopStream(store.stream); return; }
        for (int i = common; i < store.views.size(); i++) {
            renderInto(ctx, store.views.get(i), blocks.get(i), style);
        }
        renderInto(ctx, store.views.get(store.views.size() - 1),
                blocks.get(blocks.size() - 1), style);
        if (peeled) {
            renderInto(ctx, store.views.get(preViews - 1), blocks.get(preViews - 1), style);
        }
        applyStreamTail(store, streaming);
    }

    // --------------------------------------------------------- streaming tail

    /** PROSE 是 TextView 本身，CODE 卡片取 body，TABLE 没有可流式的主 TextView。 */
    private static TextView primaryTextView(View v) {
        if (v instanceof TextView) return (TextView) v;
        View body = v.findViewById(0x44544142);
        return body instanceof TextView ? (TextView) body : null;
    }

    private static void applyStreamTail(Store store, boolean streaming) {
        StreamState s = store.stream;
        if (!streaming || store.views.isEmpty()) {
            stopStream(s);
            return;
        }
        TextView tv = primaryTextView(store.views.get(store.views.size() - 1));
        if (tv == null) { stopStream(s); return; }

        // renderInto 刚把正文写进去。上一帧的 FadeSpan 随 setText 一起消失，
        // 那部分文字本就该全亮，所以这里只给「最后一批新字」做淡入，不会整块闪。
        SpannableStringBuilder ssb = new SpannableStringBuilder(tv.getText());
        if (s.fadeAnim != null) { s.fadeAnim.cancel(); s.fadeAnim = null; }
        int prev = Math.min(s.plainLen, ssb.length());
        if (ssb.length() > prev) {
            final FadeSpan fade = new FadeSpan();
            ssb.setSpan(fade, prev, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.fade = fade;
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(160);
            anim.setInterpolator(new DecelerateInterpolator(1.5f));
            anim.addUpdateListener(a -> {
                fade.alpha = (Float) a.getAnimatedValue();
                tv.invalidate();
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    fade.alpha = 1f;
                    tv.invalidate();
                }
            });
            anim.start();
            s.fadeAnim = anim;
        } else {
            s.fade = null;
        }
        s.plainLen = ssb.length();
        tv.setText(ssb);
    }

    private static void stopStream(StreamState s) {
        if (s.fadeAnim != null) { s.fadeAnim.cancel(); s.fadeAnim = null; }
        if (s.fade != null) s.fade.alpha = 1f;
        s.fade = null;
        s.plainLen = 0;
    }

    // ---------------------------------------------------------------- views

    private static View buildView(Context ctx, Block b, Style style) {
        switch (b.kind) {
            case TABLE:
                return buildTableCard(ctx, style, b.quoted);
            case CODE:
                return buildCodeCard(ctx, style);
            default:
                return buildProse(ctx, style);
        }
    }

    private static void renderInto(Context ctx, View view, Block b, Style style) {
        if (b.kind == Kind.PROSE) {
            TextView tv = (TextView) view;
            // 必须走 markwon.setMarkdown：只有它触发 beforeSetText/afterSetText 钩子，
            // AsyncDrawableScheduler 才会被调度去渲染 LaTeX 公式。直接 setText(render(...))
            // 只做了解析，公式会永远停在原文。
            MarkdownRenderer.setText(tv, b.text);
        } else if (b.kind == Kind.TABLE) {
            // 引用块里的表格外面还套了一层「竖线 + 卡片」，表格本体按 id 找。
            View card = view.findViewById(TABLE_CARD_ID);
            renderTableGrid((LinearLayout) (card != null ? card : view), b.rows, style);
        } else {
            TextView body = (TextView) ((LinearLayout) view).findViewById(0x44544142);
            body.setText(b.text);
            TextView lang = (TextView) ((LinearLayout) view).findViewById(0x44544147);
            TextView copy = (TextView) ((LinearLayout) view).findViewById(0x44544143);
            String tag = (String) copy.getTag();
            if (tag != null && tag.equals(b.text)) {
                copy.setText(HostTheme.label("copy", ctx.getString(R.string.copy_copied)));
            } else {
                copy.setText(HostTheme.label("copy", ctx.getString(R.string.copy_code)));
            }
            lang.setText(b.lang == null || b.lang.isEmpty() ? "code" : b.lang);
        }
    }

    private static void setLayoutFill(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        v.setLayoutParams(lp);
    }

    private static TextView buildProse(Context ctx, Style style) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(17);
        tv.setTextColor(style.text);
        tv.setLineSpacing(6f, 1f);
        tv.setLinkTextColor(style.brand);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        setLayoutFill(tv);
        return tv;
    }

    /** 表格卡片：背景 + 横向滚动的网格。引用块里的表格在左侧补一根竖线（与引用
     *  文字的竖条同色同宽），卡片左边改成直角，让线紧贴表格、不出现缝隙。 */
    private static View buildTableCard(Context ctx, Style style, boolean quoted) {
        LinearLayout card = new LinearLayout(ctx);
        card.setId(TABLE_CARD_ID);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(quoted
                ? roundedLeftSquare(style.codeBg, dp(10))
                : rounded(style.codeBg, dp(10)));
        setLayoutFill(card);

        // 表格按内容自然宽度排布：塞不下时整表横向滚动，而不是把路径列压成一条。
        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(true);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        hsv.addView(grid, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        card.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!quoted) return card;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Markwon 的引用竖条是内缩 3dp 画的（x=38 而不是 32），这里跟着缩进，
        // 免得混排时文字竖线和表格竖线差一根线的宽度。
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.leftMargin = dp(3);
        row.setLayoutParams(rowLp);
        View bar = new View(ctx);
        bar.setBackgroundColor(style.caption);
        row.addView(bar, new LinearLayout.LayoutParams(
                dp(3), LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(card, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static View buildCodeCard(Context ctx, Style style) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(style.codeBg, dp(10)));
        setLayoutFill(card);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(8), dp(6));

        TextView lang = new TextView(ctx);
        lang.setId(0x44544147);
        lang.setText("code");
        lang.setTextSize(11);
        lang.setTextColor(style.caption);
        lang.setTypeface(Typeface.MONOSPACE);

        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView copy = new TextView(ctx);
        copy.setId(0x44544143);
        copy.setText(ctx.getString(R.string.copy_code));
        copy.setTextSize(11);
        copy.setTextColor(style.brand);
        copy.setPadding(dp(8), dp(4), dp(8), dp(4));

        header.addView(lang, langLp);
        header.addView(copy);

        View divider = new View(ctx);
        divider.setBackgroundColor(style.border);

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(true);
        TextView body = new TextView(ctx);
        body.setId(0x44544142);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(16);
        body.setTextColor(style.secondary);
        body.setPadding(dp(12), dp(10), dp(12), dp(12));
        hsv.addView(body, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));

        card.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)));
        card.addView(hsv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            String code = (String) body.getText();
            cm.setPrimaryClip(ClipData.newPlainText("code", code));
            copy.setText(ctx.getString(R.string.copy_copied));
            copy.setTag(code);
            Toast.makeText(ctx, ctx.getString(R.string.copied_clipboard), Toast.LENGTH_SHORT).show();
        });
        return card;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** 左边两角为直角：引用表格要贴着左侧竖线，圆角会露出缝。 */
    private static GradientDrawable roundedLeftSquare(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        return g;
    }

    // ----------------------------------------------------------------- table

    /** Lightweight inline markdown for table cells: strips `**b**`, `*i*`,
     * `_i_` and `` `code` `` markers so cells don't show raw markdown syntax. */
    private static CharSequence renderCell(String s) {
        if (s == null || s.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("**", i + 2);
                if (close > i + 2) {
                    int start = sb.length();
                    sb.append(s, i + 2, close);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }
            if (ch == '`') {
                int close = s.indexOf('`', i + 1);
                if (close > i) {
                    int start = sb.length();
                    sb.append(s, i + 1, close);
                    sb.setSpan(new TypefaceSpan("monospace"), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(0x1A2C2C2E), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                char marker = ch;
                int close = s.indexOf(marker, i + 1);
                while (close > i && close < n - 1 && Character.isLetterOrDigit(s.charAt(close + 1))) {
                    close = s.indexOf(marker, close + 1);
                }
                if (close > i + 1) {
                    int start = sb.length();
                    sb.append(s, i + 1, close);
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
        return sb;
    }

    /** Renders the table as a real grid: one left-aligned TextView per cell, column
     * widths from the column's widest cell text (capped so one long path cannot
     * stretch the table to several screens); the grid fills the viewport when it
     * fits and scrolls horizontally when it does not. Bold header row with
     * translucent background, alternating body rows and 1px separators. */
    private static void renderTableGrid(LinearLayout card, List<String[]> rows, Style style) {
        if (card.getChildCount() == 0) return;             // 结构由 buildTableCard 建好
        HorizontalScrollView hsv = (HorizontalScrollView) card.getChildAt(0);
        LinearLayout gridView = (LinearLayout) hsv.getChildAt(0);
        gridView.removeAllViews();
        if (rows == null || rows.isEmpty()) return;
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        if (cols == 0) return;

        int headerBg = 0x1AFFFFFF;
        int stripeBg = 0x0AFFFFFF;

        final List<LinearLayout> lineViews = new ArrayList<>(rows.size());
        final List<TextView[]> grid = new ArrayList<>(rows.size());
        final float[] ideal = new float[cols];
        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            LinearLayout row = new LinearLayout(card.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(ri == 0 ? headerBg : (ri % 2 == 0 ? stripeBg : 0x00000000));
            TextView[] cells = new TextView[cols];
            for (int c = 0; c < cols; c++) {
                String raw = c < r.length && r[c] != null ? r[c].trim() : "";
                TextView cell = new TextView(card.getContext());
                cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                cell.setPadding(dp(8), dp(6), dp(8), dp(6));
                cell.setTextSize(14);
                cell.setTextColor(style.text);
                cell.setText(renderCell(raw));
                if (ri == 0) cell.setTypeface(Typeface.DEFAULT_BOLD);
                cells[c] = cell;
                // 用单元格自己的 paint 量宽度：字号、粗体、字体缩放都由 TextView 决定。
                // 另起一个 TextPaint 会在系统字体缩放不是 1 时量偏小，把 2026-11-11
                // 这种 token 拆成两行。
                for (String token : raw.split("\\s+")) {
                    ideal[c] = Math.max(ideal[c], cell.getPaint().measureText(token));
                }
            }
            lineViews.add(row);
            grid.add(cells);
        }

        // +dp(3)：正好等于 token 宽度时，字宽取整会差一像素把最后一个字挤到下一行
        final int[] want = new int[cols];
        for (int c = 0; c < cols; c++) {
            want[c] = (int) Math.ceil(ideal[c]) + dp(8) * 2 + dp(3);
        }
        for (int ri = 0; ri < lineViews.size(); ri++) {
            LinearLayout row = lineViews.get(ri);
            TextView[] cells = grid.get(ri);
            for (int c = 0; c < cols; c++) {
                row.addView(cells[c], new LinearLayout.LayoutParams(
                        want[c], LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            gridView.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            if (ri < rows.size() - 1) {
                View sep = new View(card.getContext());
                sep.setBackgroundColor(style.border);
                gridView.addView(sep, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        // 表格比视口窄时按比例铺满；比视口宽就保持自然宽度，交给 HorizontalScrollView。
        hsv.post(() -> fitColumns(hsv, grid));
    }

    /** 按整格文本宽定量一次列宽（超宽格封顶，避免一条长路径把整表撑到几千像素）：
     *  短列（1.2K、2026-11-11 22:03）一行放得下，长列按上限折行；放不下就横向滚动。 */
    private static void fitColumns(HorizontalScrollView hsv, List<TextView[]> grid) {
        final int avail = hsv.getWidth() - hsv.getPaddingLeft() - hsv.getPaddingRight();
        if (avail <= 0 || grid.isEmpty()) return;
        final int cols = grid.get(0).length;
        final int cap = (int) (avail * 0.55f);
        final int[] want = new int[cols];
        for (TextView[] row : grid) {
            for (int c = 0; c < cols; c++) {
                final TextView cell = row[c];
                final CharSequence t = cell.getText();
                float w = cell.getPaint().measureText(t, 0, t.length());
                want[c] = Math.max(want[c], Math.min((int) Math.ceil(w), cap));
            }
        }
        int total = 0;
        for (int c = 0; c < cols; c++) {
            want[c] += dp(8) * 2 + dp(3);
            total += want[c];
        }

        final int[] width = new int[cols];
        if (total < avail) {
            int used = 0;
            for (int c = 0; c < cols; c++) {
                width[c] = (int) ((long) avail * want[c] / total);
                used += width[c];
            }
            width[cols - 1] += avail - used;
        } else {
            System.arraycopy(want, 0, width, 0, cols);   // 横向滚动
        }

        for (TextView[] row : grid) {
            for (int c = 0; c < cols; c++) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) row[c].getLayoutParams();
                lp.width = width[c];
                lp.weight = 0;
                row[c].setLayoutParams(lp);
            }
        }
    }
}