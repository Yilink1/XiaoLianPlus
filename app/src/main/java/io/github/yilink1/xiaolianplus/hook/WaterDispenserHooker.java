package io.github.yilink1.xiaolianplus.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;

/**
 * 饮水机 H5 容器全自动 JS 注入器
 * 针对阿里 mPaaS Mriver 容器 (MriverActivityBase) 及底层 UC / 系统原生 WebView，
 * 多层挂载实现无感、鲁棒的 JS 脚本注入。
 */
public class WaterDispenserHooker {

    private static final String TAG = XiaoLianModule.TAG;
    private final XiaoLianModule mModule;
    private String mScriptContent = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Set<Object> mInjectedWebViews = Collections.newSetFromMap(new WeakHashMap<>());

    private volatile boolean mHookMriverDone = false;
    private volatile boolean mHookUcDone = false;
    private volatile boolean mHookSystemDone = false;
    private volatile boolean mHookNebulaDone = false;
    private volatile boolean mHookLoadingDone = false;

    public WaterDispenserHooker(XiaoLianModule module) {
        this.mModule = module;
        loadScript();
        hookGenericDialogShow();
    }

    private void loadScript() {
        // 尝试 1：类加载器资源流
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("assets/water_dispenser.js")) {
            if (is != null) {
                mScriptContent = readStream(is);
                mModule.log(Log.INFO, TAG, "Water dispenser JS loaded from classloader (" + mScriptContent.length() + " chars)");
                return;
            }
        } catch (Throwable ignored) {
        }

        // 尝试 2：从模块自身 APK Zip 中直接读取
        try {
            android.content.pm.ApplicationInfo appInfo = mModule.getModuleApplicationInfo();
            if (appInfo != null && appInfo.sourceDir != null) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(appInfo.sourceDir)) {
                    java.util.zip.ZipEntry entry = zip.getEntry("assets/water_dispenser.js");
                    if (entry != null) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            mScriptContent = readStream(is);
                            mModule.log(Log.INFO, TAG, "Water dispenser JS loaded from module APK zip (" + mScriptContent.length() + " chars)");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed to read JS from module APK zip", t);
        }

