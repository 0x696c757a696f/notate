plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.notate.platform"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    api("app.notate.core:model")
    api("app.notate.core:input")
    api("app.notate.core:ink")
    api("app.notate.core:store")
    api("app.notate.core:doc")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core:1.16.0")   // FileProvider for sharing logs and crash reports

    // Onyx SDK: this module is the only one allowed to depend on it.
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4")
    implementation("com.onyx.android.sdk:onyxsdk-base:1.8.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    testImplementation("app.notate.core:testing")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
