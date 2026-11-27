package io.github.nuke.oss.assembly

import org.junit.Assert.*
import org.junit.Test
import io.github.nuke.oss.core.*
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.hook.testing.FakeHookBackend
import io.github.nuke.oss.hook.testing.ProbeTarget
import io.github.nuke.oss.runtime.*

class RuntimeAssemblyTest {
    private val backend = FakeHookBackend()
    private val services = BootstrapServices(backend, Logger {})
    private val event = LoadEvent(
        ProcessIdentity(1, 10001, HostScope.TIKTOK, HostScope.TIKTOK),
        BuildIdentity("io.github.nuke.oss", "test", "g"), "xposed82", LoadStage.HOST_CLASSLOADER_READY,
        ProbeTarget::class.java.classLoader,
    )
    private val greet = ProbeTarget::class.java.getMethod("greet", String::class.java)

    private fun assembly(signatureRefusal: (LoadEvent, Logger) -> String? = ::moduleSignatureRefusal) =
        RuntimeAssembly(listOf(ProbeHostAdapter()), signatureRefusal)

    @Test fun `full bootstrap rewrites disables and restores a method`() {
        val result = assembly().accept(event, services) as BootstrapResult.Started
        val toggle = ProbeTarget::class.java.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
        val describe = ProbeTarget::class.java.getMethod("describe")
        assertEquals("hooked:original:NukeApp", backend.call(greet, null, "Alice"))
        assertTrue((backend.call(describe) as String).contains("probe.greeting: ACTIVE"))
        backend.call(toggle, null, false)
        assertEquals("original:Alice", backend.call(greet, null, "Alice"))
        backend.call(toggle, null, true)
        assertEquals("hooked:original:NukeApp", backend.call(greet, null, "Alice"))
        assertEquals(3, backend.attachments.get())
        result.session.close()
        assertEquals("original:Alice", backend.call(greet, null, "Alice"))
    }

    @Test fun `a module APK with another signer starts nothing`() {
        val messages = ArrayList<String>()
        val assembly = assembly(signatureRefusal = { _, _ -> "The module APK is not signed with the release certificate" })
        val result = assembly.accept(event, BootstrapServices(backend, Logger { messages += it }))
        assertEquals("The module APK is not signed with the release certificate", (result as BootstrapResult.Ignored).reason)
        assertEquals(0, backend.attachments.get())
        assertTrue(messages.any { it.startsWith("module_signature_refused") })
    }

    @Test fun `an entry's module APK is the one checked`() {
        var checked: String? = null
        val assembly = assembly(signatureRefusal = { loaded, _ -> checked = loaded.moduleApkPath; null })
        assembly.accept(event.copy(moduleApkPath = "/data/app/module/base.apk"), services)
        assertEquals("/data/app/module/base.apk", checked)
    }

    @Test fun `duplicate load event reuses one session`() {
        val assembly = assembly()
        val first = assembly.accept(event, services) as BootstrapResult.Started
        val second = assembly.accept(event, services) as BootstrapResult.Started
        assertSame(first.session, second.session)
        assertEquals(3, backend.attachments.get())
    }

    @Test fun `unsupported package and out of scope process do not attach hooks`() {
        val assembly = assembly()
        assertTrue(assembly.accept(event.copy(process = event.process.copy(packageName = "other")), services) is BootstrapResult.Ignored)
        val sandbox = event.process.copy(processName = "${HostScope.TIKTOK}:sandboxed_process0")
        assertTrue(assembly.accept(event.copy(process = sandbox), services) is BootstrapResult.Ignored)
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `another backend cannot replace an active runtime`() {
        val assembly = assembly()
        assembly.accept(event, services)
        val different = object : io.github.nuke.oss.hook.api.HookBackend by backend { override val id = "different" }
        assertThrows(IllegalStateException::class.java) { assembly.accept(event, services.copy(backend = different)) }
    }

    @Test fun `same backend name does not allow another backend instance`() {
        val assembly = assembly()
        assembly.accept(event, services)
        assertThrows(IllegalStateException::class.java) { assembly.accept(event, services.copy(backend = FakeHookBackend())) }
    }

    @Test fun `closed session is not returned as a newly started runtime`() {
        val assembly = assembly()
        (assembly.accept(event, services) as BootstrapResult.Started).session.close()
        assertTrue(assembly.accept(event, services) is BootstrapResult.Ignored)
    }

    // Stands in for a host adapter on the scoped Douyin package: one feature rewrites ProbeTarget.greet, the other
    // reports the feature states and toggles it, so the assembly is exercised without Douyin's own classes.
    private class ProbeHostAdapter : HostAdapter {
        override val descriptor = HostDescriptor("probe", HostScope.TIKTOK, implemented = true)

        override fun register(environment: HostEnvironment, manager: FeatureManager) {
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
                        call.returnEarly(manager.snapshot().joinToString("\n") { "${it.id}: ${it.state}" })
                    }))
                    scope.hooks.install(setEnabled, HookSpec(id = "Toggle", before = { call ->
                        val state = manager.setEnabled("probe.greeting", call.argument(0) as Boolean)
                        call.returnEarly("${state.id}: ${state.state}")
                    }))
                }
            })
            manager.setEnabled("probe.controls", true)
            manager.setEnabled("probe.greeting", true)
        }
    }
}
