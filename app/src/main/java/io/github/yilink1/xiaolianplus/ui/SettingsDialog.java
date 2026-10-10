package io.github.yilink1.xiaolianplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.window.OnBackInvokedDispatcher;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.yilink1.xiaolianplus.BuildConfig;
import io.github.yilink1.xiaolianplus.config.ModuleConfig;

/**
 * 纯代码构建的现代化无依赖模块设置弹窗
 */
public class SettingsDialog {

    private static volatile Dialog sCurrentDialog = null;

    public static boolean isShowing() {
        return sCurrentDialog != null && sCurrentDialog.isShowing();
    }

    public static void dismissCurrent() {
        if (sCurrentDialog != null) {
            try {
                if (sCurrentDialog.isShowing()) {
                    sCurrentDialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
            sCurrentDialog = null;
        }
    }

    public static void show(Context context) {
        Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        // 关闭前一个未关闭的弹窗
        dismissCurrent();

        Dialog dialog = new Dialog(activity) {
            @Override
            public void onBackPressed() {
                dismiss();
            }
        };

        int dp16 = dp2px(context, 16);
        int dp20 = dp2px(context, 20);
        int dp24 = dp2px(context, 24);
        int dp12 = dp2px(context, 12);
        int dp8 = dp2px(context, 8);

        // 弹窗外层主卡片：承载圆角白色背景与视口轮廓裁切
        LinearLayout dialogRoot = new LinearLayout(context);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(context, 18));
        dialogRoot.setBackground(bg);
        dialogRoot.setClipToOutline(true);

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp20, dp24, dp20, dp12);

        // 1. 标题与副标题（居中规整纯净布局）
        TextView tvTitle = new TextView(context);
        tvTitle.setText("笑联Plus 设置");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(Color.parseColor("#1f1f1f"));
        tvTitle.getPaint().setFakeBoldText(true);
        root.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("XIAOLIAN PLUS");
        tvSub.setTextSize(11);
        tvSub.setLetterSpacing(0.12f);
        tvSub.setTextColor(Color.parseColor("#1082FF"));
        tvSub.getPaint().setFakeBoldText(true);
        tvSub.setPadding(0, dp2px(context, 4), 0, dp16);
        root.addView(tvSub);

        // 分割线
        root.addView(createDivider(context));

        // 临时草稿状态（点击底部「完成设置」时才统一持久化保存；返回/点外部退出则丢弃改动不保存）
        final boolean[] draftSplashAdBlock = { ModuleConfig.isSplashAdBlockEnabled(context) };
        final boolean[] draftLoadingPassThrough = { ModuleConfig.isLoadingPassThroughEnabled(context) };
        final boolean[] draftIgnoreLowVersion = { ModuleConfig.isIgnoreLowVersionPrompt(context) };
        final boolean[] draftWaterDispenser = { ModuleConfig.isWaterDispenserEnabled(context) };
        final boolean[] draftWaterDefaultAllFav = { ModuleConfig.isWaterDefaultAllFavEnabled(context) };
        final boolean[] draftWaterAutoConfirm = { ModuleConfig.isWaterAutoConfirmEnabled(context) };
        final boolean[] draftWaterHoldToSettle = { ModuleConfig.isWaterHoldToSettleEnabled(context) };
        final String[] draftHoldTheme = { ModuleConfig.getWaterHoldTheme(context) };
        final String[] draftHoldThemeName = { ModuleConfig.getWaterHoldThemeName(context) };
        final boolean[] draftWebviewDebug = { ModuleConfig.isWebviewDebugEnabled(context) };
        final boolean[] draftDebugHud = { ModuleConfig.isDebugHudEnabled(context) };
        final int[] draftDirectLaunchMode = { ModuleConfig.getDirectLaunchMode(context) };
        final String[] draftSelectedDeviceName = { ModuleConfig.getSelectedDeviceName(context) };
        final boolean[] draftHasSeenAutoConfirmTip = { ModuleConfig.hasSeenAutoConfirmTip(context) };

        // 2. 开关群
        addSectionHeader(context, root, "基础与通用");

        addSwitchItem(context, root, "跳过开屏广告", "打开应用时自动跳过启动页广告",
                draftSplashAdBlock[0],
                (buttonView, isChecked) -> draftSplashAdBlock[0] = isChecked);

        addSwitchItem(context, root, "加载中允许直接扫码", "页面正在加载列表时，依然可以直接点击右下角扫码",
                draftLoadingPassThrough[0],
                (buttonView, isChecked) -> draftLoadingPassThrough[0] = isChecked);

        if (isHostVersionLower(context, "1.5.7")) {
            addSwitchItem(context, root, "关闭低版本提示", "低于 1.5.7 版本不再提示",
                    draftIgnoreLowVersion[0],
                    (buttonView, isChecked) -> draftIgnoreLowVersion[0] = isChecked);
        }

        addSectionHeader(context, root, "饮水机");

        addSwitchItem(context, root, "饮水机备注与收藏", "点击星星收藏置顶，长按水机名称修改备注，支持全部收藏视图（支持联网/公共水机）",
                draftWaterDispenser[0],
                (buttonView, isChecked) -> draftWaterDispenser[0] = isChecked);

        addSwitchItem(context, root, "默认进入全部收藏", "打开饮水机页面时，有收藏则优先展示“全部收藏”",
                draftWaterDefaultAllFav[0],
                (buttonView, isChecked) -> draftWaterDefaultAllFav[0] = isChecked);

        if (ModuleConfig.EXPERIMENTAL_FEATURES_ENABLED) {
            addSectionHeader(context, root, "设备使用与结算");

            addSwitchItem(context, root, "跳过「开始使用」二次确认", "点击「开始使用」后自动确认「确认开始使用」弹窗",
                    draftWaterAutoConfirm[0],
                    (buttonView, isChecked) -> {
                        if (isChecked && !draftHasSeenAutoConfirmTip[0]) {
                            showAutoConfirmTipDialog(context, () -> {
                                draftHasSeenAutoConfirmTip[0] = true;
                                draftWaterAutoConfirm[0] = true;
                            }, () -> {
                                buttonView.setChecked(false);
                                draftWaterAutoConfirm[0] = false;
                            });
                        } else {
                            draftWaterAutoConfirm[0] = isChecked;
                        }
                    });

            addSwitchItem(context, root, "长按滑块快速结算", "长按滑块 0.5 秒即可结算，无需费力向右拖动",
                    draftWaterHoldToSettle[0],
                    (buttonView, isChecked) -> draftWaterHoldToSettle[0] = isChecked);

            // ---- 娱乐：长按按钮主题（点击后弹出带动态预览的选择器）----
            addSectionHeader(context, root, "娱乐");

            LinearLayout themeRow = new LinearLayout(context);
            themeRow.setOrientation(LinearLayout.HORIZONTAL);
            themeRow.setGravity(Gravity.CENTER_VERTICAL);
            themeRow.setPadding(0, dp2px(context, 8), 0, dp2px(context, 8));

            LinearLayout themeTexts = new LinearLayout(context);
            themeTexts.setOrientation(LinearLayout.VERTICAL);

            TextView tvThemeTitle = new TextView(context);
            tvThemeTitle.setText("长按按钮主题");
            tvThemeTitle.setTextSize(15);
            tvThemeTitle.setTextColor(Color.parseColor("#262626"));
            tvThemeTitle.getPaint().setFakeBoldText(true);
            themeTexts.addView(tvThemeTitle);

            TextView tvThemeDesc = new TextView(context);
            tvThemeDesc.setText("结算时长按按钮的外观，点击可预览并选择");
            tvThemeDesc.setTextSize(12);
            tvThemeDesc.setTextColor(Color.parseColor("#8c8c8c"));
            tvThemeDesc.setPadding(0, dp2px(context, 2), 0, 0);
            themeTexts.addView(tvThemeDesc);

            themeRow.addView(themeTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView btnTheme = new TextView(context);
            btnTheme.setText(draftHoldThemeName[0] + "  ▾");
            btnTheme.setTextSize(12);
            btnTheme.setTextColor(Color.parseColor("#1082FF"));
            btnTheme.getPaint().setFakeBoldText(true);
            btnTheme.setPadding(dp2px(context, 10), dp2px(context, 4), dp2px(context, 10), dp2px(context, 4));
            GradientDrawable themeBg = new GradientDrawable();
            themeBg.setColor(Color.parseColor("#EAF2FF"));
            themeBg.setCornerRadius(dp2px(context, 6));
            themeBg.setStroke(dp2px(context, 1), Color.parseColor("#CCE1FF"));
            btnTheme.setBackground(themeBg);
            themeRow.addView(btnTheme);

            btnTheme.setOnClickListener(v -> {
                if (!draftWaterHoldToSettle[0]) {
                    showToast(context, "请先开启「长按滑块快速结算」");
                    return;
                }
                showThemePickerDialog(context, draftHoldTheme[0], (id, name) -> {
                    draftHoldTheme[0] = id;
                    draftHoldThemeName[0] = name;
                    btnTheme.setText(name + "  ▾");
                });
            });
            root.addView(themeRow);
        }

        if (BuildConfig.DEBUG) {
            View rowWebDebug = addSwitchItem(context, root, "网页调试模式", null,
                    draftWebviewDebug[0],
                    (buttonView, isChecked) -> draftWebviewDebug[0] = isChecked);

            View rowDebugHud = addSwitchItem(context, root, "饮水机调试条", "在饮水机页面左下角显示滚动日志",
                    draftDebugHud[0],
                    (buttonView, isChecked) -> draftDebugHud[0] = isChecked);

            boolean isUnlocked = ModuleConfig.isDevOptionsUnlocked(context);
            rowWebDebug.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);
            rowDebugHud.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);
            if (!isUnlocked) {
                final int[] titleClicks = {0};
                tvTitle.setOnClickListener(v -> {
                    titleClicks[0]++;
                    if (titleClicks[0] >= 7) {
                        ModuleConfig.setDevOptionsUnlocked(context, true);
                        rowWebDebug.setVisibility(View.VISIBLE);
                        rowDebugHud.setVisibility(View.VISIBLE);
                        tvTitle.setOnClickListener(null);
                    }
                });
            }
        }

