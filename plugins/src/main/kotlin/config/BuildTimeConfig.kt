/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package config

object BuildTimeConfig {
    // Chagai fork: branded as "Matrix Chat" with its own application id so it installs
    // side by side with the upstream Element X build.
    const val APPLICATION_ID = "website.chagai.matrixchat"
    const val APPLICATION_NAME = "Matrix Chat"
    val METADATA_HOST_REVERSED: String? = "website.chagai.matrixchat"
    val OAUTH_CLIENT_URL_PATH: String? = "apps/android"
    val URL_WEBSITE: String? = "https://chagai.website/matrix-chat/"
    val URL_LOGO: String? = "https://chagai.website/matrix-chat/icon.png"
    val URL_COPYRIGHT: String? = "https://chagai.website/matrix-chat/#licence"
    val URL_ACCEPTABLE_USE: String? = "https://chagai.website/matrix-chat/#terms"
    val URL_PRIVACY: String? = "https://chagai.website/matrix-chat/#privacy"
    val URL_POLICY: String? = "https://chagai.website/matrix-chat/#privacy"
    val SERVICES_MAPTILER_BASE_URL: String? = null
    val SERVICES_MAPTILER_APIKEY: String? = null
    val SERVICES_MAPTILER_LIGHT_MAPID: String? = null
    val SERVICES_MAPTILER_DARK_MAPID: String? = null
    val SERVICES_POSTHOG_HOST: String? = null
    val SERVICES_POSTHOG_APIKEY: String? = null
    val SERVICES_SENTRY_DSN: String? = null
    val SERVICES_SENTRY_DSN_RUST: String? = null
    val BUG_REPORT_URL: String? = null
    val BUG_REPORT_APP_NAME: String? = null
    const val PUSH_CONFIG_INCLUDE_FIREBASE: Boolean = true
    const val PUSH_CONFIG_INCLUDE_UNIFIED_PUSH: Boolean = true
    // Chagai fork: the pusher the homeserver stores must be identified as THIS app, otherwise
    // it is filed under upstream Element X's "im.vector.app.android" and the two builds fight
    // over the same pusher slot when both are signed in to the same account.
    val PUSHER_APP_ID_RELEASE: String? = "website.chagai.matrixchat"
    val PUSHER_APP_ID_DEBUG: String? = "website.chagai.matrixchat.debug"
    val PUSHER_APP_ID_NIGHTLY: String? = "website.chagai.matrixchat.nightly"
}
