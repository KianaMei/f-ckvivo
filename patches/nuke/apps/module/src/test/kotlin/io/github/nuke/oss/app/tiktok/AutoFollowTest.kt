package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.AutoFollow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFollowTest {
    @Test fun `an author that is not followed may be followed`() {
        assertTrue(AutoFollow.Rules.mayFollow(followStatus = 0, alreadyFollowedHere = false, sessionCount = 0, max = 10))
        assertTrue(AutoFollow.Rules.mayFollow(followStatus = null, alreadyFollowedHere = false, sessionCount = 9, max = 10))
    }

    @Test fun `an author already followed is skipped`() {
        assertFalse(AutoFollow.Rules.mayFollow(followStatus = 1, alreadyFollowedHere = false, sessionCount = 0, max = 10))
    }

    @Test fun `an author followed once in this run is not followed again`() {
        assertFalse(AutoFollow.Rules.mayFollow(followStatus = 0, alreadyFollowedHere = true, sessionCount = 0, max = 10))
    }

    @Test fun `a full session stops following`() {
        assertFalse(AutoFollow.Rules.mayFollow(followStatus = 0, alreadyFollowedHere = false, sessionCount = 10, max = 10))
    }
}
