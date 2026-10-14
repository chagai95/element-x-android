/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.unifiedpush

object UnifiedPushConfig {
    /**
     * It is the push gateway for UnifiedPush.
     * Note: default_push_gateway_http_url should have path '/_matrix/push/v1/notify'
     */
    const val DEFAULT_PUSH_GATEWAY_HTTP_URL: String = "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"

    /**
     * Chagai fork: the gateway the homeserver is told to POST to, regardless of what the
     * distributor's own server advertises.
     *
     * Upstream derives the gateway from the endpoint's host whenever that host answers the
     * UnifiedPush discovery request with a matrix gateway — for ntfy.sh that is
     * `https://ntfy.sh/_matrix/push/v1/notify`. That is the *phone's* view of the world, and it
     * is wrong whenever the **homeserver** cannot reach that host: chagai.website only reaches
     * ntfy.sh over IPv6, and its Synapse container has no IPv6 at all, so every push to
     * ntfy.sh's own gateway silently fails and the phone stays quiet.
     *
     * Pointing at the public UnifiedPush matrix gateway (which the homeserver can reach) fixes
     * that: it accepts the matrix push and relays it to whatever endpoint the distributor
     * handed out, ntfy.sh included. Exactly the same event-id-only payload travels either way.
     *
     * Set this back to null once a distributor server the homeserver can reach directly is in
     * use (a self-hosted ntfy on the same box, say) — then the upstream derivation is better.
     */
    val FORCED_PUSH_GATEWAY_HTTP_URL: String? = DEFAULT_PUSH_GATEWAY_HTTP_URL

    const val UNIFIED_PUSH_DISTRIBUTORS_URL = "https://unifiedpush.org/users/distributors/"

    const val INDEX = 1
    const val NAME = "UnifiedPush"
}
