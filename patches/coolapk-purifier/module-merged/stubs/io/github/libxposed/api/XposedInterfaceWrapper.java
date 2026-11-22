package io.github.libxposed.api;

import java.lang.reflect.Executable;

/**
 * 编译期桩。运行时由 Vector 的 vector.dex 提供真实实现（转调 attachFramework 注入的基类）。
 */
public class XposedInterfaceWrapper implements XposedInterface {

    @Override
    public HookBuilder hook(Executable executable) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public boolean deoptimize(Executable executable) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void log(int i, String str, String str2) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void log(int i, String str, String str2, Throwable th) {
        throw new UnsupportedOperationException("stub");
    }
}
