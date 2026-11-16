package com.douai.module.feature;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import org.junit.Assume;
import org.junit.Test;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Extra integration targets are checked against actual host instructions, not only reflection shape. */
public final class UnifiedExtrasHostContractTest {
    @Test public void gameSelectorReallySelectsAnEmojiAssetAndFactoryCallsIt() throws Exception {
        Map<String, ClassDef> classes = classes("classes31.dex");
        ClassDef content = classes.get("Lcom/ss/android/ugc/aweme/im/sdk/chat/model/EmojiContent;");
        Method select = method(content, "LJJZ");
        Set<String> calls = calls(select);
        assertTrue(calls.stream().anyMatch(value -> value.contains("kotlin/random/Random;->nextInt(I)I")));
        assertTrue(calls.contains("Ljava/util/List;->get(I)Ljava/lang/Object;"));
        assertEquals("Lcom/ss/android/ugc/aweme/emoji/model/IDUrlModel;", select.getReturnType());
        assertTrue(calls(method(content, "LJJLJLI")).stream().anyMatch(value -> value.contains("EmojiContent;->LJJZ(Ljava/util/List;)")));
    }
    @Test public void fourRainbowBackendsHaveFillAndStrokeRolesAndTheCorrectDrawBackend() throws Exception {
        Map<String, ClassDef> classes = classes("classes42.dex");
        for (String name : new String[]{"1OrF", "1OrG", "1OqQ", "1Oqa"}) {
            ClassDef owner = classes.get("LX/" + name + ";"); assertNotNull(name, owner);
            Set<String> strings = new HashSet<>();
            for (Method method : owner.getMethods()) if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
                if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof StringReference)
                    strings.add(((StringReference) ((ReferenceInstruction) instruction).getReference()).getString());
            }
            assertTrue(name, strings.contains("fillPaint")); assertTrue(name, strings.contains("strokePaint"));
            String canvas = name.startsWith("1Or") ? "Landroid/graphics/Canvas;" : "Lcom/bytedance/skity/graphics/Canvas;";
            assertEquals(java.util.Collections.singletonList(canvas), method(owner, "LJIIIIZZ").getParameterTypes());
        }
    }
    @Test public void pluginControlUsesHostMiraLoaderAndInstalledPackageList() throws Exception {
        ClassDef mira = classes("classes55.dex").get("Lcom/bytedance/mira/Mira;");
        Method load = method(mira, "loadPlugin"), installed = method(mira, "getInstalledPackageNames");
        assertEquals(java.util.Collections.singletonList("Ljava/lang/String;"), load.getParameterTypes());
        assertEquals("Z", load.getReturnType()); assertTrue((load.getAccessFlags() & 8) != 0);
        assertEquals("Ljava/util/List;", installed.getReturnType()); assertTrue(installed.getParameterTypes().isEmpty());
        assertFalse(calls(load).isEmpty());
    }
    private static Set<String> calls(Method method) {
        Set<String> calls = new HashSet<>();
        if (method.getImplementation() != null) for (var instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference)
                calls.add(((ReferenceInstruction) instruction).getReference().toString());
        }
        return calls;
    }
    private static Method method(ClassDef owner, String name) {
        assertNotNull(owner); for (Method method : owner.getMethods()) if (name.equals(method.getName())) return method;
        throw new AssertionError("Missing " + owner.getType() + " " + name);
    }
    private static Map<String, ClassDef> classes(String dexName) throws Exception {
        String file = System.getenv("DOUAI_TEST_APK"); Assume.assumeTrue(file != null && new File(file).isFile());
        try (ZipFile archive = new ZipFile(file); var stream = new BufferedInputStream(archive.getInputStream(archive.getEntry(dexName)))) {
            Map<String, ClassDef> classes = new HashMap<>();
            for (ClassDef owner : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), stream).getClasses()) classes.put(owner.getType(), owner);
            return classes;
        }
    }
}
