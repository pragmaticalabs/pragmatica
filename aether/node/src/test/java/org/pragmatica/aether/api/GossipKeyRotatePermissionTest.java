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

/// #683 with #1101's shape: the rotation route is an EXACT ADMIN row; an appended segment is a routing
/// miss (404 at dispatch) and never resolves a weaker permission than the exact route.
class GossipKeyRotatePermissionTest {
    @Test
    void exactRoute_isAdmin_appendedSegment_isAMissAndNeverWeaker() {
        var exact = ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE.assemble(List.of()).unwrap();

        assertThat(ManagementServerImpl.resolvePermission("POST", exact).minimumRole()).isEqualTo(AuthorizationRole.ADMIN);
        assertThat(ManagementRoute.match(HttpMethod.POST, exact).isSuccess()).as("control: the exact path dispatches").isTrue();
        assertThat(ManagementRoute.match(HttpMethod.POST, exact + "/junk").isSuccess()).as("over-length is a miss (404)").isFalse();
        assertThat(ManagementServerImpl.resolvePermission("POST", exact + "/junk").minimumRole())
                .as("the fallback never resolves weaker than ADMIN for it")
                .isEqualTo(AuthorizationRole.ADMIN);
    }
}
