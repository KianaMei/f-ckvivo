package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Examines original DEX declarations AND instructions; it never loads host classes. */
public final class UnifiedPlayback412ContractTest {
    @Test public void listenerPanelAutoPlayAndQualityContractsBelongToTheReviewedOriginal() throws Exception {
        Map<String, ClassDef> classes = originalClasses();
        ClassDef listener = classes.get("LX/10fQ;");
        assertTrue(hasField(listener, "a", "Lcom/ss/android/ugc/aweme/feed/panel/IBaseListFragmentPanel;"));
        method(listener, "onPlaying", "(LX/1VWG;)V");
        method(listener, "onPausePlay", "(LX/0zq7;)V");
        method(listener, "onResumePlay", "(LX/0zq4;)V");
        method(listener, "onPlayCompleted", "(LX/0zqE;)V");
        assertTrue(hasField(classes.get("LX/1VZk;"), "LIZ", "Ljava/lang/String;"));
        ClassDef panel = classes.get("Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;");
        method(panel, "LLJL", "()Lcom/ss/android/ugc/aweme/video/IPlayerManager;");
        method(panel, "setCurrentItem", "(IZ)V");
        assertTrue(hasField(panel, "f", "Lcom/ss/android/ugc/aweme/common/widget/VerticalViewPager;"));
        Method fastSpeed = method(classes.get("Lcom/ss/android/ugc/aweme/control/speed/presenter/FeedLongPressFastSpeedPresenter;"), "LLLLLLZ", "(FFF)V");
        assertTrue(strings(fastSpeed).contains("FeedLongPressFastSpeedPresenter#enterFastSpeedMode"));

        ClassDef info = classes.get("Lcom/ss/ttvideoengine/model/VideoInfo;");
        for (String name : new String[]{"mVHeight", "mVHeightVer2", "mMediaType"}) assertTrue(hasField(info, name, "I"));
        for (String name : new String[]{"mCodecType", "mCodecTypeVer2"}) assertTrue(hasField(info, name, "Ljava/lang/String;"));
        method(classes.get("Lcom/ss/ttvideoengine/model/VideoModel;"), "getVideoInfoList", "()Ljava/util/List;");

        Method auto = method(classes.get("Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autoplay/AutoPlayViewModel;"), "aD", "()Z");
        assertFalse((auto.getAccessFlags() & 8) != 0);
        assertTrue(methodReferences(auto).stream().anyMatch(name -> name.endsWith("->getValue()Ljava/lang/Object;")));
        Method padAuto = method(classes.get("Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autonext/AutoNextViewModel;"), "aD", "()Z");
        assertTrue(methodReferences(padAuto).stream().anyMatch(name -> name.endsWith("->getValue()Ljava/lang/Object;")));
    }

    @Test public void pauseAndPlayHaveRealPlayerSemanticsRatherThanSimilarObfuscatedNames() throws Exception {
        Map<String, ClassDef> classes = originalClasses();
        ClassDef wrapper = classes.get("Lcom/ss/android/ugc/aweme/feed/controller/FeedPlayerWrapper;");
        Method pause = method(wrapper, "LJJJJI", "()V");
        assertTrue(methodReferences(pause).contains("Lcom/ss/android/ugc/aweme/o/oplayer/client/player/ability/DPlayAbility;->pause()V"));
        Method other = method(wrapper, "LJJJI", "()V");
        assertFalse(methodReferences(other).contains("Lcom/ss/android/ugc/aweme/o/oplayer/client/player/ability/DPlayAbility;->pause()V"));
        Method play = method(wrapper, "LJJIJIIJI", "(Lcom/ss/android/ugc/aweme/feed/model/Video;Lcom/ss/android/ugc/aweme/player/sdk/api/OnUIPlayListener;ILjava/lang/String;ZLjava/lang/String;ZZII)Ljava/lang/String;");
        assertTrue(strings(play).contains("tryResumePlay"));
    }

    private static Map<String, ClassDef> originalClasses() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("DOUAI_TEST_APK is required", path != null && new File(path).isFile());
        File file = new File(path);
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(file));
        Map<String, ClassDef> result = new HashMap<>();
        Set<String> wanted = new HashSet<>(Set.of("LX/10fQ;", "LX/1VZk;", "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;",
                "Lcom/ss/android/ugc/aweme/control/speed/presenter/FeedLongPressFastSpeedPresenter;",
                "Lcom/ss/ttvideoengine/model/VideoInfo;", "Lcom/ss/ttvideoengine/model/VideoModel;",
                "Lcom/ss/android/ugc/aweme/feed/controller/FeedPlayerWrapper;",
                "Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autoplay/AutoPlayViewModel;",
                "Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autonext/AutoNextViewModel;"));
        try (ZipFile archive = new ZipFile(file)) {
            for (String dex : new String[]{"classes29.dex", "classes39.dex", "classes46.dex"}) {
                try (var input = new BufferedInputStream(archive.getInputStream(archive.getEntry(dex)))) {
                    for (ClassDef owner : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses())
                        if (wanted.remove(owner.getType())) result.put(owner.getType(), owner);
                }
            }
        }
        assertTrue("Missing inspected playback types: " + wanted, wanted.isEmpty());
        return result;
    }
    private static boolean hasField(ClassDef owner, String name, String type) {
        for (var field : owner.getFields()) if (name.equals(field.getName()) && type.equals(field.getType())) return true;
        return false;
    }
    private static Method method(ClassDef owner, String name, String proto) {
        Method match = null;
        for (Method method : owner.getMethods()) {
            String descriptor = "(" + String.join("", method.getParameterTypes()) + ")" + method.getReturnType();
            if (name.equals(method.getName()) && proto.equals(descriptor)) { assertNull(match); match = method; }
        }
        assertNotNull(owner.getType() + "->" + name + proto, match); return match;
    }
    private static Set<String> methodReferences(Method method) {
        Set<String> refs = new HashSet<>();
        for (var instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference)
                refs.add(((ReferenceInstruction) instruction).getReference().toString());
        return refs;
    }
    private static Set<String> strings(Method method) {
        Set<String> refs = new HashSet<>();
        for (var instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof StringReference)
                refs.add(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString());
        return refs;
    }
}
