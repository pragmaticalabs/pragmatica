// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// `provisionSpecFor` is the group path's per-node spec plus the caller's labels, nothing else.
class CloudProviderSupportProvisionSpecTest {
    private static final NodeGroupConfig GROUP = NodeGroupConfig.nodeGroupConfig(sourceNameOrDefault("local"),
                                                                                 "core",
                                                                                 1,
                                                                                 "default",
                                                                                 "default",
                                                                                 Map.of("aether-cluster", "prod-cluster"));
    private static final Map<String, String> LABELS = Map.of("aether-apply", "0123456789abcdefghijklmnop", "aether-role", "core");

    @Test
    void provisionSpecFor_carriesTheCallersLabelsAsExtraTags() {
        var spec = CloudProviderSupport.provisionSpecFor(GROUP, "node-1", LABELS).unwrap();

        assertThat(spec.context().extraTags()).isEqualTo(LABELS);
        assertThat(spec.context().nodeId().or("")).isEqualTo("node-1");
        assertThat(spec.context().clusterName().map(ClusterName::value).or("")).isEqualTo("prod-cluster");
    }

    @Test
    void provisionSpecFor_withNoLabels_isTheGroupPathsSpec() {
        var spec = CloudProviderSupport.provisionSpecFor(GROUP, "node-1", Map.of()).unwrap();

        assertThat(spec.context().extraTags()).isEmpty();
        assertThat(spec).isEqualTo(CloudProviderSupport.buildProvisionSpec(GROUP, "node-1").unwrap());
    }
}
