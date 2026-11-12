pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Cielo"
include(":app")
// The Kit: Cerniera's shared design system, pinned as a git submodule
include(":kit")
project(":kit").projectDir = file("kit/kit")
