package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * View-only bridge to IM widgets verified separately in each supported host APK.
 * No SessionInfo, IM conversation, Rips logic, message binding or SDK send API is created.
 */
final class HostNativeChatViews {
    private static final String HOST_PACKAGE = "com.ss.android.ugc.aweme";
    private static final String INPUT_FACTORY = HOST_PACKAGE
            + ".im.business.chat.feature.input.drawer.new.InputDrawerUI2";
    private static final String TITLE_FACTORY = HOST_PACKAGE
            + ".im.business.chat.feature.title.component.ChatTitleUI";
    private static final String TEXT_FACTORY = HOST_PACKAGE
            + ".im.business.chat.msgcell.common.content.root.text.TextOnlyCellUI";
    private static final String TEXT_WIDGET = HOST_PACKAGE
            + ".im.business.chat.msgcell.vh.textd.IMMsgTextView";

    final ViewGroup root;
    final TextView title;
    final View back;
    final ImageView avatar;
    final ViewGroup actions;
    final ViewGroup messageContainer;
    final ViewGroup inputContainer;
    final EditText input;
    final ViewGroup leftActions;
    final ViewGroup rightActions;
    final ImageView more;
    final ImageView tools;
    final ImageView voice;
    final ImageView emoji;
    final ImageView plus;
    final ImageView send;

    private final Activity activity;
    private final HostAdaptation.Profile profile;
    private final Class<?> textFactory;
    private final Class<?> textWidget;
    private final int receivedBackground;
    private final int sentBackground;
    private final Method tintReceivedBackground;
    private final Rect bubbleInsets;
    private final Method nativeBubbleWidth;
    private final Method nativeReverseColors;
    private final boolean portable;

    static HostNativeChatViews create(Activity activity) throws ReflectiveOperationException {
        try {
            HostAdaptation.Profile profile = profileFor(activity);
            if (profile == HostAdaptation.PROFILE_412) return new HostNativeChatViews(activity, profile, true);
            return new HostNativeChatViews(activity, profile);
        } catch (ReflectiveOperationException failure) {
            throw failure;
        } catch (RuntimeException | LinkageError failure) {
            throw new ReflectiveOperationException("抖音官方聊天组件初始化失败", failure);
        }
    }

