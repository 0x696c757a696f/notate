plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
}
subprojects {
    group = "app.notate.core"
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    dependencies {
        "testImplementation"("junit:junit:4.13.2")
        "testImplementation"("com.google.truth:truth:1.4.5")
        "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }
    tasks.withType<Test> { useJUnit(); maxHeapSize = "1g" }
}
