package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.MotionEvent
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorSeekAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.danmaku.biliplayerv2.service.IPlayerCoreService

class DanmakuMarkerClientTest {
    private fun entry(progress: Long, text: String, sender: String) = Triple(progress, text, sender)

    private class RecordingPlayerCore : IPlayerCoreService {
        var seekCalls = 0
        var lastSeekPosition: Int? = null
        var lastSeekAccurate: Boolean? = null

        override fun seekTo(position: Int, accurate: Boolean) {
            seekCalls++
            lastSeekPosition = position
            lastSeekAccurate = accurate
        }
    }

    @Test
    fun `same sentence from three senders inside five seconds becomes a mark`() {
        val entries = listOf(
            entry(121_000, "前方高能", "a"),
            entry(122_500, "前方高能", "b"),
            entry(124_000, " 前方高能 ", "c"),
        )
        assertEquals(listOf(120_000L), DanmakuMarkerClient.consensus(entries, 600_000))
    }

    @Test
    fun `two senders and far apart repeats are ignored`() {
        val entries = listOf(
            entry(121_000, "前方高能", "a"),
            entry(122_500, "前方高能", "b"),
            entry(181_000, "前方高能", "c"),
        )
        assertTrue(DanmakuMarkerClient.consensus(entries, 600_000).isEmpty())
    }

    @Test
    fun `marks outside the playable window are dropped`() {
        val entries = listOf(
            entry(10_000, "打卡", "a"),
            entry(10_500, "打卡", "b"),
            entry(11_000, "打卡", "c"),
        )
        assertTrue(DanmakuMarkerClient.consensus(entries, 600_000).isEmpty())
        assertTrue(DanmakuMarkerClient.consensus(entries, 15_000).isEmpty())
    }

    @Test
    fun `bursts from the same sentence merge into one horizontal range`() {
        val entries = (0 until 3).flatMap { sender ->
            listOf(
                entry(300_000 + sender.toLong() * 500, "泪目", "s$sender"),
                entry(305_000 + sender.toLong() * 500, "泪目", "s$sender")
            )
        }
        val marks = DanmakuMarkerClient.consensus(entries, 900_000)
        assertEquals(listOf(300_000L), marks)
    }

    @Test
    fun `installer reports capability and adapted evidence and avoids duplicate registration on retry`() {
        val loader = javaClass.classLoader!!
        val registrar = PlayerPortTestRegistrar()
        val capabilities = mutableListOf<Pair<String, FeatureInstallResult>>()
        val evidences = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val logs = mutableListOf<Pair<String, String>>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { key, msg -> logs += key to msg },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            runtimeEvidence = { id, stage, _ -> evidences += id to stage },
            capabilityEvidence = { id, result -> capabilities += id to result }
        )

        val installer = DanmakuJumpMarkersFeatureInstaller(enabled = true)
        assertEquals(listOf(DanmakuJumpMarkersFeatureInstaller.ID), installer.capabilityIds)
        val result = installer.install(environment)
        val expectedCount = registrar.hooks.size
        assertTrue(expectedCount in 5..7)
        assertEquals(FeatureInstallResult.Installed(expectedCount), result)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(
            listOf(DanmakuJumpMarkersFeatureInstaller.ID to FeatureInstallResult.Installed(expectedCount)),
            capabilities
        )
        assertEquals(
            listOf(DanmakuJumpMarkersFeatureInstaller.ID to FeatureRuntimeStage.ADAPTED),
            evidences
        )

