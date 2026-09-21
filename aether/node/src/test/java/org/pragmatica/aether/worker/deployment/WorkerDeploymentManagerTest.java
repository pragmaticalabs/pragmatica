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
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.SliceStore.LoadedSlice;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkerSliceDirectiveValue;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager.DeploymentState;
import org.pragmatica.aether.worker.deployment.WorkerDeploymentManager.WorkerSliceDeployment;
import org.pragmatica.aether.worker.mutation.MutationForwarder;
import org.pragmatica.aether.worker.mutation.WorkerMutation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    /// SF-2 (#1115 review): the same hunk closes resurrection-after-removal. The old `put` re-inserted
    /// a record read before `onDirectiveRemove` tore the slice down; `computeIfPresent` on the removed
    /// key is a no-op.
    @Test
    void removalLandingDuringAssignmentRecomputation_isNotResurrected() throws InterruptedException {
        var deployments = new LatchedMap();
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.activateSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.deactivateSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.unloadSlice(any())).thenReturn(Promise.unitPromise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      mock(MutationForwarder.class),
                                                                      deployments,
                                                                      List.of(SELF),
                                                                      () -> "default:local");
        // Directive lands and the slice deploys to completion: the record is ACTIVE.
        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));
        assertThat(deployments.get(ARTIFACT).state()).isEqualTo(DeploymentState.ACTIVE);
        deployments.armed.set(true);
        var recompute = new Thread(() -> manager.onMembershipChange(List.of(SELF)),
                                   "recompute");

        recompute.start();
        assertThat(deployments.readTaken.await(5, TimeUnit.SECONDS)).as("read taken").isTrue();
        // Inside the window: the directive is withdrawn and the slice torn down — the record is removed.
        manager.onDirectiveRemove(ARTIFACT);
        assertThat(deployments.containsKey(ARTIFACT)).as("removed before the write").isFalse();
        deployments.proceed.countDown();
        recompute.join(5_000);
        assertThat(recompute.isAlive()).as("recompute finished").isFalse();
        assertThat(deployments.containsKey(ARTIFACT)).as("removed record must not be resurrected").isFalse();
    }

    /// #1184 (rev N1): a failed load is forwarded WITH its reason and its classified `fatal` flag.
    /// Before, `handleDeploymentFailure` forwarded `nodeArtifactValue(FAILED)` — no reason, `fatal=false` —
    /// so a permanent shared-loader version conflict read as retryable cluster-wide and the two slices
    /// that disagreed were named only in this worker's log.
    @Test
    void loadFailure_isForwardedWithItsReasonAndFatalFlag() {
        var conflict = new SliceLoadingFailure.Fatal.SharedLoaderVersionConflict("org.example:slice:1.0.0",
                                                                                 "org.example:lib:^2.0.0",
                                                                                 "org.example:lib:1.0.0",
                                                                                 "org.example:other:1.0.0");
        var forwarded = mock(MutationForwarder.class);
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(conflict.promise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      forwarded,
                                                                      new ConcurrentHashMap<>(),
                                                                      List.of(SELF),
                                                                      () -> "default:local");

        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));

        var failed = forwardedFailures(forwarded);

        assertThat(failed).as("exactly one FAILED record forwarded").hasSize(1);
        assertThat(failed.getFirst().fatal()).as("a version conflict is permanent").isTrue();
        assertThat(failed.getFirst().failureReason().unwrap()).contains("slice org.example:slice:1.0.0 requires org.example:lib:^2.0.0"
                                                                       + " but org.example:lib:1.0.0 is already loaded by org.example:other:1.0.0");
    }

    /// Control for the flag: a cause typed Intermittent at its raise site stays retryable on this path.
    @Test
    void intermittentLoadFailure_isForwardedAsRetryable() {
        var notFound = new SliceLoadingFailure.Intermittent.ArtifactNotFound("org.example:slice:1.0.0");
        var forwarded = mock(MutationForwarder.class);
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(notFound.promise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      forwarded,
                                                                      new ConcurrentHashMap<>(),
                                                                      List.of(SELF),
                                                                      () -> "default:local");

        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));

        var failed = forwardedFailures(forwarded);

        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().fatal()).isFalse();
        assertThat(failed.getFirst().failureReason().unwrap()).contains("Artifact not found in any repository: org.example:slice:1.0.0");
    }

    /// The declared disposition is what decides an UNTYPED cause (#930), and it is decided per PHASE
    /// (rev1416 round 3). Load phase: PERMANENT, as on the FSM's load path, because an unrecognised
    /// load failure re-runs the same deterministic work on retry.
    @Test
    void untypedLoadFailure_isForwardedAsPermanent() {
        var forwarded = mock(MutationForwarder.class);
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(Causes.cause("unrecognised").promise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      forwarded,
                                                                      new ConcurrentHashMap<>(),
                                                                      List.of(SELF),
                                                                      () -> "default:local");

        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));

        var failed = forwardedFailures(forwarded);

        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().fatal()).isTrue();
        assertThat(failed.getFirst().failureReason().unwrap()).contains("unrecognised");
    }

    /// Activation phase: RETRY, as on the FSM's `handleActivationFailure` (the site that closes #923).
    /// An unreachable database inside `materializeAll()` or a failing `slice.start()` is untyped and
    /// must not roll a blueprint back; forwarded `fatal=false`, the leader re-drives it under budget.
    @Test
    void untypedActivationFailure_isForwardedAsRetryable() {
        var forwarded = mock(MutationForwarder.class);
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.activateSlice(any())).thenReturn(Causes.cause("db unreachable: connection refused").promise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      forwarded,
                                                                      new ConcurrentHashMap<>(),
                                                                      List.of(SELF),
                                                                      () -> "default:local");

        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));

        var failed = forwardedFailures(forwarded);

        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().fatal()).as("an untyped activation failure is retryable").isFalse();
        assertThat(failed.getFirst().failureReason().unwrap()).contains("db unreachable: connection refused");
    }

    /// A cause typed at its raise site classifies the same way in either phase: a Fatal raised during
    /// activation is forwarded fatal=true even though the phase's declared disposition is RETRY.
    @Test
    void typedFatalActivationFailure_isForwardedAsFatal() {
        var conflict = new SliceLoadingFailure.Fatal.SharedLoaderVersionConflict("org.example:slice:1.0.0",
                                                                                 "org.example:lib:^2.0.0",
                                                                                 "org.example:lib:1.0.0",
                                                                                 "org.example:other:1.0.0");
        var forwarded = mock(MutationForwarder.class);
        var sliceStore = mock(SliceStore.class);

        when(sliceStore.loadSlice(any())).thenReturn(Promise.success(mock(LoadedSlice.class)));
        when(sliceStore.activateSlice(any())).thenReturn(conflict.promise());
        when(sliceStore.loaded()).thenReturn(List.of());
        var manager = WorkerDeploymentManager.workerDeploymentManager(SELF,
                                                                      sliceStore,
                                                                      forwarded,
                                                                      new ConcurrentHashMap<>(),
                                                                      List.of(SELF),
                                                                      () -> "default:local");

        manager.onDirectivePut(WorkerSliceDirectiveValue.workerSliceDirectiveValue(ARTIFACT, 1, "any"));

        var failed = forwardedFailures(forwarded);

        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().fatal()).isTrue();
        assertThat(failed.getFirst().failureReason().unwrap()).contains("slice org.example:slice:1.0.0 requires org.example:lib:^2.0.0");
    }

    private static List<NodeArtifactValue> forwardedFailures(MutationForwarder forwarder) {
        var captor = ArgumentCaptor.forClass(WorkerMutation.class);

        verify(forwarder, atLeastOnce()).forward(captor.capture());

        return captor.getAllValues()
                     .stream()
                     .map(WorkerMutation::command)
                     .filter(KVCommand.Put.class::isInstance)
                     .map(command -> (KVCommand.Put<?, ?>) command)
                     .map(KVCommand.Put::value)
                     .filter(NodeArtifactValue.class::isInstance)
                     .map(NodeArtifactValue.class::cast)
                     .filter(value -> value.state() == SliceState.FAILED)
                     .toList();
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
