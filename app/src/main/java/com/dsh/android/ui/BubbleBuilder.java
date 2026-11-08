package com.dsh.android.ui;

import com.dsh.android.R;

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

    private static final class Block {
        final Kind kind;
        final String text;          // PROSE source or CODE source
        final String lang;          // CODE language, may be empty
        final List<String[]> rows;  // TABLE: rows[0] = header

        private Block(Kind kind, String text, String lang, List<String[]> rows) {
            this.kind = kind;
            this.text = text;
            this.lang = lang;
            this.rows = rows;
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
            if (i >= holdFrom && trim.startsWith("|") && !isTableSeparator(trim)) {
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

    private static String[] splitRow(String line) {
        String s = line.trim();
        if (s.length() > 1 && s.charAt(0) == '|') s = s.substring(1);
        if (s.length() > 1 && s.charAt(s.length() - 1) == '|') s = s.substring(0, s.length() - 1);
        String[] parts = s.trim().split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    // --------------------------------------------------------------- sync

    public static void sync(LinearLayout bubble, String md, Context ctx, Style style) {
        sync(bubble, md, ctx, style, false);
    }

    /** streaming=true 时给尾部新增的文字做淡入；false 时收尾清理。 */
    public static void sync(LinearLayout bubble, String md, Context ctx, Style style,
                            boolean streaming) {
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
                return buildTableCard(ctx, style);
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
            renderTableGrid((LinearLayout) view, b.rows, style);
        } else {
            TextView body = (TextView) ((LinearLayout) view).findViewById(0x44544142);
            body.setText(b.text);
            TextView lang = (TextView) ((LinearLayout) view).findViewById(0x44544147);
            TextView copy = (TextView) ((LinearLayout) view).findViewById(0x44544143);
            String tag = (String) copy.getTag();
            if (tag != null && tag.equals(b.text)) {
                copy.setText(ctx.getString(R.string.copy_copied));
            } else {
                copy.setText(ctx.getString(R.string.copy_code));
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

    private static View buildTableCard(Context ctx, Style style) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(style.codeBg, dp(10)));
        setLayoutFill(card);
        return card;
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
            copy.setText("⧉ 已复制");
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

    /** Renders the table as a real grid: one centered TextView per cell, equal
     * width columns (weight 1, fills the bubble), bold header row with
     * translucent background, alternating body rows and 1px horizontal
     * separators between rows. */
    private static void renderTableGrid(LinearLayout card, List<String[]> rows, Style style) {
        card.removeAllViews();
        if (rows == null || rows.isEmpty()) return;
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        if (cols == 0) return;

        int headerBg = 0x1AFFFFFF;
        int stripeBg = 0x0AFFFFFF;

        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            LinearLayout row = new LinearLayout(card.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(ri == 0 ? headerBg : (ri % 2 == 0 ? stripeBg : 0x00000000));
            for (int c = 0; c < cols; c++) {
                TextView cell = new TextView(card.getContext());
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(8), dp(6), dp(8), dp(6));
                cell.setTextSize(14);
                cell.setTextColor(style.text);
                cell.setText(renderCell(c < r.length && r[c] != null ? r[c] : ""));
                if (ri == 0) cell.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                row.addView(cell, cp);
            }
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            if (ri < rows.size() - 1) {
                View sep = new View(card.getContext());
                sep.setBackgroundColor(style.border);
                card.addView(sep, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
    }
}