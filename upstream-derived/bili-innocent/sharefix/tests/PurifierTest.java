/** 纯 JVM 逻辑测试：javac 编译 src + tests 后直接 java 运行，不进 APK。 */
package local.codex.innocent.sharefix;

public class PurifierTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        link("https://www.bilibili.com/video/BV1xx?spm_id=a&from=search&p=2&t=30",
                "https://www.bilibili.com/video/BV1xx?p=2&t=30");
        link("https://b23.tv/abc123?share_source=weixin&share_medium=iphone",
                "https://b23.tv/abc123");
        link("http://www.bilibili.com/video/BV1?start_progress=90000",
                "http://www.bilibili.com/video/BV1?t=90");
        link("https://www.bilibili.com/video/BV1?a=1#part2",
                "https://www.bilibili.com/video/BV1#part2");
        link("https://www.bilibili.com/video/BV1", null);
        link("https://example.com/video?x=1", null);
        link("https://evilbilibili.com/video?spm=x", null);
        link("https://sub.bilibili.tv/watch/9?b=2&p=7",
                "https://sub.bilibili.tv/watch/9?p=7");
        link("not a url at all", null);
        link("https://bili2233.cn/x?P=1", "https://bili2233.cn/x"); // 大写 P 不识别，全删

        content("快看 https://www.bilibili.com/v/BV1?spm=x 这个视频",
                "快看 https://www.bilibili.com/v/BV1 这个视频");
        content("纯文本无链接", null);
        content("外链 https://google.com?q=1 不动", null);

        System.out.println();
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void link(String in, String expect) {
        check("link", in, expect, LinkPurifier.purifyLink(in));
    }

    private static void content(String in, String expect) {
        check("content", in, expect, LinkPurifier.purifyContent(in));
    }

    private static void check(String kind, String in, String expect, String actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            passed++;
            System.out.println("[OK]   " + kind + " " + in);
        } else {
            failed++;
            System.out.println("[FAIL] " + kind + " " + in);
            System.out.println("       expect=" + expect);
            System.out.println("       actual=" + actual);
        }
    }
}
