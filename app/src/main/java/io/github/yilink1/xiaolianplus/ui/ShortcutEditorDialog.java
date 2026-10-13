package io.github.yilink1.xiaolianplus.ui;

import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.yilink1.xiaolianplus.config.ModuleConfig;
import io.github.yilink1.xiaolianplus.shortcut.AppShortcutHelper;

/**
 * 桌面快捷方式编排器对话框
 * 上下双区：
 * - 上半区：已添加的快捷方式（上限 4 个，从上至下即桌面长按菜单顺序，支持上移/下移/移除）
 * - 下半区：更多可用功能（点击添加）
 *
 * 配色原则：以中性灰为主，仅「保存」使用主色，避免界面花哨。
 */
public class ShortcutEditorDialog {

    private static final int MAX_ITEMS = 4;

    // 整体向宿主 App 的深蓝头部靠拢：中性色带一点蓝灰，强调色用压低饱和度的蓝
    private static final int C_TEXT = Color.parseColor("#0F172A");
    private static final int C_TEXT_SUB = Color.parseColor("#64748B");
    private static final int C_TEXT_HINT = Color.parseColor("#94A3B8");
    private static final int C_FILL = Color.parseColor("#EFF6FF");
    private static final int C_LINE = Color.parseColor("#DBEAFE");
    private static final int C_ACCENT = Color.parseColor("#1082FF");

    public static void show(Context context) {
        Activity act = findActivity(context);
        if (act == null || act.isFinishing() || act.isDestroyed()) return;

        Dialog d = new Dialog(act);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);

        int dp20 = dp2px(act, 20);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp20, dp2px(act, 20), dp20, dp2px(act, 12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(act, 20));
        card.setBackground(bg);

        // 标题
        TextView tvTitle = new TextView(act);
        tvTitle.setText("长按图标快捷");
        tvTitle.setTextSize(17);
        tvTitle.setTextColor(C_TEXT);
        tvTitle.getPaint().setFakeBoldText(true);
        card.addView(tvTitle);

        TextView tvDesc = new TextView(act);
        tvDesc.setText("从上至下的顺序即为长按图标弹出的显示顺序，最多 " + MAX_ITEMS + " 个");
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(C_TEXT_SUB);
        tvDesc.setPadding(0, dp2px(act, 4), 0, dp2px(act, 14));
        card.addView(tvDesc);

        // 数据集合
        List<String> chosenItems = new ArrayList<>(ModuleConfig.getShortcutCustomItems(act));
        List<ModuleConfig.SavedDevice> savedDevices = ModuleConfig.getSavedDevices(act);

        // 收集所有候选项目（扫一扫 + 学校所有设备）
        List<String> allCandidates = new ArrayList<>();
        allCandidates.add(ModuleConfig.SHORTCUT_ITEM_SCAN);
        for (ModuleConfig.SavedDevice dev : savedDevices) {
            if (!allCandidates.contains(dev.name)) {
                allCandidates.add(dev.name);
            }
        }
        // 兜底：确保“联网饮水机”一定在候选里
        if (!allCandidates.contains("联网饮水机")) {
            allCandidates.add("联网饮水机");
        }

        ScrollView sv = new ScrollView(act) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        LinearLayout contentRoot = new LinearLayout(act);
        contentRoot.setOrientation(LinearLayout.VERTICAL);

        Runnable[] renderRef = new Runnable[1];

