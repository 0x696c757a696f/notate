plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "app.notate.library"; compileSdk = 36
    defaultConfig { minSdk = 26 }
    kotlin { jvmToolchain(21) }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}
dependencies {
    implementation(project(":next:platform"))
    implementation(project(":next:render"))
    implementation("app.notate.core:store")
    implementation("app.notate.core:doc")
    api(platform("androidx.compose:compose-bom:2026.01.00"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.compose.ui:ui")
    api("androidx.activity:activity-compose:1.12.2")
    testImplementation("junit:junit:4.13.2")
}
