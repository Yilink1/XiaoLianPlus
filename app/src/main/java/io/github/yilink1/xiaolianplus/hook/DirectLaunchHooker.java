package io.github.yilink1.xiaolianplus.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;
import io.github.yilink1.xiaolianplus.config.ModuleConfig;

/**
 * 启动直达策略拦截器与路由控制器
 *
 * 设备数据来源只有一个：首页生活服务网格 IndexFragment.updateLifeService(...) 收到的
 * List<MiniProgram>。每次收到都整体替换本地快照（ModuleConfig.replaceDevices）。
 * 不再嗅探 BaseQuickAdapter / CustomNav / MiniProgram setter，也不再从 NavUtils.goTinyProgram 学习参数。
 *
 * 冷启动直达时，从快照里按用户选中的设备名精确取 (mpaasId, url)，找不到就提示，绝不兜底到别的设备。
 */
public class DirectLaunchHooker {

    private static final String TAG = XiaoLianModule.TAG;

    private static final String CLS_MINI_PROGRAM = "com.xiaolian.data.model.launch.MiniProgram";
    private static final String CLS_NAV_UTILS = "com.xiaolian.ui_module.util.NavUtils";
    private static final String INDEX_FRAGMENT_SIMPLE_NAME = "IndexFragment";
    private static final String METHOD_UPDATE_LIFE_SERVICE = "updateLifeService";

    // MiniProgram 字段候选（按优先级）。mpaasId 取第一个“值为 16 位字符串”的字段。
    // 如果日志(见 describeMini)显示实际用于 goTinyProgram 的是 ogId，把顺序调成 {"ogId", "appId"} 即可。
    private static final String[] NAME_FIELDS = {"title", "name"};
    private static final String[] ID_FIELDS = {"appId", "ogId"};
    private static final String[] URL_FIELDS = {"url"};

    private final XiaoLianModule mModule;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mHasDirectLaunched = new AtomicBoolean(false);

    private volatile boolean mFragmentHooked = false;
    private volatile boolean mNavUtilsHooked = false;
    private final Set<Class<?>> mHookedIndexClasses = new HashSet<>();
    private final Set<Method> mHookedMethods = new HashSet<>();

    public DirectLaunchHooker(@NonNull XiaoLianModule module) {
        this.mModule = module;
    }

    public synchronized void initHooks(ClassLoader classLoader) {
        if (classLoader == null) return;
        hookFragmentAttach(classLoader);
        hookNavUtilsForVerification(classLoader);
    }

    // ==================== 1. 捕获首页生活服务列表 ====================

