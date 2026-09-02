// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// CLI half of the `POST /api/cluster/scale` wire contract; `ScaleRequestContractTest` in
/// `aether/node` is the server half.
///
/// The CLI cannot depend on `aether/node`, where the request record lives, so the contract is
/// spelled twice and drifted: this command sent `count`/`role`/`source` while the record read a
/// lone `coreCount`. These two tests pin both spellings to the same field names, so a unilateral
/// rename on either side goes red.
class ClusterScaleCommandTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void buildScaleJson_emitsExactlyTheFieldNamesScaleRequestReads() {
        var json = ClusterScaleCommand.buildScaleJson("eu-central", "worker", 8, 42);

        MAPPER.readTree(json)
              .onSuccess(node -> {
                  assertThat(node.path("source").asText()).isEqualTo("eu-central");
                  assertThat(node.path("role").asText()).isEqualTo("worker");
                  assertThat(node.path("count").asInt()).isEqualTo(8);
                  assertThat(node.path("expectedVersion").asLong()).isEqualTo(42);
              })
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }

    /// `coreCount` is the field name that broke the contract. Its absence is the assertion.
    @Test
    void buildScaleJson_carriesNoClusterWideCoreCount() {
        var json = ClusterScaleCommand.buildScaleJson("eu-central", "core", 5, 1);

        assertThat(json).doesNotContain("coreCount");
    }

    /// A blank source is the "server, infer it" signal. It must be sent as an empty string rather
    /// than omitted, so the server distinguishes "not specified" from a malformed body.
    @Test
    void buildScaleJson_sendsBlankSource_whenOperatorDidNotNameOne() {
        var json = ClusterScaleCommand.buildScaleJson("", "core", 5, 1);

        MAPPER.readTree(json)
              .onSuccess(node -> {
                  assertThat(node.has("source")).isTrue();
                  assertThat(node.path("source").asText()).isEmpty();
              })
              .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
    }
}
