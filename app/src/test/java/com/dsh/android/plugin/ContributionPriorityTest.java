package com.dsh.android.plugin;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 各贡献类型的 priority 是独立字段（P2 解耦）：
 * 缺省 0，显式声明时按自己的值解析，互不继承插件级 priority。
 */
public class ContributionPriorityTest {

    @Test
    public void toolPriority() throws Exception {
        JSONObject o = new JSONObject()
                .put("name", "echo")
                .put("handler", "echoTool")
                .put("inputSchema", new JSONObject().put("type", "object"));
        assertEquals(0, ToolDecl.fromJson(o, 5_000).priority);
        assertEquals(7, ToolDecl.fromJson(o.put("priority", 7), 5_000).priority);
    }

    @Test
    public void actionPriority() throws Exception {
        JSONObject o = new JSONObject().put("id", "svc.restart").put("label", "Restart");
        assertEquals(0, ActionDecl.fromJson("p", o).priority);
        assertEquals(3, ActionDecl.fromJson("p", o.put("priority", 3)).priority);
    }

    @Test
    public void statePriority() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", "status").put("label", "Status").put("type", "string");
        assertEquals(0, StateDecl.fromJson("p", o).priority);
        assertEquals(-2, StateDecl.fromJson("p", o.put("priority", -2)).priority);
    }

    @Test
    public void pagePriority() throws Exception {
        JSONObject o = new JSONObject()
                .put("id", "main").put("title", "Main").put("handler", "renderMain");
        assertEquals(0, PageDecl.fromJson("p", o).priority);
        assertEquals(9, PageDecl.fromJson("p", o.put("priority", 9)).priority);
    }

    @Test
    public void slotPriority() throws Exception {
        JSONObject o = new JSONObject()
                .put("slot", "home.hero").put("handler", "renderHero");
        assertEquals(0, UiSlotDecl.fromJson("p", o).priority);
        assertEquals(4, UiSlotDecl.fromJson("p", o.put("priority", 4)).priority);
    }

    @Test
    public void navPriority() throws Exception {
        JSONObject o = new JSONObject().put("page", "p.main");
        assertEquals(0, NavDecl.fromJson("p", o).priority);
        assertEquals(1, NavDecl.fromJson("p", o.put("priority", 1)).priority);
    }
}
