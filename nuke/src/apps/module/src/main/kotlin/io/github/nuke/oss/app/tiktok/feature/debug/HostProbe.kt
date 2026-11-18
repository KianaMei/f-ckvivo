package io.github.nuke.oss.app.tiktok.feature.debug

import android.os.Bundle
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.FeatureMemory
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Reads the shape of the host classes the remaining plan items still need, so their anchors can be written from a real
// signature list instead of a guess. It writes into the module store rather than the log because the host's log buffer
// drops lines on a busy start, and it does nothing unless the user turns it on.
//
// Two of the probes are hooks, both read-only: the profile sidebar's item map is copied out when the host builds it,
// and a user search fragment's arguments are copied out when it is created. That is what A12 and A9 need to build
// entries on their own instead of guessing at the obfuscated signatures.
@HookEntry
internal class HostProbe(
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    private val memory: FeatureMemory,
) : RuntimeFeature {
    override val id = "HostProbe"

    override fun install(scope: FeatureScope) {
        runCatching { dumpClasses() }.onFailure { logger.log("probe classes_failed ${it.javaClass.simpleName}") }
        runCatching { hookSidebarMap(scope) }.onFailure { logger.log("probe sidebar_failed ${it.javaClass.simpleName}") }
        runCatching { hookUserSearch(scope) }.onFailure { logger.log("probe search_failed ${it.javaClass.simpleName}") }
    }

    private fun dumpClasses() {
        val dump = StringBuilder()
        for (name in CANDIDATES) {
            val type = classOrNull(name)
            if (type == null) {
                dump.append("class=").append(name).append(" missing\n")
                continue
            }
            val methods = runCatching { type.declaredMethods }.getOrDefault(emptyArray())
            dump.append("class=").append(name).append(" methods=").append(methods.size).append('\n')
            methods.take(MAX_METHODS).forEach { method ->
                dump.append("method=").append(type.simpleName).append('#').append(method.name).append('(')
                    .append(method.parameterTypes.joinToString(",") { it.simpleName }).append(")->")
                    .append(method.returnType.simpleName).append('\n')
            }
            runCatching { type.declaredConstructors }.getOrDefault(emptyArray()).take(MAX_CONSTRUCTORS)
                .forEach { constructor ->
                    dump.append("ctor=").append(type.simpleName).append('(')
                        .append(constructor.parameterTypes.joinToString(",") { it.simpleName }).append(")\n")
                }
        }
        memory.write(id, CLASS_DUMP, dump.toString())
        logger.log("probe class_dump lines=${dump.count { it == '\n' }}")
    }

    // The sidebar implementation builds its items into a map; copying it once shows what an injected entry must be.
    private fun hookSidebarMap(scope: FeatureScope) {
        val impl = classOrNull(SIDEBAR_IMPL) ?: return
        impl.declaredMethodsNamed("LJI").forEach { method ->
            scope.hooks.install(
                method,
                HookSpec(
                    id = "ProbeSidebarMap",
                    after = after@{ call ->
                        val map = (call.outcome as? CallOutcome.Returned)?.value as? Map<*, *> ?: return@after
                        val text = map.entries.take(MAX_ENTRIES).joinToString("\n") { (key, value) ->
                            "$key=${value?.javaClass?.name}"
                        }
                        memory.write(id, SIDEBAR_MAP, "size=${map.size}\n$text")
                    },
                ),
            )
        }
    }

    // A user search fragment carries the query in its arguments; copying them once shows how the host opens it.
    private fun hookUserSearch(scope: FeatureScope) {
        val fragment = classOrNull(SEARCH_FRAGMENT) ?: return
        fragment.declaredMethodsNamed("onCreate").forEach { method ->
            scope.hooks.install(
                method,
                HookSpec(
                    id = "ProbeSearchArgs",
                    after = after@{ call ->
                        val saved = (call.argument(0) as? Bundle)?.let(::bundleText).orEmpty()
                        val own = runCatching {
                            val getter = call.receiver?.javaClass?.getMethod("getArguments")
                            (getter?.invoke(call.receiver) as? Bundle)?.let(::bundleText).orEmpty()
                        }.getOrDefault("")
                        memory.write(id, SEARCH_ARGS, "saved:\n$saved\narguments:\n$own")
                    },
                ),
            )
        }
    }

    private fun bundleText(bundle: Bundle): String =
        bundle.keySet().take(MAX_ENTRIES).joinToString("\n") { key -> "$key=${runCatching { bundle.get(key) }.getOrNull()}" }

    private fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name, false, hostClassLoader) }.getOrNull()

    companion object : TikTokFeature {
        private const val CLASS_DUMP = "dump"
        private const val SIDEBAR_MAP = "sidebar"
        private const val SEARCH_ARGS = "search_args"
        private const val MAX_METHODS = 40
        private const val MAX_CONSTRUCTORS = 6
        private const val MAX_ENTRIES = 40
        private const val SEARCH_FRAGMENT = "com.ss.android.ugc.aweme.discover.presenter.SearchUserProfileFragment"
        private const val SIDEBAR_IMPL = "com.ss.android.ugc.aweme.sidebar.profilesidebar.core.service.ProfileSideBarServiceImpl"

        // The interfaces and implementations the plan's A9 (user search) and A12 (profile sidebar) items point at.
        private val CANDIDATES = listOf(
            "com.ss.android.ugc.aweme.search.ISearchService",
            SEARCH_FRAGMENT,
            "com.ss.android.ugc.aweme.discover.presenter.SearchUserProfileModel",
            "com.ss.android.ugc.aweme.homepage.profile.sidebar.service.IProfileSideBarService",
            "com.ss.android.ugc.aweme.homepage.profile.sidebar.service.ProfileSideBarService",
            SIDEBAR_IMPL,
        )

        override fun create(services: TikTokFeatureServices) =
            with(services) { listOf(HostProbe(hostClassLoader, logger, memory)) }
    }
}
