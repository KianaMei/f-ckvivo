package dev.ganvivo.dmsilencer;

import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯内存环形日志 + logcat 输出（严禁在目标进程落盘任何文件）。
 * 仅在用户打开 DmSilencer App 主动查询时通过有序广播返回内存快照。
 */
final class Logx {

    static final String TAG = "DmSilencer";
    private static final int MAX_EVENTS = 160;

    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS =
            new ConcurrentHashMap<String, AtomicInteger>();
    private static final AtomicInteger TOTAL_BLOCKS = new AtomicInteger(0);
    private static final ArrayDeque<String> RING = new ArrayDeque<String>(MAX_EVENTS + 4);

    private static volatile String sPkg = "";
    private static volatile String sProcess = "";
    private static volatile long sLoadedAtMs = 0L;
    private static volatile int sBlockerHooks = 0;
    private static volatile int sFenceHooks = 0;

    private Logx() {
    }

    static void recordLoaded(String pkg, String processName, int blockerHooks, int fenceHooks) {
        sPkg = pkg == null ? "" : pkg;
        sProcess = processName == null ? "" : processName;
        if (sLoadedAtMs == 0L) {
            sLoadedAtMs = System.currentTimeMillis();
        }
        sBlockerHooks = blockerHooks;
        sFenceHooks = fenceHooks;
    }

    static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    static void w(String msg, Throwable t) {
        try {
            Log.w(TAG, msg, t);
        } catch (Throwable ignored) {
        }
    }

    static void block(String key, String detail) {
        AtomicInteger c = COUNTERS.get(key);
        if (c == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = COUNTERS.putIfAbsent(key, created);
            c = existing != null ? existing : created;
        }
        int n = c.incrementAndGet();
        int seq = TOTAL_BLOCKS.incrementAndGet();

        String safeDetail = sanitize(detail);
        String line = System.currentTimeMillis() + "|" + seq + "|" + n + "|" + key + "|" + safeDetail;
        synchronized (RING) {
            if (RING.size() >= MAX_EVENTS) {
                RING.removeFirst();
            }
            RING.addLast(line);
        }

        if (n <= 5 || (n % 25 == 0)) {
            i("BLOCK[" + key + " #" + n + "] " + safeDetail);
        }
    }

    static void clearRing() {
        synchronized (RING) {
            RING.clear();
        }
    }

    static Bundle buildSnapshotBundle() {
        Bundle b = new Bundle();
        b.putString("pkg", sPkg);
        b.putString("process", sProcess);
        b.putInt("pid", Process.myPid());
        b.putInt("uid", Process.myUid());
        b.putString("version", LibXposedEntry.VERSION);
        b.putLong("loaded_at", sLoadedAtMs);
        b.putInt("blocker_hooks", sBlockerHooks);
        b.putInt("fence_hooks", sFenceHooks);
        b.putInt("total_blocks", TOTAL_BLOCKS.get());

        ArrayList<String> keys = new ArrayList<String>();
        ArrayList<Integer> vals = new ArrayList<Integer>();
        for (Map.Entry<String, AtomicInteger> e : COUNTERS.entrySet()) {
            keys.add(e.getKey());
            vals.add(Integer.valueOf(e.getValue().get()));
        }
        int[] valArr = new int[vals.size()];
        for (int i = 0; i < vals.size(); i++) {
            valArr[i] = vals.get(i).intValue();
        }
        b.putStringArrayList("counter_keys", keys);
        b.putIntArray("counter_vals", valArr);

        ArrayList<String> events;
        synchronized (RING) {
            events = new ArrayList<String>(RING);
        }
        b.putStringArrayList("events", events);
        return b;
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String clean = s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        return clean.length() > 180 ? clean.substring(0, 180) + "..." : clean;
    }
}
