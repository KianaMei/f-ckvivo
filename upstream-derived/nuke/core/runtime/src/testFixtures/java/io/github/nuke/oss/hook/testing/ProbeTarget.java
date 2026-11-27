package io.github.nuke.oss.hook.testing;

// Stands in for a host class in feature lifecycle tests: a hooked runtime rewrites greet and answers describe and
// setEnabled, so the unhooked results below are what a call returns once the runtime is closed or disabled.
public final class ProbeTarget {
    private ProbeTarget() {}

    public static String greet(String name) {
        return "original:" + name;
    }

    public static String describe() {
        return "unhooked";
    }

    public static String setEnabled(boolean enabled) {
        return "unhooked";
    }
}