        val retryResult = installer.install(environment)
        assertEquals(FeatureInstallResult.Skipped("already-registered"), retryResult)
        assertEquals(expectedCount, registrar.registrationAttempts.size)
    }

    @Test
    fun `installer sets retry flag when direct player missing and installs once cached classes arrive`() {
        val loader = javaClass.classLoader!!
        val hidingWrapper = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == SponsorPlayerAccess.WRAPPER_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val logs = mutableListOf<Pair<String, String>>()
        val registrar = PlayerPortTestRegistrar()
        val missingEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = hidingWrapper,
            hookPoints = HookPointRegistry(hidingWrapper),
            registrar = registrar,
            logInfo = { key, msg -> logs += key to msg },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        var cached: SponsorPlayerClasses? = null
        val installer = DanmakuJumpMarkersFeatureInstaller(enabled = true) { cached }

        assertEquals(FeatureInstallResult.Skipped("missing-player-structure"), installer.install(missingEnv))
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(1, logs.count { it.first == "danmaku_marker_skip" })

        // Repeated miss while waiting for adaptation must not spam duplicate skip logs.
        assertEquals(FeatureInstallResult.Skipped("missing-player-structure"), installer.install(missingEnv))
        assertEquals(1, logs.count { it.first == "danmaku_marker_skip" })

        cached = SponsorPlayerClasses(
            continuation = SponsorPlayerAccess.RUN_CLASS,
            wrapper = SponsorPlayerAccess.WRAPPER_CLASS,
            containerScope = SponsorPlayerAccess.CONTAINER_SCOPE_CLASS,
            seek = SponsorSeekAccess.CLASS
        )
        val readyEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { key, msg -> logs += key to msg },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val retryResult = installer.install(readyEnv)
        assertEquals(FeatureInstallResult.Installed(registrar.hooks.size), retryResult)
        assertFalse(installer.requiresAdaptationRetry)
    }

    @Test
    fun `accurate seek invokes player core with true flag and reports applied evidence`() {
        val loader = javaClass.classLoader!!
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val core = RecordingPlayerCore()
        val evidences = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            runtimeEvidence = { id, stage, _ -> evidences += id to stage }
        )

        assertFalse(DanmakuJumpMarkersFeatureInstaller.performAccurateJumpSeek(access, core, 120_000L, 0L, environment))
        assertFalse(DanmakuJumpMarkersFeatureInstaller.performAccurateJumpSeek(access, core, 700_000L, 600_000L, environment))
        assertEquals(0, core.seekCalls)
        assertTrue(evidences.isEmpty())

        assertTrue(DanmakuJumpMarkersFeatureInstaller.performAccurateJumpSeek(access, core, 120_000L, 600_000L, environment))
        assertEquals(1, core.seekCalls)
        assertEquals(120_000, core.lastSeekPosition)
        assertEquals(SponsorPlayerAccess.ACCURATE_SEEK, core.lastSeekAccurate)
        assertEquals(true, core.lastSeekAccurate)
        assertEquals(listOf(DanmakuJumpMarkersFeatureInstaller.ID to FeatureRuntimeStage.APPLIED), evidences)
    }

    @Test
    fun `touch tracker passes through misses and only triggers jump on confirmed hit tap`() {
        val width = 1000f
        val duration = 1_000_000L
        val marks = listOf(200_000L, 600_000L) // x = 200f, 600f
        val hitSlop = 28f
        val touchSlop = 16f
        val nearest: (Float) -> Long? = { x ->
            DanmakuMarkerLayoutPolicy.nearestMark(x, width, duration, marks, hitSlop)
        }

        val tracker = DanmakuMarkerTouchTracker()
        val jumps = mutableListOf<Long>()

        // 1. Miss on ACTION_DOWN passes through immediately and ignores MOVE/UP.
        assertFalse(tracker.onTouch(MotionEvent.ACTION_DOWN, 400f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertFalse(tracker.onTouch(MotionEvent.ACTION_MOVE, 200f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertFalse(tracker.onTouch(MotionEvent.ACTION_UP, 200f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(jumps.isEmpty())

        // 2. Hit on ACTION_DOWN consumes gesture and triggers jump on clean ACTION_UP.
        assertTrue(tracker.onTouch(MotionEvent.ACTION_DOWN, 210f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(tracker.onTouch(MotionEvent.ACTION_MOVE, 214f, 12f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(tracker.onTouch(MotionEvent.ACTION_UP, 212f, 11f, touchSlop, nearest, { 0L }, jumps::add))
        assertEquals(listOf(200_000L), jumps)

        // 3. Dragging beyond touchSlop cancels jump even if ending near mark.
        jumps.clear()
        assertTrue(tracker.onTouch(MotionEvent.ACTION_DOWN, 595f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(tracker.onTouch(MotionEvent.ACTION_MOVE, 620f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(tracker.onTouch(MotionEvent.ACTION_UP, 620f, 10f, touchSlop, nearest, { 0L }, jumps::add))
        assertTrue(jumps.isEmpty())

        // 4. Close to current position (< 15s) does not jump.
        assertTrue(tracker.onTouch(MotionEvent.ACTION_DOWN, 600f, 10f, touchSlop, nearest, { 592_000L }, jumps::add))
        assertTrue(tracker.onTouch(MotionEvent.ACTION_UP, 600f, 10f, touchSlop, nearest, { 592_000L }, jumps::add))
        assertTrue(jumps.isEmpty())
    }

    @Test
    fun `placement aligns to unique wide visible seekbar and hides in portrait without seekbar`() {
        val wideSeekBar = SeekBarBounds(left = 40, top = 500, right = 960, bottom = 560, totalWidth = 1000)
        val narrowSeekBar = SeekBarBounds(left = 100, top = 700, right = 300, bottom = 740, totalWidth = 200)

        // Portrait with unique wide SeekBar aligns above it and matches its track bounds.
        assertEquals(
            MarkerPlacement.AlignedToSeekBar(leftMargin = 40, topMargin = 464, width = 920, height = 36),
            DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = true,
                containerWidth = 1080,
                containerHeight = 2400,
                screenWidthPx = 1080,
                isLandscape = false,
                barHeightPx = 36,
                landscapeBottomMarginPx = 88,
                visibleSeekBars = listOf(wideSeekBar, narrowSeekBar)
            )
        )

        // Portrait with no wide SeekBar hides to avoid overlapping comments at bottom.
        assertEquals(
            MarkerPlacement.Hidden,
            DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = true,
                containerWidth = 1080,
                containerHeight = 2400,
                screenWidthPx = 1080,
                isLandscape = false,
                barHeightPx = 36,
                landscapeBottomMarginPx = 88,
                visibleSeekBars = listOf(narrowSeekBar)
            )
        )

        // Portrait with ambiguous multiple wide SeekBars hides.
        assertEquals(
            MarkerPlacement.Hidden,
            DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = true,
                containerWidth = 1080,
                containerHeight = 2400,
                screenWidthPx = 1080,
                isLandscape = false,
                barHeightPx = 36,
                landscapeBottomMarginPx = 88,
                visibleSeekBars = listOf(wideSeekBar, wideSeekBar.copy(top = 800, bottom = 860))
            )
        )

        // Landscape without visible SeekBar falls back to bottom margin.
        assertEquals(
            MarkerPlacement.LandscapeFallback(bottomMargin = 88, height = 36),
            DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = true,
                containerWidth = 2400,
                containerHeight = 1080,
                screenWidthPx = 2400,
                isLandscape = true,
                barHeightPx = 36,
                landscapeBottomMarginPx = 88,
                visibleSeekBars = emptyList()
            )
        )

        // Empty marks always hide even in landscape with SeekBar.
        assertEquals(
            MarkerPlacement.Hidden,
            DanmakuMarkerLayoutPolicy.computePlacement(
                hasMarks = false,
                containerWidth = 2400,
                containerHeight = 1080,
                screenWidthPx = 2400,
                isLandscape = true,
                barHeightPx = 36,
                landscapeBottomMarginPx = 88,
                visibleSeekBars = listOf(wideSeekBar)
            )
        )
    }
}
