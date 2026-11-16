package com.douai.module.legacy;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adapter for this module's around hooks on classic API82. Each executable has one
 * classic replacement and one priority-ordered module chain. Classic API has no
 * resumable around continuation: invokeOriginalMethod bypasses OTHER modules'
 * hooks, so this build must not share a target with another enhancement module.
 * Class initializers, deoptimization, remote storage and constructor allocation
 * invokers are deliberately unsupported rather than reporting false success.
 */
public final class LegacyHookBridge implements XposedInterface, AutoCloseable {
    @FunctionalInterface interface Original { Object call(Executable target, Object receiver, Object[] args) throws Throwable; }
    @FunctionalInterface interface Callback { Object call(Object receiver, Object[] args) throws Throwable; }
    interface Backend {
        Runnable install(Executable target, Callback callback);
        Object original(Executable target, Object receiver, Object[] args) throws Throwable;
        void log(String text, Throwable failure);
        int version();
    }
    private static final class ClassicBackend implements Backend {
        @Override public Runnable install(Executable target, Callback callback) {
            XC_MethodHook.Unhook handle = XposedBridge.hookMethod(target, new XC_MethodHook(PRIORITY_HIGHEST) {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try { param.setResult(callback.call(param.thisObject, param.args)); }
                    catch (Throwable failure) { param.setThrowable(failure); }
                }
            });
            if (handle == null) throw new IllegalStateException("Classic framework returned no hook handle");
            return handle::unhook;
        }
        @Override public Object original(Executable target, Object receiver, Object[] args) throws Throwable {
            try { return XposedBridge.invokeOriginalMethod(target, receiver, args); }
            catch (InvocationTargetException failure) { throw failure.getCause() == null ? failure : failure.getCause(); }
        }
        @Override public void log(String text, Throwable failure) { XposedBridge.log(text); if (failure != null) XposedBridge.log(failure); }
        @Override public int version() { return XposedBridge.getXposedVersion(); }
    }
    private final Backend backend;
    private final ApplicationInfo moduleInfo;
    private final Map<Executable, Dispatcher> dispatchers = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private boolean closed;

    public LegacyHookBridge(ApplicationInfo moduleInfo) { this(moduleInfo, new ClassicBackend()); }
    /** Backend-only seam for JVM chain tests; never used by a module entry. */
    LegacyHookBridge(Backend backend) { this.moduleInfo = null; this.backend = Objects.requireNonNull(backend); }
    LegacyHookBridge(ApplicationInfo moduleInfo, Backend backend) {
        this.moduleInfo = new ApplicationInfo(Objects.requireNonNull(moduleInfo));
        this.backend = Objects.requireNonNull(backend);
    }
    @Override public int getApiVersion() { return LIB_API; }
    @Override public String getFrameworkName() { return "Classic Xposed compatibility adapter (not native API102)"; }
    @Override public String getFrameworkVersion() { return Integer.toString(backend.version()); }
    @Override public long getFrameworkVersionCode() { return backend.version(); }
    @Override public long getFrameworkProperties() { return 0; }
    @Override public HookBuilder hook(Executable target) {
        Objects.requireNonNull(target);
        if (target instanceof Method && Modifier.isAbstract(target.getModifiers())) throw new IllegalArgumentException("Cannot hook abstract method");
        synchronized (dispatchers) { if (closed) throw new IllegalStateException("Legacy bridge is closed"); }
        return new Builder(target);
    }
    @Override public HookBuilder hookClassInitializer(Class<?> type) { throw new UnsupportedOperationException("Classic API82 cannot hook a class initializer"); }
    @Override public boolean deoptimize(Executable target) { return false; }
    @Override public Invoker<?, Method> getInvoker(Method target) { return new MethodInvoker(Objects.requireNonNull(target)); }
    @Override public <T> CtorInvoker<T> getInvoker(Constructor<T> target) { throw new UnsupportedOperationException("Classic bridge does not allocate constructor invokers"); }
    @Override public void log(int priority, String tag, String message) { backend.log("[" + tag + "] " + message, null); }
    @Override public void log(int priority, String tag, String message, Throwable failure) { backend.log("[" + tag + "] " + message, failure); }
    @Override public ApplicationInfo getModuleApplicationInfo() {
        if (moduleInfo == null) throw new IllegalStateException("Test backend has no module application metadata");
        return new ApplicationInfo(moduleInfo);
    }
    @Override public SharedPreferences getRemotePreferences(String name) { return EMPTY_PREFERENCES; }
    @Override public String[] listRemoteFiles() { return new String[0]; }
    @Override public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException { throw new FileNotFoundException("Classic adapter has no remote files"); }

    private final class Builder implements HookBuilder {
        final Executable target;
        int priority = PRIORITY_DEFAULT;
        ExceptionMode exceptionMode = ExceptionMode.DEFAULT;
        String id = "";
        boolean installed;
        Builder(Executable target) { this.target = target; }
        @Override public HookBuilder setPriority(int value) { priority = value; return this; }
        @Override public HookBuilder setExceptionMode(ExceptionMode value) { exceptionMode = Objects.requireNonNull(value); return this; }
        @Override public HookBuilder setId(String value) { id = Objects.requireNonNull(value); return this; }
        @Override public HookHandle intercept(Hooker hooker) {
            Objects.requireNonNull(hooker);
            synchronized (dispatchers) {
                if (closed || installed) throw new IllegalStateException(closed ? "Bridge closed" : "Builder already installed");
                Dispatcher dispatcher = dispatchers.get(target);
                if (dispatcher == null) {
                    dispatcher = new Dispatcher(target);
                    Dispatcher selected = dispatcher;
                    dispatcher.unhook = backend.install(target, selected::invoke);
                    dispatchers.put(target, dispatcher);
                }
                Registration registration = new Registration(dispatcher, hooker, priority, exceptionMode, id, sequence.getAndIncrement());
                dispatcher.registrations.add(registration);
                installed = true;
                return registration;
            }
        }
    }
    private final class Dispatcher {
        final Executable target;
        final List<Registration> registrations = new ArrayList<>();
        Runnable unhook;
        Dispatcher(Executable target) { this.target = target; }
        Object invoke(Object receiver, Object[] arguments) throws Throwable {
            List<Registration> snapshot;
            synchronized (dispatchers) {
                snapshot = new ArrayList<>(registrations);
                snapshot.sort(Comparator.comparingInt((Registration r) -> r.priority).reversed().thenComparingLong(r -> r.order));
            }
            return next(snapshot, 0, receiver, arguments == null ? new Object[0] : arguments);
        }
        Object next(List<Registration> hooks, int index, Object receiver, Object[] args) throws Throwable {
            if (index == hooks.size()) return backend.original(target, receiver, args);
            Registration registration = hooks.get(index);
            Frame chain = new Frame(this, hooks, index + 1, receiver, args);
            try { return registration.hooker.intercept(chain); }
            catch (Throwable failure) {
                if (registration.mode == ExceptionMode.PASSTHROUGH) throw failure;
                // A proceed exception always remains the original proceeded failure. Hook exceptions
                // before proceed continue once; after proceed recover the cached result/failure.
                if (chain.proceeded && chain.failure == failure) throw failure;
                backend.log("[DouAi.Legacy] Protected hook failed: " + registration.id, failure);
                return chain.proceeded ? chain.outcome() : chain.proceed();
            }
        }
    }
    private final class Frame implements Chain {
        final Dispatcher dispatcher;
        final List<Registration> hooks;
        final int index;
        final Object receiver;
        final Object[] args;
        final Thread owner = Thread.currentThread();
        boolean proceeded;
        Object result;
        Throwable failure;
        Frame(Dispatcher dispatcher, List<Registration> hooks, int index, Object receiver, Object[] args) {
            this.dispatcher = dispatcher; this.hooks = hooks; this.index = index; this.receiver = receiver; this.args = args;
        }
        @Override public Executable getExecutable() { return dispatcher.target; }
        @Override public Object getThisObject() { return receiver; }
        @Override public List<Object> getArgs() { return Collections.unmodifiableList(Arrays.asList(args.clone())); }
        @Override public Object getArg(int index) { return args[index]; }
        @Override public Object proceed() throws Throwable { return proceedWith(receiver, args); }
        @Override public Object proceed(Object[] next) throws Throwable { return proceedWith(receiver, next); }
        @Override public Object proceedWith(Object nextReceiver) throws Throwable { return proceedWith(nextReceiver, args); }
        @Override public Object proceedWith(Object nextReceiver, Object[] next) throws Throwable {
            if (owner != Thread.currentThread()) throw new IllegalStateException("Hook chain used from another thread");
            if (proceeded) throw new IllegalStateException("proceed may be called only once in the classic adapter");
            proceeded = true;
            try { result = dispatcher.next(hooks, index, nextReceiver, Objects.requireNonNull(next)); return result; }
            catch (Throwable thrown) { failure = thrown; throw thrown; }
        }
        Object outcome() throws Throwable { if (failure != null) throw failure; return result; }
    }
    private final class Registration implements HookHandle {
        final Dispatcher dispatcher;
        volatile Hooker hooker;
        final int priority;
        final ExceptionMode mode;
        final String id;
        final long order;
        boolean removed;
        Registration(Dispatcher dispatcher, Hooker hooker, int priority, ExceptionMode mode, String id, long order) {
            this.dispatcher = dispatcher; this.hooker = hooker; this.priority = priority; this.mode = mode; this.id = id; this.order = order;
        }
        @Override public Executable getExecutable() { return dispatcher.target; }
        @Override public String getId() { return id; }
        @Override public void unhook() {
            synchronized (dispatchers) {
                if (removed) return;
                removed = true; dispatcher.registrations.remove(this);
                if (dispatcher.registrations.isEmpty()) { dispatchers.remove(dispatcher.target); dispatcher.unhook.run(); }
            }
        }
        @Override public HookHandle replaceHook(Hooker next) {
            synchronized (dispatchers) { if (removed) throw new IllegalStateException("Hook removed"); hooker = Objects.requireNonNull(next); return this; }
        }
    }
    private final class MethodInvoker implements Invoker<MethodInvoker, Method> {
        final Method method;
        boolean origin;
        MethodInvoker(Method method) { this.method = method; }
        @Override public MethodInvoker setType(Invoker.Type type) {
            if (!(type instanceof Invoker.Type.Origin)) throw new UnsupportedOperationException("Classic bridge supports only explicit ORIGIN invokers");
            origin = true; return this;
        }
        @Override public Object invoke(Object receiver, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return invokeOriginal(virtualTarget(method, receiver), receiver, args);
        }
        @Override public Object invokeSpecial(Object receiver, Object... args) throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return invokeOriginal(method, receiver, args);
        }
        private Object invokeOriginal(Method target, Object receiver, Object[] args) throws InvocationTargetException {
            if (!origin) throw new UnsupportedOperationException("Select ORIGIN before using the classic invoker");
            try { return backend.original(target, receiver, args); }
            catch (IllegalArgumentException failure) { throw failure; }
            catch (Throwable failure) { throw new InvocationTargetException(failure); }
        }
    }
    private static Method virtualTarget(Method method, Object receiver) {
        if (receiver == null || Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())
                || Modifier.isFinal(method.getModifiers())) return method;
        for (Class<?> type = receiver.getClass(); type != null && type != method.getDeclaringClass(); type = type.getSuperclass()) {
            try {
                Method override = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
                if (!Modifier.isPrivate(override.getModifiers()) && !Modifier.isStatic(override.getModifiers())
                        && method.getReturnType().isAssignableFrom(override.getReturnType())) return override;
            } catch (NoSuchMethodException ignored) { }
        }
        return method;
    }
    @Override public void close() {
        synchronized (dispatchers) {
            if (closed) return; closed = true;
            for (Dispatcher dispatcher : new ArrayList<>(dispatchers.values())) {
                for (Registration registration : dispatcher.registrations) registration.removed = true;
                dispatcher.registrations.clear(); dispatcher.unhook.run();
            }
            dispatchers.clear();
        }
    }
    private static final SharedPreferences EMPTY_PREFERENCES = new SharedPreferences() {
        @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
        @Override public String getString(String key, String fallback) { return fallback; }
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return fallback == null ? null : new HashSet<>(fallback); }
        @Override public int getInt(String key, int fallback) { return fallback; }
        @Override public long getLong(String key, long fallback) { return fallback; }
        @Override public float getFloat(String key, float fallback) { return fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return fallback; }
        @Override public boolean contains(String key) { return false; }
        @Override public Editor edit() { throw new UnsupportedOperationException("Classic adapter remote preferences are read-only and empty; use in-host settings"); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    };
}
