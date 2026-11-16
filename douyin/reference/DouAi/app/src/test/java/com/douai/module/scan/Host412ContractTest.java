package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import org.junit.Assume;
import org.junit.Test;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import static org.junit.Assert.*;

/** Static original-APK contracts; does not load or execute any host classes. */
public final class Host412ContractTest {
    private static final String AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;";
    private static final String STATS = "Lcom/ss/android/ugc/aweme/feed/model/AwemeStatistics;";
    private static final String BASE = "Lcom/ss/android/ugc/aweme/feed/long_press_panel/modules/LongPressPanelAbsModule;";
    private static final String DOWNLOAD = "Lcom/ss/android/ugc/aweme/feed/long_press_panel/modules/business/homepage/LppDownloadModule;";

    @Test public void experimentalRulesRemainIndependentAndNeverInheritTheOldUiResourceMap() throws Exception {
        assertSame(HostAdaptation.PROFILE_412, HostAdaptation.forVersion("41.2.0", 410200L));
        assertNull(HostAdaptation.forVersion("41.2.0", 410201L));
        assertNull(HostAdaptation.forVersion("other.application", "41.2.0", 410200L));
        assertTrue(HostAdaptation.restricted(HostAdaptation.PROFILE_412));
        assertFalse(HostAdaptation.restricted(HostAdaptation.PROFILE_410));
        assertTrue(HostAdaptation.PROFILE_412.getFeedResources().isEmpty());
        HostRuleSet rules = rules();
        assertTrue(rules.getRules().size() >= 24);
        assertEquals(HostAdaptation.PROFILE_412.fingerprint, rules.getFingerprint());
        assertNotEquals(HostAdaptation.PROFILE_410.fingerprint, rules.getFingerprint());
    }

