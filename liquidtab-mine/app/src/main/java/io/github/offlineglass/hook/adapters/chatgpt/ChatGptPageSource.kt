package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderNode
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Captures one disjoint branch during normal native traversal, never a root replay. */
internal object ChatGptPageSource {
    private val b get()=ChatGptBindings
    private class Source(layout:Any,parent:Any,anchor:View){
        val layout=WeakReference(layout);val parent=WeakReference(parent);val view=WeakReference(anchor)
        val node=RenderNode("ChatGPT-disjoint-page").apply {setClipToBounds(false)}
        val toScreen=Matrix();val matrixValues=FloatArray(9);var recording=false;var ready=false;var dirty=true;var pending=false
        var requestedAt=android.os.SystemClock.uptimeMillis()
    }
    private data class Frame(val source:Source,val original:Any,val canvas:Canvas)
    private val sources=WeakHashMap<Any,Source>()
    private val owners=WeakHashMap<Any,WeakReference<View>>()
    private val consumers=WeakHashMap<Any,Source>()
    private class Sampling {
        var source:Source?=null;var revision=0L
        val consumer=Matrix();val inverse=Matrix();val transform=Matrix()
        val values=FloatArray(9);val previous=FloatArray(9){Float.NaN}
    }
    private val transforms=WeakHashMap<Any,Sampling>()
    private var samplingRevision=0L
    private val frames=WeakHashMap<XC_MethodHook.MethodHookParam,Frame>()
    private var passes=0
    private fun parentCoordinate(source:Source):Any?=source.parent.get()?.let {b.read("chain.inner",b.read("layout.chain",it)!!)}
    private fun request(source:Source){
        source.dirty=true
        if(source.pending)return
        val view=source.view.get()?:return
        source.pending=true
        view.post {
            source.pending=false
            if(consumers.values.none {it===source})return@post
            parentCoordinate(source)?.let {b.call("coordinate.invalidate",it)}
            view.invalidate()
        }
    }
    fun installResolved(){
        val wrapper=b.type("canvas.type").getDeclaredConstructor().apply {isAccessible=true}
        val canvasField=b.field("canvas.native")
        val coordinateLayout=b.field("coordinate.layout")
        XposedBridge.hookMethod(b.method("coordinate.invalidate"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(sources.isEmpty())return
                sources[coordinateLayout.get(param.thisObject)]?.let {if(!it.dirty)request(it)}
            }
        })
        XposedBridge.hookMethod(b.method("layout.draw"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val source=sources[param.thisObject]?:return
                if(source.recording)return
                runCatching {
                    val view=source.view.get()?:return
                    val canvas=canvasField.get(param.args[0]) as Canvas
                    if(!canvas.isHardwareAccelerated)return
                    val coordinate=parentCoordinate(source)?:return
                    ChatGptComposeBackground.coordinateMatrix(coordinate,output=source.toScreen,values=source.matrixValues)
                    if(source.ready&&!source.dirty&&source.node.hasDisplayList())return
                    val rect=ChatGptComposeBackground.coordinateBounds(coordinate,view)?:return
                    val width=maxOf(view.width,rect.width()).coerceAtLeast(1)
                    val height=maxOf(view.height,rect.height()).coerceAtLeast(1)
                    source.node.setPosition(0,0,width,height)
                    val recording=source.node.beginRecording(width,height)
                    val replacement=wrapper.newInstance();canvasField.set(replacement,recording)
                    source.dirty=false;source.recording=true
                    frames[param]=Frame(source,param.args[0],canvas);param.args[0]=replacement
                }.onFailure {error->source.dirty=true;source.view.get()?.let {ChatGptDiagnostics.report(it.context,"pageSourceFailure",error.toString())}}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                val frame=frames.remove(param)
                if(frame!=null){
                    param.args[0]=frame.original
                    try{
                        frame.source.node.endRecording();frame.canvas.drawRenderNode(frame.source.node)
                        if(!frame.source.ready){frame.source.ready=true;frame.source.view.get()?.post {
                            ChatGptComposeBackground.invalidate(consumers.entries.filter {it.value===frame.source}.map {it.key}.toSet())
                        }}
                    }finally{frame.source.recording=false}
                }
                param.args.firstOrNull()?.let {ChatGptControlShadow.drawForLayout(param.thisObject,it)}
            }
        })
    }
    private fun usable(source:Source,anchor:View):Boolean {
        val layout=source.layout.get();val parent=source.parent.get()
        return b.placed(layout)&&b.placed(parent)&&b.parent(layout!!)===parent&&ChatGptWindows.sameActivity(source.view.get(),anchor)&&
            (!source.ready||source.node.hasDisplayList())&&(source.ready||android.os.SystemClock.uptimeMillis()-source.requestedAt<250L)
    }
    fun bindingsHealthy(nodes:Set<Any>,anchor:View)=nodes.all {node->consumers[node]?.let {usable(it,anchor)}==true}
    fun bindAll(nodes:Set<Any>,anchor:View):Boolean {
        passes++
        val previous=consumers.toMap();nodes.forEach {owners[it]=WeakReference(anchor)}
        val live=owners.entries.toList().mapNotNull {(node,ref)->val view=ref.get()
            if(view==null||!view.isAttachedToWindow||!b.attached(node)){owners.remove(node);consumers.remove(node);transforms.remove(node);null}
            else if(ChatGptWindows.sameActivity(view,anchor))node to view else null
        }
        val forbidden=live.mapNotNull {b.layout(it.first)}
        sources.values.toList().forEach {source->
            if(ChatGptWindows.sameActivity(source.view.get(),anchor)&&(!usable(source,anchor)||source.layout.get()?.let {p->forbidden.any {b.contains(p,it)}}==true)){
                consumers.entries.filter {it.value===source}.map {it.key}.forEach {consumers.remove(it);transforms.remove(it)}
                source.layout.get()?.let(sources::remove);source.node.discardDisplayList()
            }
        }
        live.sortedByDescending {it.second.width.toLong()*it.second.height}.forEach {(node,view)->bind(node,view,forbidden)}
        sources.entries.toList().filter {(_,source)->consumers.values.none {it===source}}.forEach {(key,source)->sources.remove(key);source.node.discardDisplayList()}
        val changed=live.map {it.first}.filter {previous[it]!==consumers[it]}.toSet()
        ChatGptComposeBackground.invalidate(changed)
        ChatGptDiagnostics.report(anchor.context,"sourceLifecycle","bindingPasses=$passes; sources=${sources.size}; consumers=${consumers.size}")
        return changed.isNotEmpty()
    }
    private fun bind(consumer:Any,anchor:View,forbidden:List<Any>){
        consumers[consumer]?.let {if(usable(it,anchor))return else consumers.remove(consumer)}
        sources.values.firstOrNull {usable(it,anchor)&&it.layout.get()?.let {p->forbidden.none {b.contains(p,it)}}==true}?.let {consumers[consumer]=it;return}
        var branch=b.layout(consumer)?:return
        var best:Triple<Any,Any,Long>?=null
        repeat(24){
            val parent=b.parent(branch)?:return@repeat
            for(sibling in b.children(parent)){
                if(sibling===branch||!b.placed(sibling)||forbidden.any {b.contains(sibling,it)})continue
                val rect=ChatGptComposeBackground.coordinateBounds(b.outer(sibling),anchor)?:continue
                if(rect.width()<anchor.width*.7f||rect.height()<1)continue
                val area=rect.width().toLong()*rect.height()
                if(area>(best?.third?:0))best=Triple(sibling,parent,area)
            }
            branch=parent
        }
        val selected=best?:return
        val source=sources.getOrPut(selected.first){Source(selected.first,selected.second,anchor)}
        consumers[consumer]=source;source.requestedAt=android.os.SystemClock.uptimeMillis();request(source)
        ChatGptDiagnostics.report(anchor.context,"pageSource","disjoint semantic branch; area=${selected.third}")
    }
    fun unbind(consumer:Any){
        owners.remove(consumer);transforms.remove(consumer)
        val source=consumers.remove(consumer)?:return
        if(consumers.values.none {it===source}){source.layout.get()?.let(sources::remove);source.node.discardDisplayList();parentCoordinate(source)?.let {b.call("coordinate.invalidate",it)}}
    }
    fun recordingKey(consumer:Any):Long? {
        val source=consumers[consumer]?:return null
        if(source.recording||!source.ready||!source.node.hasDisplayList())return null
        val coordinate=b.read("node.coordinate",consumer)?:return null
        val state=transforms.getOrPut(consumer){Sampling()}
        ChatGptComposeBackground.coordinateMatrix(coordinate,output=state.consumer,values=state.values)
        if(!state.consumer.invert(state.inverse))return null
        state.transform.setConcat(state.inverse,source.toScreen);state.transform.getValues(state.values)
        if(state.source!==source||!state.values.contentEquals(state.previous)){
            state.source=source;state.revision=++samplingRevision;state.values.copyInto(state.previous)
        }
        return state.revision
    }
    fun draw(canvas:Canvas,consumer:Any,screen:Rect,width:Float,height:Float,pad:Int):Int {
        val source=consumers[consumer]?:return 0
        if(source.recording||!source.ready||!source.node.hasDisplayList())return 0
        val transform=(transforms[consumer]?:run {recordingKey(consumer);transforms[consumer]})?.transform?:return 0
        val save=canvas.save()
        try{canvas.translate(pad.toFloat(),pad.toFloat());canvas.concat(transform);canvas.drawRenderNode(source.node)}finally{canvas.restoreToCount(save)}
        return 1
    }
}
