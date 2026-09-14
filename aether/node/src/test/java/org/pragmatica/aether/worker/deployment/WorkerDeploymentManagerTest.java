// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.deployment;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager.DeploymentState;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager.WorkerSliceDeployment;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// Interleaving probe for #906: a state transition landing between `computeAndApplyAssignment`'s
/// read of the deployments map and its write must survive, not be clobbered by the stale read.
@SuppressWarnings("JBCT-EX-01")
class WorkerDeploymentManagerTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice:1.0.0").unwrap();

    /// A map whose next armed `get` parks the calling thread until released, so the test can act
    /// inside the read/write window of the code under test.
    static final class LatchedMap extends ConcurrentHashMap<Artifact, WorkerSliceDeployment> {
        final AtomicBoolean armed = new AtomicBoolean(false);
        final CountDownLatch readTaken = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public WorkerSliceDeployment get(Object key) {
            var value = super.get(key);

            if (armed.compareAndSet(true, false)) {
                readTaken.countDown();
                await(proceed);
            }

            return value;
        }
    }

    @Test
    void stateTransitionLandingDuringAssignmentRecomputation_isPreserved() throws InterruptedException {
        var deployments = new LatchedMap();
        var loadSlice = Promise.<LoadedSlice> promise();
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(loadSlice);
        when(sliceStore.activateSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      mock(MutationForwarder.class),
                                                                      deployments,
                                                                      List.of(SELF),
                                                                      () -> "default:local");
        // Directive lands: deployment recorded as LOADING, slice load in flight (promise pending).
        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));
        assertThat(deployments.get(ARTIFACT).state()).isEqualTo(DeploymentState.LOADING);
        // Thread A: membership change recomputes the assignment; its read of the map parks.
        deployments.armed.set(true);
        var recompute = new Thread(() -> manager.onMembershipChange(List.of(SELF)),
                                   "recompute");

        recompute.start();
        assertThat(deployments.readTaken.await(5, TimeUnit.SECONDS)).as("read taken").isTrue();
        // Inside the window: the slice load completes, driving LOADING -> LOADED -> ACTIVATING -> ACTIVE
        // through computeIfPresent(withState) on this thread.
        loadSlice.succeed(mock(LoadedSlice.class));
        assertThat(deployments.get(ARTIFACT).state()).as("transition applied before write")
                  .isEqualTo(DeploymentState.ACTIVE);
        // Release thread A: it now writes the assignment derived from its stale read.
        deployments.proceed.countDown();
        recompute.join(5_000);
        assertThat(recompute.isAlive()).as("recompute finished").isFalse();
        assertThat(deployments.get(ARTIFACT).state()).as("ACTIVE transition survives the concurrent write")
                  .isEqualTo(DeploymentState.ACTIVE);
        assertThat(deployments.get(ARTIFACT).assignedInstances()).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(e);
        }
    }
}
