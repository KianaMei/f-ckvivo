package io.github.nuke.oss.app.tiktok.feature.debug

import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Logs the shape of the host classes the remaining plan items still need, so their anchors can be written from a real
// signature list instead of a guess. It installs no hook: it reads the class loader once and writes method and
// constructor signatures to the module log, and does nothing unless the user turns it on.
@HookEntry
internal class HostProbe(
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
) : RuntimeFeature {
    override val id = "HostProbe"

    override fun install(scope: FeatureScope) {
        for (name in CANDIDATES) {
            val type = runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()
            if (type == null) {
                logger.log("probe class=$name missing")
                continue
            }
            val methods = runCatching { type.declaredMethods }.getOrDefault(emptyArray())
            logger.log("probe class=$name methods=${methods.size}")
            methods.take(MAX_METHODS).forEach { method ->
                logger.log(
                    "probe method=${type.simpleName}#${method.name}(" +
                        method.parameterTypes.joinToString(",") { it.simpleName } + ")->" + method.returnType.simpleName,
                )
            }
            runCatching { type.declaredConstructors }.getOrDefault(emptyArray()).take(MAX_CONSTRUCTORS)
                .forEach { constructor ->
                    logger.log(
                        "probe ctor=${type.simpleName}(" +
                            constructor.parameterTypes.joinToString(",") { it.simpleName } + ")",
                    )
                }
        }
    }

    companion object : TikTokFeature {
        private const val MAX_METHODS = 40
        private const val MAX_CONSTRUCTORS = 6

        // The interfaces and implementations the plan's A9 (user search) and A12 (profile sidebar) items point at.
        private val CANDIDATES = listOf(
            "com.ss.android.ugc.aweme.search.ISearchService",
            "com.ss.android.ugc.aweme.discover.presenter.SearchUserProfileFragment",
            "com.ss.android.ugc.aweme.discover.presenter.SearchUserProfileModel",
            "com.ss.android.ugc.aweme.homepage.profile.sidebar.service.IProfileSideBarService",
            "com.ss.android.ugc.aweme.homepage.profile.sidebar.service.ProfileSideBarService",
            "com.ss.android.ugc.aweme.sidebar.profilesidebar.core.service.ProfileSideBarServiceImpl",
        )

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(HostProbe(hostClassLoader, logger)) }
    }
}