    private HostNativeChatViews(Activity activity, HostAdaptation.Profile profile) throws ReflectiveOperationException {
        this.activity = activity;
        this.profile = profile;
        this.portable = false;
        ClassLoader loader = activity.getClass().getClassLoader();
        Object chosenLayout = hostClass("X.0YVw", loader).getMethod("LIZ").invoke(null);
        if (!(chosenLayout instanceof Integer)) throw incompatible("聊天布局返回类型");
        int layout = (Integer) chosenLayout;
        if (layout != resourceId(0x7f0d194a) && layout != resourceId(0x7f0d194b) && layout != resourceId(0x7f0d194c)) {
            throw incompatible("未知聊天布局 " + Integer.toHexString(layout));
        }
        View inflated = LayoutInflater.from(activity).inflate(layout, null, false);
        root = require(inflated, 0x7f0a2042, FrameLayout.class);
        Object rootColor = hostClass("X.0vJL", loader).getMethod("LIZIZ").invoke(null);
        if (!(rootColor instanceof Integer) || Color.alpha((Integer) rootColor) != 255) {
            throw incompatible("官方页面背景颜色");
        }
        root.setBackgroundColor((Integer) rootColor);
        LinearLayout column = require(root, 0x7f0a2063, LinearLayout.class);
        FrameLayout headerSlot = require(root, 0x7f0a4258, FrameLayout.class);
        View headerDivider = require(root, 0x7f0a205b, View.class);
        messageContainer = require(root, 0x7f0a202d, FrameLayout.class);
        FrameLayout composerSlot = require(root, 0x7f0a6444, FrameLayout.class);

        Object composer = hostClass(INPUT_FACTORY, loader).getMethod(HostResourceProfile.chatMethod(profile, "LJLJJLL"), Context.class)
                .invoke(null, activity);
        if (!(composer instanceof ViewGroup)) throw incompatible("输入栏返回类型");
        inputContainer = (ViewGroup) composer;
        if (!hostClass("androidx.constraintlayout.widget.ConstraintLayout", loader)
                .isInstance(inputContainer)) throw incompatible("输入栏根类型");
        input = require(inputContainer, 0x7f0a7d27, EditText.class);
        if (!hostClass(HOST_PACKAGE + ".im.widget.SearchableEditText", loader).isInstance(input)) {
            throw incompatible("官方输入框类型");
        }
        // The outer entrance slots flank the rounded input body. The official layout in
        // the reference uses the inner slots, so all four controls share one white pill.
        leftActions = require(inputContainer, 0x7f0a6a13, LinearLayout.class);
        rightActions = require(inputContainer, 0x7f0a9dbe, LinearLayout.class);

        // The native builder deliberately leaves decoration to its Rips UI. Use the same
        // independent host drawable factory, without constructing the Rips instance.
        View inputBody = require(inputContainer, 0x7f0a0f00, View.class);
        Object inputBackground = hostClass("X.0YVa", loader).getMethod("LIZ", Context.class)
                .invoke(null, activity);
        if (!(inputBackground instanceof Drawable)) throw incompatible("官方输入背景类型");
        inputBody.setBackground(((Drawable) inputBackground).mutate());
        tools = hostIcon(0x7f08430a, "工具");
        voice = hostIcon(0x7f084486, "语音输入");
        emoji = hostIcon(0x7f0844a9, "表情");
        plus = hostIcon(0x7f0844b1, "添加附件");
        leftActions.addView(tools, new LinearLayout.LayoutParams(dp(44), dp(44)));
        rightActions.addView(voice, new LinearLayout.LayoutParams(dp(44), dp(44)));
        rightActions.addView(emoji, new LinearLayout.LayoutParams(dp(44), dp(44)));
        rightActions.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        // This is the actual send image created by InputDrawerUI2, not an emoji control.
        // Keep it in its official parent so native text/send width negotiation is preserved.
        send = require(inputContainer, 0x7f0a4058, ImageView.class);
        send.setFocusable(true);
        send.setContentDescription("发送");
        updateComposer(false, false);

        Object nativeTitle = hostClass(TITLE_FACTORY, loader)
                .getMethod(HostResourceProfile.chatMethod(profile, "LJLIIL"), int.class, Context.class).invoke(null, resourceId(0x7f0a900f), activity);
        if (!(nativeTitle instanceof TextView)) throw incompatible("官方标题返回类型");
        title = (TextView) nativeTitle;
        if (!hostClass("com.bytedance.ies.dmt.ui.widget.DmtTextView", loader).isInstance(title)) {
            throw incompatible("官方标题类型");
        }

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        ImageView returnButton = hostIcon(0x7f0845cd, "返回");
        hostClass(HOST_PACKAGE + ".im.business.chat.feature.title.component.LeaveChatUI", loader)
                .getMethod(HostResourceProfile.chatMethod(profile, "LJLIIL"), Context.class, ImageView.class).invoke(null, activity, returnButton);
        back = returnButton;
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        avatar = createAvatar(header);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        avatarParams.setMarginEnd(dp(8));
        header.addView(avatar, avatarParams);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout headerActions = new LinearLayout(activity);
        headerActions.setGravity(Gravity.CENTER_VERTICAL);
        actions = headerActions;
        more = hostIcon(0x7f0845d2, "更多");
        more.setPadding(dp(10), dp(10), dp(10), dp(10));
        actions.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        headerSlot.removeAllViews();
        headerSlot.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        messageContainer.removeAllViews();
        composerSlot.removeAllViews();
        ViewGroup.LayoutParams nativeComposerParams = inputContainer.getLayoutParams();
        FrameLayout.LayoutParams composerParams = nativeComposerParams instanceof ViewGroup.MarginLayoutParams
                ? new FrameLayout.LayoutParams((ViewGroup.MarginLayoutParams) nativeComposerParams)
                : new FrameLayout.LayoutParams(-1, -2);
        composerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        composerParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        composerSlot.addView(inputContainer, composerParams);

        // Official root layouts overlay a 275dp panel and depend on IM logic to offset the
        // list. Reflow their actual native slots so the local list and keyboard cannot overlap.
        detach(headerSlot);
        detach(headerDivider);
        detach(messageContainer);
        detach(composerSlot);
        detach(column);
        column.removeAllViews();
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(headerSlot, new LinearLayout.LayoutParams(-1, -2));
        column.addView(headerDivider, new LinearLayout.LayoutParams(-1,
                Math.max(1, Math.round(0.5f * activity.getResources().getDisplayMetrics().density))));
        column.addView(messageContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        column.addView(composerSlot, new LinearLayout.LayoutParams(-1, -2));
        root.removeAllViews();
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        textFactory = hostClass(TEXT_FACTORY, loader);
        textWidget = hostClass(TEXT_WIDGET, loader);
        Method backgroundFactory = hostClass("X.0vMA", loader).getMethod("LIZ", boolean.class);
        receivedBackground = backgroundResource(backgroundFactory.invoke(null, false), resourceId(0x7f083b0a));
        sentBackground = backgroundResource(backgroundFactory.invoke(null, true), resourceId(0x7f083b0d));
        tintReceivedBackground = hostClass("X.0uuG", loader).getMethod("LJII", GradientDrawable.class);
        Class<?> backgroundUi = hostClass(HOST_PACKAGE
                + ".im.business.chat.msgcell.common.bg.NewBgContentCellUI", loader);
        Object insets = backgroundUi.getMethod(HostResourceProfile.chatMethod(profile, "LJJJIL")).invoke(backgroundUi.getConstructor().newInstance());
        if (!(insets instanceof Rect)) throw incompatible("官方气泡内容间距");
        bubbleInsets = new Rect((Rect) insets);
        nativeBubbleWidth = hostClass("X.0vK4", loader)
                .getMethod("LIZ", Context.class, int.class, boolean.class);
        nativeReverseColors = hostClass("X.0Yjd", loader).getMethod("LJIIJ");
        messageTextMaxWidth(Math.max(1, activity.getResources().getDisplayMetrics().widthPixels));
        // Validate both roles before the dialog is shown, so compatibility failures are
        // reported by the caller instead of appearing on the first ListView render.
        createTextBubble(messageContainer, false).bind("", messageTextColor(false), dp(240), false);
        createTextBubble(messageContainer, true).bind("", messageTextColor(true), dp(240), true);
    }

    /** A local Android view surface for builds whose private IM factories have changed. */
    private HostNativeChatViews(Activity activity, HostAdaptation.Profile profile, boolean portable) {
        this.activity = activity;
        this.profile = profile;
        this.portable = portable;
        textFactory = null;
        textWidget = TextView.class;
        receivedBackground = sentBackground = 0;
        tintReceivedBackground = nativeBubbleWidth = nativeReverseColors = null;
        bubbleInsets = new Rect(dp(14), dp(10), dp(14), dp(10));
        FrameLayout frame = new FrameLayout(activity);
        root = frame;
        frame.setBackgroundColor(dark() ? 0xff151515 : 0xfff4f5f7);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        frame.addView(column, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(4), dp(4), dp(4));
        back = portableIcon(android.R.drawable.ic_media_previous, "返回");
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        avatar = portableAvatar();
        header.addView(avatar, new LinearLayout.LayoutParams(dp(32), dp(32)));
        title = new TextView(activity);
        title.setTextSize(18);
        title.setTextColor(messageTextColor(false));
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout headerActions = new LinearLayout(activity);
        actions = headerActions;
        more = portableIcon(android.R.drawable.ic_menu_more, "更多");
        headerActions.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(headerActions, new LinearLayout.LayoutParams(-2, -2));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
        messageContainer = new FrameLayout(activity);
        column.addView(messageContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout composer = new LinearLayout(activity);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(6), dp(6), dp(6), dp(6));
        inputContainer = composer;
        leftActions = new LinearLayout(activity);
        rightActions = new LinearLayout(activity);
        tools = portableIcon(android.R.drawable.ic_menu_manage, "工具");
        leftActions.addView(tools, new LinearLayout.LayoutParams(dp(40), dp(44)));
        composer.addView(leftActions, new LinearLayout.LayoutParams(-2, -2));
        input = new EditText(activity);
        input.setTextSize(16);
        input.setTextColor(messageTextColor(false));
        input.setHintTextColor(dark() ? 0xffaaaaaa : 0xff757575);
        input.setHint("输入消息");
        input.setMaxLines(5);
        input.setMinLines(1);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        input.setBackground(portableBackground(false));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        voice = portableIcon(android.R.drawable.ic_btn_speak_now, "语音输入");
        emoji = portableIcon(android.R.drawable.ic_menu_recent_history, "表情");
        plus = portableIcon(android.R.drawable.ic_input_add, "添加附件");
        send = portableIcon(android.R.drawable.ic_menu_send, "发送");
        for (ImageView icon : new ImageView[]{voice, emoji, plus, send}) {
            rightActions.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(44)));
        }
        composer.addView(rightActions, new LinearLayout.LayoutParams(-2, -2));
        column.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        updateComposer(false, false);
    }

    private boolean dark() {
        return (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private GradientDrawable portableBackground(boolean user) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(user ? 0xff2878ef : dark() ? 0xff292929 : Color.WHITE);
        background.setCornerRadius(dp(16));
        return background;
    }

    private ImageView portableIcon(int drawable, String description) {
        ImageView image = new ImageView(activity);
        image.setImageResource(drawable);
        image.setColorFilter(dark() ? 0xffdddddd : 0xff454545);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(8), dp(8), dp(8), dp(8));
        image.setContentDescription(description);
        image.setFocusable(true);
        image.setClickable(true);
        return image;
    }

    private ImageView portableAvatar() {
        ImageView image = new ImageView(activity);
        image.setImageResource(android.R.drawable.sym_def_app_icon);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipAvatar(image);
        return image;
    }

    /** Local AI state only; no native channel state, recording or IM send logic is bound. */
    void updateComposer(boolean busy, boolean hasContent) {
        boolean showSend = busy || hasContent;
        send.setImageResource(portable ? busy ? android.R.drawable.ic_media_pause : android.R.drawable.ic_menu_send
                : resourceId(busy ? 0x7f08439b : 0x7f08415e));
        send.setContentDescription(busy ? "停止" : "发送");
        send.setVisibility(showSend ? View.VISIBLE : View.GONE);
        voice.setVisibility(showSend ? View.GONE : View.VISIBLE);
        plus.setVisibility(showSend ? View.GONE : View.VISIBLE);
        emoji.setVisibility(View.VISIBLE);
    }

    int messageTextColor(boolean user) {
        if (portable) return user ? Color.WHITE : dark() ? 0xffeeeeee : 0xff222222;
        // AbsTextCellLogic resolves these from the native bubble configuration's LIZLLL.
        return activity.getColor(resourceId(user ? 0x7f060094 : 0x7f060182));
    }

    int messageTextMaxWidth(int availableBubbleWidth) {
        if (portable) return Math.max(1, Math.min(dp(420), availableBubbleWidth - bubbleInsets.left - bubbleInsets.right));
        try {
            Object reverse = nativeReverseColors.invoke(null);
            if (!(reverse instanceof Boolean)) throw incompatible("官方气泡宽度主题类型");
            int horizontalInsets = bubbleInsets.left + bubbleInsets.right;
            int availableTextWidth = availableBubbleWidth - horizontalInsets;
            int preferredTextWidth;
            if ((Boolean) reverse) {
                int viewport = messageContainer.getWidth();
                if (viewport <= 0) viewport = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
                if (viewport <= 0) viewport = activity.getResources().getDisplayMetrics().widthPixels;
                // This native branch already returns a text limit, so the content inset
                // is applied only to our local available-width clamp.
                preferredTextWidth = Math.min(viewport, dp(500)) - dp(136);
            } else {
                Object limit = nativeBubbleWidth.invoke(null, activity, 0, true);
                if (!(limit instanceof Integer)) throw incompatible("官方气泡宽度类型");
                // The normal helper budgets the background container. Our independently
                // reused text content sits inside the official left/right Rect insets.
                preferredTextWidth = Math.min((Integer) limit, availableBubbleWidth) - horizontalInsets;
            }
            return Math.max(1, Math.min(preferredTextWidth, availableTextWidth));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new IllegalStateException("抖音官方文本消息宽度计算失败", failure);
        }
    }

    ImageView createAvatar(ViewGroup parent) throws ReflectiveOperationException {
        if (portable) return portableAvatar();
        ClassLoader loader = activity.getClass().getClassLoader();
        Class<?> factoryClass = hostClass(HOST_PACKAGE
                + ".im.business.chat.msgcell.common.basic.AvatarCellUI", loader);
        Object factory = factoryClass.getConstructor().newInstance();
        Object created = factoryClass.getMethod(HostResourceProfile.viewFactory(profile), Context.class, ViewGroup.class)
                .invoke(factory, activity, parent);
        if (!(created instanceof ImageView)
                || !hostClass("com.ss.android.ugc.exview.ImAvatarImageView", loader).isInstance(created)) {
            throw incompatible("官方头像类型");
        }
        ImageView result = (ImageView) created;
        Object params = factoryClass.getMethod("getLayoutParams").invoke(factory);
        if (!(params instanceof ViewGroup.LayoutParams)) throw incompatible("官方头像尺寸");
        result.setLayoutParams((ViewGroup.LayoutParams) params);
        result.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Native Fresco rounding also applies to remote images. The local bitmap/avatar
        // path needs an explicit oval clip so framework ImageView setters stay circular.
        clipAvatar(result);
        return result;
    }

    private static void clipAvatar(ImageView result) {
        result.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        result.setClipToOutline(true);
    }

    static final class ProfileHeader {
        final View view;
        final ImageView avatar;
        final TextView name;

        private ProfileHeader(View view, ImageView avatar, TextView name) {
            this.view = view;
            this.avatar = avatar;
            this.name = name;
        }
    }

    ProfileHeader createProfileHeader(ViewGroup parent) throws ReflectiveOperationException {
        if (portable) {
            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setGravity(Gravity.CENTER);
            header.setPadding(dp(12), dp(20), dp(12), dp(12));
            ImageView image = portableAvatar();
            header.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView name = new TextView(activity);
            name.setTextColor(messageTextColor(false));
            name.setTextSize(18);
            header.addView(name, new LinearLayout.LayoutParams(-2, -2));
            return new ProfileHeader(header, image, name);
        }
        Class<?> factoryClass = hostClass(HOST_PACKAGE
                + ".im.business.chat.msgcell.vh.strangerbigcard.StrangerTopAvatarCellUI",
                activity.getClass().getClassLoader());
        Object factory = factoryClass.getConstructor().newInstance();
        Object created = factoryClass.getMethod(HostResourceProfile.viewFactory(profile), Context.class, ViewGroup.class)
                .invoke(factory, activity, parent);
        if (!(created instanceof View)) throw incompatible("官方资料区类型");
        View view = (View) created;
        ImageView image = require(view, 0x7f0a10e6, ImageView.class);
        TextView name = require(view, 0x7f0a8189, TextView.class);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        clipAvatar(image);
        // Only this pure layout factory is used: no stranger identity, follow status,
        // relationship warning, account navigation or native holder binding is installed.
        return new ProfileHeader(view, image, name);
    }

    View createQuickAction(ViewGroup parent, String label) throws ReflectiveOperationException {
        if (portable) {
            TextView action = new TextView(activity);
            action.setText(label == null ? "" : label);
            action.setTextColor(messageTextColor(false));
            action.setPadding(dp(14), dp(10), dp(14), dp(10));
            action.setBackground(portableBackground(false));
            action.setContentDescription(label);
            action.setClickable(true);
            action.setFocusable(true);
            return action;
        }
        Object created = hostClass("X.0XZk", activity.getClass().getClassLoader())
                .getMethod("LIZIZ", ViewGroup.class).invoke(null, parent);
        if (!(created instanceof ViewGroup)) throw incompatible("官方快捷卡片类型");
        ViewGroup view = (ViewGroup) created;
        TextView text = require(view, 0x7f0a6012, TextView.class);
        text.setText(label == null ? "" : label);
        // Native action-bar artwork is supplied by the real IM data/server. Local AI
        // shortcuts use their explicit text/Unicode label without requesting that data.
        require(view, 0x7f0a464c, View.class).setVisibility(View.GONE);
        require(view, 0x7f0a5146, View.class).setVisibility(View.GONE);
        require(view, 0x7f0ab9a8, View.class).setVisibility(View.GONE);
        view.setContentDescription(label);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    ViewGroup createMessageShell(ViewGroup parent) throws ReflectiveOperationException {
        if (portable) {
            FrameLayout shell = new FrameLayout(activity);
            shell.setPadding(0, dp(6), 0, dp(6));
            return shell;
        }
        Object created = hostClass("X.0uok", activity.getClass().getClassLoader())
                .getMethod("LIZJ", Context.class).invoke(null, activity);
        if (!(created instanceof FrameLayout)) throw incompatible("官方消息行类型");
        FrameLayout view = (FrameLayout) created;
        // The native RecyclerView.LayoutParams top margin is lost when ListView creates
        // its own params, so carry the same official 6dp spacing in the actual row.
        view.setPadding(view.getPaddingLeft(), dp(6), view.getPaddingRight(), view.getPaddingBottom());
        return view;
    }

    private ImageView hostIcon(int drawable, String description) throws ReflectiveOperationException {
        Object created = hostClass(HOST_PACKAGE + ".im.sdk.widget.AutoRTLImageView",
                activity.getClass().getClassLoader())
                .getConstructor(Context.class, android.util.AttributeSet.class).newInstance(activity, null);
        if (!(created instanceof ImageView)) throw incompatible("官方图标类型");
        ImageView image = (ImageView) created;
        Drawable asset = activity.getDrawable(resourceId(drawable));
        if (asset == null) throw incompatible("官方图标资源 " + Integer.toHexString(drawable));
        image.setImageDrawable(asset.mutate());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(6), dp(6), dp(6), dp(6));
        image.setContentDescription(description);
        image.setFocusable(true);
        image.setClickable(true);
        return image;
    }

    TextBubble createTextBubble(ViewGroup parent, boolean user) throws ReflectiveOperationException {
        if (portable) {
            TextView text = new TextView(activity);
            FrameLayout bubble = new FrameLayout(activity);
            bubble.addView(text, new FrameLayout.LayoutParams(-2, -2));
            TextBubble result = new TextBubble(bubble, text);
            result.bind("", messageTextColor(user), dp(240), user);
            return result;
        }
        Object factory = textFactory.getConstructor().newInstance();
        Object created = textFactory.getMethod(HostResourceProfile.chatMethod(profile, "LJJJJL"), Context.class, ViewGroup.class)
                .invoke(factory, activity, parent);
        if (!(created instanceof View)) throw incompatible("官方文本消息返回类型");
        View view = (View) created;
        View text = require(view, 0x7f0a1dbf, View.class);
        if (!textWidget.isInstance(text)) throw incompatible("官方文本消息控件类型");
        TextBubble bubble = new TextBubble(view, text);
        bubble.bind("", messageTextColor(user), dp(240), user);
        return bubble;
    }

    final class TextBubble {
        final View view;
        private final View text;
        private final Method setText;
        private final Method setColor;
        private final Method setWidth;
        private boolean backgroundInstalled;
        private boolean currentUser;

        private TextBubble(View view, View text) throws ReflectiveOperationException {
            this.view = view;
            this.text = text;
            setText = textWidget.getMethod("setText", CharSequence.class);
            setColor = textWidget.getMethod("setTextColor", int.class);
            setWidth = textWidget.getMethod(portable ? "setMaxWidth" : "setMaxWidthPx", int.class);
            if (portable) ((TextView) text).setTextSize(16);
            else textWidget.getMethod("setTextSizePx", float.class)
                    .invoke(text, 16f * activity.getResources().getDisplayMetrics().scaledDensity);
            // Native NewBgContentCellUI places the text content inside its background
            // with this Rect; it does not add padding inside IMMsgTextView itself.
            view.setPadding(bubbleInsets.left, bubbleInsets.top, bubbleInsets.right, bubbleInsets.bottom);
            text.setPadding(0, 0, 0, 0);
        }

        void bind(CharSequence value, int color, int maxWidth, boolean user) {
            try {
                if (!backgroundInstalled || currentUser != user) {
                    Drawable background = portable ? portableBackground(user)
                            : activity.getDrawable(user ? sentBackground : receivedBackground);
                    if (background == null) throw incompatible("官方消息背景为空");
                    background = background.mutate();
                    if (!portable && !user && background instanceof GradientDrawable) {
                        tintReceivedBackground.invoke(null, background);
                    }
                    view.setBackground(background);
                    view.setPadding(bubbleInsets.left, bubbleInsets.top, bubbleInsets.right, bubbleInsets.bottom);
                    backgroundInstalled = true;
                    currentUser = user;
                }
                setWidth.invoke(text, Math.max(1, maxWidth));
                // The official self bubble is blue; its foreground is white in both skins.
                setColor.invoke(text, user ? messageTextColor(true) : color);
                setText.invoke(text, value == null ? "" : value);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new IllegalStateException("抖音官方文本消息绑定失败", failure);
            }
        }
    }

    static HostAdaptation.Profile profileFor(Context context) throws ReflectiveOperationException {
        if (context == null || !HOST_PACKAGE.equals(context.getPackageName())) {
            throw incompatible("需要在抖音中打开");
        }
        HostAdaptation.Profile installed = HostAdaptation.currentProfile();
        if (installed != null) return installed;
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(HOST_PACKAGE, 0);
            HostAdaptation.Profile profile = com.douai.module.scan.HostApkProfile.read(new java.io.File(info.sourceDir));
            if (profile == null) throw incompatible("当前官方聊天适配支持抖音 40.6.0 / 40.8.0（40809900）/ 41.0.0（41009900）");
            return profile;
        } catch (android.content.pm.PackageManager.NameNotFoundException | java.io.IOException failure) {
            throw new ReflectiveOperationException("无法读取抖音版本", failure);
        }
    }

    private int resourceId(int canonicalId) { return HostResourceProfile.id(profile, canonicalId); }

    private int backgroundResource(Object config, int expected) throws ReflectiveOperationException {
        if (config == null || !HostResourceProfile.className(profile, "X.0vM9").equals(config.getClass().getName())) {
            throw incompatible("官方消息背景配置类型");
        }
        Field resource = config.getClass().getField("LIZ");
        if (resource.getType() != int.class || resource.getInt(config) != expected) {
            throw incompatible("官方消息背景资源");
        }
        return expected;
    }

    private Class<?> hostClass(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(HostResourceProfile.className(profile, name), false, loader);
    }

    private <T extends View> T require(View parent, int id, Class<T> type)
            throws ReflectiveOperationException {
        View found = parent.findViewById(resourceId(id));
        if (!type.isInstance(found)) throw incompatible("缺少官方控件 " + Integer.toHexString(id));
        return type.cast(found);
    }

    private static ReflectiveOperationException incompatible(String detail) {
        return new ReflectiveOperationException("抖音官方聊天页面不兼容：" + detail);
    }

    private static void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
