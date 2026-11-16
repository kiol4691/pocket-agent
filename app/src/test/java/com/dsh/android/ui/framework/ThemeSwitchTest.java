package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切主题只重解析 token，节点树本身必须保持不变（节点不可变、解析器只读）。
 * 渲染器（View 层）的 A/B 在真机对照页验证，这里守住纯 Java 的一半。
 */
public class ThemeSwitchTest {

    private static UiNode tree() {
        return UiNode.column().key("root").prop("gap", "sm")
                .child(UiNode.card().key("card").prop("tone", "accent")
                        .child(UiNode.text("标题").key("title").prop("size", "title"))
                        .child(UiNode.code("$ make").key("code")))
                .build();
    }

    private static ThemePack pack() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("color.brand", "#FF0000");
        t.put("font.body", 20);
        t.put("space.m", 20);
        return ThemePack.fromJava(t);
    }

    @Test
    public void resolvingTokensDoesNotTouchTheTree() {
        UiNode before = tree();
        String snapshot = before.props().toString();
        int kids = before.children().size();

        TokenResolver a = new PackTokenResolver(new Defaults(), pack(), true);
        TokenResolver b = new PackTokenResolver(new Defaults(), ThemePack.fromJava(null), false);
        for (String size : new String[]{"title", "body", "caption"}) {
            a.sizeSp(size);
            b.sizeSp(size);
        }
        a.textColor("brand");
        b.textColor("brand");
        a.spaceDp("md");
        b.radiusDp("md");

        assertEquals(snapshot, before.props().toString());
        assertEquals(kids, before.children().size());
        assertEquals("root", before.key());
        assertEquals("card", before.children().get(0).key());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void nodePropsAreImmutable() {
        tree().props().put("tone", "danger");
    }

    private static final class Defaults implements TokenResolver {
        @Override public float sizeSp(String token) { return 15f; }
        @Override public int textColor(String tone) { return 0xFF111111; }
        @Override public int cardColor(String tone) { return 0xFF222222; }
        @Override public int codeBackground() { return 0xFF333333; }
        @Override public int dividerColor() { return 0xFF444444; }
        @Override public int badgeColor(String tone) { return 0xFF555555; }
        @Override public int spaceDp(String token) { return 8; }
        @Override public int radiusDp(String token) { return 12; }
        @Override public int dp(float dp) { return (int) dp; }
        @Override public int brandColor() { return 0xFF666666; }
        @Override public int onBrandColor() { return 0xFF777777; }
        @Override public int borderColor() { return 0xFF888888; }
    }
}
