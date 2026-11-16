package com.dsh.android.ui.framework;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 的"子树对账"：算出这一帧该复用哪些旧节点、哪些要新建、要不要重排。
 *
 * 故意不碰 Android View —— 这是框架里最容易写错、也最该在 JVM 单测里跑的一块，
 * 抽出来之后 {@link UiRenderer} 只负责按这份计划建/取 View。
 *
 * 匹配规则：同父下按 {@code (type, key)} 找旧节点；key 为 null 时调用方已经
 * 补成 {@code "#位置:type"}，自然退化成按位置复用。一个旧节点最多被复用一次。
 */
public final class UiDiff {

    private UiDiff() {
    }

    public static final class Item {
        public final String type;
        public final String key;

        public Item(String type, String key) {
            this.type = type;
            this.key = key;
        }
    }

    public static final class Step {
        public final int desiredIndex;
        /** 旧列表里的下标；-1 表示要新建。 */
        public final int oldIndex;

        Step(int desiredIndex, int oldIndex) {
            this.desiredIndex = desiredIndex;
            this.oldIndex = oldIndex;
        }
    }

    public static final class Plan {
        public final List<Step> steps;
        /** 顺序/数量/key 变了，需要重新 addView（但 View 实例仍然复用）。 */
        public final boolean structural;

        Plan(List<Step> steps, boolean structural) {
            this.steps = steps;
            this.structural = structural;
        }
    }

    public static Plan plan(List<Item> old, List<Item> desired) {
        List<Step> steps = new ArrayList<>(desired.size());
        boolean[] used = new boolean[old.size()];
        boolean structural = old.size() != desired.size();

        for (int i = 0; i < desired.size(); i++) {
            Item want = desired.get(i);
            int found = -1;
            for (int j = 0; j < old.size(); j++) {
                if (used[j]) continue;
                Item have = old.get(j);
                if (have.key.equals(want.key) && have.type.equals(want.type)) {
                    found = j;
                    break;
                }
            }
            if (found >= 0) used[found] = true;
            else structural = true;                 // 有新建，父容器要重新挂
            if (found != i) structural = true;      // 顺序变了
            steps.add(new Step(i, found));
        }
        return new Plan(steps, structural);
    }
}
