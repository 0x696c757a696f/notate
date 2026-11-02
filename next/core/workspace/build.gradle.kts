plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    api(project(":model"))
    api(project(":store"))
    testImplementation(project(":testing"))
}
