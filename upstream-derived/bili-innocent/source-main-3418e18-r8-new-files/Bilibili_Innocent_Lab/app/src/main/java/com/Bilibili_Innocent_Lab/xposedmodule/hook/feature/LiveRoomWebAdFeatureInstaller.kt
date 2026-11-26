package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.io.ByteArrayInputStream
import java.lang.reflect.Method

/**
 * 直播间 WebView 广告素材拦截。
 *
 * 直播间里有一部分推广位是网页素材（`nuwa` / `mortal_cultivator` 两类投放路径），宿主用
 * `com.bilibili.app.comm.bh.NativeWebView` 承载，请求在 WebViewClient 的
 * `shouldInterceptRequest` 边界上逐个出现。这里只在这个边界上替换命中标记的请求：
 *
 * - **主文档命中**（`isForMainFrame`）：直播间活动挂件整页就是投放页
 *   （`live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html`），
 *   回一个空 HTML，整块挂件不再渲染；返回图片会变成破图标，所以按文档类型回；
 * - **子资源命中**：回 1×1 透明 PNG，只去掉素材本身；
 * - **只认素材路径标记**，不做域名猜测、不扫 View、不改宿主数据；
 * - 只依赖稳定类 `NativeWebView` 与其同包内的 `WebViewClient` 子类，子类名混淆（9.18.0
 *   为 `$d`），所以按 `外层名 + $后缀` 有界枚举后按签名筛选，候选不唯一就按缺失处理。
 *
 * 失败不影响宿主：解析不到、注册失败都只回退到宿主原行为并上报一次原因。
 */
internal class LiveRoomWebAdFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        val loader = environment.classLoader
            ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val client = resolveClient(loader)
            ?: return FeatureInstallResult.Skipped("missing-webview-client")

        var installed = 0
        var expected = 0

        expected += 1
        runCatching {
            environment.registrar.exact(
                "live_room_web_ad.request",
                client,
                "shouldInterceptRequest",
                classOf<WebView>(),
                classOf<WebResourceRequest>()
            ) {
                before {
                    val request = args.getOrNull(1) as? WebResourceRequest ?: return@before
                    val action = LiveRoomAdUrlPolicy.decide(request.url?.toString(), request.isForMainFrame)
                    if (action == LiveRoomAdAction.NONE) return@before
                    result = LiveRoomAdBlockResponse.build(action)
                    reportHit(environment, request.url?.toString(), action)
                }
            }
        }.onSuccess {
            installed += 1
            environment.reportCapability(ID, FeatureInstallResult.Installed())
            environment.reportStatus(CHANNEL_STATUS, "success")
        }.onFailure { failure ->
            environment.logError(
                "live_room_web_ad_register_failed",
                "[BIL] 直播间广告图拦截注册失败，保留宿主原行为: $failure"
            )
            environment.reportStatus(CHANNEL_STATUS, "failed")
        }

        // 兼容旧入口：部分机型/内嵌页仍走 String 重载。
        val legacy = resolveLegacyIntercept(client)
        if (legacy != null) {
            expected += 1
            runCatching {
                environment.registrar.exact(
                    "live_room_web_ad.request_legacy",
                    client,
                    legacy.name,
                    classOf<WebView>(),
                    classOf<String>()
                ) {
                    before {
                        val url = args.getOrNull(1) as? String ?: return@before
                        val webView = instance as? WebView
                        // 旧入口拿不到 isForMainFrame，只能靠 URL 与当前文档相同来判主文档。
                        val isMainFrame = webView?.url != null && webView.url == url
                        val action = LiveRoomAdUrlPolicy.decide(url, isMainFrame)
                        if (action == LiveRoomAdAction.NONE) return@before
                        result = LiveRoomAdBlockResponse.build(action)
                        reportHit(environment, url, action)
                    }
                }
            }.onSuccess { installed += 1 }.onFailure { failure ->
                environment.logError(
                    "live_room_web_ad_register_legacy_failed",
                    "[BIL] 直播间广告图 String 入口注册失败，保留宿主原行为: $failure"
                )
            }
        }

        val complete = installed == expected
        environment.reportStatus(
            CHANNEL_STATUS,
            if (installed == 0) "failed" else if (complete) "success" else "partial:$installed/$expected"
        )
        if (installed > 0) {
            environment.logInfo(
                "live_room_web_ad_install",
                "[BIL] 直播间广告图拦截已安装 client=${client.name} legacy=${legacy != null}"
            )
        }
        return if (installed == 0) {
            FeatureInstallResult.Skipped("no-safe-hook-point")
        } else {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            FeatureInstallResult.Installed(installed, complete)
        }
    }

    /** 在 `NativeWebView` 的 $a..$z/$0..$9 里找唯一的 WebViewClient 实现，且必须声明两参拦截。 */
    internal fun resolveClient(loader: ClassLoader): Class<*>? = runCatching {
        val outer = KavaMemberLookup.classOrNull(loader, OUTER_CLASS) ?: return null
        val candidates = NESTED_SUFFIXES.mapNotNull {
            KavaMemberLookup.classOrNull(loader, outer.name + '$' + it)
        }.distinct()
            .filter { !it.isInterface && !it.isPrimitive && !it.isArray }
            .filter { classOf<WebViewClient>().isAssignableFrom(it) }
            .filter { owner -> resolveIntercept(owner) != null }
        candidates.singleOrNull()
    }.getOrNull()

    internal fun resolveLegacyIntercept(owner: Class<*>): Method? = runCatching {
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) && method.name == "shouldInterceptRequest" && method.returnType == classOf<WebResourceResponse>() &&
                method.parameterTypes.contentEquals(arrayOf<Class<*>>(classOf<WebView>(), classOf<String>()))
        }.singleOrNull()
    }.getOrNull()

    /**
     * 只接受**本类自己实现**的两参拦截：父类 `WebViewClient` 也有同名方法（返回 null），
     * 认下它等于什么都没拦。9.18.0 实测 `NativeWebView$d` 自己声明了两个重载。
     */
    internal fun resolveIntercept(owner: Class<*>): Method? = runCatching {
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
            !java.lang.reflect.Modifier.isStatic(method.modifiers) && !java.lang.reflect.Modifier.isAbstract(method.modifiers) && method.name == "shouldInterceptRequest" &&
                method.returnType == classOf<WebResourceResponse>() &&
                method.parameterTypes.contentEquals(arrayOf<Class<*>>(classOf<WebView>(), classOf<WebResourceRequest>()))
        }.singleOrNull()
    }.getOrNull()

    /** 命中只报一次证据、只打一条日志：WebView 子资源是高频路径，逐条上报会把预算打穿。 */
    private val hitLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun reportHit(environment: HookEnvironment, url: String?, action: LiveRoomAdAction) {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        if (hitLogged.compareAndSet(false, true)) {
            val path = url?.substringAfter("://", "")?.take(96).orEmpty()
            val kind = if (action == LiveRoomAdAction.EMPTY_PAGE) "页面" else "素材"
            environment.logInfo("live_room_web_ad_hit", "[BIL] 已拦截直播间广告${kind}: $path")
        }
    }

    companion object {
        const val ID = "live_room_web_ad"
        private const val CHANNEL_STATUS = "live_room_web_ad_status"
        private const val OUTER_CLASS = "com.bilibili.app.comm.bh.NativeWebView"
        private val NESTED_SUFFIXES: List<String> =
            ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }
    }
}

