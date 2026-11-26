package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuMarkersSettingTest {
    @Test
    fun danmakuMarkersSwitchIsCatalogedModeledAndHandledInPresenter() {
        val key = FeaturePreferences.DANMAKU_JUMP_MARKERS
        val descriptors = SettingsUiCatalog.descriptors()
        val descriptor = descriptors.single { it.storageKey == key }
        assertEquals("player.danmaku_jump_markers", descriptor.id)
        assertEquals(R.string.danmaku_jump_markers, descriptor.titleRes)
        assertEquals(2, descriptor.page)
        assertEquals("ENHANCEMENT_ADVANCED", descriptor.section)
        assertTrue(descriptor.directToggle && descriptor.favoriteEligible)
        assertTrue(key in modeledToggleKeys)

        val store = SettingStore(descriptors, mapOf(key to true))
        assertTrue(store.state(descriptor.id).checked)
        assertTrue(store.request(descriptor.id, false) { false }.accepted)

        val presenter = SourceContract.read("ui/activity/SettingCommandsPresenter.kt")
        val executeToggle = presenter.after("internal fun MainActivity.executeModeledToggle(")
            .before("internal val modeledToggleKeys")
        assertTrue(executeToggle.contains("FeaturePreferences.DANMAKU_JUMP_MARKERS"))

        assertEquals(true, RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())[key])
        assertEquals(false, RemoteHookConfigContract.resolveSourceValues(mapOf(key to false))[key])
        assertEquals(true, RemoteHookConfigContract.resolveSourceValues(mapOf(key to true))[key])
    }
}
