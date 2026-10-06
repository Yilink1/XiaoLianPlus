# LibXposed API rules
-keep class io.github.libxposed.api.** { *; }
-dontwarn io.github.libxposed.api.**

# XiaoLianPlus entry point
-keep public class io.github.yilink1.xiaolianplus.XiaoLianModule {
    public <init>(...);
    *;
}

# Keep Hookers, Config, and UI
-keep class io.github.yilink1.xiaolianplus.hook.** { *; }
-keep class io.github.yilink1.xiaolianplus.config.** { *; }
-keep class io.github.yilink1.xiaolianplus.ui.** { *; }

# Keep all classes implementing XposedInterface$Hooker
-keep class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public <init>(...);
    *;
}

# Keep reflection metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
