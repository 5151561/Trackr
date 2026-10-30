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
