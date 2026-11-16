package com.douai.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;
import com.douai.module.scan.HostAdaptation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme")
public final class PortableChat412Test {
    private PortableActivity activity;
    @Before public void setup() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_412);
        activity = Robolectric.buildActivity(PortableActivity.class).setup().get();
    }
    @After public void cleanup() { activity.finish(); HostAdaptation.setCurrentProfile(null); }

    @Test public void rendersAndBindsBothRolesWithoutAnyPrivateHostFactoryOrResource() throws Exception {
        HostNativeChatViews views = HostNativeChatViews.create(activity);
        assertNotNull(views.root);
        assertNotNull(views.input);
        HostNativeChatViews.TextBubble bubble = views.createTextBubble(views.messageContainer, false);
        bubble.bind("本地 AI 回复", views.messageTextColor(false), 240, false);
        assertEquals("本地 AI 回复", findText(bubble.view).getText().toString());
        bubble.bind("用户消息", views.messageTextColor(true), 180, true);
        assertEquals("用户消息", findText(bubble.view).getText().toString());
        views.updateComposer(true, true);
        assertEquals("停止", views.send.getContentDescription());
        assertEquals(View.VISIBLE, views.send.getVisibility());
        views.updateComposer(false, false);
        assertEquals(View.GONE, views.send.getVisibility());
        assertEquals(View.VISIBLE, views.plus.getVisibility());
        assertNotNull(views.createProfileHeader(views.root));
        assertNotNull(views.createQuickAction(views.root, "继续"));
    }

    @Test public void launchesSystemDocumentsForImagesAndVideoInsteadOfAnUnverifiedNativeModel() {
        HostMediaPicker image = HostMediaPicker.launch(activity, false, ignored -> { });
        assertNotNull(image);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.registry.intent.getAction());
        assertEquals("image/*", activity.registry.intent.getType());
        assertTrue(activity.registry.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertFalse(activity.registry.intent.hasExtra("extra_choose_parameters"));
        image.close();
        HostMediaPicker video = HostMediaPicker.launch(activity, true, ignored -> { });
        assertNotNull(video);
        assertEquals("video/*", activity.registry.intent.getType());
        video.close();
    }

    private static TextView findText(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    public static final class PortableActivity extends Activity {
        final Registry registry = new Registry();
        @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; }
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
    }
    private static final class Registry extends ActivityResultRegistry {
        Intent intent;
        @Override public <I,O> void onLaunch(int code, ActivityResultContract<I,O> contract, I input, ActivityOptionsCompat options) {
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }
}
