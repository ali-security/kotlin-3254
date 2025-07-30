/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */


// usages in build scripts are not tracked properly
@file:Suppress("unused")

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val kotlinGradlePluginAndItsRequired = arrayOf(
    ":kotlin-assignment",
    ":compose-compiler-gradle-plugin",
    ":kotlin-allopen",
    ":kotlin-noarg",
    ":kotlin-power-assert",
    ":kotlin-sam-with-receiver",
    ":kotlin-lombok",
    ":kotlin-serialization",
    ":kotlin-parcelize-compiler",
    ":kotlin-compiler-embeddable",
    ":native:kotlin-native-utils",
    ":kotlin-util-klib",
    ":kotlin-util-io",
    ":kotlin-compiler-runner",
    ":kotlin-daemon-embeddable",
    ":kotlin-daemon-client",
    ":kotlin-gradle-plugins-bom",
    ":kotlin-gradle-plugin-api",
    ":kotlin-gradle-plugin-annotations",
    ":kotlin-gradle-plugin-idea",
    ":kotlin-gradle-plugin-idea-proto",
    ":kotlin-gradle-plugin",
    ":kotlin-gradle-plugin-model",
    ":kotlin-tooling-metadata",
    ":kotlin-tooling-core",
    ":kotlin-reflect",
    ":kotlin-test",
    ":kotlin-gradle-subplugin-example",
    ":kotlin-stdlib-common",
    ":kotlin-stdlib",
    ":kotlin-stdlib-jdk7",
    ":kotlin-stdlib-jdk8",
    ":kotlin-dom-api-compat",
    ":examples:annotation-processor-example",
    ":kotlin-assignment-compiler-plugin.embeddable",
    ":kotlin-allopen-compiler-plugin.embeddable",
    ":kotlin-noarg-compiler-plugin.embeddable",
    ":kotlin-power-assert-compiler-plugin.embeddable",
    ":kotlin-sam-with-receiver-compiler-plugin.embeddable",
    ":kotlin-lombok-compiler-plugin.embeddable",
    ":kotlinx-serialization-compiler-plugin.embeddable",
    ":kotlin-annotation-processing-embeddable",
    ":plugins:compose-compiler-plugin:compiler",
    ":kotlin-script-runtime",
    ":kotlin-scripting-common",
    ":kotlin-scripting-jvm",
    ":kotlin-scripting-compiler-embeddable",
    ":kotlin-scripting-compiler-impl-embeddable",
    ":native:kotlin-klib-commonizer-embeddable",
    ":native:kotlin-klib-commonizer-api",
    ":native:swift:swift-export-embeddable",
    ":compiler:build-tools:kotlin-build-statistics",
    ":compiler:build-tools:kotlin-build-tools-api",
    ":compiler:build-tools:kotlin-build-tools-impl",
    ":libraries:tools:gradle:fus-statistics-gradle-plugin",
    ":kotlin-util-klib-metadata",
    ":libraries:tools:abi-validation:abi-tools-api",
    ":libraries:tools:abi-validation:abi-tools",
    ":kotlin-metadata-jvm",
    ":gradle:kotlin-gradle-ecosystem-plugin",
)

fun Task.dependsOnKotlinGradlePluginInstall() {
    kotlinGradlePluginAndItsRequired.forEach { dependency ->
        dependsOn("${dependency}:install")
    }
}

fun Task.dependsOnKotlinGradlePluginPublish() {
    kotlinGradlePluginAndItsRequired
        .filter {
            // Compose compiler plugin does not assemble with LV 1.9 and should not be a part of the dist bundle for now
            it != ":plugins:compose-compiler-plugin:compiler"
        }
        .forEach { dependency ->
            project.rootProject.tasks.findByPath("${dependency}:publish")?.let { task ->
                dependsOn(task)
            }
        }
}

private inline fun String.isFirstChar(f: (Char) -> Boolean) = isNotEmpty() && f(first())

fun Project.confugureFirPluginAnnotationsDependency(testTask: TaskProvider<Test>) {
    val firPluginJvmAnnotations: Configuration by configurations.creating
    val firPluginJsAnnotations: Configuration by configurations.creating {
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages.KOTLIN_RUNTIME))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.js)
        }
    }

    dependencies {
        firPluginJvmAnnotations(project(":plugins:plugin-sandbox:plugin-annotations")) { isTransitive = false }
        firPluginJsAnnotations(project(":plugins:plugin-sandbox:plugin-annotations")) { isTransitive = false }
    }

    testTask.configure {
        dependsOn(firPluginJvmAnnotations, firPluginJsAnnotations)
        val localFirPluginJvmAnnotations: FileCollection = firPluginJvmAnnotations
        val localFirPluginJsAnnotations: FileCollection = firPluginJsAnnotations
        doFirst {
            systemProperty("firPluginAnnotations.jvm.path", localFirPluginJvmAnnotations.singleFile.canonicalPath)
            systemProperty("firPluginAnnotations.js.path", localFirPluginJsAnnotations.singleFile.canonicalPath)
        }
    }
}

fun Project.optInTo(annotationFqName: String) {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions.optIn.add(annotationFqName)
    }
}

fun Project.optInToExperimentalCompilerApi() {
    optInTo("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
}

fun Project.optInToUnsafeDuringIrConstructionAPI() {
    optInTo("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
}

fun Project.optInToObsoleteDescriptorBasedAPI() {
    optInTo("org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI")
}

fun Project.optInToK1Deprecation() {
    optInTo("org.jetbrains.kotlin.K1Deprecation")
}
