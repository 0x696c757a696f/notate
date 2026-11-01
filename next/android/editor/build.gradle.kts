plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.notate.editor"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation(project(":next:render"))
    implementation(project(":next:platform"))
    implementation("app.notate.core:doc")
    implementation("app.notate.core:input")
    implementation("app.notate.core:workspace")
    testImplementation("junit:junit:4.13.2")
}
