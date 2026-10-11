// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

    /// Whether `engineKey` names one of [#ALL] — what the management-api write-gate (`ManagementServer`)
    /// checks a resolved stream identity against.
    ///
    /// This used to recompute `address.name().value()` locally, on the reasoning that a system stream's
    /// engine key is its bare name. It is not (#1338): the key is the full address, which is also the name the
    /// framework creates the stream under, and the bare name of a system stream is an ordinary stream name that is
    /// not forbidden. Since #1040 both sides call [StreamEngineKey#engineKey], so the gate cannot disagree with the
    /// identity the routes resolve. The owner's append path refuses a write to ANY `system:` stream, not only these.
    public static boolean isForbiddenEngineKey(String engineKey) {
        return ALL.stream().anyMatch(address -> StreamEngineKey.engineKey(address).equals(engineKey));
    }

    private SystemStreams() {}
}
