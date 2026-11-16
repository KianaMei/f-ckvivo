package com.douai.module.legacy;

import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual adapter code with an injected classic boundary; no APK or native runtime is executed. */
public final class LegacyHookBridgeTest {
    static class Parent { public String label() { return "parent"; } }
    static final class Child extends Parent { @Override public String label() { return "child"; } }
    static final class Target { int value; Target(int value) { this.value = value; } public int plus(int input) { return value + input; } }
    private static final class NativeFailure extends Exception { }
    private static final class Backend implements LegacyHookBridge.Backend {
        final Map<Executable, LegacyHookBridge.Callback> callbacks = new HashMap<>();
        final List<Executable> originals = new ArrayList<>();
        LegacyHookBridge.Original body;
        int installs, unhooks, logs;
        Backend() {
            body = (target, receiver, args) -> {
                if (target instanceof Constructor) { ((Target) receiver).value = (Integer) args[0]; return null; }
                if (target.getName().equals("label")) return target.getDeclaringClass() == Parent.class ? "parent" : "child";
                return ((Target) receiver).value + (Integer) args[0];
            };
        }
        @Override public Runnable install(Executable target, LegacyHookBridge.Callback callback) {
            installs++; callbacks.put(target, callback); return () -> { unhooks++; callbacks.remove(target); };
        }
        @Override public Object original(Executable target, Object receiver, Object[] args) throws Throwable { originals.add(target); return body.call(target, receiver, args); }
        @Override public void log(String message, Throwable failure) { logs++; }
        @Override public int version() { return 93; }
        Object invoke(Executable target, Object receiver, Object... args) throws Throwable {
            LegacyHookBridge.Callback callback = callbacks.get(target);
            return callback == null ? original(target, receiver, args) : callback.call(receiver, args);
        }
    }
    private static Method plus() throws Exception { return Target.class.getMethod("plus", int.class); }

