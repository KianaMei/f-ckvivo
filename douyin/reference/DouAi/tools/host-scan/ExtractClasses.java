package com.douai.module.scan;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.BufferedInputStream;
import java.util.*;
import java.util.zip.ZipFile;

/** Selects original class definitions for bounded static decompilation; never loads host code. */
public final class ExtractClasses {
    public static void main(String[] args) throws Exception {
        Set<String> pending = new HashSet<>(Arrays.asList(args).subList(2, args.length));
        List<ClassDef> selected = new ArrayList<>();
        try (ZipFile archive = new ZipFile(args[0])) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && !pending.isEmpty()) {
                var entry = entries.nextElement();
                if (!entry.getName().matches("classes(?:[0-9]+)?\\.dex")) continue;
                try (var input = new BufferedInputStream(archive.getInputStream(entry))) {
                    var dex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), input);
                    for (ClassDef owner : dex.getClasses()) {
                        if (pending.remove(owner.getType())) {
                            selected.add(ImmutableClassDef.of(owner));
                            System.out.println(entry.getName() + " " + owner.getType());
                        }
                    }
                }
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Missing requested classes: " + pending);
        DexPool.writeTo(args[1], new ImmutableDexFile(Opcodes.getDefault(), selected));
    }
}
