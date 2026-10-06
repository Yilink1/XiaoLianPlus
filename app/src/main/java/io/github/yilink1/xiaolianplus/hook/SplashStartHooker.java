package io.github.yilink1.xiaolianplus.hook;

import android.app.Activity;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;

/**
 * 方案 B 极速源头拦截器：
 * 拦截 SplashPresenter.splashStart()，直接掐断 Room 数据库异步查询（省去约 800ms~1000ms 白屏死等）。
 * 在下一帧安全触发宿主的 showNomrmalSplash()，配合 L3 实现秒进主页。
 */
public class SplashStartHooker implements XposedInterface.Hooker {

    private final XiaoLianModule mModule;
    private final Method mNormalSplashMethod;
    private static final AtomicBoolean sNavigated = new AtomicBoolean(false);
    public static volatile WeakReference<Activity> sCurrentSplashActivity = null;

    public SplashStartHooker(@NonNull XiaoLianModule module, Method normalSplashMethod) {
        this.mModule = module;
        this.mNormalSplashMethod = normalSplashMethod;
        if (this.mNormalSplashMethod != null) {
            this.mNormalSplashMethod.setAccessible(true);
        }
    }

    public static void reset() {
        sNavigated.set(false);
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        try {
            Object presenter = chain.getThisObject();
            Activity targetActivity = null;

            // 1. 优先从静态捕获的当前 SplashActivity 获取
            if (sCurrentSplashActivity != null) {
                targetActivity = sCurrentSplashActivity.get();
            }

            android.content.Context ctx = targetActivity != null ? targetActivity : XiaoLianModule.getAppContext();
            if (ctx != null && !io.github.yilink1.xiaolianplus.config.ModuleConfig.isSplashAdBlockEnabled(ctx)) {
                mModule.log(Log.INFO, XiaoLianModule.TAG, "Splash ad block is disabled by user config, skipping splashStart fast-track");
                return chain.proceed();
            }

            mModule.log(Log.INFO, XiaoLianModule.TAG, "Intercepted SplashPresenter.splashStart() at source -> cutting off Room DB query!");

            // 2. 备用：从 Presenter 属性中自动查找 Activity 实例（无视 R8 混淆字段名）
            if (targetActivity == null && presenter != null) {
                Class<?> current = presenter.getClass();
                while (current != null && current != Object.class && targetActivity == null) {
                    for (Field f : current.getDeclaredFields()) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(presenter);
                            if (val instanceof Activity) {
                                targetActivity = (Activity) val;
                                break;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    current = current.getSuperclass();
                }
            }

            if (targetActivity != null) {
                Activity activity = targetActivity;
                mModule.log(Log.INFO, XiaoLianModule.TAG, "Resolved target Activity: " + activity.getClass().getName());

                // 核心防踩踏：通过 DecorView.post 让 onCreate 完整退出后，在下一帧立即分发跳转
                activity.getWindow().getDecorView().post(() -> {
                    if (sNavigated.compareAndSet(false, true)) {
                        mModule.log(Log.INFO, XiaoLianModule.TAG, "Triggering fast-track showNomrmalSplash() at next frame (0s instant entry)!");
                        if (mNormalSplashMethod != null) {
                            try {
                                mNormalSplashMethod.invoke(activity);
                            } catch (Throwable t) {
                                mModule.log(Log.ERROR, XiaoLianModule.TAG, "Failed to invoke normal splash method", t);
                            }
                        }
                    }
                });

                // 成功在源头截断：绝对不执行 chain.proceed()，省去 Room DB 查库和 RxJava 调度
                return null;
            } else {
                mModule.log(Log.WARN, XiaoLianModule.TAG, "Target Activity not found, falling back to original splashStart");
            }
        } catch (Throwable t) {
            mModule.log(Log.ERROR, XiaoLianModule.TAG, "Error in SplashStartHooker, falling back", t);
        }

        // 异常回退安全网：如果查找 Activity 失败，正常执行原版查库，由 L1 兜底
        return chain.proceed();
    }
}
