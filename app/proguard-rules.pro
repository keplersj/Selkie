# Termux terminal libraries use JNI (native method names must survive) and reflection-free callbacks.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class io.github.keplersj.selkie.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.github.keplersj.selkie.**$$serializer { *; }
-keepclassmembers class io.github.keplersj.selkie.** { *** Companion; }
