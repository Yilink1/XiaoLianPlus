package io.github.yilink1.xiaolianplus.hook;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.TimerTask;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;

/**
 * L3 加速器：拦截 SplashPresenter.splashDelay() 与 Timer.schedule()。
 * 规避对匿名内部类构造器的脆弱反射，直接将宿主自身构造好的 TimerTask 投递至主线程立即执行，
 * 将 500ms 纯视觉死等物理压缩至 0ms。
 */
public class SplashDelayHooker implements XposedInterface.Hooker {

    private final XiaoLianModule mModule;
    public static volatile boolean sInSplashDelay = false;

    public SplashDelayHooker(@NonNull XiaoLianModule module) {
        this.mModule = module;
    }

    @Override
    public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
        android.content.Context ctx = SplashStartHooker.sCurrentSplashActivity != null ? SplashStartHooker.sCurrentSplashActivity.get() : XiaoLianModule.getAppContext();
        if (ctx != null && !io.github.yilink1.xiaolianplus.config.ModuleConfig.isSplashAdBlockEnabled(ctx)) {
            return chain.proceed();
        }

        mModule.log(Log.INFO, XiaoLianModule.TAG, "Entering splashDelay(), enabling 0ms accelerator guard...");
        try {
            sInSplashDelay = true;
            // 执行宿主原本的 splashDelay()，由其自身正常创建 TimerTask
            return chain.proceed();
        } finally {
            sInSplashDelay = false;
            mModule.log(Log.INFO, XiaoLianModule.TAG, "Exited splashDelay()");
        }
    }

    /**
     * Timer.schedule(TimerTask, long) 拦截器：
     * 当处于 splashDelay 调用栈或检测到 SplashPresenter 的 500ms 任务时，
     * 直接通过主线程立即调度 task.run()，将延时压为 0ms。
     */
    public static class TimerScheduleHooker implements XposedInterface.Hooker {
        private final XiaoLianModule mModule;

        public TimerScheduleHooker(@NonNull XiaoLianModule module) {
            this.mModule = module;
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            try {
                android.content.Context ctx = SplashStartHooker.sCurrentSplashActivity != null ? SplashStartHooker.sCurrentSplashActivity.get() : XiaoLianModule.getAppContext();
                if (ctx != null && !io.github.yilink1.xiaolianplus.config.ModuleConfig.isSplashAdBlockEnabled(ctx)) {
                    return chain.proceed();
                }

                Object taskObj = chain.getArg(0);
                long delay = (Long) chain.getArg(1);

                boolean isSplashTask = (taskObj != null && taskObj.getClass().getName().contains("SplashPresenter"));
                if (sInSplashDelay || (isSplashTask && (delay == 500L || delay == 0x1F4L))) {
                    mModule.log(Log.INFO, XiaoLianModule.TAG, "Intercepted Timer.schedule(500ms) from SplashPresenter -> posting to MainLooper immediately (0ms)!");

                    if (taskObj instanceof Runnable) {
                        Runnable task = (Runnable) taskObj;
                        new Handler(Looper.getMainLooper()).post(task::run);
                        // 不进入底层 Timer 等待 500ms，直接返回
                        return null;
                    }
                }
            } catch (Throwable t) {
                mModule.log(Log.WARN, XiaoLianModule.TAG, "Timer schedule accelerator fallback to native 500ms", t);
            }

            // 异常兜底：走原生 Timer 500ms 调度
            return chain.proceed();
        }
    }
}
