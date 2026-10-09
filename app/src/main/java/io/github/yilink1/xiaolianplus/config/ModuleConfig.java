package io.github.yilink1.xiaolianplus.config;

import android.content.Context;
import android.content.SharedPreferences;

public class ModuleConfig {

    private static final String PREF_NAME = "xiaolian_plus_config";

    public static final String KEY_SPLASH_AD_BLOCK = "key_splash_ad_block";
    public static final String KEY_WATER_DISPENSER = "key_water_dispenser";
    public static final String KEY_LOADING_PASS_THROUGH = "key_loading_pass_through";
    public static final String KEY_WEBVIEW_DEBUG = "key_webview_debug";
    public static final String KEY_DIRECT_LAUNCH_MODE = "key_direct_launch_mode";

    public static final int DIRECT_LAUNCH_HOME = 0;       // 默认首页
    public static final int DIRECT_LAUNCH_SCAN = 1;       // 直达扫码
    public static final int DIRECT_LAUNCH_DISPENSER = 2;  // 直达饮水机

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isSplashAdBlockEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_SPLASH_AD_BLOCK, true);
    }

    public static void setSplashAdBlockEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_SPLASH_AD_BLOCK, enabled).apply();
    }

    public static boolean isWaterDispenserEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_WATER_DISPENSER, true);
    }

    public static void setWaterDispenserEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_WATER_DISPENSER, enabled).apply();
    }

    public static final String KEY_WATER_DEFAULT_ALL_FAV = "key_water_default_all_fav";

    public static boolean isWaterDefaultAllFavEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_WATER_DEFAULT_ALL_FAV, false);
    }

    public static void setWaterDefaultAllFavEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_WATER_DEFAULT_ALL_FAV, enabled).apply();
    }

    /**
     * 实验性/打水页面体验优化功能特性总控（跳过确认、长按0.5秒结算）
     * 置为 true：展示弹窗开关，并允许后台功能生效。
     */
    public static final boolean EXPERIMENTAL_FEATURES_ENABLED = true;

    public static final String KEY_WATER_AUTO_CONFIRM = "key_water_auto_confirm";
    public static final String KEY_WATER_HOLD_TO_SETTLE = "key_water_hold_to_settle";

    public static boolean isWaterAutoConfirmEnabled(Context context) {
        if (!EXPERIMENTAL_FEATURES_ENABLED) return false;
        return getPrefs(context).getBoolean(KEY_WATER_AUTO_CONFIRM, true);
    }

    public static void setWaterAutoConfirmEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_WATER_AUTO_CONFIRM, enabled).apply();
    }

    public static boolean isWaterHoldToSettleEnabled(Context context) {
        if (!EXPERIMENTAL_FEATURES_ENABLED) return false;
        return getPrefs(context).getBoolean(KEY_WATER_HOLD_TO_SETTLE, true);
    }

    public static void setWaterHoldToSettleEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_WATER_HOLD_TO_SETTLE, enabled).apply();
    }

    public static boolean isLoadingPassThroughEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_LOADING_PASS_THROUGH, true);
    }

    public static void setLoadingPassThroughEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_LOADING_PASS_THROUGH, enabled).apply();
    }

    public static boolean isWebviewDebugEnabled(Context context) {
        if (!io.github.yilink1.xiaolianplus.BuildConfig.DEBUG) {
            return false;
        }
        return getPrefs(context).getBoolean(KEY_WEBVIEW_DEBUG, false);
    }

    public static void setWebviewDebugEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_WEBVIEW_DEBUG, enabled).apply();
    }

    public static int getDirectLaunchMode(Context context) {
        return getPrefs(context).getInt(KEY_DIRECT_LAUNCH_MODE, DIRECT_LAUNCH_HOME);
    }

    public static void setDirectLaunchMode(Context context, int mode) {
        getPrefs(context).edit().putInt(KEY_DIRECT_LAUNCH_MODE, mode).apply();
    }

    public static final String KEY_DEV_OPTIONS_UNLOCKED = "key_dev_options_unlocked";
    public static final String KEY_IGNORE_LOW_VERSION_PROMPT = "key_ignore_low_version_prompt";

    public static boolean isDevOptionsUnlocked(Context context) {
        return getPrefs(context).getBoolean(KEY_DEV_OPTIONS_UNLOCKED, false);
    }

    public static void setDevOptionsUnlocked(Context context, boolean unlocked) {
        getPrefs(context).edit().putBoolean(KEY_DEV_OPTIONS_UNLOCKED, unlocked).apply();
    }

    public static boolean isIgnoreLowVersionPrompt(Context context) {
        return getPrefs(context).getBoolean(KEY_IGNORE_LOW_VERSION_PROMPT, false);
    }

    public static void setIgnoreLowVersionPrompt(Context context, boolean ignore) {
        getPrefs(context).edit().putBoolean(KEY_IGNORE_LOW_VERSION_PROMPT, ignore).apply();
    }

    // ==================== 生活服务设备快照 ====================
    //
    // 设计原则：
    //  1. 数据只有一个来源：首页生活服务网格 IndexFragment.updateLifeService(List<MiniProgram>) 的整份列表；
    //  2. 每次收到新列表就【整体替换】快照，而不是按名字增量合并——换校区后旧校区的设备自然消失；
    //  3. 选中项只按“名称”在当前快照里精确匹配，匹配不到就返回 null，由调用方提示用户，绝不兜底到别的设备。

    public static final String KEY_DEVICE_SNAPSHOT_JSON = "key_life_service_snapshot_v2";
    public static final String KEY_SELECTED_DEVICE_NAME = "key_selected_device_name";

    // 旧版本遗留的缓存键（含被污染的数据），写入新快照时顺手清掉
    private static final String[] LEGACY_KEYS = {
        "key_saved_devices_json",
        "key_cached_dispenser_id",
        "key_cached_dispenser_url",
        "key_cached_dispenser_name"
    };

    public static class SavedDevice {
        public String name;
        public String mpaasId;
        public String url;

        public SavedDevice(String name, String mpaasId, String url) {
            this.name = name != null ? name.trim() : "";
            this.mpaasId = mpaasId != null ? mpaasId.trim() : "";
            this.url = url != null ? url.trim() : "";
        }

        public org.json.JSONObject toJson() {
            org.json.JSONObject obj = new org.json.JSONObject();
            try {
                obj.put("name", name);
                obj.put("mpaasId", mpaasId);
                obj.put("url", url);
            } catch (Throwable ignored) {
            }
            return obj;
        }

        public static SavedDevice fromJson(org.json.JSONObject obj) {
            if (obj == null) return null;
            return new SavedDevice(
                obj.optString("name", ""),
                obj.optString("mpaasId", ""),
                obj.optString("url", "")
            );
        }
    }

    /**
     * 仅用于剔除生活服务网格里明显不是“设备”的入口（红包、设置等）。
     * 不再使用白名单：数据来源已经精确到生活服务网格，不需要靠名字去猜哪些是设备，
     * 避免“浴室热水澡”“公共饮水”这类名字被白名单漏掉。
     */
    public static boolean isValidDeviceName(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        name = name.trim();
        String[] blackList = {
            "红包", "设置", "活动", "卡包", "钱包", "订单", "客服", "关于", "反馈",
            "帮助", "协议", "清除", "头条", "充值", "笑影通", "说明", "扫一扫", "消息", "更多"
        };
        for (String b : blackList) {
            if (name.contains(b)) return false;
        }
        return true;
    }

    /**
     * 用最新一次的生活服务列表整体替换快照。
     * @return 快照内容是否发生了变化；列表为空/无有效项时保留旧快照并返回 false
     */
    public static synchronized boolean replaceDevices(Context context, java.util.List<SavedDevice> fresh) {
        if (fresh == null || fresh.isEmpty()) return false;

        java.util.List<SavedDevice> clean = new java.util.ArrayList<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (SavedDevice d : fresh) {
            if (d == null || d.mpaasId.length() != 16 || !isValidDeviceName(d.name)) continue;
            // 同名只保留第一个，保证“按名称选择”在快照内无歧义
            if (!seenNames.add(d.name)) continue;
            clean.add(d);
        }
        if (clean.isEmpty()) return false;

        org.json.JSONArray arr = new org.json.JSONArray();
        for (SavedDevice d : clean) {
            arr.put(d.toJson());
        }
        String newJson = arr.toString();

        SharedPreferences sp = getPrefs(context);
        SharedPreferences.Editor editor = sp.edit();
        for (String k : LEGACY_KEYS) {
            editor.remove(k);
        }
        boolean changed = !newJson.equals(sp.getString(KEY_DEVICE_SNAPSHOT_JSON, null));
        if (changed) {
            editor.putString(KEY_DEVICE_SNAPSHOT_JSON, newJson);
        }
        editor.apply();
        return changed;
    }

    public static java.util.List<SavedDevice> getSavedDevices(Context context) {
        java.util.List<SavedDevice> list = new java.util.ArrayList<>();
        String json = getPrefs(context).getString(KEY_DEVICE_SNAPSHOT_JSON, null);
        if (json == null || json.trim().isEmpty()) return list;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                SavedDevice dev = SavedDevice.fromJson(arr.optJSONObject(i));
                if (dev != null && dev.mpaasId.length() == 16 && !dev.name.isEmpty()) {
                    list.add(dev);
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public static String getSelectedDeviceName(Context context) {
        String name = getPrefs(context).getString(KEY_SELECTED_DEVICE_NAME, "联网饮水机");
        return isValidDeviceName(name) ? name.trim() : "联网饮水机";
    }

    public static void setSelectedDeviceName(Context context, String name) {
        if (name != null && isValidDeviceName(name)) {
            getPrefs(context).edit().putString(KEY_SELECTED_DEVICE_NAME, name.trim()).apply();
        }
    }

    /**
     * 在当前快照中按名称精确查找选中的设备；找不到返回 null（不做任何兜底）。
     */
    public static SavedDevice getSelectedDevice(Context context) {
        String targetName = getSelectedDeviceName(context);
        for (SavedDevice dev : getSavedDevices(context)) {
            if (dev.name.equals(targetName)) {
                return dev;
            }
        }
        return null;
    }
}
