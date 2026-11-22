package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.util.List;

/**
 * 编译期桩：只保留合并模块实际用到的成员。
 * 签名对齐 Vector framework/vector.dex 里的 io.github.libxposed.api.XposedInterface（API 102）。
 * 运行时用的是框架自己的实现，同名同描述符即可。
 */
public interface XposedInterface {

    interface Chain {
        Object getArg(int i);

        List getArgs();

        Executable getExecutable();

        Object getThisObject();

        Object proceed();

        Object proceed(Object[] objArr);

        Object proceedWith(Object obj);

        Object proceedWith(Object obj, Object[] objArr);
    }

    interface HookBuilder {
        HookHandle intercept(Hooker hooker);

        HookBuilder setPriority(int i);

        HookBuilder setId(String str);

        HookBuilder setExceptionMode(ExceptionMode exceptionMode);
    }

    interface HookHandle {
        Executable getExecutable();

        String getId();

        HookHandle replaceHook(Hooker hooker);

        void unhook();
    }

    interface Hooker {
        Object intercept(Chain chain);
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }

    HookBuilder hook(Executable executable);

    boolean deoptimize(Executable executable);

    void log(int i, String str, String str2);

    void log(int i, String str, String str2, Throwable th);
}
