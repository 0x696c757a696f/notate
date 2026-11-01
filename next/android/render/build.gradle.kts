plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.notate.render"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation("app.notate.core:model")
    implementation("app.notate.core:ink")
    implementation("app.notate.core:doc")
    testImplementation("junit:junit:4.13.2")
}
