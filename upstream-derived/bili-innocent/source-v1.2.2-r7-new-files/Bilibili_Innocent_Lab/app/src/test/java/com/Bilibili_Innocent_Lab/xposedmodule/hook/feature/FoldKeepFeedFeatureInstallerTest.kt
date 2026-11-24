package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import androidx.window.core.layout.WindowSizeClass
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.pegasus.components.windowpane.WindowPaneStyleComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldKeepFeedFeatureInstallerTest {
    private val loader = javaClass.classLoader

    @Test
    fun unfoldsWithoutReloadingAndStillLaysOutWide() {
        assertKeepsFeed(WindowSizeClass.NORMAL, WindowSizeClass.MEDIUM)
    }

    @Test
    fun foldsWithoutReloadingAndStillLaysOutNarrow() {
        assertKeepsFeed(WindowSizeClass.MEDIUM, WindowSizeClass.NORMAL)
    }

    @Test
    fun doesNotInstallTheWindowPaneHookWhenDisabledOrOnAnotherHostVersion() {
        val registrar = PlayerPortTestRegistrar()
        val target = WindowPaneStyleComponent()
        val environment = environment(registrar)
        FoldKeepFeedFeatureInstaller(false, 9180300).install(environment)
        FoldKeepFeedFeatureInstaller(true, 9190300).install(environment)

        assertTrue(registrar.hooks.values.none { it.member.declaringClass == target.javaClass })
        target.f(WindowSizeClass.NORMAL, false)
        resize(registrar, target, WindowSizeClass.MEDIUM)
        assertEquals(1, target.refreshCount)
    }

    private fun assertKeepsFeed(before: WindowSizeClass, after: WindowSizeClass) {
        val registrar = PlayerPortTestRegistrar()
        FoldKeepFeedFeatureInstaller(true, 9180300).install(environment(registrar))
        val target = WindowPaneStyleComponent()
        target.f(before, false)

        resize(registrar, target, after)

        assertEquals(0, target.refreshCount)
        assertEquals(after, target.layoutSize)
    }

    private fun resize(registrar: PlayerPortTestRegistrar, target: WindowPaneStyleComponent, size: WindowSizeClass) {
        val hook = registrar.hooks.entries.singleOrNull { it.value.member.declaringClass == target.javaClass }
        if (hook == null) {
            target.f(size, true)
        } else {
            registrar.invoke(hook.key, target, arrayOf(size, true)) { arguments ->
                target.f(arguments[0] as WindowSizeClass, arguments[1] as Boolean)
            }
        }
    }

    private fun environment(registrar: PlayerPortTestRegistrar) = HookEnvironment(
        "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
        { _, _ -> }, { _, _ -> }, { _, _ -> }
    )
}
