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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The code catalogue is how operators identify a warning (#1574), so the codes must be unique and stable.
class OperatorWarningCodeTest {
    /// #2201 D-01, CTO ruling on #2615: a partition WAL's fail-stop refuses every publish to it on the node, so it pages (CRITICAL); its
    /// recovery only reports that the reopened log accepted a durable write (INFO).
    @Test
    void walFailStopPair_hasItsSeverities() {
        assertThat(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.STORAGE_WAL_WRITABLE.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STORAGE_WAL_WRITABLE.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED));
        assertThat(OperatorWarningCode.STORAGE_WAL_RETIRED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STORAGE_WAL_RETIRED.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED));
        assertThat(OperatorWarningCode.STORAGE_WAL_WRITABLE.closesAcrossNodes()).as("raised by a later process than the warning").isTrue();
        assertThat(OperatorWarningCode.STORAGE_WAL_RETIRED.closesAcrossNodes()).as("raised by the node that holds the open warning").isFalse();
    }

    @Test
    void codes_areUnique() {
        var byCode = Arrays.stream(OperatorWarningCode.values())
                           .collect(Collectors.groupingBy(OperatorWarningCode::code, Collectors.counting()));

        assertThat(byCode.entrySet()
                         .stream()
                         .filter(entry -> entry.getValue() > 1)
                         .map(Map.Entry::getKey))
            .as("two constants sharing a code would make their events indistinguishable")
            .isEmpty();
    }

    /// rc6 §4.4: the alert key of an escalated workflow and its recovery are operator-visible literals; a change would orphan open alerts.
    @Test
    void workflowEscalated_pinsItsCodeSubsystemAndLevel() {
        var escalated = OperatorWarningCode.WORKFLOW_ESCALATED;

        assertThat(escalated.code()).isEqualTo("workflow-escalated");
        assertThat(escalated.subsystem()).isEqualTo("workflow");
        assertThat(escalated.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(escalated.recoveryOf()).isEqualTo(Option.none());
    }

    @Test
    void workflowResolved_pinsItsCodeSubsystemLevelAndTheAlertItCloses() {
        var resolved = OperatorWarningCode.WORKFLOW_RESOLVED;

        assertThat(resolved.code()).isEqualTo("workflow-resolved");
        assertThat(resolved.subsystem()).isEqualTo("workflow");
        assertThat(resolved.level()).isEqualTo(WarningLevel.INFO);
        assertThat(resolved.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.WORKFLOW_ESCALATED));
        assertThat(resolved.closesAcrossNodes()).as("the open state is the committed run state, not this node's memory").isTrue();
    }

    @Test
    void workflowResumed_pinsItsCode_asAnInfoPointEventThatClosesNothing() {
        var resumed = OperatorWarningCode.WORKFLOW_RESUMED;

        assertThat(resumed.code()).isEqualTo("workflow-resumed");
        assertThat(resumed.subsystem()).isEqualTo("workflow");
        assertThat(resumed.level()).isEqualTo(WarningLevel.INFO);
        assertThat(resumed.recoveryOf()).isEqualTo(Option.none());
    }

    /// #2062: the operator clear is a RECOVERY of the unconfirmed warning, announced at INFO; a higher level would open a new alert while closing one.
    @Test
    void instanceTerminationCleared_isAnInfoRecoveryThatClosesAcrossNodes() {
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.code()).isEqualTo("instance-termination-cleared");
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.closesAcrossNodes()).isTrue();
    }

    /// N-5 (deployment FSM design 3.1): the mailbox backlog alarm and its recovery, a WARNING and an INFO of the `deployment` subsystem.
    @Test
    void deploymentFsmBacklog_isAWarning_withAnInfoRecoveryOfTheSameSubsystem() {
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG.code()).isEqualTo("deployment-fsm-backlog");
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG.subsystem()).isEqualTo("deployment");
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG_CLEARED.code()).isEqualTo("deployment-fsm-backlog-cleared");
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG_CLEARED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG_CLEARED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG);
        assertThat(OperatorWarningCode.DEPLOYMENT_FSM_BACKLOG.hasRecovery()).isTrue();
    }

    /// F1 survivor u16: a retirement refused for longer than its overdue bound leaves a terminated node's identity in the cluster view, so the
    /// alarm is a WARNING; its recovery is the INFO.
    @Test
    void terminationRetirementOverdue_isAWarning_withAnInfoRecoveryOfTheSameSubsystem() {
        assertThat(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE.subsystem()).isEqualTo("deployment");
        assertThat(OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE);
    }

    /// #2981 step 5: the lease and clock codes are operator-visible literals, each WARNING closed by an INFO recovery; the
    /// truncation codes split by outcome (refused WARNING with recovery, retracted INFO). A change would orphan open alerts or silence the lease signal.
    @Test
    void ownerLeaseCodes_arePinned() {
        assertThat(OperatorWarningCode.STREAM_OWNER_LEASE_LAPSED.code()).isEqualTo("stream-owner-lease-lapsed");
        assertThat(OperatorWarningCode.STREAM_OWNER_LEASE_LAPSED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.STREAM_OWNER_LEASE_RESTORED.code()).isEqualTo("stream-owner-lease-restored");
        assertThat(OperatorWarningCode.STREAM_OWNER_LEASE_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_OWNER_LEASE_RESTORED.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.STREAM_OWNER_LEASE_LAPSED));
        assertThat(OperatorWarningCode.NODE_CLOCK_RATE_DIVERGENCE.code()).isEqualTo("node-clock-rate-divergence");
        assertThat(OperatorWarningCode.NODE_CLOCK_RATE_DIVERGENCE.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.NODE_CLOCK_RATE_NORMAL.code()).isEqualTo("node-clock-rate-normal");
        assertThat(OperatorWarningCode.NODE_CLOCK_RATE_NORMAL.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.NODE_CLOCK_RATE_NORMAL.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.NODE_CLOCK_RATE_DIVERGENCE));
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_REFUSED.code()).isEqualTo("stream-truncation-refused");
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_REFUSED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_QUARANTINE_CLEARED.code()).isEqualTo("stream-truncation-quarantine-cleared");
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_QUARANTINE_CLEARED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_QUARANTINE_CLEARED.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.STREAM_TRUNCATION_REFUSED));
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_RETRACTED.code()).isEqualTo("stream-truncation-retracted");
        assertThat(OperatorWarningCode.STREAM_TRUNCATION_RETRACTED.level()).isEqualTo(WarningLevel.INFO);
    }

    @Test
    void codes_areKebabCase() {
        assertThat(Arrays.stream(OperatorWarningCode.values())
                         .map(OperatorWarningCode::code))
            .allMatch(code -> code.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"));
    }

    @Test
    void subsystems_areKebabCase() {
        assertThat(Arrays.stream(OperatorWarningCode.values())
                         .map(OperatorWarningCode::subsystem))
            .allMatch(subsystem -> subsystem.matches("[a-z][a-z0-9]*(-[a-z0-9]+)*"));
    }

    /// Every operator-attention event has a recovery event (owner rule, 2026-10-03/05). A WARNING or CRITICAL code that is
    /// not itself a recovery must therefore be closed by some recovery code. The codes in [#UNCLOSED_BEFORE_THE_RULE] were
    /// shipped as point events before the rule was enforced: the list is frozen, so a NEW unclosed code fails here and must
    /// either gain a recovery or be added to the list on purpose, in review. The codes in
    /// [#FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER] are the other frozen list: written after the rule, for an engine that is not
    /// active yet.
    @Test
    void everyWarningOrCriticalCode_hasARecovery_unlessListedAsUnclosedBeforeTheRule() {
        var closed = Arrays.stream(OperatorWarningCode.values())
                           .flatMap(code -> code.recoveryOf().stream())
                           .collect(Collectors.toSet());
        var unclosed = Arrays.stream(OperatorWarningCode.values())
                             .filter(code -> code.level() != WarningLevel.INFO)
                             .filter(code -> code.recoveryOf().isEmpty())
                             .filter(code -> !closed.contains(code))
                             .collect(Collectors.toSet());

        assertThat(unclosed).as("WARNING/CRITICAL codes without a recovery code that are not on the frozen list")
                            .isSubsetOf(Stream.concat(UNCLOSED_BEFORE_THE_RULE.stream(),
                                                      FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER.stream())
                                              .collect(Collectors.toSet()));
    }

    /// The frozen list may only shrink: a listed code that has gained a recovery (or been removed) must leave the list.
    @Test
    void unclosedBeforeTheRule_listsOnlyCodesThatAreStillUnclosed() {
        var closed = Arrays.stream(OperatorWarningCode.values())
                           .flatMap(code -> code.recoveryOf().stream())
                           .collect(Collectors.toSet());

        assertThat(UNCLOSED_BEFORE_THE_RULE).noneMatch(closed::contains);
        assertThat(UNCLOSED_BEFORE_THE_RULE).noneMatch(code -> code.level() == WarningLevel.INFO);
    }

    /// #1038: a reap held by disabled auto-heal is a WARNING with an INFO recovery for the same subject.
    @Test
    void instanceReapHeld_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.INSTANCE_REAP_RELEASED.recoveryOf()
                                                            .unwrap()).isEqualTo(OperatorWarningCode.INSTANCE_REAP_HELD);
        assertThat(OperatorWarningCode.INSTANCE_REAP_RELEASED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.INSTANCE_REAP_HELD.code()).isEqualTo("instance-reap-held");
        assertThat(OperatorWarningCode.INSTANCE_REAP_RELEASED.code()).isEqualTo("instance-reap-released");
        assertThat(OperatorWarningCode.INSTANCE_REAP_HELD.level()).isEqualTo(WarningLevel.WARNING);
    }

    /// The FSM2 list may only shrink, as the list above: a listed code that has gained a recovery (or been removed, or
    /// re-levelled to INFO) must leave the list.
    @Test
    void fsm2AwaitingRecovery_listsOnlyCodesThatAreStillUnclosed() {
        var closed = Arrays.stream(OperatorWarningCode.values())
                           .flatMap(code -> code.recoveryOf().stream())
                           .collect(Collectors.toSet());

        assertThat(FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER).noneMatch(closed::contains);
        assertThat(FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER).noneMatch(code -> code.level() == WarningLevel.INFO);
        assertThat(FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER).as("the two frozen lists are disjoint").doesNotContainAnyElementsOf(UNCLOSED_BEFORE_THE_RULE);
    }

    /// FSM2 cutover: every FSM2 code has a recovery or is INFO (the owner's D.6 ruling, know 7cc9e79f61), so the frozen list is empty.
    @Test
    void fsm2CutoverUnblocked_everyFsm2CodeHasARecovery() {
        assertThat(FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER).as("FSM2 cutover blocked: %d codes lack a recovery",
                                                              FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER.size())
                                                          .isEmpty();
    }

    /// FDB-2a (#2591): each schema-migration fence warning is closed by an INFO recovery of its own subsystem; the wire strings are pinned.
    @Test
    void schemaMigrationFenceWarnings_areClosedByInfoRecoveries() {
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED.recoveryOf()
                                                                      .unwrap()).isEqualTo(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD);
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCED.recoveryOf()
                                                              .unwrap()).isEqualTo(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED);
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED.code()).isEqualTo("schema-migration-fence-released");
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCED.code()).isEqualTo("schema-migration-fenced");
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED.subsystem()).isEqualTo(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD.subsystem());
        assertThat(OperatorWarningCode.SCHEMA_MIGRATION_FENCED.subsystem()).isEqualTo(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED.subsystem());
    }

    /// #2306: the observer lag warning is closed by an INFO recovery of its own subsystem.
    @Test
    void observerConsensusLagging_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED.recoveryOf()
                                                                 .unwrap()).isEqualTo(OperatorWarningCode.OBSERVER_CONSENSUS_LAGGING);
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_LAGGING.level()).isEqualTo(WarningLevel.WARNING);
    }

    /// #2316: a refused responder snapshot leaves the node out of service with no automatic exit, so it is critical and closed
    /// by an INFO recovery of the same subsystem when any restore succeeds; both wire strings are pinned.
    @Test
    void consensusRestoreRefused_isCritical_andIsClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_RECOVERED.recoveryOf()
                                                                  .unwrap()).isEqualTo(OperatorWarningCode.CONSENSUS_RESTORE_REFUSED);
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_RECOVERED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_REFUSED.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_REFUSED.code()).isEqualTo("consensus-restore-refused");
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_RECOVERED.code()).isEqualTo("consensus-restore-recovered");
        assertThat(OperatorWarningCode.CONSENSUS_RESTORE_RECOVERED.subsystem()).isEqualTo(OperatorWarningCode.CONSENSUS_RESTORE_REFUSED.subsystem());
    }

    /// #2201 C-16, owner ruling 2026-10-28 on Q11: a cursor expiry is critical and closed by an INFO recovery of the same
    /// subsystem when the cursor is re-established; both wire strings are pinned.
    @Test
    void cursorExpired_isCritical_andIsClosedByCursorResumed() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED.code()).isEqualTo("stream-consumer-cursor-expired");
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CURSOR_RESUMED.code()).isEqualTo("stream-consumer-cursor-resumed");
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CURSOR_RESUMED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CURSOR_RESUMED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED);
    }

    /// #2450 A3-0: a corrupt durable anchor is critical and closed by an INFO recovery of the same subsystem.
    @Test
    void anchorCorrupt_isCritical_andIsClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.STORAGE_ANCHOR_CORRUPT.code()).isEqualTo("storage-anchor-corrupt");
        assertThat(OperatorWarningCode.STORAGE_ANCHOR_CORRUPT.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.STORAGE_ANCHOR_READABLE.code()).isEqualTo("storage-anchor-readable");
        assertThat(OperatorWarningCode.STORAGE_ANCHOR_READABLE.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STORAGE_ANCHOR_READABLE.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.STORAGE_ANCHOR_CORRUPT);
    }

    /// #2429: the identity ledger's two codes are what the operator and the events API see: the strings, the level and the
    /// recovery relation are a contract.
    @Test
    void identityLedger_notDurableIsAWarning_andIsClosedByAnInfoRecoveryOfTheSameSubsystem() {
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE.code()).isEqualTo("identity-ledger-not-durable");
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_DURABLE.code()).isEqualTo("identity-ledger-durable");
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_DURABLE.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_DURABLE.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE);
        assertThat(OperatorWarningCode.IDENTITY_LEDGER_DURABLE.subsystem()).isEqualTo(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE.subsystem());
    }

    /// #241: a refused join into a full source is a WARNING with an INFO recovery for the same subject.
    @Test
    void communityGrowthBlocked_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.COMMUNITY_GROWTH_RESUMED.recoveryOf()
                                                              .unwrap()).isEqualTo(OperatorWarningCode.COMMUNITY_GROWTH_BLOCKED);
        assertThat(OperatorWarningCode.COMMUNITY_GROWTH_RESUMED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.COMMUNITY_GROWTH_BLOCKED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.COMMUNITY_GROWTH_BLOCKED.code()).isEqualTo("community-growth-blocked");
    }

    /// #241: a parked placement operation is a WARNING closed by either of two INFO recoveries, both of which may be raised by a node other
    /// than the one that raised the warning (a leader change keeps the operation).
    @Test
    void communityPlacementParked_isClosedByTwoInfoRecoveriesThatCloseAcrossNodes() {
        for (var recovery : List.of(OperatorWarningCode.COMMUNITY_PLACEMENT_RESUMED, OperatorWarningCode.COMMUNITY_PLACEMENT_CLEARED)) {
            assertThat(recovery.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.COMMUNITY_PLACEMENT_PARKED);
            assertThat(recovery.level()).isEqualTo(WarningLevel.INFO);
            assertThat(recovery.closesAcrossNodes()).isTrue();
        }
        assertThat(OperatorWarningCode.COMMUNITY_PLACEMENT_PARKED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.COMMUNITY_PLACEMENT_PARKED.code()).isEqualTo("community-placement-parked");
    }

    /// #1337: the tier-mismatch refusal is closed by an INFO recovery of its own subsystem, so it needs no place on the frozen list.
    @Test
    void topicTierMismatch_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.TOPIC_TIER_MISMATCH_RESOLVED.recoveryOf()
                                                                   .unwrap()).isEqualTo(OperatorWarningCode.TOPIC_TIER_MISMATCH);
        assertThat(OperatorWarningCode.TOPIC_TIER_MISMATCH_RESOLVED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.TOPIC_TIER_MISMATCH.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.TOPIC_TIER_MISMATCH.hasRecovery()).isTrue();
        assertThat(UNCLOSED_BEFORE_THE_RULE).doesNotContain(OperatorWarningCode.TOPIC_TIER_MISMATCH);
    }

    /// #241: a worker left without a community is a WARNING with an INFO recovery for the same subject.
    @Test
    void communityAssignmentUnplaced_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.COMMUNITY_ASSIGNMENT_PLACED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.COMMUNITY_ASSIGNMENT_UNPLACED);
        assertThat(OperatorWarningCode.COMMUNITY_ASSIGNMENT_PLACED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.COMMUNITY_ASSIGNMENT_UNPLACED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.COMMUNITY_ASSIGNMENT_UNPLACED.code()).isEqualTo("community-assignment-unplaced");
    }

    /// #2115: a refused core replacement is a WARNING (the cluster stays under-replaced), closed by an INFO recovery of the same subject. INFO
    /// would exempt the code from the recovery-pairing rule, so the level is pinned.
    @Test
    void coreReplacementSourceUnresolved_isAWarning_andIsClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.CORE_REPLACEMENT_SOURCE_UNRESOLVED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.CORE_REPLACEMENT_SOURCE_RESOLVED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.CORE_REPLACEMENT_SOURCE_RESOLVED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.CORE_REPLACEMENT_SOURCE_UNRESOLVED);
    }

    /// #2378 (know 14b6b16b8): a refused live `[json]` change is CRITICAL and closed by an INFO recovery of its own
    /// subsystem.
    @Test
    void jsonConfigRejected_isCritical_andClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.JSON_CONFIG_REJECTED.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.JSON_CONFIG_REJECTED.code()).isEqualTo("json-config-rejected");
        assertThat(OperatorWarningCode.JSON_CONFIG_APPLIED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.JSON_CONFIG_REJECTED);
        assertThat(OperatorWarningCode.JSON_CONFIG_APPLIED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.JSON_CONFIG_APPLIED.code()).isEqualTo("json-config-applied");
    }

    /// #2410: the upload node cap and the upload byte budget each warn once per episode and are closed by an INFO recovery
    /// of their own subject.
    @Test
    void uploadCapAndByteBudget_areWarnings_closedByInfoRecoveriesOfTheirOwn() {
        assertThat(OperatorWarningCode.UPLOAD_NODE_CAP_SATURATED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.UPLOAD_NODE_CAP_SATURATED.code()).isEqualTo("upload-node-cap-saturated");
        assertThat(OperatorWarningCode.UPLOAD_NODE_CAP_RESTORED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.UPLOAD_NODE_CAP_SATURATED);
        assertThat(OperatorWarningCode.UPLOAD_NODE_CAP_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.UPLOAD_BYTE_BUDGET_EXHAUSTED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.UPLOAD_BYTE_BUDGET_EXHAUSTED.code()).isEqualTo("upload-byte-budget-exhausted");
        assertThat(OperatorWarningCode.UPLOAD_BYTE_BUDGET_RESTORED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.UPLOAD_BYTE_BUDGET_EXHAUSTED);
        assertThat(OperatorWarningCode.UPLOAD_BYTE_BUDGET_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.UPLOAD_BYTE_BUDGET_RESTORED.code()).isEqualTo("upload-byte-budget-restored");
    }

    /// Know 3b01e624f1: the strings are what operators and alert rules match on, so they are pinned, with the level, the subsystem and the
    /// recovery pairing. Neither warning is on the frozen unclosed list: each has an INFO recovery of its own subsystem.
    @Test
    void backupStopFlush_andOrphanedStreamLife_arePinnedWarnings_eachClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_FAILED.code()).isEqualTo("backup-stop-flush-failed");
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_FAILED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_FAILED.subsystem()).isEqualTo("kv-backup");
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_RECOVERED.code()).isEqualTo("backup-stop-flush-recovered");
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_RECOVERED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_RECOVERED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.BACKUP_STOP_FLUSH_FAILED);
        assertThat(OperatorWarningCode.BACKUP_STOP_FLUSH_RECOVERED.closesAcrossNodes()).as("raised by a later process than the failure").isTrue();
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED.code()).isEqualTo("storage-stream-wal-orphaned");
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED.subsystem()).isEqualTo("storage");
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_RELEASED.code()).isEqualTo("storage-stream-wal-released");
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_RELEASED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STORAGE_STREAM_WAL_RELEASED.recoveryOf().unwrap()).isEqualTo(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED);
        assertThat(UNCLOSED_BEFORE_THE_RULE).doesNotContain(OperatorWarningCode.BACKUP_STOP_FLUSH_FAILED, OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED);
    }

    private static final Set<OperatorWarningCode> UNCLOSED_BEFORE_THE_RULE = Set.of(
        // A0b, added on purpose: facts about one boot with no runtime recovery. The disk tier and the memory budget are fixed when
        // the instance is built and a snapshot is restored once; the only end of each is a restart, which starts a new event log slice.
        OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE,
        OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED,
        OperatorWarningCode.STORAGE_SNAPSHOT_FELL_BACK,
        OperatorWarningCode.STORAGE_WAL_TAIL_CUT,
        // D1, added on purpose: a failed KV dispatcher stops (in production, halts) its node; the only end is a restart.
        OperatorWarningCode.KV_DISPATCHER_FAILED,
        OperatorWarningCode.STORAGE_VOLUME_GUARD_INACTIVE,
        OperatorWarningCode.STORAGE_STREAM_DISK_CAP_OVER_SPACE,
        OperatorWarningCode.STORAGE_STREAM_DISK_SPACE_UNREADABLE,
        OperatorWarningCode.STORAGE_INTEGRITY_FAILED,
        OperatorWarningCode.SWIM_KILL_GATE_HELD,
        OperatorWarningCode.CORE_ABSENCE_FENCE,
        OperatorWarningCode.REPLICA_FSYNC_FAILED,
        OperatorWarningCode.REPLICATION_POLICY_WARNING,
        OperatorWarningCode.REPLICATION_FACTOR_BELOW_THREE,
        OperatorWarningCode.DEPLOY_WARNING,
        OperatorWarningCode.CLUSTER_EVENTS_REGISTRATION_REFUSED,
        OperatorWarningCode.BACKUP_RESTORE_ENTITY_CHECKPOINTS_DROPPED,
        OperatorWarningCode.BACKUP_FORKED,
        OperatorWarningCode.NODE_NEVER_JOINED,
        OperatorWarningCode.SLICE_FLOOR_BREACHED_BY_FORCE,
        OperatorWarningCode.NODE_DRAIN_CANCELLED_REJOINED,
        OperatorWarningCode.NODE_RETIRED_IDENTITY_REJOINED,
        OperatorWarningCode.EXTERNAL_REJOIN_UNRECONCILED,
        OperatorWarningCode.STREAM_DIVERGENT_TAIL_TRUNCATED,
        OperatorWarningCode.STREAM_DIVERGENT_TAIL_PRESERVED,
        OperatorWarningCode.STREAM_PROMOTION_PAST_UNREACHABLE_PEERS,
        OperatorWarningCode.STREAM_CONSUMER_DETACH_FOUND_NOTHING,
        OperatorWarningCode.STREAM_CONSUMER_REWOUND,
        OperatorWarningCode.OFFLINE_FRAMES_EXPIRED,
        OperatorWarningCode.ENTITY_OWNERSHIP_UNSETTLED_MINT,
        OperatorWarningCode.SWIM_MEMBER_ADDRESS_CONFLICT,
        OperatorWarningCode.SWIM_MEMBER_IDENTITY_CONFLICT,
        // Know 73fd8d9759, added on purpose: a fact about one graceful stop with no runtime recovery. The process that raises
        // it is ending; what it left unsealed is replayed from its WAL at restart, or is lost with the heap.
        OperatorWarningCode.STREAM_SEAL_STOP_INCOMPLETE);

    /// The deployment FSM rewrite's WARNING codes that have no recovery yet (phase-3 design 4.2, 5.2, 8.2). They were written after
    /// the recovery rule, so they are NOT on [#UNCLOSED_BEFORE_THE_RULE]: FSM2 is not the active deployment engine in rc5, and each
    /// of these must gain a recovery, be re-levelled, or be removed before the FSM2 cutover. Frozen and shrink-only, like the list
    /// above; [#fsm2CutoverBlocked_whileAnFsm2CodeLacksARecovery] keeps the cutover blocked while it is not empty.
    /// Emptied by the owner's D.6 ruling (know 7cc9e79f61, spec 9.4): it may never grow again.
    private static final Set<OperatorWarningCode> FSM2_AWAITING_RECOVERY_BEFORE_CUTOVER = Set.of();

    /// The D.6 table as ruled (spec 9.4, know 7cc9e79f61): rows re-levelled to INFO with no recovery.
    private static final Set<OperatorWarningCode> D6_INFO_ONE_SHOT = Set.of(OperatorWarningCode.ACTIVATION_UNPERMITTED,
                                                                          OperatorWarningCode.BLUEPRINT_DELETE_REFUSED,
                                                                          OperatorWarningCode.BLUEPRINT_REJECTED,
                                                                          OperatorWarningCode.BOOT_TOKEN_ABSENT,
                                                                          OperatorWarningCode.IDENTITY_PROCESS_MISMATCH,
                                                                          OperatorWarningCode.JOIN_REFUSED_PROCESS,
                                                                          OperatorWarningCode.JOIN_REFUSED_SUPERSEDED,
                                                                          OperatorWarningCode.WORKER_READMITTED_ON_EVIDENCE);

    /// The D.6 table as ruled: each recovery (INFO) and the WARNING it closes; rows 7, 16 and 19 are the re-levelled recoveries of 6, 15, 18.
    private static final Map<OperatorWarningCode, OperatorWarningCode> D6_RECOVERIES = Map.ofEntries(Map.entry(OperatorWarningCode.DEPLOYMENT_ENGINE_MATCHED,
                                                                                                             OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH),
                                                                                                   Map.entry(OperatorWarningCode.DEPARTURE_INFERRED_REVERSED,
                                                                                                             OperatorWarningCode.DEPARTURE_INFERRED),
                                                                                                   Map.entry(OperatorWarningCode.DEPARTURE_INFERRED_CONFIRMED,
                                                                                                             OperatorWarningCode.DEPARTURE_INFERRED),
                                                                                                   Map.entry(OperatorWarningCode.DEPARTURE_BREAKER_CLEARED,
                                                                                                             OperatorWarningCode.DEPARTURE_INFERRED_SUPPRESSED),
                                                                                                   Map.entry(OperatorWarningCode.PLACEMENT_VALID,
                                                                                                             OperatorWarningCode.INVALID_PLACEMENT),
                                                                                                   Map.entry(OperatorWarningCode.JOIN_MATCHED,
                                                                                                             OperatorWarningCode.JOIN_UNMATCHED),
                                                                                                   Map.entry(OperatorWarningCode.RETRY_EXHAUSTED_SETTLED,
                                                                                                             OperatorWarningCode.RETRY_EXHAUSTED_UNSETTLED),
                                                                                                   Map.entry(OperatorWarningCode.SCHEMA_HOLD_CLEARED,
                                                                                                             OperatorWarningCode.SCHEMA_HOLD),
                                                                                                   Map.entry(OperatorWarningCode.SCHEMA_STATUS_READABLE,
                                                                                                             OperatorWarningCode.SCHEMA_STATUS_UNKNOWN),
                                                                                                   Map.entry(OperatorWarningCode.SLICE_UNRESPONSIVE_EXPIRED,
                                                                                                             OperatorWarningCode.SLICE_UNRESPONSIVE),
                                                                                                   Map.entry(OperatorWarningCode.STRAY_INSTANCE_REMOVED,
                                                                                                             OperatorWarningCode.STRAY_INSTANCE),
                                                                                                   Map.entry(OperatorWarningCode.WORKER_ABSENCE_JUDGEABLE,
                                                                                                             OperatorWarningCode.WORKER_ABSENCE_UNJUDGEABLE),
                                                                                                   Map.entry(OperatorWarningCode.WORKER_COMMUNITY_POLICY_MATCHED,
                                                                                                             OperatorWarningCode.WORKER_NO_COMMUNITY_POLICY));

    /// D.6 (know 7cc9e79f61): the 11 re-levelled rows are INFO (8 one-shots and the 3 recoveries), every recovery closes the row the table
    /// names, each closed condition is a deployment WARNING, and the wire strings of the 10 new codes are pinned.
    @Test
    void d6Ruling_levelsAndRecoveries_matchTheRuledTable() {
        assertThat(D6_INFO_ONE_SHOT).allMatch(code -> code.level() == WarningLevel.INFO && code.recoveryOf().isEmpty() && !code.hasRecovery());
        D6_RECOVERIES.forEach((recovery, opener) -> {
            assertThat(recovery.level()).as(recovery.code()).isEqualTo(WarningLevel.INFO);
            assertThat(recovery.recoveryOf()).as(recovery.code()).isEqualTo(Option.some(opener));
            assertThat(recovery.subsystem()).as(recovery.code()).isEqualTo("deployment");
            assertThat(opener.level()).as(opener.code()).isEqualTo(WarningLevel.WARNING);
        });
        assertThat(D6_RECOVERIES.keySet().stream().map(OperatorWarningCode::code)).contains("deployment-engine-matched",
                                                                                            "departure-inferred-confirmed",
                                                                                            "departure-breaker-cleared",
                                                                                            "placement-valid",
                                                                                            "join-matched",
                                                                                            "retry-exhausted-settled",
                                                                                            "schema-status-readable",
                                                                                            "stray-instance-removed",
                                                                                            "worker-absence-judgeable",
                                                                                            "worker-community-policy-matched");
        assertThat(D6_RECOVERIES.values().stream().distinct().count() + D6_INFO_ONE_SHOT.size() + 3).as("the 23 rows: 12 closed conditions, 8 one-shots, the 3 re-levelled recoveries (rows 7, 16, 19)")
                                                                                     .isEqualTo(23);
    }

    /// CTO ruling A2 on #2831 (O3), extended by #2834: of the D.6 recoveries, exactly those whose open condition is committed state close
    /// across nodes, so a successor leader publishes them. Rows 6/7 (the inferred-departure record) and row 19 (the committed exclusions)
    /// always were; the leader-local families commit their open set (`FsmWarningKey`) since #2834. `deployment-engine-matched` is raised by
    /// each node about itself, so it does not. Red when: a committed-family recovery is refused by a successor, or the node-local recovery is
    /// published by a node that never raised its warning.
    @Test
    void d6Ruling_theCommittedStateRecoveries_closeAcrossNodes() {
        assertThat(D6_RECOVERIES.keySet().stream().filter(OperatorWarningCode::closesAcrossNodes)).containsExactlyInAnyOrder(OperatorWarningCode.DEPARTURE_INFERRED_REVERSED,
                                                                                                                          OperatorWarningCode.DEPARTURE_INFERRED_CONFIRMED,
                                                                                                                          OperatorWarningCode.SLICE_UNRESPONSIVE_EXPIRED,
                                                                                                                          OperatorWarningCode.DEPARTURE_BREAKER_CLEARED,
                                                                                                                          OperatorWarningCode.PLACEMENT_VALID,
                                                                                                                          OperatorWarningCode.JOIN_MATCHED,
                                                                                                                          OperatorWarningCode.RETRY_EXHAUSTED_SETTLED,
                                                                                                                          OperatorWarningCode.SCHEMA_HOLD_CLEARED,
                                                                                                                          OperatorWarningCode.SCHEMA_STATUS_READABLE,
                                                                                                                          OperatorWarningCode.STRAY_INSTANCE_REMOVED,
                                                                                                                          OperatorWarningCode.WORKER_ABSENCE_JUDGEABLE,
                                                                                                                          OperatorWarningCode.WORKER_COMMUNITY_POLICY_MATCHED);
    }

    /// #752: a recovery code closes a condition of its own subsystem, and a condition is not itself a recovery, so a
    /// recovery is never itself waiting for one.
    @Test
    void recoveryCodes_closeAnOrdinaryConditionOfTheirOwnSubsystem() {
        var recoveries = Arrays.stream(OperatorWarningCode.values())
                               .filter(code -> code.recoveryOf().isPresent())
                               .toList();

        assertThat(recoveries).as("control: the catalogue has a recovery").isNotEmpty();
        assertThat(recoveries).allSatisfy(recovery -> {
            var closes = recovery.recoveryOf().unwrap();
            assertThat(closes.subsystem()).isEqualTo(recovery.subsystem());
            assertThat(closes.recoveryOf().isPresent()).isFalse();
            assertThat(closes.hasRecovery()).isTrue();
            assertThat(recovery.hasRecovery()).isFalse();
        });
    }

    @Test
    void streamConsumerRecovery_isInfo_andClosesTheDivergence() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_STATE_REPAIRED.recoveryOf().unwrap())
            .isEqualTo(OperatorWarningCode.STREAM_CONSUMER_STATE_DIVERGED);
    }

    /// #2600: a slice ACTIVE on no live core or worker is a WARNING; its restoration is the INFO recovery that closes it.
    @Test
    void candidateRestored_isInfo_andClosesNoLiveCandidate() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_NO_LIVE_CANDIDATE.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_NO_LIVE_CANDIDATE.code()).isEqualTo("stream-consumer-no-live-candidate");
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CANDIDATE_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CANDIDATE_RESTORED.code()).isEqualTo("stream-consumer-candidate-restored");
        assertThat(OperatorWarningCode.STREAM_CONSUMER_CANDIDATE_RESTORED.recoveryOf().unwrap())
            .isEqualTo(OperatorWarningCode.STREAM_CONSUMER_NO_LIVE_CANDIDATE);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_NO_LIVE_CANDIDATE.hasRecovery()).isTrue();
    }

    /// #1941's resolved counterpart is a recovery, so it is INFO and paired with the not-registered condition.
    @Test
    void registeredAgain_isInfo_andClosesNotRegistered() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_REGISTERED_AGAIN.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_REGISTERED_AGAIN.recoveryOf().unwrap())
            .isEqualTo(OperatorWarningCode.STREAM_CONSUMER_NOT_REGISTERED);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_NOT_REGISTERED.hasRecovery()).isTrue();
    }

    /// #1934: the end of a failing drain is a recovery, so it is INFO and paired with the failing condition.
    @Test
    void drainRestored_isInfo_andClosesDrainFailing() {
        assertThat(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.STREAM_CONSUMER_DRAIN_RESTORED.recoveryOf().unwrap())
            .isEqualTo(OperatorWarningCode.STREAM_CONSUMER_DRAIN_FAILING);
    }

    /// Positive control for [#codes_areUnique]: the same grouping reports a duplicate when one exists.
    @Test
    void uniquenessCheck_detectsADuplicate() {
        var duplicated = Arrays.stream(new String[]{"a-b", "c-d", "a-b"})
                               .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(duplicated.entrySet()
                             .stream()
                             .filter(entry -> entry.getValue() > 1)
                             .map(Map.Entry::getKey))
            .containsExactly("a-b");
    }

    /// #774: the level of the config-watch pair is part of the operator contract.
    @Test
    void configWatchPair_isAWarning_thatItsInfoRecoveryCloses() {
        assertThat(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.CONFIG_WATCH_CALLBACK_RECOVERED.recoveryOf()).isEqualTo(Option.some(OperatorWarningCode.CONFIG_WATCH_CALLBACK_FAILED));
    }

    /// Operators match on the wire string, level and category (#1574), so the gossip-key pair and its summary (#1200) are pinned as shipped.
    @Test
    void gossipKeyCodes_areStable_theirLevelsAndPairingAsShipped() {
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER.code()).isEqualTo("gossip-key-unknown-from-peer");
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER.level()).isEqualTo(WarningLevel.WARNING);
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER.subsystem()).isEqualTo("membership");
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER.hasRecovery()).isTrue();
        assertThat(OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED.code()).isEqualTo("gossip-key-peer-decrypted");
        assertThat(OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED.subsystem()).isEqualTo("membership");
        assertThat(OperatorWarningCode.GOSSIP_KEY_PEER_DECRYPTED.recoveryOf().or(OperatorWarningCode.JSON_CONFIG_APPLIED))
            .isEqualTo(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_FROM_PEER);
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED.code()).isEqualTo("gossip-key-unknown-senders-suppressed");
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED.subsystem()).isEqualTo("membership");
        assertThat(OperatorWarningCode.GOSSIP_KEY_UNKNOWN_SENDERS_SUPPRESSED.recoveryOf().isEmpty()).as("a point event").isTrue();
    }

    /// #2034 (#2684 round B): the sole-copy departure warning is CRITICAL (data is lost with the node unless restored); its recovery,
    /// raised by the draining node itself, is an INFO that names it as the recovery of that warning.
    @Test
    void dhtDepartureSoleCopy_isCriticalWithAnInfoRecovery() {
        assertThat(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK.code()).isEqualTo("dht-departure-sole-copy-at-risk");
        assertThat(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK.level()).isEqualTo(WarningLevel.CRITICAL);
        assertThat(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED.code()).isEqualTo("dht-departure-sole-copy-delivered");
        assertThat(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_DELIVERED.recoveryOf().or(OperatorWarningCode.JSON_CONFIG_APPLIED))
            .isEqualTo(OperatorWarningCode.DHT_DEPARTURE_SOLE_COPY_AT_RISK);
    }
}
