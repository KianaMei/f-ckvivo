package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import androidx.window.core.layout.WindowSizeClass
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bilibili.pegasus.components.windowpane.WindowPaneStyleComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldKeepFeedFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private object FakePegasusConfig {
        @JvmStatic fun getEnableFoldKeepNotRefresh(): Boolean = false
        @JvmStatic fun getEnableKeepFeedsOnWideColumnSwitch(): Boolean = false
    }

    private fun loaderWithFakeConfig(): ClassLoader = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "com.bilibili.pegasus.PegasusDDConfigKt") return FakePegasusConfig::class.java
            return super.loadClass(name, resolve)
        }
    }

    private fun loaderWithoutWindowPane(): ClassLoader = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "com.bilibili.pegasus.components.windowpane.WindowPaneStyleComponent") {
                throw ClassNotFoundException(name)
            }
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun unfoldsWithoutReloadingAndStillLaysOutWide() {
        assertKeepsFeed(9180300, WindowSizeClass.NORMAL, WindowSizeClass.MEDIUM)
    }

    @Test
    fun foldsWithoutReloadingAndStillLaysOutNarrow() {
        assertKeepsFeed(9180300, WindowSizeClass.MEDIUM, WindowSizeClass.NORMAL)
    }

    @Test
    fun fallsBackToWindowPaneLayoutOnlyWhenKeepMethodsAreMissingOnOtherHostVersions() {
        assertKeepsFeed(9190300, WindowSizeClass.NORMAL, WindowSizeClass.MEDIUM)
    }

    @Test
    fun prefersKeepMethodsOnOtherHostVersionsWhenPresentAndReportsEvidenceOnce() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()
        val installer = FoldKeepFeedFeatureInstaller(true, 9190300)
        assertEquals(listOf(FoldKeepFeedFeatureInstaller.ID), installer.capabilityIds)

        val result = installer.install(environment(registrar, loaderWithFakeConfig(), stages, capabilities))
        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals(listOf(FoldKeepFeedFeatureInstaller.ID to FeatureInstallResult.Installed(2)), capabilities)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
        assertTrue(registrar.hooks.values.none { it.member.declaringClass == WindowPaneStyleComponent::class.java })

        assertEquals(true, registrar.invoke("fold.keep.getEnableFoldKeepNotRefresh"))
        assertEquals(true, registrar.invoke("fold.keep.getEnableKeepFeedsOnWideColumnSwitch"))
        assertEquals(
            listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
            stages
        )
    }

    @Test
    fun doesNotInstallHooksWhenDisabledOrWhenBothHostStructuresAreMissing() {
        val registrar = PlayerPortTestRegistrar()
        val target = WindowPaneStyleComponent()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()

        val disabled = FoldKeepFeedFeatureInstaller(false, 9180300)
        assertTrue(disabled.capabilityIds.isEmpty())
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            disabled.install(environment(registrar, loader, stages, capabilities))
        )

        val missing = FoldKeepFeedFeatureInstaller(true, 9190300)
        assertEquals(
            FeatureInstallResult.Skipped("missing-host-structure"),
            missing.install(environment(registrar, loaderWithoutWindowPane(), stages, capabilities))
        )

        assertTrue(registrar.hooks.values.none { it.member.declaringClass == target.javaClass })
        assertTrue(stages.isEmpty())
        assertTrue(capabilities.isEmpty())

        target.f(WindowSizeClass.NORMAL, false)
        resize(registrar, target, WindowSizeClass.MEDIUM)
        assertEquals(1, target.refreshCount)
    }

    private fun assertKeepsFeed(hostVersionCode: Int, before: WindowSizeClass, after: WindowSizeClass) {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()
        val installer = FoldKeepFeedFeatureInstaller(true, hostVersionCode)
        assertEquals(listOf(FoldKeepFeedFeatureInstaller.ID), installer.capabilityIds)

        val result = installer.install(environment(registrar, loader, stages, capabilities))
        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals(listOf(FoldKeepFeedFeatureInstaller.ID to FeatureInstallResult.Installed(1)), capabilities)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)

        val target = WindowPaneStyleComponent()
        target.f(before, false)
        // allowRefresh == false 时直接放行，不触发拦截证据。
        resize(registrar, target, before, allowRefresh = false)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)

        resize(registrar, target, after)
        resize(registrar, target, before)

        assertEquals(0, target.refreshCount)
        assertEquals(before, target.layoutSize)
        // 多次折叠/展开切换仅首次上报 OBSERVED + APPLIED，防止刷爆诊断计数。
        assertEquals(
            listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
            stages
        )
    }

    private fun resize(
        registrar: PlayerPortTestRegistrar,
        target: WindowPaneStyleComponent,
        size: WindowSizeClass,
        allowRefresh: Boolean = true
    ) {
        val hook = registrar.hooks.entries.singleOrNull { it.value.member.declaringClass == target.javaClass }
        if (hook == null) {
            target.f(size, allowRefresh)
        } else {
            registrar.invoke(hook.key, target, arrayOf(size, allowRefresh)) { arguments ->
                target.f(arguments[0] as WindowSizeClass, arguments[1] as Boolean)
            }
        }
    }

    private fun environment(
        registrar: PlayerPortTestRegistrar,
        classLoader: ClassLoader = loader,
        stages: MutableList<FeatureRuntimeStage> = mutableListOf(),
        capabilities: MutableList<Pair<String, FeatureInstallResult>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = classLoader,
        hookPoints = HookPointRegistry(classLoader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> },
        runtimeEvidence = { id, stage, _ ->
            assertEquals(FoldKeepFeedFeatureInstaller.ID, id)
            stages += stage
        },
        capabilityEvidence = { id, result ->
            capabilities += id to result
        }
    )
}
