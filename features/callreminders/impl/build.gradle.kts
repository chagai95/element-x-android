import extension.setupDependencyInjection
import extension.testCommonDependencies

/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

plugins {
    id("io.element.android-compose-library")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.element.android.features.callreminders.impl"

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

setupDependencyInjection()

dependencies {
    implementation(projects.libraries.architecture)
    implementation(projects.libraries.core)
    implementation(projects.libraries.designsystem)
    implementation(projects.libraries.featureflag.api)
    implementation(projects.libraries.matrix.api)
    implementation(projects.libraries.preferences.api)
    implementation(projects.libraries.uiStrings)
    implementation(libs.androidx.corektx)
    implementation(libs.serialization.json)
    api(projects.features.callreminders.api)

    testCommonDependencies(libs, true)
    testImplementation(projects.libraries.matrix.test)
}
