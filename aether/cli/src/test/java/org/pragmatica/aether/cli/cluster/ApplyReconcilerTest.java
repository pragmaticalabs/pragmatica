// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// The resume lookup of INTENDED rows and the apply-label audit (cluster-apply spec 4.3 step 3 and 4.4).
class ApplyReconcilerTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("reconciler-test").unwrap();

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final Consumer<Duration> originalPause = ApplyReconciler.pause;
    private final List<Duration> pauses = new ArrayList<>();
    private final ApplyLedger.TempWriter realWriter = ApplyLedger.tempWriter;

    @AfterEach
    void restore() {
        ApplyReconciler.pause = originalPause;
        ApplyLedger.tempWriter = realWriter;
    }

    private ApplyScenario scenario() {
        ApplyReconciler.pause = pauses::add;

        return new ApplyScenario(ledger, CLUSTER);
    }

    @Test
    void intentWithVmOnProvider_isAdopted_asCreated_withoutAnyCreateCall() {
        var scenario = scenario();
        var server = scenario.createWithLostReply("n-1");

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(scenario.row("n-1").serverId().or("")).isEqualTo(server);
        assertThat(scenario.harness.provider.totalCreates()).as("the lookup creates nothing").isEqualTo(1);
        assertThat(scenario.provider.filteredListings).isEqualTo(1);
        assertThat(pauses).isEmpty();
        scenario.harness.assertNoLeak(scenario.recorder.applyId(), scenario.onDisk());
    }

    @Test
    void lookupFails_refuses_andLeavesTheRowIntended() {
        var scenario = scenario();

        scenario.createWithLostReply("n-1");
        scenario.provider.onFilteredList = _ -> Causes.cause("HTTP 503 (test cause)").promise();

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes())
                       .onSuccess(_ -> fail("an inconclusive lookup must refuse"))
                       .onFailure(cause -> assertThat(cause.message()).contains("Could not look up node n-1").contains("Nothing was created"));

        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.INTENDED);
        assertThat(scenario.harness.provider.totalCreates()).isEqualTo(1);
    }

    @Test
    void twelveEmptyListings_markTheRowAbsent_spacedByTheListingInterval() {
        var scenario = scenario();

        scenario.recorder.intend("n-1", "hetzner").unwrap();

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.provider.filteredListings).isEqualTo(ApplyReconciler.ABSENT_LISTINGS_TO_CONFIRM).isEqualTo(12);
        assertThat(pauses).hasSize(11).containsOnly(ApplyReconciler.LISTING_INTERVAL);
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.ABSENT);
    }

    @Test
    void aVmThatLandsOnALaterListing_isStillFound_insideTheWindow() {
        var scenario = scenario();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.harness.provider.landLate("n-1", 5);
        scenario.harness.provider.create("n-1", scenario.labels());

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).onFailure(cause -> fail(cause.message()));

        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
        assertThat(scenario.provider.filteredListings).isEqualTo(5);
    }

    /// No adapter in the repo configures an HTTP request timeout (the clients use library defaults), so the claim "the
    /// window outlasts each adapter's request timeout" cannot be pinned adapter by adapter; what can be pinned is the
    /// CTM's own relation: 12 listings, spaced a sixth of the provisioning timeout, span about twice that timeout.
    @Test
    void absentWindow_spansAboutTwiceTheProvisioningTimeout_theCtmPrecedent() {
        var window = ApplyReconciler.LISTING_INTERVAL.multipliedBy(ApplyReconciler.ABSENT_LISTINGS_TO_CONFIRM - 1);
        var provisioningTimeout = Duration.ofNanos(AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT.nanos());

        assertThat(ApplyReconciler.LISTING_INTERVAL).isEqualTo(provisioningTimeout.dividedBy(6));
        assertThat(window).isGreaterThan(provisioningTimeout.multipliedBy(3).dividedBy(2)).isLessThan(provisioningTimeout.multipliedBy(2));
    }

    @Test
    void dockerRows_areLookedUpByTheApplyLabel_andMatchedByNodeIdentity() {
        var scenario = scenario();
        var seen = new ArrayList<Map<String, String>>();

        scenario.recorder.intend("n-1", "docker").unwrap();
        scenario.harness.provider.create("n-1", scenario.labels());
        scenario.provider.onFilteredList = filter -> {
            seen.add(filter);

            return scenario.harness.provider.listInstances(filter);
        };

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).onFailure(cause -> fail(cause.message()));

        assertThat(seen).containsExactly(Map.of(WaveNodeProvisioning.APPLY_LABEL, scenario.recorder.applyId()));
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
    }

    /// Node identity is compared exactly: a VM of node `n-10` is not node `n-1`'s.
    @Test
    void lookup_aNodeWhoseIdExtendsTheWantedOne_isNotTheWantedNode() {
        var scenario = scenario();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.harness.provider.create("n-10", scenario.labels());

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).unwrap();

        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.ABSENT);
        assertThat(scenario.harness.provider.nodeIdsByServer().values()).containsExactly("n-10");
    }

    @Test
    void cloudRows_areLookedUpByTheNodeIdLabel() {
        var scenario = scenario();
        var seen = new ArrayList<Map<String, String>>();

        scenario.createWithLostReply("n-1");
        scenario.provider.onFilteredList = filter -> {
            seen.add(filter);

            return scenario.harness.provider.listInstances(filter);
        };

        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).unwrap();

        assertThat(seen).containsExactly(Map.of(WaveNodeProvisioning.NODE_LABEL, "n-1"));
    }

    // ---------------------------------------------------------------- the audit

    @Test
    void lateLandingVmAfterAbsent_isAdoptedAtPreCommitAudit_andBlocksCommitUntilThen() {
        var scenario = scenario();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        ApplyReconciler.reconcileIntents(scenario.recorder, scenario.computes()).unwrap();
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.ABSENT);

        var server = scenario.harness.provider.create("n-1", scenario.labels()).id().value();

        // the adoption cannot be recorded: the audit fails, so nothing may commit
        ApplyLedger.tempWriter = (temp, content) -> {
            throw new java.io.IOException("No space left on device (test cause)");
        };
        ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).onSuccess(_ -> fail("an unrecorded VM must block the commit"));
        ApplyLedger.tempWriter = realWriter;

        // recorded: named, ADOPTED, and the ledger now matches the provider
        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.adopted()).singleElement().asString().contains("n-1").contains(server);
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.ADOPTED);
        scenario.harness.assertNoLeak(scenario.recorder.applyId(), scenario.onDisk());
    }

    @Test
    void auditMarksALedgerVmTheProviderNoLongerHas_asGone() {
        var scenario = scenario();
        var server = scenario.createRecorded("n-1");

        scenario.harness.provider.terminate(new org.pragmatica.aether.environment.InstanceId(server)).await();

        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.gone()).containsExactly("n-1");
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.GONE);
    }

    @Test
    void auditDoesNotCallAVmGone_whenItStillExistsUnderOtherLabels() {
        var scenario = scenario();
        var foreign = scenario.harness.provider.create("somebody-elses", Map.of()).id().value();

        scenario.recorder.intend("n-1", "hetzner").unwrap();
        scenario.recorder.created("n-1", foreign).unwrap();

        var report = ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap();

        assertThat(report.gone()).isEmpty();
        assertThat(report.relabelled()).singleElement().asString().contains("n-1");
        assertThat(scenario.row("n-1").state()).isEqualTo(ApplyResource.State.CREATED);
    }

    @Test
    void anAuditThatCouldNotListRefuses_itIsNotACleanAudit() {
        var scenario = scenario();

        scenario.createRecorded("n-1");
        scenario.provider.onFilteredList = _ -> Causes.cause("HTTP 503 (test cause)").promise();

        ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes())
                       .onSuccess(_ -> fail("must refuse"))
                       .onFailure(cause -> assertThat(cause.message()).contains("audit did not run"));
    }

    @Test
    void aCleanLedgerAuditsClean() {
        var scenario = scenario();

        scenario.createRecorded("n-1");
        scenario.createRecorded("n-2");

        assertThat(ApplyReconciler.audit(scenario.recorder, CLUSTER, scenario.computes()).unwrap().clean()).isTrue();
    }
}
