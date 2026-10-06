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
    /// A configured core member died on this node's membership view without this node ever observing it
    /// reachable (#1835): no QUIC handshake, SWIM ALIVE or health evidence. It never joined, so it did not
    /// fail; NODE_FAILED and the CRITICAL node-health alert are reserved for members that had.
    NODE_NEVER_JOINED("node-never-joined", "membership", WarningLevel.WARNING),
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
    /// A partition's owner promotion is refused because a peer ANSWERED its watermark probe with a page cut before its
    /// first event: that event alone exceeds the peer's read cap (#1431). Not an unreachable peer; the operator raises
    /// the peer's `maxReadResponseBytes`. The message names the partition, the peer and the offset.
    STREAM_EVENT_EXCEEDS_READ_CAP("stream-event-exceeds-read-cap", "stream-replication", WarningLevel.CRITICAL);
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
