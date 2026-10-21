package com.musheer360.swiftslate.xposed;

import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.util.Log;

import com.musheer360.swiftslate.BuildConfig;
import com.musheer360.swiftslate.bridge.BridgeProtocol;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LSPosed (libxposed API 102) entry point. Loaded into every keyboard app in the module's scope.
 * This is what replaces SwiftSlate's AccessibilityService.
 */
public class MainHook extends XposedModule {
    private static final String TAG = "SwiftSlateHook";

    private interface Callback { void run(List<Object> args, Object thisObject); }

    private final Set<Method> hookedSelection = new HashSet<>();
    private volatile ImeSession session;

    public MainHook() {
        super();
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        // Never hook ourselves or system_server; only keyboards need this module.
        if (BuildConfig.APPLICATION_ID.equals(pkg) || "android".equals(pkg)) return;
        try {
            hookInputMethodService();
        } catch (Throwable t) {
            Log.e(TAG, "failed to hook InputMethodService in " + pkg, t);
        }
    }

    private void hookInputMethodService() throws Exception {
        Method onCreate = InputMethodService.class.getDeclaredMethod("onCreate");
        hookAfter(onCreate, (args, self) -> {
            if (self instanceof InputMethodService) onImeCreated((InputMethodService) self);
        });

        Method onDestroy = InputMethodService.class.getDeclaredMethod("onDestroy");
        hookBefore(onDestroy, (args, self) -> {
            ImeSession s = session;
            session = null;
            if (s != null) s.destroy();
        });
    }

    private void onImeCreated(InputMethodService ims) {
        ImeSession old = session;
        if (old != null) old.destroy();

        SharedPreferences prefs = null;
        try {
            prefs = getRemotePreferences(BridgeProtocol.PREFS_SNAPSHOT);
        } catch (Throwable t) {
            Log.w(TAG, "remote preferences unavailable", t);
        }
        ImeSession s = new ImeSession(ims, prefs);
        session = s;

        hookSelectionUpdates(ims.getClass());
        s.sayHello();
    }

    /**
     * Hooks onUpdateSelection on the most-derived class that declares it. Keyboards override it
     * and may or may not call super, so hooking the base class alone would miss some of them,
     * while hooking every level would fire once per level.
     */
    private void hookSelectionUpdates(Class<?> imsClass) {
        Method target = null;
        for (Class<?> c = imsClass; c != null && target == null; c = c.getSuperclass()) {
            try {
                target = c.getDeclaredMethod("onUpdateSelection",
                        int.class, int.class, int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        if (target == null) {
            Log.w(TAG, "onUpdateSelection not found on " + imsClass.getName());
            return;
        }
        synchronized (hookedSelection) {
            if (!hookedSelection.add(target)) return;
        }
        hookAfter(target, (args, self) -> {
            ImeSession s = session;
            if (s == null || args.size() < 4) return;
            Object a = args.get(2);
            Object b = args.get(3);
            if (a instanceof Integer && b instanceof Integer) {
                s.onSelectionUpdate((Integer) a, (Integer) b);
            }
        });
        Log.i(TAG, "hooked onUpdateSelection on " + target.getDeclaringClass().getName());
    }

    // ---- tiny wrappers around the libxposed interceptor API --------------------------------

    private void hookAfter(Executable method, Callback callback) {
        hook(method).intercept(chain -> {
            List<Object> args = chain.getArgs();
            Object result = chain.proceed(args.toArray());
            try {
                callback.run(args, chain.getThisObject());
            } catch (Throwable t) {
                Log.w(TAG, "after-hook failed", t);
            }
            return result;
        });
    }

    private void hookBefore(Executable method, Callback callback) {
        hook(method).intercept(chain -> {
            List<Object> args = chain.getArgs();
            try {
                callback.run(args, chain.getThisObject());
            } catch (Throwable t) {
                Log.w(TAG, "before-hook failed", t);
            }
            return chain.proceed(args.toArray());
        });
    }
}
