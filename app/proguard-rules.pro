# Xray VLESS VPN ProGuard Rules

# Manter classes principais
-keep class com.vpn.xrayvless.** { *; }
-keep class com.google.gson.** { *; }

# Manter informações de linha para debugging
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**
