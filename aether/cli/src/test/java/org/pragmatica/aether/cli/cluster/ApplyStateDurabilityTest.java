// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// F4: the ledger write is atomic. `ApplyLedger.tempWriter` is handed the TEMP path; a writer that writes half the
/// bytes and dies there stands for a full disk or a kill mid-write. The previous ledger must survive it.
class ApplyStateDurabilityTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("durability-test").unwrap();

    @TempDir
    Path stateRoot;

    private Function<ClusterName, Path> originalResolver;
    private ApplyLedger.TempWriter originalWriter;

    @BeforeEach
    void redirect() {
        originalResolver = ApplyState.stateDirResolver;
        originalWriter = ApplyLedger.tempWriter;
        ApplyState.stateDirResolver = name -> stateRoot.resolve(name.value());
    }

    @AfterEach
    void restore() {
        ApplyState.stateDirResolver = originalResolver;
        ApplyLedger.tempWriter = originalWriter;
    }

    private Path ledgerFile() {
        return stateRoot.resolve(CLUSTER.value()).resolve("apply-state.json");
    }

    /// Writes the first half of the content to the path it is handed, then dies.
    private static Unit halfWriteThenDie(Path target, byte[] content) throws Exception {
        Files.write(target, java.util.Arrays.copyOf(content, content.length / 2), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        throw new java.io.IOException("No space left on device (test cause)");
    }

    @Test
    void save_failureMidWrite_leavesThePreviousFileReadable() throws Exception {
        var first = ApplyStateFixtures.state(CLUSTER);

        ApplyState.save(first).onFailure(cause -> fail(cause.message()));

        var before = Files.readString(ledgerFile());

        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;

        ApplyState.save(first.withPhase(ApplyPhase.COMMITTING))
                  .onSuccess(_ -> fail("a write that died mid-way must be reported as a failure"));

        assertThat(Files.readString(ledgerFile())).as("the previous ledger is byte-identical").isEqualTo(before);
        ApplyState.read(CLUSTER)
                  .onFailure(cause -> fail("the previous ledger must stay readable: " + cause.message()))
                  .onSuccess(read -> assertThat(read.unwrap()).isEqualTo(first));
    }

    @Test
    void save_afterAFailedWrite_replacesTheLeftoverTempFileCompletely() {
        var big = ApplyStateFixtures.state(CLUSTER).withNode(ApplyResource.applyResource("k-" + "x".repeat(2000), "n-1", "fake", Option.none(), ApplyResource.State.INTENDED));

        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;
        ApplyState.save(big).onSuccess(_ -> fail("must fail"));
        ApplyLedger.tempWriter = originalWriter;

        var small = ApplyStateFixtures.state(CLUSTER);

        ApplyState.save(small).onFailure(cause -> fail(cause.message()));

        ApplyState.read(CLUSTER)
                  .onFailure(cause -> fail("a leftover tail of the failed write must not survive: " + cause.message()))
                  .onSuccess(read -> assertThat(read.unwrap()).isEqualTo(small));
    }

    @Test
    void save_failureOnTheVeryFirstWrite_leavesNoReadableLedgerAndNoLedgerFile() {
        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onSuccess(_ -> fail("must fail"));

        assertThat(ledgerFile()).as("a torn first write is never promoted to the ledger name").doesNotExist();
        ApplyState.read(CLUSTER).onFailure(cause -> fail("absent, not unreadable: " + cause.message()));
    }

    @Test
    void save_success_replacesTheFileAndLeavesNoTempFile() throws Exception {
        var first = ApplyStateFixtures.state(CLUSTER);
        var second = first.withPhase(ApplyPhase.COMMITTING);

        ApplyState.save(first).onFailure(cause -> fail(cause.message()));
        ApplyState.save(second).onFailure(cause -> fail(cause.message()));

        assertThat(ApplyState.read(CLUSTER).unwrap().unwrap()).isEqualTo(second);
        assertThat(Files.list(ledgerFile().getParent()).map(p -> p.getFileName().toString()).toList())
                .containsExactly("apply-state.json");
        assertThat(new String(Files.readAllBytes(ledgerFile()), StandardCharsets.UTF_8)).isEqualTo(ApplyState.toJson(second));
    }

    @Test
    void save_underAPathThatCannotBeCreated_isAFailureNotAnException() throws Exception {
        var blocker = Files.writeString(stateRoot.resolve("blocker"), "i am a file");

        ApplyState.stateDirResolver = name -> blocker.resolve(name.value());

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onSuccess(_ -> fail("must fail"));
    }
}
