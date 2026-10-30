# ProGuard and R8 Rules for Trackr

# --- Code Obfuscation Optimization ---
-repackageclasses 'com.trackr.internal'
-allowaccessmodification

# --- Kotlin & Coroutines ---
-dontwarn kotlinx.coroutines.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Kotlinx Serialization ---
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}
-keepclassmembers class **$Companion {
    public kotlinx.serialization.KSerializer serializer(...);
}

# Keep Domain Models and Navigation Keys for JSON and Navigation3 Serialization
-keep class com.trackr.domain.model.** { *; }
-keep class com.trackr.ui.navigation.** { *; }
-keep class com.trackr.data.provider.model.** { *; }

# --- OkHttp & Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Android Components & Receivers ---
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.appwidget.AppWidgetProvider

# --- 高德地图 SDK（3dmap / location / search）---
# SDK 内部大量使用 JNI 回调与反射查找类名，混淆后会直接导致地图白屏或崩溃
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**
# 11.x 起内置的 location fusion 会反射引用可选的 GNSS 模块（com.amap.ams.*），
# 该模块未打进一体化包，属于「可选依赖缺失」，忽略即可
-dontwarn com.amap.ams.**

