# Placeholder; later PRs add Onyx, pdfbox, kotlinx.serialization and @JavascriptInterface rules.
-keep class app.notate.** { *; }
# Onyx SDK is reached by reflection and from native code.
-keep class com.onyx.** { *; }
-dontwarn com.onyx.**
-keep class org.lsposed.hiddenapibypass.** { *; }
