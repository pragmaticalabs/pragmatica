// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.aether.cli.cluster.ApplyChaosHarness.APPLY_LABEL;
import static org.pragmatica.aether.cli.cluster.ApplyResource.State.CREATED;
import static org.pragmatica.aether.cli.cluster.ApplyResource.State.INTENDED;
import static org.pragmatica.aether.cli.cluster.ApplyResource.State.TERMINATED;
import static org.pragmatica.aether.cli.cluster.ApplyResource.State.TERMINATING;
import static org.pragmatica.aether.cli.cluster.ApplyResource.applyResource;

/// The harness is an instrument: these scenarios prove its leak invariant goes RED when it should (a check that
/// cannot fail would report success identically to a real one), and that its fakes behave as the phases rely on.
class ApplyChaosHarnessTest {
    private static final String APPLY_ID = ApplyStateFixtures.APPLY_ID;
    private static final Map<String, String> TAGS = Map.of(APPLY_LABEL, APPLY_ID);
    private static final ClusterName CLUSTER = ClusterName.clusterName("harness-test").unwrap();

    private final ApplyChaosHarness harness = new ApplyChaosHarness();

    private static ApplyState emptyLedger() {
        return ApplyStateFixtures.state(CLUSTER);
    }

    private static ApplyResource row(String nodeId, String serverId, ApplyResource.State state) {
        return applyResource("AddRole|eu|worker|->2", nodeId, "fake", Option.option(serverId), state);
    }

    @Test
    void leakInvariant_holds_whenLedgerRowsMatchInventory() {
        var first = harness.provider.create("eu-worker-1", TAGS).id().value();
        var second = harness.provider.create("eu-worker-2", TAGS).id().value();
        var ledger = emptyLedger().withNode(row("eu-worker-1", first, CREATED))
                                  .withNode(row("eu-worker-2", second, CREATED));

        harness.assertNoLeak(APPLY_ID, ledger);
        harness.assertAtMostOneCreatePerNodeId();
    }

    /// The money probe of P1: a scenario that creates, then loses the ledger, must make the invariant fail.
    @Test
    void leakInvariant_fails_whenTheLedgerIsLostAfterACreate() {
        harness.provider.create("eu-worker-1", TAGS);

        assertThatThrownBy(() -> harness.assertNoLeak(APPLY_ID, emptyLedger())).isInstanceOf(AssertionError.class)
                                                                               .hasMessageContaining("provider holds srv-1");
    }

    @Test
    void leakInvariant_fails_whenARowClaimsAVmTheProviderLacks() {
        var ledger = emptyLedger().withNode(row("eu-worker-1", "srv-9", CREATED));

        assertThatThrownBy(() -> harness.assertNoLeak(APPLY_ID, ledger)).isInstanceOf(AssertionError.class)
                                                                        .hasMessageContaining("ledger holds srv-9");
    }

    @Test
    void leakInvariant_fails_whenAHoldingRowNamesNoServer() {
        harness.provider.create("eu-worker-1", TAGS);

        var ledger = emptyLedger().withNode(row("eu-worker-1", null, CREATED));

        assertThat(harness.leakReport(APPLY_ID, ledger)).anyMatch(line -> line.contains("names no server id"));
    }

    @Test
    void leakInvariant_ignoresVmsUnderAnotherApplyLabel() {
        harness.provider.create("other-1", Map.of(APPLY_LABEL, "zzzzzzzzzzzzzzzzzzzzzzzzzz"));

        harness.assertNoLeak(APPLY_ID, emptyLedger());
    }

    @Test
    void leakInvariant_terminatedRowAndGoneVm_pass_terminatingRowStillHoldsTheVm() {
        var server = harness.provider.create("eu-worker-1", TAGS).id().value();

        assertThat(harness.leakReport(APPLY_ID, emptyLedger().withNode(row("eu-worker-1", server, TERMINATING)))).isEmpty();

        harness.provider.terminate(new org.pragmatica.aether.environment.InstanceId(server)).await();

        assertThat(harness.leakReport(APPLY_ID, emptyLedger().withNode(row("eu-worker-1", server, TERMINATED)))).isEmpty();
    }

    @Test
    void lateLandingCreate_isBilledBeforeItIsListable_andTheInvariantSeesIt() {
        harness.provider.landLate("eu-worker-1", 2);
        harness.provider.create("eu-worker-1", TAGS);

        assertThat(harness.provider.listInstances().await().unwrap()).as("not yet listable").isEmpty();
        assertThat(harness.leakReport(APPLY_ID, emptyLedger().withNode(row("eu-worker-1", null, INTENDED))))
                .as("accepted and billed although not listed: the ledger's INTENDED row does not hold it")
                .hasSize(1);
        assertThat(harness.provider.listInstances().await().unwrap()).as("lands on the second listing").hasSize(1);
    }

    @Test
    void terminate_ofAnUnknownServer_failsAndIsCounted() {
        harness.provider.terminate(new org.pragmatica.aether.environment.InstanceId("srv-404"))
                        .await()
                        .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("terminate of an unknown server must fail"));

        assertThat(harness.provider.terminateCalls()).containsEntry("srv-404", 1);
        assertThat(harness.provider.totalTerminates()).isEqualTo(1);
    }

    @Test
    void atMostOneCreatePerNodeId_fails_onASecondCreateOfTheSameId() {
        harness.provider.create("eu-worker-1", TAGS);
        harness.provider.create("eu-worker-1", TAGS);

        assertThatThrownBy(harness::assertAtMostOneCreatePerNodeId).isInstanceOf(AssertionError.class);
    }

    @Test
    void killSeam_firesOnTheNthReachOfTheArmedStepOnly() {
        harness.armKill(ApplyChaosHarness.Step.S3, 2);

        harness.reach(ApplyChaosHarness.Step.S1);
        harness.reach(ApplyChaosHarness.Step.S3);

        assertThatThrownBy(() -> harness.reach(ApplyChaosHarness.Step.S3)).isInstanceOf(ApplyChaosHarness.Killed.class);

        harness.reach(ApplyChaosHarness.Step.S3);
    }

    @Test
    void clock_advancesOnlyWhenTold() {
        harness.advance(600_000L);

        assertThat(harness.now()).isEqualTo(600_000L);
    }
}
