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

    private TestArtifacts() {}
}
