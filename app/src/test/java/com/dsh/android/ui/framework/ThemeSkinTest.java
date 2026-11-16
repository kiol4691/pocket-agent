package com.dsh.android.ui.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** 皮肤应用：占位符替换、内容追加、非容器根回退。 */
public class ThemeSkinTest {

    private static UiNode skin(String text) {
        return UiNode.card().key("skin").prop("tone", "surface")
                .child(UiNode.text("prefix " + text).key("t"))
                .build();
    }

    private static UiNode content() {
        return UiNode.column().key("content").child(UiNode.text("hello").key("c")).build();
    }

    @Test
    public void appendsContentToContainerRoot() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("message.role", "assistant");
        UiNode out = ThemeSkin.apply(skin("{{message.role}}"), content(), vars);
        assertEquals(UiNode.CARD, out.type());
        assertEquals(2, out.children().size());          // 皮肤标题 + 内容
        assertEquals("prefix assistant", out.children().get(0).props().get("text"));
        assertSame("content", out.children().get(1).key());
    }

    @Test
    public void nonContainerSkinFallsBackToContent() {
        UiNode plain = UiNode.text("only text").build();
        UiNode content = content();
        assertSame(content, ThemeSkin.apply(plain, content, null));
    }

    @Test
    public void replaceLeavesUnknownPlaceholdersAlone() {
        assertEquals("a X {{unknown}}", ThemeSkin.replace("a {{k}} {{unknown}}",
                java.util.Collections.singletonMap("k", "X")));
    }

    @Test
    public void namedAssetSrcResolvesToThemeDirectory() {
        Map<String, String> assets = java.util.Collections.singletonMap(
                "logo", "assets/logo.webp");
        assertEquals("asset://midnight/assets/logo.webp",
                ThemeSkin.resolveAsset("asset://logo", assets, "midnight"));
        // 已是完整相对路径 / 未知键原样保留
        assertEquals("asset://custom/x.webp",
                ThemeSkin.resolveAsset("asset://custom/x.webp", assets, "midnight"));
        assertEquals("asset://logo",
                ThemeSkin.resolveAsset("asset://logo", null, "midnight"));
    }

    @Test
    public void applyRewritesImageSrcInSkin() {
        UiNode skin = UiNode.card()
                .child(UiNode.of(UiNode.IMAGE).key("logo").prop("src", "asset://logo"))
                .build();
        UiNode out = ThemeSkin.apply(skin, content(),
                java.util.Collections.emptyMap(),
                java.util.Collections.singletonMap("logo", "assets/logo.webp"), "midnight");
        UiNode img = out.children().get(0);
        assertEquals("asset://midnight/assets/logo.webp", img.props().get("src"));
    }
}
