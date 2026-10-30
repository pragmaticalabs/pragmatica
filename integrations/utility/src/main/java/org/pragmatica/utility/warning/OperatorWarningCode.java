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
/// To add a code, insert a constant ABOVE the last constant, never after it: the last constant carries the terminating
/// `;`, and the formatter rewrites a trailing comma away, so a constant appended after it would edit that line and
/// conflict with any other PR adding a code (this file merges with `merge=union`, see `.gitattributes`).
///
/// Never rename the string of a shipped code, because anything
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
    /// The end of a [#BACKUP_RESTORE_BLOCKED] on this node (#1968): the restore could read the backup and decided, or this node stopped
    /// leading or is stopping, so its blocked restore no longer holds the cluster. INFO, paired with the warning: the event layer
    /// publishes it only after that warning for the same subject. A leader that still cannot read the backup raises the warning again.
    BACKUP_RESTORE_UNBLOCKED("backup-restore-unblocked", "kv-backup", WarningLevel.INFO, BACKUP_RESTORE_BLOCKED),
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
    /// The cluster retired a node (scale-down, departure, drain, replacement) and could NOT confirm that its instance is terminated at the
    /// compute provider after every bounded retry (#2062): the instance may still be running, and billing. The subject is the node; the
    /// message names the node, the last cause and what to do. Its recovery is [#INSTANCE_TERMINATION_CONFIRMED].
    INSTANCE_TERMINATION_UNCONFIRMED("instance-termination-unconfirmed", "deployment", WarningLevel.WARNING),
    /// The recovery of [#INSTANCE_TERMINATION_UNCONFIRMED], same subject (#2062): a later reap of the node was confirmed by a provider
    /// listing (or the operator removed the instance and a repeat reap found none it had listed). The warning it closes is open in the
    /// cluster (a replicated mark), not only in the aggregator of the node that raised it, so a later leader's confirmation closes it too.
    INSTANCE_TERMINATION_CONFIRMED("instance-termination-confirmed",
                                   "deployment",
                                   WarningLevel.INFO,
                                   INSTANCE_TERMINATION_UNCONFIRMED,
                                   true),
    /// The other recovery of [#INSTANCE_TERMINATION_UNCONFIRMED], same subject (#2062): the node joined the cluster again, a new incarnation, so the
    /// warning about its previous incarnation no longer applies. NOTHING was terminated: this is not a confirmation. Like the confirmation it closes a
    /// warning another node may have raised.
    INSTANCE_TERMINATION_REJOINED("instance-termination-rejoined",
                                  "deployment",
                                  WarningLevel.INFO,
                                  INSTANCE_TERMINATION_UNCONFIRMED,
                                  true),
    /// The third recovery of [#INSTANCE_TERMINATION_UNCONFIRMED], same subject (#2062): an OPERATOR cleared the mark after verifying at the provider
    /// and terminating the instance by hand. Not a confirmation by the cluster - the event names the operator and the instance the operator
    /// attests. Closes the warning another node may have raised.
    INSTANCE_TERMINATION_CLEARED("instance-termination-cleared",
                                 "deployment",
                                 WarningLevel.INFO,
                                 INSTANCE_TERMINATION_UNCONFIRMED,
                                 true),
    /// A node that had a pending operator drain joined the cluster again (#2062): a new incarnation, so the pending drain was cancelled and its grace will
    /// not terminate it. The subject is the node. Re-issue the drain if it is still intended.
    NODE_DRAIN_CANCELLED_REJOINED("node-drain-cancelled-rejoined", "deployment", WarningLevel.WARNING),
    /// A node that a replacement retired (#2062) joined the cluster again - a restart of a supposedly gone instance. Its identity is superseded by the node that
    /// replaced it, so it is refused and is being drained: re-admitting it would add a node beside its replacement. The subject is the retired node.
    NODE_RETIRED_IDENTITY_REJOINED("node-retired-identity-rejoined", "deployment", WarningLevel.WARNING),
    /// An operator-started (EXTERNAL) node joined the cluster again while the release of its previous incarnation's capacity reservation was being committed
    /// (#2062), and its capacity reservation could not be reconciled: either the ledger cannot count its slot (the node is refused and is being drained), or the
    /// reconciliation failed and the node's reservation is not known to be in step with the ledger. Never log-only. The subject is the node.
    EXTERNAL_REJOIN_UNRECONCILED("external-rejoin-unreconciled", "deployment", WarningLevel.WARNING),
    /// A start this node refused for want of a committed `SliceTarget` (LOAD, ACTIVATE or an ACTIVE claim) has stayed parked past the
    /// bound (#1109): the target the leader acted on has not arrived in this node's applied log. The park stays retry-able and is
    /// re-driven when the target arrives. The subject is `<artifact>@<node>`. Closed by [#SLICE_START_DEFERRAL_ENDED].
    SLICE_START_AWAITING_TARGET("slice-start-awaiting-target", "deployment", WarningLevel.WARNING),
    /// The recovery of [#SLICE_START_AWAITING_TARGET], same subject (#1109): the target arrived and the start proceeded, or the parked
    /// entry was superseded or removed (an UNLOAD, the leader's orphan sweep). The message says which. Raised only when the warning was.
    SLICE_START_DEFERRAL_ENDED("slice-start-deferral-ended",
                               "deployment",
                               WarningLevel.INFO,
                               SLICE_START_AWAITING_TARGET),
    /// A node replacement (#1543) was committed: a fresh-id node is taking over from the subject (the ORIGINAL node). Raised once,
    /// when the record is first committed. Closed by [#NODE_REPLACEMENT_COMPLETED] or [#NODE_REPLACEMENT_ROLLED_BACK].
    NODE_REPLACEMENT_STARTED("node-replacement-started", "replacement", WarningLevel.INFO),
    /// The recovery of [#NODE_REPLACEMENT_STARTED], same subject: the replacement took over and the original is retired.
    NODE_REPLACEMENT_COMPLETED("node-replacement-completed", "replacement", WarningLevel.INFO, NODE_REPLACEMENT_STARTED),
    /// The recovery of [#NODE_REPLACEMENT_STARTED], same subject, for a replacement that did not happen: the replacement was given
    /// up (it never joined, died, failed its canary or the swap) and the original is untouched.
    NODE_REPLACEMENT_ROLLED_BACK("node-replacement-rolled-back",
                                 "replacement",
                                 WarningLevel.WARNING,
                                 NODE_REPLACEMENT_STARTED),
    /// The replacement has not joined by the middle of its join budget; the replacement is rolled back at the end of it. Closed by
    /// [#NODE_REPLACEMENT_JOINED].
    NODE_REPLACEMENT_JOIN_OVERDUE("node-replacement-join-overdue", "replacement", WarningLevel.WARNING),
    /// The recovery of [#NODE_REPLACEMENT_JOIN_OVERDUE], same subject: the replacement joined after all.
    NODE_REPLACEMENT_JOINED("node-replacement-joined", "replacement", WarningLevel.INFO, NODE_REPLACEMENT_JOIN_OVERDUE),
    /// Draining the original is held back by the slice `minAvailable` floor (never forced). Raised once on the transition into
    /// the block; closed by [#NODE_REPLACEMENT_DRAIN_UNBLOCKED]. A drain that merely is not admitted yet is not reported.
    NODE_REPLACEMENT_DRAIN_BLOCKED("node-replacement-drain-blocked", "replacement", WarningLevel.WARNING),
    /// The recovery of [#NODE_REPLACEMENT_DRAIN_BLOCKED], same subject: the floor cleared and the drain was admitted.
    NODE_REPLACEMENT_DRAIN_UNBLOCKED("node-replacement-drain-unblocked",
                                     "replacement",
                                     WarningLevel.INFO,
                                     NODE_REPLACEMENT_DRAIN_BLOCKED),
    /// The replacement ended with BOTH nodes kept (a drain that never completed, a swap that applied but did not settle, a
    /// replacement lost after the original began to retire): the cluster runs one node over its size until an operator settles
    /// it. Closed by [#NODE_REPLACEMENT_SETTLED].
    NODE_REPLACEMENT_FAILED_KEPT_BOTH("node-replacement-failed-kept-both", "replacement", WarningLevel.WARNING),
    /// The recovery of [#NODE_REPLACEMENT_FAILED_KEPT_BOTH], same subject: an operator kept the new node or gave it up.
    NODE_REPLACEMENT_SETTLED("node-replacement-settled",
                             "replacement",
                             WarningLevel.INFO,
                             NODE_REPLACEMENT_FAILED_KEPT_BOTH),
    /// A core replacement has been refused by capacity admission (source busy, fleet limit reached, inventory unavailable) and is deferred
    /// to the next reconcile pass (#1404). Raised on the first refusal of a run, not on every tick; the subject is `core-provisioning`. Closed
    /// by [#PROVISION_ADMISSION_RESUMED]. A permanent refusal never closes, which is what makes it visible.
    PROVISION_ADMISSION_DEFERRED("provision-admission-deferred", "deployment", WarningLevel.WARNING),
    /// The recovery of [#PROVISION_ADMISSION_DEFERRED], same subject: a replacement passed admission and was dispatched.
    PROVISION_ADMISSION_RESUMED("provision-admission-resumed",
                                "deployment",
                                WarningLevel.INFO,
                                PROVISION_ADMISSION_DEFERRED),
    /// An allocation dispatched under an earlier leader term, whose instance no provider listing or lookup showed in several
    /// consecutive passes, was released (#1404). The subject is the node. A one-off resolution that tells the operator a leader decided an
    /// uncertain create did not happen: nothing stays open, so it is INFO (a condition that stays open would be a WARNING with a recovery); the
    /// release itself is logged at WARN where it happens.
    CAPACITY_DISPATCH_RELEASED("capacity-dispatch-released", "deployment", WarningLevel.INFO),
    /// A core deficit has been held open for longer than the join grace by committed allocations that never joined (#1404): an uncertain
    /// create (DISPATCHED) counts as capacity for ever, so no replacement is created until the allocation resolves. The subject is `core`.
    /// Closed by [#CORE_CAPACITY_DURABLE_RESOLVED].
    CORE_CAPACITY_AWAITING_DURABLE("core-capacity-awaiting-durable", "deployment", WarningLevel.WARNING),
    /// The recovery of [#CORE_CAPACITY_AWAITING_DURABLE], same subject: durable allocations no longer cover the deficit.
    CORE_CAPACITY_DURABLE_RESOLVED("core-capacity-durable-resolved",
                                   "deployment",
                                   WarningLevel.INFO,
                                   CORE_CAPACITY_AWAITING_DURABLE),
    /// A rolling upgrade run (#1543 part F) was started: every node is being replaced, serially, by a node running the target
    /// version, so the cluster runs mixed versions until it ends. The subject is always `upgrade` (there is one run). Closed by
    /// [#UPGRADE_COMPLETED] or [#UPGRADE_ABORTED].
    UPGRADE_STARTED("upgrade-started", "upgrade", WarningLevel.INFO),
    /// The recovery of [#UPGRADE_STARTED]: every node reports the target version.
    UPGRADE_COMPLETED("upgrade-completed", "upgrade", WarningLevel.INFO, UPGRADE_STARTED),
    /// The recovery of [#UPGRADE_STARTED] for a run an operator abandoned: the cluster is left running the versions named in the
    /// message, and no replacement is left half-done (an abort waits for the replacement in flight to end).
    UPGRADE_ABORTED("upgrade-aborted", "upgrade", WarningLevel.WARNING, UPGRADE_STARTED),
    /// The run stopped and needs an operator: a replacement was rolled back or stopped with both nodes kept, or an operator paused
    /// it. The message names the node, the reason and the way on. Closed by [#UPGRADE_RESUMED] or [#UPGRADE_PAUSE_ENDED].
    UPGRADE_PAUSED("upgrade-paused", "upgrade", WarningLevel.WARNING),
    /// The recovery of [#UPGRADE_PAUSED]: an operator resumed the run.
    UPGRADE_RESUMED("upgrade-resumed", "upgrade", WarningLevel.INFO, UPGRADE_PAUSED),
    /// The recovery of [#UPGRADE_PAUSED] when the run did not resume: it was aborted while paused.
    UPGRADE_PAUSE_ENDED("upgrade-pause-ended", "upgrade", WarningLevel.INFO, UPGRADE_PAUSED),
    /// A replica of a `confirmation_factor` 1 stream cut its divergent tail back to the last offset it shares with its
    /// owner (#1730 phase 2). With that factor the acknowledgement was the old owner's alone, so the discarded offsets
    /// may have been acknowledged and are lost; the message names them. A stream that confirms with replicas
    /// discards only unacknowledged records on the same cut, and that is only logged.
    STREAM_DIVERGENT_TAIL_TRUNCATED("stream-divergent-tail-truncated", "stream-replication", WarningLevel.WARNING),
    /// A replica cut its divergent tail back and, BEFORE removing anything, wrote the removed records (offset, timestamp,
    /// owner epoch, payload) to a recovery segment on its own volume (#2080). Raised for every cut that removed records,
    /// whatever the confirmation factor: the segment is the only copy of what left the live stream, and is never deleted
    /// automatically. The message names the stream, the partition, the offset range and the segment file.
    STREAM_DIVERGENT_TAIL_PRESERVED("stream-divergent-tail-preserved", "stream-replication", WarningLevel.WARNING),
    /// A replica's divergent-tail cut is REFUSED because what it must do first could not be done (#2084): the records could not be
    /// written to a recovery segment (`RepairPreserveFailed`) or the truncation witness could not be made durable
    /// (`RepairWitnessFailed`), typically a full or read-only volume. The copy stays quarantined with nothing removed and the repair is
    /// retried; raised once per distinct failure episode, naming the partition and the cause. Ended by
    /// [#STREAM_DIVERGENT_TAIL_CUT_RESUMED].
    STREAM_DIVERGENT_TAIL_CUT_REFUSED("stream-divergent-tail-cut-refused", "stream-replication", WarningLevel.WARNING),
    /// The recovery of a [#STREAM_DIVERGENT_TAIL_CUT_REFUSED] (#2084), same subject: the cut went through, its records preserved.
    STREAM_DIVERGENT_TAIL_CUT_RESUMED("stream-divergent-tail-cut-resumed",
                                      "stream-replication",
                                      WarningLevel.INFO,
                                      STREAM_DIVERGENT_TAIL_CUT_REFUSED),
    /// A stream partition's promotion proceeded past members that did not answer for longer than the bound (#2080): the
    /// candidate is named in the partition's committed in-sync set, so it holds every acknowledged record outside the
    /// full-cluster cold-restart case, where an unreachable node whose disk is ahead may hold acknowledged records the new
    /// lineage lacks. CRITICAL: a point event, raised once per escape. The message names the partition, the candidate, the
    /// members it proceeded without and the gate (owner activation or replica promotion contest).
    STREAM_PROMOTION_PAST_UNREACHABLE_PEERS("stream-promotion-past-unreachable-peers",
                                            "stream-replication",
                                            WarningLevel.CRITICAL),
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
    /// A stream consumer's cursor is below its partition's ring tail, so its reads are served from the sealed tier (#2201
    /// C-16, owner ruling Q10): raised once per `(group, partition)` episode, when a read first needs the tier. A new group
    /// that starts at `earliest` on a stream with sealed history raises one episode by design. The message names the group,
    /// the partition and the cursor.
    STREAM_CONSUMER_BEHIND_RING("stream-consumer-behind-ring", "stream-consumer", WarningLevel.WARNING),
    /// The recovery of a [#STREAM_CONSUMER_BEHIND_RING], same subject: a read was served from the ring again, or the
    /// consumer was cancelled while behind it (the message says which).
    STREAM_CONSUMER_BACK_IN_RING("stream-consumer-back-in-ring",
                                 "stream-consumer",
                                 WarningLevel.INFO,
                                 STREAM_CONSUMER_BEHIND_RING),
    /// A stream consumer's position is no longer held by its partition's owner (reclaimed by retention) and the group had not
    /// read it, so its cursor was moved forward to the earliest offset the owner holds and the range will never be delivered (#2201 C-16; the single exception to
    /// redelivery-never-skip, owner ruling Q9). CRITICAL (Q11): the group lost records it was configured to receive. Raised
    /// once per expiry, never per poll, and closed by [#STREAM_CONSUMER_CURSOR_RESUMED] once the cursor is re-established;
    /// that closes the alert, never the loss: the skip is done and nothing later undoes it. The message names the group, the
    /// partition, the skipped range and its count.
    STREAM_CONSUMER_CURSOR_EXPIRED("stream-consumer-cursor-expired", "stream-consumer", WarningLevel.CRITICAL),
    /// The recovery of a [#STREAM_CONSUMER_CURSOR_EXPIRED], same subject (owner ruling 2026-10-28 on Q11: no unclosed
    /// expiry): the group's cursor is re-established, because a read from the offset it moved to succeeded, the cursor moved
    /// again first (a later expiry or a rewind), or the consumer was cancelled (the message says which). The skipped range
    /// stays undelivered.
    STREAM_CONSUMER_CURSOR_RESUMED("stream-consumer-cursor-resumed",
                                   "stream-consumer",
                                   WarningLevel.INFO,
                                   STREAM_CONSUMER_CURSOR_EXPIRED),
    /// A stream consumer waits at an offset that no sealed segment holds while a later offset is sealed: a hole in the
    /// sealed range, above what retention reclaimed (#2201 C-16). The cursor does not move; the group retries with the poll
    /// backoff until the range is repaired. Retention never reclaims at or above a hole (its bound stops at the contiguous
    /// sealed watermark), so an unfilled hole holds the group. Raised once per `(group, partition, offset)`. The message
    /// names the range.
    STREAM_CONSUMER_SEALED_RANGE_MISSING("stream-consumer-sealed-range-missing", "stream-consumer", WarningLevel.WARNING),
    /// The recovery of a [#STREAM_CONSUMER_SEALED_RANGE_MISSING], same subject: a later read from that offset succeeded,
    /// the cursor moved off it, or the consumer was cancelled while waiting at it (the message says which).
    STREAM_CONSUMER_SEALED_RANGE_RESTORED("stream-consumer-sealed-range-restored",
                                          "stream-consumer",
                                          WarningLevel.INFO,
                                          STREAM_CONSUMER_SEALED_RANGE_MISSING),
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
                                 CLUSTER_TLS_RENEWAL_REFUSED),
    /// Request frames (invocations, forwards, stream or replication requests, command forwards) were held in a peer's offline
    /// buffer until their callers gave up, and were DROPPED at the flush instead of being delivered to the peer once it
    /// reattached (#1996). A point event: the frames are already gone, so there is no recovery. Subject is the peer; the message
    /// carries how many frames and of which message types. The exact total is the `quic_offline_expired_total` metric.
    OFFLINE_FRAMES_EXPIRED("offline-frames-expired", "cluster-transport", WarningLevel.WARNING),
    /// The leader minted a durable-entity keyspace's first ownership records over a hosting set that had not settled
    /// within the first-mint ceiling (#1734). Owners may be moved once the set settles, which opens a transient not-held
    /// window per moved arc. Subject is the keyspace.
    ENTITY_OWNERSHIP_UNSETTLED_MINT("entity-ownership-unsettled-mint", "entity-ownership", WarningLevel.WARNING),
    /// This node's identity ledger (#2429) does not protect it: the ledger file was lost on a node that is not new (the node admits no peer, so it is
    /// isolated from the cluster until the merge of ledgers at join exists), the ledger directory cannot be written at boot (the node runs without the restart
    /// protection), or a ledger write failed (retirements made from then on will not survive a restart until a write succeeds). Subject is the node. Raised on the transition
    /// into the state, not on every refused peer. Closed by [#IDENTITY_LEDGER_DURABLE].
    IDENTITY_LEDGER_NOT_DURABLE("identity-ledger-not-durable", "membership", WarningLevel.WARNING),
    /// The recovery of [#IDENTITY_LEDGER_NOT_DURABLE], same subject: a ledger write succeeded, so the node's retirements survive a restart
    /// again. INFO, published only after a published not-durable event for the node (#752 mechanism).
    IDENTITY_LEDGER_DURABLE("identity-ledger-durable", "membership", WarningLevel.INFO, IDENTITY_LEDGER_NOT_DURABLE),
    /// A SWIM datagram claimed a member identity from a source address other than the one that identity is
    /// pinned to, and was refused. A restart is not this: a new process carries a new identity and gets its own pin.
    SWIM_MEMBER_ADDRESS_CONFLICT("swim-member-address-conflict", "membership", WarningLevel.WARNING),
    /// A SWIM datagram presented a different process token for a member whose live process answered with its own,
    /// and was refused. A genuinely new process under the same identity is not this: it answers with the new token
    /// and is refused by the identity rule.
    SWIM_MEMBER_IDENTITY_CONFLICT("swim-member-identity-conflict", "membership", WarningLevel.WARNING),
    /// An observer (a node outside the voter roster, such as an auto-heal replacement still joining) stopped applying
    /// consensus decisions while it catches up or after it lost sight of the quorum. It is not a voter, so this is NOT a
    /// quorum loss and raises no `QUORUM_LOST`; routing on the node is quiesced until it observes again. Throttled per node like every operator
    /// warning; subject is the node; the message names it. Closed by [#OBSERVER_CONSENSUS_RESUMED].
    OBSERVER_CONSENSUS_LAGGING("observer-consensus-lagging", "consensus", WarningLevel.WARNING),
    /// The recovery of [#OBSERVER_CONSENSUS_LAGGING], same subject (#2306): the observer is observing again and applies committed
    /// decisions, or it left the lagging state another way (promoted to a voter, removed from the cluster, shutting down; the message
    /// says which). INFO, published only after a published lagging event for the node (#752 mechanism); an observer that never lagged
    /// publishes nothing.
    OBSERVER_CONSENSUS_RESUMED("observer-consensus-resumed", "consensus", WarningLevel.INFO, OBSERVER_CONSENSUS_LAGGING),
    /// Background promotion of a storage instance has copied nothing for several consecutive cycles while copies kept
    /// failing: a copy attempt fails when its faster tier rejects the write or its source tier cannot be read. It detects
    /// FAILING COPIES only: a tier sitting above its promotion watermark is skipped without an attempt and raises nothing.
    /// The subject is the storage instance; the message names it and what to do (check the tiers' health and free space,
    /// `memory_max_bytes` / `disk_max_bytes`, or set `promotion = false` for the instance). Closed by
    /// [#STORAGE_PROMOTION_RESUMED].
    STORAGE_PROMOTION_STALLED("storage-promotion-stalled", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_PROMOTION_STALLED], same subject: a promotion cycle copied a block successfully. A cycle
    /// with nothing to copy does not close it, because a tier that is still broken looks the same as an idle one. INFO,
    /// published only after a published stalled event for the instance (#752 mechanism).
    STORAGE_PROMOTION_RESUMED("storage-promotion-resumed", "storage", WarningLevel.INFO, STORAGE_PROMOTION_STALLED),
    /// A worker still has no committed activation directive after the community-absence window (#1840): its SWIM ANNOUNCE is held until the
    /// directive commits, so the cluster's SWIM scope does not include it. Typically the leader has not admitted it (no capacity reservation
    /// or admission authorisation), or the directive was refused. The subject is the node. Closed by [#WORKER_ASSIGNED].
    WORKER_ASSIGNMENT_OVERDUE("worker-assignment-overdue", "membership", WarningLevel.WARNING),
    /// The recovery of [#WORKER_ASSIGNMENT_OVERDUE], same subject (#1840): the worker's activation directive committed and its held SWIM ANNOUNCE was
    /// released. Raised only when the warning was.
    WORKER_ASSIGNED("worker-assigned", "membership", WarningLevel.INFO, WORKER_ASSIGNMENT_OVERDUE),
    /// An ephemeral-tier publisher fanned an event out to a subscriber whose handler takes the delivery context (#1337): that
    /// handler belongs to a durable topic, and an ephemeral delivery carries no envelope to build the context from. The delivery
    /// was refused and the event NOT delivered to that subscriber. The message names the topic, the publishing slice and the
    /// subscribing method. Subject is `<topic address>/<subscriber slice without its version>:<method>`, so redeploying the subscriber
    /// at a new version stays the same subject.
    TOPIC_TIER_MISMATCH("topic-tier-mismatch", "topic", WarningLevel.WARNING),
    /// The end of a `topic-tier-mismatch` (#1337): a later delivery from the same publisher reached the same subscriber slice (any
    /// version), the subscriber is no longer registered, or the publisher was released. INFO,
    /// published only after a published mismatch event for the same subject (#752 mechanism); a subscriber that was never refused
    /// publishes nothing.
    TOPIC_TIER_MISMATCH_RESOLVED("topic-tier-mismatch-resolved", "topic", WarningLevel.INFO, TOPIC_TIER_MISMATCH),
    /// A worker joined a source whose derived communities cannot take it (#241): every community of the source is at the hard ceiling and
    /// the source is at its `community_policy.max_communities`, so the worker gets no community. Subject is the source; the message names the
    /// worker and the bounds. Raise `max_communities` or `max_size` in the cluster config, or add worker capacity under another source; the
    /// worker takes a community when it rejoins. Closed by [#COMMUNITY_GROWTH_RESUMED].
    COMMUNITY_GROWTH_BLOCKED("community-growth-blocked", "community-growth", WarningLevel.WARNING),
    /// The recovery of [#COMMUNITY_GROWTH_BLOCKED], same subject (#241): a worker of the source was admitted to a community again (the bound
    /// was raised, or a community has room). INFO, published only after a published blocked event for the source (#752 mechanism).
    COMMUNITY_GROWTH_RESUMED("community-growth-resumed", "community-growth", WarningLevel.INFO, COMMUNITY_GROWTH_BLOCKED),
    /// A community placement operation (a worker replacement, a scale-down, a community retirement) is parked and needs an operator (#241): its
    /// drain acknowledgement deadline passed (DRAIN_UNCERTAIN), so the previous worker may or may not be drained, or it is BLOCKED by a
    /// conflict the reconciler cannot resolve by itself. Subject is the community; the message names the operation, the phase, both nodes
    /// and the command that clears it. Closed by [#COMMUNITY_PLACEMENT_RESUMED] or [#COMMUNITY_PLACEMENT_CLEARED].
    COMMUNITY_PLACEMENT_PARKED("community-placement-parked", "community-placement", WarningLevel.WARNING),
    /// The recovery of [#COMMUNITY_PLACEMENT_PARKED], same subject (#241): the operation left its parked phase by itself (a late drain
    /// acknowledgement, the target observed). INFO. It closes a warning another node may have raised: a leader change keeps the operation.
    COMMUNITY_PLACEMENT_RESUMED("community-placement-resumed",
                                "community-placement",
                                WarningLevel.INFO,
                                COMMUNITY_PLACEMENT_PARKED,
                                true),
    /// The other recovery of [#COMMUNITY_PLACEMENT_PARKED], same subject (#241): an OPERATOR cleared the parked operation (`aether cluster
    /// clear-placement`). Nothing was terminated or removed by the clear: the event names the operator, the reason the operator gave, the phase
    /// the operation was parked in and both nodes, which are left as they were. Closes the warning another node may have raised.
    COMMUNITY_PLACEMENT_CLEARED("community-placement-cleared",
                                "community-placement",
                                WarningLevel.INFO,
                                COMMUNITY_PLACEMENT_PARKED,
                                true),
    /// A worker has no community because the leader's commit of its assignment was refused several times in a row (#241): the community
    /// changed between each decision and its commit. Subject is the worker. The worker takes a community when it is admitted again (restart it
    /// if it does not). Closed by [#COMMUNITY_ASSIGNMENT_PLACED].
    COMMUNITY_ASSIGNMENT_UNPLACED("community-assignment-unplaced", "community-assignment", WarningLevel.WARNING),
    /// The recovery of [#COMMUNITY_ASSIGNMENT_UNPLACED], same subject (#241): the worker was assigned a community. INFO, published only after a
    /// published unplaced event for the worker (#752 mechanism).
    COMMUNITY_ASSIGNMENT_PLACED("community-assignment-placed",
                                "community-assignment",
                                WarningLevel.INFO,
                                COMMUNITY_ASSIGNMENT_UNPLACED),
    /// Auto-heal is disabled, so the cluster did NOT delete the instance of a node it would otherwise have reaped (#1038): a departed node,
    /// an orphan the sweep found, a surplus trim, the reap of a never-joined (join-grace) zombie, or the retry or re-check of any of them.
    /// A retirement directed from outside (the node-replacement protocol, a community-placement retirement) and a worker scale-down (config-directed)
    /// are operator intent and are not held. The instance is kept for diagnosis and keeps billing. Subject is the node. Raised
    /// once per node by the leader that held the reap. Closed by [#INSTANCE_REAP_RELEASED].
    INSTANCE_REAP_HELD("instance-reap-held", "deployment", WarningLevel.WARNING),
    /// The recovery of [#INSTANCE_REAP_HELD], same subject (#1038): auto-heal is enabled again and the reap of the node is no longer held (it still passes the usual liveness and refusal checks), or the instance
    /// was deleted by a directed retirement after the reap was held, or the node joined the cluster again and has nothing to reap. INFO, published only after a published held event for the node (#752 mechanism). It
    /// is raised by the leader that held the reap: a leader change in between leaves the held event open.
    INSTANCE_REAP_RELEASED("instance-reap-released", "deployment", WarningLevel.INFO, INSTANCE_REAP_HELD),
    /// An upload was refused 429 because this node's `[app-http.upload] max_concurrent` was reached (#2410): the node is holding
    /// as many upload bodies as it allows. Subject is the node. Raised once, on the transition into saturation, per node, not
    /// per refused request; a refusal for one principal's own cap is a client error and raises nothing. Closed by
    /// [#UPLOAD_NODE_CAP_RESTORED].
    UPLOAD_NODE_CAP_SATURATED("upload-node-cap-saturated", "http-ingest", WarningLevel.WARNING),
    /// The recovery of [#UPLOAD_NODE_CAP_SATURATED], same subject (#2410): an upload finished and a slot is free again. INFO,
    /// published only after a published saturation event for the node (#752 mechanism).
    UPLOAD_NODE_CAP_RESTORED("upload-node-cap-restored", "http-ingest", WarningLevel.INFO, UPLOAD_NODE_CAP_SATURATED),
    /// An upload was refused 503 because the declared bytes of the uploads in flight on this node reached `[app-http.upload]
    /// max_in_flight_size` (#2410): an upload body is aggregated in memory, so the node holds as much as it allows. Subject is
    /// the node. Raised once, on the transition into exhaustion, per node, not per refused request. Closed by
    /// [#UPLOAD_BYTE_BUDGET_RESTORED].
    UPLOAD_BYTE_BUDGET_EXHAUSTED("upload-byte-budget-exhausted", "http-ingest", WarningLevel.WARNING),
    /// The recovery of [#UPLOAD_BYTE_BUDGET_EXHAUSTED], same subject (#2410): uploads finished and at most half of the budget is
    /// reserved again. INFO, published only after a published exhaustion event for the node (#752 mechanism).
    UPLOAD_BYTE_BUDGET_RESTORED("upload-byte-budget-restored",
                                "http-ingest",
                                WarningLevel.INFO,
                                UPLOAD_BYTE_BUDGET_EXHAUSTED),
    /// A committed `[json]` configuration change was refused for a slice (#2378): the slice's HTTP routes keep the
    /// JSON codec selection they had. Subject is `<slice artifact>/json`; the message names every refused key, its
    /// value and why, and the selection kept. Raised once, on the transition into refusal, per node. The same
    /// configuration fails the slice's next provisioning (restart or redeploy), so fix or remove the keys. Closed by
    /// [#JSON_CONFIG_APPLIED].
    JSON_CONFIG_REJECTED("json-config-rejected", "json", WarningLevel.CRITICAL),
    /// A storage instance's disk tier could not be opened and the instance runs without it (A0b): its writes are
    /// acknowledged on memory and the shared tier alone, which a node loss or a whole-cluster restart does not survive.
    /// Raised for the instances the node placed itself (the synthesized `artifacts` and `content`, a derived path) and for
    /// `streams` on a directly built node; an operator-written `disk_path` that does not exist or cannot open refuses the boot instead. The
    /// subject is the instance. The tier is fixed at boot, so there is no recovery code: fix the path and restart.
    STORAGE_DISK_TIER_UNAVAILABLE("storage-disk-tier-unavailable", "storage", WarningLevel.CRITICAL),
    /// A configured memory tier was larger than the share of the heap storage may take, and was cut down to it (A0b). The
    /// subject is the instance; the message names the configured and the effective bytes and the heap.
    STORAGE_MEMORY_TIER_CLAMPED("storage-memory-tier-clamped", "storage", WarningLevel.WARNING),
    /// A durable tier is above its high watermark and holds the only durable copy of its blocks, so demotion is refused rather than
    /// paid for with durability; writes that need the tier fail with `TierFull` once it is full. The subject is the instance.
    /// Closed by [#STORAGE_DEMOTION_RESUMED].
    STORAGE_DEMOTION_REFUSED("storage-demotion-refused", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_DEMOTION_REFUSED], same subject: the tier dropped back under its high watermark. INFO, published only
    /// after a published refusal for the instance (#752 mechanism).
    STORAGE_DEMOTION_RESUMED("storage-demotion-resumed", "storage", WarningLevel.INFO, STORAGE_DEMOTION_REFUSED),
    /// The metadata snapshot of a storage instance cannot be written, so the references it holds are unprotected across a restart. The
    /// subject is the instance. Closed by [#STORAGE_SNAPSHOT_WRITE_RECOVERED].
    STORAGE_SNAPSHOT_WRITE_FAILED("storage-snapshot-write-failed", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_SNAPSHOT_WRITE_FAILED], same subject: a snapshot was written. INFO, published only after a published
    /// failure for the instance (#752 mechanism).
    STORAGE_SNAPSHOT_WRITE_RECOVERED("storage-snapshot-write-recovered",
                                     "storage",
                                     WarningLevel.INFO,
                                     STORAGE_SNAPSHOT_WRITE_FAILED),
    /// A storage instance restored an older retained snapshot because the newest was unreadable: metadata recorded only in the
    /// unreadable file is lost unless a WAL replays it. The subject is the instance. A restore happens once, at boot, so no recovery.
    STORAGE_SNAPSHOT_FELL_BACK("storage-snapshot-fell-back", "storage", WarningLevel.WARNING),
    /// A write was refused because a durable local-disk tier is full: the tier is a required tier of its instance, so the put
    /// fails with `TierFull` instead of landing on memory alone. Raised once per episode, on the first refused write; the episode ends when
    /// a write at least as large as the smallest one refused in it fits ([#STORAGE_TIER_WRITABLE]), so a full tier that alternates large and
    /// small writes raises once. The
    /// subject is the instance. Closed by [#STORAGE_TIER_WRITABLE].
    STORAGE_TIER_FULL("storage-tier-full", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_TIER_FULL], same subject: a write at least as large as the smallest one refused fit on the tier. INFO, published only after a published
    /// refusal for the instance (#752 mechanism).
    STORAGE_TIER_WRITABLE("storage-tier-writable", "storage", WarningLevel.INFO, STORAGE_TIER_FULL),
    /// The post-formation check of an instance's DHT encryption marker could not complete, so its DHT tier stays gated and the node
    /// stays not-ready until it does. Raised once, on the first failed attempt; the check keeps retrying. The subject is the
    /// instance. Closed by [#STORAGE_DHT_MARKER_VERIFIED]. A refusal (the marker disagrees with the configuration) aborts the boot
    /// and is not reported here.
    STORAGE_DHT_MARKER_UNVERIFIED("storage-dht-marker-unverified", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_DHT_MARKER_UNVERIFIED], same subject: a later attempt completed and the DHT tier was admitted. INFO,
    /// published only after a published failure for the instance (#752 mechanism).
    STORAGE_DHT_MARKER_VERIFIED("storage-dht-marker-verified",
                                "storage",
                                WarningLevel.INFO,
                                STORAGE_DHT_MARKER_UNVERIFIED),
    /// Demotion, promotion or garbage collection could not be started when the node reported ready, so the instance's tiers are not
    /// kept within their watermarks and its garbage is not reclaimed. The subject is the instance (`leader-maintenance` for the
    /// leader-pinned group). Closed by [#STORAGE_MAINTENANCE_STARTED].
    STORAGE_MAINTENANCE_NOT_STARTED("storage-maintenance-not-started", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_MAINTENANCE_NOT_STARTED], same subject: the managers were activated. INFO, published only after a
    /// published failure for the subject (#752 mechanism).
    STORAGE_MAINTENANCE_STARTED("storage-maintenance-started",
                                "storage",
                                WarningLevel.INFO,
                                STORAGE_MAINTENANCE_NOT_STARTED),
    /// A stream append log cut a torn tail while opening: bytes past the last valid record were discarded (they were never
    /// acknowledged). The subject is the storage instance; the message names the log, the byte range and the last valid offset. The
    /// cut happens once, at open, so there is no recovery code. Raised once per instance: a second log cut in the same boot is in
    /// the node log only.
    STORAGE_WAL_TAIL_CUT("storage-wal-tail-cut", "storage", WarningLevel.WARNING),
    /// The unmounted-volume guard of a storage instance is inactive: its sentinel lives on the same filesystem as the guarded volume, so
    /// it is lost with the volume and the node would boot empty on an unmounted one without refusing. The subject is the instance; the
    /// message names both paths. Decided once, at boot, so there is no recovery code: move the state directory
    /// (`-Daether.state.dir` / `AETHER_STATE_DIR`) off the volume and restart.
    STORAGE_VOLUME_GUARD_INACTIVE("storage-volume-guard-inactive", "storage", WarningLevel.WARNING),
    /// An append log fail-stopped after a failed fsync, frame write or reopen: every later append is refused until the node restarts and
    /// recovers the valid prefix. The subject is the storage instance; the message names the log and the cause. Raised once per
    /// instance (a second log failing in the same boot is in the node log only). No recovery code: a restart is the recovery.
    STORAGE_WAL_FAIL_STOPPED("storage-wal-fail-stopped", "storage", WarningLevel.CRITICAL),
    /// Block prefetching could not be started when the node reported ready, so the instance serves reads without the hints it would
    /// have acted on. The subject is the instance. Closed by [#STORAGE_PREFETCH_STARTED].
    STORAGE_PREFETCH_NOT_STARTED("storage-prefetch-not-started", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_PREFETCH_NOT_STARTED], same subject: the prefetch manager was activated. INFO, published only after a
    /// published failure for the instance (#752 mechanism).
    STORAGE_PREFETCH_STARTED("storage-prefetch-started", "storage", WarningLevel.INFO, STORAGE_PREFETCH_NOT_STARTED),
    /// The configured `[streaming] segment_disk_max_bytes` is larger than the space usable where stream segments are kept, so stream
    /// seals fail with `TierFull` before the cap is reached. The subject is `streams`. Decided once, at boot, so there is no recovery code:
    /// lower the cap or grow the volume and restart.
    STORAGE_STREAM_DISK_CAP_OVER_SPACE("storage-stream-disk-cap-over-space", "storage", WarningLevel.WARNING),
    /// The space usable where stream segments are kept could not be read, so the streams disk tier cap fell back to its floor. The subject
    /// is `streams`. Decided once, at boot, so there is no recovery code: set `[streaming] segment_disk_max_bytes` or fix the volume and restart.
    STORAGE_STREAM_DISK_SPACE_UNREADABLE("storage-stream-disk-space-unreadable", "storage", WarningLevel.WARNING),
    /// A block read from a tier did not hash to its id: the copy is corrupt and the read failed with an integrity error. The subject is the
    /// instance; the message names the tier and the block. Raised once per instance (later corrupt reads are in the node log only), and
    /// nothing observable proves a repair, so there is no recovery code.
    STORAGE_INTEGRITY_FAILED("storage-integrity-failed", "storage", WarningLevel.CRITICAL),
    /// The newest version of a durable anchor on one tier cannot be read back as written, or two durable tiers hold
    /// different values for it. The anchor is not read, and no older version is read in its place, until an operator
    /// accepts a version. The subject is `<instance>/<anchor>@<tier position>:<tier level>` (`@diverged` when two tiers
    /// disagree), so each tier and anchor is throttled and closed on its own; raised once per subject. Closed by
    /// [#STORAGE_ANCHOR_READABLE].
    STORAGE_ANCHOR_CORRUPT("storage-anchor-corrupt", "storage", WarningLevel.CRITICAL),
    /// The recovery of [#STORAGE_ANCHOR_CORRUPT], same subject: the newest version of that anchor on that tier reads back
    /// as written again (a newer version was written, or an operator accepted one). INFO, published only after a published
    /// [#STORAGE_ANCHOR_CORRUPT].
    STORAGE_ANCHOR_READABLE("storage-anchor-readable", "storage", WarningLevel.INFO, STORAGE_ANCHOR_CORRUPT),
    /// A write-behind flush to a slower tier failed, so the block is acknowledged but not yet on that tier. The subject is the instance. Closed
    /// by [#STORAGE_WRITE_BEHIND_FLUSHED].
    STORAGE_WRITE_BEHIND_FLUSH_FAILED("storage-write-behind-flush-failed", "storage", WarningLevel.WARNING),
    /// The recovery of [#STORAGE_WRITE_BEHIND_FLUSH_FAILED], same subject: a later flush succeeded. INFO, published only after a published
    /// failure for the instance (#752 mechanism).
    STORAGE_WRITE_BEHIND_FLUSHED("storage-write-behind-flushed",
                                 "storage",
                                 WarningLevel.INFO,
                                 STORAGE_WRITE_BEHIND_FLUSH_FAILED),
    /// A stream has a life that the committed config does not name: a WAL directory `<stream>@<incarnation>` and/or sealed segments of an
    /// incarnation the cluster state restored at startup does not know (typically a backup older than the stream's config), so the stream runs
    /// under another incarnation and the life's acknowledged records are never read. The subject is the life (`<stream>@<incarnation>`); the
    /// message names the stream, the orphaned incarnation, the adopted one and the sealed-segment count. Nothing deletes the life. Raised once
    /// per life per process; closed by [#STORAGE_STREAM_WAL_RELEASED].
    STORAGE_STREAM_WAL_ORPHANED("storage-stream-wal-orphaned", "storage", WarningLevel.WARNING),
    /// The final KV-backup flush of a graceful node stop failed or overran its deadline (the stop itself went ahead): cluster-state changes
    /// inside the backup's debounce window are not in the backup, so a whole-cluster restart restores from before them. The subject is the
    /// node's KV backup. Raised during shutdown; closed by [#BACKUP_STOP_FLUSH_RECOVERED] when a later flush of this node's backup succeeds.
    BACKUP_STOP_FLUSH_FAILED("backup-stop-flush-failed", "kv-backup", WarningLevel.WARNING),
    /// The recovery of [#BACKUP_STOP_FLUSH_FAILED]: the KV backup was written and pushed again, by a later flush of this node's backup
    /// (its marker, kept beside the backup repository, says a stop-time flush had failed). INFO. It closes across nodes because the
    /// failure was raised while a previous process stopped; the raiser holds the open state in that marker.
    BACKUP_STOP_FLUSH_RECOVERED("backup-stop-flush-recovered",
                                "kv-backup",
                                WarningLevel.INFO,
                                BACKUP_STOP_FLUSH_FAILED,
                                true),
    /// The recovery of [#STORAGE_STREAM_WAL_ORPHANED], same subject (the life `<stream>@<incarnation>`): a later adoption of the stream found
    /// that life gone (an operator removed it) or adopted it as the committed life (storage-identity adoption, #1569). INFO, published
    /// only after the warning for the subject. Detected at the next adoption of the same stream in this process; a life released while the
    /// process was down is announced afresh, or not at all, by the next boot.
    STORAGE_STREAM_WAL_RELEASED("storage-stream-wal-released", "storage", WarningLevel.INFO, STORAGE_STREAM_WAL_ORPHANED),
    /// A node's stream retention has withheld a stream's sealed history for two consecutive passes (#2201 3.2 item 2): the node has
    /// no retention policy it can read for the stream (its committed config is not applied there yet, or its retention mode is
    /// unknown to the build), and a rule it cannot read is not authority to delete. Nothing of the stream is reclaimed on that
    /// node meanwhile, so its disk use grows. The subject is `node <nodeId> stream <name>`. Closed by
    /// [#STREAM_RETENTION_RESUMED].
    STREAM_RETENTION_WITHHELD("stream-retention-withheld", "storage", WarningLevel.WARNING),
    /// The recovery of [#STREAM_RETENTION_WITHHELD], same subject: a pass judged the stream again, or the node no longer holds its
    /// sealed segments (the message says which).
    STREAM_RETENTION_RESUMED("stream-retention-resumed", "storage", WarningLevel.INFO, STREAM_RETENTION_WITHHELD),
    /// The block of a sealed stream segment cannot be read for its age for a reason no retry changes (#2201 3.2 item 8): a missing
    /// or wrong encryption key, a damaged or missing block. The segment is not read again until the node restarts, and retention
    /// judges it by the age of the next newer segment, so it neither holds the stream's history for ever nor goes before a newer
    /// segment. The subject is `node <nodeId> stream <name>[<partition>] <start>-<end>`. Closed by
    /// [#STREAM_SEGMENT_UNREADABLE_RECLAIMED].
    STREAM_SEGMENT_UNREADABLE("stream-segment-unreadable", "storage", WarningLevel.WARNING),
    /// The recovery of [#STREAM_SEGMENT_UNREADABLE], same subject: retention reclaimed the segment.
    STREAM_SEGMENT_UNREADABLE_RECLAIMED("stream-segment-unreadable-reclaimed",
                                        "storage",
                                        WarningLevel.INFO,
                                        STREAM_SEGMENT_UNREADABLE),
    /// A peer's SWIM datagram was encrypted under a gossip key id this node does not hold (#1200), and was dropped. The usual cause is a node that
    /// booted with only its `cluster_secret`-derived key after the cluster rotated its gossip key: its gossip and the cluster's cannot be read by
    /// either side until the node joins consensus and replays the rotation record, which normally takes seconds. Subject is the sender's address
    /// as `ip:port`, never a key; a UDP source address can be spoofed. An event that is not followed by its recovery means the sender did not
    /// catch up: check its peer list and `cluster_secret`. At most 16 new senders raise it per minute. Closed by [#GOSSIP_KEY_PEER_DECRYPTED].
    GOSSIP_KEY_UNKNOWN_FROM_PEER("gossip-key-unknown-from-peer", "membership", WarningLevel.WARNING),
    /// The recovery of [#GOSSIP_KEY_UNKNOWN_FROM_PEER], same subject (#1200): a datagram from that sender decrypted. INFO, published only after
    /// a published unknown-key event for the sender (#752 mechanism).
    GOSSIP_KEY_PEER_DECRYPTED("gossip-key-peer-decrypted", "membership", WarningLevel.INFO, GOSSIP_KEY_UNKNOWN_FROM_PEER),
    /// The summary of the senders [#GOSSIP_KEY_UNKNOWN_FROM_PEER] did not report one by one because more than 16 new sender addresses arrived within a
    /// minute (#1200), almost certainly spoofed or a flood: the count of suppressed sender addresses (a lower bound when it reaches the tracking cap
    /// of 1024). Subject is `unknown-gossip-key-senders`. INFO: it states a count, closes nothing and opens nothing (a WARNING needs a recovery), and the
    /// per-datagram WARN in the node log carries every datagram.
    GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED("gossip-key-unknown-senders-suppressed", "membership", WarningLevel.INFO),
    /// A node's collection of released stream segments from the cluster-shared tier has failed for three consecutive runs
    /// (#2258, spec 3.2 item 8): the DHT write quorum is unreachable or its removes fail. The released segments are already
    /// out of the node's index (readers are answered `CursorExpired`), but their blocks stay on disk and in the DHT, so usage
    /// does not fall while this lasts. The subject is `node <nodeId> storage streams`. Closed by
    /// [#STREAM_SEGMENT_DELETE_RECOVERED].
    STREAM_SEGMENT_DELETE_FAILING("stream-segment-delete-failing", "storage", WarningLevel.WARNING),
    /// The recovery of [#STREAM_SEGMENT_DELETE_FAILING], same subject: a run completed with no failed delete.
    STREAM_SEGMENT_DELETE_RECOVERED("stream-segment-delete-recovered",
                                    "storage",
                                    WarningLevel.INFO,
                                    STREAM_SEGMENT_DELETE_FAILING),
    /// A node's DHT engine holds 85% or more of its `[streaming] dht_segment_max_bytes` as live plus tombstone bytes of the
    /// `stream-segments` prefix (#2258, spec 3.2 item 8). Tombstones are not capped but are heap, so they count here: this is a
    /// heap warning, not a refusal. Raised once per episode. The subject is `node <nodeId> dht prefix stream-segments`. Closed by
    /// [#STREAM_SEGMENT_DHT_NEAR_FULL_CLEARED].
    STREAM_SEGMENT_DHT_NEAR_FULL("stream-segment-dht-near-full", "storage", WarningLevel.WARNING),
    /// The recovery of [#STREAM_SEGMENT_DHT_NEAR_FULL], same subject: live plus tombstone bytes fell below 80% of the cap.
    STREAM_SEGMENT_DHT_NEAR_FULL_CLEARED("stream-segment-dht-near-full-cleared",
                                         "storage",
                                         WarningLevel.INFO,
                                         STREAM_SEGMENT_DHT_NEAR_FULL),
    /// A node's DHT engine refused a client put because it would take the live bytes of the `stream-segments` prefix above
    /// `[streaming] dht_segment_max_bytes` (#2258, spec 3.2 item 8). The message names the cap, the usage and the refused size.
    /// Raised once per episode, at the first refusal; seals that need this replica fail until space is freed. The subject is
    /// `node <nodeId> dht prefix stream-segments`. Closed by [#STREAM_SEGMENT_DHT_FULL_CLEARED].
    STREAM_SEGMENT_DHT_FULL("stream-segment-dht-full", "storage", WarningLevel.CRITICAL),
    /// The recovery of [#STREAM_SEGMENT_DHT_FULL], same subject: live bytes fell below 85% of the cap.
    STREAM_SEGMENT_DHT_FULL_CLEARED("stream-segment-dht-full-cleared",
                                    "storage",
                                    WarningLevel.INFO,
                                    STREAM_SEGMENT_DHT_FULL),
    /// The durable local-disk tier of the streams instance is at or above its warning ratio on this node (#2258, spec 3.2 item 8,
    /// #1574): stream seals fail when it is full and owner publishes are refused from the refuse ratio. Raised once per episode.
    /// The subject is `node <nodeId> storage streams`. Closed by [#STREAM_SEGMENT_DISK_PRESSURE_RELIEVED].
    STREAM_SEGMENT_DISK_PRESSURE("stream-segment-disk-pressure", "storage", WarningLevel.WARNING),
    /// The recovery of [#STREAM_SEGMENT_DISK_PRESSURE], same subject: the tier fell below its warning ratio.
    STREAM_SEGMENT_DISK_PRESSURE_RELIEVED("stream-segment-disk-pressure-relieved",
                                          "storage",
                                          WarningLevel.INFO,
                                          STREAM_SEGMENT_DISK_PRESSURE),
    /// The recovery of [#JSON_CONFIG_REJECTED], same subject (#2378): a valid `[json]` configuration was adopted after a
    /// refusal. INFO, published only after a published rejection for the subject (#752 mechanism).
    JSON_CONFIG_APPLIED("json-config-applied", "json", WarningLevel.INFO, JSON_CONFIG_REJECTED),
    /// A `Watched`/`Live` configuration subscription could not take a change (#774): its callback threw, failed or timed out, or the
    /// section's record could not be bound from the new configuration (the message names the phase and carries the `applySeq`). The
    /// previous record stays in force and the next change is delivered; nothing is retried. Subject `<artifact>/<section>`. Raised on
    /// the transition into failure only; a repeat failure logs at ERROR without re-raising.
    CONFIG_WATCH_CALLBACK_FAILED("config-watch-callback-failed", "config", WarningLevel.WARNING),
    /// The recovery of [#CONFIG_WATCH_CALLBACK_FAILED], same subject (#774): the next delivery bound and (when it called back) succeeded.
    /// INFO, published only after a published failure for the subject (#752 mechanism).
    CONFIG_WATCH_CALLBACK_RECOVERED("config-watch-callback-recovered",
                                    "config",
                                    WarningLevel.INFO,
                                    CONFIG_WATCH_CALLBACK_FAILED);
    private final String code;
    private final String subsystem;
    private final WarningLevel level;
    private final Option<OperatorWarningCode> recoveryOf;
    private final boolean closesAcrossNodes;
    OperatorWarningCode(String code, String subsystem, WarningLevel level) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
        this.recoveryOf = Option.none();
        this.closesAcrossNodes = false;
    }
    OperatorWarningCode(String code, String subsystem, WarningLevel level, OperatorWarningCode recoveryOf) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
        this.recoveryOf = Option.some(recoveryOf);
        this.closesAcrossNodes = false;
    }
    OperatorWarningCode(String code,
                        String subsystem,
                        WarningLevel level,
                        OperatorWarningCode recoveryOf,
                        boolean closesAcrossNodes) {
        this.code = code;
        this.subsystem = subsystem;
        this.level = level;
        this.recoveryOf = Option.some(recoveryOf);
        this.closesAcrossNodes = closesAcrossNodes;
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
    /// Whether this recovery may be published by a node that did not raise the warning it closes (#2062): the warning's open state is held
    /// by the cluster, not by the raising node's event layer, so the layer need not have seen it. The raiser of such a recovery owns the
    /// "is it open" decision.
    public boolean closesAcrossNodes() {
        return closesAcrossNodes;
    }
    /// Whether some other code is the recovery of this one.
    public boolean hasRecovery() {
        return Arrays.stream(values()).anyMatch(other -> other.recoveryOf.filter(this::equals)
                                                                         .isPresent());
    }
}
