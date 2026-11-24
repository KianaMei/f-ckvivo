package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Test

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
