// SPDX-License-Identifier: GPL-2.0-or-later

import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.xcsoar.mobile"
    // Android 17 platforms have minor versions (SDK package android-37.2)
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        // installs alongside upstream XCSoar (mobile/docs/DECISIONS.md D8)
        applicationId = "org.xcsoar.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

/* ---- inputs from the XCSoar tree (built, not copied into git) ---- */

val xcsoarRoot = rootProject.layout.projectDirectory.dir("..")

/** Copies files into a generated source/asset directory. */
abstract class CopyIntoGenerated : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    /** Sub-directory inside [outputDir], e.g. a Java package path. */
    @get:Input
    abstract val subDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val target = out.resolve(subDirectory.get()).apply { mkdirs() }
        sources.forEach { it.copyTo(target.resolve(it.name)) }
    }
}

/**
 * Upstream Java classes whose JNI counterparts are in libxcsoar_core.so;
 * reused unchanged in package org.xcsoar (mobile/docs/DECISIONS.md D13).
 */
val upstreamJava = tasks.register<CopyIntoGenerated>("upstreamJava") {
    sources.from(listOf(
        "InternalGPS", "NonGPSSensors", "NativeSensorListener",
        "SensorListener", "AndroidSensor", "PermissionManager", "SafeDestruct",
    ).map { xcsoarRoot.file("android/src/$it.java") })
    subDirectory.set("org/xcsoar")
}

/** A recorded flight for the "Replay demo" action. */
val demoFlight = tasks.register<CopyIntoGenerated>("demoFlight") {
    sources.from(xcsoarRoot.file("test/data/01lz1hq1.igc"))
    subDirectory.set("")
}

/**
 * Builds libxcsoar_core.so with XCSoar's Makefile (mobile/docs/DECISIONS.md
 * D7) for each ABI and puts it where Gradle packages native libraries.
 * make decides what is out of date, so this task always runs.
 */
abstract class NativeCoreLibrary : DefaultTask() {
    @get:Input
    abstract val abis: ListProperty<String>

    @get:Internal
    abstract val repoRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val root = repoRoot.get().asFile
        val targets = mapOf("arm64-v8a" to "ANDROIDAARCH64", "x86_64" to "ANDROIDX64")
        for (abi in abis.get()) {
            val target = targets[abi] ?: error("unsupported ABI $abi")
            exec.exec {
                workingDir = root
                commandLine("bash", "-c",
                    "source mobile/tools/env.sh >/dev/null && xmake TARGET=$target core")
            }
            val lib = root.resolve("output/ANDROID/$abi/dbg/bin/libxcsoar_core.so")
            lib.copyTo(outputDir.get().asFile.resolve("$abi/libxcsoar_core.so"), overwrite = true)
        }
    }
}

val nativeCore = tasks.register<NativeCoreLibrary>("nativeCore") {
    // -Pxcsoar.abis=arm64-v8a,x86_64 (arm64 covers phones and Apple
    // Silicon emulators)
    abis.set((findProperty("xcsoar.abis") as String? ?: "arm64-v8a").split(","))
    repoRoot.set(xcsoarRoot)
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(upstreamJava, CopyIntoGenerated::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(demoFlight, CopyIntoGenerated::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeCore, NativeCoreLibrary::outputDir)
    }
}
