package com.douai.module.scan;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** Original DEX role contracts for the 41.2 comment publisher, TTS, notice inbox and thread lookup. */
public final class Host412CommentsContractTest {
    private static final String COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;";
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static final String REQUEST = "Lcom/ss/android/ugc/aweme/comment/data/publish/CommentPublishRequest;";
    private static final String NOTICE = "Lcom/ss/android/ugc/aweme/socialnotice/bean/BaseNotice;";
    private static final String STRING = "Ljava/lang/String;";
    private static final Map<String, ClassDef> TYPES = new HashMap<>();
    @BeforeClass public static void loadSelectedOriginalDefinitions() throws Exception {
        String configured = System.getenv("DOUAI_TEST_APK");
        File apk = configured == null ? null : new File(configured);
        Assume.assumeTrue(apk != null && apk.isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(apk));
        Set<String> names = new HashSet<>(Arrays.asList(REQUEST, COMMENT,
                "LX/12uf;", "LX/12vJ;", "LX/12uh;", "LX/12wU;", "LX/12p7;", "LX/11cF;", "LX/12vp;",
                "LY/AObjectS307S0200000_36;", "LX/12lc;", "LX/12wj;", "LX/0r4K;", "LX/1hx9;", "LX/1hx8;",
                "LX/1ZLH;", "LX/13N0;", "LX/13K2;", "LX/13KC;", "LX/12lx;", "LX/12m0;", "LX/135g;",
                "Lcom/ss/android/ugc/aweme/comment/keyboard/refactor/BaseCmtInputModule;", "LX/12p0;", "LX/12y4;"));
        try (ZipFile archive = new ZipFile(apk)) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && !names.isEmpty()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (var input = new BufferedInputStream(archive.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) if (names.remove(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                }
            }
        }
        assertTrue("Missing selected comment contracts: " + names, names.isEmpty());
    }
    @AfterClass public static void clear() { TYPES.clear(); }

    @Test public void publisherFieldsRetainTextReplyContextAndAudioRolesAcrossInheritance() {
        assertEquals("LX/12vJ;", TYPES.get("LX/12uf;").getSuperclass());
        field("LX/12vJ;", "a", STRING); field("LX/12vJ;", "b", STRING); field("LX/12vJ;", "e", STRING);
        field("LX/12uf;", "p", COMMENT); field("LX/12uf;", "y", "LX/12p7;"); field("LX/12uf;", "A", "LX/12uh;");
        field("LX/12uh;", "audio", "LX/12wU;"); field("LX/12uh;", "text", "Landroid/text/Editable;");
        field("LX/12wU;", "localFile", "Ljava/io/File;"); field("LX/12wU;", "duration", "Ljava/lang/Long;");
        method("LX/12wU;", "<init>", "V", "Ljava/io/File;", "Ljava/util/List;", "Ljava/lang/Long;");
        method("LX/12uf;", "LIZ", "LX/12uf;"); method("LX/12uh;", "LJFF", "LX/12uh;");
    }

    @Test public void capturedWorkIsStoredInTheContextAndResolvedFromThatSameDataSlot() {
        Method factory = method("LX/11cF;", "LIZ", "LX/12p7;", AWEME);
        assertTrue(AccessFlags.STATIC.isSet(factory.getAccessFlags()));
        assertTrue(refs(factory).contains("LX/12p7;->LJIILLIIL(Ljava/lang/Object;)V"));
        assertTrue(refs(method("LX/12p7;", "LJIILLIIL", "V", "Ljava/lang/Object;")).contains("LX/12p7;->groupData:Ljava/lang/Object;"));
        assertTrue(refs(method("LX/12vp;", "LIZJ", AWEME, "LX/12p7;")).contains("LX/12p7;->LJIIIIZZ()Ljava/lang/Object;"));
        assertTrue(refs(method("LX/12p7;", "LJIIIIZZ", "Ljava/lang/Object;")).contains("LX/12p7;->groupData:Ljava/lang/Object;"));
        field("Lcom/ss/android/ugc/aweme/comment/keyboard/refactor/BaseCmtInputModule;", "e", "LX/12y4;");
        field("LX/12p0;", "e", "LX/12p7;");
        assertTrue(refs(method("LX/12y4;", "LJIIIIZZ", COMMENT)).contains("LX/12tl;->LJIILLIIL()" + COMMENT));
        field(COMMENT, "cid", STRING);
    }

