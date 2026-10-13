package io.github.yilink1.xiaolianplus.shortcut;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import io.github.yilink1.xiaolianplus.XiaoLianModule;
import io.github.yilink1.xiaolianplus.config.ModuleConfig;
import io.github.yilink1.xiaolianplus.hook.DirectLaunchHooker;

/**
 * 桌面长按快捷方式 (App Shortcuts) 管理器
 * 支持 Android 7.1 (API 25) 及以上系统的动态桌面快捷方式
 */
public class AppShortcutHelper {

    private static final String TAG = XiaoLianModule.TAG;

    public static final String ACTION_SHORTCUT = "io.github.yilink1.xiaolianplus.ACTION_SHORTCUT";
    public static final String EXTRA_SHORTCUT_TARGET = "shortcut_target";
    public static final String EXTRA_SHORTCUT_DEVICE_NAME = "shortcut_device_name";

    public static final String TARGET_SCAN = "scan";
    public static final String TARGET_DEVICE = "device";

    private static final String ID_SCAN = "xiaolian_shortcut_scan";

    // 暂存从 SplashActivity 传递给 HomeActivity 的快捷意图
    public static volatile String sPendingShortcutTarget = null;
    public static volatile String sPendingShortcutDeviceName = null;

    /**
     * 捕获传入的快捷方式意图（通常在 SplashActivity 或 HomeActivity onCreate 时调用）
     */
    public static boolean captureShortcutIntent(Intent intent) {
        if (intent == null) return false;
        if (ACTION_SHORTCUT.equals(intent.getAction())) {
            String target = intent.getStringExtra(EXTRA_SHORTCUT_TARGET);
            if (target != null && !target.trim().isEmpty()) {
                sPendingShortcutTarget = target.trim();
                sPendingShortcutDeviceName = intent.getStringExtra(EXTRA_SHORTCUT_DEVICE_NAME);
                Log.i(TAG, "Captured pending shortcut target: " + sPendingShortcutTarget + " device: " + sPendingShortcutDeviceName);
                return true;
            }
        }
        return false;
    }

    /**
     * 消费暂存的快捷方式目标（单次消费）
     */
    public static String consumePendingTarget() {
        String target = sPendingShortcutTarget;
        sPendingShortcutTarget = null;
        return target;
    }

    public static String consumePendingDeviceName() {
        String device = sPendingShortcutDeviceName;
        sPendingShortcutDeviceName = null;
        return device;
    }

    public interface ShortcutDispatcher {
        void onDispatchShortcut(Activity homeActivity, String target, String deviceName);
    }

    public static volatile ShortcutDispatcher sShortcutDispatcher = null;

    /**
     * 当应用已在后台运行时，将 SplashActivity 收到的快捷意图直接转发给后台的 HomeActivity
     * 并立即 finish 掉 SplashActivity，避免闪白屏和无响应
     */
    public static boolean forwardToHomeIfAlive(Activity splashActivity) {
        String target = sPendingShortcutTarget;
        String deviceName = sPendingShortcutDeviceName;
        if (target == null || splashActivity == null) return false;

        Activity home = DirectLaunchHooker.sCurrentHomeActivity != null
                ? DirectLaunchHooker.sCurrentHomeActivity.get()
                : null;

        if (home != null && !home.isFinishing() && !home.isDestroyed()) {
            consumePendingTarget(); // 转发成功才真正消费
            consumePendingDeviceName();
            try {
                Log.i(TAG, "HomeActivity is alive in background, bringing to front and executing: " + target);
                Intent toHome = new Intent(splashActivity, home.getClass());
                toHome.setAction(ACTION_SHORTCUT);
                toHome.putExtra(EXTRA_SHORTCUT_TARGET, target);
                if (deviceName != null) {
                    toHome.putExtra(EXTRA_SHORTCUT_DEVICE_NAME, deviceName);
                }
                toHome.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                splashActivity.startActivity(toHome);
                splashActivity.overridePendingTransition(0, 0);
                splashActivity.finish();

                if (sShortcutDispatcher != null) {
                    sShortcutDispatcher.onDispatchShortcut(home, target, deviceName);
                }
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed forwarding shortcut to HomeActivity", t);
            }
        }
        return false;
    }

    /**
     * 更新或刷新动态快捷方式（设备在上，扫一扫永远沉底在最下方）
     */
    public static void updateShortcuts(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) {
            return;
        }

