// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.lang.Option;


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

    /// Per-minute capacity samples, one per (community, artifact) (rc6 spec 7.2). Written by each governor.
    public static final ResourceAddress CAPACITY_MINUTE = capacityStream("capacity-minute");
    /// The hourly roll-up of [#CAPACITY_MINUTE], written by the core leader's roll-up job.
    public static final ResourceAddress CAPACITY_HOUR = capacityStream("capacity-hour");
    /// All system stream addresses that must exist at cluster bootstrap.
    public static final List<ResourceAddress> ALL = List.of(CLUSTER_EVENTS, CAPACITY_MINUTE, CAPACITY_HOUR);
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;

    /// 35 days. Count and byte caps are sized for ~50k records per artifact over the window (spec 7.2) with
    /// headroom for ~100 artifacts x communities; mode ANY, so an undersized cap would shorten the window.
    static final RetentionPolicy CAPACITY_MINUTE_RETENTION = RetentionPolicy.retentionPolicy(10_000_000L,
                                                                                             2L * 1024 * 1024 * 1024,
                                                                                             35 * DAY_MS);

    /// 3 years (1095 days), ~26k records per artifact over the window (spec 7.2).
    static final RetentionPolicy CAPACITY_HOUR_RETENTION = RetentionPolicy.retentionPolicy(5_000_000L,
                                                                                           1024L * 1024 * 1024,
                                                                                           1095 * DAY_MS);

    private static final Map<ResourceAddress, RetentionPolicy> RETENTION = Map.of(CAPACITY_MINUTE,
                                                                                  CAPACITY_MINUTE_RETENTION,
                                                                                  CAPACITY_HOUR,
                                                                                  CAPACITY_HOUR_RETENTION);

    /// The retention this system stream declares for itself, or none when it takes the bootstrap default
    /// (`cluster-events` keeps that default).
    public static Option<RetentionPolicy> retentionFor(ResourceAddress address) {
        return Option.option(RETENTION.get(address));
    }

    private static ResourceAddress capacityStream(String name) {
        return ResourceAddress.systemResource(name,
                                              ResourceVersion.resourceVersion(1, 0, 0).unwrap())
                              .unwrap();
    }

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
