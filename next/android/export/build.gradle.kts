plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.notate.export"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation(project(":next:render"))
    implementation(project(":next:platform"))
    implementation("app.notate.core:model")
    implementation("app.notate.core:store")
    implementation("app.notate.core:legacy")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.documentfile:documentfile:1.1.0")

    testImplementation("app.notate.core:testing")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.google.truth:truth:1.4.5")
}
