package com.dsh.android.ui.framework;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class UiStateFormatTest {

    @Test
    public void percentFromRatio() {
        assertEquals("62%", UiStateFormat.percent(0.62));
        assertEquals("0%", UiStateFormat.percent(0));
        assertEquals("100%", UiStateFormat.percent(1));
        assertEquals("62%", UiStateFormat.percent(62));   // 已经像百分数的原样用
    }

    @Test
    public void numberWithDecimalsAndSuffix() {
        assertEquals("62", UiStateFormat.number(62.4));
        assertEquals("62.0k", UiStateFormat.number(62.0, 1, "k"));
        assertEquals("1.23 MB", UiStateFormat.number(1.234, 2, " MB"));
    }

    @Test
    public void durationAndBytes() {
        assertEquals("500ms", UiStateFormat.duration(500));
        assertEquals("1m 20s", UiStateFormat.duration(80000));
        assertEquals("1h 5m", UiStateFormat.duration(3900000));
        assertEquals("512 B", UiStateFormat.bytes(512));
        assertEquals("1.2 MB", UiStateFormat.bytes(1.2 * 1024 * 1024));
    }

    @Test
    public void stateNameFromBothForms() {
        assertEquals("pocket.context.ratio", UiStateFormat.stateName("pocket.context.ratio"));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("state", "deploy.progress");
        spec.put("format", "percent");
        assertEquals("deploy.progress", UiStateFormat.stateName(spec));
    }

    @Test
    public void applyWithObjectSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("state", "x");
        spec.put("format", "percent");
        assertEquals("43%", UiStateFormat.apply(0.43, spec));
        assertEquals("7", UiStateFormat.apply(7, "plain.state"));
        assertEquals("", UiStateFormat.apply(null, spec));
    }
}
