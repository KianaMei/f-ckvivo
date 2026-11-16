package com.douai.module.ui;

import android.os.Bundle;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Bounded startup evidence that remains available before host identity or DEX scanning succeeds. */
public final class HostStartupStatus {
    private static final int MAX_EVENTS = 20;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private String stage = "等待宿主识别";
    private String detail = "模块宿主入口已到达，尚未确认宿主身份。";
    private boolean authorized;
    private long changedAt = SystemClock.elapsedRealtime();

    public HostStartupStatus(boolean authorized) {
        this.authorized = authorized;
        record(stage, detail);
    }

    public synchronized void authorization(boolean allowed, String stage, String detail) {
        authorized = allowed;
        record(stage, detail);
    }

    public synchronized void record(String stage, String detail) {
        this.stage = bounded(stage, 120, "启动状态");
        this.detail = bounded(detail, 2048, "没有更多详细信息");
        changedAt = SystemClock.elapsedRealtime();
        String event = changedAt + " ms · " + this.stage + "\n" + this.detail;
        if (!events.isEmpty() && events.getLast().equals(event)) return;
        events.addLast(event);
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    public synchronized String stage() { return stage; }
    public synchronized String detail() { return detail; }
    public synchronized boolean authorized() { return authorized; }

    public synchronized String describe() {
        return "当前阶段：" + stage + "\n宿主业务授权：" + (authorized ? "已通过身份检查" : "未通过或尚未完成")
                + "\n" + detail + "\n\n启动记录：\n" + String.join("\n\n", events);
    }

    /** Every field has a startup_ prefix so the owner can merge it into its diagnostic snapshot. */
    public synchronized Bundle snapshot() {
        Bundle result = new Bundle();
        result.putBoolean("startup_host_callback_reached", true);
        result.putBoolean("startup_host_authorized", authorized);
        result.putString("startup_stage", stage);
        result.putString("startup_detail", detail);
        result.putLong("startup_changed_at_elapsed", changedAt);
        result.putStringArrayList("startup_events", new ArrayList<>(events));
        return result;
    }

    private static String bounded(String value, int limit, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
