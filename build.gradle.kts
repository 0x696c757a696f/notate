buildscript {
    repositories {
        google()
    }
    dependencies {
        // R8 9.2.14 bundled with AGP 9.2.1 crashes (NPE in its API database)
        // on androidx.core LocationManagerCompat during minifyReleaseWithR8.
        // Pin a newer R8 (9.2.39 builds cleanly) until AGP ships one.
        classpath("com.android.tools:r8:9.2.39")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
