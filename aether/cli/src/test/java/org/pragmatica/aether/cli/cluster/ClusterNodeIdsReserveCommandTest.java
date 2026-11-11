// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.json.JsonMapper;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1715: `aether cluster node-ids reserve`. CLI half of the `POST /api/cluster/node-ids/reserve` contract;
/// `NodeIdRoutesTest` in `aether/node` is the server half, over `ReserveNodeIdsRequest`.
class ClusterNodeIdsReserveCommandTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void buildReserveJson_emitsExactlyTheFieldNamesReserveNodeIdsRequestReads() {
        MAPPER.readTree(ClusterNodeIdsReserveCommand.buildReserveJson("eu-central", "worker", 3))
              .onSuccess(node -> {
                  assertThat(node.path("source").asText()).isEqualTo("eu-central");
                  assertThat(node.path("role").asText()).isEqualTo("worker");
                  assertThat(node.path("count").asInt()).isEqualTo(3);
                  assertThat(node.size()).isEqualTo(3);
              })
              .onFailure(cause -> fail(cause.message()));
    }

    /// A quote in a source name must stay inside its string: the body is data, never a way to add fields.
    @Test
    void buildReserveJson_escapesTheNames() {
        MAPPER.readTree(ClusterNodeIdsReserveCommand.buildReserveJson("a\",\"count\":99,\"x\":\"", "core", 1))
              .onSuccess(node -> {
                  assertThat(node.path("count").asInt()).isEqualTo(1);
                  assertThat(node.path("source").asText()).isEqualTo("a\",\"count\":99,\"x\":\"");
              })
              .onFailure(cause -> fail(cause.message()));
    }

    @Test
    void successMessage_listsTheReservedIds() {
        var json = "{\"source\":\"s\",\"role\":\"core\",\"ids\":[\"s-core-1\",\"s-core-2\"],\"highWater\":2}";

        assertThat(ClusterNodeIdsReserveCommand.successMessage(json)).isEqualTo("Reserved: s-core-1, s-core-2");
    }

    @Test
    void successMessage_fallsBack_whenTheAnswerIsNotJson() {
        assertThat(ClusterNodeIdsReserveCommand.successMessage("not json")).isEqualTo("Node ids reserved.");
    }

    @Test
    void reserve_isRegisteredUnderClusterNodeIds() {
        var cluster = new CommandLine(new AetherCli()).getSubcommands().get("cluster");

        assertThat(cluster.getSubcommands()).containsKey("node-ids");
        assertThat(cluster.getSubcommands().get("node-ids").getSubcommands()).containsKey("reserve");
    }

    @Test
    void reserve_refusesAMissingSource_withTheUsageExitCode() {
        var originalErr = System.err;
        var err = new ByteArrayOutputStream();

        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var code = new CommandLine(new AetherCli()).execute("cluster", "node-ids", "reserve", "--count", "2");

            assertThat(code).isEqualTo(CommandLine.ExitCode.USAGE);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("--source");
        } finally {
            System.setErr(originalErr);
        }
    }
}
