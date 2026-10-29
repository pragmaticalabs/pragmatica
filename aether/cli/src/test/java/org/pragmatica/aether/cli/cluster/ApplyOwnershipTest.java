// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;

import static org.assertj.core.api.Assertions.assertThat;

/// Ownership as a provider REPORTS it on a listed server (the client-side half of "a terminate needs both labels").
class ApplyOwnershipTest {
    private static final String CLUSTER = "prod";
    private static final String APPLY = "0123456789abcdefghjkmnpqrs";

    private static InstanceInfo listed(Map<String, String> tags) {
        return new InstanceInfo(new InstanceId("srv-1"), InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND, tags);
    }

    @Test
    void cloudProviders_needBothLabelsWithTheRightValues() {
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY)), CLUSTER, APPLY)).isTrue();
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of("aether-cluster", CLUSTER)), CLUSTER, APPLY)).as("no apply label").isFalse();
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of("aether-apply", APPLY)), CLUSTER, APPLY)).as("no cluster label").isFalse();
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of("aether-cluster", "other", "aether-apply", APPLY)), CLUSTER, APPLY)).as("another cluster").isFalse();
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of("aether-cluster", CLUSTER, "aether-apply", "other")), CLUSTER, APPLY)).as("another apply").isFalse();
        assertThat(WaveNodeProvisioning.ownedBy("hetzner", listed(Map.of()), CLUSTER, APPLY)).isFalse();
    }

    /// The Docker provider lists `aether.cluster` (with a dot) and never custom labels: the apply label is what the daemon's
    /// own label filter proved, so only the cluster is checked on the listed instance.
    @Test
    void docker_isCheckedByTheClusterLabelItReports() {
        assertThat(WaveNodeProvisioning.ownedBy("docker", listed(Map.of("aether.cluster", CLUSTER)), CLUSTER, APPLY)).isTrue();
        assertThat(WaveNodeProvisioning.ownedBy("docker", listed(Map.of("aether.cluster", "other")), CLUSTER, APPLY)).isFalse();
        assertThat(WaveNodeProvisioning.ownedBy("docker", listed(Map.of("aether-cluster", CLUSTER, "aether-apply", APPLY)), CLUSTER, APPLY)).as("the cloud spelling is not Docker's").isFalse();
    }
}
