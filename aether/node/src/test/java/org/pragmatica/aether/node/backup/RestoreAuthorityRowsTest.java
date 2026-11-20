// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3303 T-RA0: the restore-authority table drives every case. `restore-authority-rows.tsv` (beside this class's package
/// in the test resources) is the generator's `--rows` output (restore-authority r3.1 §2); the generator's `--check` keeps
/// it equal to the note. States are `BackupRestoreOutcome.values()` with `IN_PROGRESS` split by `rejection` and `abandon`,
/// plus absent: 11 states, 121 pairs (a pair into absent is a delete), and 6 violating variants of the conditioned rows.
/// Every pair runs through every path against the real [BackupRestoreValue] at the real [BackupRestoreKey] in a real
/// [KVStore]; a write is admitted iff a `Put` row names its pair. Each case starts from its own store, the committed
/// state installed by snapshot (the one path outside the fence), so no case depends on another.
class RestoreAuthorityRowsTest {
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final BackupRestoreKey KA = BackupRestoreKey.backupRestoreKey();
    private static final LeaderValue LEADER = LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                            .unwrap(),
                                                                      1L);
    private static final String TSV = "restore-authority-rows.tsv";
    private static final String LINEAGE = "01K4ZT9Q6W3X8Y2B7C5D1E0F9G";
    private static final String COMMIT = "2222222222222222222222222222222222222222";
    private static final String K = "config/alpha";
    private static final String K_PRIME = "config/beta";
    private static final String ABSENT = "absent";
    private static final String ANY = "*";
    private static final String NONE = "-";
    private static final String HEADER_EQUAL = "header equal";
    private static final String DISTINCT_REJECTION = "k'!=k";
    private static final String PUT = "Put";
    private static final String BARE_PUT = "bare Put";
    private static final String NON_AUTHORITY = "non-authority Put or mutation";
    private static final String DELETE_ARM = "delete arm";
    private static final String BARE_REMOVE = "bare Remove";
    private static final Set<String> PATHS = Set.of(PUT, BARE_PUT, NON_AUTHORITY, DELETE_ARM, BARE_REMOVE);
    private static final Set<String> CONDITIONS = Set.of(NONE, HEADER_EQUAL, HEADER_EQUAL + ", " + DISTINCT_REJECTION);

    private record Row(int number, String from, String to, String condition, String path) {}

    private long slot;

    // --- the table ---

    @Test
    void everyPairThroughEveryPath_isAdmittedIffAPutRowNamesIt() {
        var rows = rows();
        var states = states();
        var mismatches = new ArrayList<String>();
        var admitted = 0;

        for (var from : states) {
            for (var to : states) {
                if (to.equals(ABSENT)) {
                    continue;
                }
                var expected = putRow(rows, from, to).isPresent();
                var actual = putAdmitted(from, target(to));

                admitted += actual ? 1 : 0;
                if (expected != actual) {
                    mismatches.add("Put " + from + " -> " + to + ": expected " + (expected ? "admitted" : "refused"));
                }
            }
        }
        for (var row : rows) {
            if (!row.path().equals(PUT)) {
                mismatches.addAll(refusedOnEveryPair(row, states));
            }
        }

        assertThat(states).as("11 states: the outcomes, IN_PROGRESS split four ways, and absent").hasSize(11);
        assertThat(rows).as("the TSV lands r3.1's 14 rows").hasSize(14);
        assertThat(mismatches).as("every pair agrees with the table").isEmpty();
        assertThat(admitted).as("exactly the 10 Put rows admit").isEqualTo(10);
    }

    @Test
    void everyConditionedRow_isRefusedWhenOneConditionIsViolated() {
        var mismatches = new ArrayList<String>();
        var variants = 0;

        for (var row : rows()) {
            if (!row.path().equals(PUT) || row.condition().equals(NONE)) {
                continue;
            }
            for (var clause : row.condition().split(", ")) {
                variants++;
                if (putAdmitted(row.from(), violating(row, clause))) {
                    mismatches.add("row " + row.number() + " admitted with [" + clause + " violated]");
                }
            }
        }

        assertThat(variants).as("6 violating variants: rows 5-9 header, row 9 k'!=k").isEqualTo(6);
        assertThat(mismatches).isEmpty();
    }

    @Test
    void anIncomingUnknown_isRefusedOverEveryState() {
        for (var from : states()) {
            assertThat(putAdmitted(from, BackupRestoreValue.decided(BackupRestoreOutcome.UNKNOWN))).as("UNKNOWN over %s", from)
                                                                                                  .isFalse();
        }
    }

    // --- admitsUnits and isTerminal ---

    @Test
    void admitsUnits_onlyUnderAnInProgressMarkerThatIsNotBeingAbandoned() {
        assertThat(committed("(c)").unwrap().admitsUnits()).isTrue();
        assertThat(committed("(c,r)").unwrap().admitsUnits()).as("REJECTED is IN_PROGRESS: units still land").isTrue();
        assertThat(committed("(c,a)").unwrap().admitsUnits()).as("abandon (2) removes the gated keys").isFalse();
        assertThat(BackupRestoreValue.decided(BackupRestoreOutcome.RESTORED).admitsUnits()).isFalse();
    }

    @Test
    void isTerminal_includesAbandoned_andNeverUnknown() {
        assertThat(BackupRestoreOutcome.ABANDONED.isTerminal()).isTrue();
        assertThat(BackupRestoreOutcome.UNKNOWN.isTerminal()).isFalse();
        assertThat(BackupRestoreOutcome.IN_PROGRESS.isTerminal()).isFalse();
    }

    // --- parsing the oracle ---

    /// The TSV is the oracle, so it is parsed strictly: an unknown state, path or condition fails the test rather than
    /// silently matching nothing.
    private static List<Row> rows() {
        var states = states();
        var rows = new ArrayList<Row>();

        for (var line : tsvLines()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var cells = line.split("\t");
            assertThat(cells).as("five columns: %s", line).hasSize(5);
            var row = new Row(Integer.parseInt(cells[0]), cells[1], cells[2], cells[3], cells[4]);

            assertThat(row.number()).as("rows numbered in order").isEqualTo(rows.size() + 1);
            assertThat(PATHS).as("known path in %s", line).contains(row.path());
            assertThat(CONDITIONS).as("known condition in %s", line).contains(row.condition());
            if (row.path().equals(PUT)) {
                assertThat(states).as("a Put row names concrete states: %s", line).contains(row.from(), row.to());
            } else {
                assertThat(row.from()).as("a refusing path holds over any state: %s", line).isEqualTo(ANY);
                assertThat(row.condition()).isEqualTo(NONE);
                assertThat(row.to()).isIn(ANY, ABSENT);
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> tsvLines() {
        try (InputStream in = RestoreAuthorityRowsTest.class.getResourceAsStream(TSV)) {
            assertThat(in).as("%s on the test classpath", TSV).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Option<Row> putRow(List<Row> rows, String from, String to) {
        return Option.from(rows.stream()
                               .filter(row -> row.path().equals(PUT) && row.from().equals(from) && row.to().equals(to))
                               .findFirst());
    }

    // --- states and their values ---

    private static List<String> states() {
        var states = new ArrayList<String>();

        states.add(ABSENT);
        for (var outcome : BackupRestoreOutcome.values()) {
            if (outcome == BackupRestoreOutcome.IN_PROGRESS) {
                states.addAll(List.of("(c)", "(c,r)", "(c,a)", "(c,r,a)"));
            } else {
                states.add(outcome.name());
            }
        }
        return states;
    }

    /// The committed value of a state: `IN_PROGRESS` and RESTORED carry the restore's header, a rejection names `K`.
    private static Option<BackupRestoreValue> committed(String state) {
        return switch (state) {
            case ABSENT -> Option.none();
            case "(c)" -> Option.some(inProgress(Option.none(), false));
            case "(c,r)" -> Option.some(inProgress(Option.some(K), false));
            case "(c,a)" -> Option.some(inProgress(Option.none(), true));
            case "(c,r,a)" -> Option.some(inProgress(Option.some(K), true));
            case "RESTORED" -> Option.some(inProgress(Option.none(), false).restored());
            case "ABANDONED" -> Option.some(BackupRestoreValue.abandoned(3L));
            default -> Option.some(BackupRestoreValue.decided(BackupRestoreOutcome.valueOf(state)));
        };
    }

    /// The value written to reach a state: the committed value of that state, except that a rejection names `K'`, so row 9
    /// (re-refusal at a different key) is satisfied and the pair is judged by the table alone.
    private static BackupRestoreValue target(String state) {
        return switch (state) {
            case "(c,r)" -> inProgress(Option.some(K_PRIME), false);
            case "(c,r,a)" -> inProgress(Option.some(K_PRIME), true);
            default -> committed(state).unwrap();
        };
    }

    /// The target of `row` with one condition clause broken: another header, or the committed rejection repeated.
    private static BackupRestoreValue violating(Row row, String clause) {
        var to = target(row.to());

        return clause.equals(HEADER_EQUAL)
               ? BackupRestoreValue.backupRestoreValue(to.outcome(), "other-lineage", to.incarnation(), to.revision(), to.commit(),
                                                       to.rejection(), to.abandon(), to.removedKeys())
               : BackupRestoreValue.backupRestoreValue(to.outcome(), to.lineageId(), to.incarnation(), to.revision(), to.commit(),
                                                       committed(row.from()).unwrap().rejection(), to.abandon(), to.removedKeys());
    }

    private static BackupRestoreValue inProgress(Option<String> rejection, boolean abandon) {
        return BackupRestoreValue.backupRestoreValue(BackupRestoreOutcome.IN_PROGRESS, LINEAGE, 3L, 40L, COMMIT, rejection, abandon, 0L);
    }

    // --- running one case ---

    private boolean putAdmitted(String from, BackupRestoreValue target) {
        var store = storeAt(from);
        var accepted = transaction(store, new KVCommand.Mutation<>(KA, committedValue(from), Option.<AetherValue> some(target)));

        assertThat(store.get(KA)).as("the cell holds the target iff admitted (%s -> %s)", from, target)
                                 .isEqualTo(accepted ? Option.some(target) : committedValue(from));
        return accepted;
    }

    /// A refusing row's path over every committed state: the cell is unchanged, and a transaction reports refused.
    private List<String> refusedOnEveryPair(Row row, List<String> states) {
        var mismatches = new ArrayList<String>();

        for (var from : states) {
            for (var to : row.to().equals(ABSENT) ? List.of(ABSENT) : states.subList(1, states.size())) {
                var store = storeAt(from);
                var refused = refusedThrough(row.path(), store, from, to);

                if (!refused || !store.get(KA).equals(committedValue(from))) {
                    mismatches.add("row " + row.number() + " (" + row.path() + ") " + from + " -> " + to + " changed the cell");
                }
            }
        }
        return mismatches;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean refusedThrough(String path, KVStore<AetherKey, AetherValue> store, String from, String to) {
        return switch (path) {
            case BARE_PUT -> {
                apply(store, new KVCommand.Put<>(KA, target(to)));
                yield true;
            }
            case NON_AUTHORITY -> !transaction(store, new KVCommand.Mutation<>(KA,
                                                                              committedValue(from),
                                                                              Option.<AetherValue> some(ConfigValue.configValue("not",
                                                                                                                                "authority"))));
            case DELETE_ARM -> !transaction(store, new KVCommand.Mutation<>(KA, committedValue(from), Option.<AetherValue> none()));
            case BARE_REMOVE -> {
                apply(store, new KVCommand.Remove<>(KA));
                apply(store, new KVCommand.Remove<>(KA, (Option) committedValue(from)));
                yield true;
            }
            default -> throw new AssertionError("no refusing path " + path);
        };
    }

    private static Option<AetherValue> committedValue(String state) {
        return committed(state).map(AetherValue.class::cast);
    }

    /// A store holding the leader and, unless `state` is absent, that state's value at K_A, installed by snapshot.
    private KVStore<AetherKey, AetherValue> storeAt(String state) {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
        var content = new HashMap<Object, Object>(Map.of(LeaderKey.INSTANCE, LEADER));

        committed(state).onPresent(value -> content.put(KA, value));
        assertThat(store.restoreSnapshot(NODE_CODEC.canonical()
                                                   .encode(content))
                        .isSuccess()).isTrue();
        assertThat(store.get(KA)).as("installed %s", state).isEqualTo(committedValue(state));
        return store;
    }

    private boolean transaction(KVStore<AetherKey, AetherValue> store, KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(KA, "rows:" + ++slot, LEADER, List.of(), List.of(mutation));
        var result = (KVCommand.TransactionResult) store.process(store.createBatch(List.of(command)))
                                                         .getFirst();

        return result.accepted();
    }

    private void apply(KVStore<AetherKey, AetherValue> store, KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }
}
