package com.douai.module.feature;

/** Small event policy shared by the 41.2 adapter and its device-independent tests. */
final class UnifiedPlayback412Policy {
    private String aid;
    private String advancedAid;
    private int direction = 1;

    void selected(String next) {
        if (next == null || next.isEmpty()) return;
        if (!next.equals(aid)) advancedAid = null;
        aid = next;
    }

    void direction(int next) { direction = next < 0 ? -1 : 1; }
    String aid() { return aid; }

    int completion(String completedAid, boolean enabled, boolean foreground, boolean touching,
                   boolean comments, int position, int count) {
        if (!enabled || !foreground || touching || comments || aid == null
                || !aid.equals(completedAid) || aid.equals(advancedAid)
                || position < 0 || count <= 0) return -1;
        int target = position + direction;
        if (target < 0 || target >= count) return -1;
        advancedAid = aid;
        return target;
    }

    void clear() { aid = null; advancedAid = null; direction = 1; }
}
