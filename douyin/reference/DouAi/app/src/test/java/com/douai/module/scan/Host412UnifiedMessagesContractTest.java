package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Original DEX contracts for the migrated audio, read/typing, menu and serialized media roles. */
public final class Host412UnifiedMessagesContractTest {
    private static final Map<String, ClassDef> TYPES = new HashMap<>();
    private static final String MESSAGE = "Lcom/bytedance/im/core/model/Message;";
    @BeforeClass public static void load() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue(path != null && new File(path).isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(new File(path)));
        Set<String> wanted = new HashSet<>(Arrays.asList("LX/1bIz;", "LX/1Mv8;", "LX/1MaJ;", "LX/1Mmz;", "LX/1Mau;", "LX/1Mar;", "LX/1MT3;", "LX/1NPg;",
                "Lcom/ss/android/ugc/aweme/im/business/chat/feature/audio/logic/AudioLogic;", "LY/AsS3277S0100000_45;",
                "Lryx/AcS848S0300000_33;", "Lryx/Av1S4802S0100000_45;",
                "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/common/content/root/voice/VoiceCellInteract;",
                "Lcom/ss/android/ugc/aweme/im/business/chat/feature/input/drawer/new/InputDrawerUI2;",
                "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/longclick/LongClickWindowManagerNew;",
                "Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;", "Lcom/ss/android/ugc/aweme/main/MainActivity;",
                "Lcom/ss/android/ugc/aweme/feed/adapter/FeedImageViewHolder;",
                "Lcom/ss/android/ugc/aweme/comment/manager/longclickaction/actions/SaveImageActionItem;",
                "Lcom/ss/android/ugc/aweme/search/op/standard/AbstractSearchRequest;", "LX/0iO4;", "LX/0ixv;", "LX/0iAr;",
                "Lcom/ss/android/ugc/aweme/discover/api/SearchApi$RealApi;",
                "Lcom/ss/android/ugc/aweme/emoji/model/Emoji;", "Lcom/bytedance/im/core/model/Message;",
                "Lcom/ss/android/ugc/aweme/relation/view/FollowUserButton;",
                "Lcom/ss/android/ugc/aweme/comment/adapter/vh/components/TimeOrRetryComponent;",
                "Lcom/ss/android/ugc/aweme/feed/model/Video;", "Lcom/ss/android/ugc/aweme/feed/model/AwemeStatistics;"));
        try (ZipFile archive = new ZipFile(path)) {
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements() && !wanted.isEmpty()) {
                ZipEntry entry = entries.nextElement(); if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (InputStream input = new BufferedInputStream(archive.getInputStream(entry))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses()) {
                        if (wanted.remove(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                    }
                }
            }
        }
        assertTrue("Missing message contracts " + wanted, wanted.isEmpty());
    }
    @AfterClass public static void clear() { TYPES.clear(); }

    @Test public void readTargetsActuallyCreateMarkReadRequestsAndTypingCreatesInputTypingContent() {
        for (String name : new String[]{"p3", "q3"}) {
            assertTrue(refs(method("LX/1bIz;", name)).stream().anyMatch(r -> r.contains("MarkConversationReadRequestBody$Builder;-><init>")));
        }
        assertTrue(refs(method("LX/1Mv8;", "handleMsg")).stream().anyMatch(r -> r.contains("InputTypingContent;-><init>")));
    }
    @Test public void recordModelUsesFileBoxedDurationAndWaveAndCallbackComesFromAudioLogic() {
        field("LX/1MaJ;", "LIZ", "Ljava/io/File;"); field("LX/1MaJ;", "LIZIZ", "Ljava/lang/Long;"); field("LX/1MaJ;", "LIZJ", "Ljava/util/List;");
        field("LX/1Mmz;", "RECORD_COMPLETE", "LX/1Mmz;");
        assertTrue(refs(method("Lcom/ss/android/ugc/aweme/im/business/chat/feature/audio/logic/AudioLogic;", "onCreate")).contains("LY/AsS3277S0100000_45;-><init>(Ljava/lang/Object;I)V"));
        assertTrue(refs(method("LY/AsS3277S0100000_45;", "LIZ")).contains("LX/1MaJ;->LIZIZ:Ljava/lang/Long;"));
    }
    @Test public void uploaderIsCachedThroughTheFactoryParentRatherThanTheBranchConstructor() {
        field("LX/1Mau;", "LJFF", "Lzoz/Ay5S2066S0000000_45;");
        assertTrue(refs(method("LX/1Mar;", "LIZIZ")).contains("LX/1Mar;->LIZ:Ljava/lang/Object;"));
        assertTrue(refs(method("LX/1Mar;", "LIZIZ")).contains("LX/1Mar;->LIZ()Ljava/lang/Object;"));
    }
    @Test public void messageMenuRetainsTheSelectedMessageAndTypedGroupWithoutResourceNumbers() {
        String owner = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/longclick/LongClickWindowManagerNew;";
        field(owner, "currentMessage", MESSAGE);
        field("LX/1MT3;", "LIZ", "Lcom/ss/android/ugc/aweme/im/business/readedpage/hades/view/ImAutoLayerTabLayout;");
        field("LX/1MT3;", "LJII", "I");
        assertTrue(refs(method(owner, "LIZ")).contains(owner + "->LJI()LX/1MT3;"));
    }
    @Test public void audioForwardingUsesOnlyTheVoiceLeafAndPreservesTheBasePolicy() {
        String voice = "Lcom/ss/android/ugc/aweme/im/business/chat/msgcell/common/content/root/voice/VoiceCellInteract;";
        assertTrue(refs(method(voice, "LLD")).stream().anyMatch(r -> r.contains("Lryx/AcS848S0300000_33;-><init>")));
        assertEquals("LX/1NPg;", TYPES.get("Lryx/AcS848S0300000_33;").getSuperclass());
        field("Lryx/AcS848S0300000_33;", "l1", "Ljava/lang/Object;");
        field("Lryx/AcS848S0300000_33;", "l2", "Ljava/lang/Object;");
        assertNotNull(method("LX/1NPg;", "LJIJJLI"));
    }
    @Test public void videoAndDiggJsonAnnotationsCarryTheSemanticFieldsAtRuntime() {
        assertEquals("play_addr", serialized(field("Lcom/ss/android/ugc/aweme/feed/model/Video;", "a", "Lcom/ss/android/ugc/aweme/feed/model/VideoUrlModel;")));
        assertEquals("duration", serialized(field("Lcom/ss/android/ugc/aweme/feed/model/Video;", "x", "I")));
        assertEquals("digg_count", serialized(field("Lcom/ss/android/ugc/aweme/feed/model/AwemeStatistics;", "d", "J")));
    }
    @Test public void portableUiUsesTheActualRootGetterAndMainActivityOwnsItsGestureDispatch() {
        assertTrue(refs(method("Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;", "LJIIJ"))
                .contains("Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;->z:Landroid/view/View;"));
        assertEquals("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", method("Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;", "getAweme").getReturnType());
        assertEquals("Z", method("Lcom/ss/android/ugc/aweme/main/MainActivity;", "dispatchTouchEvent").getReturnType());
        field("Lcom/ss/android/ugc/aweme/feed/adapter/FeedImageViewHolder;", "f", "Landroid/widget/FrameLayout;");
        assertTrue(strings(method("Lcom/ss/android/ugc/aweme/feed/adapter/FeedImageViewHolder;", "<init>")).contains("field 'mRootView'"));
    }
    @Test public void nativeCommentSaveClickDelegatesToTheRealAlbumPermissionAndSavePath() {
        String owner = "Lcom/ss/android/ugc/aweme/comment/manager/longclickaction/actions/SaveImageActionItem;";
        assertTrue(refs(method(owner, "LJJI")).contains(owner + "->LJJIIJZLJL(I)V"));
        Set<String> evidence = strings(method(owner, "LJJIIJZLJL"));
        assertTrue(evidence.contains("save_image_comment"));
        assertTrue(evidence.contains("bpea-comment_save_image_to_album"));
    }
    @Test public void searchUsesTheActualVideoPostAndOfficialParameterAssembler() {
        Method search = method("Lcom/ss/android/ugc/aweme/discover/api/SearchApi$RealApi;", "LJJIIJZLJL");
        assertEquals("Lio/reactivex/Observable;", search.getReturnType());
        String path = "";
        for (Annotation a : search.getAnnotations()) if (a.getType().equals("Lretrofit2/http/POST;")) for (AnnotationElement e : a.getElements()) if (e.getValue() instanceof StringEncodedValue) path = ((StringEncodedValue)e.getValue()).getValue();
        assertEquals("/aweme/v1/search/item/", path);
        assertTrue(search.getParameters().get(0).getAnnotations().stream().anyMatch(a -> a.getType().equals("Lretrofit2/http/FieldMap;")));
        assertTrue(strings(method("LX/0ixv;", "<init>")).contains("search"));
        Set<String> assemble = refs(method("LX/0iO4;", "LIZIZ"));
        assertTrue(assemble.contains("Lcom/ss/android/ugc/aweme/search/op/standard/AbstractSearchRequest;->LJJIIZ()Ljava/util/Map;"));
        assertTrue(assemble.contains("Lcom/ss/android/ugc/aweme/search/op/standard/AbstractSearchRequest;->LJ()Ljava/util/Map;"));
    }
    @Test public void searchNormalizesTheVideoResultsRatherThanChoosingAnArbitraryList() {
        Set<String> normalize = refs(method("LX/0iAr;", "LJLLL"));
        assertTrue(normalize.contains("LX/0iAr;->awemeFeeds:Ljava/util/List;"));
        assertTrue(normalize.contains("Lcom/ss/android/ugc/aweme/newfollow/model/BaseFlowFeed;->getAweme()Lcom/ss/android/ugc/aweme/feed/model/Aweme;"));
    }
    @Test public void interactionTargetsReadOnlyTheProvenVisibilityStatusAndCreatedAtRoles() {
        assertTrue(refs(method("Lcom/ss/android/ugc/aweme/emoji/model/Emoji;", "LJIJ"))
                .contains("Lcom/ss/android/ugc/aweme/emoji/model/Emoji;->hide:Ljava/lang/Boolean;"));
        assertTrue(refs(method("Lcom/ss/android/ugc/aweme/relation/view/FollowUserButton;", "getMCurrentStatus"))
                .contains("Lcom/ss/android/ugc/aweme/relation/view/FollowUserButton;->a:I"));
        assertTrue(refs(method(MESSAGE, "getCreatedAt")).contains(MESSAGE + "->createdAt:J"));
    }
    @Test public void actualCommentTimeComponentsUseTheScopedMillisecondFormatter() {
        boolean actual = false;
        for (Method method : TYPES.get("Lcom/ss/android/ugc/aweme/comment/adapter/vh/components/TimeOrRetryComponent;").getMethods()) {
            if (method.getImplementation() == null) continue;
            Set<String> calls = refs(method);
            if (calls.contains("Lcom/ss/android/ugc/aweme/utils/TimeUtils;->LIZ(JLandroid/content/Context;)Ljava/lang/String;")
                    && calls.contains("Lcom/ss/android/ugc/aweme/comment/model/Comment;->getCreateTime()J")) actual = true;
        }
        assertTrue(actual);
    }
    private static Field field(String owner, String name, String type) { for (Field f : TYPES.get(owner).getFields()) if (f.getName().equals(name) && f.getType().equals(type)) return f; throw new AssertionError(owner + "." + name); }
    private static Method method(String owner, String name) { for (Method m : TYPES.get(owner).getMethods()) if (m.getName().equals(name)) return m; throw new AssertionError(owner + "." + name); }
    private static Set<String> refs(Method method) { Set<String> result = new HashSet<>(); for (Instruction i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction) result.add(((ReferenceInstruction)i).getReference().toString()); return result; }
    private static Set<String> strings(Method method) { Set<String> result = new HashSet<>(); for (Instruction i : method.getImplementation().getInstructions()) if (i instanceof ReferenceInstruction && ((ReferenceInstruction)i).getReference() instanceof StringReference) result.add(((StringReference)((ReferenceInstruction)i).getReference()).getString()); return result; }
    private static String serialized(Field field) { for (Annotation a : field.getAnnotations()) if (a.getVisibility() == 1 && a.getType().equals("Lcom/google/gson/annotations/SerializedName;")) for (AnnotationElement e : a.getElements()) if (e.getName().equals("value") && e.getValue() instanceof StringEncodedValue) return ((StringEncodedValue)e.getValue()).getValue(); throw new AssertionError("Missing runtime serialized role " + field); }
}
