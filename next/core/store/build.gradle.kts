plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    api(project(":model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(project(":testing"))
}
