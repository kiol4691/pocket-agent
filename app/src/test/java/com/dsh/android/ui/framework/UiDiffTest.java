package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class UiDiffTest {

    private static UiDiff.Item item(String key) {
        return new UiDiff.Item("text", key);
    }

    private static List<UiDiff.Item> items(String... keys) {
        List<UiDiff.Item> out = new ArrayList<>();
        for (String k : keys) out.add(item(k));
        return out;
    }

    @Test
    public void identicalTree_reusesEverything_noStructuralChange() {
        UiDiff.Plan p = UiDiff.plan(items("a", "b", "c"), items("a", "b", "c"));
        assertFalse(p.structural);
        assertEquals(3, p.steps.size());
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(1, p.steps.get(1).oldIndex);
        assertEquals(2, p.steps.get(2).oldIndex);
    }

    @Test
    public void appendChild_isStructural_butKeepsExistingViews() {
        UiDiff.Plan p = UiDiff.plan(items("a", "b"), items("a", "b", "c"));
        assertTrue(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(1, p.steps.get(1).oldIndex);
        assertEquals(-1, p.steps.get(2).oldIndex);
    }

    @Test
    public void removeChild_isStructural() {
        UiDiff.Plan p = UiDiff.plan(items("a", "b", "c"), items("a", "c"));
        assertTrue(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(2, p.steps.get(1).oldIndex);
    }

    @Test
    public void reorder_isStructural_butReusesViews() {
        UiDiff.Plan p = UiDiff.plan(items("a", "b", "c"), items("c", "a", "b"));
        assertTrue(p.structural);
        assertEquals(2, p.steps.get(0).oldIndex);
        assertEquals(0, p.steps.get(1).oldIndex);
        assertEquals(1, p.steps.get(2).oldIndex);
    }

    @Test
    public void changedKey_createsNewView() {
        UiDiff.Plan p = UiDiff.plan(items("a", "b"), items("a", "x"));
        assertTrue(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(-1, p.steps.get(1).oldIndex);
    }

    @Test
    public void changedTypeWithSameKey_createsNewView() {
        List<UiDiff.Item> old = Arrays.asList(new UiDiff.Item("text", "a"));
        List<UiDiff.Item> want = Arrays.asList(new UiDiff.Item("code", "a"));
        UiDiff.Plan p = UiDiff.plan(old, want);
        assertTrue(p.structural);
        assertEquals(-1, p.steps.get(0).oldIndex);
    }

    @Test
    public void duplicateKeys_neverReuseSameOldTwice() {
        UiDiff.Plan p = UiDiff.plan(items("a", "a"), items("a", "a"));
        assertFalse(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(1, p.steps.get(1).oldIndex);
    }

    @Test
    public void duplicateDesiredKeys_secondOneIsNew() {
        UiDiff.Plan p = UiDiff.plan(items("a"), items("a", "a"));
        assertTrue(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(-1, p.steps.get(1).oldIndex);
    }

    @Test
    public void emptyOld_allNew() {
        UiDiff.Plan p = UiDiff.plan(new ArrayList<UiDiff.Item>(), items("a", "b"));
        assertTrue(p.structural);
        assertEquals(-1, p.steps.get(0).oldIndex);
        assertEquals(-1, p.steps.get(1).oldIndex);
    }

    @Test
    public void emptyDesired_dropsEverything() {
        UiDiff.Plan p = UiDiff.plan(items("a"), new ArrayList<UiDiff.Item>());
        assertTrue(p.structural);
        assertTrue(p.steps.isEmpty());
    }

    /** 没有显式 key 时按 "#位置:type" 匹配，插入会让后面的位置全部错开。 */
    @Test
    public void positionalFallback_insertShiftsFollowingKeys() {
        UiDiff.Plan p = UiDiff.plan(items("#0:text", "#1:text"),
                items("#0:text", "#1:text", "#2:text"));
        assertTrue(p.structural);
        assertEquals(0, p.steps.get(0).oldIndex);
        assertEquals(1, p.steps.get(1).oldIndex);
        assertEquals(-1, p.steps.get(2).oldIndex);
    }
}
