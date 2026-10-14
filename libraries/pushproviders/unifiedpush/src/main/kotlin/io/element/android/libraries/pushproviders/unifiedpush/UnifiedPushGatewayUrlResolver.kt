/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

interface UnifiedPushGatewayUrlResolver {
    fun resolve(
        gatewayResult: UnifiedPushGatewayResolverResult,
        instance: String,
    ): String
}

@ContributesBinding(AppScope::class)
class DefaultUnifiedPushGatewayUrlResolver(
    private val unifiedPushStore: UnifiedPushStore,
    private val defaultPushGatewayHttpUrlProvider: DefaultPushGatewayHttpUrlProvider,
) : UnifiedPushGatewayUrlResolver {
    override fun resolve(
        gatewayResult: UnifiedPushGatewayResolverResult,
        instance: String,
    ): String {
        // Chagai fork: see UnifiedPushConfig.FORCED_PUSH_GATEWAY_HTTP_URL — the gateway has to be
        // reachable by the HOMESERVER, which is not the same thing as reachable by the phone.
        UnifiedPushConfig.FORCED_PUSH_GATEWAY_HTTP_URL?.let { return it }
        return when (gatewayResult) {
            is UnifiedPushGatewayResolverResult.Error -> {
                // Use previous gateway if any, or the default one
                unifiedPushStore.getPushGateway(instance)
                    ?: defaultPushGatewayHttpUrlProvider.provide()
            }
            UnifiedPushGatewayResolverResult.ErrorInvalidUrl,
            UnifiedPushGatewayResolverResult.NoMatrixGateway -> {
                defaultPushGatewayHttpUrlProvider.provide()
            }
            is UnifiedPushGatewayResolverResult.Success -> {
                gatewayResult.gateway
            }
        }
    }
}
