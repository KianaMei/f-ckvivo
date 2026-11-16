package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Injects one entry into the uniquely identified host settings scroll content. */
final class HostSettingsEntry {
    private static final String TAG = "DouAi.HostSettingsEntry";

    private HostSettingsEntry() { }

    static View attach(Activity activity, Class<?> scrollClass, Runnable openSettings) {
        View content = activity.findViewById(android.R.id.content);
        List<ViewGroup> scrolls = new ArrayList<>();
        int[] budget = {2048};
        findScrolls(content, scrollClass, scrolls, budget, 0);
        if (budget[0] <= 0) { DouAiLog.event("settings_entry", "target_lookup", "tree_limit", "fallback_available=true"); return null; }
        if (scrolls.size() != 1) { DouAiLog.event("settings_entry", "target_lookup", "ambiguous_or_missing", "candidate_count=" + scrolls.size()); return null; }
        ViewGroup scroll = scrolls.get(0);
        if (scroll.getChildCount() != 1) return null;
        LinearLayout container = verticalContent(scroll.getChildAt(0));
        if (container == null) return null;
        for (int index = 0; index < container.getChildCount(); index++) {
            View existing = container.getChildAt(index);
            if (TAG.equals(existing.getTag())) return existing;
        }

        TextView entry = new TextView(activity);
        entry.setTag(TAG);
        entry.setText("融合设置");
        entry.setContentDescription("抖音融合设置，DouAi 模块设置");
        entry.setTextSize(16);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        entry.setMinHeight(dp(activity, 54));
        entry.setCompoundDrawablesRelativeWithIntrinsicBounds(android.R.drawable.ic_menu_preferences,
                0, 0, 0);
        entry.setCompoundDrawablePadding(dp(activity, 12));
        entry.setOnClickListener(ignored -> {
            DouAiLog.Operation trace = DouAiLog.begin("settings_entry", "entry_click");
            try { openSettings.run(); trace.finish(DouAiLog.Outcome.UNCONFIRMED, "open_requested", "settings_render_result=unobserved"); }
            catch (RuntimeException failure) { trace.error("open_settings", failure); trace.finish(DouAiLog.Outcome.FAILED, "open_failed", ""); throw failure; }
        });
        entry.setFocusable(true);
        container.addView(entry, Math.min(1, container.getChildCount()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        DouAiLog.event("settings_entry", "view_attached", "parent_readback", "parent_matches=" + (entry.getParent() == container));
        return entry;
    }

    private static void findScrolls(View view, Class<?> scrollClass, List<ViewGroup> matches, int[] budget, int depth) {
        if (view == null || scrollClass == null) return;
        if (depth > 64 || budget[0]-- <= 0) { budget[0] = 0; return; }
        if (scrollClass.isInstance(view) && view instanceof ViewGroup) {
            matches.add((ViewGroup) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                findScrolls(group.getChildAt(index), scrollClass, matches, budget, depth + 1);
            }
        }
    }

    /** Single-child neutral wrappers are safe; ambiguous branches and adapter-backed lists are not. */
    private static LinearLayout verticalContent(View view) {
        for (int depth = 0; view != null && depth < 12; depth++) {
            if (view instanceof LinearLayout) {
                LinearLayout layout = (LinearLayout) view;
                return layout.getOrientation() == LinearLayout.VERTICAL ? layout : null;
            }
            if (!(view instanceof ViewGroup) || adapterBacked(view)) return null;
            ViewGroup wrapper = (ViewGroup) view;
            if (wrapper.getChildCount() != 1) return null;
            view = wrapper.getChildAt(0);
        }
        return null;
    }

    private static boolean adapterBacked(View view) {
        if (view instanceof android.widget.AdapterView) return true;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())) return true;
        }
        return false;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
