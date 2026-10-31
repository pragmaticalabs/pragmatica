// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityAdmissionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderAuthorized;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-0 characterisation: the golden transition table of [CapacityControlledLifecycle], generated from the code as it stands.
///
/// One row per step of a scenario: the operation, the starting phase of the reservation of node `x`, whether this instance
/// leads, and what the operation did - its result, the resulting phase, the ledger delta, the keys it deleted, the keys it
/// wrote, the operator warnings it raised and the provider calls it made (a create also records the reservation and the
/// counter as the provider saw them, which pins "committed before dispatched"). The table is pinned in
/// `golden/capacity-lifecycle-transitions.txt`. The storage moves in O3-2..O3-4 (an identity record per node) must leave it
/// identical, apart from the FREED tombstone rows O3-4 introduces on release.
///
/// Regenerate on purpose with `-Dgolden.regenerate=true` (writes `target/golden/capacity-lifecycle-transitions.txt`; copy it
/// over the pinned file after reading the diff). Scope of "warnings": the operator warnings that reach the sink (the log
/// lines are not pinned). The scenarios are single-node on purpose so that the provider call order is deterministic.
@org.junit.jupiter.api.Timeout(300)
class CapacityLifecycleGoldenTest {
    private static final String GOLDEN = "/golden/capacity-lifecycle-transitions.txt";
    private static final NodeId CORE = new NodeId("core");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final NodeId X = new NodeId("x");
    private static final NodeId Y = new NodeId("y");
    private static final long CEILING_MS = 1_000L;
    private static final long T0 = 1_000_000L;
    private static final Pattern DISPATCH_TIME = Pattern.compile("\\((dispatched at [^)]*|dispatch time unknown \\(earlier leader\\))\\)");
    private static final String EAST_TOML = """
        config_version = "1.0.0"
        [cluster]
        name = "test"
        version = "1.0.0"
        [source.east]
        type = "forge"
        [source.east.core]
        count = 3
        """;

    enum Start {
        ABSENT, DISPATCHED, OBSERVED, RELEASED, UNKNOWN, EXT_EMPTY, EXT_UNCOUNTED, OBSERVED_OTHER_BINDING, OBSERVED_NOROLE
    }

    enum Answer { EMPTY, FOUND, FAIL }

    enum Create { SUCCESS, UNAVAILABLE, CAP_EXCEEDED, AMBIGUOUS }

    private static final List<Start> PHASES = List.of(Start.ABSENT, Start.DISPATCHED, Start.OBSERVED, Start.RELEASED, Start.UNKNOWN);
    private static final List<Start> ALL = List.of(Start.ABSENT, Start.DISPATCHED, Start.OBSERVED, Start.RELEASED, Start.UNKNOWN,
                                                    Start.EXT_EMPTY, Start.EXT_UNCOUNTED, Start.OBSERVED_OTHER_BINDING);
    private static final List<Boolean> LEADING = List.of(true, false);

    private final List<String> rows = new ArrayList<>();
    private final Set<String> steps = new TreeSet<>();

    @Test
    void goldenTransitionTable_isUnchanged() throws Exception {
        build();
        var actual = render();

        if (Boolean.getBoolean("golden.regenerate")) {
            var out = Path.of("target", "golden", "capacity-lifecycle-transitions.txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, actual, StandardCharsets.UTF_8);
            return;
        }
        try (var in = CapacityLifecycleGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as("the pinned golden table " + GOLDEN).isNotNull();
            var expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(actual).isEqualTo(expected);
        }
    }

    /// The instrument must reach every public operation: a public method added to the lifecycle without a row fails here, so the
    /// table cannot silently stop covering the surface it pins.
    @Test
    void everyPublicOperation_hasRows() {
        build();
        var exempt = Set.of("capacityControlledLifecycle", "isExternalBinding", "setOperatorWarningSink");
        var components = new TreeSet<String>();

        for (var component : CapacityControlledLifecycle.class.getRecordComponents()) {
            components.add(component.getName());
        }
        var missing = new TreeSet<String>();

        for (var method : CapacityControlledLifecycle.class.getDeclaredMethods()) {
            var name = method.getName();

            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || components.contains(name)
                || exempt.contains(name) || Set.of("equals", "hashCode", "toString").contains(name)) {
                continue;
            }
            var covered = steps.stream().anyMatch(step -> step.startsWith(name + "("));
            if (!covered) {
                missing.add(name);
            }
        }
        assertThat(missing).as("public operations with no row").isEmpty();
    }

    @Test
    void table_hasEveryPhaseOfTheStartAndBothLeadershipStates() {
        build();
        for (var start : Start.values()) {
            for (var lead : List.of("leader=Y", "leader=N")) {
                assertThat(rows.stream().anyMatch(row -> row.contains("start=" + start + " ") && row.contains(lead)))
                    .as("rows for %s %s", start, lead).isTrue();
            }
        }
        assertThat(rows.size()).as("row count").isGreaterThan(300);
    }

    private String render() {
        return "# CapacityControlledLifecycle golden transition table (O3-0); node x unless stated; R=reservation, L=ledger, A=admission marker, P=node placement\n"
               + "# id | leader | start | step | result | x (phase before->after) | ledger delta | deleted | written | warnings | provider calls\n"
               + String.join("\n", rows) + "\n";
    }

