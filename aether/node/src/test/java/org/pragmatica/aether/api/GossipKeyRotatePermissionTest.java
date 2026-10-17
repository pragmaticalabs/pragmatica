// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

/// #683: the rotation route is an EXACT ADMIN row, and an appended segment is a routing miss (404
/// at dispatch) that still resolves ADMIN.
///
/// **What holds the over-length case, corrected in round 2.** This was originally labelled "#1101's
/// shape". It is not: `/api/v1/cluster` appears in neither `RoutePermissionRegistry.Prefixes.ADMIN`
/// nor `.OPERATOR`, so `resolveMutationPermission` returns `ADMIN_ONLY` by DENY-BY-DEFAULT. The
/// protection is real and in fact doubly held — the registry default and #1101's
/// `strictestOf(fallback, extendedExactRoutes(...))` — but removing the #1101 arm leaves this test
/// green, so it cannot speak to that mechanism and must not claim to. `LengthenedPathPermissionMatrixTest`
/// walks every mutating route and is the instrument that does pin #1101; it covers this row
/// automatically.
class GossipKeyRotatePermissionTest {
    @Test
    void exactRoute_isAdmin_appendedSegment_isAMissAndStillAdmin() {
        var exact = ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE.assemble(List.of()).unwrap();

        assertThat(ManagementServerImpl.resolvePermission("POST", exact).minimumRole()).isEqualTo(AuthorizationRole.ADMIN);
        assertThat(ManagementRoute.match(HttpMethod.POST, exact).isSuccess()).as("control: the exact path dispatches").isTrue();
        assertThat(ManagementRoute.match(HttpMethod.POST, exact + "/junk").isSuccess()).as("over-length is a miss (404)").isFalse();
        assertThat(ManagementServerImpl.resolvePermission("POST", exact + "/junk").minimumRole())
                .as("over-length still resolves ADMIN — by the registry's deny-by-default, NOT by #1101")
                .isEqualTo(AuthorizationRole.ADMIN);
    }
}
