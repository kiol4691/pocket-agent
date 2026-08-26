package com.dsh.android.plugin.styles;

import android.graphics.Color;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Whitelisted style overrides contributed by hook plugins via onStyle().
 *
 * JS side: onStyle() returns { slot: { prop: value, ... }, ... } where
 *   slot ∈ bubbleUser | bubbleAssistant | toolCard | codeBlock | composer | topbar | sidebar
 *   prop ∈ bg (#RRGGBB[AA]) | textColor (#RRGGBB[AA]) | radius (dp) | textSize (sp) | padding (dp)
 * Anything outside the whitelist is dropped. Applies to views created afterwards;
 * topbar/composer/sidebar (singletons) are re-styled immediately by MainActivity. */
public final class PluginStyles {

    public static final Set<String> SLOTS = new HashSet<>(java.util.Arrays.asList(
            "bubbleUser", "bubbleAssistant", "toolCard", "codeBlock", "composer", "topbar", "sidebar"));
    public static final Set<String> PROPS = new HashSet<>(java.util.Arrays.asList(
            "bg", "textColor", "radius", "textSize", "padding"));

    private static final Map<String, Map<String, String>> SLOTS_MAP = new ConcurrentHashMap<>();

    private PluginStyles() { }

    /** Replace all overrides with a validated copy of the given slots. */
    public static void replaceAll(Map<String, Map<String, String>> slots) {
        SLOTS_MAP.clear();
        if (slots == null) return;
        for (Map.Entry<String, Map<String, String>> e : slots.entrySet()) {
            String slot = e.getKey();
            if (!SLOTS.contains(slot) || e.getValue() == null) continue;
            Map<String, String> props = new ConcurrentHashMap<>();
            for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                if (p.getValue() == null) continue;
                if (!PROPS.contains(p.getKey())) continue;
                if (p.getKey().equals("bg") || p.getKey().equals("textColor")) {
                    try { Color.parseColor(p.getValue()); } catch (Exception ex) { continue; }
                } else {
                    try { Float.parseFloat(p.getValue()); } catch (Exception ex) { continue; }
                }
                props.put(p.getKey(), p.getValue());
            }
            if (!props.isEmpty()) SLOTS_MAP.put(slot, props);
        }
    }

    public static boolean has(String slot) {
        return SLOTS_MAP.containsKey(slot);
    }

    /** Parsed bg color, or null. */
    public static Integer bgColor(String slot) {
        return colorProp(slot, "bg");
    }

    /** Parsed text color, or null. */
    public static Integer textColor(String slot) {
        return colorProp(slot, "textColor");
    }

    /** Numeric prop (radius/textSize/padding) in dp/sp units, or null. */
    public static Float num(String slot, String prop) {
        Map<String, String> props = SLOTS_MAP.get(slot);
        if (props == null) return null;
        String v = props.get(prop);
        if (v == null) return null;
        try {
            float f = Float.parseFloat(v);
            return (f < 0 || f > 200) ? null : f;
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer colorProp(String slot, String prop) {
        Map<String, String> props = SLOTS_MAP.get(slot);
        if (props == null) return null;
        String v = props.get(prop);
        if (v == null) return null;
        try {
            int c = Color.parseColor(v);
            // force a sane alpha floor: fully transparent backgrounds would look like a bug
            if ((prop.equals("bg")) && (c >>> 24) < 0x10) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}
