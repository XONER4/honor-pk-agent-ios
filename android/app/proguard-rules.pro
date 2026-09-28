# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.honerai.app.**$$serializer { *; }
-keepclassmembers class com.honerai.app.** { *** Companion; }
-keepclasseswithmembers class com.honerai.app.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Jsoup / PdfBox
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# WebView JavaScript bridge
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
