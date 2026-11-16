package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
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

/** New symbols are verified by their actual bind/unbind, count and menu behavior. */
public final class UnifiedBasic412ContractTest {
    @Test public void diggBindsTheRealViewModelAndTheOtherIdenticalSignatureUnbinds() throws Exception {
        Map<String, ClassDef> types = original();
        String owner = "Lcom/ss/android/ugc/aweme/feed/quick/component/digg/core/DiggCorePresenter;";
        Method bind = method(types.get(owner), "LLLJ");
        Method unbind = method(types.get(owner), "LLLJIL");
        assertTrue(strings(bind).contains("onDiggBind"));
        assertTrue(strings(unbind).contains("onDiggUnBind"));
        assertFalse(strings(bind).contains("onDiggUnBind"));
        ClassDef base = types.get("Lcom/ss/android/ugc/aweme/feed/quick/component/digg/BaseDiggPresenter;");
        assertEquals("Lcom/ss/android/ugc/aweme/feed/quick/component/digg/DiggViewModel;", method(base, "LLLILZJ").getReturnType());
        Method like = method(types.get("Lcom/ss/android/ugc/aweme/feed/quick/component/digg/DiggViewModel;"), "ZC");
        assertTrue(strings(like).contains("digg for "));
        assertTrue(fields(like).contains("Lcom/ss/android/ugc/aweme/feed/quick/component/digg/DiggViewModel;->b:Lcom/ss/android/ugc/aweme/feed/model/Aweme;"));
    }

    @Test public void ownProfileAndTextViewFieldsCannotConfuseNeighborBooleansOrControllerFields() throws Exception {
        Map<String, ClassDef> types = original();
        ClassDef panda = types.get("Lcom/ss/android/ugc/aweme/profile/panda/core/Panda;");
        Method contextName = method(panda, "LJIILJJIL");
        assertTrue(strings(contextName).contains("personal_homepage"));
        assertTrue(strings(contextName).contains("others_homepage"));
        assertTrue(methods(contextName).contains("Lcom/ss/android/ugc/aweme/profile/panda/core/Panda;->LJJIIJ()Z"));
        ClassDef area = types.get("Lcom/ss/android/ugc/aweme/profile/panda/PandaHeaderValueArea;");
        for (String name : new String[]{"x", "v", "w", "E"}) assertEquals("Landroid/widget/TextView;", field(area, name));
        assertNotEquals("Landroid/widget/TextView;", field(area, "F"));
        ClassDef holder = types.get("Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;");
        assertEquals("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;", field(holder, "A2"));
        assertNotEquals("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;", field(holder, "B2"));
        assertEquals("Landroid/widget/FrameLayout;", field(holder, "w"));
        assertEquals("Landroid/view/View;", field(holder, "z"));
    }

    @Test public void commentMenuParametersAreTheExactKFieldAndContainTheSelectedComment() throws Exception {
        Map<String, ClassDef> types = original();
        assertEquals("LX/12dW;", field(types.get("Lcom/ss/android/ugc/aweme/comment/ui/longpress/CommentLongPressItemModel;"), "k"));
        ClassDef params = types.get("LX/12dW;");
        assertEquals("Landroidx/fragment/app/FragmentActivity;", field(params, "LJFF"));
        assertEquals("Lcom/ss/android/ugc/aweme/comment/model/Comment;", field(params, "LJIIIIZZ"));
        assertEquals("Ljava/lang/String;", field(params, "LJIIIZ"));
    }

    @Test public void shortProgressRulesRetainAdAndDurationSemantics() throws Exception {
        Map<String, ClassDef> types = original();
        for (String name : new String[]{"LX/1EjE;", "LX/1EVS;"}) {
            Method candidate = method(types.get(name), "LIZ");
            assertTrue(methods(candidate).stream().anyMatch(ref -> ref.startsWith("Lcom/ss/android/ugc/aweme/commercialize/utils/AwemeAdRankUtilsKt;->")));
        }
        assertTrue(fields(method(types.get("LX/1EVS;"), "LIZ")).stream().anyMatch(ref -> ref.startsWith("Lcom/ss/android/ugc/aweme/feed/model/Video;->")));
    }

    private static Map<String, ClassDef> original() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK"); Assume.assumeTrue(path != null && new File(path).isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(new File(path)));
        Set<String> wanted = new HashSet<>(Set.of(
                "Lcom/ss/android/ugc/aweme/feed/quick/component/digg/core/DiggCorePresenter;",
                "Lcom/ss/android/ugc/aweme/feed/quick/component/digg/BaseDiggPresenter;",
                "Lcom/ss/android/ugc/aweme/feed/quick/component/digg/DiggViewModel;",
                "Lcom/ss/android/ugc/aweme/profile/panda/core/Panda;",
                "Lcom/ss/android/ugc/aweme/profile/panda/PandaHeaderValueArea;",
                "Lcom/ss/android/ugc/aweme/feed/adapter/VideoViewHolder;",
                "Lcom/ss/android/ugc/aweme/comment/ui/longpress/CommentLongPressItemModel;", "LX/12dW;", "LX/1EjE;", "LX/1EVS;"));
        Map<String, ClassDef> result = new HashMap<>();
        try (ZipFile archive = new ZipFile(path)) {
            for (String dex : new String[]{"classes25.dex", "classes29.dex", "classes31.dex", "classes37.dex"}) {
                try (var input = new BufferedInputStream(archive.getInputStream(archive.getEntry(dex)))) {
                    for (ClassDef type : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input).getClasses())
                        if (wanted.remove(type.getType())) result.put(type.getType(), type);
                }
            }
        }
        assertTrue("Missing original types: " + wanted, wanted.isEmpty()); return result;
    }
    private static String field(ClassDef owner, String name) {
        for (var field : owner.getFields()) if (name.equals(field.getName())) return field.getType();
        throw new AssertionError(owner.getType() + "->" + name);
    }
    private static Method method(ClassDef owner, String name) {
        Method result = null;
        for (Method method : owner.getMethods()) if (name.equals(method.getName())) { assertNull(result); result = method; }
        assertNotNull(owner.getType() + "->" + name, result); return result;
    }
    private static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        for (var insn : method.getImplementation().getInstructions())
            if (insn instanceof ReferenceInstruction && ((ReferenceInstruction) insn).getReference() instanceof StringReference)
                result.add(((StringReference) ((ReferenceInstruction) insn).getReference()).getString());
        return result;
    }
    private static Set<String> methods(Method method) { return refs(method, true); }
    private static Set<String> fields(Method method) { return refs(method, false); }
    private static Set<String> refs(Method method, boolean methods) {
        Set<String> result = new HashSet<>();
        for (var insn : method.getImplementation().getInstructions()) if (insn instanceof ReferenceInstruction) {
            var ref = ((ReferenceInstruction) insn).getReference();
            if (methods ? ref instanceof MethodReference : ref instanceof FieldReference) result.add(ref.toString());
        }
        return result;
    }
}