        // 3. APP启动直达策略（单选组）
        addSectionHeader(context, root, "APP启动直达策略");

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(LinearLayout.VERTICAL);

        int currentMode = draftDirectLaunchMode[0];
        String selectedDevice = draftSelectedDeviceName[0];
        java.util.List<ModuleConfig.SavedDevice> savedDevices = ModuleConfig.getSavedDevices(context);

        RadioButton rbHome = createRadioButton(context, "默认进入首页", ModuleConfig.DIRECT_LAUNCH_HOME);
        RadioButton rbScan = createRadioButton(context, "启动直达扫码界面", ModuleConfig.DIRECT_LAUNCH_SCAN);
        RadioButton rbDevice = createRadioButton(context, "启动直达快捷设备", ModuleConfig.DIRECT_LAUNCH_DISPENSER);

        radioGroup.addView(rbHome);
        radioGroup.addView(rbScan);
        radioGroup.addView(rbDevice);

        if (currentMode == ModuleConfig.DIRECT_LAUNCH_SCAN) {
            rbScan.setChecked(true);
        } else if (currentMode == ModuleConfig.DIRECT_LAUNCH_DISPENSER) {
            rbDevice.setChecked(true);
        } else {
            rbHome.setChecked(true);
        }

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            View rb = group.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof Integer) {
                int selected = (int) rb.getTag();
                draftDirectLaunchMode[0] = selected;
            }
        });

        root.addView(radioGroup);

        // 下拉选择卡片（替代平铺刷屏列表，点击弹窗单选）
        LinearLayout devicePickerRow = new LinearLayout(context);
        devicePickerRow.setOrientation(LinearLayout.HORIZONTAL);
        devicePickerRow.setGravity(Gravity.CENTER_VERTICAL);
        devicePickerRow.setPadding(dp2px(context, 32), dp2px(context, 4), dp2px(context, 12), dp2px(context, 6));

        TextView tvPickerPrompt = new TextView(context);
        tvPickerPrompt.setText("目标设备: ");
        tvPickerPrompt.setTextSize(12);
        tvPickerPrompt.setTextColor(Color.parseColor("#8c8c8c"));
        devicePickerRow.addView(tvPickerPrompt);

        TextView btnDevicePicker = new TextView(context);
        btnDevicePicker.setText(selectedDevice + "  ▾");
        btnDevicePicker.setTextSize(12);
        btnDevicePicker.setTextColor(Color.parseColor("#1082FF"));
        btnDevicePicker.getPaint().setFakeBoldText(true);
        btnDevicePicker.setPadding(dp2px(context, 10), dp2px(context, 4), dp2px(context, 10), dp2px(context, 4));

        GradientDrawable pickerBg = new GradientDrawable();
        pickerBg.setColor(Color.parseColor("#EAF2FF"));
        pickerBg.setCornerRadius(dp2px(context, 6));
        pickerBg.setStroke(dp2px(context, 1), Color.parseColor("#CCE1FF"));
        btnDevicePicker.setBackground(pickerBg);

        btnDevicePicker.setOnClickListener(v -> {
            java.util.List<ModuleConfig.SavedDevice> currentSaved = ModuleConfig.getSavedDevices(context);
            if (currentSaved.isEmpty()) {
                showToast(context, "暂无记录设备，请先在首页加载一次");
                return;
            }

            String[] names = new String[currentSaved.size()];
            int checkedItem = 0;
            String curName = draftSelectedDeviceName[0];
            for (int i = 0; i < currentSaved.size(); i++) {
                names[i] = currentSaved.get(i).name;
                if (names[i].equals(curName)) {
                    checkedItem = i;
                }
            }

            showModernSingleChoiceDialog(context, "选择直达目标设备", names, checkedItem, which -> {
                String chosen = names[which];
                draftSelectedDeviceName[0] = chosen;
                btnDevicePicker.setText(chosen + "  ▾");
                rbDevice.setChecked(true);
                draftDirectLaunchMode[0] = ModuleConfig.DIRECT_LAUNCH_DISPENSER;
            });
        });

        devicePickerRow.addView(btnDevicePicker);
        root.addView(devicePickerRow);

        root.addView(createDivider(context));

        // 4. 底部确定按钮（Soft UI 浅色胶囊微按钮，上下微胖饱满，留足呼吸间距）
        LinearLayout bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(dp20, dp2px(context, 14), dp20, dp2px(context, 18));

        TextView btnClose = new TextView(context);
        btnClose.setText("完成设置");
        btnClose.setTextSize(15);
        btnClose.setTextColor(Color.parseColor("#1082FF"));
        btnClose.getPaint().setFakeBoldText(true);
        btnClose.setGravity(Gravity.CENTER);
        int btnPad = dp2px(context, 12);
        btnClose.setPadding(0, btnPad, 0, btnPad);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setColor(Color.parseColor("#EAF2FF"));
        normalBg.setCornerRadius(dp2px(context, 24));

        GradientDrawable pressedBg = new GradientDrawable();
        pressedBg.setColor(Color.parseColor("#D6E7FF"));
        pressedBg.setCornerRadius(dp2px(context, 24));

        StateListDrawable btnBg = new StateListDrawable();
        btnBg.addState(new int[]{android.R.attr.state_pressed}, pressedBg);
        btnBg.addState(new int[]{}, normalBg);
        btnClose.setBackground(btnBg);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomBar.addView(btnClose, btnLp);

        btnClose.setOnClickListener(v -> {
            ModuleConfig.setSplashAdBlockEnabled(context, draftSplashAdBlock[0]);
            ModuleConfig.setLoadingPassThroughEnabled(context, draftLoadingPassThrough[0]);
            if (isHostVersionLower(context, "1.5.7")) {
                ModuleConfig.setIgnoreLowVersionPrompt(context, draftIgnoreLowVersion[0]);
            }
            ModuleConfig.setWaterDispenserEnabled(context, draftWaterDispenser[0]);
            ModuleConfig.setWaterDefaultAllFavEnabled(context, draftWaterDefaultAllFav[0]);
            if (ModuleConfig.EXPERIMENTAL_FEATURES_ENABLED) {
                ModuleConfig.setWaterAutoConfirmEnabled(context, draftWaterAutoConfirm[0]);
                ModuleConfig.setWaterHoldToSettleEnabled(context, draftWaterHoldToSettle[0]);
                ModuleConfig.setWaterHoldTheme(context, draftHoldTheme[0]);
                ModuleConfig.setWaterHoldThemeName(context, draftHoldThemeName[0]);
                ModuleConfig.setHasSeenAutoConfirmTip(context, draftHasSeenAutoConfirmTip[0]);
            }
            if (BuildConfig.DEBUG) {
                ModuleConfig.setWebviewDebugEnabled(context, draftWebviewDebug[0]);
                ModuleConfig.setDebugHudEnabled(context, draftDebugHud[0]);
            }
            ModuleConfig.setDirectLaunchMode(context, draftDirectLaunchMode[0]);
            ModuleConfig.setSelectedDeviceName(context, draftSelectedDeviceName[0]);
            dialog.dismiss();
        });

        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        dialog.setOnDismissListener(d -> {
            if (sCurrentDialog == d) {
                sCurrentDialog = null;
            }
        });

        scrollView.addView(root);
        dialogRoot.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialogRoot.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(dialogRoot);

        sCurrentDialog = dialog;
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.88);
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            enforceWindowState(dialog, window);
        }
    }

    private static void enforceWindowState(Dialog d, Window w) {
        // 强制开启满血 GPU 硬件加速，杜绝高刷屏动态降频卡顿
        w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        // 无论之前被谁改过，都恢复：可聚焦、模态、有遮罩
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.45f);

        View decor = w.getDecorView();
        decor.setFocusableInTouchMode(true);
        decor.requestFocus();

        // Android 13+ 预测性返回：显式注册，保证手势/按键都能到弹窗
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                d.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY, d::dismiss);
            } catch (Throwable ignored) {
            }
        }
    }

    private static View addSwitchItem(Context context, LinearLayout parent, String title, String desc,
                                      boolean initialValue, CompoundButton.OnCheckedChangeListener listener) {
        int dp12 = dp2px(context, 12);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp12, 0, dp12);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textColLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(textColLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor("#262626"));
        tvTitle.getPaint().setFakeBoldText(true);
        textCol.addView(tvTitle);

        if (desc != null && !desc.trim().isEmpty()) {
            TextView tvDesc = new TextView(context);
            tvDesc.setText(desc);
            tvDesc.setTextSize(11);
            tvDesc.setTextColor(Color.parseColor("#8c8c8c"));
            tvDesc.setPadding(0, dp2px(context, 2), 0, 0);
            textCol.addView(tvDesc);
        }

        row.addView(textCol);

        Switch sw = new Switch(context);
        sw.setChecked(initialValue);
        sw.setOnCheckedChangeListener(listener);

        // 开启状态完全保留原版配色，关闭状态滑块改回纯白色(#FFFFFF)
        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] thumbColors = new int[] {
            Color.parseColor("#1082FF"),
            Color.parseColor("#FFFFFF")
        };
        int[] trackColors = new int[] {
            Color.argb(100, 16, 130, 255),
            Color.parseColor("#E0E0E0")
        };
        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
        row.addView(sw);

        parent.addView(row);
        return row;
    }

    private static void addSectionHeader(Context context, LinearLayout parent, String title) {
        TextView tv = new TextView(context);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#1082FF"));
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, dp2px(context, 14), 0, dp2px(context, 4));
        parent.addView(tv);
    }

    private static RadioButton createRadioButton(Context context, String text, int tagVal) {
        RadioButton rb = new RadioButton(context);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTextSize(13);
        rb.setTextColor(Color.parseColor("#595959"));
        rb.setTag(tagVal);
        rb.setPadding(dp2px(context, 6), dp2px(context, 6), 0, dp2px(context, 6));

        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] rbColors = new int[] {
            Color.parseColor("#1082FF"),
            Color.parseColor("#8C8C8C")
        };
        rb.setButtonTintList(new ColorStateList(states, rbColors));
        return rb;
    }

    private static View createDivider(Context context) {
        View v = new View(context);
        v.setBackgroundColor(Color.parseColor("#f0f0f0"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(context, 1));
        lp.topMargin = dp2px(context, 6);
        lp.bottomMargin = dp2px(context, 6);
        v.setLayoutParams(lp);
        return v;
    }

    private static void showToast(Context context, String msg) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    private static int dp2px(Context context, float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    private static Activity findActivity(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static boolean isHostVersionLower(Context context, String target) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            String current = pi.versionName;
            if (current == null) return false;
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

    private interface OnItemSelectCallback {
        void onSelected(int index);
    }

    private static void showAutoConfirmTipDialog(Context context, Runnable onPositive, Runnable onNegative) {
        Activity act = findActivity(context);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        Dialog d = new Dialog(act);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp2px(act, 22);
        int padV = dp2px(act, 22);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(act, 20));
        card.setBackground(bg);

        // 1. 顶部 Header 行 (警告角标 + 首次开启提醒)
        LinearLayout headerRow = new LinearLayout(act);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout badge = new FrameLayout(act);
        int badgeSize = dp2px(act, 36);
        badge.setLayoutParams(new LinearLayout.LayoutParams(badgeSize, badgeSize));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#FEF3C7"));
        badgeBg.setCornerRadius(dp2px(act, 9));
        badge.setBackground(badgeBg);

        View iconView = new View(act) {
            private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path mPath = new Path();
            private final CornerPathEffect mCornerEffect = new CornerPathEffect(dp2px(act, 2.2f));

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float w = getWidth();
                float h = getHeight();
                if (w <= 0 || h <= 0) return;

                mPaint.setColor(Color.parseColor("#D97706"));
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dp2px(getContext(), 1.8f));
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setPathEffect(mCornerEffect);

                float padH = dp2px(getContext(), 1.5f);
                float padTop = dp2px(getContext(), 1.5f);
                float padBottom = dp2px(getContext(), 2f);

                float topX = w / 2f;
                float topY = padTop;
                float bottomY = h - padBottom;
                float leftX = padH;
                float rightX = w - padH;

                mPath.reset();
                mPath.moveTo(topX, topY);
                mPath.lineTo(rightX, bottomY);
                mPath.lineTo(leftX, bottomY);
                mPath.close();
                canvas.drawPath(mPath, mPaint);

                mPaint.setPathEffect(null);
                mPaint.setStrokeWidth(dp2px(getContext(), 1.8f));
                float midY = (topY + bottomY) / 2f;
                canvas.drawLine(topX, midY - dp2px(getContext(), 3.5f), topX, midY + dp2px(getContext(), 1.5f), mPaint);

                mPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(topX, bottomY - dp2px(getContext(), 3f), dp2px(getContext(), 1.0f), mPaint);
            }
        };
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp2px(act, 19), dp2px(act, 19));
        iconLp.gravity = Gravity.CENTER;
        badge.addView(iconView, iconLp);
        headerRow.addView(badge);

        TextView tvTitle = new TextView(act);
        tvTitle.setText("首次开启提醒");
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(Color.parseColor("#1F1F1F"));
        tvTitle.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = dp2px(act, 10);
        tvTitle.setLayoutParams(titleLp);
        headerRow.addView(tvTitle);

        card.addView(headerRow);

        // 2. 浅黄底色变化说明卡片
        LinearLayout noticeBox = new LinearLayout(act);
        noticeBox.setOrientation(LinearLayout.VERTICAL);
        int nbPadH = dp2px(act, 14);
        int nbPadV = dp2px(act, 12);
        noticeBox.setPadding(nbPadH, nbPadV, nbPadH, nbPadV);

        GradientDrawable nbBg = new GradientDrawable();
        nbBg.setColor(Color.parseColor("#FFFBEB"));
        nbBg.setStroke(dp2px(act, 1), Color.parseColor("#FDE68A"));
        nbBg.setCornerRadius(dp2px(act, 12));
        noticeBox.setBackground(nbBg);

        LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbLp.topMargin = dp2px(act, 16);
        noticeBox.setLayoutParams(nbLp);

        TextView tvNoticeTitle = new TextView(act);
        tvNoticeTitle.setText("开启后将发生以下变化");
        tvNoticeTitle.setTextSize(13.5f);
        tvNoticeTitle.setTextColor(Color.parseColor("#B45309"));
        tvNoticeTitle.getPaint().setFakeBoldText(true);
        noticeBox.addView(tvNoticeTitle);

        TextView tvNoticeContent = new TextView(act);
        tvNoticeContent.setText("点击「开始使用」后将直接启用设备，不再弹出二次确认");
        tvNoticeContent.setTextSize(13);
        tvNoticeContent.setTextColor(Color.parseColor("#4B5563"));
        tvNoticeContent.setLineSpacing(dp2px(act, 4), 1.0f);
        tvNoticeContent.setPadding(0, dp2px(act, 6), 0, 0);
        noticeBox.addView(tvNoticeContent);

        card.addView(noticeBox);

        // 3. 补充说明文案 (正文颜色加深至 #686868，适度字号与1.5倍行距，去句号)
        TextView tvSecondaryNote = new TextView(act);
        tvSecondaryNote.setText("此功能不会在进入页面时自动扣款，请在准备就绪后再点击使用");
        tvSecondaryNote.setTextSize(13);
        tvSecondaryNote.setTextColor(Color.parseColor("#686868"));
        tvSecondaryNote.setLineSpacing(dp2px(act, 4.5f), 1.0f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp2px(act, 16);
        noteLp.bottomMargin = dp2px(act, 22);
        tvSecondaryNote.setLayoutParams(noteLp);
        card.addView(tvSecondaryNote);

        // 4. 双操作按钮 (1:1 等宽对称设计，四字对仗：暂不开启 vs 确认开启)
        LinearLayout btnRow = new LinearLayout(act);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnNeg = new TextView(act);
        btnNeg.setText("暂不开启");
        btnNeg.setTextSize(14);
        btnNeg.setTextColor(Color.parseColor("#4B5563"));
        btnNeg.getPaint().setFakeBoldText(true);
        btnNeg.setGravity(Gravity.CENTER);
        int bPad = dp2px(act, 11);
        btnNeg.setPadding(0, bPad, 0, bPad);

        GradientDrawable negBg = new GradientDrawable();
        negBg.setColor(Color.WHITE);
        negBg.setStroke(dp2px(act, 1), Color.parseColor("#E5E7EB"));
        negBg.setCornerRadius(dp2px(act, 12));
        btnNeg.setBackground(negBg);

        TextView btnPos = new TextView(act);
        btnPos.setText("确认开启");
        btnPos.setTextSize(14);
        btnPos.setTextColor(Color.WHITE);
        btnPos.getPaint().setFakeBoldText(true);
        btnPos.setGravity(Gravity.CENTER);
        btnPos.setPadding(0, bPad, 0, bPad);

        GradientDrawable posBg = new GradientDrawable();
        posBg.setColor(Color.parseColor("#1082FF"));
        posBg.setCornerRadius(dp2px(act, 12));
        btnPos.setBackground(posBg);

        // 严格 1:1 等宽
        LinearLayout.LayoutParams lpNeg = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        lpNeg.rightMargin = dp2px(act, 10);
        btnRow.addView(btnNeg, lpNeg);

        LinearLayout.LayoutParams lpPos = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        btnRow.addView(btnPos, lpPos);

        card.addView(btnRow);

        d.setContentView(card);

        final boolean[] handled = {false};
        btnNeg.setOnClickListener(v -> {
            handled[0] = true;
            d.dismiss();
            if (onNegative != null) onNegative.run();
        });
        btnPos.setOnClickListener(v -> {
            handled[0] = true;
            d.dismiss();
            if (onPositive != null) onPositive.run();
        });
        d.setOnCancelListener(dialogInterface -> {
            if (!handled[0] && onNegative != null) onNegative.run();
        });

        d.show();
        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.80);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            enforceWindowState(d, w);
        }
    }

    private static void showModernSingleChoiceDialog(Context context, String title, String[] items, int checkedIndex,
                                                      OnItemSelectCallback callback) {
        Activity act = findActivity(context);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        Dialog d = new Dialog(act);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp2px(act, 22);
        card.setPadding(padH, dp2px(act, 20), padH, dp2px(act, 16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(act, 20));
        card.setBackground(bg);

        TextView tvTitle = new TextView(act);
        tvTitle.setText(title);
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(Color.parseColor("#1f1f1f"));
        tvTitle.getPaint().setFakeBoldText(true);
        card.addView(tvTitle);

        ScrollView sv = new ScrollView(act);
        LinearLayout listRoot = new LinearLayout(act);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(0, dp2px(act, 12), 0, dp2px(act, 8));

        int[][] states = new int[][] {
            new int[] { android.R.attr.state_checked },
            new int[] { -android.R.attr.state_checked }
        };
        int[] rbColors = new int[] {
            Color.parseColor("#1082FF"),
            Color.parseColor("#8C8C8C")
        };

        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            LinearLayout itemRow = new LinearLayout(act);
            itemRow.setOrientation(LinearLayout.HORIZONTAL);
            itemRow.setGravity(Gravity.CENTER_VERTICAL);
            itemRow.setPadding(0, dp2px(act, 10), 0, dp2px(act, 10));

            RadioButton rb = new RadioButton(act);
            rb.setChecked(i == checkedIndex);
            rb.setButtonTintList(new ColorStateList(states, rbColors));
            rb.setClickable(false);
            itemRow.addView(rb);

            TextView tvItem = new TextView(act);
            tvItem.setText(items[i]);
            tvItem.setTextSize(14);
            tvItem.setTextColor(i == checkedIndex ? Color.parseColor("#1082FF") : Color.parseColor("#262626"));
            if (i == checkedIndex) tvItem.getPaint().setFakeBoldText(true);
            tvItem.setPadding(dp2px(act, 8), 0, 0, 0);
            itemRow.addView(tvItem);

            itemRow.setOnClickListener(v -> {
                d.dismiss();
                if (callback != null) callback.onSelected(idx);
            });

            listRoot.addView(itemRow);
        }

        sv.addView(listRoot);
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView btnCancel = new TextView(act);
        btnCancel.setText("取消");
        btnCancel.setTextSize(14);
        btnCancel.setTextColor(Color.parseColor("#8C8C8C"));
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(0, dp2px(act, 10), 0, 0);
        btnCancel.setOnClickListener(v -> d.dismiss());
        card.addView(btnCancel);

        d.setContentView(card);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.82);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            enforceWindowState(d, w);
        }
    }

    // ======================================================================
    // 长按按钮主题选择器（动态预览）
    // 预览页由 drink_water.js 自己渲染（预览模式），与真实按钮同一份 CSS/DOM，
    // 以后新增主题只需改 drink_water.js 里的 THEME_META 与对应 CSS，这里不用动。
    // ======================================================================

    private interface OnThemePick {
        void onPick(String id, String name);
    }

    /** 读取 drink_water.js 全文。优先从模块 ClassLoader 读取，回退至 assets。 */
    private static String loadDrinkWaterScript(Context context) {
        try (java.io.InputStream in = SettingsDialog.class.getClassLoader().getResourceAsStream("assets/drink_water.js")) {
            if (in != null) {
                return readStreamToString(in);
            }
        } catch (Throwable ignored) {
        }
        try (java.io.InputStream in = context.getAssets().open("drink_water.js")) {
            return readStreamToString(in);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readStreamToString(java.io.InputStream in) throws java.io.IOException {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String sanitizeThemeId(String id) {
        return (id != null && id.matches("[a-z0-9_-]{1,24}")) ? id : null;
    }

    private static String buildThemePreviewHtml(String script, String selectedId) {
        String safeId = sanitizeThemeId(selectedId);
        if (safeId == null) safeId = "classic";
        String safeScript = script.replace("</script", "<\\/script");
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,user-scalable=no\">"
                + "<style>html,body{margin:0;padding:0;background:transparent;}</style></head><body>"
                + "<script>window.__XL_DW_PREVIEW__=true;</script>"
                + "<script>" + safeScript + "</script>"
                + "<script>__XL_DW_PREVIEW_API__.renderPicker(document.body,\"" + safeId
                + "\",function(id,name){XLBridge.pick(id,name);});</script>"
                + "</body></html>";
    }

    private static final class ThemeBridge {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final OnThemePick cb;
        private final Dialog dialog;
        private boolean done;

        ThemeBridge(Dialog dialog, OnThemePick cb) {
            this.dialog = dialog;
            this.cb = cb;
        }

        @JavascriptInterface
        public void pick(String id, String name) {
            final String safeId = sanitizeThemeId(id);
            if (safeId == null) return;
            String n = name == null ? "" : name.trim();
            if (n.length() > 12) n = n.substring(0, 12);
            final String safeName = n.isEmpty() ? safeId : n;
            main.post(() -> {
                if (done) return;
                done = true;
                if (cb != null) cb.onPick(safeId, safeName);
                // 留一点时间让用户看到选中态再关闭
                main.postDelayed(() -> {
                    try { dialog.dismiss(); } catch (Throwable ignored) { }
                }, 260);
            });
        }
    }

    private static void showThemePickerDialog(Context context, String currentId, OnThemePick callback) {
        Activity act = findActivity(context);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        String script = loadDrinkWaterScript(context);
        if (script == null || script.isEmpty()) {
            showToast(context, "无法加载预览资源");
            return;
        }

        Dialog d = new Dialog(act);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp2px(act, 18);
        card.setPadding(padH, dp2px(act, 20), padH, dp2px(act, 14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(act, 20));
        card.setBackground(bg);

        TextView tvTitle = new TextView(act);
        tvTitle.setText("选择长按按钮主题");
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(Color.parseColor("#1f1f1f"));
        tvTitle.getPaint().setFakeBoldText(true);
        card.addView(tvTitle);

        TextView tvHint = new TextView(act);
        tvHint.setText("预览会自动演示长按效果，点击卡片即可选用");
        tvHint.setTextSize(12);
        tvHint.setTextColor(Color.parseColor("#8c8c8c"));
        tvHint.setPadding(0, dp2px(act, 4), 0, dp2px(act, 8));
        card.addView(tvHint);

        WebView web = new WebView(act);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setVerticalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setDomStorageEnabled(false);
        ws.setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // 预览页不允许任何跳转
            }
        });
        web.addJavascriptInterface(new ThemeBridge(d, callback), "XLBridge");
        web.loadDataWithBaseURL(null, buildThemePreviewHtml(script, currentId), "text/html", "utf-8", null);

        int webH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.55);
        card.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, webH));

        TextView btnCancel = new TextView(act);
        btnCancel.setText("取消");
        btnCancel.setTextSize(14);
        btnCancel.setTextColor(Color.parseColor("#8C8C8C"));
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(0, dp2px(act, 10), 0, 0);
        btnCancel.setOnClickListener(v -> d.dismiss());
        card.addView(btnCancel);

        d.setOnDismissListener(x -> {
            try {
                web.stopLoading();
                web.removeJavascriptInterface("XLBridge");
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) { }
        });

        d.setContentView(card);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            enforceWindowState(d, w);
        }
    }
}
