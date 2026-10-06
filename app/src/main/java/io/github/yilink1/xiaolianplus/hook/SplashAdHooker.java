package io.github.yilink1.xiaolianplus.hook;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;

/**
 * 拦截 showAdvSplash(SplashAd)，阻止展示广告并掐断 5 秒 advDelay 倒计时，
 * 替换为调用宿主原生的 showNomrmalSplash()，实现平滑快速导航。
 */
public class SplashAdHooker implements XposedInterface.Hooker {

    private final XiaoLianModule mModule;
    private final Method mNormalSplashMethod;

    public SplashAdHooker(@NonNull XiaoLianModule module, Method normalSplashMethod) {
        this.mModule = module;
        this.mNormalSplashMethod = normalSplashMethod;
        if (this.mNormalSplashMethod != null) {
            this.mNormalSplashMethod.setAccessible(true);
        }
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        Object splashActivity = chain.getThisObject();
        if (splashActivity instanceof android.content.Context) {
            if (!io.github.yilink1.xiaolianplus.config.ModuleConfig.isSplashAdBlockEnabled((android.content.Context) splashActivity)) {
                mModule.log(Log.INFO, XiaoLianModule.TAG, "Splash ad block is disabled by user config, proceeding original");
                return chain.proceed();
            }
        }

        mModule.log(Log.INFO, XiaoLianModule.TAG, "Intercepted showAdvSplash -> bypassing 5s ad timer and redirecting to showNomrmalSplash");
        if (splashActivity != null && mNormalSplashMethod != null) {
            try {
                // 触发宿主原生的无广告路径（隐藏广告视图 + 官方内置平滑导航）
                mNormalSplashMethod.invoke(splashActivity);
                mModule.log(Log.INFO, XiaoLianModule.TAG, "Successfully invoked showNomrmalSplash()");
            } catch (Throwable t) {
                mModule.log(Log.ERROR, XiaoLianModule.TAG, "Failed to invoke showNomrmalSplash()", t);
            }
        } else {
            mModule.log(Log.WARN, XiaoLianModule.TAG, "splashActivity or normalSplashMethod is null, skipping redirection");
        }

        // 核心切入点：绝对不调用 chain.proceed()！
        // 从而阻断原 showAdvSplash 内的 Glide 广告图解码与 Flowable.interval 5 秒倒计时 advDelay()
        return null;
    }
}
