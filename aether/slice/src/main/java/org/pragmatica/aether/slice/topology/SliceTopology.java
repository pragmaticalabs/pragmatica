// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topology;

import java.util.List;


public record SliceTopology(String sliceName,
                            String artifact,
                            List<Route> routes,
                            List<SliceDep> dependencies,
                            List<ResourceDep> resources,
                            List<TopicPub> publishes,
                            List<TopicSub> subscribes) {
    public SliceTopology {
        routes = List.copyOf(routes);
        dependencies = List.copyOf(dependencies);
        resources = List.copyOf(resources);
        publishes = List.copyOf(publishes);
        subscribes = List.copyOf(subscribes);
    }

    public record Route(String method, String path, String handler) {}

    public record SliceDep(String interfaceName, String artifact) {}

    public record ResourceDep(String type, String config) {}

    /// A published topic.
    ///
    /// `config` is the raw declared topic string (kept verbatim for back-compat). `address` is the
    /// resolved canonical `namespace:topic:version` — a bare/legacy name has its namespace derived
    /// from the slice's blueprint Maven coordinates and its version defaulted to `1.0.0`; an
    /// already-namespaced declaration is kept as-is. Topology node identity and cross-slice pub/sub
    /// matching key on `address`, so a bare publisher and an explicitly-namespaced subscriber of the
    /// same logical topic connect.
    public record TopicPub(String config, String address, String messageType) {}

    /// A subscribed topic. See [TopicPub] for the `config` vs. `address` distinction.
    public record TopicSub(String config, String address, String method, String messageType) {}
}
