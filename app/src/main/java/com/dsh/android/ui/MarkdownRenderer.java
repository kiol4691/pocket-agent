package com.dsh.android.ui;

import android.content.Context;
import android.text.Spanned;
import android.widget.TextView;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
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
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(MarkwonTheme.Builder builder) {
                        builder
                                .linkColor(brand)
                                .isLinkUnderlined(false)
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
                .usePlugin(JLatexMathPlugin.create(16f, builder -> builder
                        .theme()
                        .textColor(0xFFF9FAFB)
                        .inlinePadding(io.noties.markwon.ext.latex.JLatexMathTheme.Padding.of(6, 2, 6, 2))))
                .build();
    }

    public static CharSequence render(String md) {
        return markwon.toMarkdown(md == null ? "" : md);
    }

    public static void setText(TextView textView, String md) {
        markwon.setMarkdown(textView, md == null ? "" : md);
    }
}