package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

/** Real 41.2 download entry points converge below both long-press and share-panel rows. */
public final class Host412SharedDownloadContractTest {
    private static final String ACTION = "LX/0xqk;";
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static final String SHARE = "Lcom/ss/android/ugc/aweme/sharer/ui/SharePackage;";
    private static final String SHEET = "Lcom/ss/android/ugc/aweme/sharer/ui/SheetAction;";
    private static final String FACTORY = "Lcom/ss/android/ugc/aweme/share/ShareActionImpl;";
    private static final String EXT = "Lcom/ss/android/ugc/aweme/share/ShareExtServiceImpl;";
    private static final String SOCIAL = "Lcom/ss/android/ugc/aweme/share/socialpanel/adapter/SocialActionsAdapter;";
    private static final String COMMON_PANEL = "Lcom/ss/android/ugc/aweme/shareplatform/ui/CommonShareDialogPanel;";
    private static final String SOCIAL_PANEL = "Lcom/ss/android/ugc/aweme/share/socialpanel/dialog/SocialActionsPanel;";
    private static final String PANEL_MODEL = "Lcom/ss/android/ugc/aweme/sharer/panelmodel/base/PanelModel;";
    private static final String CONSTRUCTOR = ACTION + "-><init>(Landroid/app/Activity;" + AWEME
            + "Ljava/lang/String;ZLjava/lang/String;ILX/0xX9;LX/0xqw;ZI)V";
    private static final Map<String, ClassDef> TYPES = new HashMap<>();

    @BeforeClass public static void loadOriginalContract() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue("Original host APK required", path != null && new File(path).isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(new File(path)));
        Set<String> needed = new HashSet<>(Arrays.asList(ACTION, FACTORY, EXT, SOCIAL,
                COMMON_PANEL, SOCIAL_PANEL, PANEL_MODEL, "LX/0xWh;", "LX/0xcF;"));
        try (ZipFile zip = new ZipFile(path)) {
            var entries = zip.entries();
            while (entries.hasMoreElements() && !needed.isEmpty()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (var input = new BufferedInputStream(zip.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef type : dex.getClasses()) if (needed.remove(type.getType()))
                        TYPES.put(type.getType(), ImmutableClassDef.of(type));
                }
            }
        }
        assertTrue("Missing download entry contract " + needed, needed.isEmpty());
    }

    @AfterClass public static void clear() { TYPES.clear(); }

    @Test public void downloadActionHasExactCurrentWorkAndExecutionBoundary() {
        ClassDef action = TYPES.get(ACTION);
        assertTrue(action.getInterfaces().contains(SHEET));
        assertTrue(java.util.stream.StreamSupport.stream(action.getFields().spliterator(), false)
                .anyMatch(field -> "b".equals(field.getName()) && AWEME.equals(field.getType())));
        Method execute = method(ACTION, "execute");
        assertEquals(List.of("Landroid/content/Context;", SHARE), execute.getParameterTypes().stream()
                .map(CharSequence::toString).toList());
        assertEquals("V", execute.getReturnType());
        assertTrue(fieldRefs(execute).contains(ACTION + "->b:" + AWEME));
        assertTrue("Capture must happen before the native permission route",
                methodRefs(execute).contains("Lcom/ss/android/ugc/aweme/privacy/service/IConsumerPermissionService;->LIZJ("
                        + AWEME + ")LX/0xc8;"));
        assertTrue(fieldRefs(method(ACTION, "<clinit>")).contains(
                "Lcom/ss/android/ugc/aweme/sharePlatformApi/ChannelKey;->download:Lcom/ss/android/ugc/aweme/sharePlatformApi/ChannelKey;"));
        assertTrue(fieldRefs(method(ACTION, "key")).contains(ACTION + "->r:Ljava/lang/String;"));
    }

    @Test public void longPressFactoryShareExtensionAndSocialPanelCreateTheSameAction() {
        assertTrue(methodRefs(method(FACTORY, "LJJIIZI")).contains(CONSTRUCTOR));
        assertTrue(methodRefs(method(EXT, "LJIIL")).contains(CONSTRUCTOR));
        assertTrue(methodRefs(method(SOCIAL, "onBindViewHolder")).contains(CONSTRUCTOR));
    }

    @Test public void newSharePanelsExposeTheWindowAndWorkBindingsUsedByIndependentInsertion() {
        String bottomSheet = "Lcom/google/android/material/bottomsheet/BottomSheetDialog;";
        assertEquals(bottomSheet, TYPES.get(COMMON_PANEL).getSuperclass());
        assertEquals(bottomSheet, TYPES.get(SOCIAL_PANEL).getSuperclass());
        assertField(COMMON_PANEL, "g", PANEL_MODEL);
        assertField(PANEL_MODEL, "m", "LX/0xWh;");
        assertField("LX/0xWh;", "LIZIZ", SHARE);
        assertField(SOCIAL_PANEL, "b", "LX/0xcF;");
        assertField("LX/0xcF;", "LJIIL", SHARE);
        assertField("LX/0xcF;", "LIZ", AWEME);
        assertTrue(methodRefs(method(COMMON_PANEL, "show")).contains(bottomSheet + "->show()V"));
        assertTrue(fieldRefs(method(SOCIAL_PANEL, "onCreate")).contains("LX/0xcF;->LIZ:" + AWEME));
    }

    private static void assertField(String owner, String name, String type) {
        assertTrue("Original 41.2 field missing: " + owner + "->" + name + ":" + type,
                java.util.stream.StreamSupport.stream(TYPES.get(owner).getFields().spliterator(), false)
                        .anyMatch(field -> name.equals(field.getName()) && type.equals(field.getType())));
    }

    @Test public void sharedExecutionIsARequiredRuleFor412DownloadReadiness() throws Exception {
        HostRuleSet rules;
        try (FileInputStream input = new FileInputStream("src/main/assets/" + HostAdaptation.PROFILE_412.rulesAsset)) {
            rules = HostRuleSet.parse(input);
        }
        HostRule shared = rules.getRules().stream().filter(rule -> "download_shared_execute".equals(rule.getId()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing share-panel download execution rule"));
        assertEquals(HostRule.Kind.METHOD, shared.getKind());
        assertEquals(ACTION, shared.getClassDescriptor());
        assertEquals("execute", shared.getMethodName());
        assertEquals("(Landroid/content/Context;" + SHARE + ")V", shared.getMethodDescriptor());
        HostFeatureCatalog.Feature feature = HostFeatureCatalog.getFeatures(HostAdaptation.PROFILE_412).stream()
                .filter(value -> "download".equals(value.id)).findFirst().orElseThrow();
        assertTrue("Long-press-only readiness must not report shared download as installed",
                feature.rules.contains("download_shared_execute"));
        assertEquals(HostAdaptation.PROFILE_412.fingerprint, rules.getFingerprint());
    }

    private static Method method(String owner, String name) {
        Method result = null;
        for (Method candidate : TYPES.get(owner).getMethods()) if (name.equals(candidate.getName())) {
            assertNull("Ambiguous method contract " + owner + "->" + name, result);
            result = candidate;
        }
        assertNotNull("Missing method " + owner + "->" + name, result);
        return result;
    }

    private static Set<String> methodRefs(Method method) {
        Set<String> refs = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference)
                refs.add(((ReferenceInstruction) instruction).getReference().toString());
        return refs;
    }

    private static Set<String> fieldRefs(Method method) {
        Set<String> refs = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions())
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference)
                refs.add(((ReferenceInstruction) instruction).getReference().toString());
        return refs;
    }
}
