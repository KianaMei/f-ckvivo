package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldKeepSettingTest {
    @Test fun foldSwitchHasACommandAndPublishesItsPersistedValue() {
        val key = FeaturePreferences.FOLD_KEEP_NOT_REFRESH
        val descriptors = SettingsUiCatalog.descriptors()
        val descriptor = descriptors.single { it.storageKey == key }
        assertEquals("home.fold.keep_feeds.enabled", descriptor.id)
        assertEquals(R.string.fold_keep_not_refresh, descriptor.titleRes)
        assertEquals(2, descriptor.page)
        assertEquals("ENHANCEMENT_ADVANCED", descriptor.section)
        assertTrue(descriptor.directToggle && descriptor.favoriteEligible)
        assertTrue(key in modeledToggleKeys)
        assertEquals(false, RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())[key])
        assertEquals(true, RemoteHookConfigContract.resolveSourceValues(mapOf(key to true))[key])
        assertEquals(false, RemoteHookConfigContract.resolveSourceValues(mapOf(key to false))[key])
    }
}
