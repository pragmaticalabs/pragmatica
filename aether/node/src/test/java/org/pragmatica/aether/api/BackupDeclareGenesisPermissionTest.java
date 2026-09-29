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

/// #1532: declaring genesis supersedes the cluster's backup head — ADMIN only, exact path.
class BackupDeclareGenesisPermissionTest {
    @Test
    void declareGenesis_isAdmin_atItsExactPath() {
        var exact = ManagementRoute.BACKUP_DECLARE_GENESIS.assemble(List.of())
                                                          .unwrap();

        assertThat(ManagementRoute.match(HttpMethod.POST, exact)
                                  .isSuccess()).as("the exact path dispatches")
                                               .isTrue();
        assertThat(ManagementServerImpl.resolvePermission("POST", exact)
                                       .minimumRole()).isEqualTo(AuthorizationRole.ADMIN);
    }
}
