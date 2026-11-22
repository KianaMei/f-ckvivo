package local.codex.vivoportal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "VPortalPlus";
    private static final String MODULE_PACKAGE = "local.codex.vivoportal";
    private static final String TARGET_PACKAGE = "com.vivo.vportal";
    private static final long TARGET_VERSION = 170001180L;
    private static final String GALLERY_PACKAGE = "com.vivo.gallery";
    private static final String FILE_MANAGER_PACKAGE = "com.android.filemanager";
    private static final String FILE_MANAGER_ACTIVITY = "com.android.filemanager.FileManagerListActivity";
    private static final String FILE_POSITION_ACTIVITY = "com.android.filemanager.ViewNonCompressedFilePositionActivity";
    private static final String RECYCLE_ACTIVITY = "com.android.filemanager.recycle.view.RecycleMoveInOutSideActivity";
    private static final String INTENT_RECYCLE = "com.vivo.filemanager.intent.action.OPEN_RECYCLE_FILE_CONFIRM_DELETE";
    private static final String RECYCLE_PERMISSION = "com.vivo.filemanager.permission.OPEN_FILE_LIST";
    private static final String MENU_CLASS = "O2.o";
    private static final String MEDIA_ITEM = "N3.g";
    private static final String FILE_ITEM = "N3.c";
    private static final int ACTION_GALLERY = 0x56500001;
    private static final int ACTION_FILE_MANAGER = 0x56500002;
    private static final int ACTION_RECYCLE = 0x56500003;
    private static Boolean supportedVersion;

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static void logError(String message, Throwable error) {
        String detail = message + " (" + error.getClass().getSimpleName() + ")";
        Log.e(TAG, detail);
        XposedBridge.log(TAG + ": " + detail);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)
                || !TARGET_PACKAGE.equals(loadPackageParam.processName)) {
            return;
        }
        log("loaded in " + loadPackageParam.processName);
        try {
            ClassLoader classLoader = loadPackageParam.classLoader;
            installClickHook(classLoader);
            installLayoutHooks(classLoader);
        } catch (Throwable error) {
            logError("install hooks failed", error);
        }
    }

    private static void installLayoutHooks(ClassLoader classLoader) {
        XC_MethodHook updateMenu = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    updateActionState(param.thisObject, (Integer) param.args[0]);
                } catch (Throwable error) {
                    logError("update action state failed", error);
                }
            }
        };
        String[] layouts = {
            "com.vivo.vportal.ui.pc.PCRootLayout",
            "com.vivo.vportal.ui.page.RootLayout"
        };
        for (String name : layouts) {
            try {
                Class<?> layoutClass = XposedHelpers.findClass(name, classLoader);
                XposedHelpers.findAndHookMethod(layoutClass, "x", int.class, int.class, updateMenu);
                log("hooked " + name);
            } catch (Throwable error) {
                logError("layout hook unavailable: " + name, error);
            }
        }
    }

    private static void installClickHook(ClassLoader classLoader) {
        Class<?> listenerClass = XposedHelpers.findClass("V3.H", classLoader);
        Class<?> menuClass = XposedHelpers.findClass(MENU_CLASS, classLoader);
        XposedHelpers.findAndHookMethod(listenerClass, "a", menuClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int action = actionId(param.args[0]);
                if (action == 0) {
                    return;
                }
                param.setResult(null);
                Object layout = readField(param.thisObject, "a");
                if (layout == null) {
                    return;
                }
                try {
                    handleAction(layout, action);
                } catch (Throwable error) {
                    logError("action " + action + " failed", error);
                    toast((Context) callMethod(layout, "getContext"), "无法完成此操作");
                }
            }
        });
        log("hooked action click");
    }

    private static Object createMenu(Object layout, Context context, String title, int id) {
        ClassLoader classLoader = layout.getClass().getClassLoader();
        Class<?> menuClass = XposedHelpers.findClass(MENU_CLASS, classLoader);
        Drawable icon = actionIcon(context, id);
        Object menu = XposedHelpers.newInstance(menuClass, icon, title, id);
        writeField(menu, "i", title);
        return menu;
    }

    private static Drawable actionIcon(Context context, int action) {
        String name;
        int fallback;
        switch (action) {
            case ACTION_GALLERY:
                name = "ic_action_gallery";
                fallback = android.R.drawable.ic_menu_gallery;
                break;
            case ACTION_FILE_MANAGER:
                name = "ic_action_file_manager";
                fallback = android.R.drawable.ic_menu_agenda;
                break;
            default:
                name = "ic_action_delete";
                fallback = android.R.drawable.ic_menu_delete;
                break;
        }
        try {
            Context module = context.createPackageContext(MODULE_PACKAGE, 0)
                    .createConfigurationContext(context.getResources().getConfiguration());
            Resources resources = module.getResources();
            int resourceId = resources.getIdentifier(name, "drawable", MODULE_PACKAGE);
            return resources.getDrawable(resourceId, null).mutate();
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException error) {
            logError("action icon unavailable", error);
            return context.getDrawable(fallback);
        }
    }

    private static boolean supportsHost(Context context) throws Exception {
        if (supportedVersion == null) {
            long version = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0).getLongVersionCode();
            supportedVersion = version == TARGET_VERSION;
            if (!supportedVersion) {
                log("unsupported host version " + version);
            }
        }
        return supportedVersion;
    }

    private static void updateActionState(Object layout, int selectedCount) throws Exception {
        Context context = (Context) callMethod(layout, "getContext");
        if (!supportsHost(context)) {
            return;
        }
        Object panel = callMethod(layout, "getSharePanel");
        boolean gallery = isGalleryTab(layout);
        Object delegate = callMethod(panel, "getFloatDelegate");
        Object source = readField(panel, delegate == null ? "f" : "Y0");
        if (!(source instanceof List) || ((List<?>) source).isEmpty()) {
            return;
        }
        List<?> menus = (List<?>) source;
        Object galleryMenu = findAction(menus, ACTION_GALLERY);
        boolean changed = false;
        if (galleryMenu != null && !gallery) {
            changed = menus.remove(galleryMenu);
            galleryMenu = null;
        }
        changed |= setMenu(panel, layout, context, galleryMenu, ACTION_GALLERY, "在图库中打开", gallery && selectedCount > 0);
        changed |= setMenu(panel, layout, context, findAction(menus, ACTION_FILE_MANAGER), ACTION_FILE_MANAGER,
                "在文件管理器中打开", selectedCount == 1);
        changed |= setMenu(panel, layout, context, findAction(menus, ACTION_RECYCLE), ACTION_RECYCLE,
                "删除…", selectedCount > 0 && context.checkSelfPermission(RECYCLE_PERMISSION) == 0);
        if (changed) {
            callMethod(panel, "t");
        }
    }

    private static Object findAction(List<?> menus, int action) {
        for (Object menu : menus) {
            if (actionId(menu) == action) {
                return menu;
            }
        }
        return null;
    }

    private static boolean setMenu(Object panel, Object layout, Context context, Object menu, int action,
            String title, boolean enabled) {
        if (menu == null) {
            if (!enabled) {
                return false;
            }
            callMethod(panel, "m", createMenu(layout, context, title, action));
            return true;
        }
        if (Boolean.TRUE.equals(readField(menu, "e")) == enabled) {
            return false;
        }
        callMethod(menu, "c", enabled);
        return true;
    }

    private static int actionId(Object menu) {
        Object value = readField(menu, "d");
        if (!(value instanceof Integer)) {
            return 0;
        }
        int action = (Integer) value;
        return action >= ACTION_GALLERY && action <= ACTION_RECYCLE ? action : 0;
    }

    private static void handleAction(Object layout, int action) {
        Context context = (Context) callMethod(layout, "getContext");
        boolean gallery = isGalleryTab(layout);
        List<?> current = currentItems(layout, gallery);
        List<Object> selected = selectedItems(current, gallery);
        if (selected.isEmpty()) {
            toast(context, "请先选择文件");
            return;
        }
        Intent intent;
        if (action == ACTION_GALLERY) {
            intent = gallery ? galleryIntent(context, selected.get(0), current) : null;
        } else if (action == ACTION_FILE_MANAGER) {
            if (selected.size() != 1) {
                toast(context, "请只选择一个文件");
                return;
            }
            intent = fileManagerIntent(context, selected.get(0), gallery);
        } else {
            intent = recycleIntent(context, selected, gallery);
        }
        if (intent == null) {
            toast(context, "当前选项不支持此操作");
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        log("opened action " + action + " selected=" + selected.size());
        try {
            Class<?> helper = XposedHelpers.findClass("b4.q", layout.getClass().getClassLoader());
            helper.getMethod("l", Context.class).invoke(null, context);
        } catch (ReflectiveOperationException | LinkageError error) {
            logError("close panel failed", error);
        }
    }

    private static Intent galleryIntent(Context context, Object first, List<?> current) {
        int id = mediaId(first);
        if (id == 0) {
            return null;
        }
        String mime = (String) readField(first, "f");
        boolean video = Boolean.TRUE.equals(readField(first, "h"));
        Uri collection = video ? android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = android.content.ContentUris.withAppendedId(collection, id);
        Intent intent = new Intent("com.vivo.gallery.ACTION_VIEW");
        intent.setPackage(GALLERY_PACKAGE);
        if (mime == null || mime.isEmpty() || "image/avif".equals(mime)) {
            mime = video ? "video/*" : "image/*";
        }
        intent.setDataAndType(uri, mime);
        intent.putExtra("view_src", "VPortal");
        intent.putExtra("local_file_custom_flag", true);
        ArrayList<Integer> ids = new ArrayList<Integer>();
        int index = -1;
        for (Object item : current) {
            int itemId = mediaId(item);
            if (itemId != 0) {
                if (item == first) {
                    index = ids.size();
                }
                ids.add(itemId);
            }
        }
        if (index < 0 || intent.resolveActivity(context.getPackageManager()) == null) {
            return null;
        }
        intent.putIntegerArrayListExtra("local_file_custom_id_list", ids);
        intent.putExtra("local_file_custom_index", index);
        return intent;
    }

    private static Intent fileManagerIntent(Context context, Object item, boolean gallery) {
        if (!gallery) {
            return fileLocationIntent((String) readField(item, "b"));
        }
        Uri uri = mediaUri(item);
        if (uri == null) {
            return null;
        }
        Intent location = fileLocationIntent(selectedMediaPath(context, uri));
        if (location != null) {
            return location;
        }
        String mime = (String) readField(item, "f");
        return new Intent(Intent.ACTION_VIEW)
                .setClassName(FILE_MANAGER_PACKAGE, FILE_POSITION_ACTIVITY)
                .setDataAndType(uri, mime == null ? "*/*" : mime);
    }

    private static Intent fileLocationIntent(String path) {
        if (path == null || !path.startsWith("/") || path.endsWith("/")) {
            return null;
        }
        String parent = path.substring(0, Math.max(1, path.lastIndexOf('/')));
        Intent intent = new Intent()
                .setClassName(FILE_MANAGER_PACKAGE, FILE_MANAGER_ACTIVITY)
                .setAction("com.android.filemanager.FILE_OPEN");
        intent.putExtra("FilePathToBeOpenAfterScan", parent);
        intent.putExtra("AfterCreateFileToLocationDestFile", path);
        intent.putExtra("is_need_show_highlight", true);
        intent.putExtra("OutsideOpenDir", false);
        return intent;
    }

    private static String selectedMediaPath(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_data"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndexOrThrow("_data"));
            }
        } catch (RuntimeException error) {
            logError("media path unavailable", error);
        }
        return null;
    }

    private static Intent recycleIntent(Context context, List<Object> items, boolean gallery) {
        if (context.checkSelfPermission(RECYCLE_PERMISSION) != 0) {
            return null;
        }
        Intent intent = new Intent(INTENT_RECYCLE);
        intent.setClassName(FILE_MANAGER_PACKAGE, RECYCLE_ACTIVITY);
        intent.putExtra("from", TARGET_PACKAGE);
        if (gallery) {
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            for (Object item : items) {
                int mediaId = mediaId(item);
                if (mediaId == 0 || mediaUri(item) == null) {
                    return null;
                }
                ids.add(mediaId);
            }
            intent.putExtra("del_is_path", false);
            intent.putIntegerArrayListExtra("del_media_ids", new ArrayList<>(ids));
        } else {
            String sharedStorage = Environment.getExternalStorageDirectory().getAbsolutePath() + "/";
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (Object item : items) {
                String path = (String) readField(item, "b");
                if (path == null || !path.startsWith(sharedStorage) || path.contains("/../")
                        || path.endsWith("/..") || path.startsWith(sharedStorage + "Android/")
                        || path.length() == sharedStorage.length()) {
                    return null;
                }
                paths.add(path);
            }
            intent.putExtra("del_is_path", true);
            intent.putStringArrayListExtra("del_paths", new ArrayList<>(paths));
        }
        return intent;
    }

    private static List<?> currentItems(Object layout, boolean gallery) {
        Object page = gallery ? callMethod(layout, "getGalleryPage") : currentFileTab(layout);
        if (page == null) {
            return Collections.emptyList();
        }
        Object list = callMethod(page, gallery ? "l" : "h", false);
        return list instanceof List ? (List<?>) list : Collections.emptyList();
    }

    private static List<Object> selectedItems(List<?> current, boolean gallery) {
        String itemClass = gallery ? MEDIA_ITEM : FILE_ITEM;
        String selectedField = gallery ? "k" : "h";
        List<Object> result = new ArrayList<>();
        for (Object item : current) {
            if (item != null && itemClass.equals(item.getClass().getName())
                    && Boolean.TRUE.equals(readField(item, selectedField))) {
                result.add(item);
            }
        }
        return result;
    }

    private static Object currentFileTab(Object layout) {
        Object filePage = callMethod(layout, "getFilePage");
        if (filePage == null) {
            return null;
        }
        Object tabs = callMethod(filePage, "getTabs");
        Object pager = callMethod(filePage, "getViewPager2");
        if (!(tabs instanceof List) || pager == null) {
            return null;
        }
        int index = (Integer) callMethod(pager, "getCurrentItem");
        List<?> list = (List<?>) tabs;
        if (index < 0 || index >= list.size()) {
            return null;
        }
        return list.get(index);
    }

    private static boolean isGalleryTab(Object layout) {
        return Boolean.TRUE.equals(callMethod(layout, "r"));
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private static Object callMethod(Object target, String name, Object... args) {
        return XposedHelpers.callMethod(target, name, args);
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable error) {
                return null;
            }
        }
        return null;
    }

    private static void writeField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable error) {
            logError("write field failed: " + name, error);
        }
    }

    private static int mediaId(Object item) {
        if (item == null || !MEDIA_ITEM.equals(item.getClass().getName())) {
            return 0;
        }
        Object value = readField(item, "b");
        long id = value instanceof Long ? (Long) value : 0L;
        return id > 0 && id <= Integer.MAX_VALUE ? (int) id : 0;
    }

    private static Uri mediaUri(Object item) {
        Object value = readField(item, "e");
        if (!(value instanceof Uri)) {
            return null;
        }
        Uri uri = (Uri) value;
        return "content".equals(uri.getScheme()) && "media".equals(uri.getAuthority()) ? uri : null;
    }
}
