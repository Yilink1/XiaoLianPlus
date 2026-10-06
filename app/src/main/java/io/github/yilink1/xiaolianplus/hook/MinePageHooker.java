package io.github.yilink1.xiaolianplus.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.yilink1.xiaolianplus.XiaoLianModule;
import io.github.yilink1.xiaolianplus.ui.SettingsDialog;

/**
 * 宿主“我的”页面设置入口注入器
 * 自动识别并插入到 com.xiaolian.prometheus:id/list_lin 列表容器中
 */
public class MinePageHooker {

    private static final String TAG = XiaoLianModule.TAG;
    private static final String ITEM_TAG = "xl_settings_item";

    private final XiaoLianModule mModule;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean mBaseFragmentHooked = false;
    private volatile boolean mHomeActivityHooked = false;

    public MinePageHooker(XiaoLianModule module) {
        this.mModule = module;
    }

    public synchronized void initHooks(ClassLoader classLoader) {
        if (classLoader == null) return;

        // 1. Hook BaseFragment 生命周期与视图构建
        if (!mBaseFragmentHooked) {
            try {
                Class<?> baseFragClass = classLoader.loadClass("com.xiaolian.zygote.ui.base.BaseFragment");
                for (Method m : baseFragClass.getDeclaredMethods()) {
                    if ("onCreateView".equals(m.getName()) && m.getParameterCount() == 3) {
                        mModule.hook(m)
                            .setId("hook_base_fragment_on_create_view")
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object view = chain.proceed();
                                if (view instanceof View) {
                                    checkAndInjectListLin((View) view, classLoader);
                                }
                                return view;
                            });
                    } else if ("onHiddenChanged".equals(m.getName()) && m.getParameterCount() == 1) {
                        mModule.hook(m)
                            .setId("hook_base_fragment_on_hidden_changed")
                            .setPriority(XposedInterface.PRIORITY_LOWEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object res = chain.proceed();
                                java.util.List<Object> args = chain.getArgs();
                                if (args != null && !args.isEmpty() && Boolean.FALSE.equals(args.get(0))) {
                                    Object frag = chain.getThisObject();
                                    if (frag != null) {
                                        try {
                                            Method getViewMethod = frag.getClass().getMethod("getView");
                                            Object view = getViewMethod.invoke(frag);
                                            if (view instanceof View) {
                                                checkAndInjectListLin((View) view, classLoader);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                                return res;
                            });
                    }
                }
                mBaseFragmentHooked = true;
                mModule.log(Log.INFO, TAG, "Successfully hooked BaseFragment for Mine page settings injection");
            } catch (Throwable t) {
                // MultiDex 未就绪时静默跳过，后续在 Activity 启动时重试
            }
        }

        // 2. Hook HomeActivity.onResume 兜底检查
        if (!mHomeActivityHooked) {
            try {
                Class<?> homeActivityClass = classLoader.loadClass("com.xiaolian.home.ui.HomeActivity");
                Method onResume = findMethod(homeActivityClass, "onResume", 0);
                if (onResume != null) {
                    mModule.hook(onResume)
                        .setId("hook_home_activity_resume_mine_page")
                        .setPriority(XposedInterface.PRIORITY_LOWEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object res = chain.proceed();
                            Object thisObj = chain.getThisObject();
                            if (thisObj instanceof Activity) {
                                scheduleActivityScan((Activity) thisObj, classLoader);
                            }
                            return res;
                        });
                    mHomeActivityHooked = true;
                    mModule.log(Log.INFO, TAG, "Successfully hooked HomeActivity.onResume for Mine page scan");
                }
            } catch (Throwable t) {
                // MultiDex 未就绪时静默跳过，后续在 Activity 启动时重试
            }
        }
    }

