// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.provenance;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionRecoveryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionRecoveryValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;


/// The durable stream-partition flag (#1596, spec #1569 §7.5.3): the one surface through which a divergence, an
/// incomplete history or a provenance mismatch is recorded as cluster state, under
/// [StreamPartitionRecoveryKey]. The promotion gate (#1596, S6) and cold-restart detection (AD7) raise through it
/// and read it; clearing it belongs to operator resolution (AD14), not here.
///
/// [#raise] is idempotent (R7-2): it writes `old ∪ {reason}` only when that differs from the committed record, so
/// raising the same reason again -- a flapping node, a retried promotion -- writes nothing and leaves
/// [PartitionFlag#recordDigest] unchanged, while a new reason changes it. The write is a leader-witnessed
/// transaction carrying the exact previous record, so two concurrent raises of different reasons cannot lose
/// either: the loser re-reads and retries.
public interface PartitionFlags {
    /// Raise `reason` on `(stream, partition)`; resolves with the record as committed after it.
    Promise<PartitionFlag> raise(String stream, int partition, PartitionRecoveryReason reason);
    /// The partition's committed record, as applied on this node; none when it was never flagged.
    Option<PartitionFlag> status(String stream, int partition);
    /// A reason about THIS node's copy (`storageId` = this node's id until storage ULID identity exists).
    PartitionRecoveryReason local(PartitionRecoveryReasonKind kind, String evidence);

    /// A committed record and its digest: SHA-256 of its canonical encoding, the read witness an AD14 resolution
    /// CASes on.
    record PartitionFlag(StreamPartitionRecoveryValue record, String recordDigest) {}

    int MAX_ATTEMPTS = 5;

    static PartitionFlags kvPartitionFlags(ClusterNode<KVCommand<AetherKey>> node,
                                           KVStore<AetherKey, AetherValue> kvStore,
                                           SliceCodec codec) {
        return new KvPartitionFlags(node, kvStore, codec.canonical());
    }

    /// Why a raise did not commit.
    enum FlagError implements Cause {
        NO_LEADER("No committed core leader to witness the partition flag"),
        CONTENDED("The partition flag kept changing under the raise; the reason was not recorded");
        private final String message;
        FlagError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}

final class KvPartitionFlags implements PartitionFlags {
    private static final Logger log = LoggerFactory.getLogger(KvPartitionFlags.class);
    private static final String DIGEST_UNAVAILABLE = "sha-256-unavailable";

    private final ClusterNode<KVCommand<AetherKey>> node;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final SliceCodec canonical;

    KvPartitionFlags(ClusterNode<KVCommand<AetherKey>> node,
                     KVStore<AetherKey, AetherValue> kvStore,
                     SliceCodec canonical) {
        this.node = node;
        this.kvStore = kvStore;
        this.canonical = canonical;
    }

    @Override
    public Promise<PartitionFlag> raise(String stream, int partition, PartitionRecoveryReason reason) {
        return attempt(StreamPartitionRecoveryKey.streamPartitionRecoveryKey(stream, partition), reason, MAX_ATTEMPTS);
    }

    @Override
    public Option<PartitionFlag> status(String stream, int partition) {
        return committed(StreamPartitionRecoveryKey.streamPartitionRecoveryKey(stream, partition)).map(this::flag);
    }

    @Override
    public PartitionRecoveryReason local(PartitionRecoveryReasonKind kind, String evidence) {
        return PartitionRecoveryReason.partitionRecoveryReason(kind,
                                                               some(node.self().id()),
                                                               evidence);
    }

    /// The raise against the record as applied here. Equal to it after the raise: nothing to write.
    private Promise<PartitionFlag> attempt(StreamPartitionRecoveryKey key,
                                           PartitionRecoveryReason reason,
                                           int remaining) {
        var committed = committed(key);
        var raised = StreamPartitionRecoveryValue.raised(committed, reason);

        return committed.filter(raised::equals)
                        .map(unchanged -> Promise.success(flag(unchanged)))
                        .or(() -> write(key, committed, raised, reason, remaining));
    }

