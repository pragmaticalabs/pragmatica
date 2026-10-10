// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// CLI half of the `POST /api/v1/cluster/upgrade` wire contract (#1424); `UpgradeRequestContractTest` in
/// `aether/node` is the server half. Same shape as `ClusterScaleCommandTest`: the CLI cannot depend on the module
/// holding the request record, so both spellings are pinned to the same field names.
class ClusterUpgradeCommandTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void buildUpgradeJson_emitsExactlyTheFieldNamesUpgradeRequestReads() {
        var json = ClusterUpgradeCommand.buildUpgradeJson("1.1.0", 42);

        MAPPER.readTree(json)
              .onSuccess(node -> {
                  assertThat(node.path("targetVersion").asText()).isEqualTo("1.1.0");
                  assertThat(node.path("expectedVersion").asLong()).isEqualTo(42);
              })
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }
}
