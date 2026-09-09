# ============================================================
# webhome.jar 混淆保留规则
#
# 核心约束：spider jar 是被壳 DexClassLoader 反射加载的，
# 所有「对外契约」都不能动，只能混淆内部实现。
# 规则之外的东西全部会被重命名 / 内联 / 删除。
# ============================================================

# ---------- 1. Spider 契约（动了就加载失败或方法调不到） ----------

# 壳按 "com.github.catvod.spider." + api名 反射加载，包名+类名必须原样
-keep public class com.github.catvod.spider.** extends com.github.catvod.crawler.Spider {
    public <init>();
    public *;
    protected *;   /* scriptPath() / target() 是 protected abstract，子类覆盖，改名会连累 SECURE 模式 */
}

# siteKey 等公开字段由壳赋值
-keepclassmembers class com.github.catvod.spider.** {
    public java.lang.String siteKey;
}

# ---------- 2. JS 桥（名字写死在 JS 里，改了页面就调不通） ----------

-keepclassmembers class * {
    @android.webkit.JavascriptInterface *;
}

# ---------- 3. WebHome 对外静态方法 ----------

-keep public class com.github.catvod.spider.WebHome {
    public static <methods>;
}

# ---------- 4. 反射相关的类不能删 ----------

# ScriptHost 反射宿主 Loader 的类名字符串在别处，这里只是保险
-keep class com.github.catvod.spider.ScriptHost { public *; }
-keep class com.github.catvod.spider.ScriptProxy { public *; protected *; }
-keep class com.github.catvod.spider.SecureSpider { public *; protected *; }

# ---------- 5. 生成代码的入口 ----------

# ScriptAssets 的静态初始化块注册脚本，不能被优化掉
-keep class com.github.catvod.spider.ScriptAssets { *; }

# ---------- 6. Android 组件（生命周期回调） ----------

-keepclasseswithmembers class * {
    native <methods>;
}

# ---------- 7. 常规开关 ----------

-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-allowaccessmodification

# 保留行号会让反编译结果可读性暴增，关掉
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable  # 去掉这行可彻底删除行号（调试变难）

# 注解（@JavascriptInterface 需要）
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ---------- 8. 警告压制 ----------
# android.jar 是 stub，缺很多实现；org.json / chaquopy 不在编译期 classpath
-dontwarn android.**
-dontwarn org.json.**
-dontwarn com.chaquo.**
-dontwarn com.github.catvod.crawler.**

# ============================================================
# 可选：更激进（提高门槛，但风险上升，出问题先关掉这段）
# ============================================================
# 把未被 keep 的类全部打散重命名
# -repackageclasses com.github.catvod.x
# -flattenpackagehierarchy
# 移除日志调用
# -assumenosideeffects class android.util.Log {
#     public static *** d(...);
#     public static *** v(...);
#     public static *** i(...);
# }
