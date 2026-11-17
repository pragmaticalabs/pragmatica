// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.FakeSliceStore;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingInvocationHandler;
import org.pragmatica.aether.deployment.wiring.RuntimeFixtures.RecordingRoutePublisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.wiring.WiringFixtures.*;

/// H3b ordering of `SUSPEND`'s late route removal against the next `PUBLISH_ROUTES` (#3174), beyond the single-SUSPEND case pinned in
/// `SliceRuntimeAdapterTest`: several SUSPENDs of one artifact before the PUBLISH, another artifact's removal, and the settled entry's removal.
/// Each test names the merge or lookup variant it kills.
class PendingUnpublishOrderingTest {
    private final FakeSliceStore store = new FakeSliceStore();
    private final RecordingRoutePublisher publisher = new RecordingRoutePublisher();
    private final ConfigNotificationManager configNotifications = ConfigNotificationManager.configNotificationManager();
    private final SliceRuntimeAdapter adapter = SliceRuntimeAdapter.sliceRuntimeAdapter(new SliceRuntimeAdapter.Collaborators(SELF,
                                                                                                                              store,
                                                                                                                              new RecordingInvocationHandler(),
                                                                                                                              SliceCodec.sliceCodec(List.of()),
                                                                                                                              Option.some(publisher),
                                                                                                                              Option.some(RuntimeFixtures.FACADE),
                                                                                                                              configNotifications,
                                                                                                                              WiringFixtures.store(),
                                                                                                                              () -> new StubCluster(),
                                                                                                                              () -> BOOT_INCARNATION,
                                                                                                                              () -> OperatorWarningSink.handingOffTo(_ -> {})));

    @AfterEach
    void tearDown() {
        configNotifications.shutdown();
    }

    private Promise<LocalOutcome> run(Artifact artifact, LocalStep step) {
        return adapter.run(new NodeFsmEffect.LocalOp(1, 7, Option.some(artifact), step, 0, Option.none()));
    }

    private Promise<Unit> suspendWithRemoval(Artifact artifact) {
        var removal = Promise.<Unit> promise();

        publisher.unpublishPending = removal;
        await().atMost(Duration.ofSeconds(1)).until(run(artifact, LocalStep.SUSPEND)::isResolved);

        return removal;
    }

    private void assertHeld(Promise<LocalOutcome> published) {
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !published.isResolved());
    }

    private Promise<LocalOutcome> publishAfterTwoSuspends(Promise<Unit>[] removals) {
        store.withLoaded(ORDERS, new RuntimeFixtures.BareSlice());
        removals[0] = suspendWithRemoval(ORDERS);
        removals[1] = suspendWithRemoval(ORDERS);

        return run(ORDERS, LocalStep.PUBLISH_ROUTES);
    }

    /// Kills the merge variant that keeps `later` only: the earlier removal is still in flight when the later one settles.
    @Test
    @SuppressWarnings("unchecked")
    void twoSuspends_publishWaitsForTheEarlierRemovalWhenTheLaterSettlesFirst() {
        var removals = new Promise[2];
        var published = publishAfterTwoSuspends(removals);

        assertHeld(published);
        removals[1].succeed(Unit.unit());
        assertHeld(published);
        removals[0].succeed(Unit.unit());
        await().atMost(Duration.ofSeconds(5)).until(published::isResolved);
    }

    /// Kills the merge variant that keeps `earlier` only: the later removal is still in flight when the earlier one settles.
    @Test
    @SuppressWarnings("unchecked")
    void twoSuspends_publishWaitsForTheLaterRemovalWhenTheEarlierSettlesFirst() {
        var removals = new Promise[2];
        var published = publishAfterTwoSuspends(removals);

        removals[0].succeed(Unit.unit());
        assertHeld(published);
        removals[1].succeed(Unit.unit());
        await().atMost(Duration.ofSeconds(5)).until(published::isResolved);
    }

    /// H3b round-c item: the earlier removal FAILS while the later is in flight; PUBLISH must still wait for the later. Holds because the
    /// removal is recovered to success in `severing`: the probe is to drop that `recover`, which makes the merged promise fail at once.
    @Test
    @SuppressWarnings("unchecked")
    void twoSuspends_publishStillWaitsForTheLaterRemovalWhenTheEarlierFails() {
        var removals = new Promise[2];
        var published = publishAfterTwoSuspends(removals);

        removals[0].fail(Causes.cause("consensus write failed"));
        assertHeld(published);
        removals[1].succeed(Unit.unit());
        await().atMost(Duration.ofSeconds(5)).until(published::isResolved);
        assertThat(published.await().unwrap()).isInstanceOf(LocalOutcome.Done.class);
    }

    /// Kills the lookup that awaits any artifact's removal: A's removal never settles, B's PUBLISH must go through.
    @Test
    void suspendOfOneArtifact_doesNotHoldAnotherArtifactsPublish() {
        store.withLoaded(BILLING, new RuntimeFixtures.BareSlice());
        suspendWithRemoval(ORDERS);

        var published = run(BILLING, LocalStep.PUBLISH_ROUTES);

        await().atMost(Duration.ofSeconds(2)).until(published::isResolved);
        assertThat(published.await().unwrap()).isInstanceOf(LocalOutcome.Done.class);
        assertThat(publisher.calls).containsExactly("unpublish " + ORDERS, "publish " + BILLING);
    }

    /// Kills "the entry is never cleared": a settled entry is behaviourally invisible (an already-settled gate resolves at once), so the
    /// only observable is the map itself. It is read reflectively, a test-side read of the private field; adding a production accessor
    /// for it would put a test-only surface into the runtime.
    @Test
    void settledRemoval_isDroppedFromThePendingMap() throws Exception {
        var removal = suspendWithRemoval(ORDERS);

        assertThat(pendingEntries()).as("unsettled removal is tracked").hasSize(1);
        removal.succeed(Unit.unit());
        await().atMost(Duration.ofSeconds(2)).until(() -> pendingEntries().isEmpty());
    }

    private Map<?, ?> pendingEntries() throws ReflectiveOperationException {
        Field field = SliceRuntimeAdapter.class.getDeclaredField("pendingUnpublish");

        field.setAccessible(true);

        return (Map<?, ?>) field.get(adapter);
    }
}
