// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.List;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;


/// Catalog of framework-internal stream addresses registered by the cluster at bootstrap.
///
/// These addresses are the RC1 set. Adding a new system stream is a minor-version change in the
/// framework and must also list it here so [SystemStreamBootstrap] registers it.
public final class SystemStreams {
    /// Structured cluster event stream (replaces the per-node [RingBuffer] storage).
    public static final ResourceAddress CLUSTER_EVENTS = ResourceAddress.systemResource("cluster-events",
                                                                                        ResourceVersion.resourceVersion(1,
                                                                                                                        0,
                                                                                                                        0).unwrap())
                                                                        .unwrap();

    /// All system stream addresses that must exist at cluster bootstrap.
    public static final List<ResourceAddress> ALL = List.of(CLUSTER_EVENTS);

    private SystemStreams() {}
}
