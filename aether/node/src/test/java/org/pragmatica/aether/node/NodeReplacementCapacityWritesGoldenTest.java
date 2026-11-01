// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-3 (q4 7.1.7 F1): the capacity writes of `NodeReplacementWiring`, characterised. `admissionMutations` (the reservation and the fleet count
/// an EXTERNAL replacement commits with its record) and `settleReservation` (what the commit that ends the replacement does to that reservation) are
/// pure, so every combination of their inputs is rendered, mutation by mutation (key, expected value, replacement), and pinned in
/// `golden/node-replacement-capacity-writes.txt`. The table was generated at the base, BEFORE these writes moved onto the `CapacityRecords` port; the port
/// must leave it identical. Regenerate on purpose with `-Dgolden.regenerate=true` (writes `target/golden/node-replacement-capacity-writes.txt`).
class NodeReplacementCapacityWritesGoldenTest {
    private static final String GOLDEN = "/golden/node-replacement-capacity-writes.txt";
    private static final NodeId ORIGINAL = new NodeId("orig-1");
    private static final NodeId REPLACEMENT = new NodeId("repl-1");
    private static final String UNCOUNTED = "external-uncounted";

    @Test
    void capacityWrites_matchTheGoldenTable() throws IOException {
        var table = table();

        if (Boolean.getBoolean("golden.regenerate")) {
            var out = Path.of("target", "golden", "node-replacement-capacity-writes.txt");

            Files.createDirectories(out.getParent());
            Files.writeString(out, String.join("\n", table) + "\n", StandardCharsets.UTF_8);

            return;
        }

        try (InputStream in = NodeReplacementCapacityWritesGoldenTest.class.getResourceAsStream(GOLDEN)) {
            assertThat(in).as("the pinned table " + GOLDEN).isNotNull();
            assertThat(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))).isEqualTo(table);
        }
    }

    @Test
    void table_coversEveryInputCombination() {
        var table = table();

        assertThat(table).hasSizeGreaterThan(300);
        assertThat(table).anyMatch(row -> row.startsWith("admit|external=true|") && row.contains("ledger=none"));
        assertThat(table).anyMatch(row -> row.startsWith("settle|") && row.contains("-> R:"));
        assertThat(table).anyMatch(row -> row.startsWith("settle|") && row.contains("-> del"));
        assertThat(table).anyMatch(row -> row.startsWith("settle|") && row.contains("-> none"));
    }

    private static List<String> table() {
        var rows = new ArrayList<String>();

        for (var external : List.of(false, true)) {
            for (var role : List.of("core", "Worker")) {
                for (var ledger : ledgers()) {
                    var mutations = NodeReplacementWiring.admissionMutations(external, role, REPLACEMENT, "east", ledger);

                    rows.add("admit|external=" + external + "|role=" + role + "|ledger=" + ledgerText(ledger) + " -> " + render(mutations));
                }
            }
        }

        for (var phase : NodeReplacementPhase.values()) {
            for (var mode : List.of(NodeReplacementValue.MODE_EXTERNAL, NodeReplacementValue.MODE_CTM)) {
                for (var reservation : reservations()) {
                    var next = new NodeReplacementValue(REPLACEMENT, "core", phase, 100L, "east", "1.0.0", mode, 0, "", 0L);
                    var mutations = NodeReplacementWiring.settleReservation(next, reservation);

                    rows.add("settle|phase=" + phase + "|mode=" + mode + "|reservation=" + reservationText(reservation) + " -> " + render(mutations));
                }
            }
        }

        return List.copyOf(rows);
    }

    private static List<Option<CapacityLedgerValue>> ledgers() {
        return List.of(Option.none(),
                       Option.some(new CapacityLedgerValue(3, 5L, false)),
                       Option.some(new CapacityLedgerValue(3, 5L, true)),
                       Option.some(new CapacityLedgerValue(0, 1L, true)));
    }

    private static List<Option<CapacityReservationValue>> reservations() {
        var result = new ArrayList<Option<CapacityReservationValue>>();

        result.add(Option.none());
        for (var phase : CapacityReservationPhase.values()) {
            for (var binding : List.of("", "bind", UNCOUNTED)) {
                result.add(Option.some(new CapacityReservationValue("east", binding, "core", phase)));
            }
        }

        return List.copyOf(result);
    }

    private static String ledgerText(Option<CapacityLedgerValue> ledger) {
        return ledger.map(value -> value.allocated() + "/" + value.version() + "/" + value.inventoryComplete()).or("none");
    }

    private static String reservationText(Option<CapacityReservationValue> reservation) {
        return reservation.map(value -> value.phase() + "/" + value.sourceBinding() + "/" + value.sourceName() + "/" + value.intendedRole()).or("none");
    }

    private static String render(List<org.pragmatica.cluster.state.kvstore.KVCommand.Mutation<org.pragmatica.aether.slice.kvstore.AetherKey, AetherValue>> mutations) {
        if (mutations.isEmpty()) {
            return "none";
        }

        var parts = new ArrayList<String>();

        for (var mutation : mutations) {
            var replacement = mutation.replacement().map(AetherValue::toString).or("del");

            parts.add(mutation.key().asString() + " E:" + mutation.expected().map(AetherValue::toString).or("absent") + " -> " + (replacement.equals("del") ? "del" : "R:" + replacement));
        }

        return String.join(" ; ", parts);
    }
}
