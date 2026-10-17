// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.ClusterConfigResponse;
import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// Server half of the `GET /api/v1/cluster/config` -> CLI contract. `aether cluster upgrade` (#1424), `scale` and
/// `apply` read the fence version as the JSON field `configVersion` of this response. Nothing pinned that name: the
/// CLI cannot depend on this module, and a CLI that read a misnamed field fell back to 0, which the server refuses as
/// an unfenced overwrite (409) on EVERY upgrade, with all 954 CLI tests still green.
class ClusterConfigResponseContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void clusterConfigResponse_carriesTheFenceVersion_underTheNameTheCliReads() {
        var response = new ClusterConfigResponse("", "prod", "1.0.0", 3, List.of(), 3, 9, "hetzner", 7, 0);

        var json = MAPPER.writeAsString(response).flatMap(MAPPER::readTree);

        assertThat(json.isSuccess()).as(json.toString()).isTrue();
        assertThat(json.unwrap().path("configVersion").asLong(-1)).isEqualTo(7);
        assertThat(json.unwrap().path("version").asText()).as("the CLI's already-at-version check reads this name").isEqualTo("1.0.0");
    }
}