    // ------------------------------------------------------------------------------------------------------------ the scenarios

    private void build() {
        if (!rows.isEmpty()) {
            return;
        }
        provisioning();
        lookups();
        operatorClear();
        refusals();
        inventoryPasses();
        termination();
        readmission();
        markers();
        externalRelease();
        readOnly();
        listings();
        delegations();
        var ids = new TreeSet<String>();
        for (var row : rows) {
            assertThat(ids.add(row.substring(0, row.indexOf(" | ")))).as("duplicate row id in %s", row).isTrue();
        }
    }

    private void provisioning() {
        for (var lead : LEADING) {
            for (var create : Create.values()) {
                for (var start : PHASES) {
                    run("provision/" + create + "/" + start, lead, r -> {
                        r.create = create;
                        r.start(start);
                        r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
                    });
                }
            }
            for (var create : List.of(Create.SUCCESS, Create.UNAVAILABLE)) {
                run("provision-binding/" + create, lead, r -> {
                    r.create = create;
                    r.step("provisionNode(spec,binding)", () -> r.lc.provisionNode(r.spec(X, "worker"), "binding"));
                });
            }
            run("provision/role-core-uppercase", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "CORE")));
            });
            run("provision/fleet-full", lead, r -> {
                r.limit = 1;
                r.seedReservation(Y, "east", "binding", "worker", CapacityReservationPhase.OBSERVED);
                r.seedLedger(1, true);
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/ledger-absent-inventory-initialises", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.removeLedger();
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/ledger-absent-preexisting-adopted", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.removeLedger();
                r.listed = List.of("pre");
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/ledger-incomplete-inventory-completes", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.seedLedger(0, false);
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/no-config", lead, r -> {
                r.noConfig();
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/listing-fails", lead, r -> {
                r.removeLedger();
                r.listFails = true;
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            for (var from : List.of(1, 2, 3, 4)) {
                for (var create : List.of(Create.SUCCESS, Create.UNAVAILABLE)) {
                    run("provision/commit-rejected-from-" + from + "/" + create, lead, r -> {
                        r.create = create;
                        r.rejectFrom = from;
                        r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
                    });
                }
            }
            run("provision/refusal-with-active-placement", lead, r -> {
                r.create = Create.UNAVAILABLE;
                r.seedPlacementOperation(X, AetherValue.PlacementOperationPhase.RESERVED);
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
                r.seedPlacementOperation(X, AetherValue.PlacementOperationPhase.COMPLETE);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
            run("provision/refusal-with-placement-key", lead, r -> {
                r.create = Create.UNAVAILABLE;
                r.seedPlacementKey(X);
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
            });
            run("provision/leadership-lost-after-reserve", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.hookAt = 2;
                r.afterCommit = () -> r.leading.set(false);
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
                r.leading.set(true);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
        }
    }

    private void lookups() {
        for (var lead : LEADING) {
            for (var start : ALL) {
                for (var answer : Answer.values()) {
                    run("instancesForNode/" + answer + "/" + start, lead, r -> {
                        r.answer = answer;
                        r.start(start);
                        if (start == Start.OBSERVED) {
                            r.seedPlacementKey(X);
                        }
                        r.step("instancesForNode(node,source)", () -> r.lc.instancesForNode(X, r.east()));
                    });
                }
            }
            for (var start : List.of(Start.ABSENT, Start.DISPATCHED, Start.OBSERVED)) {
                for (var answer : List.of(Answer.EMPTY, Answer.FOUND)) {
                    run("instancesForNode-binding/" + answer + "/" + start, lead, r -> {
                        r.answer = answer;
                        r.start(start);
                        r.step("instancesForNode(node,source,binding)", () -> r.lc.instancesForNode(X, r.east(), "binding"));
                    });
                    run("instancesForNode-node/" + answer + "/" + start, lead, r -> {
                        r.answer = answer;
                        r.start(start);
                        r.step("instancesForNode(node)", () -> r.lc.instancesForNode(X));
                    });
                    run("readInstancesForNode/" + answer + "/" + start, lead, r -> {
                        r.answer = answer;
                        r.start(start);
                        r.step("readInstancesForNode(node,source)", () -> r.lc.readInstancesForNode(X, r.east()));
                    });
                }
            }
            run("instancesForNode/empty-ledger-zero", lead, r -> {
                r.start(Start.OBSERVED);
                r.seedLedger(0, true);
                r.step("instancesForNode(node,source)", () -> r.lc.instancesForNode(X, r.east()));
            });
            run("instancesForNode/empty-ledger-absent", lead, r -> {
                r.start(Start.OBSERVED);
                r.removeLedger();
                r.step("instancesForNode(node,source)", () -> r.lc.instancesForNode(X, r.east()));
            });
            run("instancesForNode/empty-commit-rejected", lead, r -> {
                r.start(Start.OBSERVED);
                r.rejectFrom = 1;
                r.step("instancesForNode(node,source)", () -> r.lc.instancesForNode(X, r.east()));
            });
            run("instancesForNode/found-commit-rejected", lead, r -> {
                r.answer = Answer.FOUND;
                r.start(Start.DISPATCHED);
                r.rejectFrom = 1;
                r.step("instancesForNode(node,source)", () -> r.lc.instancesForNode(X, r.east()));
            });
        }
    }

    private void operatorClear() {
        for (var lead : LEADING) {
            for (var start : ALL) {
                run("releaseAfterOperatorClear/" + start, lead, r -> {
                    r.start(start);
                    r.step("releaseAfterOperatorClear(node,source)", () -> r.lc.releaseAfterOperatorClear(X, r.east()));
                });
            }
            run("releaseAfterOperatorClear/OBSERVED+placement-key", lead, r -> {
                r.start(Start.OBSERVED);
                r.seedPlacementKey(X);
                r.step("releaseAfterOperatorClear(node,source)", () -> r.lc.releaseAfterOperatorClear(X, r.east()));
            });
            run("releaseAfterOperatorClear/OBSERVED+ledger-zero", lead, r -> {
                r.start(Start.OBSERVED);
                r.seedLedger(0, true);
                r.step("releaseAfterOperatorClear(node,source)", () -> r.lc.releaseAfterOperatorClear(X, r.east()));
            });
        }
    }

    private void refusals() {
        for (var lead : LEADING) {
            for (var start : PHASES) {
                run("reconcileRefusals/" + start, lead, r -> {
                    r.start(start);
                    r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
                });
            }
            run("reconcileRefusals/RELEASED+placement-key", lead, r -> {
                r.start(Start.RELEASED);
                r.seedPlacementKey(X);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
            run("reconcileRefusals/RELEASED+active-placement", lead, r -> {
                r.start(Start.RELEASED);
                r.seedPlacementOperation(X, AetherValue.PlacementOperationPhase.CREATE_REQUESTED);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
            run("reconcileRefusals/RELEASED+complete-placement", lead, r -> {
                r.start(Start.RELEASED);
                r.seedPlacementOperation(X, AetherValue.PlacementOperationPhase.COMPLETE);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
            run("reconcileRefusals/RELEASED+ledger-zero", lead, r -> {
                r.start(Start.RELEASED);
                r.seedLedger(0, true);
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
            run("reconcileRefusals/RELEASED+commit-rejected", lead, r -> {
                r.start(Start.RELEASED);
                r.rejectFrom = 1;
                r.step("reconcileRefusals()", () -> r.lc.reconcileRefusals());
            });
        }
    }

    private void inventoryPasses() {
        for (var lead : LEADING) {
            run("reconcileInventory/OBSERVED-listed", lead, r -> {
                r.start(Start.OBSERVED);
                r.listed = List.of("x");
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            for (var answer : Answer.values()) {
                run("reconcileInventory/OBSERVED-missing/" + answer, lead, r -> {
                    r.start(Start.OBSERVED);
                    r.answer = answer;
                    r.seedPlacementKey(X);
                    r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                });
            }
            run("reconcileInventory/ABSENT-listed", lead, r -> {
                r.listed = List.of("x");
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/RELEASED-listed", lead, r -> {
                r.start(Start.RELEASED);
                r.listed = List.of("x");
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/UNKNOWN-listed", lead, r -> {
                r.start(Start.UNKNOWN);
                r.listed = List.of("x");
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/UNKNOWN-missing", lead, r -> {
                r.start(Start.UNKNOWN);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/listing-fails", lead, r -> {
                r.start(Start.OBSERVED);
                r.listFails = true;
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/no-config", lead, r -> {
                r.noConfig();
                r.start(Start.OBSERVED);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/duplicate-across-sources", lead, r -> {
                r.seedReservation(X, "west", "binding", "worker", CapacityReservationPhase.OBSERVED);
                r.seedLedger(1, true);
                r.listed = List.of("x");
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/DISPATCHED-earlier-term-never-seen", lead, r -> {
                r.start(Start.DISPATCHED);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.clock.addAndGet(CEILING_MS);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/DISPATCHED-seen-late", lead, r -> {
                r.start(Start.DISPATCHED);
                r.answer = Answer.FOUND;
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/DISPATCHED-lookup-fails", lead, r -> {
                r.start(Start.DISPATCHED);
                r.answer = Answer.FAIL;
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
            run("reconcileInventory/DISPATCHED-own-create-ages-out", lead, r -> {
                r.create = Create.AMBIGUOUS;
                r.step("provisionNode(spec)", () -> r.lc.provisionNode(r.spec(X, "worker")));
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.clock.addAndGet(CEILING_MS);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
                r.clock.addAndGet(CEILING_MS);
                r.step("reconcileInventory()", () -> r.lc.reconcileInventory());
            });
        }
    }

    private void termination() {
        for (var lead : LEADING) {
            for (var start : ALL) {
                for (var answer : List.of(Answer.EMPTY, Answer.FOUND)) {
                    run("terminateNode-node/" + answer + "/" + start, lead, r -> {
                        r.answer = answer;
                        r.start(start);
                        r.step("terminateNode(node)", () -> r.lc.terminateNode(X));
                    });
                }
            }
            for (var start : List.of(Start.ABSENT, Start.DISPATCHED, Start.OBSERVED, Start.EXT_EMPTY)) {
                run("terminateNode-source/" + start, lead, r -> {
                    r.start(start);
                    r.step("terminateNode(node,source)", () -> r.lc.terminateNode(X, r.east()));
                });
            }
            for (var start : List.of(Start.ABSENT, Start.OBSERVED, Start.OBSERVED_OTHER_BINDING)) {
                run("terminateNode-binding/" + start, lead, r -> {
                    r.start(start);
                    r.step("terminateNode(node,source,binding)", () -> r.lc.terminateNode(X, r.east(), "binding"));
                });
            }
            run("terminateNode-node/ledger-incomplete", lead, r -> {
                r.start(Start.OBSERVED);
                r.seedLedger(1, false);
                r.listed = List.of("x");
                r.step("terminateNode(node)", () -> r.lc.terminateNode(X));
            });
            run("executeAction/StopNode/OBSERVED", lead, r -> {
                r.start(Start.OBSERVED);
                r.step("executeAction(StopNode)", () -> r.lc.executeAction(new NodeAction.StopNode(X)));
            });
            run("executeAction/StartNode/ABSENT", lead, r -> {
                r.create = Create.SUCCESS;
                r.step("executeAction(StartNode)", () -> r.lc.executeAction(new NodeAction.StartNode(r.spec(X, "worker"))));
            });
        }
    }

    private void readmission() {
        for (var lead : LEADING) {
            for (var start : List.of(Start.EXT_EMPTY, Start.EXT_UNCOUNTED, Start.OBSERVED)) {
                run("readmitExternal/" + start, lead, r -> {
                    r.start(start);
                    r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
                });
                run("readmitExternal/" + start + "+marker", lead, r -> {
                    r.start(start);
                    r.seedMarker(X, 2, Option.none(), Option.none());
                    r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
                });
            }
            run("readmitExternal/EXT_EMPTY+commit-rejected", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.rejectFrom = 1;
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-no-marker", lead, r -> r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X)));
            run("readmitExternal/ABSENT-superseded", lead, r -> {
                r.seedMarker(X, 1, Option.none(), Option.some("y"));
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-marker-without-memory", lead, r -> {
                r.seedMarker(X, 1, Option.none(), Option.none());
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-released-counted", lead, r -> {
                r.seedMarker(X, 1, Option.some(r.reservation("east", "", "core", CapacityReservationPhase.OBSERVED)), Option.none());
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-released-uncounted", lead, r -> {
                r.seedMarker(X, 1, Option.some(r.reservation("east", CapacityControlledLifecycle.UNCOUNTED_BINDING, "core", CapacityReservationPhase.OBSERVED)), Option.none());
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-released-counted-ledger-incomplete", lead, r -> {
                r.seedLedger(0, false);
                r.seedMarker(X, 1, Option.some(r.reservation("east", "", "core", CapacityReservationPhase.OBSERVED)), Option.none());
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-released-counted-ledger-absent", lead, r -> {
                r.removeLedger();
                r.seedMarker(X, 1, Option.some(r.reservation("east", "", "core", CapacityReservationPhase.OBSERVED)), Option.none());
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/ABSENT-released-counted+commit-rejected", lead, r -> {
                r.seedMarker(X, 1, Option.some(r.reservation("east", "", "core", CapacityReservationPhase.OBSERVED)), Option.none());
                r.rejectFrom = 1;
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/release-then-rejoin", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/release-then-rejoin-uncounted", lead, r -> {
                r.start(Start.EXT_UNCOUNTED);
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/reservation-released-between-read-and-commit", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.beforeCommit = () -> r.concurrentRelease(X, false);
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/reservation-released-between-read-and-commit+marker-remembers", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.beforeCommit = () -> r.concurrentRelease(X, true);
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
            run("readmitExternal/uncounted-reservation-released-between-read-and-commit+marker-remembers", lead, r -> {
                r.start(Start.EXT_UNCOUNTED);
                r.beforeCommit = () -> r.concurrentRelease(X, true);
                r.step("readmitExternal(node)", () -> r.lc.readmitExternal(X));
            });
        }
    }

    private void markers() {
        for (var lead : LEADING) {
            for (var marker : List.of("none", "present", "tombstone")) {
                run("supersedeAdmission/" + marker, lead, r -> {
                    r.seedMarkerVariant(marker);
                    r.step("supersedeAdmission(node,replacement)", () -> r.lc.supersedeAdmission(X, Y));
                });
                run("forgetAdmission/" + marker, lead, r -> {
                    r.seedMarkerVariant(marker);
                    r.step("forgetAdmission(node)", () -> r.lc.forgetAdmission(X));
                });
            }
            run("supersedeAdmission/present+commit-rejected", lead, r -> {
                r.seedMarkerVariant("present");
                r.rejectFrom = 1;
                r.step("supersedeAdmission(node,replacement)", () -> r.lc.supersedeAdmission(X, Y));
            });
            run("forgetAdmission/present+commit-rejected", lead, r -> {
                r.seedMarkerVariant("present");
                r.rejectFrom = 1;
                r.step("forgetAdmission(node)", () -> r.lc.forgetAdmission(X));
            });
        }
    }

    private void externalRelease() {
        for (var lead : LEADING) {
            for (var start : List.of(Start.ABSENT, Start.EXT_EMPTY, Start.EXT_UNCOUNTED, Start.OBSERVED)) {
                run("releaseExternal/" + start, lead, r -> {
                    r.start(start);
                    r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
                });
            }
            run("releaseExternal/EXT_EMPTY+marker", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.seedMarker(X, 2, Option.none(), Option.none());
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
            run("releaseExternal/EXT_EMPTY+ledger-absent", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.removeLedger();
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
            run("releaseExternal/EXT_EMPTY+ledger-zero", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.seedLedger(0, true);
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
            run("releaseExternal/EXT_UNCOUNTED+ledger-absent", lead, r -> {
                r.start(Start.EXT_UNCOUNTED);
                r.removeLedger();
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
            run("releaseExternal/EXT_EMPTY+commit-rejected", lead, r -> {
                r.start(Start.EXT_EMPTY);
                r.rejectFrom = 1;
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
            run("releaseExternal/EXT_UNCOUNTED+commit-rejected", lead, r -> {
                r.start(Start.EXT_UNCOUNTED);
                r.rejectFrom = 1;
                r.step("releaseExternal(node)", () -> r.lc.releaseExternal(X));
            });
        }
    }

    private void readOnly() {
        var starts = new ArrayList<>(ALL);
        starts.add(Start.OBSERVED_NOROLE);

        for (var lead : LEADING) {
            for (var start : starts) {
                run("externalNode/" + start, lead, r -> {
                    r.start(start);
                    r.step("externalNode(node)", () -> r.lc.externalNode(X));
                });
                run("sourceOf/" + start, lead, r -> {
                    r.start(start);
                    r.step("sourceOf(node)", () -> r.lc.sourceOf(X));
                });
            }
        }
        for (var start : starts) {
            run("allocatedNodes/" + start, true, r -> {
                r.start(start);
                r.step("allocatedNodes(worker)", () -> r.lc.allocatedNodes("worker"));
                r.step("allocatedNodes(core)", () -> r.lc.allocatedNodes("core"));
                r.step("allocatedNodes(WORKER)", () -> r.lc.allocatedNodes("WORKER"));
                r.step("allocatedNodes()", () -> r.lc.allocatedNodes(""));
            });
        }
    }

    private void listings() {
        for (var lead : LEADING) {
            run("listInstances/filter-without-source", lead, r -> {
                r.listed = List.of("x");
                r.step("listInstances(filter)", () -> r.lc.listInstances(Map.of("aether-cluster", "test")));
            });
            for (var start : List.of(Start.ABSENT, Start.DISPATCHED, Start.OBSERVED, Start.RELEASED)) {
                run("listInstances-source/" + start, lead, r -> {
                    r.start(start);
                    r.listed = List.of("x");
                    r.step("listInstances(filter)", () -> r.lc.listInstances(Map.of("aether-source", "east")));
                });
                run("listInstances-binding/" + start, lead, r -> {
                    r.start(start);
                    r.listed = List.of("x");
                    r.step("listInstances(filter,source,binding)", () -> r.lc.listInstances(Map.of(), r.east(), "binding"));
                });
            }
            run("listInstances-source/dotted-key", lead, r -> {
                r.listed = List.of("x");
                r.step("listInstances(filter)", () -> r.lc.listInstances(Map.of("aether.source", "east")));
            });
            run("listInstances-source/listing-fails", lead, r -> {
                r.listFails = true;
                r.step("listInstances(filter)", () -> r.lc.listInstances(Map.of("aether-source", "east")));
            });
        }
    }

    private void delegations() {
        run("delegations", true, r -> {
            r.step("instanceStatus(id,source)", () -> r.lc.instanceStatus(new InstanceId("i-1"), r.east()));
            r.step("sourceBinding(source)", () -> r.lc.sourceBinding(r.east()));
            r.step("isCloudManaged()", () -> r.lc.isCloudManaged());
            r.step("resetProvisionerState(name)", () -> {
                r.lc.resetProvisionerState(Option.none());
                return Unit.unit();
            });
            r.step("executeAction(MigrateSlices)", () -> r.lc.executeAction(new NodeAction.MigrateSlices(X, Option.none())));
        });
    }

    private void run(String id, boolean lead, Consumer<Rig> body) {
        var rig = new Rig(id + (lead ? "@leader" : "@follower"), lead);

        body.accept(rig);
        rows.addAll(rig.rows);
        steps.addAll(rig.stepLabels);
    }

    // ------------------------------------------------------------------------------------------------------------ the rig

    /// One fresh store, lifecycle and provider per scenario.
    private static final class Rig {
        final String id;
        final boolean lead;
        final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) { return null; }
        });
        final AtomicBoolean leading = new AtomicBoolean();
        final AtomicLong clock = new AtomicLong(T0);
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final List<String> rows = new ArrayList<>();
        final List<String> stepLabels = new ArrayList<>();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger stepNo = new AtomicInteger();
        Create create = Create.AMBIGUOUS;
        Answer answer = Answer.EMPTY;
        List<String> listed = List.of();
        boolean listFails;
        int limit = 10;
        int rejectFrom = Integer.MAX_VALUE;
        /// Run before / after the commit numbered `hookAt` (the inventory initialisation of a fresh rig is commit 1 where it applies).
        int hookAt = 1;
        Runnable beforeCommit = () -> {};
        Runnable afterCommit = () -> {};
        Start startPhase = Start.ABSENT;
        final CapacityControlledLifecycle lc;
        final OperatorWarningSink sink;

        Rig(String id, boolean lead) {
            this.id = id;
            this.lead = lead;
            leading.set(lead);
            seed(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
            seed(new KVCommand.Put<>(AetherKey.ClusterConfigKey.CURRENT,
                                     new AetherValue.ClusterConfigValue(Option.some(EAST_TOML), "test", "1.0.0", List.of(), 3, 3, "forge", 1, 0)));
            seedLedger(0, true);
            lc = CapacityControlledLifecycle.capacityControlledLifecycle(provider(), CORE, store, this::apply, leading::get, () -> limit,
                                                                         TimeSpan.timeSpan(CEILING_MS).millis(), clock::get);
            sink = OperatorWarningSink.handingOffTo(warning -> warnings.add(describe(warning)));
            lc.setOperatorWarningSink(sink);
        }

        private static String describe(OperatorWarning warning) {
            var time = DISPATCH_TIME.matcher(warning.message());

            return warning.code().code() + "@" + warning.subject() + (time.find() ? "[" + time.group(1) + "]" : "");
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void seed(KVCommand command) {
            store.process(store.createBatch(List.of(command)));
        }

        /// A seed that the store refuses (a version fence, a leader check) would leave the rig in a state other than the row says, and the
        /// row would still print: every seed is read back.
        void put(AetherKey key, AetherValue value) {
            if (value instanceof LeaderAuthorized) {
                seed(new KVCommand.LeaderTransaction<>(key, UUID.randomUUID().toString(), LEADER, List.of(),
                                                       List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, store.get(key), Option.some(value)))));
            } else {
                seed(new KVCommand.Put<>(key, value));
            }
            if (!store.get(key).equals(Option.some(value))) {
                throw new AssertionError("seed of " + key + " was not applied: " + store.get(key));
            }
        }

        void remove(AetherKey key) {
            seed(new KVCommand.LeaderTransaction<>(key, UUID.randomUUID().toString(), LEADER, List.of(),
                                                   List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key, store.get(key), Option.none()))));
            if (store.get(key).isPresent()) {
                throw new AssertionError("removal of " + key + " was not applied");
            }
        }

        CapacityReservationValue reservation(String source, String binding, String role, CapacityReservationPhase phase) {
            return new CapacityReservationValue(source, binding, role, phase);
        }

        void seedReservation(NodeId node, String source, String binding, String role, CapacityReservationPhase phase) {
            put(new AetherKey.CapacityReservationKey(node), reservation(source, binding, role, phase));
        }

        void deleteReservation(NodeId node) {
            remove(new AetherKey.CapacityReservationKey(node));
        }

        /// What another leader's release of an EXTERNAL reservation leaves: the reservation deleted, its slot returned, and (as the real release
        /// does) the marker remembering what it deleted.
        void concurrentRelease(NodeId node, boolean remember) {
            var existing = store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class).unwrap();
            var counted = !CapacityControlledLifecycle.UNCOUNTED_BINDING.equals(existing.sourceBinding());

            deleteReservation(node);
            if (counted) {
                var ledger = store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).unwrap();

                seedLedger(ledger.allocated() - 1, ledger.inventoryComplete());
            }
            if (remember) {
                seedMarker(node, 0, Option.some(existing), Option.none());
            }
        }

        void seedLedger(int allocated, boolean complete) {
            var version = store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class).map(value -> value.version() + 1).or(1L);

            put(AetherKey.CapacityLedgerKey.INSTANCE, new CapacityLedgerValue(allocated, version, complete));
        }

        void removeLedger() {
            remove(AetherKey.CapacityLedgerKey.INSTANCE);
        }

        void noConfig() {
            remove(AetherKey.ClusterConfigKey.CURRENT);
        }

        void seedPlacementKey(NodeId node) {
            put(new AetherKey.NodePlacementKey(node), new AetherValue.NodePlacementValue("east", Option.none(), "instance"));
        }

        void seedPlacementOperation(NodeId node, AetherValue.PlacementOperationPhase phase) {
            var key = new AetherKey.CommunityPlacementOperationKey("c1");

            put(key, new AetherValue.CommunityPlacementOperationValue("op-1", "c1", node, "east", Option.none(), "binding", Option.none(), "",
                                                                       phase, LEADER, T0, T0, ""));
        }

        void seedMarker(NodeId node, long admissions, Option<CapacityReservationValue> released, Option<String> supersededBy) {
            put(new AetherKey.CapacityAdmissionKey(node), new CapacityAdmissionValue(admissions, released, supersededBy));
        }

        void seedMarkerVariant(String variant) {
            switch (variant) {
                case "present" -> seedMarker(X, 3, Option.some(reservation("east", "", "core", CapacityReservationPhase.OBSERVED)), Option.none());
                case "tombstone" -> seedMarker(X, 3, Option.none(), Option.some("y"));
                default -> {}
            }
        }

        /// The phase fixes the reservation of `x` and a counter that counts it as the code does (a reservation is a slot until it is
        /// released, whatever its phase; an uncounted EXTERNAL one holds none).
        void start(Start start) {
            startPhase = start;
            switch (start) {
                case ABSENT -> seedLedger(0, true);
                case DISPATCHED -> counted(reservation("east", "binding", "worker", CapacityReservationPhase.DISPATCHED));
                case OBSERVED -> counted(reservation("east", "binding", "worker", CapacityReservationPhase.OBSERVED));
                case RELEASED -> counted(reservation("east", "binding", "worker", CapacityReservationPhase.RELEASED));
                case UNKNOWN -> counted(reservation("east", "binding", "worker", CapacityReservationPhase.UNKNOWN));
                case EXT_EMPTY -> counted(reservation("east", "", "core", CapacityReservationPhase.OBSERVED));
                case EXT_UNCOUNTED -> {
                    put(new AetherKey.CapacityReservationKey(X), reservation("east", CapacityControlledLifecycle.UNCOUNTED_BINDING, "core", CapacityReservationPhase.OBSERVED));
                    seedLedger(0, true);
                }
                case OBSERVED_OTHER_BINDING -> counted(reservation("east", "other-binding", "worker", CapacityReservationPhase.OBSERVED));
                case OBSERVED_NOROLE -> counted(reservation("east", "binding", "", CapacityReservationPhase.OBSERVED));
            }
        }

        private void counted(CapacityReservationValue value) {
            put(new AetherKey.CapacityReservationKey(X), value);
            seedLedger(1, true);
        }

        SourceName east() {
            return SourceName.sourceName("east").unwrap();
        }

        ProvisionSpec spec(NodeId node, String role) {
            return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "size", role,
                                               ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(), role, east(), node.id())).unwrap();
        }

        Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            var number = commits.incrementAndGet();

            if (number == hookAt) {
                beforeCommit.run();
            }
            if (number >= rejectFrom) {
                return Promise.success(List.of());
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            List<Object> results = (List<Object>) store.process(store.createBatch((List) commands));

            if (number == hookAt) {
                afterCommit.run();
            }
            return Promise.success(results);
        }

        static InstanceInfo instance(String node) {
            return new InstanceInfo(new InstanceId("instance-" + node), InstanceStatus.RUNNING, List.of(), InstanceType.ON_DEMAND,
                                    Map.of("aether-role", "core"), Option.some(node), Option.none());
        }

        private NodeLifecycleManager provider() {
            return new NodeLifecycleManager() {
                @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
                    var node = spec.context().nodeId().or("?");
                    var seen = store.getTyped(new AetherKey.CapacityReservationKey(new NodeId(node)), CapacityReservationValue.class)
                                    .map(value -> value.phase().name()).or("none");
                    var counter = store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class)
                                       .map(value -> String.valueOf(value.allocated())).or("none");

                    calls.add("create(" + node + ")[R=" + seen + ",L=" + counter + "]");
                    return switch (create) {
                        case SUCCESS -> Promise.success(instance(node));
                        case UNAVAILABLE -> new EnvironmentError.CapacityUnavailable("zone-1", new RuntimeException("no capacity")).promise();
                        case CAP_EXCEEDED -> new EnvironmentError.NodeCapExceeded(ClusterName.clusterName("test").unwrap(), 3, 3).promise();
                        case AMBIGUOUS -> Causes.cause("Provider timed out after accepting request").promise();
                    };
                }
                @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec, String binding) { return provisionNode(spec); }
                @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source, String binding) { return instancesForNode(node, source); }
                @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node, SourceName source) {
                    calls.add("lookup(" + node.id() + ")");
                    return lookup(node);
                }
                @Override public Promise<List<InstanceInfo>> instancesForNode(NodeId node) {
                    calls.add("lookup(" + node.id() + ")");
                    return lookup(node);
                }
                @Override public Promise<Unit> terminateNode(NodeId node, SourceName source, String binding) { return terminateNode(node); }
                @Override public Promise<Unit> terminateNode(NodeId node) {
                    calls.add("terminate(" + node.id() + ")");
                    return Promise.unitPromise();
                }
                @Override public Promise<ActionResult> executeAction(NodeAction action) { return Causes.cause("unused").promise(); }
                @Override public Result<String> sourceBinding(SourceName source) { return Result.success("binding"); }
                @Override public boolean isCloudManaged() { return true; }
                @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId, SourceName source) {
                    calls.add("status(" + instanceId.value() + ")");
                    return Promise.success(instance("status"));
                }
                @Override public void resetProvisionerState(Option<ClusterName> name) { calls.add("reset"); }
                @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
                    calls.add("list()");
                    return listing();
                }
                @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> filter, SourceName source, String binding) {
                    calls.add("list(" + source.value() + ")");
                    return listing();
                }
            };
        }

        private Promise<List<InstanceInfo>> lookup(NodeId node) {
            return switch (answer) {
                case EMPTY -> Promise.success(List.of());
                case FOUND -> Promise.success(List.of(instance(node.id())));
                case FAIL -> Causes.<List<InstanceInfo>>cause("transient inventory failure").promise();
            };
        }

        private Promise<List<InstanceInfo>> listing() {
            return listFails
                   ? Causes.<List<InstanceInfo>>cause("credentials rejected").promise()
                   : Promise.success(listed.stream().map(Rig::instance).toList());
        }

        // ---- the row

        void step(String label, Supplier<Object> operation) {
            var n = stepNo.incrementAndGet();
            var before = view();
            var callsFrom = calls.size();
            var warningsFrom = warnings.size();
            var outcome = outcome(operation.get());
            var flushed = flush(warningsFrom);
            var after = view();

            stepLabels.add(label);
            rows.add(String.join(" | ",
                                 id + "#" + n,
                                 "leader=" + (lead ? "Y" : "N"),
                                 "start=" + startPhase + " ",
                                 label,
                                 "result=" + outcome,
                                 "x=" + before.getOrDefault("R:x", "absent") + "->" + after.getOrDefault("R:x", "absent"),
                                 "ledger=" + ledgerDelta(before.get("L"), after.get("L")),
                                 "del=" + deleted(before, after),
                                 "wrote=" + written(before, after),
                                 "warn=" + flushed,
                                 "calls=" + calls.subList(callsFrom, calls.size())));
        }

        private String flush(int warningsFrom) {
            sink.accept(new OperatorWarning(OperatorWarningCode.CAPACITY_DISPATCH_RELEASED, "flush-marker", ""));
            var deadline = System.nanoTime() + 10_000_000_000L;

            while (warnings.stream().noneMatch(w -> w.contains("@flush-marker"))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("operator warning hand-off did not drain");
                }
                Thread.onSpinWait();
            }
            var mine = new ArrayList<String>();

            for (var warning : warnings.subList(warningsFrom, warnings.size())) {
                if (!warning.contains("@flush-marker")) {
                    mine.add(warning);
                }
            }
            warnings.removeIf(w -> w.contains("@flush-marker"));
            return mine.toString();
        }

        private static String outcome(Object value) {
            if (value instanceof Promise<?> promise) {
                return outcome(promise.await());
            }
            if (value instanceof Result<?> result) {
                return result.fold(cause -> "fail(" + cause.message() + ")", ok -> "ok" + describe(ok));
            }
            return "value" + describe(value);
        }

        private static String describe(Object value) {
            return switch (value) {
                case Unit _ -> "";
                case InstanceInfo info -> "(" + info.nodeId().or("?") + ")";
                case ActionResult.NodeStarted started -> "(NodeStarted:" + started.instance().nodeId().or("?") + ")";
                case ActionResult.NodeStopped stopped -> "(NodeStopped:" + stopped.nodeId().id() + ")";
                case List<?> list -> list.stream().map(Rig::describe).toList().toString();
                case Map<?, ?> map -> new TreeMap<>(map.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue())))).toString();
                case Option<?> option -> option.fold(() -> "(none)", inner -> "(some " + describe(inner) + ")");
                case NodeId node -> node.id();
                case SourceName source -> source.value();
                case Boolean flag -> "(" + flag + ")";
                default -> "(" + value + ")";
            };
        }

        /// Every key of the four families, rendered with its value. The counter is under `L`; node `x` under `R:x`.
        private Map<String, String> view() {
            var result = new TreeMap<String, String>();
            Map<?, ?> snapshot = store.snapshot();

            snapshot.forEach((key, value) -> {
                switch (key) {
                    case AetherKey.CapacityLedgerKey _ -> result.put("L", ledgerString((CapacityLedgerValue) value));
                    case AetherKey.CapacityReservationKey k -> result.put("R:" + k.nodeId().id(), reservationString((CapacityReservationValue) value));
                    case AetherKey.CapacityAdmissionKey k -> result.put("A:" + k.nodeId().id(), admissionString((CapacityAdmissionValue) value));
                    case AetherKey.NodePlacementKey k -> result.put("P:" + k.nodeId().id(), "placed");
                    default -> {}
                }
            });
            return result;
        }

        private static String ledgerString(CapacityLedgerValue value) {
            return value.allocated() + "/" + value.version() + "/" + value.inventoryComplete();
        }

        private static String reservationString(CapacityReservationValue value) {
            return value.phase() + "(" + value.sourceName() + "," + value.sourceBinding() + "," + value.intendedRole() + ")";
        }

        private static String admissionString(CapacityAdmissionValue value) {
            return "n=" + value.admissions() + ",released=" + value.released().map(Rig::reservationString).or("none")
                   + ",superseded=" + value.supersededBy().or("none");
        }

        private static String ledgerDelta(String before, String after) {
            if (before == null || after == null) {
                return before == null && after == null ? "absent" : (before == null ? "created " + after : "deleted");
            }
            var b = before.split("/");
            var a = after.split("/");

            return "allocated" + signed(Integer.parseInt(a[0]) - Integer.parseInt(b[0]))
                   + " version" + signed(Long.parseLong(a[1]) - Long.parseLong(b[1]))
                   + (b[2].equals(a[2]) ? "" : " complete " + b[2] + "->" + a[2]);
        }

        private static String signed(long delta) {
            return delta >= 0 ? "+" + delta : String.valueOf(delta);
        }

        private static String deleted(Map<String, String> before, Map<String, String> after) {
            var result = new ArrayList<String>();

            for (var key : before.keySet()) {
                if (!key.equals("L") && !after.containsKey(key)) {
                    result.add(key);
                }
            }
            return result.toString();
        }

        private static String written(Map<String, String> before, Map<String, String> after) {
            var result = new ArrayList<String>();

            for (var entry : after.entrySet()) {
                if (!entry.getKey().equals("L") && !entry.getValue().equals(before.get(entry.getKey()))) {
                    result.add(entry.getKey() + "=" + entry.getValue());
                }
            }
            return result.toString();
        }
    }
}
