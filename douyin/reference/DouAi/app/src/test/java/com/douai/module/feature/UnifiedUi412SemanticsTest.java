package com.douai.module.feature;

import com.douai.module.config.ComponentHideSettings.Component;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UnifiedUi412SemanticsTest {
    @Test public void supportsAccessibleActionLabelsIncludingCountsAndToggleStates() {
        assertEquals(Component.LIKE, UnifiedUi412Hook.semanticComponent("", "未点赞，喜欢1.2万，按钮", "android.widget.ImageView"));
        assertEquals(Component.COMMENT, UnifiedUi412Hook.semanticComponent("", "评论128，按钮", "android.widget.FrameLayout"));
        assertEquals(Component.COLLECT, UnifiedUi412Hook.semanticComponent("", "取消收藏", "android.widget.ImageView"));
        assertEquals(Component.SHARE, UnifiedUi412Hook.semanticComponent("share_container", "", "android.widget.FrameLayout"));
    }

    @Test public void doesNotGuessObfuscatedResourcesOrTreatArbitraryDescriptionsAsActions() {
        assertNull(UnifiedUi412Hook.semanticComponent("e29", "", "X.1Gio"));
        assertNull(UnifiedUi412Hook.semanticComponent("title", "", "android.widget.TextView"));
        assertNull(UnifiedUi412Hook.semanticComponent("", "这个视频的评论区很有趣", "android.widget.TextView"));
        assertNull(UnifiedUi412Hook.semanticComponent("", "null", "android.view.TextureView"));
    }

    @Test public void recognizesNamedProgressWithoutHidingDanmakuByDefault() {
        assertEquals(Component.PROGRESS, UnifiedUi412Hook.semanticComponent("", "", "com.ss.android.ugc.aweme.feed.widget.LineProgressBar"));
        assertNull(UnifiedUi412Hook.semanticComponent("", "", "com.ss.android.ugc.aweme.feed.danmaku.FeedDanmakuView"));
    }
}