    /**
     * IndexFragment 的包名无需写死：Hook Fragment.onAttach，发现类简单名为 IndexFragment 的实例时，
     * 再去 Hook 它的 updateLifeService。onAttach 早于数据回调，所以不会错过第一次列表更新。
     */
    private void hookFragmentAttach(ClassLoader cl) {
        if (mFragmentHooked) return;
        for (String fragmentClassName : new String[]{
                "androidx.fragment.app.Fragment", "android.support.v4.app.Fragment"}) {
            try {
                Class<?> fragmentClass = cl.loadClass(fragmentClassName);
                Method onAttach = fragmentClass.getDeclaredMethod("onAttach", Context.class);
                mModule.hook(onAttach)
                    .setId("hook_fragment_on_attach")
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object fragment = chain.getThisObject();
                            if (fragment != null
                                    && INDEX_FRAGMENT_SIMPLE_NAME.equals(fragment.getClass().getSimpleName())) {
                                hookIndexFragment(fragment.getClass());
                            }
                        } catch (Throwable t) {
                            mModule.log(Log.WARN, TAG, "IndexFragment discovery failed", t);
                        }
                        return chain.proceed();
                    });
                mFragmentHooked = true;
                mModule.log(Log.INFO, TAG, "Hooked " + fragmentClassName + ".onAttach for IndexFragment discovery");
                return;
            } catch (Throwable ignored) {
                // 该 Fragment 实现不存在或类暂未可加载，尝试下一个 / 等下次 initHooks
            }
        }
    }

    private synchronized void hookIndexFragment(Class<?> indexClass) {
        if (!mHookedIndexClasses.add(indexClass)) return;

        int hooked = 0;
        for (Class<?> c = indexClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!METHOD_UPDATE_LIFE_SERVICE.equals(m.getName())) continue;
                if (!mHookedMethods.add(m)) continue;
                try {
                    mModule.hook(m)
                        .setId("hook_update_life_service_" + hooked)
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                captureLifeServices(chain.getArgs());
                            } catch (Throwable t) {
                                mModule.log(Log.WARN, TAG, "captureLifeServices failed", t);
                            }
                            return chain.proceed();
                        });
                    hooked++;
                } catch (Throwable t) {
                    mModule.log(Log.WARN, TAG, "Failed to hook " + c.getName() + "." + m.getName(), t);
                }
            }
        }
        mModule.log(Log.INFO, TAG, "Hooked " + hooked + " updateLifeService method(s) on " + indexClass.getName());
    }

    /**
     * 在参数里找出“元素类型恰好是 MiniProgram 的集合”，解析后整体替换快照。
     * 类名是精确比较，不依赖任何名字猜测。
     */
    private void captureLifeServices(List<?> args) {
        if (args == null) return;
        for (Object arg : args) {
            if (!(arg instanceof Collection)) continue;

            boolean sawMini = false;
            List<ModuleConfig.SavedDevice> parsed = new ArrayList<>();
            List<String> dump = new ArrayList<>();
            for (Object item : (Collection<?>) arg) {
                if (item == null || !CLS_MINI_PROGRAM.equals(item.getClass().getName())) continue;
                sawMini = true;
                dump.add(describeMini(item));
                ModuleConfig.SavedDevice dev = parseMiniProgram(item);
                if (dev != null) parsed.add(dev);
            }
            if (!sawMini) continue;

            Context ctx = XiaoLianModule.getAppContext();
            if (ctx == null) return;

            boolean changed = ModuleConfig.replaceDevices(ctx, parsed);
            if (changed) {
                mModule.log(Log.INFO, TAG, "Life-service snapshot replaced (" + parsed.size() + " parsed):");
                for (String line : dump) {
                    mModule.log(Log.INFO, TAG, "  " + line);
                }
            } else if (parsed.isEmpty()) {
                mModule.log(Log.WARN, TAG, "updateLifeService got MiniProgram list but nothing parsable; "
                        + "keep old snapshot. Items: " + dump);
            }
            return;
        }
    }

    private ModuleConfig.SavedDevice parseMiniProgram(Object mini) {
        String name = firstNonEmpty(mini, NAME_FIELDS);
        if (name == null) return null;

        String id = null;
        for (String f : ID_FIELDS) {
            String v = readString(mini, f);
            if (v != null && v.length() == 16) {
                id = v;
                break;
            }
        }
        if (id == null) return null;

        String url = firstNonEmpty(mini, URL_FIELDS);
        return new ModuleConfig.SavedDevice(name, id, url != null ? url : "");
    }

    /** 日志里同时打印所有候选字段，便于核对到底哪个字段才是宿主传给 goTinyProgram 的 id */
    private String describeMini(Object mini) {
        StringBuilder sb = new StringBuilder();
        for (String f : NAME_FIELDS) sb.append(f).append('=').append(readString(mini, f)).append(' ');
        for (String f : ID_FIELDS) sb.append(f).append('=').append(readString(mini, f)).append(' ');
        for (String f : URL_FIELDS) sb.append(f).append('=').append(readString(mini, f)).append(' ');
        return sb.toString().trim();
    }

    private static String firstNonEmpty(Object obj, String[] names) {
        for (String n : names) {
            String v = readString(obj, n);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 先读字段（含父类），读不到再走 getXxx()；非 String 或读取失败返回 null */
    private static String readString(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v instanceof String) return ((String) v).trim();
                break;
            } catch (NoSuchFieldException e) {
                // 继续找父类
            } catch (Throwable t) {
                break;
            }
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Object v = obj.getClass().getMethod(getter).invoke(obj);
            if (v instanceof String) return ((String) v).trim();
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 2. 只读验证：记录宿主真实的 goTinyProgram 调用 ====================

    /**
     * 只打日志，不写任何配置。用户在首页手动点一次设备，就能在 logcat 里对照
     * “宿主实际传的 id/url” 与 “快照里存的 id/url” 是否一致。
     */
    private void hookNavUtilsForVerification(ClassLoader cl) {
        if (mNavUtilsHooked) return;
        try {
            Class<?> navUtils = cl.loadClass(CLS_NAV_UTILS);
            int hooked = 0;
            for (Method m : navUtils.getDeclaredMethods()) {
                if (!"goTinyProgram".equals(m.getName())) continue;
                mModule.hook(m)
                    .setId("hook_nav_utils_go_tiny_program_" + hooked++)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            List<?> a = chain.getArgs();
                            if (a != null && a.size() >= 2) {
                                String match = "(not in snapshot)";
                                Context ctx = XiaoLianModule.getAppContext();
                                if (ctx != null && a.get(0) instanceof String) {
                                    for (ModuleConfig.SavedDevice d : ModuleConfig.getSavedDevices(ctx)) {
                                        if (d.mpaasId.equals(((String) a.get(0)).trim())) {
                                            match = "snapshot: " + d.name + " url=" + d.url;
                                            break;
                                        }
                                    }
                                }
                                mModule.log(Log.INFO, TAG, "[verify] host goTinyProgram id=" + a.get(0)
                                        + " url=" + a.get(1) + " | " + match);
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
            }
            if (hooked > 0) {
                mNavUtilsHooked = true;
                mModule.log(Log.INFO, TAG, "Hooked " + hooked + " NavUtils.goTinyProgram method(s) (log only)");
            }
        } catch (Throwable ignored) {
            // 类暂未可加载，等后续 ClassLoader 就绪时重试
        }
    }

    // ==================== 3. 启动直达 ====================

    /**
     * 在 HomeActivity 启动就绪时分发启动直达策略
     */
    public void onHomeActivityReady(Activity activity, ClassLoader classLoader) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        initHooks(classLoader);

        int mode = ModuleConfig.getDirectLaunchMode(activity);
        if (mode == ModuleConfig.DIRECT_LAUNCH_HOME) {
            return; // 默认首页，不执行跳转
        }

        // 单次冷启动只直达一次：换校区触发的 HomeActivity.recreate() 不会再次跳转
        if (!mHasDirectLaunched.compareAndSet(false, true)) {
            return;
        }

        mModule.log(Log.INFO, TAG, "Processing direct launch strategy: mode=" + mode);

        // 延时 280ms 等待宿主首帧 DecorView 绘制完成，提供最丝滑的视觉转场
        mMainHandler.postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            if (mode == ModuleConfig.DIRECT_LAUNCH_SCAN) {
                performDirectScan(activity);
            } else if (mode == ModuleConfig.DIRECT_LAUNCH_DISPENSER) {
                performDirectDevice(activity, classLoader);
            }
        }, 280);
    }

    private void performDirectScan(Activity activity) {
        mModule.log(Log.INFO, TAG, "Executing direct launch -> SCAN");
        try {
            // 方案 1：直接反射官方 HomeActivity.scanAction()
            Method scanAction = findMethod(activity.getClass(), "scanAction", 0);
            if (scanAction != null) {
                scanAction.setAccessible(true);
                scanAction.invoke(activity);
                mModule.log(Log.INFO, TAG, "Triggered scan via HomeActivity.scanAction()");
                return;
            }
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed HomeActivity.scanAction(), falling back to click", t);
        }

        try {
            // 方案 2：模拟点击底部中心扫一扫按钮 (scan_tab_item)
            int scanTabId = activity.getResources().getIdentifier("scan_tab_item", "id", activity.getPackageName());
            if (scanTabId != 0) {
                View scanTab = activity.findViewById(scanTabId);
                if (scanTab != null) {
                    scanTab.performClick();
                    mModule.log(Log.INFO, TAG, "Triggered scan via scan_tab_item.performClick()");
                    return;
                }
            }
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed scan_tab_item click", t);
        }

        Toast.makeText(activity, "笑联Plus: 启动直达扫码失败", Toast.LENGTH_SHORT).show();
    }

    private void performDirectDevice(Activity activity, ClassLoader classLoader) {
        mModule.log(Log.INFO, TAG, "Executing direct launch -> DEVICE");

        String targetName = ModuleConfig.getSelectedDeviceName(activity);
        ModuleConfig.SavedDevice device = ModuleConfig.getSelectedDevice(activity);

        if (device == null) {
            boolean snapshotEmpty = ModuleConfig.getSavedDevices(activity).isEmpty();
            String tip = snapshotEmpty
                    ? "【笑联Plus】还没有设备列表，请先在首页停留一次让列表加载"
                    : "【笑联Plus】当前校区的设备列表里没有「" + targetName + "」，请在设置里重新选择";
            Toast.makeText(activity, tip, Toast.LENGTH_LONG).show();
            mModule.log(Log.WARN, TAG, "Selected device not in snapshot: " + targetName);
            return;
        }

        String mpaasId = device.mpaasId;
        String url = device.url;
        mModule.log(Log.INFO, TAG, "Launching target device: " + device.name + " (id=" + mpaasId + ", url=" + url + ")");

        try {
            // 方案 1：反射 NavUtils.goTinyProgram(5参)
            Class<?> navUtilsClass = classLoader.loadClass(CLS_NAV_UTILS);
            for (Method m : navUtilsClass.getDeclaredMethods()) {
                if ("goTinyProgram".equals(m.getName()) && m.getParameterCount() == 5) {
                    m.setAccessible(true);
                    m.invoke(null, mpaasId, url, null, null, false);
                    mModule.log(Log.INFO, TAG, "Directly launched device via NavUtils.goTinyProgram: " + device.name);
                    return;
                }
            }
        } catch (Throwable t) {
            mModule.log(Log.WARN, TAG, "Failed NavUtils.goTinyProgram, trying UnionRouter", t);
        }

        try {
            // 方案 2：反射 UnionRouter.route("1", mpaasId, url)
            Class<?> routerClass = classLoader.loadClass("com.xiaolian.ui_module.util.UnionRouter");
            for (Method m : routerClass.getDeclaredMethods()) {
                if ("route".equals(m.getName()) && m.getParameterCount() == 3) {
                    m.setAccessible(true);
                    m.invoke(null, "1", mpaasId, url);
                    mModule.log(Log.INFO, TAG, "Directly launched device via UnionRouter.route: " + device.name);
                    return;
                }
            }
        } catch (Throwable t) {
            mModule.log(Log.ERROR, TAG, "Failed to direct launch device: " + device.name, t);
        }

        Toast.makeText(activity, "【笑联Plus】启动直达 " + device.name + " 失败", Toast.LENGTH_SHORT).show();
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