    public void onHomeActivityCreated(Activity activity) {
        if (activity == null) return;
        ClassLoader cl = activity.getClass().getClassLoader();
        initHooks(cl);

        // 官方原生 FragmentManager 生命周期监听器，彻底解决“晚点进/切Tab不显示”问题
        if (activity instanceof androidx.fragment.app.FragmentActivity) {
            try {
                androidx.fragment.app.FragmentActivity fa = (androidx.fragment.app.FragmentActivity) activity;
                fa.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                    new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                        @Override
                        public void onFragmentViewCreated(
                                androidx.fragment.app.FragmentManager fm,
                                androidx.fragment.app.Fragment f,
                                View v,
                                android.os.Bundle savedInstanceState) {
                            checkAndInjectListLin(v, cl);
                        }

                        @Override
                        public void onFragmentResumed(
                                androidx.fragment.app.FragmentManager fm,
                                androidx.fragment.app.Fragment f) {
                            View v = f.getView();
                            if (v != null) {
                                checkAndInjectListLin(v, cl);
                            }
                        }
                    }, true);
                mModule.log(Log.INFO, TAG, "Registered FragmentLifecycleCallbacks on HomeActivity for real-time Mine page detection");
            } catch (Throwable t) {
                mModule.log(Log.WARN, TAG, "Failed to register FragmentLifecycleCallbacks", t);
            }
        }

        scheduleActivityScan(activity, cl);
    }

    public void scheduleActivityScan(Activity activity, ClassLoader classLoader) {
        long[] delays = { 200, 600, 1500, 3000 };
        for (long delay : delays) {
            mMainHandler.postDelayed(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                    View decor = activity.getWindow().getDecorView();
                    checkAndInjectListLin(decor, classLoader);
                } catch (Throwable ignored) {
                }
            }, delay);
        }
    }

    private void checkAndInjectListLin(View root, ClassLoader classLoader) {
        if (root == null) return;
        mMainHandler.post(() -> {
            try {
                Context ctx = root.getContext();
                int listLinId = ctx.getResources().getIdentifier("list_lin", "id", ctx.getPackageName());
                if (listLinId != 0) {
                    View listLin = root.findViewById(listLinId);
                    if (listLin instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) listLin;
                        attachLayoutGuard(group, classLoader);
                        injectIntoViewGroup(group, classLoader);
                    }
                }
            } catch (Throwable t) {
                mModule.log(Log.WARN, TAG, "Error checking list_lin", t);
            }
        });
    }

    private void attachLayoutGuard(ViewGroup listLin, ClassLoader classLoader) {
        // 给 list_lin 绑定布局变动监听：当云控异步下发刷新导致 removeAllViews() 时，自动恢复设置条目
        listLin.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                if (group.findViewWithTag(ITEM_TAG) == null) {
                    mMainHandler.post(() -> injectIntoViewGroup(group, classLoader));
                }
            }
        });
    }

    private void injectIntoViewGroup(ViewGroup listLin, ClassLoader classLoader) {
        if (listLin == null || listLin.findViewWithTag(ITEM_TAG) != null) {
            return;
        }

        Context context = listLin.getContext();
        View itemView = null;

        // 尝试方案 A: 反射实例化官方 XlSingleTitleListItem
        try {
            Class<?> itemClass = classLoader.loadClass("com.xiaolian.ui_module.ui.widgets.XlSingleTitleListItem");
            Constructor<?> ctor = itemClass.getConstructor(Context.class);
            Object xlItem = ctor.newInstance(context);

            // 调用 setLeftText 或 setTitle
            boolean titleSet = false;
            for (String methodCandidate : new String[]{"setLeftText", "setTitle", "setLeftTitle"}) {
                try {
                    Method m = itemClass.getMethod(methodCandidate, CharSequence.class);
                    m.invoke(xlItem, "笑联Plus 设置");
                    titleSet = true;
                    break;
                } catch (NoSuchMethodException ignored) {
                }
            }

            // 尝试设置左侧图标：专属极简细线 Circle Plus (XiaoLianBadgeDrawable)
            try {
                io.github.yilink1.xiaolianplus.ui.XiaoLianBadgeDrawable badge =
                        new io.github.yilink1.xiaolianplus.ui.XiaoLianBadgeDrawable(context);
                boolean iconSet = false;
                for (String iconMethod : new String[]{"setLeftImage", "setLeftIcon", "setLeftDrawable"}) {
                    try {
                        Method m = itemClass.getMethod(iconMethod, android.graphics.drawable.Drawable.class);
                        m.invoke(xlItem, badge);
                        iconSet = true;
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }

                // 获取左侧 ImageView 实例
                ImageView ourIv = null;
                try {
                    Method getIv = itemClass.getMethod("getLeftImageView");
                    Object iv = getIv.invoke(xlItem);
                    if (iv instanceof ImageView) {
                        ourIv = (ImageView) iv;
                        if (!iconSet) {
                            ourIv.setImageDrawable(badge);
                            ourIv.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }

                // 像素级对齐校准：寻找 list_lin 中已有的官方条目，克隆其左侧图标的宽高、Margin 与 Padding
                if (ourIv != null && listLin.getChildCount() > 0) {
                    try {
                        for (int i = 0; i < listLin.getChildCount(); i++) {
                            View child = listLin.getChildAt(i);
                            if (child != null && itemClass.isInstance(child)) {
                                Method getIv = itemClass.getMethod("getLeftImageView");
                                Object refIvObj = getIv.invoke(child);
                                if (refIvObj instanceof ImageView) {
                                    ImageView refIv = (ImageView) refIvObj;
                                    ViewGroup.LayoutParams refLp = refIv.getLayoutParams();
                                    if (refLp != null) {
                                        ViewGroup.LayoutParams ourLp = ourIv.getLayoutParams();
                                        if (ourLp != null) {
                                            ourLp.width = refLp.width;
                                            ourLp.height = refLp.height;
                                            if (ourLp instanceof ViewGroup.MarginLayoutParams && refLp instanceof ViewGroup.MarginLayoutParams) {
                                                ViewGroup.MarginLayoutParams ourMlp = (ViewGroup.MarginLayoutParams) ourLp;
                                                ViewGroup.MarginLayoutParams refMlp = (ViewGroup.MarginLayoutParams) refLp;
                                                ourMlp.leftMargin = refMlp.leftMargin;
                                                ourMlp.rightMargin = refMlp.rightMargin;
                                                ourMlp.topMargin = refMlp.topMargin;
                                                ourMlp.bottomMargin = refMlp.bottomMargin;
                                            }
                                            ourIv.setLayoutParams(ourLp);
                                        }
                                        ourIv.setPadding(refIv.getPaddingLeft(), refIv.getPaddingTop(), refIv.getPaddingRight(), refIv.getPaddingBottom());
                                        ourIv.setScaleType(refIv.getScaleType());
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            if (titleSet && xlItem instanceof View) {
                itemView = (View) xlItem;
                mModule.log(Log.INFO, TAG, "Created native XlSingleTitleListItem with XiaoLianBadgeDrawable via reflection");
            }
        } catch (Throwable t) {
            mModule.log(Log.INFO, TAG, "Reflection on XlSingleTitleListItem fallback to custom View: " + t.getMessage());
        }

        // 方案 B: 降级自绘官方同款条目
        if (itemView == null) {
            itemView = createCustomListItem(context);
        }

        itemView.setTag(ITEM_TAG);
        itemView.setOnClickListener(v -> SettingsDialog.show(context));

        // 查找插入位置：优先插在“更多 (more_item)”前面，否则追加到末尾
        int insertIndex = listLin.getChildCount();
        int moreItemId = context.getResources().getIdentifier("more_item", "id", context.getPackageName());
        if (moreItemId != 0) {
            for (int i = 0; i < listLin.getChildCount(); i++) {
                if (listLin.getChildAt(i).getId() == moreItemId) {
                    insertIndex = i;
                    break;
                }
            }
        }

        listLin.addView(itemView, insertIndex);
        mModule.log(Log.INFO, TAG, "Successfully injected [笑联Plus 设置] item into list_lin at index " + insertIndex);
    }

    private View createCustomListItem(Context context) {
        int dp16 = dp2px(context, 16);
        int dp48 = dp2px(context, 48);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp16, 0, dp16, 0);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp48);
        row.setLayoutParams(rowLp);

        ImageView ivIcon = new ImageView(context);
        ivIcon.setImageDrawable(new io.github.yilink1.xiaolianplus.ui.XiaoLianBadgeDrawable(context));
        int iconSize = dp2px(context, 18);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp2px(context, 12);
        row.addView(ivIcon, iconLp);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("笑联Plus 设置");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor("#333333"));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tvTitle, titleLp);

        TextView tvArrow = new TextView(context);
        tvArrow.setText(">");
        tvArrow.setTextSize(14);
        tvArrow.setTextColor(Color.parseColor("#bfbfbf"));
        row.addView(tvArrow);

        return row;
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

    private static int dp2px(Context context, float dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }
}
