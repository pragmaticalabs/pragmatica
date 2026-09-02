/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for the distributed hash table.
///
/// @param replicationFactor number of copies of each piece of data (including primary).
///                          Use 0 for full replication (all nodes store everything).
/// @param writeQuorum       minimum number of successful writes for operation to succeed
/// @param readQuorum        minimum number of successful reads for operation to succeed
/// @param operationTimeout  timeout for individual DHT operations
/// @param retryPolicy       bounded retry policy for transient failures on both the DHT
///                          write path (`ArtifactStore.dhtPutWithRetry`/`storagePutWithRetry`)
///                          and the DHT read/resolve path
///                          (`ArtifactStore.dhtGetWithRetry`/`storageGetWithRetry`). All
///                          backoff durations are typed `TimeSpan`, configurable here — no
///                          hardcoded millis literals at the call site.
public record DHTConfig(int replicationFactor,
                        int writeQuorum,
                        int readQuorum,
                        TimeSpan operationTimeout,
                        DhtRetryPolicy retryPolicy) {
    /// Bounded retry policy for transient DHT failures.
    ///
    /// `maxAttempts` is the TOTAL number of attempts (initial + retries); a value of 3 means
    /// one initial try plus up to two retries. `backoff` holds the inter-attempt delays as
    /// `TimeSpan` values; the delay before retry N is `backoff.get(min(N-1, backoff.size()-1))`,
    /// so the last entry is reused if there are more retries than configured delays.
    ///
    /// @param maxAttempts total attempts before surfacing the last failure (>= 1)
    /// @param backoff     inter-attempt delays as `TimeSpan` (non-empty)
    public record DhtRetryPolicy(int maxAttempts, List<TimeSpan> backoff) {
        public DhtRetryPolicy {
            backoff = List.copyOf(backoff);
        }

        /// Delay to wait before the retry that follows the given zero-based attempt index.
        /// Clamps to the last configured backoff entry when retries outnumber delays.
        public TimeSpan backoffFor(int attempt) {
            return backoff.get(Math.min(attempt, backoff.size() - 1));
        }
    }

    /// Default bounded retry: 3 total attempts with 100ms / 250ms / 500ms backoff.
    /// Mirrors the former `ArtifactStore.MAX_DHT_PUT_ATTEMPTS` + `DHT_PUT_BACKOFF_MS`
    /// constants, now expressed as configurable `TimeSpan` values.
    public static final DhtRetryPolicy DEFAULT_RETRY_POLICY = new DhtRetryPolicy(3,
                                                                                 List.of(timeSpan(100).millis(),
                                                                                         timeSpan(250).millis(),
                                                                                         timeSpan(500).millis()));

    /// Backward-compatible constructor that applies the default retry policy.
    /// Retained so existing call sites supplying only quorum + timeout keep compiling.
    public DHTConfig(int replicationFactor, int writeQuorum, int readQuorum, TimeSpan operationTimeout) {
        this(replicationFactor, writeQuorum, readQuorum, operationTimeout, DEFAULT_RETRY_POLICY);
    }

    /// Full replication marker - all nodes store all data.
    public static final int FULL_REPLICATION = 0;
    /// Default operation timeout for individual DHT get/put operations.
    ///
    /// Set to 30s (was 10s) to give headroom for cluster bootstrap and
    /// parallel-suite-load latency. The deploy chain `/api/blueprints/deploy` →
    /// `BlueprintService.publishFromArtifact` → `BuiltinRepository` →
    /// `ArtifactStore.resolveWithMetadata` → `dht.get(metaKey)` bottlenecks on
    /// this timeout. With longer node identifiers (post NodeId-as-container-name
    /// migration) plus still-settling QUIC connections during cluster bootstrap,
    /// a 10s budget routinely breaches and surfaces as
    /// `Promise timed out after 10000ms` HTTP 500 on the deploy endpoint.
    /// `RemoteRepository`, `ArtifactStore.DEPLOY_TIMEOUT`, and
    /// `LocalRepository.localRepository(Path)` are already at 30s — this aligns
    /// the DHT operation budget with the rest of the deploy pipeline.
    public static final TimeSpan DEFAULT_TIMEOUT = timeSpan(30).seconds();

    private static final Cause INVALID_REPLICATION = Causes.cause("replicationFactor must be >= 0 (0 = full replication)");

    private static final Cause INVALID_WRITE_QUORUM = Causes.cause("writeQuorum must be between 1 and replicationFactor");

    private static final Cause INVALID_READ_QUORUM = Causes.cause("readQuorum must be between 1 and replicationFactor");

    /// Default configuration: 3 replicas, quorum of 2 for both reads and writes.
    public static final DHTConfig DEFAULT = new DHTConfig(3, 2, 2, DEFAULT_TIMEOUT, DEFAULT_RETRY_POLICY);
    /// Single-node configuration for testing.
    public static final DHTConfig SINGLE_NODE = new DHTConfig(1, 1, 1, DEFAULT_TIMEOUT, DEFAULT_RETRY_POLICY);
    /// Cache configuration: single replica for ephemeral cache data.
    public static final DHTConfig CACHE_DEFAULT = new DHTConfig(1, 1, 1, DEFAULT_TIMEOUT, DEFAULT_RETRY_POLICY);

    /// Full replication configuration - every node stores all data (replicationFactor sentinel 0 → clusterSize).
    ///
    /// Consistency: **eventually consistent, NOT linearizable.** A write acks after `writeQuorum`=1 node and
    /// replicates to the rest asynchronously (fire-and-forget; see `DistributedDHTClient.put`); a read returns
    /// the first of `readQuorum`=1 response with no version reconciliation and no read-repair (see
    /// `QuorumCollector.selectBest`). A read may therefore observe a stale replica until async replication
    /// lands, and — because FULL also disables background anti-entropy (`DHTAntiEntropy`) and rebalancing — a
    /// dropped replication is not self-healed. Suitable for ephemeral / cache-like data, not for state that
    /// requires read-your-writes across nodes.
    public static final DHTConfig FULL = new DHTConfig(FULL_REPLICATION, 1, 1, DEFAULT_TIMEOUT, DEFAULT_RETRY_POLICY);

    /// Create a DHT configuration with validation.
    public static Result<DHTConfig> dhtConfig(int replicationFactor, int writeQuorum, int readQuorum) {
        return dhtConfig(replicationFactor, writeQuorum, readQuorum, DEFAULT_TIMEOUT);
    }

    /// Create a DHT configuration with validation and custom timeout.
    public static Result<DHTConfig> dhtConfig(int replicationFactor,
                                              int writeQuorum,
                                              int readQuorum,
                                              TimeSpan operationTimeout) {
        return dhtConfig(replicationFactor, writeQuorum, readQuorum, operationTimeout, DEFAULT_RETRY_POLICY);
    }

    /// Create a DHT configuration with validation, custom timeout, and retry policy.
    public static Result<DHTConfig> dhtConfig(int replicationFactor,
                                              int writeQuorum,
                                              int readQuorum,
                                              TimeSpan operationTimeout,
                                              DhtRetryPolicy retryPolicy) {
        if (replicationFactor < 0) {
            return INVALID_REPLICATION.result();
        }

        if (replicationFactor > 0) {
            if (writeQuorum < 1 || writeQuorum > replicationFactor) {
                return INVALID_WRITE_QUORUM.result();
            }

            if (readQuorum < 1 || readQuorum > replicationFactor) {
                return INVALID_READ_QUORUM.result();
            }
        }

        return Result.success(new DHTConfig(replicationFactor, writeQuorum, readQuorum, operationTimeout, retryPolicy));
    }

    /// Create a config with the given replication factor and majority quorum.
    /// Use 0 for full replication.
    /// Returns the pre-defined FULL config for full replication, otherwise calculates majority quorum.
    public static Result<DHTConfig> withReplication(int replicationFactor) {
        if (replicationFactor == FULL_REPLICATION) {
            return Result.success(FULL);
        }

        int quorum = (replicationFactor / 2) + 1;

        return dhtConfig(replicationFactor, quorum, quorum);
    }

    /// Check if this is full replication mode (all nodes store everything).
    public boolean isFullReplication() {
        return replicationFactor == FULL_REPLICATION;
    }

    /// Whether the configured read and write quorums overlap (`R + W > N`).
    ///
    /// Quorum overlap guarantees the read set intersects the last quorum-acked write set, so at least one
    /// responding replica HOLDS the most recent write. It is **necessary but NOT sufficient for linearizable
    /// reads** in this implementation: the read path (`QuorumCollector.selectBest`) returns first-response-wins
    /// with no version reconciliation, and there is no read-repair, so a stale replica in the read set may
    /// still be returned. FULL replication (replicationFactor sentinel 0) is excluded — it is eventually
    /// consistent regardless (see the `FULL` constant). Configuration introspection only; no runtime path
    /// consumes this.
    public boolean hasQuorumOverlap() {
        return ! isFullReplication() && readQuorum + writeQuorum > replicationFactor;
    }

    /// Get effective replication factor for a given cluster size.
    /// For full replication, returns cluster size. Otherwise returns configured value.
    public int effectiveReplicationFactor(int clusterSize) {
        return isFullReplication()
               ? clusterSize
               : Math.min(replicationFactor, clusterSize);
    }

    /// Get effective write quorum for a given cluster size.
    /// Caps at effective replication factor to prevent impossible quorum.
    public int effectiveWriteQuorum(int clusterSize) {
        return Math.min(writeQuorum, effectiveReplicationFactor(clusterSize));
    }

    /// Get effective read quorum for a given cluster size.
    /// Caps at effective replication factor to prevent impossible quorum.
    public int effectiveReadQuorum(int clusterSize) {
        return Math.min(readQuorum, effectiveReplicationFactor(clusterSize));
    }
}
