rootProject.name = "yummypc"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":api")
include(":app")
include(":extractors")
include(":player-ipc")
