// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.labels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class ContainerLabelInspectorTest {
    @Test
    void parseLabels_extractsAetherClusterAndNodeId() {
        var body = """
                   {"Id":"abc123","Config":{"Image":"aether:latest","Labels":{"aether.cluster":"prod-us","aether.node-id":"node-1","aether.role":"core"}}}""";
        var labels = ContainerLabelInspector.parseLabels(body);
        assertThat(labels).containsEntry("aether.cluster", "prod-us")
                          .containsEntry("aether.node-id", "node-1")
                          .containsEntry("aether.role", "core");
    }

    @Test
    void parseLabels_emptyLabelsBlock_returnsEmptyMap() {
        var body = """
                   {"Id":"abc","Config":{"Labels":{}}}""";
        assertThat(ContainerLabelInspector.parseLabels(body)).isEmpty();
    }

    @Test
    void parseLabels_noLabelsField_returnsEmptyMap() {
        var body = """
                   {"Id":"abc","Config":{}}""";
        assertThat(ContainerLabelInspector.parseLabels(body)).isEmpty();
    }

    @Test
    void compareWithConfigured_matchingNames_succeeds() {
        var labels = Map.of("aether.cluster", "us-prod", "aether.node-id", "node-1");
        var result = ContainerLabelInspector.compareWithConfigured("us-prod", labels);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void compareWithConfigured_mismatchedNames_fails() {
        var labels = Map.of("aether.cluster", "us-prod", "aether.node-id", "node-1");
        var result = ContainerLabelInspector.compareWithConfigured("eu-prod", labels);
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ContainerLabelInspector.ContainerLabelMismatch.class));
    }

    @Test
    void compareWithConfigured_emptyConfigured_skips() {
        var labels = Map.of("aether.cluster", "us-prod");
        var result = ContainerLabelInspector.compareWithConfigured("", labels);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void compareWithConfigured_emptyLabelValue_skips() {
        var result = ContainerLabelInspector.compareWithConfigured("us-prod", Map.of());
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void mismatchError_messageNamesBothSides() {
        var error = new ContainerLabelInspector.ContainerLabelMismatch("eu-prod", "us-prod");
        assertThat(error.message()).contains("'eu-prod'")
                                                .contains("'us-prod'")
                                                .contains("aborting startup");
    }

    @Test
    void inspectSelfLabels_markerAbsent_returnsEmptyEvenWhenSocketAnswers(@TempDir Path dir) throws Exception {
        var socket = dir.resolve("docker.sock");

        try (var server = serveOnce(socket, LABELS_RESPONSE)) {
            var result = ContainerLabelInspector.inspectSelfLabels(dir.resolve("absent-dockerenv"), socket, Option.some("node-1"));

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Test
    void inspectSelfLabels_markerPresentSocketAbsent_returnsEmpty(@TempDir Path dir) throws Exception {
        var marker = Files.createFile(dir.resolve("dockerenv"));
        var result = ContainerLabelInspector.inspectSelfLabels(marker, dir.resolve("absent.sock"), Option.some("node-1"));

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void inspectSelfLabels_markerPresentBlankHostname_returnsEmpty(@TempDir Path dir) throws Exception {
        var marker = Files.createFile(dir.resolve("dockerenv"));
        var socket = dir.resolve("docker.sock");

        try (var server = serveOnce(socket, LABELS_RESPONSE)) {
            assertThat(ContainerLabelInspector.inspectSelfLabels(marker, socket, Option.some(" ")).isEmpty()).isTrue();
        }
    }

    @Test
    void inspectSelfLabels_markerPresentSocketAnswers_returnsLabels(@TempDir Path dir) throws Exception {
        var marker = Files.createFile(dir.resolve("dockerenv"));
        var socket = dir.resolve("docker.sock");

        try (var server = serveOnce(socket, LABELS_RESPONSE)) {
            var result = ContainerLabelInspector.inspectSelfLabels(marker, socket, Option.some("node-1"));

            assertThat(result.isPresent()).isTrue();
            result.onPresent(labels -> assertThat(labels).containsEntry("aether.cluster", "prod-us"));
        }
    }

    private static final String LABELS_RESPONSE =
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n"
        + "{\"Id\":\"abc\",\"Config\":{\"Labels\":{\"aether.cluster\":\"prod-us\"}}}";

    /// Binds a Unix-domain socket at the path and answers one request with the given response.
    private static ServerSocketChannel serveOnce(Path socket, String response) throws IOException {
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);

        server.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofVirtual().start(() -> answer(server, response));
        return server;
    }

    private static void answer(ServerSocketChannel server, String response) {
        try (var client = server.accept()) {
            client.read(ByteBuffer.allocate(4096));
            client.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException _) {
            // server closed by the test before a client connected
        }
    }
}
