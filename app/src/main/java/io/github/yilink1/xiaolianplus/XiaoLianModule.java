package io.github.yilink1.xiaolianplus;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.yilink1.xiaolianplus.hook.DirectLaunchHooker;
import io.github.yilink1.xiaolianplus.hook.MinePageHooker;
import io.github.yilink1.xiaolianplus.hook.SplashAdHooker;
import io.github.yilink1.xiaolianplus.hook.SplashDelayHooker;
import io.github.yilink1.xiaolianplus.hook.SplashStartHooker;
import io.github.yilink1.xiaolianplus.hook.WaterDispenserHooker;

public class XiaoLianModule extends XposedModule {

    public static final String TAG = "XiaoLianPlus";
    public static final String TARGET_PACKAGE = "com.xiaolian.prometheus";

    private static volatile Context sAppContext = null;

    public static Context getAppContext() {
        return sAppContext;
    }

    public static void setAppContext(Context context) {
        if (context != null && sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }
    }

    private String mProcessName = null;
    private volatile boolean mHooked = false;
    private WaterDispenserHooker mWaterHooker = null;
    private volatile boolean mWaterHooked = false;
    private MinePageHooker mMinePageHooker = null;
    private DirectLaunchHooker mDirectLaunchHooker = null;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        super.onModuleLoaded(param);
        mProcessName = param.getProcessName();
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        super.onPackageReady(param);

        // 1. 包名过滤
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        // 2. 进程过滤：排除子进程（如 :tools 后台进程），只针对主进程
        if (mProcessName != null && !TARGET_PACKAGE.equals(mProcessName)) {
            log(Log.INFO, TAG, "Skipping non-main process: " + mProcessName);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String appProcess = Application.getProcessName();
            if (!TARGET_PACKAGE.equals(appProcess)) {
                log(Log.INFO, TAG, "Skipping non-main process: " + appProcess);
                return;
            }
        }

        log(Log.INFO, TAG, "XiaoLianPlus active for main process (API " + getApiVersion() + ")");

        mWaterHooker = new WaterDispenserHooker(this);
        mMinePageHooker = new MinePageHooker(this);
        mDirectLaunchHooker = new DirectLaunchHooker(this);

        // 3. 第一层：直接尝试用当前类加载器 Hook
        tryHookSplash(param.getClassLoader());
        tryHookWaterDispenser(param.getClassLoader());
        tryHookMinePage(param.getClassLoader());
        tryHookDirectLaunch(param.getClassLoader());

        // 4. 第二层：Hook ContextWrapper.attachBaseContext 解决 MultiDex 晚装载
        hookContextWrapperAttach();

