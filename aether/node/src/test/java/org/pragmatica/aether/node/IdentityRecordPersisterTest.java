// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeIdentityKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.IdentityState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeIdentityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.OperationId;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisionIntent;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.IdentityFacts;
import org.pragmatica.consensus.net.IdentityLedger;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// ApplyPersistsToLedgerTest (ID-1b, membership overhaul spec 2.1): every core's apply of a TERMINATED identity record writes the
/// retirement to ITS OWN identity ledger (F1-F3) before the apply callback returns, so the fact is on disk before the core acts on it.
///
/// What is NOT written, each pinned: a record that is not TERMINATED; a TERMINATED record with no process (an abandoned intent: "no
/// ledger fact for intents", q4 7.1); and anything on a node that cannot vouch (WIPED, or not yet loaded), because writing the file would
/// turn a WIPED node into a CREATED-looking one at its next restart (a missing file IS the WIPED signal).
class IdentityRecordPersisterTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeIdentityKey KEY = NodeIdentityKey.nodeIdentityKey(NODE);
    private static final ProcessIncarnation PROCESS = new ProcessIncarnation(1_760_000_000_123L, 0x5EEDFACE0BADL);
    private static final OperationId OPERATION = OperationId.operationId("heal-7").unwrap();
    private static final ProvisionIntent INTENT = new ProvisionIntent(OPERATION, "cpx31", "fsn1", "dc-1", Option.none(), 100L);

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("identity").resolve("ledger");
    }

    private static NodeIdentityValue value(IdentityState state, Option<ProcessIncarnation> process, Option<IdentityCause> cause) {
        return new NodeIdentityValue(state, 1L, process, "core", Option.none(), cause, 10L, Option.some(INTENT));
    }

    /// A loaded, non-wiped ledger: the state of a core that booted new, or with a good file.
    private IdentityRecordPersister loadedPersister(IdentityLedger.DurableWriter writer) {
        var tokens = BootTokens.bootTokens(0L);
        var ledger = IdentityLedger.identityLedger(file, writer);
        var loading = ledger.open(true);

        tokens.attachLedger(ledger);
        tokens.preload(loading.facts(), false);

        return IdentityRecordPersister.identityRecordPersister(ledger, tokens);
    }

    private IdentityRecordPersister wipedPersister() {
        var tokens = BootTokens.bootTokens(0L);
        var ledger = IdentityLedger.identityLedger(file, IdentityLedger.DurableWriter.fileOps());
        var loading = ledger.open(false);

        tokens.attachLedger(ledger);
        tokens.preload(loading.facts(), loading.origin() == IdentityLedger.Origin.WIPED);

        return IdentityRecordPersister.identityRecordPersister(ledger, tokens);
    }

    private IdentityFacts onDisk() throws IOException {
        return IdentityFacts.parse(Files.readString(file)).unwrap();
    }

    @Test
    void onPut_writesTheRetirementToTheLedger_beforeReturning() throws IOException {
        var persister = loadedPersister(IdentityLedger.DurableWriter.fileOps());

        persister.onPut(KEY, value(IdentityState.TERMINATED, Option.some(PROCESS), Option.some(IdentityCause.GONE)));

        assertThat(onDisk().terminated()).containsEntry(NODE, "GONE");
    }

    @Test
    void onPut_writesNothing_forARecordThatIsNotTerminated() throws IOException {
        var persister = loadedPersister(IdentityLedger.DurableWriter.fileOps());

        for (var state : new IdentityState[] {IdentityState.INTENDED, IdentityState.PROVISIONING, IdentityState.ADMITTED, IdentityState.DEPARTED}) {
            persister.onPut(KEY, value(state, Option.some(PROCESS), Option.none()));
        }

        assertThat(onDisk().isEmpty()).as("the ledger holds retirements only; Bound facts are not kept (ID-1a)").isTrue();
    }

    @Test
    void onPut_writesNothing_forAnAbandonedIntent_whichHadNoProcess() throws IOException {
        var persister = loadedPersister(IdentityLedger.DurableWriter.fileOps());

        persister.onPut(KEY, value(IdentityState.TERMINATED, Option.none(), Option.some(IdentityCause.VANISHED)));

        assertThat(onDisk().isEmpty()).as("no ledger fact for intents").isTrue();
    }

    @Test
    void onPut_neverCreatesTheLedgerFile_onAWipedNode() {
        var persister = wipedPersister();

        persister.onPut(KEY, value(IdentityState.TERMINATED, Option.some(PROCESS), Option.some(IdentityCause.GONE)));

        assertThat(Files.exists(file)).as("a missing file is the WIPED signal; writing it would erase the signal at the next restart").isFalse();
    }

    @Test
    void onPut_writesNothing_beforeTheLedgerIsLoaded() {
        var tokens = BootTokens.bootTokens(0L);
        var ledger = IdentityLedger.identityLedger(file, IdentityLedger.DurableWriter.fileOps());

        tokens.attachLedger(ledger);
        IdentityRecordPersister.identityRecordPersister(ledger, tokens)
                               .onPut(KEY, value(IdentityState.TERMINATED, Option.some(PROCESS), Option.some(IdentityCause.GONE)));

        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void onPut_returnsNormally_whenTheLedgerCannotBeWritten() {
        var failing = new IdentityLedger.DurableWriter() {
            @Override
            public Result<Unit> writeForced(Path target, byte[] content) {
                return org.pragmatica.lang.utils.Causes.cause("disk full").result();
            }

            @Override
            public Result<Unit> moveAtomic(Path source, Path target) {
                return Result.unitResult();
            }

            @Override
            public Result<Unit> forceDirectory(Path target) {
                return Result.unitResult();
            }
        };
        var persister = loadedPersister(failing);

        persister.onPut(KEY, value(IdentityState.TERMINATED, Option.some(PROCESS), Option.some(IdentityCause.GONE)));

        assertThat(persister.failures()).as("the failure is counted for the operator, the apply is not broken").isEqualTo(1L);
    }

    @Test
    void onPut_isIdempotent_forTheSameRetirement() throws IOException {
        var persister = loadedPersister(IdentityLedger.DurableWriter.fileOps());
        var terminated = value(IdentityState.TERMINATED, Option.some(PROCESS), Option.some(IdentityCause.RETIRED));

        persister.onPut(KEY, terminated);
        persister.onPut(KEY, terminated);

        assertThat(onDisk().terminated()).hasSize(1).containsEntry(NODE, "RETIRED");
    }
}
