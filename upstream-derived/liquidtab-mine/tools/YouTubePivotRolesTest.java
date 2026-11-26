package io.github.offlineglass.hook.adapters.youtube;

public final class YouTubePivotRolesTest {
    public static void main(String[] args) {
        expect(YouTubePivotRoles.slot("首页") == 0, "zh-CN Home");
        expect(YouTubePivotRoles.slot("Home") == 0, "en Home");
        expect(YouTubePivotRoles.slot("Shorts") == 1, "Shorts");
        expect(YouTubePivotRoles.slot("Shorts：有新内容") == 1, "Shorts with extra info");
        expect(YouTubePivotRoles.slot("创建") == 2, "zh-CN Create");
        expect(YouTubePivotRoles.slot("上传") == 2, "zh-CN Upload");
        expect(YouTubePivotRoles.slot("订阅内容") == 3, "zh-CN Subscriptions");
        expect(YouTubePivotRoles.slot("订阅内容：有新内容") == 3, "zh-CN Subscriptions with extra info");
        expect(YouTubePivotRoles.slot("Subscriptions, new content") == 3, "en Subscriptions with comma");
        expect(YouTubePivotRoles.slot("我") == 4, "zh-CN You");
        expect(YouTubePivotRoles.slot("我的") == 4, "zh-CN Mine");
        expect(YouTubePivotRoles.slot("媒体库") == 4, "zh-CN Library");
        expect(YouTubePivotRoles.slot(null) == -1, "null safe");
        expect(YouTubePivotRoles.slot("未知标签") == -1, "unknown label");

        expect(YouTubePivotRoles.complete(new boolean[]{true, true, true, true, true}), "5 tabs complete");
        expect(YouTubePivotRoles.complete(new boolean[]{true, true, false, true, true}), "4 tabs without create complete");
        expect(YouTubePivotRoles.complete(new boolean[]{true, true, true, false, true}), "4 tabs without subscriptions complete");
        expect(!YouTubePivotRoles.complete(new boolean[]{false, true, true, true, true}), "missing home incomplete");
        expect(!YouTubePivotRoles.complete(new boolean[]{true, true, true, false, false}), "only 3 tabs incomplete");

        System.out.println("PASS: YouTubePivotRoles");
    }

    private static void expect(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
