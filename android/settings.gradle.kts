pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "goga-android"
include(
    ":app",
    ":core:model",
    ":core:data",
    ":core:network",
    ":feature:notes",
    ":feature:tasks",
    ":feature:calendar",
    ":feature:assistant",
)
project(":core:model").projectDir = file("../shared/model")
