package com.douai.module.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** The 41.2 profile must not reuse unverified numeric IDs or native UI reflection aliases. */
public final class HostResourceProfile412Test {
    @Test public void onlyExplicitVerifiedSemanticNamesResolveInBothDirections() {
        int[][] verified = {
                {0x7f0a2515, 0x7f0a242c},
                {0x7f0a2596, 0x7f0a24b8},
                {0x7f0a8f46, 0x7f0a8f46},
                {0x7f0a9cc7, 0x7f0a9cc7},
                {0x7f0ad2af, 0x7f0ad2af}
        };
        for (int[] entry : verified) {
            assertEquals(entry[1], HostResourceProfile.id(HostAdaptation.PROFILE_412, entry[0]));
            assertEquals(entry[0], HostResourceProfile.canonicalId(HostAdaptation.PROFILE_412, entry[1]));
        }
        // Old host IDs and same-value unknown IDs must not silently select a different 41.2 widget.
        assertEquals(-1, HostResourceProfile.id(HostAdaptation.PROFILE_412, 0x7f0a463c));
        assertEquals(-1, HostResourceProfile.id(HostAdaptation.PROFILE_412, 0x7f0a6444));
        assertEquals(-1, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_412, 0x7f0a4629));
        assertEquals(-1, HostResourceProfile.canonicalId(HostAdaptation.PROFILE_412, 0x7f0a2515));
    }

    @Test public void unverifiedReflectionAliasesRejectTheNewProfile() {
        HostAdaptation.Profile profile = HostAdaptation.PROFILE_412;
        assertThrows(IllegalStateException.class, () -> HostResourceProfile.className(profile, "X.0YVw"));
        assertThrows(IllegalStateException.class, () -> HostResourceProfile.viewFactory(profile));
        assertThrows(IllegalStateException.class, () -> HostResourceProfile.avatarLoader(profile));
        assertThrows(IllegalStateException.class, () -> HostResourceProfile.mediaSourceGetter(profile));
        assertEquals("LJJLIIIJLJLI", HostResourceProfile.ripsLogicProvider(profile));
        assertEquals("LLLILZJ", HostResourceProfile.diggViewModelGetter(profile));
        assertEquals("A2", HostResourceProfile.feedItemParamsField(profile));
        assertEquals("LJJIIZI", HostResourceProfile.textBindMethod(profile));
        assertEquals("LJJI", HostResourceProfile.commentSaveClick(profile));
        assertThrows(IllegalStateException.class, () -> HostResourceProfile.chatMethod(profile, "LJLJJLL"));
        assertEquals("LJIIJ", HostResourceProfile.downloadAwemeGetter(profile));
    }
}
