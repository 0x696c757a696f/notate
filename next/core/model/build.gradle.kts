plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    api(project(":geometry"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.10.0")
}
