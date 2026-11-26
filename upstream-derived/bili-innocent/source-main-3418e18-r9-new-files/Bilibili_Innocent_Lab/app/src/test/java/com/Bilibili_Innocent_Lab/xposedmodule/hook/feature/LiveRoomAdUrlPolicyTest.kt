package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Application
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class LiveRoomAdUrlPolicyTest {
    private val pendant =
        "https://live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html?&room_id=1950045894&is_live_half_webview=1"

    @Test fun activityPendantMainDocumentBecomesEmptyPage() {
        assertEquals(LiveRoomAdAction.EMPTY_PAGE, LiveRoomAdUrlPolicy.decide(pendant, isMainFrame = true))
    }

    @Test fun nuwaSubResourceBecomesTransparentPixel() {
        val url = "https://i0.hdslb.com/bfs/live/nuwa/pendant-bg.png"
        assertEquals(LiveRoomAdAction.TINY_IMAGE, LiveRoomAdUrlPolicy.decide(url, isMainFrame = false))
    }

    @Test fun mortalCultivatorIsCovered() {
        val url = "https://live.bilibili.com/activity/mortal_cultivator/panel/index.html"
        assertEquals(LiveRoomAdAction.EMPTY_PAGE, LiveRoomAdUrlPolicy.decide(url, isMainFrame = true))
    }

    @Test fun mainDocumentsOutsideActivityPathStayUntouched() {
        val url = "https://live.bilibili.com/nuwa-in-article/read"
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide(url, isMainFrame = true))
    }

    @Test fun ordinaryLiveRoomRequestsAreUntouched() {
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("https://live.bilibili.com/1950045894", true))
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("https://i0.hdslb.com/bfs/face/avatar.jpg", false))
    }

    @Test fun nonHttpSchemesAreRejected() {
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("file:///android_asset/nuwa/index.html", true))
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("data:image/png;base64,nuwa", false))
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide(null, true))
    }

    @Test fun emptyAndBlankUrlsAreUntouched() {
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("", false))
        assertEquals(LiveRoomAdAction.NONE, LiveRoomAdUrlPolicy.decide("   ", true))
    }
}

class LiveRoomWebAdFeatureInstallerTest {
    private val pendantUrl =
        "https://live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html?room_id=1950045894"