    private Promise<PartitionFlag> write(StreamPartitionRecoveryKey key,
                                         Option<StreamPartitionRecoveryValue> committed,
                                         StreamPartitionRecoveryValue raised,
                                         PartitionRecoveryReason reason,
                                         int remaining) {
        return kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .async(FlagError.NO_LEADER)
                      .flatMap(leader -> submit(key, committed, raised, leader))
                      .flatMap(accepted -> settle(key, raised, reason, accepted, remaining));
    }

    /// One leader-witnessed transaction whose single mutation carries the exact previous record.
    private Promise<Boolean> submit(StreamPartitionRecoveryKey key,
                                    Option<StreamPartitionRecoveryValue> committed,
                                    StreamPartitionRecoveryValue raised,
                                    LeaderValue leader) {
        var transactionId = UUID.randomUUID().toString();
        var mutation = new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                      committed.map(AetherValue.class::cast),
                                                                      some(raised));
        KVCommand<AetherKey> transaction = new KVCommand.LeaderTransaction<>(key,
                                                                             transactionId,
                                                                             leader,
                                                                             List.of(),
                                                                             List.of(mutation));

        return node.<Object> apply(List.of(transaction))
                   .map(results -> accepted(results, transactionId));
    }

    private static boolean accepted(List<Object> results, String transactionId) {
        return results.stream()
                      .filter(KVCommand.TransactionResult.class::isInstance)
                      .map(KVCommand.TransactionResult.class::cast)
                      .anyMatch(result -> result.transactionId()
                                                .equals(transactionId) && result.accepted());
    }

    /// A refused transaction means the record changed under this raise: re-read and raise again, a bounded number
    /// of times.
    private Promise<PartitionFlag> settle(StreamPartitionRecoveryKey key,
                                          StreamPartitionRecoveryValue raised,
                                          PartitionRecoveryReason reason,
                                          boolean accepted,
                                          int remaining) {
        if (accepted) {
            return Promise.success(flagged(key, raised, reason));
        }

        return remaining > 1
               ? attempt(key, reason, remaining - 1)
               : FlagError.CONTENDED.promise();
    }

    /// CRITICAL (spec §7.5.3 outcome): logged once per record change, on the node that committed it. Event-backed
    /// once the OperatorWarning cluster event (#1574) lands.
    private PartitionFlag flagged(StreamPartitionRecoveryKey key,
                                  StreamPartitionRecoveryValue raised,
                                  PartitionRecoveryReason reason) {
        var flag = flag(raised);

        log.error("STREAM_PARTITION_FLAGGED {}[{}]: {} ({}). Flagged; enforcement (no owner, no reads until an operator "
                 + "resolves it by pick-source or accept-loss) arrives with the promotion gate (#1596 gate, S6); record "
                 + "digest {}, reasons {}",
                  key.stream(),
                  key.partition(),
                  reason.kind(),
                  reason.evidence(),
                  flag.recordDigest(),
                  raised.reasons());

        return flag;
    }

    private Option<StreamPartitionRecoveryValue> committed(StreamPartitionRecoveryKey key) {
        return kvStore.getTyped(key, StreamPartitionRecoveryValue.class);
    }

    private PartitionFlag flag(StreamPartitionRecoveryValue record) {
        return new PartitionFlag(record, digest(canonical.encode(record)));
    }

    /// Design-out: every Java SE implementation must provide SHA-256 (`MessageDigest`'s contract), so the failure
    /// branch is unreachable. Were it reached, [#DIGEST_UNAVAILABLE] matches no record's real digest, so a
    /// resolution CAS on it fails -- closed, never open.
    private static String digest(byte[] encoded) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(sha -> HexFormat.of().formatHex(sha.digest(encoded)))
                     .or(DIGEST_UNAVAILABLE);
    }
}
