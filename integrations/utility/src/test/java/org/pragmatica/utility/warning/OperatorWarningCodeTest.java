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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The code catalogue is how operators identify a warning (#1574), so the codes must be unique and stable.
class OperatorWarningCodeTest {
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

    /// #2062: the operator clear is a RECOVERY of the unconfirmed warning, announced at INFO; a higher level would open a new alert while closing one.
    @Test
    void instanceTerminationCleared_isAnInfoRecoveryThatClosesAcrossNodes() {
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.code()).isEqualTo("instance-termination-cleared");
        assertThat(OperatorWarningCode.INSTANCE_TERMINATION_CLEARED.closesAcrossNodes()).isTrue();
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
    /// either gain a recovery or be added to the list on purpose, in review.
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
                            .isSubsetOf(UNCLOSED_BEFORE_THE_RULE);
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

    /// #2306: the observer lag warning is closed by an INFO recovery of its own subsystem.
    @Test
    void observerConsensusLagging_isClosedByAnInfoRecovery() {
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED.recoveryOf()
                                                                 .unwrap()).isEqualTo(OperatorWarningCode.OBSERVER_CONSENSUS_LAGGING);
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED.level()).isEqualTo(WarningLevel.INFO);
        assertThat(OperatorWarningCode.OBSERVER_CONSENSUS_LAGGING.level()).isEqualTo(WarningLevel.WARNING);
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

    private static final Set<OperatorWarningCode> UNCLOSED_BEFORE_THE_RULE = Set.of(
        // A0b, added on purpose: facts about one boot with no runtime recovery. The disk tier and the memory budget are fixed when
        // the instance is built and a snapshot is restored once; the only end of each is a restart, which starts a new event log slice.
        OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE,
        OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED,
        OperatorWarningCode.STORAGE_SNAPSHOT_FELL_BACK,
        OperatorWarningCode.STORAGE_WAL_TAIL_CUT,
        OperatorWarningCode.STORAGE_VOLUME_GUARD_INACTIVE,
        OperatorWarningCode.STORAGE_WAL_FAIL_STOPPED,
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
        // #2201 C-16, added on purpose at the overhaul/streams merge: a CRITICAL point event by owner ruling Q11 (e4bcbd2c7b), raised
        // once per expiry and never per poll; the records it skipped are gone, so no event can close it.
        OperatorWarningCode.STREAM_CONSUMER_CURSOR_EXPIRED,
        OperatorWarningCode.OFFLINE_FRAMES_EXPIRED,
        OperatorWarningCode.ENTITY_OWNERSHIP_UNSETTLED_MINT,
        OperatorWarningCode.SWIM_MEMBER_ADDRESS_CONFLICT,
        OperatorWarningCode.SWIM_MEMBER_IDENTITY_CONFLICT);

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
}
