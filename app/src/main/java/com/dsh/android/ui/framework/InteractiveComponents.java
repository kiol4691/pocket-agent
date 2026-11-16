package com.dsh.android.ui.framework;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 依赖事件的 P2 交互控件。事件一律交给 {@link UiEventDispatcher} 分发，组件只负责画和取值，
 * 不直接调业务回调。
 *
 * 口径（PLUGIN_API.md §6.2）：
 *  - input 的 change 只在**提交或失焦**时发，不是每次按键；
 *  - switch/checkbox 点击即发；slider **松手**才发一次；
 *  - apply 里回写值时用 silent 标志抑制监听，避免状态回环。
 */
public final class InteractiveComponents {

    private InteractiveComponents() {
    }

    public static void install(UiRenderer renderer, UiEventDispatcher dispatcher) {
        renderer.register(UiNode.BUTTON, button());
        renderer.register(UiNode.INPUT, input(dispatcher));
        renderer.register(UiNode.SWITCH, toggle(dispatcher, true));
        renderer.register(UiNode.CHECKBOX, toggle(dispatcher, false));
        renderer.register(UiNode.SLIDER, slider(dispatcher));
        renderer.register(UiNode.TASKBAR, taskbar(dispatcher));
    }

    /**
     * taskbar：把窗口列表渲染成一排小按钮。items 是 {@code [{key,label,active}]}，
     * 点击时以 {@code value=key} 发 tap 事件，宿主据此还原/置顶窗口。
     */
    private static UiComponent taskbar(UiEventDispatcher dispatcher) {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setTag("");
                return row;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                LinearLayout row = (LinearLayout) view;
                Object items = node.props().get("items");
                String sig = String.valueOf(items);
                boolean rebuild = !sig.equals(String.valueOf(row.getTag()));
                if (rebuild) {
                    row.setTag(sig);
                    row.removeAllViews();
                    if (items instanceof List) {
                        int i = 0;
                        for (Object o : (List<?>) items) {
                            if (!(o instanceof Map)) continue;
                            Map<?, ?> item = (Map<?, ?>) o;
                            final String key = String.valueOf(item.get("key"));
                            TextView btn = taskButton(ctx,
                                    String.valueOf(item.get("label") == null ? key : item.get("label")),
                                    Boolean.TRUE.equals(item.get("active")), env);
                            btn.setOnClickListener(v ->
                                    dispatcher.fireByView(row, "tap", key));
                            row.addView(btn, chipParams(ctx, i++));
                        }
                    }
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static TextView taskButton(Context ctx, String label, boolean active, UiComponent.Env env) {
        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(env.theme().sizeSp("caption"));
        int ph = env.theme().dp(12), pv = env.theme().dp(6);
        tv.setPadding(ph, pv, ph, pv);
        GradientDrawable g = new GradientDrawable();
        g.setColor(active ? env.theme().brandColor() : 0x22FFFFFF);
        g.setCornerRadius(env.theme().dp(10));
        tv.setBackground(g);
        tv.setTextColor(active ? env.theme().onBrandColor() : env.theme().textColor("primary"));
        tv.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                ctx.getResources().getDisplayMetrics().density));
        return tv;
    }

