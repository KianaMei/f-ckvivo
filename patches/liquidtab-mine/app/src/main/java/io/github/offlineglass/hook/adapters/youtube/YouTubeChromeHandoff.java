package io.github.offlineglass.hook.adapters.youtube;

/** One-use ownership handoff only for a verified configuration recreation. */
final class YouTubeChromeHandoff {
    private int task = -1;
    private long until;
    void save(int taskId, long now, boolean changingConfiguration, boolean ownsHome) {
        task = changingConfiguration && ownsHome ? taskId : -1;
        until = now + 3000;
    }
    boolean consume(int taskId, long now) {
        boolean owns = task >= 0 && task == taskId && now <= until;
        task = -1;
        return owns;
    }
}