        // 5. 第三层：Hook Instrumentation.callActivityOnCreate 动态拦截 Activity 启动
        hookInstrumentationCallActivityOnCreate();
    }

    /**
     * 解决 MultiDex / 晚期加载：attachBaseContext 声明在 ContextWrapper 中，必须在 ContextWrapper 上查找
     */
    private void hookContextWrapperAttach() {
        try {
            Method attachBaseContext = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
            hook(attachBaseContext)
                .setId("hook_context_wrapper_attach_base")
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();

                    Context baseContext = (Context) chain.getArg(0);
                    Object thisObj = chain.getThisObject();
                    if (baseContext != null) {
                        setAppContext(baseContext);
                    } else if (thisObj instanceof Context) {
                        setAppContext((Context) thisObj);
                    }

                    ClassLoader targetCl = null;

                    if (thisObj instanceof Application) {
                        targetCl = ((Application) thisObj).getClassLoader();
                    } else if (baseContext != null) {
                        targetCl = baseContext.getClassLoader();
                    }

                    if (targetCl != null) {
                        if (!mHooked) {
                            log(Log.INFO, TAG, "ContextWrapper.attachBaseContext completed, attempting hookSplash...");
                            boolean success = tryHookSplash(targetCl);
                            if (success) {
                                log(Log.INFO, TAG, "Splash hook applied in attachBaseContext!");
                            }
                        }
                        tryHookWaterDispenser(targetCl);
                        tryHookMinePage(targetCl);
                        tryHookDirectLaunch(targetCl);
                    }

                    return result;
                });

            log(Log.INFO, TAG, "ContextWrapper.attachBaseContext hook installed successfully");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook ContextWrapper.attachBaseContext", t);
        }
    }

    /**
     * 终极兜底：当任何 Activity 实例化并在 onCreate 前，捕获 Activity 实例与 ClassLoader
     */
    private void hookInstrumentationCallActivityOnCreate() {
        try {
            Method callActivityOnCreate = Instrumentation.class.getDeclaredMethod(
                "callActivityOnCreate",
                Activity.class,
                Bundle.class
            );

            hook(callActivityOnCreate)
                .setId("hook_instrumentation_call_activity_on_create")
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Activity activity = (Activity) chain.getArg(0);
                    if (activity != null) {
                        setAppContext(activity);
                        String className = activity.getClass().getName();
                        if (className.contains("SplashActivity")) {
                            SplashStartHooker.reset();
                            SplashStartHooker.sCurrentSplashActivity = new WeakReference<>(activity);

                            if (!mHooked) {
                                log(Log.INFO, TAG, "Detected SplashActivity instance launched: " + className);
                                boolean success = tryHookSplash(activity.getClass().getClassLoader());
                                if (success) {
                                    log(Log.INFO, TAG, "Splash hook applied dynamically on Activity launch!");
                                }
                            }
                        } else if (className.contains("MriverActivity") || className.contains("Mriver")) {
                            log(Log.INFO, TAG, "Detected MriverActivity launched: " + className);
                            if (mWaterHooker != null) {
                                mWaterHooker.onMriverActivityCreated(activity);
                            }
                        } else if (className.contains("HomeActivity")) {
                            log(Log.INFO, TAG, "Detected HomeActivity launched: " + className);
                            checkHostVersion(activity);
                            if (mMinePageHooker != null) {
                                mMinePageHooker.onHomeActivityCreated(activity);
                            }
                            if (mDirectLaunchHooker != null) {
                                mDirectLaunchHooker.onHomeActivityReady(activity, activity.getClass().getClassLoader());
                            }
                        }
                    }
                    return chain.proceed();
                });

            log(Log.INFO, TAG, "Instrumentation.callActivityOnCreate hook installed successfully");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook Instrumentation.callActivityOnCreate", t);
        }
    }

    private synchronized void tryHookDirectLaunch(ClassLoader classLoader) {
        if (classLoader == null || mDirectLaunchHooker == null) return;
        try {
            mDirectLaunchHooker.initHooks(classLoader);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to init DirectLaunchHooker", t);
        }
    }

    private synchronized void tryHookMinePage(ClassLoader classLoader) {
        if (classLoader == null || mMinePageHooker == null) return;
        try {
            mMinePageHooker.initHooks(classLoader);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to init MinePageHooker", t);
        }
    }

    private synchronized void tryHookWaterDispenser(ClassLoader classLoader) {
        if (mWaterHooked || classLoader == null || mWaterHooker == null) {
            return;
        }
        try {
            mWaterHooker.initHooks(classLoader);
            mWaterHooked = true;
            log(Log.INFO, TAG, "WaterDispenser hooks installed successfully!");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to initialize WaterDispenser hooks", t);
        }
    }

    private synchronized boolean tryHookSplash(ClassLoader classLoader) {
        if (mHooked) {
            return true;
        }
        if (classLoader == null) {
            return false;
        }

        try {
            Class<?> splashClass = null;
            // 依据真机日志精确校准：真实类名为 com.xiaolian.launch.ui.SplashActivity
            String[] candidateClassNames = {
                "com.xiaolian.launch.ui.SplashActivity",
                "com.xiaolian.prometheus.launch.ui.SplashActivity",
                "com.xiaolian.prometheus.ui.splash.SplashActivity",
                "com.xiaolian.prometheus.main.SplashActivity",
                "com.xiaolian.prometheus.SplashActivity",
                "com.xiaolian.prometheus.activity.SplashActivity"
            };

            for (String className : candidateClassNames) {
                try {
                    splashClass = classLoader.loadClass(className);
                    log(Log.INFO, TAG, "Found SplashActivity class: " + className);
                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (splashClass == null) {
                return false;
            }

            // 1. 递归在继承链中查找 showAdvSplash(SplashAd) 接口方法（L1 断广告链）
            Method advSplashMethod = findMethodInHierarchy(splashClass, "showAdvSplash", 1);

            // 2. 递归在继承链中查找 showNomrmalSplash()（兼容原版错别字 Nomrmal 与修复后的 Normal）
            Method normalSplashMethod = findNormalSplashMethodInHierarchy(splashClass);

            if (advSplashMethod != null) {
                hook(advSplashMethod)
                    .setId("hook_splash_ad_replacement")
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new SplashAdHooker(this, normalSplashMethod));

                mHooked = true;
                log(Log.INFO, TAG, "Hook installed successfully on: " + advSplashMethod);

                // 3. 挂载加速器：包括源头拦截 (SplashPresenter.splashStart) 与延时压缩 (Timer.schedule)
                hookSplashAccelerators(classLoader, normalSplashMethod);

                return true;
            } else {
                log(Log.WARN, TAG, "Method showAdvSplash(1 param) not found on hierarchy of " + splashClass.getName());
            }

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Error during splash hook setup", t);
        }

        return false;
    }

    /**
     * 极速加速器挂载（源头拦截 + 延时清零）：
     * 1. 拦截 SplashPresenter.splashStart() 彻底掐断 Room 查库死等；
     * 2. 拦截 Timer.schedule() 将 500ms 延迟在主线程中立即调度执行；
     * 3. 拦截 SplashPresenter.splashDelay() 建立作用域哨兵。
     */
    private void hookSplashAccelerators(ClassLoader classLoader, Method normalSplashMethod) {
        // (1) Hook java.util.Timer.schedule(TimerTask, long) - 核心加速点
        try {
            Method timerSchedule = Timer.class.getDeclaredMethod("schedule", TimerTask.class, long.class);
            hook(timerSchedule)
                .setId("hook_timer_schedule_accelerator")
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(new SplashDelayHooker.TimerScheduleHooker(this));
            log(Log.INFO, TAG, "Timer.schedule accelerator hook installed successfully");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook Timer.schedule", t);
        }

        // (2) Hook SplashPresenter 方法群
        try {
            String[] presenterCandidates = {
                "com.xiaolian.launch.ui.SplashPresenter",
                "com.xiaolian.launch.presenter.SplashPresenter",
                "com.xiaolian.prometheus.launch.ui.SplashPresenter",
                "com.xiaolian.prometheus.ui.splash.SplashPresenter"
            };

            for (String pName : presenterCandidates) {
                try {
                    Class<?> pClass = classLoader.loadClass(pName);

                    // A. 方案 B 源头拦截：Hook splashStart() 掐断 Room 查库死等（实证为 1 个参数）
                    Method startMethod = findMethodInHierarchy(pClass, "splashStart", 1);
                    if (startMethod == null) {
                        startMethod = findMethodInHierarchy(pClass, "splashStart", 0);
                    }
                    if (startMethod != null) {
                        hook(startMethod)
                            .setId("hook_splash_start_source_accelerator")
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new SplashStartHooker(this, normalSplashMethod));
                        log(Log.INFO, TAG, "Hooked SplashPresenter.splashStart (Source 0s Accelerator) on: " + pName);
                    } else {
                        StringBuilder sb = new StringBuilder("SplashPresenter methods: ");
                        for (Method m : pClass.getDeclaredMethods()) {
                            sb.append(m.getName()).append("(").append(m.getParameterCount()).append("), ");
                        }
                        log(Log.INFO, TAG, sb.toString());
                    }

                    // B. L3 延时拦截：Hook splashDelay() 哨兵
                    Method delayMethod = findMethodInHierarchy(pClass, "splashDelay", 0);
                    if (delayMethod != null) {
                        hook(delayMethod)
                            .setId("hook_splash_delay_method")
                            .setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new SplashDelayHooker(this));
                        log(Log.INFO, TAG, "Hooked SplashPresenter.splashDelay on: " + pName);
                    }

                    break;
                } catch (ClassNotFoundException ignored) {
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to hook SplashPresenter methods", t);
        }
    }

    /**
     * 递归遍历类及其父类，查找指定名称和参数个数的方法
     */
    private Method findMethodInHierarchy(Class<?> clazz, String methodName, int paramCount) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                if (methodName.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * 递归遍历类及其父类，查找原生无广告开屏方法（兼容官方源码拼写错字 showNomrmalSplash 及纠偏 showNormalSplash）
     */
    private Method findNormalSplashMethodInHierarchy(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method m : current.getDeclaredMethods()) {
                String name = m.getName();
                if (("showNomrmalSplash".equals(name) || "showNormalSplash".equals(name))
                        && m.getParameterCount() == 0) {
                    return m;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static volatile boolean sVersionChecked = false;

    private void checkHostVersion(Activity activity) {
        if (sVersionChecked || activity == null) return;
        sVersionChecked = true;
        try {
            Context context = activity.getApplicationContext();
            if (io.github.yilink1.xiaolianplus.config.ModuleConfig.isIgnoreLowVersionPrompt(context)) {
                return;
            }
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            String versionName = pi.versionName;
            if (versionName != null && isVersionLowerThan(versionName, "1.5.7")) {
                activity.runOnUiThread(() -> {
                    android.widget.Toast.makeText(context,
                            "【笑联Plus】当前版本低于 1.5.7，部分功能可能不会生效",
                            android.widget.Toast.LENGTH_LONG).show();
                });
                log(Log.WARN, TAG, "Detected host version " + versionName + " < 1.5.7");
            } else {
                log(Log.INFO, TAG, "Host version verified: " + versionName);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Failed to check host version", t);
        }
    }

    private static boolean isVersionLowerThan(String current, String target) {
        try {
            String[] cParts = current.split("[^0-9]+");
            String[] tParts = target.split("[^0-9]+");
            int len = Math.max(cParts.length, tParts.length);
            for (int i = 0; i < len; i++) {
                int c = (i < cParts.length && !cParts[i].isEmpty()) ? Integer.parseInt(cParts[i]) : 0;
                int t = (i < tParts.length && !tParts[i].isEmpty()) ? Integer.parseInt(tParts[i]) : 0;
                if (c < t) return true;
                if (c > t) return false;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
