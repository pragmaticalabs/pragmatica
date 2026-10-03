// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.DockerConfig;
import org.pragmatica.aether.config.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/// The generated Kubernetes StatefulSet must pull the node image from the single source of truth
/// (`DockerConfig.DEFAULT_IMAGE`, on the published `ghcr.io/pragmaticalabs` namespace with a pinned
/// tag) rather than the stale hard-coded `ghcr.io/siy/aether-node:latest` it emitted before — the
/// K8s sibling of the S4 default-image fix.
class KubernetesGeneratorTest {
    @Test
    void generateStatefulSet_usesSharedDefaultImage_notStalePersonalNamespace() {
        var config = AetherConfig.aetherConfig(Environment.KUBERNETES);

        var statefulSet = new KubernetesGenerator().generateStatefulSet(config);

        assertThat(statefulSet).contains("image: " + DockerConfig.DEFAULT_IMAGE);
        assertThat(statefulSet).doesNotContain("ghcr.io/siy");
    }

    /// #960 class: a probe pointed at a route the node does not serve fails forever. The node serves
    /// only the unversioned `/health/live` and `/health/ready` probes (`ManagementRoute.HEALTH_LIVE`,
    /// `HEALTH_READY`); bare `/health` is a 404 (measured against a running Forge cluster).
    @Test
    void generateStatefulSet_probesTargetRoutesTheNodeServes() {
        var config = AetherConfig.builder().withEnvironment(Environment.KUBERNETES).tls(false).build();

        var statefulSet = new KubernetesGenerator().generateStatefulSet(config);

        assertThat(statefulSet).as("control: both probes are rendered")
                  .contains("readinessProbe:")
                  .contains("livenessProbe:");
        assertThat(statefulSet).contains("path: /health/ready").contains("path: /health/live");
        assertThat(statefulSet).doesNotContain("path: /health\n");
    }

    /// The management listener serves HTTPS when `tls` is on, and an `httpGet` probe defaults to HTTP,
    /// so without `scheme: HTTPS` both probes fail against a TLS node. One scheme line per probe, and
    /// none when TLS is off (the control that the line is conditional, not unconditional).
    @Test
    void generateStatefulSet_tlsEnabled_probesUseHttps() {
        var config = AetherConfig.builder().withEnvironment(Environment.KUBERNETES).tls(true).build();

        var statefulSet = new KubernetesGenerator().generateStatefulSet(config);

        assertThat(config.tlsEnabled()).as("control: the config under test has TLS on").isTrue();
        assertThat(occurrences(statefulSet, "scheme: HTTPS")).isEqualTo(2);
        assertThat(statefulSet).contains("path: /health/ready\n            port: management\n            scheme: HTTPS");
        assertThat(statefulSet).contains("path: /health/live\n            port: management\n            scheme: HTTPS");
    }

    @Test
    void generateStatefulSet_tlsDisabled_probesStayHttp() {
        var config = AetherConfig.builder().withEnvironment(Environment.KUBERNETES).tls(false).build();

        var statefulSet = new KubernetesGenerator().generateStatefulSet(config);

        assertThat(config.tlsEnabled()).as("control: the config under test has TLS off").isFalse();
        assertThat(statefulSet).doesNotContain("scheme:");
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
