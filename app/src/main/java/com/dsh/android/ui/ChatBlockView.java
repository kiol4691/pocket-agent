package com.dsh.android.ui;

import android.view.View;
import android.widget.LinearLayout;

import com.dsh.android.ui.framework.ChatComponents;
import com.dsh.android.ui.framework.HostTheme;
import com.dsh.android.ui.framework.UiFramework;
import com.dsh.android.ui.framework.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 把一条消息的 Markdown 切成块，用框架节点树渲染到气泡里。
 *
 * 与旧 {@link BubbleBuilder#sync} 的差别只在实现：块切分仍复用 {@link BubbleBuilder#split}，
 * 但表格/代码/工具卡和 prose 都变成声明式节点，diff 复用 View；流式尾部 prose 用
 * {@code text.chunks}，避免整段 setText。
 *
 * 旧路径保留在 {@code BubbleBuilder.USE_FRAMEWORK=false} 时，方便真机 A/B。
 */
public final class ChatBlockView {

    /** 插件 renderers 的接入点（由 HookRegistry 实现）；null 表示不启用。 */
    public interface BlockRenderer {
        /** @return 插件返回的视图节点；null = 不接管，走默认渲染 */
        UiNode render(String type, String lang, String body);
    }

    private static BlockRenderer blockRenderer;
    /** 调试统计：走 appendText 快路径的帧数。 */
    public static int fastFrames;
    public static long splitNanos;
    public static long appendNanos;

    public static void setBlockRenderer(BlockRenderer renderer) {
        blockRenderer = renderer;
    }

    private static final WeakHashMap<LinearLayout, ChatBlockView> INSTANCES = new WeakHashMap<>();

    private final UiFramework fw;
    private String lastMd;
    private String lastKinds;
    private String lastTailKey;
    private int lastTailIndex = -1;
    private int lastBlockCount;
    private boolean lastTailProse;
    private boolean lastStreaming;

    private ChatBlockView(LinearLayout bubble) {
        fw = new UiFramework(bubble);
        ChatComponents.install(fw.renderer());
        // WeakHashMap 的 key 虽然是弱引用，但值里的渲染器强引用着同一个气泡：
        // 气泡移出窗口后 key 永远回收不掉。这里在 detach 时主动断掉映射；
        // 重新 attach 后再调 render() 会重新创建实例（见 render 的 null 分支）。
        bubble.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                if (INSTANCES.get(v) == ChatBlockView.this) INSTANCES.remove(v);
            }
        });
    }

    public static void render(LinearLayout bubble, String md, boolean streaming) {
        ChatBlockView v = INSTANCES.get(bubble);
        if (v == null) {
            v = new ChatBlockView(bubble);
            INSTANCES.put(bubble, v);
        }
        v.doRender(md == null ? "" : md, streaming, false);
    }

    /**
     * 后台 renderer 求值完成：重渲染仍在界面上的气泡。
     *
     * <p>renderer 跑在 plugin-render 线程，结果已写入缓存，这里第二次渲染不会再跑 JS；
     * 只是把默认渲染的块换成插件返回的视图。
     */
    public static void invalidateAll() {
        invalidateAll(true);
    }

    /**
     * @param force true 时即使 md 与上次相同也走完整重渲染（跳过流式 appendText 快路径）。
     *              主题切换、插件结果回填会改变节点/皮肤，必须强制。
     */
    public static void invalidateAll(boolean force) {
        for (Map.Entry<LinearLayout, ChatBlockView> e : new ArrayList<>(INSTANCES.entrySet())) {
            LinearLayout bubble = e.getKey();
            ChatBlockView v = e.getValue();
            if (bubble.isAttachedToWindow() && v.lastMd != null) {
                v.doRender(v.lastMd, v.lastStreaming, force);
            }
        }
    }

    private void doRender(String md, boolean streaming, boolean force) {
        // 纯 prose 追加：不切分、不 diff，只 patch 尾部 text.chunks
        if (!force && streaming && lastMd != null && lastTailProse
                && lastTailIndex == lastBlockCount - 1 && md.startsWith(lastMd)) {
            String delta = md.substring(lastMd.length());
            if (isPlainProse(delta)) {
                if (!delta.isEmpty()) {
                    long a0 = System.nanoTime();
                    fw.appendText(lastTailKey, delta);
                    appendNanos += System.nanoTime() - a0;
                    fastFrames++;
                }
                lastMd = md;
                return;
            }
        }

        long s0 = System.nanoTime();
        List<BubbleBuilder.Block> blocks = BubbleBuilder.split(md);
        splitNanos += System.nanoTime() - s0;
        String kinds = kindSignature(blocks);

        UiNode.Builder root = UiNode.column().key("chat").prop("gap", "none")
                .prop("select", "cross");
        int tailIndex = -1;
        String tailKey = null;
        for (int i = 0; i < blocks.size(); i++) {
            BubbleBuilder.Block b = blocks.get(i);
            String key = "cb" + i;
            boolean tail = streaming && i == blocks.size() - 1;
            if (tail && b.kind == BubbleBuilder.Kind.PROSE) {
                tailIndex = i;
                tailKey = key;
            }
            switch (b.kind) {
                case PROSE: {
                    UiNode override = tail || blockRenderer == null
                            ? null : blockRenderer.render("text", "", b.text);
                    if (override != null) {
                        root.child(withKey(override, key));
                    } else {
                        UiNode.Builder n = UiNode.of(tail ? UiNode.TEXT_CHUNKS : UiNode.MARKDOWN)
                                .key(key)
                                .prop("text", b.text)
                                .prop("size", "prose")
                                .prop("maxChunk", 256)
                                .prop("lineSpacing", 6);
                        root.child(n);
                    }
                    break;
                }
                case TABLE: {
                    UiNode table = UiNode.of(ChatComponents.TABLE).key(key)
                            .prop("rows", b.rows)
                            .prop("quoted", b.quoted)
                            .build();
                    root.child(HostTheme.applyTableSkin(table));
                    break;
                }
                case CODE: {
                    // 流式时不跑插件：半截 fence 会让 renderer 反复超时/抛错
                    UiNode override = tail || blockRenderer == null
                            ? null : blockRenderer.render("fence", b.lang, b.text);
                    if (override != null) {
                        root.child(withKey(override, key));
                    } else {
                        UiNode card = UiNode.of(ChatComponents.CODE_CARD).key(key)
                                .prop("body", b.text)
                                .prop("lang", b.lang == null ? "" : b.lang)
                                .build();
                        root.child(HostTheme.applyCodeSkin(card, b.lang, b.text));
                    }
                    break;
                }
                default:
                    break;
            }
        }
        // 主题皮肤：message.bubble 的 skin 节点包住整条消息（需要 render.match）。
        UiNode tree = HostTheme.applyMessageSkin(root.build(), md);
        fw.render(tree);
        lastMd = md;
        lastKinds = kinds;
        lastTailIndex = tailIndex;
        lastTailKey = tailKey;
        lastBlockCount = blocks.size();
        lastTailProse = tailIndex >= 0;
        lastStreaming = streaming;
    }

    /** 追加内容不含会改变分块结构的标记（fence / 表格 / 引用）。 */
    private static boolean isPlainProse(String delta) {
        return !delta.contains("```")
                && !delta.contains("\n\n|")
                && !delta.contains("\n|")
                && !delta.contains("\n\n>")
                && !delta.contains("\n>");
    }

    /** 结构签名：块类型 + 表格行列数。尾部 prose 内容变化不影响这个签名。 */
    private static String kindSignature(List<BubbleBuilder.Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (BubbleBuilder.Block b : blocks) {
            sb.append(b.kind).append(':');
            if (b.kind == BubbleBuilder.Kind.TABLE && b.rows != null) {
                sb.append(b.rows.size()).append('x');
                sb.append(b.rows.isEmpty() ? 0 : b.rows.get(0).length).append('q')
                        .append(b.quoted ? 1 : 0);
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /** 插件返回的节点可能没有 key：复制一份并指定稳定的块 key。 */
    private static UiNode withKey(UiNode node, String key) {
        UiNode.Builder b = UiNode.of(node.type()).key(key);
        for (Map.Entry<String, Object> e : node.props().entrySet()) {
            b.prop(e.getKey(), e.getValue());
        }
        for (UiNode c : node.children()) b.child(c);
        return b.build();
    }
}