        try {
            ShortcutManager sm = context.getSystemService(ShortcutManager.class);
            if (sm == null) return;

            boolean enabled = ModuleConfig.isAppShortcutsEnabled(context);
            if (!enabled) {
                sm.removeAllDynamicShortcuts();
                Log.i(TAG, "App shortcuts disabled by config, cleared dynamic shortcuts");
                return;
            }

            ComponentName launcherComponent = getLauncherActivityComponent(context);
            if (launcherComponent == null) {
                Log.w(TAG, "Cannot resolve launcher component for shortcuts");
                return;
            }

            List<ShortcutInfo> shortcuts = new ArrayList<>();
            List<String> chosenItems = ModuleConfig.getShortcutCustomItems(context);

            // 最多支持 4 个快捷方式，严格按照列表用户排序赋 Rank
            int rank = 1;
            int devCounter = 0;
            for (String itemKey : chosenItems) {
                if (rank > 4) break;
                if (itemKey == null || itemKey.trim().isEmpty()) continue;
                String key = itemKey.trim();

                if (ModuleConfig.SHORTCUT_ITEM_SCAN.equals(key)) {
                    Intent scanIntent = createShortcutIntent(context, launcherComponent, TARGET_SCAN, null);
                    ShortcutInfo scanShortcut = new ShortcutInfo.Builder(context, ID_SCAN)
                            .setShortLabel("扫一扫")
                            .setLongLabel("扫一扫")
                            .setIcon(createScanIcon(context))
                            .setIntent(scanIntent)
                            .setRank(rank++)
                            .build();
                    shortcuts.add(scanShortcut);
                } else {
                    Intent deviceIntent = createShortcutIntent(context, launcherComponent, TARGET_DEVICE, key);
                    ShortcutInfo deviceShortcut = new ShortcutInfo.Builder(context, "xiaolian_shortcut_dev_" + (devCounter++))
                            .setShortLabel(key)
                            .setLongLabel("直达 " + key)
                            .setIcon(createDeviceIcon(context))
                            .setIntent(deviceIntent)
                            .setRank(rank++)
                            .build();
                    shortcuts.add(deviceShortcut);
                }
            }

            sm.setDynamicShortcuts(shortcuts);
            Log.i(TAG, "Successfully updated " + shortcuts.size() + " dynamic shortcuts from custom order: " + chosenItems);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to update dynamic shortcuts: " + t.getMessage());
        }
    }

    private static Intent createShortcutIntent(Context context, ComponentName component, String target, String deviceName) {
        Intent intent = new Intent(ACTION_SHORTCUT);
        intent.setComponent(component);
        intent.setAction(ACTION_SHORTCUT);
        intent.putExtra(EXTRA_SHORTCUT_TARGET, target);
        if (deviceName != null) {
            intent.putExtra(EXTRA_SHORTCUT_DEVICE_NAME, deviceName);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private static ComponentName getLauncherActivityComponent(Context context) {
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launchIntent != null && launchIntent.getComponent() != null) {
                return launchIntent.getComponent();
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> splashCls = context.getClassLoader().loadClass("com.xiaolian.launch.ui.SplashActivity");
            return new ComponentName(context, splashCls);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 克制、极简、现代原生单色透明图标 ====================

    private static Icon createDeviceIcon(Context context) {
        return Icon.createWithBitmap(drawDeviceBitmap(context));
    }

    private static Icon createScanIcon(Context context) {
        return Icon.createWithBitmap(drawScanBitmap(context));
    }

    private static final int GLYPH_COLOR = Color.parseColor("#374151"); // 现代冷深灰 (Neutral Dark)

    private static Bitmap drawDeviceBitmap(Context context) {
        int size = dp2px(context, 48);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        float cx = size * 0.5f;
        float cy = size * 0.55f;
        float r = size * 0.26f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(GLYPH_COLOR);
        paint.setStyle(Paint.Style.FILL);

        // 极简原生感水滴
        Path path = new Path();
        path.moveTo(cx, cy - r * 1.55f);
        path.cubicTo(cx + r * 1.4f, cy - r * 0.1f, cx + r * 1.25f, cy + r * 1.05f, cx, cy + r * 1.05f);
        path.cubicTo(cx - r * 1.25f, cy + r * 1.05f, cx - r * 1.4f, cy - r * 0.1f, cx, cy - r * 1.55f);
        path.close();
        canvas.drawPath(path, paint);

        return bitmap;
    }

    private static Bitmap drawScanBitmap(Context context) {
        int size = dp2px(context, 48);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(GLYPH_COLOR);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(size * 0.075f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        float left = size * 0.22f;
        float top = size * 0.22f;
        float right = size * 0.78f;
        float bottom = size * 0.78f;
        float len = size * 0.18f;

        // 四角精炼取景器
        Path path = new Path();
        // 左上
        path.moveTo(left, top + len);
        path.lineTo(left, top);
        path.lineTo(left + len, top);
        // 右上
        path.moveTo(right - len, top);
        path.lineTo(right, top);
        path.lineTo(right, top + len);
        // 右下
        path.moveTo(right, bottom - len);
        path.lineTo(right, bottom);
        path.lineTo(right - len, bottom);
        // 左下
        path.moveTo(left + len, bottom);
        path.lineTo(left, bottom);
        path.lineTo(left, bottom - len);
        canvas.drawPath(path, paint);

        // 中心水平极简扫描细线
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(GLYPH_COLOR);
        linePaint.setStrokeWidth(size * 0.05f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left + size * 0.08f, size * 0.5f, right - size * 0.08f, size * 0.5f, linePaint);

        return bitmap;
    }

    private static int dp2px(Context context, float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
