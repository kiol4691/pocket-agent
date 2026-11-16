package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 跨控件选择的偏移映射与复制文本：分段拼接、换行规则、越界夹取。 */
public class SelectionModelTest {

    private static SelectionModel.Part part(String text, Object owner) {
        return new SelectionModel.Part(text, owner);
    }

    private static SelectionModel model(SelectionModel.Part... parts) {
        return new SelectionModel(Arrays.asList(parts));
    }

    @Test
    public void partialSelection_insideOnePart() {
        SelectionModel m = model(part("hello world", "a"));
        assertEquals("hello", m.copy(0, 5));
        assertEquals("world", m.copy(6, 11));
    }

    @Test
    public void sameOwnerChunks_joinWithoutNewline() {
        Object owner = new Object();
        SelectionModel m = model(part("段落一\n\n", owner), part("段落二", owner));
        assertEquals(8, m.total());
        assertEquals("段落一\n\n段落二", m.copy(0, m.total()));
        assertEquals("一\n\n", m.copy(2, 5));
        assertEquals("一\n\n段", m.copy(2, 6));
    }

    @Test
    public void differentOwners_insertNewlineOnlyWhenSelected() {
        SelectionModel m = model(part("aaa", "a"), part("bbb", "b"));
        assertEquals(7, m.total());               // 3 + 换行 + 3
        assertEquals("aaabbb", m.copy(0, 3) + m.copy(4, 7));
        assertEquals("aaa\nbbb", m.copy(0, m.total()));
        assertEquals("aaa", m.copy(0, 3));        // 不含分隔符
        assertEquals("\nbbb", m.copy(3, m.total()));
    }

    @Test
    public void threeParts_mixedOwners() {
        Object shared = new Object();
        SelectionModel m = model(part("A", shared), part("B", shared), part("C", "other"));
        assertEquals(4, m.total());
        assertEquals("AB\nC", m.copy(0, m.total()));
    }

    @Test
    public void outOfRange_isClamped() {
        SelectionModel m = model(part("abc", "a"));
        assertEquals("abc", m.copy(-10, 99));
        assertEquals("", m.copy(3, 3));
        assertEquals("", m.copy(2, 1));
    }

    @Test
    public void emptyModel_copiesNothing() {
        SelectionModel m = model();
        assertEquals(0, m.total());
        assertEquals("", m.copy(0, 10));
    }

    @Test
    public void offsets_includeSeparators() {
        SelectionModel m = model(part("aa", "a"), part("bb", "b"), part("cc", "c"));
        assertEquals(0, m.startOf(0));
        assertEquals(2, m.endOf(0));
        assertEquals(3, m.startOf(1));
        assertEquals(5, m.endOf(1));
        assertEquals(6, m.startOf(2));
        assertEquals(8, m.endOf(2));
        assertEquals(8, m.total());
    }

    @Test
    public void nullParts_areTolerated() {
        List<SelectionModel.Part> parts = new ArrayList<>();
        parts.add(part(null, "a"));
        parts.add(part("x", "a"));
        SelectionModel m = new SelectionModel(parts);
        assertEquals("x", m.copy(0, m.total()));
    }
}
