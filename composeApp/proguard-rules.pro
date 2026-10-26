# ProGuard rules for release builds (packageRelease*, runRelease).
# Compose Desktop already applies its own default rules for Compose and Skiko.

# JavaCPP / FFmpeg: classes are looked up by name from JNI and Loader reflection.
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**

# kotlinx.serialization: keep generated serializers and the companions that expose them.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers class **$$serializer { *** INSTANCE; }
-keep,includedescriptorclasses class com.programmersbox.scrcpyviewer.**$$serializer { *; }
-keepclassmembers class com.programmersbox.scrcpyviewer.** {
    *** Companion;
}
-keepclasseswithmembers class com.programmersbox.scrcpyviewer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coroutines: the Swing dispatcher is found through ServiceLoader.
-keep class kotlinx.coroutines.swing.SwingDispatcherFactory { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# Optional dependencies referenced by libraries but absent at runtime.
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
