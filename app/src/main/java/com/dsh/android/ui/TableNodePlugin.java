package com.dsh.android.ui;

import android.text.Spanned;

import androidx.annotation.NonNull;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.Node;
import org.commonmark.node.Text;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.MarkwonVisitor;
import io.noties.markwon.SpannableBuilder;

/** 用 WeightedTableSpan 接管 TableBlock 的渲染（保留 TablePlugin 的 GFM 解析）。 */
public class TableNodePlugin extends AbstractMarkwonPlugin {

    private final float textSizePx;

    public TableNodePlugin(float textSizePx) { this.textSizePx = textSizePx; }

    @Override
    public void configureVisitor(@NonNull MarkwonVisitor.Builder builder) {
        builder.on(TableBlock.class, new MarkwonVisitor.NodeVisitor<TableBlock>() {
            @Override
            public void visit(@NonNull MarkwonVisitor visitor, @NonNull TableBlock node) {
                final WeightedTableSpan span = new WeightedTableSpan(textSizePx);
                for (Node n = node.getFirstChild(); n != null; n = n.getNext()) {
                    final boolean header = (n instanceof TableHead);
                    if (n instanceof TableHead || n instanceof TableBody) {
                        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                            if (c instanceof TableRow) span.addRow(cells((TableRow) c), header);
                        }
                    } else if (n instanceof TableRow) {
                        span.addRow(cells((TableRow) n), false);
                    }
                }

                final SpannableBuilder b = visitor.builder();
                visitor.ensureNewLine();
                final int start = visitor.length();
                b.append("\uFFFC");
                b.setSpan(span, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                visitor.ensureNewLine();
            }
        });
    }

    private static List<String> cells(TableRow row) {
        final List<String> out = new ArrayList<>();
        for (Node cn = row.getFirstChild(); cn != null; cn = cn.getNext()) {
            if (!(cn instanceof TableCell)) continue;
            final TableCell cell = (TableCell) cn;
            final StringBuilder sb = new StringBuilder();
            for (Node n = cell.getFirstChild(); n != null; n = n.getNext()) collect(n, sb);
            out.add(sb.toString().trim());
        }
        return out;
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Text) sb.append(((Text) node).getLiteral());
        for (Node n = node.getFirstChild(); n != null; n = n.getNext()) collect(n, sb);
    }
}
