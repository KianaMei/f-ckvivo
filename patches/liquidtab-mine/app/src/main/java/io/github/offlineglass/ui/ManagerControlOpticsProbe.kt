package io.github.offlineglass.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.github.offlineglass.ui.ios.LiquidToggle
import io.github.offlineglass.ui.ios.LiquidSlider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shell-only debug test: synthetic stripes, RAM-only pixel statistics; no image export. */
internal fun verifyManagerControlOptics(): Bundle {
    check(Looper.myLooper() != Looper.getMainLooper())
    val result = Bundle()
    val done = CountDownLatch(1)
    val main = Handler(Looper.getMainLooper())
    main.post {
        val activity = io.github.offlineglass.MainActivity.diagnosticActivity.get()
        val parent = activity?.findViewById<ViewGroup>(android.R.id.content)
        if (activity == null || parent == null || !activity.hasWindowFocus()) {
            result.putString("error", "Module must be foreground"); done.countDown(); return@post
        }
        val overlay = ComposeView(activity)
        val rectangles = HashMap<String, android.graphics.Rect>()
        var captureState: IosCaptureState? = null
        val animations = HashMap<String, io.github.offlineglass.ui.ios.DampedDragAnimation>()
        var stopped = false
        fun finish() {
            if (stopped) return
            stopped = true
            parent.removeView(overlay); overlay.disposeComposition(); done.countDown()
        }
        fun touch(action: Int, rect: android.graphics.Rect, dx: Float = 0f) {
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, rect.exactCenterX()+dx, rect.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { result.putBoolean("lastTouchHandled",activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        fun sample(name: String, rect: android.graphics.Rect, next: () -> Unit) {
            result.putInt(name+"_consumers",captureState?.activeOpticalConsumers ?: -1)
            result.putFloat(name+"_press",animations[if(name.startsWith("toggle")) "toggle" else "slider"]?.pressProgress ?: -1f)
            val d = activity.resources.displayMetrics.density
            val centerX = if (name.startsWith("toggle")) rect.left + (22f*d).toInt() else rect.centerX()
            val area = android.graphics.Rect(centerX-(12*d).toInt(),rect.centerY()-(7*d).toInt(),
                centerX+(12*d).toInt(),rect.centerY()+(7*d).toInt())
            val pixels = android.graphics.Bitmap.createBitmap(area.width(),area.height(),android.graphics.Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window,area,pixels,{ status ->
                if(status==PixelCopy.SUCCESS) {
                    var chromatic=0; var dark=0; var light=0
                    for(y in 0 until pixels.height) for(x in 0 until pixels.width) {
                        val c=pixels.getPixel(x,y);val r=android.graphics.Color.red(c);val g=android.graphics.Color.green(c);val b=android.graphics.Color.blue(c)
                        if(maxOf(r,g,b)-minOf(r,g,b)>40) chromatic++
                        if(maxOf(r,g,b)<70) dark++
                        if(minOf(r,g,b)>220) light++
                    }
                    result.putInt(name+"_color",chromatic);result.putInt(name+"_dark",dark);result.putInt(name+"_white",light)
                } else result.putInt(name+"_copy_error",status)
                pixels.recycle(); next()
            },main)
        }
        overlay.setContent {
            top.yukonga.miuix.kmp.theme.MiuixTheme {
                CompositionLocalProvider(LocalIosStyle provides true, LocalIosDark provides true) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF202020)),contentAlignment=Alignment.Center) {
                        ManagerStyleCard(Modifier.width(320.dp),allowTilt=false) {
                            val capture = LocalIosCaptureState.current
                            SideEffect { captureState=capture }
                            Box(Modifier.fillMaxWidth().height(200.dp)) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val stripe=6.dp.toPx()
                                    for(i in 0..(size.width/stripe).toInt()) drawRect(
                                        if(i%2==0) Color(0xFFFF2040) else Color(0xFF20CFFF),
                                        Offset(i*stripe,0f),Size(stripe,size.height))
                                }
                                Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceEvenly,horizontalAlignment=Alignment.CenterHorizontally) {
                                    CompositionLocalProvider(io.github.offlineglass.ui.ios.LocalControlOpticsProbe provides { animations["toggle"]=it }) {
                                    LiquidToggle({false},{},Modifier.onGloballyPositioned {
                                        val r=it.boundsInWindow();rectangles["toggle"]=android.graphics.Rect(r.left.toInt(),r.top.toInt(),r.right.toInt(),r.bottom.toInt())
                                    })
                                    }
                                    var value by remember { mutableFloatStateOf(0.5f) }
                                    CompositionLocalProvider(io.github.offlineglass.ui.ios.LocalControlOpticsProbe provides { animations["slider"]=it }) {
                                    LiquidSlider({value},{value=it},valueRange=0f..1f,visibilityThreshold=0.001f,
                                        modifier=Modifier.width(260.dp).onGloballyPositioned {
                                            val r=it.boundsInWindow();rectangles["slider"]=android.graphics.Rect(r.left.toInt(),r.top.toInt(),r.right.toInt(),r.bottom.toInt())
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        parent.addView(overlay,ViewGroup.LayoutParams(-1,-1))
        main.postDelayed({
            val t=rectangles["toggle"];val s=rectangles["slider"]
            if(t==null||s==null){result.putString("error","Test controls not laid out");finish();return@postDelayed}
            sample("toggle_idle",t) {
                animations["toggle"]?.press()
                main.postDelayed({sample("toggle_active",t) {
                    animations["toggle"]?.release()
                    animations["slider"]?.press()
                    main.postDelayed({sample("slider_active",s) {
                        animations["slider"]?.release()
                        main.postDelayed({ sample("slider_idle",s) {finish()} },900)
                    }},350)
                }},350)
            }
        },700)
        main.postDelayed({if(!stopped){result.putString("error","Probe timed out");finish()}},6000)
    }
    if(!done.await(8,TimeUnit.SECONDS))result.putString("error","Main thread timeout")
    return result
}