    private static LinearLayout.LayoutParams chipParams(Context ctx, int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) lp.leftMargin = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        return lp;
    }

    private static UiComponent button() {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                TextView tv = new TextView(ctx);
                tv.setGravity(Gravity.CENTER);
                tv.setTextSize(env.theme().sizeSp("body"));
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                int ph = env.theme().dp(14), pv = env.theme().dp(8);
                tv.setPadding(ph, pv, ph, pv);
                tv.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                        ctx.getResources().getDisplayMetrics().density));
                return tv;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                TextView tv = (TextView) view;
                String label = env.clip(node.str("label", ""));
                if (!label.equals(old.get("label"))) tv.setText(label);
                String variant = node.str("variant", "primary");
                // 主题实例变了（切主题/恢复默认）就重新取一遍 token；旧 props 已被清空，
                // 不能靠 variant 变化来判断。
                TokenResolver theme = env.theme();
                if (Builtins.retheme(view, env) || !variant.equals(old.get("variant"))) {
                    tv.setTextSize(theme.sizeSp("body"));
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    int ph = theme.dp(14), pv = theme.dp(8);
                    tv.setPadding(ph, pv, ph, pv);
                    tv.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                            ctx.getResources().getDisplayMetrics().density));
                    GradientDrawable g = new GradientDrawable();
                    if ("ghost".equals(variant)) {
                        g.setColor(0x00000000);
                        g.setStroke(theme.dp(1), theme.borderColor());
                        tv.setTextColor(theme.brandColor());
                    } else {
                        g.setColor(theme.brandColor());
                        tv.setTextColor(theme.onBrandColor());
                    }
                    g.setCornerRadius(theme.dp(10));
                    view.setBackground(g);
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent input(UiEventDispatcher dispatcher) {
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                EditText et = new EditText(ctx);
                et.setTextSize(env.theme().sizeSp("body"));
                et.setTextColor(env.theme().textColor("primary"));
                et.setHintTextColor(env.theme().textColor("caption"));
                et.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                        ctx.getResources().getDisplayMetrics().density));
                et.setInputType(node.bool("multiline", false)
                        ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                        : InputType.TYPE_CLASS_TEXT);
                et.setOnEditorActionListener((v, actionId, ev) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                        dispatcher.fireByView(v, "submit", v.getText().toString());
                        dispatcher.fireByView(v, "change", v.getText().toString());
                        return true;
                    }
                    return false;
                });
                et.setOnFocusChangeListener((v, hasFocus) -> {
                    String value = ((EditText) v).getText().toString();
                    if (hasFocus) dispatcher.fireByView(v, "focus", value);
                    else {
                        dispatcher.fireByView(v, "blur", value);
                        dispatcher.fireByView(v, "change", value);
                    }
                });
                return et;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                EditText et = (EditText) view;
                TokenResolver theme = env.theme();
                if (Builtins.retheme(view, env)) {
                    et.setTextSize(theme.sizeSp("body"));
                    et.setTextColor(theme.textColor("primary"));
                    et.setHintTextColor(theme.textColor("caption"));
                    et.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                            ctx.getResources().getDisplayMetrics().density));
                }
                String value = env.clip(node.str("value", ""));
                // 只在节点值和当前内容不同时回写，天然避免监听回环
                if (!value.equals(old.get("value")) && !value.contentEquals(et.getText())) {
                    et.setText(value);
                }
                String hint = env.clip(node.str("placeholder", ""));
                if (!hint.equals(old.get("placeholder"))) et.setHint(hint);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent toggle(UiEventDispatcher dispatcher, boolean isSwitch) {
        final WeakHashMap<View, Boolean> silent = new WeakHashMap<>();
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                CompoundButton cb = isSwitch ? new Switch(ctx) : new CheckBox(ctx);
                cb.setTextSize(env.theme().sizeSp("body"));
                cb.setTextColor(env.theme().textColor("primary"));
                cb.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                        ctx.getResources().getDisplayMetrics().density));
                cb.setOnCheckedChangeListener((v, checked) -> {
                    if (Boolean.TRUE.equals(silent.get(v))) return;
                    dispatcher.fireByView(v, "change", checked);
                });
                return cb;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                CompoundButton cb = (CompoundButton) view;
                TokenResolver theme = env.theme();
                if (Builtins.retheme(view, env)) {
                    cb.setTextSize(theme.sizeSp("body"));
                    cb.setTextColor(theme.textColor("primary"));
                    cb.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                            ctx.getResources().getDisplayMetrics().density));
                    int brand = theme.brandColor();
                    if (cb instanceof Switch) {
                        ((Switch) cb).setThumbTintList(ColorStateList.valueOf(brand));
                        ((Switch) cb).setTrackTintList(ColorStateList.valueOf(
                                (brand & 0x00FFFFFF) | 0x66000000));
                    } else {
                        cb.setButtonTintList(ColorStateList.valueOf(brand));
                    }
                }
                boolean checked = node.bool("checked", false);
                if (checked != Boolean.TRUE.equals(old.get("checked")) || cb.isChecked() != checked) {
                    silent.put(view, Boolean.TRUE);
                    cb.setChecked(checked);
                    silent.remove(view);
                }
                String label = env.clip(node.str("label", ""));
                if (!label.contentEquals(cb.getText())) cb.setText(label);
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }

    private static UiComponent slider(UiEventDispatcher dispatcher) {
        final WeakHashMap<View, String> sigs = new WeakHashMap<>();
        // 每个 slider 的声明区间：松手时要把 0..100 的 progress 反算回 min..max
        final WeakHashMap<View, int[]> ranges = new WeakHashMap<>();
        return new UiComponent() {
            @Override
            public View create(Context ctx, UiNode node, Env env) {
                SeekBar sb = new SeekBar(ctx);
                sb.setMinimumHeight(ThemeValidator.minTouchTargetPx(
                        ctx.getResources().getDisplayMetrics().density));
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        int[] range = ranges.get(seekBar);
                        if (range == null) {
                            dispatcher.fireByView(seekBar, "change", seekBar.getProgress());
                            return;
                        }
                        int min = range[0];
                        int max = range[1];
                        int value = min
                                + (int) Math.round(seekBar.getProgress() * (max - min) / 100.0);
                        dispatcher.fireByView(seekBar, "change",
                                Math.max(min, Math.min(max, value)));
                    }
                });
                return sb;
            }

            @Override
            public void apply(Context ctx, View view, UiNode node,
                              Map<String, Object> old, Env env) {
                SeekBar sb = (SeekBar) view;
                int min = node.integer("min", 0);
                int max = Math.max(min + 1, node.integer("max", 100));
                int value = Math.max(min, Math.min(max, node.integer("value", min)));
                // SeekBar 只支持 0..max，把声明区间线性映射过去
                int progress = (int) Math.round((value - min) * 100.0 / (max - min));
                String sig = min + "/" + max + "/" + progress;
                if (!sig.equals(sigs.get(view))) {
                    sigs.put(view, sig);
                    ranges.put(view, new int[]{min, max});
                    if (sb.getMax() != 100) sb.setMax(100);
                    if (sb.getProgress() != progress) sb.setProgress(progress);
                }
            }

            @Override
            public boolean isContainer() {
                return false;
            }
        };
    }
}
