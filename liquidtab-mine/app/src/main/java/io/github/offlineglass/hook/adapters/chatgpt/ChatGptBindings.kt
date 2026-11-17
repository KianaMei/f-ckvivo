package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Properties

/** One verified native capability set for drawing, geometry, sampling and lifecycle. */
internal object ChatGptBindings {
    private val methods = HashMap<String,Method>()
    private val fields = HashMap<String,Field>()
    private val types = HashMap<String,Class<*>>()
    @Volatile var ready = false; private set
    private var pending = false
    private const val SCHEMA = "chatgpt-semantic-contract-2"
    private val required = listOf("scope.content","scope.canvasScope","scope.parameters","parameters.canvas",
        "parameters.size","parameters.direction","parameters.density","background.draw","background.shape",
        "node.coordinate","node.attached","node.next","node.delegate","layout.outer","layout.children",
        "layout.chain","layout.owner","layout.placed","layout.deactivated","chain.head","chain.inner",
        "owner.nodes","semantics.lookup","coordinate.layout","coordinate.parent","coordinate.layer",
        "coordinate.invalidate","coordinate.relativeMatrix","coordinate.screen","coordinate.root","coordinate.size",
        "layout.draw","canvas.native","shape.outline","outline.bounds","outline.roundValue","outline.path","path.native",
        "rectangle.left","rectangle.top","rectangle.right","rectangle.bottom","round.left","round.top","round.right","round.bottom",
        "round.topLeft","round.topRight","round.bottomRight","round.bottomLeft","shadow.draw","shadow.painter",
        "layer.graphics","graphics.renderer","renderer.native","shadow.registryNode","shadow.registryDraw","shadow.registry","shadow.entryPainter","shadow.entryActive")
    fun ensure(activity: Activity): Boolean {
        if (ready) return true
        if (pending) return false
        pending = true
        val app = activity.applicationContext
        val loader = activity.classLoader
        Thread({
            val result = runCatching {
                val apks = (listOf(app.applicationInfo.sourceDir) + app.applicationInfo.splitSourceDirs.orEmpty()).sorted()
                val identity = SCHEMA + apks.joinToString { val f=File(it); "$it/${f.length()}/${f.lastModified()}" }
                val cache = File(app.codeCacheDir,"liquidtab-chatgpt-contract.properties")
                val cached = runCatching { Properties().apply { cache.inputStream().use(::load) } }.getOrNull()
                val properties = if (cached?.getProperty("identity") == identity) cached else
                    ChatGptNativeContract.inspect(apks.toTypedArray()).apply { setProperty("identity",identity) }
                val absent = required.filter { properties.getProperty(it) == null }
                require(absent.isEmpty()) { "Unresolved contracts: " + absent.joinToString { "$it(${properties.getProperty("$it.candidates","0")})" } }
                val ms=HashMap<String,Method>();val fs=HashMap<String,Field>();val ts=HashMap<String,Class<*>>()
                properties.stringPropertyNames().filter { it != "identity" && !it.endsWith(".candidates") && it != "failure" }.forEach { role ->
                    val parts = properties.getProperty(role).split('|')
                    if (parts.size == 1 && parts[0].startsWith("L")) ts[role]=type(parts[0],loader)
                    if (parts.size == 3) {
                        val field=type(parts[0],loader).getDeclaredField(parts[1]).apply { isAccessible=true }
                        require(field.type==type(parts[2],loader));fs[role]=field
                    }
                    if (parts.size == 4 && parts[1] != "<init>") {
                        val args=parts[3].split(',').filter { it.isNotEmpty() }.map { type(it,loader) }.toTypedArray()
                        val method=type(parts[0],loader).getDeclaredMethod(parts[1],*args).apply { isAccessible=true }
                        require(method.returnType==type(parts[2],loader));ms[role]=method
                    }
                }
                require(ms.getValue("background.draw").parameterTypes.single()==ts.getValue("scope.type"))
                require(fs.getValue("node.coordinate").type==ts.getValue("coordinate.type"))
                require(ms.getValue("layout.draw").parameterTypes[0].isAssignableFrom(ts.getValue("canvas.type")))
                val temporary=File(cache.path+".pending")
                temporary.outputStream().use { properties.store(it,SCHEMA) }
                check(temporary.renameTo(cache)) { "Binding cache publish failed" }
                Triple(ms,fs,ts)
            }
            Handler(Looper.getMainLooper()).post {
                result.onSuccess { (ms,fs,ts) ->
                    methods.putAll(ms);fields.putAll(fs);types.putAll(ts)
                    runCatching { ChatGptComposeBackground.installResolved();ready=true
                        ChatGptDiagnostics.report(app,"contract","verified semantic graph; methods=${ms.size}; fields=${fs.size}")
                        if (!activity.isDestroyed) ChatGptComposerGlass.scan(activity)
                    }.onFailure { ChatGptDiagnostics.report(app,"contractFailure",it.toString()) }
                }.onFailure { ChatGptDiagnostics.report(app,"contractFailure",it.toString()) }
            }
        },"ChatGPT-contract-discovery").start()
        return false
    }
    private fun type(descriptor:String,loader:ClassLoader):Class<*> = when(descriptor){
        "V"->Void.TYPE;"Z"->Boolean::class.javaPrimitiveType!!;"B"->Byte::class.javaPrimitiveType!!
        "I"->Int::class.javaPrimitiveType!!;"J"->Long::class.javaPrimitiveType!!;"F"->Float::class.javaPrimitiveType!!
        "D"->Double::class.javaPrimitiveType!!;"S"->Short::class.javaPrimitiveType!!;"C"->Char::class.javaPrimitiveType!!
        else->Class.forName(if(descriptor.startsWith("L"))descriptor.substring(1,descriptor.length-1).replace('/','.') else descriptor.replace('/','.'),false,loader)
    }
    fun method(role:String)=methods.getValue(role)
    fun field(role:String)=fields.getValue(role)
    fun type(role:String)=types.getValue(role)
    fun read(role:String,target:Any):Any?=field(role).get(target)
    fun call(role:String,target:Any?,vararg args:Any?):Any?=method(role).invoke(target,*args)
    fun layout(node:Any):Any?=read("node.coordinate",node)?.let { read("coordinate.layout",it) }
    fun outer(layout:Any):Any=call("layout.outer",layout)!!
    @Suppress("UNCHECKED_CAST") fun children(layout:Any):List<Any> = call("layout.children",layout) as List<Any>
    private val parentFields=HashMap<Class<*>,List<Field>>()
    private data class ParentLink(val field:Field,val parent:java.lang.ref.WeakReference<Any>)
    private val parentLinks=java.util.WeakHashMap<Any,ParentLink>()
    fun parent(layout:Any):Any? {
        parentLinks[layout]?.let { link ->
            val old=link.parent.get()
            if(old!=null&&link.field.get(layout)===old)return old
        }
        val candidates=parentFields.getOrPut(layout.javaClass){layout.javaClass.declaredFields.filter { it.type==layout.javaClass && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible=true }}
        val matching=candidates.mapNotNull { field->field.get(layout)?.let {field to it} }
            .filter {(_,candidate)->children(candidate).any {it===layout}}
        val values=matching.map {it.second}.distinct()
        if(values.size!=1){parentLinks.remove(layout);return null}
        val (field,parent)=matching.first()
        parentLinks[layout]=ParentLink(field,java.lang.ref.WeakReference(parent))
        return parent
    }
    fun contains(ancestor:Any,child:Any):Boolean {
        var current:Any?=child
        repeat(96){if(current===ancestor)return true;current=current?.let(::parent)?:return false};return true
    }
    fun placed(layout:Any?):Boolean = layout!=null && read("layout.owner",layout)!=null && read("layout.deactivated",layout)!=true && call("layout.placed",layout)==true
    fun attached(node:Any):Boolean=runCatching {read("node.attached",node)==true&&placed(layout(node))}.getOrDefault(false)
    fun rect(target:Any,role:String="rectangle")=RectF(read("$role.left",target) as Float,read("$role.top",target) as Float,read("$role.right",target) as Float,read("$role.bottom",target) as Float)
    fun invalidate(node:Any){read("node.coordinate",node)?.let { call("coordinate.invalidate",it) }}
}