    @Test public void oneClassicDispatcherRetainsEveryModuleHookInPriorityOrder() throws Throwable {
        Backend backend = new Backend(); List<String> events = new ArrayList<>();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus();
            bridge.hook(target).setPriority(10).intercept(chain -> { events.add("low-before"); Object result = chain.proceed(); events.add("low-after"); return (Integer) result + 1; });
            bridge.hook(target).setPriority(100).intercept(chain -> { events.add("high-before"); Object result = chain.proceed(new Object[]{4}); events.add("high-after"); return (Integer) result * 2; });
            assertEquals(16, backend.invoke(target, new Target(3), 9));
            assertEquals(List.of("high-before", "low-before", "low-after", "high-after"), events);
            assertEquals(1, backend.installs); assertEquals(1, backend.originals.size());
        }
        assertEquals(1, backend.unhooks);
    }
    @Test public void shortCircuitDoesNotCallAnyLowerPriorityHookOrOriginal() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus();
            bridge.hook(target).setPriority(1).intercept(chain -> { throw new AssertionError("Must not run"); });
            bridge.hook(target).setPriority(2).intercept(chain -> 99);
            assertEquals(99, backend.invoke(target, new Target(0), 0)); assertTrue(backend.originals.isEmpty());
        }
    }
    @Test public void protectiveExceptionsBeforeAndAfterProceedNeverExecuteOriginalTwice() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus();
            bridge.hook(target).setPriority(2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> { throw new IllegalStateException("before"); });
            bridge.hook(target).setPriority(1).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> { chain.proceed(); throw new IllegalStateException("after"); });
            assertEquals(7, backend.invoke(target, new Target(3), 4)); assertEquals(1, backend.originals.size()); assertEquals(2, backend.logs);
        }
    }
    @Test public void nativeProceedFailureIsNeverConvertedIntoASecondAttempt() throws Throwable {
        Backend backend = new Backend(); NativeFailure expected = new NativeFailure(); backend.body = (target, receiver, args) -> { throw expected; };
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus(); bridge.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(XposedInterface.Chain::proceed);
            try { backend.invoke(target, new Target(0), 0); fail(); } catch (NativeFailure actual) { assertSame(expected, actual); }
            assertEquals(1, backend.originals.size()); assertEquals(0, backend.logs);
        }
    }
    @Test public void passthroughHookFailureAndDoubleProceedPropagateWithoutDuplicatingOriginal() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus(); bridge.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> { chain.proceed(); return chain.proceed(); });
            try { backend.invoke(target, new Target(0), 0); fail(); } catch (IllegalStateException actual) { assertTrue(actual.getMessage().contains("only once")); }
            assertEquals(1, backend.originals.size());
        }
    }
    @Test public void hookReplacementAndIndividualUnhookRetainOtherRegistrations() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus();
            XposedInterface.HookHandle first = bridge.hook(target).setId("first").intercept(chain -> (Integer) chain.proceed() + 1);
            XposedInterface.HookHandle second = bridge.hook(target).setId("second").intercept(chain -> (Integer) chain.proceed() + 2);
            first.replaceHook(chain -> (Integer) chain.proceed() + 4);
            assertEquals(9, backend.invoke(target, new Target(1), 2)); assertEquals("first", first.getId()); assertSame(target, first.getExecutable());
            first.unhook(); first.unhook(); assertEquals(0, backend.unhooks); assertEquals(5, backend.invoke(target, new Target(1), 2));
            second.unhook(); assertEquals(1, backend.unhooks); assertEquals(3, backend.invoke(target, new Target(1), 2));
            assertThrows(IllegalStateException.class, () -> first.replaceHook(chain -> null));
        }
    }
    @Test public void constructorHooksInitializeTheExistingReceiverAndIgnoreNoAllocationSemantics() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Constructor<Target> target = Target.class.getDeclaredConstructor(int.class); Target receiver = new Target(0);
            bridge.hook(target).intercept(chain -> { assertSame(receiver, chain.getThisObject()); assertNull(chain.proceed(new Object[]{7})); assertEquals(7, receiver.value); return null; });
            assertNull(backend.invoke(target, receiver, 2)); assertEquals(7, receiver.value); assertEquals(1, backend.originals.size());
            assertThrows(UnsupportedOperationException.class, () -> bridge.getInvoker(target));
        }
    }
    @Test public void explicitOriginSpecialUsesExactParentWhileVirtualOriginUsesOverride() throws Exception {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method parent = Parent.class.getMethod("label");
            var invoker = bridge.getInvoker(parent).setType(XposedInterface.Invoker.Type.ORIGIN);
            assertEquals("parent", invoker.invokeSpecial(new Child())); assertEquals("child", invoker.invoke(new Child()));
            assertEquals(List.of(Parent.class, Child.class), backend.originals.stream().map(Executable::getDeclaringClass).toList());
            assertThrows(UnsupportedOperationException.class, () -> bridge.getInvoker(parent).invokeSpecial(new Child()));
            assertThrows(UnsupportedOperationException.class, () -> bridge.getInvoker(parent).setType(new XposedInterface.Invoker.Type.Chain(50)));
        }
    }
    @Test public void chainDoesNotLeakMutableArgumentsOrCrossThreadContinuation() throws Throwable {
        Backend backend = new Backend();
        try (LegacyHookBridge bridge = new LegacyHookBridge(backend)) {
            Method target = plus(); bridge.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                assertThrows(UnsupportedOperationException.class, () -> chain.getArgs().set(0, 1));
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread thread = new Thread(() -> { try { chain.proceed(); } catch (Throwable invalid) { failure.set(invalid); } });
                thread.start(); thread.join(); assertTrue(failure.get() instanceof IllegalStateException); return chain.proceed();
            });
            assertEquals(3, backend.invoke(target, new Target(1), 2)); assertEquals(1, backend.originals.size());
        }
    }
    @Test public void unsupportedCapabilitiesNeverReportSuccessAndRemotePreferencesCannotBeWritten() throws Exception {
        Backend backend = new Backend(); LegacyHookBridge bridge = new LegacyHookBridge(backend);
        assertEquals(0, bridge.getFrameworkProperties()); assertFalse(bridge.deoptimize(plus()));
        assertTrue(bridge.getFrameworkName().contains("not native API102"));
        assertThrows(UnsupportedOperationException.class, () -> bridge.hookClassInitializer(Target.class));
        assertFalse(bridge.getRemotePreferences("anything").contains("old"));
        assertEquals("fallback", bridge.getRemotePreferences("anything").getString("old", "fallback"));
        assertThrows(UnsupportedOperationException.class, () -> bridge.getRemotePreferences("anything").edit());
        assertThrows(java.io.FileNotFoundException.class, () -> bridge.openRemoteFile("anything"));
        bridge.close(); assertThrows(IllegalStateException.class, () -> bridge.hook(plus()));
    }
}
