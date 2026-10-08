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
package org.pragmatica.utility.warning;

import java.util.Arrays;

import org.pragmatica.lang.Option;


/// The single catalogue of operator-warning codes (#1574).
///
/// Operators identify a warning in the cluster event log by `code`, so the kebab string is part of the
/// contract.
/// Because call sites name a constant rather than typing a string, a typo cannot silently start a
/// new code. Each constant also fixes its subsystem and level, so two sites cannot report the same
/// condition under different severities. `OperatorWarningCodeTest` refuses duplicate or non-kebab
/// codes.
///
/// To add a code, append a constant here. Never rename the string of a shipped code, because anything
/// that matches on it (alert rules, scripts, a future server-side filter) depends on it.
public enum OperatorWarningCode {
    /// SWIM's #336 co-confirmation kill-gate is holding the death of a long-healthy peer. This node
    /// alone judged the peer FAULTY, with no transport corroboration.
    SWIM_KILL_GATE_HELD("swim-kill-gate-held", "membership", WarningLevel.WARNING),
    /// A worker has lost the core, and its core-absence fence is dissolving it locally.
    CORE_ABSENCE_FENCE("core-absence-fence", "worker-isolation", WarningLevel.CRITICAL),
    /// A replica failed to fsync an applied batch and is withholding its ack. The owner's durability
    /// barrier will not count this copy.
    REPLICA_FSYNC_FAILED("replica-fsync-failed", "stream-replication", WarningLevel.WARNING),
    /// A stream, durable topic or durable entity was activated with a replication policy that carries a stated
    /// risk (#1564): a confirmation factor equal to the replication factor, or a confirmation factor of 1.
    REPLICATION_POLICY_WARNING("replication-policy-warning", "stream-replication", WarningLevel.WARNING),
    /// The LOUD replication warning (#1564, owner ruling 596bdfd07(3)): a stream, durable topic or durable entity
    /// was activated with an explicitly declared replication factor below 3 — under terminal node removal, losing
    /// that many nodes loses the partition. CRITICAL, so it is distinguishable from the ordinary policy warnings.
    REPLICATION_FACTOR_BELOW_THREE("replication-factor-below-three", "stream-replication", WarningLevel.CRITICAL),
    /// A blueprint publish was accepted with a deploy-time warning (#1564): a declaration with a stated
    /// replication risk, or a stream declaration the cluster accepted with a caveat.
    DEPLOY_WARNING("deploy-warning", "deployment", WarningLevel.WARNING),
    /// A stream consumer's delivery pass has thrown on several consecutive attempts (#1934): the reader or the runtime
    /// throws instead of reading, so the consumer delivers nothing and retries with a growing backoff. The message
    /// names the consumer group, the partition, what was thrown and its top frames. Subject is
    /// `stream[partition]/group`.
    STREAM_CONSUMER_DRAIN_FAILING("stream-consumer-drain-failing", "stream-consumer", WarningLevel.WARNING),
    /// The end of a `stream-consumer-drain-failing` run: a pass read the partition again, or the consumer was cancelled
    /// while the alert stood. Raised only when that warning was; INFO, paired with [#STREAM_CONSUMER_DRAIN_FAILING] so
    /// the event layer publishes it only after a published failing event for the same subject (#752 mechanism).
    STREAM_CONSUMER_DRAIN_RESTORED("stream-consumer-drain-restored",
                                   "stream-consumer",
                                   WarningLevel.INFO,
                                   STREAM_CONSUMER_DRAIN_FAILING),
    /// The replication policy refused the registration of `system:cluster-events` (#1564 B1). The node keeps
    /// running, but cluster events are not recorded until the cluster config is corrected and re-applied.
    CLUSTER_EVENTS_REGISTRATION_REFUSED("cluster-events-registration-refused",
                                        "stream-replication",
                                        WarningLevel.CRITICAL),
    /// A whole-cluster restore withheld the previous cluster's entity checkpoints; entity state restarts empty
    /// for the named partitions (#1533).
    BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED("backup-restore-entity-checkpoints-dropped",
                                              "kv-backup",
                                              WarningLevel.WARNING),
    /// A cold start cannot read the KV backup (unreachable, undecodable); cluster-state writes stay refused
    /// until it can, or until a restart with `[backup] restore = "fresh"` (#1533).
    BACKUP_RESTORE_BLOCKED("backup-restore-blocked", "kv-backup", WarningLevel.CRITICAL),
    /// Another cluster holds the backup head at this cluster's own lineage and incarnation (a different
    /// incarnation id); this cluster backs up nothing until an operator resolves the fork (#1533).
    BACKUP_FORKED("backup-forked", "kv-backup", WarningLevel.CRITICAL),
    /// This node became the leader without a `[backup]` while the cluster's committed state says the backup is in use (a
    /// committed restore decision other than DISABLED, or a committed cluster configuration that enables it): nothing is
    /// backed up while it leads (#1968). Typically a replacement provisioned without the cluster's `[backup]`. The committed
    /// setting is kept, never downgraded to DISABLED; the operator restarts this node with the cluster's `[backup]`, or
    /// moves leadership to a node that has it.
    BACKUP_CONFIG_MISSING("backup-config-missing", "kv-backup", WarningLevel.CRITICAL),
    /// This node, which led without a `[backup]`, no longer leads (#1968). INFO, paired with [#BACKUP_CONFIG_MISSING]: the
    /// event layer publishes it only after that warning for the same subject. It ends this node's part of the condition; a
    /// leader that also lacks `[backup]` raises the warning again.
    BACKUP_CONFIG_RESTORED("backup-config-restored", "kv-backup", WarningLevel.INFO, BACKUP_CONFIG_MISSING),
    /// A configured core member died on this node's membership view without this node ever observing it
    /// reachable (#1835): no QUIC handshake, SWIM ALIVE or health evidence. It never joined, so it did not
    /// fail; NODE_FAILED and the CRITICAL node-health alert are reserved for members that had.
    NODE_NEVER_JOINED("node-never-joined", "membership", WarningLevel.WARNING),
    /// An operator drain or shutdown was FORCED past the slice `minAvailable` floor (#1720): the target hosts
    /// ACTIVE slice instances whose remaining count falls below the floor, so those slices run degraded, or go
    /// dark, until re-placed. The subject is the target node; the message names each slice and its counts.
    SLICE_FLOOR_BREACHED_BY_FORCE("slice-floor-breached-by-force", "deployment", WarningLevel.WARNING),
    /// An operator drain or shutdown was REFUSED (409) because it would take a hosted slice below its `minAvailable`
    /// floor (#1720). Raised once per target on the transition into refusal; the subject is the target node and the
    /// message names each slice and its counts. Its recovery is [#SLICE_FLOOR_DRAIN_ADMITTED].
    SLICE_FLOOR_DRAIN_REFUSED("slice-floor-drain-refused", "deployment", WarningLevel.WARNING),
    /// The recovery of a [#SLICE_FLOOR_DRAIN_REFUSED], same subject (#1720): a drain or shutdown for a target that was
    /// earlier refused by the slice floor has now been admitted (the floor cleared, or the operator forced it). INFO,
    /// published only after a published refusal for that target, and it clears the refusal's throttle window (#752).
    SLICE_FLOOR_DRAIN_ADMITTED("slice-floor-drain-admitted", "deployment", WarningLevel.INFO, SLICE_FLOOR_DRAIN_REFUSED),
    /// A replica of a `confirmation_factor` 1 stream cut its divergent tail back to the last offset it shares with its
    /// owner (#1730 phase 2). With that factor the acknowledgement was the old owner's alone, so the discarded offsets
    /// may have been acknowledged and are lost; the message names them. A stream that confirms with replicas
    /// discards only unacknowledged records on the same cut, and that is only logged.
    STREAM_DIVERGENT_TAIL_TRUNCATED("stream-divergent-tail-truncated", "stream-replication", WarningLevel.WARNING),
    /// #1730 phase 2: a replica's catch-up has been answered as a consumer read for a minute -- the source does not list it
    /// as a replica of the partition -- so nothing is applied and it stays out of the in-sync set. Subject is
    /// `stream[partition]@source`.
    STREAM_CATCHUP_SOURCE_NOT_ANSWERING("stream-catchup-source-not-answering",
                                        "stream-replication",
                                        WarningLevel.WARNING),
    /// The end of a `stream-catchup-source-not-answering` episode: the source lists the replica again and its catch-up is answered
    /// as a replica. Raised only when that warning was; INFO, paired with [#STREAM_CATCHUP_SOURCE_NOT_ANSWERING]: the event layer
    /// publishes it only after a published not-answering event for the same subject (#752 mechanism). Same subject.
    STREAM_CATCHUP_SOURCE_ANSWERING_RESTORED("stream-catchup-source-answering-restored",
                                             "stream-replication",
                                             WarningLevel.INFO,
                                             STREAM_CATCHUP_SOURCE_NOT_ANSWERING),
    /// A declarative stream consumer this node held as attached had no subscription in the consumer runtime (#752):
    /// found by a reconcile pass, which forgets and re-attaches it. The partition was not consumed in between while this node reported it attached.
    STREAM_CONSUMER_STATE_DIVERGED("stream-consumer-state-diverged", "stream-consumer", WarningLevel.WARNING),
    /// A detach or abandon of a declarative stream consumer found no subscription in the consumer runtime (#752): delivery
    /// had already stopped and no final cursor flush was made. A point event with the state already reconciled, so it has no
    /// recovery and, being its own code, opens no record for [#STREAM_CONSUMER_STATE_REPAIRED] and shares no throttle
    /// window with [#STREAM_CONSUMER_STATE_DIVERGED].
    STREAM_CONSUMER_DETACH_FOUND_NOTHING("stream-consumer-detach-found-nothing", "stream-consumer", WarningLevel.WARNING),
    /// The recovery of a [#STREAM_CONSUMER_STATE_DIVERGED] that a reconcile pass found (#752), same subject: the
    /// consumer is attached again, or the partition is no longer assigned to this node. A detach-found divergence is
    /// [#STREAM_CONSUMER_DETACH_FOUND_NOTHING], a point event with no recovery.
    STREAM_CONSUMER_STATE_REPAIRED("stream-consumer-state-repaired",
                                   "stream-consumer",
                                   WarningLevel.INFO,
                                   STREAM_CONSUMER_STATE_DIVERGED),
    /// A slice's declarative stream consumer could not be registered at activation (#1935): the slice activated, but
    /// that consumer will receive nothing until the cause is fixed and the slice re-activated. CRITICAL: a declared
    /// consumer that silently never fires is a data-plane gap, not a caveat.
    STREAM_CONSUMER_NOT_REGISTERED("stream-consumer-not-registered", "stream-subscription", WarningLevel.CRITICAL),
    /// The resolved counterpart of [#STREAM_CONSUMER_NOT_REGISTERED] (#1935): a consumer that was raised as not
    /// registered now registers. Raised only for a subject the not-registered warning was raised for. INFO, paired with
    /// [#STREAM_CONSUMER_NOT_REGISTERED]: the event layer publishes it only after a published not-registered event for the
    /// same subject (#752 mechanism).
    STREAM_CONSUMER_REGISTERED_AGAIN("stream-consumer-registered-again",
                                     "stream-subscription",
                                     WarningLevel.INFO,
                                     STREAM_CONSUMER_NOT_REGISTERED),
    /// A stream consumer re-read from an earlier offset because the partition's owner replaced the lineage its cursor
    /// belonged to (#1873, KIP-320): a restart without a WAL, or a failover to a replica that held less, began a new owner
    /// epoch below the consumer's cursor. The records the group processed above that offset are gone from the log and the
    /// records now at those offsets are delivered; the message names the group, the partition and the offsets.
    STREAM_CONSUMER_REWOUND("stream-consumer-rewound", "stream-consumer", WarningLevel.WARNING),
    /// A partition's owner promotion is refused because a peer ANSWERED its watermark probe with a page cut before its
    /// first event: that event alone exceeds the peer's read cap (#1431). Not an unreachable peer; the operator raises
    /// the peer's `maxReadResponseBytes`. The message names the partition, the peer and the offset.
    STREAM_EVENT_EXCEEDS_READ_CAP("stream-event-exceeds-read-cap", "stream-replication", WarningLevel.CRITICAL),
    /// The recovery of a [#STREAM_EVENT_EXCEEDS_READ_CAP] (#1937), same subject: the oversized-event refusal no longer holds the
    /// partition's promotion (the peer's cap was raised, another peer was caught up from, or this node stopped being its owner).
    STREAM_EVENT_EXCEEDS_READ_CAP_RESOLVED("stream-event-exceeds-read-cap-resolved",
                                           "stream-replication",
                                           WarningLevel.INFO,
                                           STREAM_EVENT_EXCEEDS_READ_CAP),
    /// A partition's owner promotion waits because members that may hold records did not answer its watermark probe for longer
    /// than the alarm window (#1937). CRITICAL: the partition is unavailable for writes until they answer or an operator acts.
    /// The message names the partition, the silent members and the responders.
    STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE("stream-owner-promotion-holders-unreachable",
                                               "stream-replication",
                                               WarningLevel.CRITICAL),
    /// The recovery of a [#STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE] (#1937), same subject: every member answers again, or this
    /// node stopped being the partition's owner.
    STREAM_OWNER_PROMOTION_HOLDERS_ANSWERING("stream-owner-promotion-holders-answering",
                                             "stream-replication",
                                             WarningLevel.INFO,
                                             STREAM_OWNER_PROMOTION_HOLDERS_UNREACHABLE),
    /// A partition's owner promotion keeps being refused at the guarded commit of its epoch start (#1976): the ownership record
    /// keeps changing under it. CRITICAL: the partition stays un-activated, and the owner retries with backoff. Raised once
    /// per episode, after repeated refusals; the message names the partition and the count.
    STREAM_OWNER_LINEAGE_REFUSED("stream-owner-lineage-refused", "stream-replication", WarningLevel.CRITICAL),
    /// The recovery of a [#STREAM_OWNER_LINEAGE_REFUSED] (#1976), same subject: the epoch start was committed, or this node
    /// stopped being the partition's owner or lost quorum.
    STREAM_OWNER_LINEAGE_COMMITTED("stream-owner-lineage-committed",
                                   "stream-replication",
                                   WarningLevel.INFO,
                                   STREAM_OWNER_LINEAGE_REFUSED),
    /// A node's HTTP listener refused a TLS certificate rotation because the new certificate bundle did not build into a TLS
    /// context (a malformed or mismatched certificate or key). The listener keeps serving the PREVIOUS certificate, which
    /// expires; nothing is replaced and nothing falls back to plain HTTP. Subject is the listener (`management`, `app-http`).
    /// Raised on the transition into refusal, not on every repeated refusal.
    HTTP_TLS_ROTATION_REFUSED("http-tls-rotation-refused", "http-listener", WarningLevel.WARNING),
    /// The recovery of an [#HTTP_TLS_ROTATION_REFUSED], same subject: a later rotation built and the listener now serves the
    /// rotated certificate.
    HTTP_TLS_ROTATION_RESTORED("http-tls-rotation-restored",
                               "http-listener",
                               WarningLevel.INFO,
                               HTTP_TLS_ROTATION_REFUSED),
    /// A renewed node certificate did not build into the cluster transport's QUIC server and client contexts (or its private key
    /// does not match it), so the renewal is refused and the transport keeps its current certificate, which expires. Nothing
    /// downstream (the HTTP listeners' rotation) runs for a refused bundle. Subject is `cluster-quic`. Raised once on the
    /// transition into refusal.
    CLUSTER_TLS_RENEWAL_REFUSED("cluster-tls-renewal-refused", "cluster-transport", WarningLevel.WARNING),
    /// The recovery of a [#CLUSTER_TLS_RENEWAL_REFUSED], same subject: a later renewal built and was applied.
    CLUSTER_TLS_RENEWAL_RESTORED("cluster-tls-renewal-restored",
                                 "cluster-transport",
                                 WarningLevel.INFO,
                                 CLUSTER_TLS_RENEWAL_REFUSED);
    private final String code;
    private final String subsystem;
    private final WarningLevel level;
    private final Option<OperatorWarningCode> recoveryOf;
    OperatorWarningCode(String code, String subsystem, WarningLevel level) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
        this.recoveryOf = Option.none();
    }
    OperatorWarningCode(String code, String subsystem, WarningLevel level, OperatorWarningCode recoveryOf) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
        this.recoveryOf = Option.some(recoveryOf);
    }
    /// The stable kebab-case identifier of the condition.
    public String code() {
        return code;
    }
    public String subsystem() {
        return subsystem;
    }
    public WarningLevel level() {
        return level;
    }
    /// The condition this code is the recovery of, when it is one. A recovery is only meaningful for a subject whose
    /// condition an operator has seen, so the event layer publishes it exactly then (#752).
    public Option<OperatorWarningCode> recoveryOf() {
        return recoveryOf;
    }
    /// Whether some other code is the recovery of this one.
    public boolean hasRecovery() {
        return Arrays.stream(values()).anyMatch(other -> other.recoveryOf.filter(this::equals)
                                                                         .isPresent());
    }
}
