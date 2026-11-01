pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories { mavenCentral() } // no google(): unreachable from cloud sessions
}
rootProject.name = "notate-core"
include(":geometry", ":model", ":store", ":ink", ":doc", ":input", ":workspace", ":web", ":search", ":legacy", ":testing")
