package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.feed.AutoLike
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLikeTest {
    @Test fun `a video that is not liked yet may be liked`() {
        assertTrue(AutoLike.Rules.mayLike(userDigg = false, alreadyLikedHere = false, sessionCount = 0, max = 20))
        assertTrue(AutoLike.Rules.mayLike(userDigg = null, alreadyLikedHere = false, sessionCount = 19, max = 20))
    }

    @Test fun `a video the account already liked is skipped`() {
        assertFalse(AutoLike.Rules.mayLike(userDigg = true, alreadyLikedHere = false, sessionCount = 0, max = 20))
    }

    @Test fun `a video liked once in this run is not liked again`() {
        assertFalse(AutoLike.Rules.mayLike(userDigg = false, alreadyLikedHere = true, sessionCount = 0, max = 20))
    }

    @Test fun `a full session stops adding likes`() {
        assertFalse(AutoLike.Rules.mayLike(userDigg = false, alreadyLikedHere = false, sessionCount = 20, max = 20))
    }
}
