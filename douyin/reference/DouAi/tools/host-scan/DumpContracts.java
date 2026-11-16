package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import java.io.BufferedInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/** Text-only instruction evidence for a bounded, explicit owner/method selection. */
public final class DumpContracts {
    public static void main(String[] args) throws Exception {
        Set<String> targets = new HashSet<>(Arrays.asList(args).subList(1, args.length));
        try (ZipFile archive = new ZipFile(args[0])) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (var input = new BufferedInputStream(archive.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef owner : dex.getClasses()) {
                        for (Method method : owner.getMethods()) {
                            if (!targets.contains(owner.getType() + "->" + method.getName())) continue;
                            System.out.println(entry.getName() + " " + owner.getType() + "->" + method.getName()
                                    + method.getParameterTypes() + method.getReturnType());
                            if (method.getImplementation() != null) {
                                for (Instruction instruction : method.getImplementation().getInstructions()) {
                                    System.out.println("  " + instruction.getOpcode()
                                            + (instruction instanceof ReferenceInstruction
                                            ? " " + ((ReferenceInstruction) instruction).getReference() : ""));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
