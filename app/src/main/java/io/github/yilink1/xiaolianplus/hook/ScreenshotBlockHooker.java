package io.github.yilink1.xiaolianplus.hook;

import android.app.Service;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;

/**
 * 宿主截屏监听源头阻断器
 *
 * 核心设计哲学：
 * 1. 源头绝对掐死：在 ScreenshotMonitorService 与 ScreenshotObserver 入口处彻底阻断，
 *    宿主底层完全无法向 ContentResolver 注册监听，也收不到任何截屏事件；
 * 2. 绝不触碰下游 UI/弹窗：不依赖任何脆弱易混淆的 Dialog/Activity 类，无生命周期异常与 NPE 风险；
 * 3. 零配置纯静默守护：不给用户制造恐慌，默默杜绝误提交带模块界面的截图至官方后台。
 */
public class ScreenshotBlockHooker {

    private static final String TAG = XiaoLianModule.TAG;

    private static final String CLS_SCREENSHOT_SERVICE = "com.xiaolian.home.service.ScreenshotMonitorService";
    private static final String CLS_SCREENSHOT_OBSERVER = "com.xiaolian.home.utils.ScreenshotObserver";
    private static final String CLS_H5_SCREENSHOT_LISTENER = "com.alipay.mobile.nebulacore.util.H5CommonScreenshotListener";

    private final XiaoLianModule mModule;
    private volatile boolean mServiceHooked = false;
    private volatile boolean mObserverHooked = false;
    private volatile boolean mH5ListenerHooked = false;

    private final Set<Method> mHookedMethods = new HashSet<>();

    public ScreenshotBlockHooker(@NonNull XiaoLianModule module) {
        this.mModule = module;
    }

    public synchronized void initHooks(ClassLoader classLoader) {
        if (classLoader == null) return;

        // 1. 掐死服务源头：ScreenshotMonitorService
        if (!mServiceHooked) {
            hookScreenshotService(classLoader);
        }

        // 2. 掐死观察者源头：ScreenshotObserver
        if (!mObserverHooked) {
            hookScreenshotObserver(classLoader);
        }

        // 3. 掐死 H5 容器内置截屏监听：H5CommonScreenshotListener (mPaaS/Nebula)
        if (!mH5ListenerHooked) {
            hookH5ScreenshotListener(classLoader);
        }
    }

    private void hookScreenshotService(ClassLoader cl) {
        try {
            Class<?> serviceClass = cl.loadClass(CLS_SCREENSHOT_SERVICE);

            // Hook onCreate: 直接阻止初始化并自我停止
            try {
                Method onCreate = serviceClass.getDeclaredMethod("onCreate");
                if (mHookedMethods.add(onCreate)) {
                    mModule.hook(onCreate)
                        .setId("hook_screenshot_service_on_create")
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            mModule.appLog(Log.INFO, TAG, "🛡️ Blocked ScreenshotMonitorService.onCreate -> killing service");
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof Service) {
                                ((Service) thisObj).stopSelf();
                            }
                            return null;
                        });
                }
            } catch (NoSuchMethodException ignored) {}

            // Hook onStartCommand: 直接阻止启动并返回 START_NOT_STICKY
            try {
                Method onStartCommand = serviceClass.getDeclaredMethod("onStartCommand", Intent.class, int.class, int.class);
                if (mHookedMethods.add(onStartCommand)) {
                    mModule.hook(onStartCommand)
                        .setId("hook_screenshot_service_on_start_command")
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            mModule.appLog(Log.INFO, TAG, "🛡️ Blocked ScreenshotMonitorService.onStartCommand -> rejecting start");
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof Service) {
                                ((Service) thisObj).stopSelf();
                            }
                            return Service.START_NOT_STICKY;
                        });
                }
            } catch (NoSuchMethodException ignored) {}

            mServiceHooked = true;
            mModule.appLog(Log.INFO, TAG, "Installed hook on " + CLS_SCREENSHOT_SERVICE);
        } catch (ClassNotFoundException ignored) {
            // 类尚未被该 ClassLoader 装载，留待后续阶段
        } catch (Throwable t) {
            mModule.appLog(Log.WARN, TAG, "Failed to hook " + CLS_SCREENSHOT_SERVICE, t);
        }
    }

    private void hookScreenshotObserver(ClassLoader cl) {
        try {
            Class<?> observerClass = cl.loadClass(CLS_SCREENSHOT_OBSERVER);
            int count = 0;

            for (Class<?> c = observerClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    String name = m.getName().toLowerCase();
                    boolean shouldBlock = name.contains("start")
                            || name.contains("register")
                            || name.contains("listen")
                            || name.contains("observe")
                            || name.contains("onchange")
                            || name.contains("onscreenshot")
                            || name.contains("queryscreenshot");

                    if (shouldBlock && mHookedMethods.add(m)) {
                        m.setAccessible(true);
                        final String methodName = m.getName();
                        final Class<?> retType = m.getReturnType();
                        mModule.hook(m)
                            .setId("hook_screenshot_observer_" + methodName + "_" + count)
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                mModule.appLog(Log.INFO, TAG, "🛡️ Blocked ScreenshotObserver." + methodName);
                                if (retType == boolean.class) return false;
                                if (retType == int.class || retType == long.class) return 0;
                                return null;
                            });
                        count++;
                    }
                }
            }

            mObserverHooked = true;
            mModule.appLog(Log.INFO, TAG, "Installed " + count + " hook(s) on " + CLS_SCREENSHOT_OBSERVER);
        } catch (ClassNotFoundException ignored) {
            // 留待后续阶段
        } catch (Throwable t) {
            mModule.appLog(Log.WARN, TAG, "Failed to hook " + CLS_SCREENSHOT_OBSERVER, t);
        }
    }

    private void hookH5ScreenshotListener(ClassLoader cl) {
        try {
            Class<?> h5ListenerClass = cl.loadClass(CLS_H5_SCREENSHOT_LISTENER);
            int count = 0;

            for (Method m : h5ListenerClass.getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if ((name.contains("register") || name.contains("start") || name.contains("onchange") || name.contains("onscreenshot"))
                        && mHookedMethods.add(m)) {
                    m.setAccessible(true);
                    final String methodName = m.getName();
                    final Class<?> retType = m.getReturnType();
                    mModule.hook(m)
                        .setId("hook_h5_screenshot_listener_" + methodName + "_" + count)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            mModule.appLog(Log.INFO, TAG, "🛡️ Blocked H5CommonScreenshotListener." + methodName);
                            if (retType == boolean.class) return false;
                            return null;
                        });
                    count++;
                }
            }

            mH5ListenerHooked = true;
            mModule.appLog(Log.INFO, TAG, "Installed " + count + " hook(s) on " + CLS_H5_SCREENSHOT_LISTENER);
        } catch (ClassNotFoundException ignored) {
            // 正常现象，Nebula 容器可能未装载
        } catch (Throwable t) {
            mModule.appLog(Log.WARN, TAG, "Failed to hook " + CLS_H5_SCREENSHOT_LISTENER, t);
        }
    }
}
