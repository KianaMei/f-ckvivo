package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Original 41.2 annotations prove the media selectors read the current model roles. */
public final class Host412MediaActionsContractTest {
    private static final Map<String,ClassDef> TYPES = new HashMap<>();
    private static final String PREFIX = "Lcom/ss/android/ugc/aweme/";
    @BeforeClass public static void load() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue(path != null && new File(path).isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(new File(path)));
        Set<String> needed = new HashSet<>(List.of(PREFIX+"comment/model/Comment;", PREFIX+"comment/model/CommentVideoStruct;",
                PREFIX+"comment/model/CommentAudioStruct;", PREFIX+"profile/model/User;", PREFIX+"feed/model/Video;", PREFIX+"feed/model/BitRate;"));
        try (ZipFile zip = new ZipFile(path)) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && !needed.isEmpty()) {
                var entry = entries.nextElement(); if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (var input = new BufferedInputStream(zip.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) if (needed.remove(type.getType())) TYPES.put(type.getType(), ImmutableClassDef.of(type));
                }
            }
        }
        assertTrue("Missing media models " + needed, needed.isEmpty());
    }
    @AfterClass public static void clear() { TYPES.clear(); }
    @Test public void videoEncodingAndOfficialDownloadRolesMatchOriginalFields() {
        String owner = PREFIX+"feed/model/Video;";
        annotated(owner,"a","play_addr"); annotated(owner,"b","play_addr_265"); annotated(owner,"d","play_addr_h264");
        annotated(owner,"T","play_addr_bytevc1"); annotated(owner,"v","download_addr"); annotated(owner,"z","new_download_addr");
        annotated(owner,"P","is_h265"); annotated(owner,"U","is_bytevc1"); annotated(owner,"y","bit_rate");
        annotated(PREFIX+"feed/model/BitRate;","playAddr","play_addr");
        annotated(PREFIX+"feed/model/BitRate;","playAddr265","play_addr_265");
        annotated(PREFIX+"feed/model/BitRate;","playAddrBytevc1","play_addr_bytevc1");
        annotated(PREFIX+"feed/model/BitRate;","isH265","is_h265");
    }
    @Test public void avatarLargerIsTheSuppliedUrlModel() {
        assertEquals(PREFIX+"base/model/UrlModel;", annotated(PREFIX+"profile/model/User;","avatarLarger","avatar_larger").getType());
    }
    @Test public void commentVideoAndAudioReadActualAliasAndPlaybackContent() {
        byJson(PREFIX+"comment/model/Comment;","video_list"); byJson(PREFIX+"comment/model/Comment;","comment_audio");
        assertEquals(PREFIX+"feed/model/Aweme;", annotated(PREFIX+"comment/model/CommentVideoStruct;","b","alias_aweme").getType());
        assertEquals("Ljava/lang/String;", annotated(PREFIX+"comment/model/CommentAudioStruct;","b","content").getType());
        annotated(PREFIX+"comment/model/CommentAudioStruct;","a","vid");
    }
    private static Field annotated(String owner,String name,String json) {
        Field field = byJson(owner,json); assertEquals(name,field.getName()); return field;
    }
    private static Field byJson(String owner,String json) {
        for(Field field:TYPES.get(owner).getFields())for(Annotation annotation:field.getAnnotations())
            if(annotation.getType().equals("Lcom/google/gson/annotations/SerializedName;"))for(AnnotationElement element:annotation.getElements())
                if(element.getName().equals("value")&&element.getValue() instanceof StringEncodedValue
                        &&json.equals(((StringEncodedValue)element.getValue()).getValue()))return field;
        throw new AssertionError("Missing annotation "+owner+" "+json);
    }
}
