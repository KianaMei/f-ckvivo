package io.github.offlineglass.hook;

/**
 * Shared exported settings, not an app-specific opt-in or an empty defaults map.
 *
 * Fork note (2026-11-17): the explicit global glass-decision key is no longer
 * required. That key is only persisted when the user toggles the master
 * switch, so a fresh install never wrote it and the framework mirror was
 * rejected as "incomplete" whenever the provider process was dead (vivo
 * freezes the swiped-away module app within seconds). A present, non-negative
 * revision already proves the snapshot was exported by the module; the reader
 * defaults the missing toggle to true.
 */
final class StartupPreferencesPolicy {
    static boolean usable(boolean hasRevision, long revision, boolean hasGlobalGlassDecision) {
        return hasRevision && revision >= 0L;
    }
}
