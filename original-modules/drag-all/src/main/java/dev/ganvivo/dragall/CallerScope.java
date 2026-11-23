package dev.ganvivo.dragall;

final class CallerScope {
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();

    int enter() {
        Integer current = depth.get();
        int previous = current == null ? 0 : current;
        depth.set(previous + 1);
        return previous;
    }

    void exit(int previous) {
        if (previous == 0) depth.remove();
        else depth.set(previous);
    }

    boolean active() {
        Integer current = depth.get();
        return current != null && current > 0;
    }
}
