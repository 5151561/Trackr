# ProGuard and R8 Rules for Trackr

# --- Code Obfuscation Optimization ---
-repackageclasses 'com.trackr.internal'
-allowaccessmodification

# --- Kotlin & Coroutines ---
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Kotlinx Serialization ---
# 不再自己写宽规则（第 29 步）：kotlinx-serialization-core 1.11 自带 R8 规则
# （META-INF/com.android.tools/r8/kotlinx-serialization-*.pro），已保留 @Serializable 类的
# Companion 字段、Companion / 具名 Companion / object 的 serializer()、以及运行时注解。
# 本工程的序列化全部走插件生成的 serializer（Json.decodeFromString(X.serializer()) 或 reified
# decodeFromString<T>()，后者编译期内联成同样的调用），序列化名在编译期写成字符串，类名混淆不影响
# 读旧数据。返回栈存进 SavedState 用 TrackrSavedStateConfiguration 显式登记全部 ScreenKey 子类，
# 不走 Android 上按类名反射的 NavKeySerializer，所以 ScreenKey 也不用整类保留。
# 模型包、导航包里没有反射（Class.forName / ::class.java / qualifiedName 都没有），原先的
# -keep class com.trackr.core.model.** { *; } 等整包保留一并去掉。
-dontnote kotlinx.serialization.SerializationKt

# --- OkHttp & Okio（Ktor 的 Android 引擎）---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Android 组件 ---
# Manifest 里声明的 Activity / Service / Receiver / Provider（含合并进来的库组件）由 AGP 生成的
# aapt_rules.txt 保留类和无参构造，不用再按父类整类保留（第 29 步）。

# --- 高德地图 SDK（3dmap / location / search）---
# SDK 内部大量使用 JNI 回调与反射查找类名，混淆后会直接导致地图白屏或崩溃
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
# location 11.x 把实现类挪到 com.amap.location.**，libapssdk.so 的 JNI_OnLoad 按原类名
# FindClass（如 com.amap.location.support.log.ALLog）并回调这些类；被改名或裁掉会 SIGABRT
-keep class com.amap.location.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
-dontwarn com.amap.location.**
# 11.x 起内置的 location fusion 会反射引用可选的 GNSS 模块（com.amap.ams.*），
# 该模块未打进一体化包，属于「可选依赖缺失」，忽略即可
-dontwarn com.amap.ams.**


# --- Glance 小组件（第 26 步）---
# RunCallbackAction 用无参构造反射创建 ActionCallback
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
