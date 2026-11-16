package com.dsh.android.ui.framework;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 动效 spec（PLUGIN_API.md §12.1）：名字必须来自宿主白名单，时长上限 400ms，
 * 系统「移除动画」时统一降级为不播。纯 Java，方便 JVM 单测。
 */
public final class UiMotion {

    public static final long MAX_MS = 400;
    public static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "none", "fade", "fade-rise", "slide-up", "scale-in", "crossfade", "spring"));

    private final String enter;
    private final String exit;
    private final long durationMs;
    private final boolean reduceMotion;

    private UiMotion(String enter, String exit, long durationMs, boolean reduceMotion) {
        this.enter = enter;
        this.exit = exit;
        this.durationMs = durationMs;
        this.reduceMotion = reduceMotion;
    }

    public static UiMotion defaults(boolean reduceMotion) {
        return new UiMotion("none", "none", 0, reduceMotion);
    }

    public static UiMotion from(ThemePack pack, boolean reduceMotion) {
        String enter = normalize(pack == null ? null : pack.string("motion.enter"));
        String exit = normalize(pack == null ? null : pack.string("motion.exit"));
        Integer d = pack == null ? null : pack.number("motion.duration");
        long ms = d == null ? 180 : Math.max(0, Math.min(MAX_MS, d));
        return new UiMotion(enter, exit, ms, reduceMotion);
    }

    public static boolean isAllowed(String name) {
        return ALLOWED.contains(name);
    }

    public static String normalize(String name) {
        return isAllowed(name) ? name : "none";
    }

    public String enter() {
        return reduceMotion ? "none" : enter;
    }

    public String exit() {
        return reduceMotion ? "none" : exit;
    }

    public long durationMs() {
        return reduceMotion ? 0 : Math.min(MAX_MS, durationMs);
    }

    public boolean enabled() {
        return !reduceMotion && durationMs() > 0 && !"none".equals(enter());
    }
}
