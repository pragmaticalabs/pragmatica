// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

/// Shared test artifact constants with version derived from the Maven project version.
///
///
/// The version is injected via the `project.version` system property set by
/// the maven-failsafe-plugin configuration. This eliminates hardcoded version
/// strings that break on every version bump.
final class TestArtifacts {
    static final String VERSION = System.getProperty("project.version", "UNKNOWN");
    static final String ECHO_SLICE = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + VERSION;
    static final String VERSIONED_SLICE = "org.pragmatica-lite.aether.test:versioned-slice-versioned-echo:" + VERSION;
    static final String STRICT_VERSIONED_SLICE = "org.pragmatica-lite.aether.test:versioned-slice-strict-versioned-echo:" + VERSION;
    static final String URL_SHORTENER = "org.pragmatica.aether.example:url-shortener-url-shortener:" + VERSION;
    static final String ANALYTICS = "org.pragmatica.aether.example:url-shortener-analytics:" + VERSION;

    /// Minimal stream-only blueprint (no database), used by `StreamFanoutConsumerTest` for the
    /// #265 STEP 0 streaming fan-out baseline. Unlike platform artifacts above, the
    /// `aether/tests/blueprints/test-*` blueprints carry a fixed `1.0.0` artifact version
    /// (independent of the platform `project.version`), so the coordinate is hardcoded.
    static final String STREAM_SLICE = "org.pragmatica.aether.test:test-stream-stream-slice:1.0.0";

    /// RF=2 / min-sync-replicas=2 replicated stream blueprint (`test-stream-repl`), used by
    /// `StreamOwnerFailoverTest` for the #457 in-JVM owner-kill failover proof. A synchronously
    /// replicated stream (owner + 1 in-sync replica) is the ONLY topology with a promotable CAUGHT_UP
    /// non-owner replica; `POST /api/streams` can only mint RF=1 (owner-only), so the RF=2 config MUST
    /// come from a blueprint that declares `min-sync-replicas=2`. Mirrors the RF=1 [#STREAM_SLICE]
    /// coordinate (the `-stream-slice` artifact, fixed `1.0.0` blueprint version, resolved from the
    /// local Maven repo) — the sibling `aether/tests/blueprints/test-stream-repl` module the cloud
    /// suite `02-chaos/test-stream-replica-failover.sh` deploys.
    static final String STREAM_REPL_SLICE = "org.pragmatica.aether.test:test-stream-repl-stream-slice:1.0.0";

    /// partitions=4 / RF=2 / min-sync-replicas=2 replicated stream blueprint (`test-stream-multipart`),
    /// used by `MultiPartitionStreamTest` for the #429 multi-partition e2e fixture (partition→owner
    /// distribution, per-partition ordering, local/forwarded reads + read-preference arms) and by
    /// `StreamPublishReshuffleTest` for the #430 publish-under-ownership-reshuffle chaos test. Four
    /// partitions give HRW placement room to spread owners across the 5-node cluster; RF=2 +
    /// synchronous replication (min-sync-2) is the only topology whose ACKED writes survive a single
    /// owner kill. Mirrors the [#STREAM_REPL_SLICE] coordinate (the `-stream-slice` artifact, fixed
    /// `1.0.0` blueprint version, resolved from the local Maven repo) — the sibling
    /// `aether/tests/blueprints/test-stream-multipart` module.
    static final String STREAM_MULTIPART_SLICE = "org.pragmatica.aether.test:test-stream-multipart-stream-slice:1.0.0";

    /// partitions=1 declarative-consumer blueprint (`test-stream-consumer`), the first
    /// `StreamSubscriber` fixture in the repository, used for the #488 declarative-delivery proof.
    /// The slice declares a `@ConsumerEventSubscriber` method and reports back whatever the runtime
    /// actually delivered to it, so delivery is asserted directly rather than inferred from logs.
    /// A single partition is the ownership-gating discriminator: with every node running the slice,
    /// correct partition-ownership gating delivers each event ONCE cluster-wide, while ungated
    /// delivery would deliver it once per node. Mirrors the [#STREAM_SLICE] coordinate (fixed
    /// `1.0.0` blueprint version, resolved from the local Maven repo) — the sibling
    /// `aether/tests/blueprints/test-stream-consumer` module.
    static final String STREAM_CONSUMER_SLICE = "org.pragmatica.aether.test:test-stream-consumer-consumer-slice:1.0.0";

    /// The FIRST blueprint in the repository that declares a `DurableEntity` resource
    /// (`aether/tests/blueprints/test-entity`, #345 increment I0). Until it existed no
    /// `resources.toml` anywhere named a durable entity, so the whole `resource/durable-entity`
    /// module — interface, SPI factory, and all three implementations — was unreachable from any
    /// running node and every build stayed green regardless of whether it worked. The slice reports
    /// each operation's outcome as data — a create's state, a scheduled timer's token, and every
    /// failure by its cause type (`EntityAlreadyExists`, `EntityNotFound`) — so [DurableEntityForgeTest]
    /// asserts on the entity's real behavior rather than on the absence of an exception. Timers are real
    /// on a node: `DurableEntityFactory` provisions only the fenced-log backing, which schedules them as
    /// ordinary fenced writes, so `TimerNotSupported` — the in-memory backings' answer — never appears
    /// here. It also COUNTS timer fires in
    /// `OrderState.expiries`, which is what lets [DurableEntityTimerDurabilityTest] hold a durable
    /// timer to exactly-once across an owner handover and a full-cluster restart. Mirrors the
    /// [#STREAM_CONSUMER_SLICE] coordinate (fixed `1.0.0` blueprint version, resolved from the local
    /// Maven repo).
    static final String ENTITY_SLICE = "org.pragmatica.aether.test:test-entity-entity-slice:1.0.0";

    private TestArtifacts() {}
}
