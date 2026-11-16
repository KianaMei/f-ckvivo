package com.douai.module.feature;

import com.douai.module.scan.HostAdaptation;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class DirectMessageSpeech412Test {
    @Test public void nativeAudioCallbackUsesTheAudioLogicBranchInEachHost() {
        assertEquals(1, DirectMessageSpeechHook.recordCallbackBranch(HostAdaptation.PROFILE_406));
        assertEquals(1, DirectMessageSpeechHook.recordCallbackBranch(HostAdaptation.PROFILE_408));
        assertEquals(1, DirectMessageSpeechHook.recordCallbackBranch(HostAdaptation.PROFILE_410));
        assertEquals(4, DirectMessageSpeechHook.recordCallbackBranch(HostAdaptation.PROFILE_412));
    }
}