    @Test public void exactOriginalApkResolvesAllRestrictedRulesUniquely() throws Exception {
        File apk = original();
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(apk));
        HostScanReport report = new HostScanner().scan(Collections.singletonList(apk), rules());
        for (RuleScanResult rule : report.getRuleResults()) {
            assertEquals(rule.getRuleId(), RuleScanResult.Status.UNIQUE, rule.getStatus());
        }
    }

    @Test public void capabilityDiagnosticsReferenceTheNewImplementationsActualRules() throws Exception {
        Set<String> ids = new HashSet<>();
        for (HostRule rule : rules().getRules()) ids.add(rule.getId());
        for (HostFeatureCatalog.Feature feature : HostFeatureCatalog.getFeatures(HostAdaptation.PROFILE_412)) {
            for (String rule : feature.rules) assertTrue(feature.id + " depends on unmigrated " + rule, ids.contains(rule));
            if ("auto_play".equals(feature.id)) assertTrue(feature.rules.contains("unified412_completed"));
            if ("prevent_skip_hidden".equals(feature.id)) {
                assertTrue(feature.rules.contains("verified412_safety_secondary"));
                assertFalse("The removed legacy delete wrapper is not the new safety-task contract", feature.rules.contains("adv_ui_delete_aweme"));
            }
        }
    }

    @Test public void matchingManifestAloneCannotAuthorizeAnotherApk() throws Exception {
        File apk = original();
        File copiedManifest = Files.createTempFile("douai-412-unverified-", ".apk").toFile();
        try {
            try (ZipFile source = new ZipFile(apk);
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(copiedManifest.toPath()))) {
                output.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                try (var input = source.getInputStream(source.getEntry("AndroidManifest.xml"))) { input.transferTo(output); }
                output.closeEntry();
            }
            HostApkProfile.Identity identity = HostApkProfile.readIdentity(copiedManifest);
            assertEquals("41.2.0", identity.versionName);
            assertEquals(410200L, identity.versionCode);
            assertNull("The unchanged Manifest is insufficient without the reviewed APK hash", HostApkProfile.read(copiedManifest));
        } finally { Files.deleteIfExists(copiedManifest.toPath()); }
    }

    @Test public void downloadGetterReadsCurrentWorkAndNativeClickUsesIt() throws Exception {
        Map<String, ClassDef> classes = selected(original(), BASE, DOWNLOAD, STATS);
        ClassDef parent = classes.get(BASE), child = classes.get(DOWNLOAD);
        assertEquals(BASE, child.getSuperclass());
        Method getter = method(parent, "LJIIJ");
        assertEquals(AWEME, getter.getReturnType());
        assertTrue(fieldRefs(getter).contains(
                "Lcom/ss/android/ugc/aweme/feed/long_press_panel/model/LongPressPanelParams;->a:" + AWEME));
        Method click = method(child, "LJJIJIIJIL");
        assertEquals(Collections.singletonList("Landroid/view/View;"), click.getParameterTypes());
        assertTrue(methodRefs(click).contains(BASE + "->LJIIJ()" + AWEME));
        assertTrue(methodRefs(click).contains("Lcom/ss/android/ugc/aweme/sharer/ui/SheetAction;->execute(Landroid/content/Context;Lcom/ss/android/ugc/aweme/sharer/ui/SharePackage;)V"));
        boolean diggField = false;
        for (var field : classes.get(STATS).getFields()) {
            if (!"d".equals(field.getName()) || !"J".equals(field.getType())) continue;
            for (var annotation : field.getAnnotations()) {
                if (!"Lcom/google/gson/annotations/SerializedName;".equals(annotation.getType())) continue;
                for (var element : annotation.getElements()) {
                    if (element.getValue() instanceof StringEncodedValue
                            && "digg_count".equals(((StringEncodedValue) element.getValue()).getValue())) diggField = true;
                }
            }
        }
        assertTrue("Minimum-like filtering must read the real serialized digg_count field", diggField);
    }

    @Test public void fallbackVideoDurationIsBoundToTheOriginalSerializedField() throws Exception {
        String videoName = "Lcom/ss/android/ugc/aweme/feed/model/Video;";
        ClassDef video = selected(original(), videoName).get(videoName);
        boolean durationFound = false;
        for (var field : video.getFields()) {
            assertFalse("The inspected 41.2 video model has no old literal duration alias",
                    "videoLength".equals(field.getName()) || "duration".equals(field.getName()));
            if (!"x".equals(field.getName()) || !"I".equals(field.getType())) continue;
            for (var annotation : field.getAnnotations()) {
                if (!"Lcom/google/gson/annotations/SerializedName;".equals(annotation.getType())) continue;
                for (var element : annotation.getElements()) {
                    if (element.getValue() instanceof StringEncodedValue
                            && "duration".equals(((StringEncodedValue) element.getValue()).getValue())) durationFound = true;
                }
            }
        }
        assertTrue("The 41.2 fallback must follow SerializedName(duration), not the old field name", durationFound);
    }

    private static File original() {
        String configured = System.getenv("DOUAI_TEST_APK");
        File file = configured == null ? null : new File(configured);
        Assume.assumeTrue("DOUAI_TEST_APK is required for original 41.2 contracts", file != null && file.isFile());
        return file;
    }
    private static HostRuleSet rules() throws Exception {
        File file = new File("src/main/assets/" + HostAdaptation.PROFILE_412.rulesAsset);
        if (!file.isFile()) file = new File("app/src/main/assets/" + HostAdaptation.PROFILE_412.rulesAsset);
        try (var input = new FileInputStream(file)) { return HostRuleSet.parse(input); }
    }
    private static Map<String, ClassDef> selected(File apk, String... owners) throws Exception {
        Set<String> remaining = new HashSet<>(Arrays.asList(owners));
        Map<String, ClassDef> result = new HashMap<>();
        try (ZipFile archive = new ZipFile(apk)) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && !remaining.isEmpty()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (var input = new BufferedInputStream(archive.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef owner : dex.getClasses()) if (remaining.remove(owner.getType())) result.put(owner.getType(), owner);
                }
            }
        }
        assertTrue("Required original host classes missing: " + remaining, remaining.isEmpty());
        return result;
    }
    private static Method method(ClassDef owner, String name) {
        for (Method method : owner.getMethods()) if (name.equals(method.getName())) return method;
        throw new AssertionError("Missing " + owner.getType() + "->" + name);
    }
    private static Set<String> fieldRefs(Method method) {
        Set<String> refs = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof FieldReference) {
                refs.add(((ReferenceInstruction) instruction).getReference().toString());
            }
        }
        return refs;
    }
    private static Set<String> methodRefs(Method method) {
        Set<String> refs = new HashSet<>();
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            if (instruction instanceof ReferenceInstruction && ((ReferenceInstruction) instruction).getReference() instanceof MethodReference) {
                refs.add(((ReferenceInstruction) instruction).getReference().toString());
            }
        }
        return refs;
    }
}
