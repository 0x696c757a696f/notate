plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.notate.platform"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation("app.notate.core:input")
    implementation("app.notate.core:store")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