/** 拦截动作：整页投放挂件给空文档，单个素材给透明像素。 */
internal enum class LiveRoomAdAction { NONE, TINY_IMAGE, EMPTY_PAGE }

/**
 * 广告素材路径判定。
 *
 * `nuwa` 是直播推广素材的投放路径标记，`mortal_cultivator` 是另一类活动素材；两者都只作为
 * **路径片段**匹配，且拒绝以任何非 http(s) 入口（如 data:/file:）为借口触发。
 *
 * 9.18.0 实测直播间活动挂件是**主文档**整页投放
 * （`live.bilibili.com/activity/nuwa/liveroom_activity_pendant/index.html`），
 * 只拦子资源等于没拦，所以主文档也命中，但换成空 HTML 而不是图片；主文档只在
 * `/activity/` 投放路径下才认，避免误伤正文里带同样字样的正常页面。
 */
internal object LiveRoomAdUrlPolicy {
    private val MARKERS = listOf("nuwa", "mortal_cultivator")

    fun decide(url: String?, isMainFrame: Boolean): LiveRoomAdAction {
        val value = url?.trim()?.lowercase().orEmpty()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return LiveRoomAdAction.NONE
        if (MARKERS.none(value::contains)) return LiveRoomAdAction.NONE
        if (!isMainFrame) return LiveRoomAdAction.TINY_IMAGE
        return if (value.contains("/activity/")) LiveRoomAdAction.EMPTY_PAGE else LiveRoomAdAction.NONE
    }
}

/** 拦截响应：主文档回空 HTML（空 body 的图片会变破图标），子资源回真实透明像素。 */
internal object LiveRoomAdBlockResponse {
    private const val TRANSPARENT_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

    private val png: ByteArray by lazy {
        android.util.Base64.decode(TRANSPARENT_PNG_BASE64, android.util.Base64.DEFAULT)
    }

    private val emptyHtml: ByteArray = "<!doctype html><html><body></body></html>".toByteArray()

    fun build(action: LiveRoomAdAction): WebResourceResponse = when (action) {
        LiveRoomAdAction.EMPTY_PAGE -> response("text/html", emptyHtml)
        LiveRoomAdAction.TINY_IMAGE -> response("image/png", png)
        LiveRoomAdAction.NONE -> error("NONE 不应构造拦截响应")
    }

    private fun response(mime: String, body: ByteArray) = WebResourceResponse(
        mime,
        "UTF-8",
        200,
        "OK",
        mapOf("Cache-Control" to "no-store", "Content-Length" to body.size.toString()),
        ByteArrayInputStream(body)
    )
}
