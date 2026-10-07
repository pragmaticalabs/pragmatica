// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// Server half of the `POST /api/v1/cluster/upgrade` wire contract (#1424); the CLI half is
/// `ClusterUpgradeCommandTest`. The CLI cannot depend on `aether/node`, so the request is spelled twice and these
/// two pin both spellings to the same field names, as `ScaleRequestContractTest` does for scale.
class UpgradeRequestContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// The exact body `ClusterUpgradeCommand.buildUpgradeJson("1.1.0", 42)` produces.
    private static final String CLI_BODY = "{\"targetVersion\":\"1.1.0\",\"expectedVersion\":42}";

    @Test
    void upgradeRequest_deserializesEveryFieldTheCliSends() {
        var parsed = MAPPER.readString(CLI_BODY, UpgradeRequest.class);

        assertThat(parsed.isSuccess()).as("CLI upgrade body must deserialize into UpgradeRequest: " + parsed).isTrue();
        parsed.onSuccess(request -> {
            assertThat(request.targetVersion()).isEqualTo("1.1.0");
            assertThat(request.expectedVersion()).isEqualTo(42L);
        });
    }
}
