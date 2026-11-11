# Placeholder; later PRs add Onyx, pdfbox, kotlinx.serialization and @JavascriptInterface rules.
-keep class app.notate.** { *; }
# Onyx SDK is reached by reflection and from native code.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**
-keep class org.lsposed.hiddenapibypass.** { *; }
# pdfbox-android (PDF outline reading, R3-1): optional crypto / JPEG2000 classes are absent on purpose.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn com.gemalto.jp2.**
