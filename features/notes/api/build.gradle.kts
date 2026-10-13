/*
 * Copyright (c) 2026 Chagai Friedlander
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
plugins {
    id("io.element.android-compose-library")
}

android {
    namespace = "io.element.android.features.notes.api"
}

dependencies {
    implementation(projects.libraries.matrix.api)
}