    @Test public void checkedSubmitStartsTheRealAsyncUploaderWithOwnedModelAndRequestCallback() {
        Method checked = method(REQUEST, "LJIILLIIL", "Z", "LX/12uf;");
        assertTrue(refs(checked).contains("Lcom/ss/android/ugc/aweme/comment/helper/AudioUploader;->LJIIIIZZ(LX/12ug;Ljava/lang/String;LY/AObjectS307S0200000_36;LY/AObjectS820S0100000_36;)V"));
        Method callback = method("LY/AObjectS307S0200000_36;", "<init>", "V", "Ljava/lang/Object;", "Ljava/lang/Object;", "I");
        Map<String, Integer> stores = new HashMap<>();
        for (Instruction i : callback.getImplementation().getInstructions()) {
            if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof FieldReference && i instanceof TwoRegisterInstruction) {
                stores.put(((FieldReference) ((ReferenceInstruction) i).getReference()).getName(), ((TwoRegisterInstruction) i).getRegisterA());
            }
        }
        int base = callback.getImplementation().getRegisterCount() - 4;
        assertEquals(Integer.valueOf(base + 1), stores.get("l0")); assertEquals(Integer.valueOf(base + 2), stores.get("l1"));
        assertTrue(refs(method(REQUEST, "LJIJJLI", "V", "LX/12uf;")).contains(REQUEST + "->LJIILLIIL(LX/12uf;)Z"));
        assertTrue(refs(method(REQUEST, "LJIJI", "V", "LX/12uo;", "LX/12uf;")).stream().anyMatch(r -> r.contains("comment")));
    }

    @Test public void noticeReadQueriesAndProvidersRemainReadOnlyAndKeepExactQuerySlots() {
        Method notice = named("LX/1hx9;", "fetchNotice");
        assertEquals("https://aweme.snssdk.com/aweme/v1/notice/", annotation(notice, "Lretrofit2/http/GET;"));
        List<String> expected = Arrays.asList("max_time", "min_time", "count", "notice_group", "top_group", "is_mark_read", "address_book_access", "gps_access", "is_new_notice", "user_avatar_shrink", "video_cover_shrink", "pin_notice_id", "sub_category", "scene", "interactive_disable_style", "top_notice_ids", "notice_unread_count", "current_unread_count", "del_notice_id", "preload", "enable_official_disable", "notice_type", "enable_type_filter");
        assertEquals(expected.size(), notice.getParameters().size());
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), annotation(notice.getParameters().get(i).getAnnotations(), "Lretrofit2/http/Query;"));
        assertTrue(AccessFlags.STATIC.isSet(field("LX/1ZLH;", "b", "LX/1hx9;").getAccessFlags()));
        Method own = named("LX/1hx8;", "getMineComment");
        assertEquals(2, own.getParameterTypes().size());
        assertTrue(annotation(own, "Lretrofit2/http/GET;").contains("comment"));
        assertTrue(AccessFlags.STATIC.isSet(field("LX/13N0;", "b", "LX/1hx8;").getAccessFlags()));
        assertTrue(strings(method("LX/0r4K;", "LJIIIIZZ", "I")).contains("2"));
    }

    @Test public void inboxUsesTheActualGeneralNoticePayloadAndReplyDetailModels() {
        assertTrue(refs(method("LX/13K2;", "LIZ", "LX/13KC;", NOTICE)).stream().anyMatch(r -> r.contains("interactiveGeneralNoticeStruct")));
        assertTrue(refs(method("LX/13K2;", "LIZJ", COMMENT, NOTICE)).contains("LX/13K2;->LIZ(Lcom/ss/android/ugc/aweme/socialnotice/bean/BaseNotice;)LX/13KC;"));
        field("LX/13KC;", "a", COMMENT); field("LX/13KC;", "b", "I"); field("LX/13KC;", "e", COMMENT);
        assertTrue(refs(method(COMMENT, "LLLLLZIL", "Lcom/ss/android/ugc/aweme/profile/model/User;")).stream().anyMatch(r -> r.contains("commentUser")));
    }

    @Test public void replyRootAndDirectResolversReadTheModelSlotsThePublisherWrites() {
        assertTrue(refs(method("LX/12lc;", "LJ", STRING, COMMENT, "LX/12uf;")).contains("LX/12vJ;->b:Ljava/lang/String;"));
        assertTrue(refs(method("LX/12lc;", "LJFF", STRING, COMMENT, "LX/12uf;")).contains("LX/12vJ;->e:Ljava/lang/String;"));
    }

    @Test public void threadRequestCopiesCursorRootCountAndRecipientFromTheBuilder() {
        Method constructor = method("LX/12lx;", "<init>", "V", "LX/12m0;");
        Set<String> refs = refs(constructor);
        for (String name : new String[]{"LIZ", "LJII", "LJIIIZ", "LJIIJJI", "LJIIJ", "LJJII", "LJJIII"}) {
            assertTrue("Constructor must read builder role " + name, refs.stream().anyMatch(r -> r.startsWith("LX/12m0;->" + name + ":")));
        }
        field("LX/12m0;", "LJIIIZ", "Ljava/lang/Long;"); field("LX/12m0;", "LJIIJJI", "Ljava/lang/Integer;");
        field("LX/12m0;", "LJJII", COMMENT); field("LX/12m0;", "LIZ", "LX/12p7;");
        Method fetch = method("LX/135g;", "LJIJJ", "Lbolts/Task;", "LX/12lx;");
        assertTrue(AccessFlags.STATIC.isSet(fetch.getAccessFlags()));
        assertTrue(refs(fetch).stream().anyMatch(r -> r.startsWith("Lcom/ss/android/ugc/aweme/comment/api/CommentRealApi;->")));
    }

    private static Field field(String owner, String name, String type) {
        for (Field f : TYPES.get(owner).getFields()) if (name.equals(f.getName()) && type.equals(f.getType())) return f;
        throw new AssertionError("Missing field " + owner + "->" + name + ":" + type);
    }
    private static Method named(String owner, String name) { for (Method m : TYPES.get(owner).getMethods()) if (name.equals(m.getName())) return m; throw new AssertionError(name); }
    private static Method method(String owner, String name, String returned, String... params) {
        for (Method m : TYPES.get(owner).getMethods()) {
            List<String> actual = new ArrayList<>();
            for (CharSequence item : m.getParameterTypes()) actual.add(item.toString());
            if (name.equals(m.getName()) && returned.equals(m.getReturnType()) && Arrays.asList(params).equals(actual)) return m;
        }
        throw new AssertionError("Missing method " + owner + "->" + name);
    }
    private static Set<String> refs(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction) result.add(((ReferenceInstruction) i).getReference().toString());
        return result;
    }
    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        for (Instruction i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction && ((ReferenceInstruction) i).getReference() instanceof StringReference) result.add(((StringReference) ((ReferenceInstruction) i).getReference()).getString());
        return result;
    }
    private static String annotation(Annotatable owner, String type) {
        return annotation(owner.getAnnotations(), type);
    }
    private static String annotation(Set<? extends Annotation> annotations, String type) {
        for (Annotation a : annotations) if (type.equals(a.getType())) for (AnnotationElement e : a.getElements()) if (e.getValue() instanceof StringEncodedValue) return ((StringEncodedValue)e.getValue()).getValue();
        return "";
    }
}
