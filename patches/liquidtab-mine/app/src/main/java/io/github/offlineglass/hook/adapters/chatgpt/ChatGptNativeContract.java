package io.github.offlineglass.hook.adapters.chatgpt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;

/** Read-only APK contracts rooted in platform/Compose API and drawing semantics.
 * Internal descriptors are discovered outputs only, never search keys. */
public final class ChatGptNativeContract {
    public record Ref(String owner,String name,String result,List<String> args) {
        public String key(){return owner+"|"+name+"|"+result+"|"+String.join(",",args);}
    }
    public record Field(String owner,String name,String type) { public String key(){return owner+"|"+name+"|"+type;} }
    static final class Body {
        final Ref ref;final int flags;final Set<String> strings=new HashSet<>();
        final Map<String,List<Field>> labelled=new HashMap<>();final List<Ref> calls=new ArrayList<>();final List<Field> reads=new ArrayList<>(),writes=new ArrayList<>();
        Body(Ref r,int f){ref=r;flags=f;}
    }
    static final class Type {
        final List<String> interfaces=new ArrayList<>();final String name,parent;final List<Field> fields=new ArrayList<>();final List<Body> methods=new ArrayList<>();
        Type(String n,String p){name=n;parent=p;}
    }
    static final class Index { final Map<String,Type> types=new LinkedHashMap<>();final Map<Ref,Body> methods=new LinkedHashMap<>(); }
    static final String LAYOUT="Landroidx/compose/ui/node/LayoutNode;",VIEW="Landroidx/compose/ui/platform/AndroidComposeView;";
    private final Index index;private final Properties roles=new Properties();
    private ChatGptNativeContract(Index i){index=i;}
    public static Properties inspect(String[] apks)throws IOException {
        Index i=new Index();
        for(String apk:apks)try(ZipFile z=new ZipFile(apk)){
            var entries=z.entries();while(entries.hasMoreElements()){
                var e=entries.nextElement();if(!e.getName().matches("classes[0-9]*\\.dex"))continue;
                if(e.getSize()<112||e.getSize()>64*1024*1024)throw new IOException("DEX size");
                try(InputStream in=z.getInputStream(e)){new Dex(in.readAllBytes()).scan(i);}
                if(i.types.containsKey(LAYOUT)&&i.types.containsKey(VIEW)) {
                    ChatGptNativeContract c=new ChatGptNativeContract(i);c.bind();
                    if(c.roles.getProperty("background.draw")!=null)return c.roles;
                }
            }
        }
        ChatGptNativeContract c=new ChatGptNativeContract(i);c.bind();return c.roles;
    }
    private Type type(String name){Type t=index.types.get(name);if(t==null)throw new IllegalStateException("Missing dependency type");return t;}
    private List<Body> all(){return new ArrayList<>(index.methods.values());}
    private <T> T unique(String role,Collection<T> values){
        List<T> list=new ArrayList<>(new LinkedHashSet<>(values));roles.setProperty(role+".candidates",Integer.toString(list.size()));
        if(list.size()!=1)return null;T value=list.get(0);
        roles.setProperty(role,value instanceof Ref?((Ref)value).key():value instanceof Field?((Field)value).key():value.toString());return value;
    }
    private Ref method(String role,String owner,Predicate<Body> match){
        return unique(role,type(owner).methods.stream().filter(match).map(b->b.ref).toList());
    }
    private Field field(String role,String owner,Predicate<Field> match){return unique(role,type(owner).fields.stream().filter(match).toList());}
    private static boolean args(Ref r,String... args){return r.args.equals(List.of(args));}
    private Body body(Ref r){return index.methods.get(r);}
    private void bind(){
        try { bindDrawing(); } catch(RuntimeException e) { roles.setProperty("failure",e.toString()); }
    }
    private void bindDrawing(){
        Ref content=unique("scope.content",all().stream().filter(b->args(b.ref)&&b.ref.result.equals("V")&&
            b.strings.stream().anyMatch(s->s.startsWith("Attempting to drawContent for a `null` node"))).map(b->b.ref).toList());
        if(content==null)return;String scope=content.owner;unique("scope.type",List.of(scope));
        Ref direction=method("scope.direction",scope,b->b.ref.name.equals("getLayoutDirection")&&args(b.ref));
        Ref outer=method("layout.outer",LAYOUT,b->b.ref.name.equals("getOuterCoordinator$ui")&&args(b.ref));
        if(direction==null||outer==null)return;
        String coord=outer.result;unique("coordinate.type",List.of(coord));
        Field chain=unique("layout.chain",body(outer).reads.stream().filter(f->f.owner.equals(LAYOUT)).toList());
        if(chain==null)return;
        Field coordLayout=field("coordinate.layout",coord,f->f.type.equals(LAYOUT));
        Ref detach=unique("node.detach",all().stream().filter(b->b.strings.contains("Cannot detach a node that is not attached")&&args(b.ref)&&b.ref.result.equals("V")).map(b->b.ref).toList());
        if(detach==null)return;String node=detach.owner;unique("node.type",List.of(node));
        Field attached=unique("node.attached",body(detach).writes.stream().filter(f->f.owner.equals(node)&&f.type.equals("Z")).toList());
        field("node.coordinate",node,f->f.type.equals(coord));
        Field head=field("chain.head",chain.type,f->f.type.equals(node));
        Field inner=field("chain.inner",chain.type,f->index.types.containsKey(f.type)&&coord.equals(type(f.type).parent));
        unique("node.next",body(content).reads.stream().filter(f->f.owner.equals(node)&&f.type.equals(node)&&type(node).methods.stream().filter(b->b.ref.name.equals("<init>")).noneMatch(b->b.writes.contains(f))).distinct().toList());
        // Resolve the draw dispatcher through its single concrete Canvas wrapper.
        List<String> wrappers=index.types.values().stream().filter(t->t.fields.stream().anyMatch(f->f.type.equals("Landroid/graphics/Canvas;"))).map(t->t.name).toList();
        List<Body> layoutDraws=type(LAYOUT).methods.stream().filter(b->b.ref.result.equals("V")&&b.ref.args.size()==2&&
            b.calls.contains(outer)&&b.calls.stream().anyMatch(c->c.owner.equals(coord)&&c.result.equals("V")&&c.args.equals(b.ref.args))).toList();
        Ref layoutDraw=unique("layout.draw",layoutDraws.stream().map(b->b.ref).toList());
        if(layoutDraw==null)return;
        String canvasApi=layoutDraw.args.get(0);
        String wrapper=unique("canvas.type",wrappers.stream().filter(w->type(w).interfaces.contains(canvasApi)&&
            type(w).methods.stream().anyMatch(b->b.calls.stream().anyMatch(c->c.owner.equals("Landroid/graphics/Canvas;")&&c.name.equals("drawPath")))).toList());
        if(wrapper!=null)field("canvas.native",wrapper,f->f.type.equals("Landroid/graphics/Canvas;"));
        // A Background draws content, caches an outline, and has a Shape field
        // with the packed-size/layout-direction/density -> outline contract.
        List<Body> backgrounds=all().stream().filter(b->b.ref.result.equals("V")&&args(b.ref,scope)&&b.calls.contains(content)&&
            type(b.ref.owner).fields.stream().filter(f->f.type.equals("F")).count()==1&&
            type(b.ref.owner).fields.stream().filter(f->f.type.equals("J")).count()>=2&&
            type(b.ref.owner).fields.stream().anyMatch(f->index.types.containsKey(f.type)&&type(f.type).methods.stream().anyMatch(m->
                m.ref.args.size()==3&&m.ref.args.get(0).equals("J")&&m.ref.args.get(1).equals(direction.result)&&
                index.types.containsKey(m.ref.args.get(2))&&type(m.ref.args.get(2)).methods.stream().anyMatch(d->d.ref.name.equals("getDensity"))))).toList();
        Ref draw=unique("background.draw",backgrounds.stream().map(b->b.ref).toList());
        if(draw==null)return;String background=draw.owner;unique("background.type",List.of(background));
        List<Field> shapes=type(background).fields.stream().filter(f->index.types.containsKey(f.type)&&type(f.type).methods.stream().anyMatch(m->m.ref.args.size()==3&&m.ref.args.get(0).equals("J")&&m.ref.args.get(1).equals(direction.result))).toList();
        Field shape=unique("background.shape",shapes.stream().filter(f->!body(draw).writes.contains(f)).toList());
        if(shape!=null)method("shape.outline",shape.type,b->b.ref.args.size()==3&&b.ref.args.get(0).equals("J")&&b.ref.args.get(1).equals(direction.result));
        Field canvasScope=unique("scope.canvasScope",body(direction).reads.stream().filter(f->f.owner.equals(scope)).toList());
        if(canvasScope!=null){
            Field params=unique("scope.parameters",body(direction).reads.stream().filter(f->f.owner.equals(canvasScope.type)).toList());
            if(params!=null){
                field("parameters.size",params.type,f->f.type.equals("J"));
                field("parameters.direction",params.type,f->f.type.equals(direction.result));
                if(shape!=null){Ref outline=type(shape.type).methods.get(0).ref;if(outline.args.size()==3)field("parameters.density",params.type,f->f.type.equals(outline.args.get(2)));}
                field("parameters.canvas",params.type,f->f.type.equals(canvasApi));
            }
        }
        List<Body> invalidations=type(coord).methods.stream().filter(b->args(b.ref)&&b.ref.result.equals("V")&&b.calls.contains(b.ref)&&
            b.reads.stream().anyMatch(f->f.owner.equals(coord)&&f.type.equals(coord))&&
            b.calls.stream().anyMatch(c->!c.owner.equals(coord)&&c.result.equals("V")&&args(c))).toList();
        Ref invalidation=unique("coordinate.invalidate",invalidations.stream().map(b->b.ref).toList());
        if(invalidation!=null){
            unique("coordinate.parent",body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&f.type.equals(coord)).toList());
            unique("coordinate.layer",body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&!f.type.equals(coord)).toList());
        }
        List<Body> packed=type(coord).methods.stream().filter(b->b.ref.result.equals("J")&&args(b.ref,"J")).toList();
        roles.setProperty("coordinate.packedTransforms.candidates",Integer.toString(packed.size()));
        Ref root=unique("coordinate.root",packed.stream().filter(b->b.calls.stream().anyMatch(c->c.owner.equals("Landroidx/compose/ui/node/Owner;")&&c.name.equals("getRectManager"))).map(b->b.ref).toList());
        if(root!=null)unique("coordinate.screen",packed.stream().filter(b->b.calls.contains(root)&&b.reads.stream().anyMatch(f->f.owner.equals(VIEW)&&f.type.equals("[F"))).map(b->b.ref).toList());
        Ref matrix=method("coordinate.relativeMatrix",coord,b->b.ref.result.equals("V")&&b.ref.args.size()==2&&b.ref.args.get(1).equals("[F")&&!b.ref.args.get(0).equals(coord));
        if(matrix!=null){String coordsApi=matrix.args.get(0);method("coordinate.size",coordsApi,b->args(b.ref)&&b.ref.result.equals("J"));}
        field("layout.owner",LAYOUT,f->f.type.equals("Landroidx/compose/ui/node/Owner;"));
        Ref placed=method("layout.placed",LAYOUT,b->args(b.ref)&&b.ref.result.equals("Ljava/lang/Boolean;"));
        method("layout.children",LAYOUT,b->b.ref.name.equals("getChildren$ui")&&args(b.ref));
        unique("node.delegate",body(content).reads.stream().filter(f->f.type.equals(node)&&!f.owner.equals(node)).toList());
        Ref text=method("layout.description",LAYOUT,b->b.ref.name.equals("toString")&&args(b.ref)&&b.strings.contains(" deactivated: "));
        if(text!=null)unique("layout.deactivated",body(text).labelled.getOrDefault(" deactivated: ",List.of()).stream().filter(f->f.owner.equals(LAYOUT)&&f.type.equals("Z")).toList());
        Ref nodes=method("owner.nodes",VIEW,b->b.ref.name.equals("getLayoutNodes")&&args(b.ref)&&(b.flags&64)==0);
        if(nodes!=null){String map=nodes.result;
            while(map!=null){List<Body> gets=type(map).methods.stream().filter(b->args(b.ref,"I")&&b.ref.result.equals("Ljava/lang/Object;")&&b.writes.isEmpty()&&b.calls.stream().allMatch(c->c.owner.startsWith("Ljava/lang/"))&&b.reads.stream().anyMatch(f->f.type.equals("[Ljava/lang/Object;"))&&b.reads.stream().anyMatch(f->f.type.equals("[I"))).toList();
                if(!gets.isEmpty()){unique("semantics.lookup",gets.stream().map(b->b.ref).toList());break;}map=type(map).parent;}
        }
        Ref outline=shape==null?null:type(shape.type).methods.stream().filter(b->b.ref.args.size()==3&&b.ref.args.get(0).equals("J")&&b.ref.args.get(1).equals(direction.result)).map(b->b.ref).findFirst().orElse(null);
        if(outline!=null){
            Ref bounds=method("outline.bounds",outline.result,b->args(b.ref)&&b.ref.result.startsWith("L"));
            if(bounds!=null){
                Ref ctor=method("rectangle.constructor",bounds.result,b->b.ref.name.equals("<init>")&&args(b.ref,"F","F","F","F"));
                if(ctor!=null){List<Field> fs=body(ctor).writes.stream().filter(f->f.owner.equals(bounds.result)&&f.type.equals("F")).distinct().toList();
                    if(fs.size()==4)for(int j=0;j<4;j++)unique("rectangle."+List.of("left","top","right","bottom").get(j),List.of(fs.get(j)));}
            }
            for(Type t:index.types.values())if(outline.result.equals(t.parent)){
                for(Field value:t.fields){Type g=index.types.get(value.type);if(g==null)continue;
                    var ctors=g.methods.stream().filter(b->b.ref.name.equals("<init>")&&args(b.ref,"F","F","F","F","J","J","J","J")).toList();
                    if(ctors.size()==1){unique("outline.round",List.of(t.name));unique("outline.roundValue",List.of(value));
                        List<Field> fs=ctors.get(0).writes.stream().filter(f->f.owner.equals(g.name)).distinct().toList();
                        if(fs.size()==8)for(int j=0;j<8;j++)unique("round."+List.of("left","top","right","bottom","topLeft","topRight","bottomRight","bottomLeft").get(j),List.of(fs.get(j)));
                    }
                    if(t.fields.size()==1&&g.fields.stream().filter(f->f.type.equals("Landroid/graphics/Path;")).count()==1){
                        unique("outline.generic",List.of(t.name));unique("outline.path",List.of(value));field("path.native",g.name,f->f.type.equals("Landroid/graphics/Path;"));
                    }
                }
            }
        }
        if(shape!=null){
            List<Body> shadowDraws=all().stream().filter(b->args(b.ref,scope)&&b.ref.result.equals("V")&&b.calls.contains(content)&&
                type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals(shape.type))&&
                type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals("Lkotlin/jvm/functions/Function0;"))&&
                b.calls.stream().anyMatch(c->c.result.equals("V")&&args(c,scope,"F")&&type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals(c.owner)))).toList();
            Ref shadow=unique("shadow.draw",shadowDraws.stream().map(b->b.ref).toList());
            if(shadow!=null){Ref painter=unique("shadow.paint",body(shadow).calls.stream().filter(c->c.result.equals("V")&&args(c,scope,"F")).toList());
                if(painter!=null){field("shadow.painter",shadow.owner,f->f.type.equals(painter.owner));
                    Ref registry=unique("shadow.registryDraw",all().stream().filter(b->args(b.ref,scope)&&b.ref.result.equals("V")&&b.calls.contains(painter)&&
                        b.calls.stream().anyMatch(c->c.owner.equals("Ljava/util/Map$Entry;")&&c.name.equals("getValue"))).map(b->b.ref).toList());
                    if(registry!=null){field("shadow.registryNode",shadow.owner,f->f.type.equals(registry.owner));
                        field("shadow.registry",registry.owner,f->index.types.containsKey(f.type)&&type(f.type).interfaces.contains("Ljava/util/Map;"));
                        Field entry=unique("shadow.entryPainter",body(registry).reads.stream().filter(f->f.type.equals(painter.owner)).toList());
                        if(entry!=null)unique("shadow.entryActive",body(registry).reads.stream().filter(f->f.owner.equals(entry.owner)&&f.type.equals("Z")&&!body(registry).writes.contains(f)).toList());
                    }
                }
            }
        }
        Ref alpha=unique("renderer.alphaSetter",all().stream().filter(b->b.ref.result.equals("V")&&args(b.ref,"F")&&
            b.calls.stream().anyMatch(c->c.owner.equals("Landroid/graphics/RenderNode;")&&c.name.equals("setAlpha")&&args(c,"F"))).map(b->b.ref).toList());
        if(alpha!=null){
            field("renderer.native",alpha.owner,f->f.type.equals("Landroid/graphics/RenderNode;"));
            List<String> rendererInterfaces=type(alpha.owner).interfaces;
            Field implementation=unique("graphics.renderer",index.types.values().stream().filter(t->t.fields.stream().anyMatch(f->f.type.equals("Landroid/graphics/Outline;")))
                .flatMap(t->t.fields.stream()).filter(f->rendererInterfaces.contains(f.type)).toList());
            Field owned=body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&!f.type.equals(coord)).findFirst().orElse(null);
            if(implementation!=null&&owned!=null)unique("layer.graphics",index.types.values().stream().filter(t->t.interfaces.contains(owned.type)&&t.fields.stream().anyMatch(f->f.type.equals(VIEW)))
                .flatMap(t->t.fields.stream()).filter(f->f.type.equals(implementation.owner)).toList());
        }
        // Delegated nodes are found via their parent-side modifier chain role;
        // shadow registry and animation alpha need independent unique contracts.
    }
    static final class Dex {
        final byte[] b; final Map<Integer,String> text=new HashMap<>();
        Dex(byte[] b)throws IOException{this.b=b;if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x'||u4(32)!=b.length)throw new IOException("DEX header");}
        int u2(int p){if(p<0||p+2>b.length)throw new IllegalArgumentException("DEX bounds");return(b[p]&255)|((b[p+1]&255)<<8);}
        int u4(int p){return u2(p)|(u2(p+2)<<16);}
        int leb(int[] p){int v=0;for(int i=0;i<5;i++){int q=b[p[0]++]&255;v|=(q&127)<<(7*i);if((q&128)==0)return v;}throw new IllegalArgumentException("ULEB");}
        String str(int id){return text.computeIfAbsent(id,k->{int[] p={u4(u4(60)+k*4)};leb(p);int end=p[0];while(b[end]!=0)end++;return new String(b,p[0],end-p[0],StandardCharsets.UTF_8);});}
        String type(int id){return str(u4(u4(68)+id*4));}
        Ref ref(int id){int m=u4(92)+id*8,proto=u4(76)+u2(m+2)*12,params=u4(proto+8);List<String>a=new ArrayList<>();if(params!=0)for(int i=0;i<u4(params);i++)a.add(type(u2(params+4+i*2)));return new Ref(type(u2(m)),str(u4(m+4)),type(u4(proto+4)),List.copyOf(a));}
        Field field(int id){int p=u4(84)+id*8;return new Field(type(u2(p)),str(u4(p+4)),type(u2(p+2)));}
        void scan(Index out)throws IOException {
            for(int i=0;i<u4(96);i++){
                int def=u4(100)+i*32,data=u4(def+24); String owner=type(u4(def));
                Type t=new Type(owner,u4(def+8)==-1?null:type(u4(def+8))); out.types.put(owner,t);
                int interfaces=u4(def+12);if(interfaces!=0)for(int j=0;j<u4(interfaces);j++)t.interfaces.add(type(u2(interfaces+4+j*2)));
                if(data==0)continue;
                int[] p={data};int s=leb(p),f=leb(p),d=leb(p),v=leb(p);
                for(int count:new int[]{s,f}){int id=0;for(int n=0;n<count;n++){
                    id+=leb(p);int flags=leb(p);if((flags&8)==0)t.fields.add(field(id));
                }}
                for(int count:new int[]{d,v}){int id=0;for(int n=0;n<count;n++){
                    id+=leb(p);int flags=leb(p),code=leb(p);Ref r=ref(id);
                    Body body=code==0?new Body(r,flags):inspect(r,flags,code);
                    t.methods.add(body);out.methods.put(r,body);
                }}
            }
        }
        Body inspect(Ref r,int flags,int code)throws IOException{
            Body body=new Body(r,flags);int count=u4(code+12),start=code+16;
            if(count<0||(long)start+count*2L>b.length)throw new IOException("DEX code bounds");
            
            String label=null;Map<Integer,Object> registers=new HashMap<>();
            for(int pos=0;pos<count;){int word=u2(start+pos*2),op=word&255,w=width(op);
                if(op==0&&word!=0){if(word==0x100)w=4+u2(start+(pos+1)*2)*2;else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*Integer.toUnsignedLong(u4(start+(pos+2)*2))+1)/2);else throw new IOException("DEX payload");}
                if(w<1||pos+w>count)throw new IOException("DEX instruction");
                if(op==0x1a||op==0x1b){String value=str(op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2));body.strings.add(value);registers.put(word>>>8,value);}
                if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    Ref call=ref(u2(start+(pos+1)*2));body.calls.add(call);
                    if(call.owner.equals("Ljava/lang/StringBuilder;")&&call.name.equals("append")&&call.args.size()==1){
                        int argument=(op>=0x74)?u2(start+(pos+2)*2)+1:(u2(start+(pos+2)*2)>>>4)&15;
                        Object value=registers.get(argument);
                        if(value instanceof String)label=(String)value;
                        else if(value instanceof Field && label!=null){body.labelled.computeIfAbsent(label,k->new ArrayList<>()).add((Field)value);label=null;}
                    }
                }
                if(op==0x01||op==0x04||op==0x07){int destination=(word>>>8)&15,source=word>>>12;registers.put(destination,registers.get(source));}
                if(op==0x02||op==0x05||op==0x08)registers.put(word>>>8,registers.get(u2(start+(pos+1)*2)));
                if((op>=0x52&&op<=0x58)||(op>=0x60&&op<=0x66)){
                    Field f=field(u2(start+(pos+1)*2));body.reads.add(f);registers.put(op<=0x58?(word>>>8)&15:word>>>8,f);
                }
                if((op>=0x59&&op<=0x5f)||(op>=0x67&&op<=0x6d))body.writes.add(field(u2(start+(pos+1)*2)));
                pos+=w;
            }return body;
        }
        static int width(int op){
            if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
            if(op==0x03||op==0x06||op==0x09||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
            if(op==0x02||op==0x05||op==0x08||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;return 1;
        }
    }
}
