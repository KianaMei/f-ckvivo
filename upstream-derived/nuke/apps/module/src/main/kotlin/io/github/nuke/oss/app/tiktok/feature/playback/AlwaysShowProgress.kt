package io.github.nuke.oss.app.tiktok.feature.playback

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The player asks one check whether to hide the progress bar for a video, which it does for short ones. Answering no
// leaves the bar as it was. A second route for older hosts, raising the bar views' alpha, is not implemented.
@HookEntry
internal class AlwaysShowProgress(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "AlwaysShowProgress"
    override val dexQueries = listOf(FeedQueries.PROGRESS_BAR_GATE)

    override fun install(scope: FeatureScope) {
        val gate = resolver.methods(hostClassLoader, FeedQueries.PROGRESS_BAR_GATE).single()
        check(gate.returnType == Boolean::class.javaPrimitiveType) { "${gate.declaringClass.name}.${gate.name} is not a boolean check" }
        scope.hooks.install(gate, HookSpec(
            id = "KeepProgress",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(false) },
        ))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AlwaysShowProgress(resolver, hostClassLoader))
        }
    }
}
