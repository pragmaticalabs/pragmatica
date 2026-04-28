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

    public record Route(String method, String path, String handler){}

    public record SliceDep(String interfaceName, String artifact){}

    public record ResourceDep(String type, String config){}

    public record TopicPub(String config, String messageType){}

    public record TopicSub(String config, String method, String messageType){}
}
