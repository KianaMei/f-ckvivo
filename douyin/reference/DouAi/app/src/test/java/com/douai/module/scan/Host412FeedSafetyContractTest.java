package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import org.junit.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

/** These switches are classified from original code, not lambda names or the previous release. */
public final class Host412FeedSafetyContractTest {
    private static final Map<String, ClassDef> TYPES = new HashMap<>();
    @BeforeClass public static void load() throws Exception {
        String path = System.getenv("DOUAI_TEST_APK");
        Assume.assumeTrue(path != null && new File(path).isFile());
        assertSame(HostAdaptation.PROFILE_412, HostApkProfile.read(new File(path)));
        Set<String> needed = new HashSet<>(Arrays.asList("Lkotlin/jvm/internal/ALambdaS34S1500000_3;",
                "Lkotlin/jvm/internal/ALambdaS157S1300000_3;", "LX/06BY;", "LX/0A8v;",
                "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;"));
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
        assertTrue("Missing safety contracts " + needed, needed.isEmpty());
    }
    @AfterClass public static void clear() { TYPES.clear(); }
    @Test public void primaryTaskBranchesZeroAndOneAreRecallAndSafetyDelete() {
        Method task = method("Lkotlin/jvm/internal/ALambdaS34S1500000_3;", "invoke");
        assertTrue(firstBranchReferences(task, 0, 25).contains("LX/05fm;->LJI(Ljava/lang/String;)V"));
        assertTrue(firstBranchReferences(task, 1, 12).stream().anyMatch(r -> r.startsWith("Lcom/ss/android/ugc/aweme/feed/plato/core/IFeedDataContext$DefaultImpls;->")));
    }
    @Test public void secondaryTaskOnlyBranchOneIsRecallAndItsOtherBranchRemainsUntouched() {
        Method task = method("Lkotlin/jvm/internal/ALambdaS157S1300000_3;", "invoke");
        assertTrue(firstBranchReferences(task, 1, 25).contains("LX/05fm;->LJI(Ljava/lang/String;)V"));
        assertFalse(firstBranchReferences(task, 0, 25).contains("LX/05fm;->LJI(Ljava/lang/String;)V"));
        assertTrue(refs(method("LX/06BY;", "invoke")).stream().anyMatch(r -> r.contains("IFeedDataContext;->LJIIIZ(")));
    }
    @Test public void fourInsertionEntrypointsHaveTheVerifiedWrapperAndAdapterRoles() {
        String owner = "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;";
        assertTrue(refs(method(owner,"LLLLL")).stream().anyMatch(r -> r.contains("->LLLLLIL(")));
        assertTrue(refs(method(owner,"LLLLLJLJLL")).stream().anyMatch(r -> r.contains("->LLLLLJIL(")));
        assertTrue(refs(method(owner,"LLLLLIL")).stream().anyMatch(r -> r.contains("AbstractFeedAdapter;->LJJJI(")));
        assertTrue(refs(method(owner,"LLLLLJIL")).stream().anyMatch(r -> r.contains("AbstractFeedAdapter;->LJJJ(")));
    }
    @Test public void sdkParameterCallerReallyForwardsTheOwnedMediaPlayerInsteadOfAnUnrelatedSetter() {
        Method method = method("LX/0A8v;", "setPlaybackParams");
        assertTrue(refs(method).contains("LX/0A8v;->f:Lcom/ss/ttm/player/MediaPlayer;"));
        assertTrue(refs(method).contains("Lcom/ss/ttm/player/MediaPlayer;->setPlaybackParams(Lcom/ss/ttm/player/PlaybackParams;)V"));
    }
    private static Method method(String owner, String name) { for (Method m : TYPES.get(owner).getMethods()) if (name.equals(m.getName())) return m; throw new AssertionError(name); }
    private static Set<String> refs(Method method) { Set<String> r=new HashSet<>(); for(Instruction i:method.getImplementation().getInstructions())if(i instanceof ReferenceInstruction)r.add(((ReferenceInstruction)i).getReference().toString());return r; }
    private static Set<String> firstBranchReferences(Method method, int branch, int budget) {
        Map<Integer,Instruction> code=new LinkedHashMap<>();int pc=0;
        for(Instruction i:method.getImplementation().getInstructions()){code.put(pc,i);pc+=i.getCodeUnits();}
        int start=-1;
        for(var entry:code.entrySet())if(entry.getValue() instanceof OffsetInstruction){
            Instruction payload=code.get(entry.getKey()+((OffsetInstruction)entry.getValue()).getCodeOffset());
            if(payload instanceof SwitchPayload)for(SwitchElement element:((SwitchPayload)payload).getSwitchElements())if(element.getKey()==branch)start=entry.getKey()+element.getOffset();
        }
        assertTrue("Expected switch branch "+branch,start>=0);Set<String> refs=new HashSet<>();int count=0;
        for(var entry:code.entrySet())if(entry.getKey()>=start && count++<budget && entry.getValue() instanceof ReferenceInstruction)refs.add(((ReferenceInstruction)entry.getValue()).getReference().toString());
        return refs;
    }
}
