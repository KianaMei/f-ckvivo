package com.douai.module.config;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostStorageNamespaceTest {
    @Test public void upstreamNamesStayStableWhileAllUnifiedPrivateStoresUseAnotherName() {
        for (String name : new String[]{"douai_message_state", "douai_comment_submissions", "douai_reply_submissions",
                "douai_comment_reply_thread_lookup", "douai-analytics", "douai_message_chat", "douai-voice-source", "douai"}) {
            assertEquals(name, HostStorageNamespace.name(name, false));
            assertNotEquals(name, HostStorageNamespace.name(name, true));
            assertEquals(HostStorageNamespace.name(name, true), HostStorageNamespace.name(HostStorageNamespace.name(name, true), true));
        }
        assertEquals("douai-analytics-adapt412", HostStorageNamespace.name("douai-analytics", true));
        assertThrows(IllegalArgumentException.class, () -> HostStorageNamespace.name("../other", true));
    }
}