        renderRef[0] = () -> {
            contentRoot.removeAllViews();

            // ==================== 已添加 ====================
            contentRoot.addView(sectionLabel(act, "已添加  " + chosenItems.size() + "/" + MAX_ITEMS));

            if (chosenItems.isEmpty()) {
                TextView tvEmpty = new TextView(act);
                tvEmpty.setText("还没有添加，请从下方选择");
                tvEmpty.setTextSize(13);
                tvEmpty.setTextColor(C_TEXT_HINT);
                tvEmpty.setGravity(Gravity.CENTER);
                tvEmpty.setPadding(0, dp2px(act, 18), 0, dp2px(act, 18));
                contentRoot.addView(tvEmpty);
            } else {
                for (int i = 0; i < chosenItems.size(); i++) {
                    final int idx = i;
                    String itemKey = chosenItems.get(i);

                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp2px(act, 14), 0, dp2px(act, 6), 0);

                    GradientDrawable rowBg = new GradientDrawable();
                    rowBg.setColor(C_FILL);
                    rowBg.setCornerRadius(dp2px(act, 12));
                    row.setBackground(rowBg);

                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp2px(act, 46));
                    rowLp.bottomMargin = dp2px(act, 6);
                    row.setLayoutParams(rowLp);

                    // 序号（纯文字）
                    TextView tvRank = new TextView(act);
                    tvRank.setText(String.valueOf(idx + 1));
                    tvRank.setTextSize(16);
                    tvRank.setTextColor(C_ACCENT);
                    tvRank.getPaint().setFakeBoldText(true);
                    row.addView(tvRank, new LinearLayout.LayoutParams(dp2px(act, 26), ViewGroup.LayoutParams.WRAP_CONTENT));

                    // 名称
                    TextView tvName = new TextView(act);
                    tvName.setText(getDisplayTitle(itemKey));
                    tvName.setTextSize(14.5f);
                    tvName.setTextColor(C_TEXT);
                    tvName.setSingleLine(true);
                    tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    // 上移 / 下移 / 移除：统一为无底色的灰色字符按钮，点击区域加大
                    // 首项不显示上移、末项不显示下移，用占位保持右侧对齐
                    row.addView(idx > 0
                            ? iconButton(act, "▲", () -> {
                                Collections.swap(chosenItems, idx, idx - 1);
                                renderRef[0].run();
                            })
                            : iconPlaceholder(act));
                    row.addView(idx < chosenItems.size() - 1
                            ? iconButton(act, "▼", () -> {
                                Collections.swap(chosenItems, idx, idx + 1);
                                renderRef[0].run();
                            })
                            : iconPlaceholder(act));
                    row.addView(iconButton(act, "✕", () -> {
                        chosenItems.remove(idx);
                        renderRef[0].run();
                    }));

                    contentRoot.addView(row);
                }
            }

            // ==================== 可添加 ====================
            List<String> unchosen = new ArrayList<>();
            for (String c : allCandidates) {
                if (!chosenItems.contains(c)) {
                    unchosen.add(c);
                }
            }

            if (!unchosen.isEmpty()) {
                TextView label = sectionLabel(act, "点击添加");
                label.setPadding(0, dp2px(act, 14), 0, dp2px(act, 8));
                contentRoot.addView(label);

                for (int i = 0; i < unchosen.size(); i += 2) {
                    final String item1 = unchosen.get(i);
                    final String item2 = (i + 1 < unchosen.size()) ? unchosen.get(i + 1) : null;

                    LinearLayout gridRow = new LinearLayout(act);
                    gridRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dp2px(act, 6);
                    gridRow.setLayoutParams(rowLp);

                    View chip1 = createCandidateChip(act, item1, chosenItems, renderRef[0]);
                    LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp1.rightMargin = dp2px(act, 3);
                    gridRow.addView(chip1, lp1);

                    if (item2 != null) {
                        View chip2 = createCandidateChip(act, item2, chosenItems, renderRef[0]);
                        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        lp2.leftMargin = dp2px(act, 3);
                        gridRow.addView(chip2, lp2);
                    } else {
                        View placeholder = new View(act);
                        LinearLayout.LayoutParams lpPlace = new LinearLayout.LayoutParams(0, 1, 1f);
                        lpPlace.leftMargin = dp2px(act, 3);
                        gridRow.addView(placeholder, lpPlace);
                    }

                    contentRoot.addView(gridRow);
                }
            }
        };

        renderRef[0].run();

        sv.addView(contentRoot);
        card.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部动作栏：取消 / 保存（间距拉大，取消往左移）
        LinearLayout bottomRow = new LinearLayout(act);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        bottomRow.setPadding(0, dp2px(act, 14), 0, dp2px(act, 4));

        View spacer = new View(act);
        bottomRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView btnCancel = new TextView(act);
        btnCancel.setText("取消");
        btnCancel.setTextSize(14.5f);
        btnCancel.setTextColor(C_TEXT_SUB);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding(dp2px(act, 10), dp2px(act, 6), dp2px(act, 10), dp2px(act, 6));
        btnCancel.setOnClickListener(v -> d.dismiss());
        bottomRow.addView(btnCancel);

        // 间距加大至 36dp，让「取消」更加明显靠左展开，与「保存」疏朗平衡
        View btnGap = new View(act);
        bottomRow.addView(btnGap, new LinearLayout.LayoutParams(dp2px(act, 36), 1));

        TextView btnSave = new TextView(act);
        btnSave.setText("保存");
        btnSave.setTextSize(14.5f);
        btnSave.setTextColor(C_ACCENT);
        btnSave.getPaint().setFakeBoldText(true);
        btnSave.setGravity(Gravity.CENTER);
        btnSave.setPadding(dp2px(act, 10), dp2px(act, 6), dp2px(act, 10), dp2px(act, 6));

        btnSave.setOnClickListener(v -> {
            d.dismiss();
            ModuleConfig.setShortcutCustomItems(act, chosenItems);
            try {
                AppShortcutHelper.updateShortcuts(act);
            } catch (Throwable ignored) {
            }
            Toast.makeText(act, "已更新长按图标快捷（" + chosenItems.size() + "项）", Toast.LENGTH_SHORT).show();
        });
        bottomRow.addView(btnSave);

        card.addView(bottomRow);

        d.setContentView(card);
        d.show();

        Window w = d.getWindow();
        if (w != null) {
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            w.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.45f);

            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    d.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                            OnBackInvokedDispatcher.PRIORITY_OVERLAY, d::dismiss);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static TextView sectionLabel(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(C_TEXT_SUB);
        tv.setPadding(0, 0, 0, dp2px(act, 8));
        return tv;
    }

    /** 无底色的灰色字符按钮，固定 34dp 宽，保证点击区域。 */
    private static TextView iconButton(Activity act, String glyph, Runnable onClick) {
        TextView tv = new TextView(act);
        tv.setText(glyph);
        boolean isRemove = "✕".equals(glyph);
        tv.setTextSize(15);
        tv.setTextColor(isRemove ? C_TEXT_SUB : C_ACCENT);
        tv.setGravity(Gravity.CENTER);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp2px(act, 40), ViewGroup.LayoutParams.MATCH_PARENT));
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private static View iconPlaceholder(Activity act) {
        View v = new View(act);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp2px(act, 40), 1));
        return v;
    }

    private static View createCandidateChip(Activity act, String itemKey, List<String> chosenItems, Runnable onRefresh) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp2px(act, 12), dp2px(act, 11), dp2px(act, 12), dp2px(act, 11));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp2px(act, 12));
        bg.setStroke(dp2px(act, 1), C_LINE);
        chip.setBackground(bg);
        // 已满 4 个时整体变淡，提示不能再添加（点击仍会弹 Toast）
        chip.setAlpha(chosenItems.size() >= MAX_ITEMS ? 0.4f : 1f);

        TextView tvName = new TextView(act);
        tvName.setText(getDisplayTitle(itemKey));
        tvName.setTextSize(13.5f);
        tvName.setTextColor(C_TEXT);
        tvName.setSingleLine(true);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvPlus = new TextView(act);
        tvPlus.setText("＋");
        tvPlus.setTextSize(22);
        tvPlus.setTextColor(C_ACCENT);
        tvPlus.getPaint().setFakeBoldText(true);
        tvPlus.setGravity(Gravity.CENTER);
        tvPlus.setPadding(dp2px(act, 4), 0, 0, 0);
        chip.addView(tvPlus);

        chip.setOnClickListener(v -> {
            if (chosenItems.size() >= MAX_ITEMS) {
                Toast.makeText(act, "桌面快捷方式最多添加 " + MAX_ITEMS + " 个", Toast.LENGTH_SHORT).show();
                return;
            }
            chosenItems.add(itemKey);
            onRefresh.run();
        });

        return chip;
    }

    private static String getDisplayTitle(String itemKey) {
        if (ModuleConfig.SHORTCUT_ITEM_SCAN.equals(itemKey)) {
            return "扫一扫";
        }
        return itemKey != null ? itemKey : "";
    }

    private static int dp2px(Context context, float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    private static Activity findActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        }
        if (context instanceof android.content.ContextWrapper) {
            return findActivity(((android.content.ContextWrapper) context).getBaseContext());
        }
        return null;
    }
}
