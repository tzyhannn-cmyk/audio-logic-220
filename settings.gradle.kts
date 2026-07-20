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
        // Repository JitPack (Wajib agar library NewPipe Extractor bisa diunduh)
        maven { url = java.net.URI("https://jitpack.io") }
    }
}

rootProject.name = "Pro GoPlayer"
include(":app")

