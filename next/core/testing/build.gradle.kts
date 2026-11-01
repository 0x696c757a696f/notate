plugins { kotlin("jvm") }
dependencies {
    api(project(":store"))
    api("org.xerial:sqlite-jdbc:3.50.3.0")
    api("junit:junit:4.13.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
