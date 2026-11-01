pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version "2.2.0"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("http://repo.boox.com/repository/maven-public/")
            isAllowInsecureProtocol = true
        }
    }
}
rootProject.name = "Notate"
include(":app")
include(":ocr-runtime")

// Notate Next (rewrite, see plans/rewrite): pure-JVM core as an included build + Android shells.
includeBuild("next/core")
include(
    ":next:platform", ":next:render", ":next:editor", ":next:pdf", ":next:web",
    ":next:export", ":next:search", ":next:library", ":next:app",
)
listOf("platform", "render", "editor", "pdf", "web", "export", "search", "library", "app").forEach {
    project(":next:$it").projectDir = file("next/android/$it")
}
