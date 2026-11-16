package com.douai.module.feature;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutoFollow412Test {
    @Test public void oneExplicitNativeFollowControlIsIdentifiedWithoutClickingItDuringLookup() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity); activity.setContentView(root);
        View follow = new View(activity); follow.setContentDescription("关注按钮"); follow.setClickable(true); root.addView(follow);
        TextView caption = new TextView(activity); caption.setText("关注"); caption.setClickable(true); root.addView(caption);
        assertSame(follow, AutoFollowHook.uniqueFollowButton412(root));
        follow.setEnabled(false); assertNull(AutoFollowHook.uniqueFollowButton412(root));
        activity.finish();
    }
    @Test public void ambiguousOrAlreadyFollowedDescriptionsDoNotAuthorizeAnyControl() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity); activity.setContentView(root);
        for (int i = 0; i < 2; i++) { View button = new View(activity); button.setContentDescription("关注"); button.setClickable(true); root.addView(button); }
        assertNull(AutoFollowHook.uniqueFollowButton412(root));
        root.removeAllViews();
        View followed = new View(activity); followed.setContentDescription("已关注"); followed.setClickable(true); root.addView(followed);
        assertNull(AutoFollowHook.uniqueFollowButton412(root));
        activity.finish();
    }
}
