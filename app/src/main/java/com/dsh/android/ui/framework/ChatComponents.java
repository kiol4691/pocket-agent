package com.dsh.android.ui.framework;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.dsh.android.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聊天内容专用组件：表格卡、代码卡、工具卡。
 *
 * 这三类原来在 {@code BubbleBuilder}/{@code MainActivity} 里手搓 View；现在作为框架组件，
 * 由节点树驱动、按 key 复用。渲染器需要先 {@link #install(UiRenderer)}。
 *
 * 视觉与旧实现保持一致：表格按整格文本量列宽、超宽横向滚动、引用表格左侧竖线；
 * 代码卡带语言头与复制；工具卡有 running/done 状态与可展开的入参/输出。
 */
public final class ChatComponents {

    public static final String TABLE = "table";
    public static final String CODE_CARD = "codeCard";
    public static final String TOOL_CARD = "toolCard";

    /** 工具卡的输出/状态胶囊给宿主直接更新（保留旧的脉冲动画与历史回放逻辑）。 */
    public static final int TOOL_PILL_ID = 0x44544161;
    public static final int TOOL_OUT_ID = 0x44544162;
    public static final int TOOL_BODY_ID = 0x44544163;
    public static final int TOOL_GLYPH_ID = 0x44544164;

    private ChatComponents() {
    }

    public static void install(UiRenderer renderer) {
        renderer.register(TABLE, table());
        renderer.register(CODE_CARD, codeCard());
        renderer.register(TOOL_CARD, toolCard());
    }

    // ---------------------------------------------------------------- 表格

    private static final class TableState {
        final View bar;
        final LinearLayout card;
        final HorizontalScrollView hsv;
        final LinearLayout grid;
        String signature = "";
        List<TextView[]> cells = new ArrayList<>();

        TableState(View bar, LinearLayout card, HorizontalScrollView hsv, LinearLayout grid) {
            this.bar = bar;
            this.card = card;
            this.hsv = hsv;
            this.grid = grid;
        }
    }

    private static UiComponent table() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout root = new LinearLayout(ctx);
                root.setOrientation(LinearLayout.HORIZONTAL);

                View bar = new View(ctx);
                root.addView(bar, new LinearLayout.LayoutParams(
                        env.theme().dp(3), ViewGroup.LayoutParams.MATCH_PARENT));

                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);

                HorizontalScrollView hsv = new HorizontalScrollView(ctx);
                hsv.setHorizontalScrollBarEnabled(true);
                LinearLayout grid = new LinearLayout(ctx);
                grid.setOrientation(LinearLayout.VERTICAL);
                hsv.addView(grid, new HorizontalScrollView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(hsv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                root.addView(card, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                root.setTag(new TableState(bar, card, hsv, grid));
                return root;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                TableState st = (TableState) view.getTag();
                boolean quoted = node.bool("quoted", false);
                boolean retheme = Builtins.retheme(view, env);
                List<String[]> rows = rowsOf(node);
                String sig = signature(rows, quoted);
                if (!retheme && sig.equals(st.signature)) return;
                st.signature = sig;

                view.setPadding(quoted ? env.theme().dp(3) : 0, 0, 0, 0);
                st.bar.setVisibility(quoted ? View.VISIBLE : View.GONE);
                st.bar.setBackgroundColor(env.theme().textColor("caption"));
                st.card.setBackground(quoted
                        ? roundedLeftSquare(env.theme().codeBackground(), env.theme().dp(10))
                        : rounded(env.theme().codeBackground(), env.theme().dp(10)));
                rebuildGrid(ctx, st, rows, env.theme());
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    /** 表格内容签名：逐格拼文本。rows.toString() 只是数组 identity，内容变化时会漏刷新。 */
    private static String signature(List<String[]> rows, boolean quoted) {
        StringBuilder sb = new StringBuilder(quoted ? "q" : "n");
        for (String[] row : rows) {
            sb.append('|');
            if (row == null) continue;
            for (String cell : row) sb.append(cell == null ? "" : cell).append('\u001f');
        }
        return sb.toString();
    }

    private static List<String[]> rowsOf(UiNode node) {
        List<String[]> out = new ArrayList<>();
        Object raw = node.props().get("rows");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (o instanceof String[]) {
                out.add((String[]) o);
            } else if (o instanceof List) {
                List<?> l = (List<?>) o;
                String[] row = new String[l.size()];
                for (int i = 0; i < row.length; i++) {
                    Object c = l.get(i);
                    row[i] = c == null ? "" : String.valueOf(c);
                }
                out.add(row);
            }
        }
        return out;
    }

    private static void rebuildGrid(Context ctx, TableState st, List<String[]> rows,
                                    TokenResolver theme) {
        st.grid.removeAllViews();
        st.cells = new ArrayList<>();
        if (rows.isEmpty()) return;
        int cols = 0;
        for (String[] r : rows) cols = Math.max(cols, r.length);
        if (cols == 0) return;

        int headerBg = 0x1AFFFFFF;
        int stripeBg = 0x0AFFFFFF;
        List<LinearLayout> lines = new ArrayList<>(rows.size());
        float[] ideal = new float[cols];

        for (int ri = 0; ri < rows.size(); ri++) {
            String[] r = rows.get(ri);
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setBackgroundColor(ri == 0 ? headerBg : (ri % 2 == 0 ? stripeBg : 0x00000000));
            TextView[] cells = new TextView[cols];
            for (int c = 0; c < cols; c++) {
                String raw = c < r.length && r[c] != null ? r[c].trim() : "";
                TextView cell = new TextView(ctx);
                cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                cell.setPadding(theme.dp(8), theme.dp(6), theme.dp(8), theme.dp(6));
                cell.setTextSize(14);
                cell.setTextColor(theme.textColor("primary"));
                cell.setText(renderCell(raw));
                UiSelection.include(cell);
                if (ri == 0) cell.setTypeface(Typeface.DEFAULT_BOLD);
                cells[c] = cell;
                for (String token : raw.split("\\s+")) {
                    ideal[c] = Math.max(ideal[c], cell.getPaint().measureText(token));
                }
            }
            lines.add(line);
            st.cells.add(cells);
        }

        int[] want = new int[cols];
        for (int c = 0; c < cols; c++) {
            want[c] = (int) Math.ceil(ideal[c]) + theme.dp(8) * 2 + theme.dp(3);
        }
        for (int ri = 0; ri < lines.size(); ri++) {
            LinearLayout line = lines.get(ri);
            TextView[] cells = st.cells.get(ri);
            for (int c = 0; c < cols; c++) {
                line.addView(cells[c], new LinearLayout.LayoutParams(
                        want[c], ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            st.grid.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (ri < rows.size() - 1) {
                View sep = new View(ctx);
                sep.setBackgroundColor(theme.borderColor());
                st.grid.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, theme.dp(1)));
            }
        }
        st.hsv.post(() -> fitColumns(st.hsv, st.cells, theme));
    }

    /** 表格比视口窄时按比例铺满，超宽保持自然宽度交给横向滚动。 */
    private static void fitColumns(HorizontalScrollView hsv, List<TextView[]> grid,
                                   TokenResolver theme) {
        int avail = hsv.getWidth() - hsv.getPaddingLeft() - hsv.getPaddingRight();
        if (avail <= 0 || grid.isEmpty()) return;
        int cols = grid.get(0).length;
        int cap = (int) (avail * 0.55f);
        int[] want = new int[cols];
        for (TextView[] row : grid) {
            for (int c = 0; c < cols; c++) {
                TextView cell = row[c];
                CharSequence t = cell.getText();
                float w = cell.getPaint().measureText(t, 0, t.length());
                want[c] = Math.max(want[c], Math.min((int) Math.ceil(w), cap));
            }
        }
        int total = 0;
        for (int c = 0; c < cols; c++) {
            want[c] += theme.dp(8) * 2 + theme.dp(3);
            total += want[c];
        }
        int[] width = new int[cols];
        if (total < avail) {
            int used = 0;
            for (int c = 0; c < cols; c++) {
                width[c] = (int) ((long) avail * want[c] / total);
                used += width[c];
            }
            width[cols - 1] += avail - used;
        } else {
            width = want;
        }
        for (TextView[] row : grid) {
            for (int c = 0; c < cols; c++) {
                ViewGroup.LayoutParams lp = row[c].getLayoutParams();
                if (lp != null && lp.width != width[c]) {
                    lp.width = width[c];
                    row[c].setLayoutParams(lp);
                }
            }
        }
    }

    /** 表格单元格里的轻量 Markdown：只认 **粗体**、*斜体*、`代码`。 */
    private static CharSequence renderCell(String s) {
        if (s == null || s.isEmpty()) return "";
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '*' && i + 1 < n && s.charAt(i + 1) == '*') {
                int close = s.indexOf("**", i + 2);
                if (close > i + 2) {
                    int start = sb.length();
                    sb.append(s, i + 2, close);
                    sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, sb.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }
            if (ch == '`') {
                int close = s.indexOf('`', i + 1);
                if (close > i) {
                    int start = sb.length();
                    sb.append(s, i + 1, close);
                    sb.setSpan(new android.text.style.TypefaceSpan("monospace"), start, sb.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new android.text.style.BackgroundColorSpan(0x1A2C2C2E),
                            start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int close = s.indexOf(ch, i + 1);
                while (close > i && close < n - 1 && Character.isLetterOrDigit(s.charAt(close + 1))) {
                    close = s.indexOf(ch, close + 1);
                }
                if (close > i + 1) {
                    int start = sb.length();
                    sb.append(s, i + 1, close);
                    sb.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC), start, sb.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
        return sb;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static GradientDrawable roundedLeftSquare(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadii(new float[]{0, 0, radius, radius, radius, radius, 0, 0});
        return g;
    }

    // ---------------------------------------------------------------- 代码卡

    private static final class CodeState {
        final TextView lang;
        final TextView copy;
        final TextView body;
        final LinearLayout card;
        final View divider;
        boolean copied;

        CodeState(TextView lang, TextView copy, TextView body, LinearLayout card, View divider) {
            this.lang = lang;
            this.copy = copy;
            this.body = body;
            this.card = card;
            this.divider = divider;
        }
    }

    private static UiComponent codeCard() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout card = new LinearLayout(ctx);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(rounded(env.theme().codeBackground(), env.theme().dp(10)));

                LinearLayout header = new LinearLayout(ctx);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(env.theme().dp(12), env.theme().dp(6),
                        env.theme().dp(8), env.theme().dp(6));

                TextView lang = new TextView(ctx);
                lang.setText("code");
                lang.setTextSize(11);
                lang.setTextColor(env.theme().textColor("caption"));
                lang.setTypeface(Typeface.MONOSPACE);

                TextView copy = new TextView(ctx);
                copy.setText(HostTheme.label("copy", ctx.getString(R.string.copy_code)));
                copy.setTextSize(11);
                copy.setTextColor(env.theme().brandColor());
                copy.setPadding(env.theme().dp(8), env.theme().dp(4),
                        env.theme().dp(8), env.theme().dp(4));

                header.addView(lang, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                header.addView(copy);

                View divider = new View(ctx);
                divider.setBackgroundColor(env.theme().borderColor());

                HorizontalScrollView hsv = new HorizontalScrollView(ctx);
                hsv.setHorizontalScrollBarEnabled(true);
                TextView body = new TextView(ctx);
                body.setTypeface(Typeface.MONOSPACE);
                body.setTextSize(16);
                body.setTextColor(env.theme().textColor("secondary"));
                body.setPadding(env.theme().dp(12), env.theme().dp(10),
                        env.theme().dp(12), env.theme().dp(12));
                UiSelection.include(body);
                hsv.addView(body, new HorizontalScrollView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                card.addView(header, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                card.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, env.theme().dp(1)));
                card.addView(hsv, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                CodeState st = new CodeState(lang, copy, body, card, divider);
                card.setTag(st);
                copy.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    String code = body.getText().toString();
                    cm.setPrimaryClip(ClipData.newPlainText("code", code));
                    st.copied = true;
                    copy.setText(R.string.copy_copied);
                    copy.setTag(code);
                    Toast.makeText(ctx, ctx.getString(R.string.copied_clipboard),
                            Toast.LENGTH_SHORT).show();
                });
                return card;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                CodeState st = (CodeState) view.getTag();
                if (Builtins.retheme(view, env)) {
                    st.card.setBackground(rounded(env.theme().codeBackground(), env.theme().dp(10)));
                    st.lang.setTextColor(env.theme().textColor("caption"));
                    if (!st.copied) {
                        st.copy.setText(HostTheme.label("copy", ctx.getString(R.string.copy_code)));
                    }
                    st.copy.setTextColor(env.theme().brandColor());
                    st.divider.setBackgroundColor(env.theme().borderColor());
                    st.body.setTextColor(env.theme().textColor("secondary"));
                }
                String lang = node.str("lang", "");
                String label = lang == null || lang.isEmpty() ? "code" : lang;
                if (!label.contentEquals(st.lang.getText())) st.lang.setText(label);

                String body = node.str("body", "");
                if (!body.contentEquals(st.body.getText())) {
                    st.body.setText(body);
                }
                // 复制态标签只在内容变化时回写，避免每次 apply 都 setText
                String copyLabel = HostTheme.label("copy", ctx.getString(body.equals(st.copy.getTag())
                        ? R.string.copy_copied : R.string.copy_code));
                if (!copyLabel.contentEquals(st.copy.getText())) st.copy.setText(copyLabel);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    // ---------------------------------------------------------------- 工具卡

    private static UiComponent toolCard() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout root = new LinearLayout(ctx);
                root.setOrientation(LinearLayout.VERTICAL);

                LinearLayout header = new LinearLayout(ctx);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(0, env.theme().dp(4), 0, env.theme().dp(4));

                TextView glyph = new TextView(ctx);
                glyph.setId(TOOL_GLYPH_ID);
                glyph.setTextSize(13);
                glyph.setTextColor(env.theme().textColor("caption"));
                glyph.setPadding(0, 0, env.theme().dp(6), 0);
                glyph.setMinWidth(env.theme().dp(20));

                TextView title = new TextView(ctx);
                title.setTextSize(14);
                title.setTypeface(null, Typeface.BOLD);
                title.setTextColor(env.theme().textColor("primary"));

                TextView dot = new TextView(ctx);
                dot.setText(" · ");
                dot.setTextSize(12);
                dot.setTextColor(env.theme().textColor("caption"));

                TextView summary = new TextView(ctx);
                summary.setTextSize(13);
                summary.setTextColor(ctx.getColor(R.color.text_tertiary));
                summary.setMaxLines(1);
                summary.setEllipsize(TextUtils.TruncateAt.END);

                TextView pill = new TextView(ctx);
                pill.setId(TOOL_PILL_ID);
                pill.setText(ctx.getString(R.string.pill_running));
                pill.setTextSize(10);
                pill.setTextColor(ctx.getColor(R.color.text_tertiary));
                pill.setPadding(env.theme().dp(8), env.theme().dp(2),
                        env.theme().dp(8), env.theme().dp(2));
                pill.setBackgroundResource(R.drawable.bg_chip);

                header.addView(glyph);
                header.addView(title);
                header.addView(dot);
                header.addView(summary, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                plp.leftMargin = env.theme().dp(6);
                header.addView(pill, plp);

                LinearLayout body = new LinearLayout(ctx);
                body.setId(TOOL_BODY_ID);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setBackgroundResource(R.drawable.bg_tool);
                body.setPadding(env.theme().dp(14), env.theme().dp(10),
                        env.theme().dp(14), env.theme().dp(10));
                body.setVisibility(View.GONE);

                TextView inCap = new TextView(ctx);
                inCap.setText(ctx.getString(R.string.in_label));
                inCap.setTextSize(10);
                inCap.setTextColor(env.theme().textColor("caption"));
                inCap.setTypeface(Typeface.MONOSPACE);

                TextView in = new TextView(ctx);
                in.setTextSize(12);
                in.setTypeface(Typeface.MONOSPACE);
                in.setTextColor(ctx.getColor(R.color.tool_text));
                in.setPadding(0, env.theme().dp(2), 0, env.theme().dp(8));
                UiSelection.include(in);

                View divider = new View(ctx);
                divider.setBackgroundColor(env.theme().borderColor());

                TextView outCap = new TextView(ctx);
                outCap.setText(ctx.getString(R.string.out_label));
                outCap.setTextSize(10);
                outCap.setTextColor(env.theme().textColor("caption"));
                outCap.setTypeface(Typeface.MONOSPACE);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                olp.topMargin = env.theme().dp(8);

                TextView out = new TextView(ctx);
                out.setId(TOOL_OUT_ID);
                out.setTextSize(12);
                out.setTypeface(Typeface.MONOSPACE);
                out.setTextColor(ctx.getColor(R.color.tool_text));
                out.setLineSpacing(2, 1.0f);
                out.setPadding(0, env.theme().dp(2), 0, env.theme().dp(4));
                out.setText(ctx.getString(R.string.pill_running));
                UiSelection.include(out);

                body.addView(inCap);
                body.addView(in);
                body.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, env.theme().dp(1)));
                body.addView(outCap, olp);
                body.addView(out);

                ToolState st = new ToolState(glyph, title, summary, pill, in, out, body);
                glyph.setTag(node.str("glyph", "◇"));
                header.setOnClickListener(v -> {
                    st.open = !st.open;
                    body.setVisibility(st.open ? View.VISIBLE : View.GONE);
                    glyph.setText(st.open ? "▾" : String.valueOf(glyph.getTag()));
                });

                root.addView(header);
                root.addView(body);
                root.setTag(st);
                return root;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                ToolState st = (ToolState) view.getTag();
                if (Builtins.retheme(view, env)) {
                    st.glyph.setTextColor(env.theme().textColor("caption"));
                    st.title.setTextColor(env.theme().textColor("primary"));
                    st.summary.setTextColor(ctx.getColor(R.color.text_tertiary));
                }
                String glyph = node.str("glyph", "◇");
                st.glyph.setTag(glyph);
                if (!st.open && !glyph.contentEquals(st.glyph.getText())) st.glyph.setText(glyph);

                String title = node.str("title", "");
                if (!title.contentEquals(st.title.getText())) st.title.setText(title);

                String summary = node.str("summary", "");
                if (!summary.contentEquals(st.summary.getText())) st.summary.setText(summary);

                String args = node.str("args", "");
                if (!args.contentEquals(st.in.getText())) st.in.setText(args);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static final class ToolState {
        final TextView glyph;
        final TextView title;
        final TextView summary;
        final TextView pill;
        final TextView in;
        final TextView out;
        final LinearLayout body;
        boolean open;

        ToolState(TextView glyph, TextView title, TextView summary, TextView pill,
                  TextView in, TextView out, LinearLayout body) {
            this.glyph = glyph;
            this.title = title;
            this.summary = summary;
            this.pill = pill;
            this.in = in;
            this.out = out;
            this.body = body;
        }
    }
}