    private class FakeNativeWebView {
        @Suppress("unused")
        class d : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? = null
        }
    }

    private class FakeLegacyOnlyWebView {
        @Suppress("unused")
        class d : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
        }
    }

    private class FakeWebView : WebView(allocate(Application::class.java)) {
        var shown = View.VISIBLE
        var currentUrl: String? = null
        var setVisibilityCalls = 0
        var shouldThrowOnSet = false
        override fun getVisibility(): Int = shown
        override fun setVisibility(value: Int) {
            setVisibilityCalls += 1
            if (shouldThrowOnSet) error("host view failure")
            shown = value
        }
        override fun getUrl(): String? = currentUrl
    }

    private class FakeRequest(
        val rawUrl: String,
        private val mainFrame: Boolean
    ) : WebResourceRequest {
        override fun getUrl(): Uri = error("JVM test uses requestUrl extractor")
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private fun allocateWebView(
        initialVisibility: Int = View.VISIBLE,
        throwOnSet: Boolean = false
    ): FakeWebView = allocate(FakeWebView::class.java).apply {
        shown = initialVisibility
        shouldThrowOnSet = throwOnSet
    }

    private fun loaderFor(outerTarget: Class<*>): ClassLoader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == "com.bilibili.app.comm.bh.NativeWebView") return outerTarget
            return super.loadClass(name, resolve)
        }
    }

    private fun webResourceRequest(url: String, mainFrame: Boolean): WebResourceRequest =
        FakeRequest(url, mainFrame)

    private fun createInstaller(
        harness: Harness,
        enabled: Boolean = true
    ) = LiveRoomWebAdFeatureInstaller(
        enabled = enabled,
        mainPost = { harness.posted += it },
        requestUrl = { req -> (req as? FakeRequest)?.rawUrl },
        responseBuilder = { allocate(WebResourceResponse::class.java) }
    )

    private data class Harness(
        val registrar: PlayerPortTestRegistrar = PlayerPortTestRegistrar(),
        val statuses: MutableList<Pair<String, String>> = mutableListOf(),
        val capabilities: MutableList<Pair<String, FeatureInstallResult>> = mutableListOf(),
        val stages: MutableList<FeatureRuntimeStage> = mutableListOf(),
        val posted: MutableList<Runnable> = mutableListOf()
    ) {
        fun environment(loader: ClassLoader) = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses += channel to status },
            runtimeEvidence = { id, stage, _ ->
                assertEquals(LiveRoomWebAdFeatureInstaller.ID, id)
                stages += stage
            },
            capabilityEvidence = { id, result ->
                capabilities += id to result
            }
        )

        fun drainMain() {
            while (posted.isNotEmpty()) {
                posted.removeAt(0).run()
            }
        }
    }

    @Test
    fun reportsSingleStatusCapabilityAndAdaptedForFullAndPartialInstallations() {
        val fullHarness = Harness()
        val fullResult = createInstaller(fullHarness)
            .install(fullHarness.environment(loaderFor(FakeNativeWebView::class.java)))
        assertEquals(FeatureInstallResult.Installed(2, complete = true), fullResult)
        assertEquals(listOf("live_room_web_ad_status" to "success"), fullHarness.statuses)
        assertEquals(listOf(LiveRoomWebAdFeatureInstaller.ID to fullResult), fullHarness.capabilities)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), fullHarness.stages)

        val partialHarness = Harness(registrar = PlayerPortTestRegistrar(failId = "live_room_web_ad.request"))
        val partialResult = createInstaller(partialHarness)
            .install(partialHarness.environment(loaderFor(FakeNativeWebView::class.java)))
        assertEquals(FeatureInstallResult.Installed(1, complete = false), partialResult)
        assertEquals(listOf("live_room_web_ad_status" to "partial:1/2"), partialHarness.statuses)
        assertEquals(listOf(LiveRoomWebAdFeatureInstaller.ID to partialResult), partialHarness.capabilities)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), partialHarness.stages)

        val noLegacyHarness = Harness()
        val noLegacyResult = createInstaller(noLegacyHarness)
            .install(noLegacyHarness.environment(loaderFor(FakeLegacyOnlyWebView::class.java)))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), noLegacyResult)
        assertEquals(listOf("live_room_web_ad_status" to "success"), noLegacyHarness.statuses)
        assertEquals(listOf(LiveRoomWebAdFeatureInstaller.ID to noLegacyResult), noLegacyHarness.capabilities)

        val failedHarness = Harness(registrar = PlayerPortTestRegistrar(failId = "live_room_web_ad.request"))
        val failedResult = createInstaller(failedHarness)
            .install(failedHarness.environment(loaderFor(FakeLegacyOnlyWebView::class.java)))
        assertEquals(FeatureInstallResult.Skipped("no-safe-hook-point"), failedResult)
        assertEquals(listOf("live_room_web_ad_status" to "failed"), failedHarness.statuses)
        assertTrue(failedHarness.capabilities.isEmpty())
        assertTrue(failedHarness.stages.isEmpty())
    }

    @Test
    fun hidesWebViewOnEmptyPageAndRestoresOnlyWhenReusedForNormalMainFrame() {
        val h = Harness()
        val client = allocate(FakeNativeWebView.d::class.java)
        createInstaller(h).install(h.environment(loaderFor(FakeNativeWebView::class.java)))

        val adWebView = allocateWebView(View.VISIBLE)
        val untouchedWebView = allocateWebView(View.INVISIBLE)

        // 普通从未隐藏过的 WebView 访问非广告主文档时，不发主线程任务，也不改原 visibility。
        val normalReq = webResourceRequest("https://live.bilibili.com/1950045894", mainFrame = true)
        val normalRes = h.registrar.invoke("live_room_web_ad.request", client, arrayOf(untouchedWebView, normalReq)) { null }
        assertNull(normalRes)
        assertTrue(h.posted.isEmpty())
        assertEquals(View.INVISIBLE, untouchedWebView.visibility)
        assertEquals(0, untouchedWebView.setVisibilityCalls)

        // 命中活动挂件主文档（EMPTY_PAGE）：延迟主线程设为 View.GONE。
        val adReq = webResourceRequest(pendantUrl, mainFrame = true)
        val blockedRes = h.registrar.invoke("live_room_web_ad.request", client, arrayOf(adWebView, adReq)) { null }
        assertTrue(blockedRes is WebResourceResponse)
        assertEquals(1, h.posted.size)
        h.drainMain()
        assertEquals(View.GONE, adWebView.visibility)

        // 非主文档（如普通子资源请求）不会错误恢复已隐藏的 WebView。
        val subRes = webResourceRequest("https://i0.hdslb.com/bfs/face/avatar.jpg", mainFrame = false)
        h.registrar.invoke("live_room_web_ad.request", client, arrayOf(adWebView, subRes)) { null }
        assertTrue(h.posted.isEmpty())
        assertEquals(View.GONE, adWebView.visibility)

        // 同一 WebView 被宿主演进复用到普通非广告主文档时，主线程恢复为 View.VISIBLE。
        h.registrar.invoke("live_room_web_ad.request", client, arrayOf(adWebView, normalReq)) { null }
        assertEquals(1, h.posted.size)
        h.drainMain()
        assertEquals(View.VISIBLE, adWebView.visibility)

        // 恢复后再加载普通页，不会再次发送冗余恢复任务。
        h.registrar.invoke("live_room_web_ad.request", client, arrayOf(adWebView, normalReq)) { null }
        assertTrue(h.posted.isEmpty())
    }

    @Test
    fun legacyEntryHidesAndRestoresAndMainGuardAbsorbsViewExceptions() {
        HostThreadGuard.resetForTests()
        val h = Harness()
        val client = allocate(FakeNativeWebView.d::class.java)
        createInstaller(h).install(h.environment(loaderFor(FakeNativeWebView::class.java)))

        val legacyWebView = allocateWebView(View.VISIBLE).apply { currentUrl = pendantUrl }
        val legacyBlocked = h.registrar.invoke(
            "live_room_web_ad.request_legacy",
            client,
            arrayOf(legacyWebView, pendantUrl)
        ) { null }
        assertTrue(legacyBlocked is WebResourceResponse)
        h.drainMain()
        assertEquals(View.GONE, legacyWebView.visibility)

        legacyWebView.currentUrl = "https://live.bilibili.com/1950045894"
        h.registrar.invoke(
            "live_room_web_ad.request_legacy",
            client,
            arrayOf(legacyWebView, "https://live.bilibili.com/1950045894")
        ) { null }
        h.drainMain()
        assertEquals(View.VISIBLE, legacyWebView.visibility)

        // 若隐藏后尚未在主线程执行就立刻切换回普通主文档，最终保持 VISIBLE 不被延迟 hide 误隐藏。
        val racingWebView = allocateWebView(View.VISIBLE)
        h.registrar.invoke(
            "live_room_web_ad.request",
            client,
            arrayOf(racingWebView, webResourceRequest(pendantUrl, mainFrame = true))
        ) { null }
        h.registrar.invoke(
            "live_room_web_ad.request",
            client,
            arrayOf(racingWebView, webResourceRequest("https://live.bilibili.com/1950045894", mainFrame = true))
        ) { null }
        h.drainMain()
        assertEquals(View.VISIBLE, racingWebView.visibility)

        // 主线程修改 visibility 抛异常时由 HostThreadGuard 拦下，不逃逸到宿主主线程。
        val throwingWebView = allocateWebView(View.VISIBLE, throwOnSet = true)
        h.registrar.invoke(
            "live_room_web_ad.request",
            client,
            arrayOf(throwingWebView, webResourceRequest(pendantUrl, mainFrame = true))
        ) { null }
        h.drainMain()
        assertEquals(1L, HostThreadGuard.failureCounts()["live_room_web_ad.hide"])
    }

    private companion object {
        fun <T> allocate(type: Class<T>): T {
            val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
            @Suppress("UNCHECKED_CAST")
            return unsafe.allocateInstance(type) as T
        }
    }
}
