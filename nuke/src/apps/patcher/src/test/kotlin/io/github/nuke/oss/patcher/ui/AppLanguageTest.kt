package io.github.nuke.oss.patcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun `a system language list maps to the offered choice of its first language`() {
        assertEquals("en", AppLanguage.choiceOf("en"))
        assertEquals("en", AppLanguage.choiceOf("en-US,zh-CN"))
        assertEquals("zh", AppLanguage.choiceOf("zh-Hans-CN"))
    }

    @Test fun `no language or one that is not offered follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.choiceOf(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.choiceOf("fr-FR,en"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.choiceOf("zhx"))
    }
}
