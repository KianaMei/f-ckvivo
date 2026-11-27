package io.github.nuke.oss.testing

import io.github.nuke.oss.common.closeAll
import io.github.nuke.oss.core.RuntimeSnapshot
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookFailureSink
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.hook.runtime.DefaultHookService
import io.github.nuke.oss.hook.testing.ProbeTarget
import io.github.nuke.oss.runtime.*

// A minimal runtime that hooks the shared ProbeTarget with two features, one toggling the other, so each
// backend is exercised through the real scheduler and feature lifecycle without linking the application.
class ProbeBootstrap : RuntimeBootstrap {
    override fun accept(event: LoadEvent, services: BootstrapServices): BootstrapResult {
        val hooks = DefaultHookService(services.backend, HookFailureSink {})
        val manager = FeatureManager(event.build.generationId, hooks, services.logger)
        val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)
        val describe = ProbeTarget::class.java.getMethod("describe")
        val setEnabled = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        manager.register(object : RuntimeFeature {
            override val id = "probe.greeting"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(greet, HookSpec(
                    id = "RewriteGreeting",
                    before = { it.setArgument(0, "NukeApp") },
                    after = { call ->
                        val returned = call.outcome as? CallOutcome.Returned
                        if (returned != null) call.returnValue("hooked:${returned.value}")
                    },
                ))
            }
        })
        manager.register(object : RuntimeFeature {
            override val id = "probe.controls"
            override fun install(scope: FeatureScope) {
                scope.hooks.install(describe, HookSpec(id = "Describe", before = { call ->
                    call.returnEarly("backend=${services.backend.id}")
                }))
                scope.hooks.install(setEnabled, HookSpec(id = "Toggle", before = { call ->
                    val state = manager.setEnabled("probe.greeting", call.argument(0) as Boolean)
                    call.returnEarly("${state.id}: ${state.state}")
                }))
            }
        })
        manager.setEnabled("probe.controls", true)
        manager.setEnabled("probe.greeting", true)
        return BootstrapResult.Started(object : RuntimeSessionView {
            override fun snapshot() = RuntimeSnapshot(event.build, event.process, "probe", services.backend.id, manager.snapshot())
            override fun close() = listOf<AutoCloseable>(hooks, manager).asReversed().closeAll()
        })
    }
}
