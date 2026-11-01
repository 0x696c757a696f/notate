plugins { kotlin("jvm") }
dependencies {
    api(project(":store"))
    api(project(":ink"))
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(project(":testing"))
}
