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
}
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "harness-hub"

include(":app")
include(":core:model")
include(":core:harness-api")
include(":core:termux")
include(":core:transport")
include(":core:discovery")
include(":harness:hermes")
include(":harness:claude")
