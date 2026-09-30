// SPDX-License-Identifier: GPL-2.0-or-later
// XCSoar Mobile: Kotlin/Compose UI on top of XCSoar's C++ core.
// See docs/ARCHITECTURE.md.

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

rootProject.name = "xcsoar-mobile"

// :core  pure Kotlin (no Android): API types, snapshot decoding, fake core
// :app   the Android app (Compose UI, JNI binding of libxcsoar_core.so)
include(":core", ":app")
