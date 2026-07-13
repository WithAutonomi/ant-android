pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Published Autonomi SDK (ant-android) — GitHub Pages Maven repo.
        maven("https://withautonomi.github.io/ant-maven/")
    }
}
rootProject.name = "AntAndroidDemo"
include(":app")
