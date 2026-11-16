package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class UnifiedInteraction412InstallationTest {
    @Test public void aFailedSecondStageRollsBackOnlyItsHandlesAndCannotClaimSupport() throws Exception {
        AtomicInteger installed = new AtomicInteger(), revoked = new AtomicInteger();
        ClassLoader loader = getClass().getClassLoader();
        XposedInterface framework = (XposedInterface)Proxy.newProxyInstance(loader, new Class<?>[]{XposedInterface.class}, (api, method, args) -> {
            if (!method.getName().equals("hook")) return null;
            return Proxy.newProxyInstance(loader, new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                if (!call.getName().equals("intercept")) return builder;
                int sequence = installed.incrementAndGet();
                if (sequence == 3) return null;
                return Proxy.newProxyInstance(loader, new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, parameters) -> {
                    if (action.getName().equals("unhook")) revoked.incrementAndGet();
                    return null;
                });
            });
        });
        UnifiedInteraction412Hook feature = new UnifiedInteraction412Hook(new LocalOptions(Collections.emptyMap()), null);
        feature.stage(UnifiedInteraction412Hook.SHOW_HIDDEN_EMOJI, () -> feature.add(framework,
                Object.class.getMethod("toString"), chain -> chain.proceed()));
        assertTrue(feature.supports(UnifiedInteraction412Hook.SHOW_HIDDEN_EMOJI));
        feature.stage(UnifiedInteraction412Hook.CONFIRM_FOLLOW, () -> {
            feature.add(framework, Object.class.getMethod("toString"), chain -> chain.proceed());
            feature.add(framework, Object.class.getMethod("hashCode"), chain -> chain.proceed());
        });
        assertFalse(feature.supports(UnifiedInteraction412Hook.CONFIRM_FOLLOW));
        assertTrue(feature.supports(UnifiedInteraction412Hook.SHOW_HIDDEN_EMOJI));
        assertEquals(1, revoked.get());
        feature.close();
        assertEquals(2, revoked.get());
        assertFalse(feature.supports(UnifiedInteraction412Hook.SHOW_HIDDEN_EMOJI));
    }
}
