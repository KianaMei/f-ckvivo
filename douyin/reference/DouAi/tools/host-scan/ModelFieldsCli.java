package com.douai.module.scan;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;
import java.io.BufferedInputStream;
import java.util.*;
import java.util.zip.ZipFile;
/** Static field/JSON-name evidence only; does not load host classes. */
public final class ModelFieldsCli {
    public static void main(String[] args) throws Exception {
        Set<String> names=new HashSet<>(Arrays.asList(args).subList(1,args.length));
        try(ZipFile zip=new ZipFile(args[0])){
            var entries=zip.entries();
            while(entries.hasMoreElements()&&!names.isEmpty()){
                var entry=entries.nextElement();if(!entry.getName().matches("classes[0-9]*\\.dex"))continue;
                try(var input=new BufferedInputStream(zip.getInputStream(entry))){
                    var dex=DexBackedDexFile.fromInputStream(Opcodes.getDefault(),input);
                    for(var type:dex.getClasses())if(names.remove(type.getType())){
                        System.out.println("OWNER "+type.getType()+" "+entry.getName());
                        for(var field:type.getFields()){
                            String json="";
                            for(var a:field.getAnnotations())if(a.getType().equals("Lcom/google/gson/annotations/SerializedName;"))for(var e:a.getElements())if(e.getName().equals("value")&&e.getValue()instanceof StringEncodedValue)json=((StringEncodedValue)e.getValue()).getValue();
                            System.out.println(field.getName()+"\t"+field.getType()+"\t"+json);
                        }
                    }
                }
            }
        }
    }
}
