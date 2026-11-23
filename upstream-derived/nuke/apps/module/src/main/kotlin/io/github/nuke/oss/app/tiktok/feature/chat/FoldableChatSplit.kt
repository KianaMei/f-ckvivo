package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.reflect.Modifier

@HookEntry
internal class FoldableChatSplit(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val foldDelegateName: String = FOLD_DELEGATE,
    private val padFragmentName: String = PAD_FRAGMENT,
) : RuntimeFeature, UsesDexQueries {
    override val id = "FoldableChatSplit"
    override val dexQueries = listOf(ChatQueries.PAD_MESSAGE_LIST)

    override fun install(scope: FeatureScope) {
        val gate = resolver.methods(hostClassLoader, ChatQueries.PAD_MESSAGE_LIST).single()
        val booleanType = Boolean::class.javaPrimitiveType
        check(!Modifier.isStatic(gate.modifiers) && gate.parameterCount == 0 && gate.returnType == booleanType) {
            "Unexpected tablet message-list gate: $gate"
        }
        Class.forName(padFragmentName, false, hostClassLoader).getConstructor()
        val foldDelegate = Class.forName(foldDelegateName, false, hostClassLoader)
        val singleton = foldDelegate.getField("INSTANCE")
        val isFoldDevice = foldDelegate.getMethod("isFoldDevice")
        check(Modifier.isStatic(singleton.modifiers) && singleton.type == foldDelegate &&
            !Modifier.isStatic(isFoldDevice.modifiers) && isFoldDevice.returnType == booleanType
        ) { "Unexpected fold device delegate: $foldDelegateName" }

        scope.hooks.install(gate, HookSpec(
            id = "UseTabletMessagesOnFoldables",
            requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
            after = { call ->
                val result = call.outcome as? CallOutcome.Returned
                if (result?.value == false && isFoldDevice.invoke(singleton.get(null)) == true) {
                    call.returnValue(true)
                }
            },
        ))
    }

    companion object : TikTokFeature {
        private const val FOLD_DELEGATE = "com.ss.android.ugc.aweme.pad_api.fold.IFoldCommonServiceDelegate"
        private const val PAD_FRAGMENT = "com.ss.android.ugc.aweme.im.sdk.module.session.rips.MessageFragmentPad"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(FoldableChatSplit(resolver, hostClassLoader))
        }
    }
}
