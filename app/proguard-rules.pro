# JNA 通过接口反射定位 native 方法，全量保留
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# TDLib so 以 dlopen 方式加载
-keep class com.yg.tgdrive.td.** { *; }
# JNA 的 AWT/Swing 分支在 Android 上不存在（Native$AWT 引用 java.awt.*）
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.naming.**
-dontwarn java.beans.**
-dontwarn sun.misc.**

# JNA 的 Structure 子类靠字段名映射 native 内存，需保留字段
-keepclassmembers class * extends com.sun.jna.Structure { <fields>; }
-keepclassmembers class * extends com.sun.jna.PointerType { <fields>; }
