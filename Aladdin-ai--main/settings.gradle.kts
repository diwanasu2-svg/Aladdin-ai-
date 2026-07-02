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
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots") }
        mavenLocal()
    }
}

rootProject.name = "Aladdin"
include(":app")
include(":ai-engine")
include(":smart-memory")
include(":tool-system")
include(":voice-core")
include(":internet")
include(":vision-system")
include(":plugin-system")
include(":sample-plugin")
include(":reliability-system")
include(":performance-optimization")
include(":security-system")
