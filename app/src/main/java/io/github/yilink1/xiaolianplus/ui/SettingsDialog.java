package io.github.yilink1.xiaolianplus.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
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

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.80);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp20, dp24, dp20, dp24);

        // 圆角白色背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(context, 18));
        root.setBackground(bg);

        // 1. 标题与副标题（居中规整纯净布局）
        TextView tvTitle = new TextView(context);
        tvTitle.setText("笑联Plus 设置");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(Color.parseColor("#1f1f1f"));
        tvTitle.getPaint().setFakeBoldText(true);
        root.addView(tvTitle);

        TextView tvSub = new TextView(context);
        tvSub.setText("轻量、稳定、去广告的定制体验");
        tvSub.setTextSize(12);
        tvSub.setTextColor(Color.parseColor("#8c8c8c"));
        tvSub.setPadding(0, dp2px(context, 4), 0, dp16);
        root.addView(tvSub);

        // 分割线
        root.addView(createDivider(context));

        // 2. 开关群
        addSwitchItem(context, root, "跳过开屏广告", null,
                ModuleConfig.isSplashAdBlockEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setSplashAdBlockEnabled(context, isChecked));

        addSwitchItem(context, root, "饮水机备注与收藏", "长按名称改名，点击星标置顶（支持联网/公共饮水机）",
                ModuleConfig.isWaterDispenserEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setWaterDispenserEnabled(context, isChecked));

        addSwitchItem(context, root, "跳过打水确认弹窗", "进入打水页面时自动确认“开始使用”，免去手动二次点击",
                ModuleConfig.isWaterAutoConfirmEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setWaterAutoConfirmEnabled(context, isChecked));

        addSwitchItem(context, root, "长按0.5秒结算找零", "长按底部胶囊进度条替代横向滑动结算，0.5秒快速找零",
                ModuleConfig.isWaterHoldToSettleEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setWaterHoldToSettleEnabled(context, isChecked));

        addSwitchItem(context, root, "打水页面数据脱敏", "将打水界面中的楼栋位置伪装为虚拟设备（防开盒与隐私泄露）",
                ModuleConfig.isWaterDesensitizeEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setWaterDesensitizeEnabled(context, isChecked));

        addSwitchItem(context, root, "加载中允许直接扫码", "页面转圈加载时依然能直接点击右下角扫码（支持饮水机、浴室等全部页面）",
                ModuleConfig.isLoadingPassThroughEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setLoadingPassThroughEnabled(context, isChecked));

        if (isHostVersionLower(context, "1.5.7")) {
            addSwitchItem(context, root, "关闭低版本提示", "低于 1.5.7 版本不再提示",
                    ModuleConfig.isIgnoreLowVersionPrompt(context),
                    (buttonView, isChecked) -> ModuleConfig.setIgnoreLowVersionPrompt(context, isChecked));
        }

        View rowWebDebug = addSwitchItem(context, root, "网页调试模式", null,
                ModuleConfig.isWebviewDebugEnabled(context),
                (buttonView, isChecked) -> ModuleConfig.setWebviewDebugEnabled(context, isChecked));

        if (!BuildConfig.DEBUG) {
            rowWebDebug.setVisibility(View.GONE);
        } else {
            boolean isUnlocked = ModuleConfig.isDevOptionsUnlocked(context);
            rowWebDebug.setVisibility(isUnlocked ? View.VISIBLE : View.GONE);
            if (!isUnlocked) {
                final int[] titleClicks = {0};
                tvTitle.setOnClickListener(v -> {
                    titleClicks[0]++;
                    if (titleClicks[0] >= 7) {
                        ModuleConfig.setDevOptionsUnlocked(context, true);
                        rowWebDebug.setVisibility(View.VISIBLE);
                        tvTitle.setOnClickListener(null);
                    }
                });
            }
        }

        root.addView(createDivider(context));

        // 3. APP启动直达策略（单选组）
        TextView tvDirectTitle = new TextView(context);
        tvDirectTitle.setText("APP启动直达策略");
        tvDirectTitle.setTextSize(14);
        tvDirectTitle.setTextColor(Color.parseColor("#262626"));
        tvDirectTitle.getPaint().setFakeBoldText(true);
        tvDirectTitle.setPadding(0, dp12, 0, dp8);
        root.addView(tvDirectTitle);

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(LinearLayout.VERTICAL);

        int currentMode = ModuleConfig.getDirectLaunchMode(context);
        String selectedDevice = ModuleConfig.getSelectedDeviceName(context);
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
                ModuleConfig.setDirectLaunchMode(context, selected);
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
        btnDevicePicker.setTextColor(Color.parseColor("#1677ff"));
        btnDevicePicker.getPaint().setFakeBoldText(true);
        btnDevicePicker.setPadding(dp2px(context, 10), dp2px(context, 4), dp2px(context, 10), dp2px(context, 4));

        GradientDrawable pickerBg = new GradientDrawable();
        pickerBg.setColor(Color.parseColor("#f0f5ff"));
        pickerBg.setCornerRadius(dp2px(context, 6));
        pickerBg.setStroke(dp2px(context, 1), Color.parseColor("#d6e4ff"));
        btnDevicePicker.setBackground(pickerBg);

        btnDevicePicker.setOnClickListener(v -> {
            java.util.List<ModuleConfig.SavedDevice> currentSaved = ModuleConfig.getSavedDevices(context);
            if (currentSaved.isEmpty()) {
                showToast(context, "暂无记录设备，请先在首页加载一次");
                return;
            }

            String[] names = new String[currentSaved.size()];
            int checkedItem = 0;
            String curName = ModuleConfig.getSelectedDeviceName(context);
            for (int i = 0; i < currentSaved.size(); i++) {
                names[i] = currentSaved.get(i).name;
                if (names[i].equals(curName)) {
                    checkedItem = i;
                }
            }

            new AlertDialog.Builder(context)
                .setTitle("选择直达目标设备")
                .setSingleChoiceItems(names, checkedItem, (dialogInterface, which) -> {
                    String chosen = names[which];
                    ModuleConfig.setSelectedDeviceName(context, chosen);
                    btnDevicePicker.setText(chosen + "  ▾");
                    rbDevice.setChecked(true);
                    ModuleConfig.setDirectLaunchMode(context, ModuleConfig.DIRECT_LAUNCH_DISPENSER);
                    dialogInterface.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        });

        devicePickerRow.addView(btnDevicePicker);
        root.addView(devicePickerRow);

        root.addView(createDivider(context));

        // 4. 底部确定按钮
        TextView btnClose = new TextView(context);
        btnClose.setText("完成设置");
        btnClose.setTextSize(15);
        btnClose.setTextColor(Color.WHITE);
        btnClose.getPaint().setFakeBoldText(true);
        btnClose.setGravity(Gravity.CENTER);
        int btnPad = dp2px(context, 12);
        btnClose.setPadding(0, btnPad, 0, btnPad);

        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#1677ff"));
        btnBg.setCornerRadius(dp2px(context, 10));
        btnClose.setBackground(btnBg);

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp16;
        root.addView(btnClose, btnLp);

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        dialog.setOnDismissListener(d -> {
            if (sCurrentDialog == d) {
                sCurrentDialog = null;
            }
        });

        scrollView.addView(root);
        dialog.setContentView(scrollView);

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
        row.addView(sw);

        parent.addView(row);
        return row;
    }

    private static RadioButton createRadioButton(Context context, String text, int tagVal) {
        RadioButton rb = new RadioButton(context);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTextSize(13);
        rb.setTextColor(Color.parseColor("#595959"));
        rb.setTag(tagVal);
        rb.setPadding(dp2px(context, 6), dp2px(context, 6), 0, dp2px(context, 6));
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
}
