// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.labels;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// Reads the current container's labels via the Docker daemon's Unix-domain socket.
///
/// Detection is conservative: returns `Option.empty()` whenever the calling process is
/// not running under Docker (no `/.dockerenv` marker), the socket isn't mounted at the
/// default path, or any I/O step fails. The caller treats `empty` as "can't tell — skip
/// any check that depends on knowing my own labels"; a present `Map` means the daemon
/// answered authoritatively.
///
/// The implementation issues a single `GET /containers/{id}/json` over the Docker daemon
/// socket using JDK-native `SocketChannel` + `UnixDomainSocketAddress` (Java 16+). No
/// third-party HTTP-over-Unix-socket library required. Labels are extracted from the
/// JSON response via a narrow regex against the `Config.Labels` section — Docker's
/// label values cannot contain unescaped quotes (Docker rejects them at container-create
/// time), so the regex is safe against label-value adversarial input.
public final class ContainerLabelInspector {
    private static final Logger log = LoggerFactory.getLogger(ContainerLabelInspector.class);
    private static final String DOCKER_SOCKET = "/var/run/docker.sock";
    private static final String DOCKERENV_MARKER = "/.dockerenv";
    private static final int READ_TIMEOUT_MS = 2000;

    private ContainerLabelInspector() {}

    /// Returns the labels of the current container as Docker sees them, or empty when
    /// detection can't proceed (not in a container, socket not mounted, I/O failure).
    /// Logs at DEBUG on skip; INFO on success with the resolved (cluster, node-id) pair.
    public static Option<Map<String, String>> inspectSelfLabels() {
        return detectContainer().flatMap(_ -> resolveContainerId())
                .flatMap(ContainerLabelInspector::queryDocker)
                .flatMap(ContainerLabelInspector::extractLabels);
    }

    private static Option<Boolean> detectContainer() {
        if (Files.exists(Path.of(DOCKERENV_MARKER))) {return Option.some(true);}
        log.debug("ContainerLabelInspector: no {} marker — not running in Docker, skipping label inspection", DOCKERENV_MARKER);
        return Option.empty();
    }

    private static Option<String> resolveContainerId() {
        // HOSTNAME defaults to the truncated container id under Docker; integration
        // fixtures override it with a friendly name (e.g. `aether-a-node-1`) which is
        // also accepted as a name parameter by the Docker /containers/{id}/json endpoint.
        var hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            log.debug("ContainerLabelInspector: HOSTNAME env not set, skipping label inspection");
            return Option.empty();
        }
        return Option.some(hostname);
    }

    private static Option<String> queryDocker(String containerId) {
        var socketPath = Path.of(DOCKER_SOCKET);
        if (!Files.exists(socketPath)) {
            log.debug("ContainerLabelInspector: {} not present (not mounted into this container) — skipping consistency check", DOCKER_SOCKET);
            return Option.empty();
        }
        try (var channel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            channel.configureBlocking(true);
            var request = "GET /containers/" + containerId + "/json HTTP/1.1\r\nHost: docker\r\nConnection: close\r\nAccept: application/json\r\n\r\n";
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            return Option.some(readAll(channel));
        } catch (IOException e) {
            log.debug("ContainerLabelInspector: docker socket read failed: {}", e.getMessage());
            return Option.empty();
        }
    }

    private static String readAll(SocketChannel channel) throws IOException {
        var buf = ByteBuffer.allocate(8192);
        var out = new ByteArrayOutputStream();
        var deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            var read = channel.read(buf);
            if (read < 0) {break;}
            if (read == 0) {continue;}
            out.write(buf.array(), 0, buf.position());
            buf.clear();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /// Returns Result<Map> — failure on mismatch between configured cluster.name and the
    /// `aether.cluster` label observed on this container. Configured name and label both
    /// empty → success-with-empty-map (nothing to compare). Either side empty → success
    /// (we can't claim disagreement). Both non-empty AND different → failure.
    public static Result<Map<String, String>> compareWithConfigured(String configuredClusterName, Map<String, String> labels) {
        var labelValue = labels.getOrDefault("aether.cluster", "");
        if (configuredClusterName.isEmpty() || labelValue.isEmpty()) {return Result.success(labels);}
        if (!configuredClusterName.equals(labelValue)) {return new ContainerLabelMismatch(configuredClusterName, labelValue).result();}
        return Result.success(labels);
    }

    private static Option<Map<String, String>> extractLabels(String httpResponse) {
        var bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            log.debug("ContainerLabelInspector: malformed HTTP response (no body separator)");
            return Option.empty();
        }
        var body = httpResponse.substring(bodyStart + 4);
        if (!body.contains("\"Labels\"")) {
            log.debug("ContainerLabelInspector: container JSON missing Labels field");
            return Option.empty();
        }
        return Option.some(parseLabels(body));
    }

    private static final Pattern LABELS_SECTION = Pattern.compile("\"Labels\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern LABEL_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");

    static Map<String, String> parseLabels(String body) {
        var sectionMatcher = LABELS_SECTION.matcher(body);
        if (!sectionMatcher.find()) {return Map.of();}
        return parseEntries(sectionMatcher.group(1));
    }

    private static Map<String, String> parseEntries(String labelsBlock) {
        var entries = new java.util.HashMap<String, String>();
        var entryMatcher = LABEL_ENTRY.matcher(labelsBlock);
        while (entryMatcher.find()) {entries.put(entryMatcher.group(1), entryMatcher.group(2));}
        return Map.copyOf(entries);
    }

    /// Helper for the matcher used in tests; not normally invoked directly.
    static Matcher labelsSectionMatcher(CharSequence input) {
        return LABELS_SECTION.matcher(input);
    }

    public record ContainerLabelMismatch(String configuredClusterName, String labelClusterName) implements Cause {
        @Override
        public String message() {
            return "Cluster label mismatch: aether.toml / AETHER_CLUSTER_NAME declares cluster.name='"
                  + configuredClusterName + "' but this container's `aether.cluster` label is '"
                  + labelClusterName + "'. Deployment infrastructure label is inconsistent with cluster identity config — aborting startup to prevent the node from joining the wrong cluster.";
        }
    }
}
