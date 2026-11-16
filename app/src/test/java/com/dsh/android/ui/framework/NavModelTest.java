package com.dsh.android.ui.framework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 导航模型：排序、分组、覆盖、hidden 与未知页面。 */
public class NavModelTest {

    private static NavModel.Source src(String page, String label, String group, int order,
                                       boolean builtin) {
        return new NavModel.Source(page, label, group, order, false, builtin);
    }

    private static NavModel.Override ov(String page, String label, String group,
                                        Integer order, Boolean hidden) {
        return new NavModel.Override(page, label, group, order, hidden);
    }

    private static List<NavModel.Source> baseSources() {
        return Arrays.asList(
                src(NavModel.CHAT_PAGE, "聊天", "", 0, true),
                src("p.status", "状态面板", "", 1000, false),
                src("p.nav", "导航", "", 1000, false));
    }

    @Test
    public void ordersByOrderThenPage() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("p.status", null, "工具", 10, null),
                ov("p.nav", null, "工具", 20, null)));
        assertEquals(Arrays.asList("pocket.chat", "p.status", "p.nav"), pages(m));
    }

    @Test
    public void appliesLabelGroupAndOrder() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("p.status", "运行状态", "工具", 5, null)));
        NavModel.Item item = m.find("p.status");
        assertEquals("运行状态", item.label);
        assertEquals("工具", item.group);
        assertEquals(5, item.order);
    }

    @Test
    public void groupsFollowMinOrder() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("p.status", null, "工具", 10, null),
                ov("p.nav", null, "工具", 20, null)));
        List<NavModel.Group> groups = m.groups();
        assertEquals(2, groups.size());
        assertEquals("", groups.get(0).name);
        assertEquals("工具", groups.get(1).name);
        assertEquals(2, groups.get(1).items.size());
    }

    @Test
    public void hiddenRemovesFromMenuButKeepsOtherItems() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("p.nav", null, null, null, true)));
        assertEquals(Arrays.asList("pocket.chat", "p.status"), pages(m));
        assertNull(m.find("p.nav"));
    }

    @Test
    public void firstOverrideWins() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("p.status", "第一个", null, null, null),
                ov("p.status", "第二个", null, null, null)));
        assertEquals("第一个", m.find("p.status").label);
    }

    @Test
    public void unknownPageOverrideIsIgnored() {
        NavModel m = NavModel.build(baseSources(), Arrays.asList(
                ov("other.missing", "X", "Y", 1, null)));
        assertEquals(3, m.items().size());
        assertNull(m.find("other.missing"));
    }

    @Test
    public void chatCanBeRenamedAndHidden() {
        NavModel renamed = NavModel.build(baseSources(), Arrays.asList(
                ov(NavModel.CHAT_PAGE, "对话", null, 1, null)));
        assertEquals("对话", renamed.find(NavModel.CHAT_PAGE).label);

        NavModel hidden = NavModel.build(baseSources(), Arrays.asList(
                ov(NavModel.CHAT_PAGE, null, null, null, true)));
        assertNull(hidden.find(NavModel.CHAT_PAGE));
        assertTrue(hidden.items().size() == 2);
    }

    private static List<String> pages(NavModel m) {
        List<String> out = new java.util.ArrayList<>();
        for (NavModel.Item i : m.items()) out.add(i.page);
        return out;
    }
}