        if (mScriptContent == null) {
            mModule.log(Log.ERROR, TAG, "water_dispenser.js could not be loaded from any source!");
        }
    }

    private String readStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * 当检测到 MriverActivity 创建时，直接获取活动实例并启动注入
     */
    public void onMriverActivityCreated(Activity activity) {
        if (activity == null) return;
        mModule.log(Log.INFO, TAG, "onMriverActivityCreated: " + activity.getClass().getName());

        // 尝试使用宿主当前运行时的真实 ClassLoader 安装未完成的 Hook
        initHooks(activity.getClass().getClassLoader());

        // 立即调度 View 树多频次扫描注入
        scheduleViewTreeScan(activity);
    }

    public void initHooks(ClassLoader classLoader) {
        if (classLoader == null) return;

        // 1. Hook MriverActivityBase 的生命周期（Activity 树扫描注入）
        if (!mHookMriverDone) {
            hookMriverActivity(classLoader);
        }

        // 2. Hook 系统原生 WebView (android.webkit.WebView / WebViewClient)
        if (!mHookSystemDone) {
            hookSystemWebView();
        }

        // 3. Hook 阿里 UC 浏览器内核 (com.uc.webview.export.WebView / WebViewClient)
        if (!mHookUcDone) {
            hookUcWebView(classLoader);
        }

        // 4. Hook 阿里 APWebView (com.alipay.mobile.nebula.webview.APWebView)
        if (!mHookNebulaDone) {
            hookNebulaWebView(classLoader);
        }

        // 5. Hook 阿里 mPaaS H5LoadingDialog 解除全屏触控阻断（允许加载中直接点击扫码）
        if (!mHookLoadingDone) {
            hookLoadingDialog(classLoader);
        }

        // 6. 全局无条件强制开启 WebContents 调试（同时解锁系统原生与阿里 UC 内核调试，支持 Chrome 开发者工具）
        enableGlobalWebViewDebugging(classLoader);
    }

    private void enableGlobalWebViewDebugging(ClassLoader classLoader) {
        Context ctx = XiaoLianModule.getAppContext();
        if (ctx != null && !io.github.yilink1.xiaolianplus.config.ModuleConfig.isWebviewDebugEnabled(ctx)) {
            mModule.log(Log.INFO, TAG, "Webview debugging disabled by user config");
            return;
        }

        try {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
            mModule.log(Log.INFO, TAG, "Global Android WebView debugging enabled");
        } catch (Throwable ignored) {
        }

        String[] ucCandidates = {
            "com.uc.webview.export.WebView",
            "com.alipay.mobile.nebulauc.impl.UCWebView"
        };
        for (String ucClass : ucCandidates) {
            try {
                Class<?> clazz = classLoader.loadClass(ucClass);
                Method setDebug = clazz.getMethod("setWebContentsDebuggingEnabled", boolean.class);
                setDebug.invoke(null, true);
                mModule.log(Log.INFO, TAG, "Global UC/mPaaS WebView debugging enabled for " + ucClass);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 全局拦截通用 Dialog.show：当展示 H5LoadingDialog 时，将窗口标志位设为非模态，移除遮罩点击拦截
     */
    private void hookGenericDialogShow() {
        try {
            Method showMethod = android.app.Dialog.class.getDeclaredMethod("show");
            mModule.hook(showMethod)
                .setId("hook_generic_dialog_show_filter")
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object res = chain.proceed();
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof android.app.Dialog) {
                        String name = thisObj.getClass().getName();
                        if (name.contains("LoadingDialog") || name.contains("NebulaLoading")) {
                            unlockDialogTouch((android.app.Dialog) thisObj, name);
                        }
                    }
                    return res;
                });
        } catch (Throwable ignored) {
        }
    }

    private void hookLoadingDialog(ClassLoader classLoader) {
        try {
            Class<?> dialogClass = classLoader.loadClass("com.mpaas.mriver.integration.view.loading.H5LoadingDialog");
            Method showMethod = findMethod(dialogClass, "show", 0);
            if (showMethod != null) {
                mModule.hook(showMethod)
                    .setId("hook_h5_loading_dialog_show")
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object res = chain.proceed();
                        Object thisObj = chain.getThisObject();
                        if (thisObj instanceof android.app.Dialog) {
                            unlockDialogTouch((android.app.Dialog) thisObj, "H5LoadingDialog");
                        }
                        return res;
                    });
                mHookLoadingDone = true;
                mModule.log(Log.INFO, TAG, "Hooked H5LoadingDialog.show -> touch pass-through enabled!");
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed to hook H5LoadingDialog", t);
        }
    }

    private void unlockDialogTouch(android.app.Dialog dialog, String tag) {
        try {
            if (dialog != null && dialog.getClass().getName().startsWith("io.github.yilink1.xiaolianplus")) {
                return;
            }
            if (!io.github.yilink1.xiaolianplus.config.ModuleConfig.isLoadingPassThroughEnabled(dialog.getContext())) {
                return;
            }
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                );
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                mModule.log(Log.INFO, TAG, "Unlocked touch pass-through on Dialog: " + tag);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 1. 拦截 MriverActivityBase 页面生命周期
     */
    private void hookMriverActivity(ClassLoader classLoader) {
        String[] mriverActivities = {
            "com.mpaas.mriver.integration.MriverActivityBase$Main",
            "com.mpaas.mriver.integration.MriverActivityBase",
            "com.alipay.mobile.nebulacore.ui.H5Activity"
        };

        for (String actName : mriverActivities) {
            try {
                Class<?> actClass = classLoader.loadClass(actName);

                // Hook onResume
                Method onResume = findMethod(actClass, "onResume", 0);
                if (onResume != null) {
                    mModule.hook(onResume)
                        .setId("hook_mriver_resume_" + Math.abs(actName.hashCode()))
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object res = chain.proceed();
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof Activity) {
                                scheduleViewTreeScan((Activity) thisObj);
                            }
                            return res;
                        });
                }

                // Hook onWindowFocusChanged
                Method onFocus = findMethod(actClass, "onWindowFocusChanged", 1);
                if (onFocus != null) {
                    mModule.hook(onFocus)
                        .setId("hook_mriver_focus_" + Math.abs(actName.hashCode()))
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object res = chain.proceed();
                            Boolean hasFocus = (Boolean) chain.getArg(0);
                            if (Boolean.TRUE.equals(hasFocus)) {
                                Object thisObj = chain.getThisObject();
                                if (thisObj instanceof Activity) {
                                    scheduleViewTreeScan((Activity) thisObj);
                                }
                            }
                            return res;
                        });
                }

                mHookMriverDone = true;
                mModule.log(Log.INFO, TAG, "Hooked Mriver container Activity: " + actName);
                break;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                mModule.log(Log.WARN, TAG, "Failed to hook " + actName, t);
            }
        }
    }

    /**
     * 在主线程按阶梯时间延迟扫描 Activity 的 View 树以发现 WebView 并执行注入
     */
    public void scheduleViewTreeScan(Activity activity) {
        long[] delays = { 300, 800, 1800, 3200 };
        for (long delay : delays) {
            mMainHandler.postDelayed(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    View decor = activity.getWindow().getDecorView();
                    scanAndInject(decor, 0);
                } catch (Throwable t) {
                    mModule.log(Log.WARN, TAG, "Error during view tree scan", t);
                }
            }, delay);
        }
    }

    private void scanAndInject(View root, int depth) {
        if (root == null) return;
        String name = root.getClass().getName();

        if (isWebViewLike(root)) {
            mModule.log(Log.INFO, TAG, "Found WebView in tree [depth " + depth + "]: " + name);
            inject(root);
        }

        // 尝试探测内部包含的 WebView 属性或 getter
        try {
            Method getWebView = root.getClass().getMethod("getWebView");
            Object inner = getWebView.invoke(root);
            if (inner != null && isWebViewLike(inner)) {
                mModule.log(Log.INFO, TAG, "Found inner WebView via getWebView(): " + inner.getClass().getName());
                inject(inner);
            }
        } catch (Throwable ignored) {
        }

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                scanAndInject(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private boolean isWebViewLike(Object obj) {
        if (obj == null) return false;
        if (obj instanceof android.webkit.WebView) return true;
        String name = obj.getClass().getName().toLowerCase();
        if (name.contains("webview")) return true;

        try {
            obj.getClass().getMethod("loadUrl", String.class);
            return true;
        } catch (NoSuchMethodException ignored) {
        }

        try {
            for (Method m : obj.getClass().getMethods()) {
                if ("evaluateJavascript".equals(m.getName()) && m.getParameterCount() == 2) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    /**
     * 2. Hook 系统原生 WebView
     */
    private void hookSystemWebView() {
        try {
            Method onPageFinished = android.webkit.WebViewClient.class.getDeclaredMethod(
                "onPageFinished", android.webkit.WebView.class, String.class
            );
            mModule.hook(onPageFinished)
                .setId("hook_system_webview_on_page_finished")
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object res = chain.proceed();
                    android.webkit.WebView webView = (android.webkit.WebView) chain.getArg(0);
                    inject(webView);
                    return res;
                });
            mHookSystemDone = true;
            mModule.log(Log.INFO, TAG, "Hooked android.webkit.WebViewClient.onPageFinished");
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed to hook system WebViewClient", t);
        }
    }

    /**
     * 3. Hook 阿里 UC 内核 WebView
     */
    private void hookUcWebView(ClassLoader classLoader) {
        try {
            Class<?> ucClient = classLoader.loadClass("com.uc.webview.export.WebViewClient");
            Method onPageFinished = findMethod(ucClient, "onPageFinished", 2);
            if (onPageFinished != null) {
                mModule.hook(onPageFinished)
                    .setId("hook_uc_webview_on_page_finished")
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object res = chain.proceed();
                        Object webView = chain.getArg(0);
                        inject(webView);
                        return res;
                    });
                mHookUcDone = true;
                mModule.log(Log.INFO, TAG, "Hooked UC WebViewClient.onPageFinished");
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed to hook UC WebViewClient", t);
        }
    }

    /**
     * 4. 阿里 Nebula APWebView 为抽象接口，禁止直接 Hook 避免抛出 Cannot hook abstract methods。
     * 注入已由 scheduleViewTreeScan 阶梯扫描与通用 WebView 容器完整兜底。
     */
    private void hookNebulaWebView(ClassLoader classLoader) {
        mHookNebulaDone = true;
    }

    /**
     * 统一通用注入方法，自动适配系统原生 WebView、UC WebView 及 APWebView
     */
    public void inject(Object webView) {
        if (webView == null || mScriptContent == null || mScriptContent.isEmpty()) {
            return;
        }

        if (webView instanceof View) {
            Context ctx = ((View) webView).getContext();
            if (!io.github.yilink1.xiaolianplus.config.ModuleConfig.isWaterDispenserEnabled(ctx)) {
                return;
            }
        }

        mMainHandler.post(() -> {
            try {
                // 若用户开启了网页调试，则反射激活 WebContents 调试开关（支持 PC chrome://inspect 调试）
                Context targetCtx = webView instanceof View ? ((View) webView).getContext() : XiaoLianModule.getAppContext();
                if (io.github.yilink1.xiaolianplus.BuildConfig.DEBUG && targetCtx != null && io.github.yilink1.xiaolianplus.config.ModuleConfig.isWebviewDebugEnabled(targetCtx)) {
                    try {
                        Method setDebug = webView.getClass().getMethod("setWebContentsDebuggingEnabled", boolean.class);
                        setDebug.invoke(null, true);
                    } catch (Throwable ignored) {
                    }
                }

                boolean autoConfirm = targetCtx != null && io.github.yilink1.xiaolianplus.config.ModuleConfig.isWaterAutoConfirmEnabled(targetCtx);
                boolean holdToSettle = targetCtx != null && io.github.yilink1.xiaolianplus.config.ModuleConfig.isWaterHoldToSettleEnabled(targetCtx);
                boolean desensitize = targetCtx != null && io.github.yilink1.xiaolianplus.config.ModuleConfig.isWaterDesensitizeEnabled(targetCtx);
                String scriptToRun = "window.__XL_CONFIG__ = { autoConfirm: " + autoConfirm + ", holdToSettle: " + holdToSettle + ", desensitize: " + desensitize + " };\n" + mScriptContent;

                boolean evaluated = false;

                // 方案 A: 反射调用 evaluateJavascript(String, ValueCallback)
                try {
                    Method evalMethod = webView.getClass().getMethod("evaluateJavascript", String.class, android.webkit.ValueCallback.class);
                    evalMethod.invoke(webView, scriptToRun, null);
                    logInjectedOnce(webView, "evaluateJavascript");
                    evaluated = true;
                } catch (Throwable ignored) {
                }

                // 方案 B: 降级调用 loadUrl("javascript:...")
                if (!evaluated) {
                    try {
                        Method loadUrlMethod = webView.getClass().getMethod("loadUrl", String.class);
                        loadUrlMethod.invoke(webView, "javascript:" + scriptToRun);
                        logInjectedOnce(webView, "loadUrl");
                    } catch (Throwable ignored) {
                    }
                }

            } catch (Throwable t) {
                mModule.log(Log.WARN, TAG, "Failed to inject script into " + webView.getClass().getName(), t);
            }
        });
    }

    private void logInjectedOnce(Object webView, String method) {
        if (mInjectedWebViews.add(webView)) {
            mModule.log(Log.INFO, TAG, "Successfully injected water dispenser JS into " + webView.getClass().getName() + " via " + method);
        }
    }

    private Method findMethod(Class<?> clazz, String name, int paramCount) {
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            for (Method m : curr.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            curr = curr.getSuperclass();
        }
        return null;
    }
}
