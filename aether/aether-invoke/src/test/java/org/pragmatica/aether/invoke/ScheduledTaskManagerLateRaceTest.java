// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1723 F2: in production the state writer is asynchronous consensus and the reader is the COMMITTED registry, so a
/// late response can land after a fire's UNKNOWN row was submitted but before it committed. Every other test applies
/// writes synchronously and cannot see that window. Here commits are deferred and the response wins the race.
class ScheduledTaskManagerLateRaceTest {
    /// F2b: fire 1 times out and its UNKNOWN commits; fire 2 FAILS and its row is submitted but not yet committed; then
    /// fire 1's late success arrives. The committed row still says "fire 1 is the newest", but this manager has already
    /// submitted fire 2's row: the resolution must build on THAT (the newer, by sequence), so fire 2's failure is not
    /// overwritten with fire 1's success and the sequence does not go from 2 back to 1.
    @Test
    void olderFireLateAnswer_whileTheNewerFiresRowIsInFlight_neverOverwritesIt() throws Exception {
        var registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        var stub = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());
        var self = new NodeId("node-self");
        var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        var method = MethodName.methodName("cleanup").unwrap();
        var key = ScheduledTaskStateKey.scheduledTaskStateKey("cache", artifact, method, self);
        var committed = new ConcurrentHashMap<ScheduledTaskStateKey, ScheduledTaskStateValue>();
        var submitted = new CopyOnWriteArrayList<ScheduledTaskStateValue>();
        org.pragmatica.lang.Cause boom = () -> "fire 2 failed";
        Consumer<KVCommand<AetherKey>> writer = command -> {
            if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof ScheduledTaskStateValue value) {
                submitted.add(value);
                if (submitted.size() == 1) {
                    committed.put(key, value);
                    stub.unknownWithLateOutcome.set(false);
                    stub.setCompletionFailure(Option.some(boom));
                } else if (submitted.size() == 2) {
                    stub.lateOutcomes.getFirst().succeed(Unit.unit());
                }
            }
        };
        var manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                stub,
                                                                self,
                                                                writer,
                                                                k -> Option.option(committed.get(k)),
                                                                new ScheduledTaskManagerTest.TestLeaderManager(self));

        stub.unknownWithLateOutcome.set(true);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache", artifact, method),
                                                                       ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.ALL)),
                                                   Option.none()));
        manager.onQuorumStateChange(ClusterStateNotification.active());
        var deadline = System.currentTimeMillis() + 6_000;

        while (submitted.size() < 3 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        manager.stop();

        assertThat(submitted).as("premise: fire 1 UNKNOWN, fire 2 FAILURE, the resolution").hasSizeGreaterThanOrEqualTo(3);
        var fire2 = submitted.get(1);
        var resolved = submitted.get(2);

        assertThat(fire2.lastOutcome()).as("premise: fire 2 failed").isEqualTo(ScheduledTaskStateValue.OUTCOME_FAILURE);
        assertThat(resolved.fireSeq()).as("the sequence never goes backwards").isGreaterThanOrEqualTo(fire2.fireSeq());
        assertThat(resolved.lastOutcome()).as("the newer fire's FAILURE stands").isEqualTo(ScheduledTaskStateValue.OUTCOME_FAILURE);
        assertThat(resolved.consecutiveFailures()).as("its streak is not reset").isEqualTo(fire2.consecutiveFailures());
        assertThat(resolved.lateResolutions()).as("fire 1 is still counted as answered").isEqualTo(1);
    }

    /// An older fire (seq 5, its response lost) is still unknown. Fire 6 times out; its answer arrives before its
    /// UNKNOWN commits. The resolution must not write the sequence backwards nor lower the gauge against a count that
    /// does not yet include fire 6: the committed pair (UNKNOWN, then the resolution) must leave fire 5 counted.
    @Test
    void lateAnswer_beforeItsUnknownCommit_neverRegressesTheSequence_orDropsAnotherFiresGauge() throws Exception {
        var registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        var stub = new ScheduledTaskManagerTest.StubSliceInvoker(new CopyOnWriteArrayList<>(), Option.none());
        var self = new NodeId("node-self");
        var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        var method = MethodName.methodName("cleanup").unwrap();
        var key = ScheduledTaskStateKey.scheduledTaskStateKey("cache", artifact, method, self);
        var committed = new ConcurrentHashMap<ScheduledTaskStateKey, ScheduledTaskStateValue>();

        committed.put(key,
                      new ScheduledTaskStateValue(0, 0, 0, 3, "", 0, 0, ScheduledTaskStateValue.OUTCOME_UNKNOWN, 5, 100L, 1, 0));
        var submitted = new CopyOnWriteArrayList<ScheduledTaskStateValue>();
        Consumer<KVCommand<AetherKey>> writer = command -> {
            if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof ScheduledTaskStateValue value) {
                submitted.add(value);
                if (submitted.size() == 1) {
                    // the callee's response arrives just after the timeout, before this Put commits
                    stub.lateOutcomes.getLast().succeed(Unit.unit());
                }
            }
        };
        var manager = ScheduledTaskManager.scheduledTaskManager(registry,
                                                                stub,
                                                                self,
                                                                writer,
                                                                k -> Option.option(committed.get(k)),
                                                                new ScheduledTaskManagerTest.TestLeaderManager(self));

        stub.unknownWithLateOutcome.set(true);
        registry.onScheduledTaskPut(new ValuePut<>(new KVCommand.Put<>(ScheduledTaskKey.scheduledTaskKey("cache", artifact, method),
                                                                       ScheduledTaskValue.intervalTask(self, "1s", ExecutionMode.ALL)),
                                                   Option.none()));
        manager.onQuorumStateChange(ClusterStateNotification.active());
        var deadline = System.currentTimeMillis() + 5_000;

        while (submitted.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        manager.stop();

        assertThat(submitted).as("premise: the UNKNOWN row, then the resolution").hasSizeGreaterThanOrEqualTo(2);
        var unknown = submitted.get(0);
        var resolved = submitted.get(1);

        assertThat(unknown.fireSeq()).as("premise: fire 6").isEqualTo(6);
        assertThat(unknown.completionTimeouts()).as("premise: fires 5 and 6 timed out").isEqualTo(2);
        assertThat(resolved.fireSeq()).as("the sequence never goes backwards").isEqualTo(6);
        assertThat(resolved.completionTimeouts()).as("fire 6's timeout is kept: the row is built on the UNKNOWN row it wrote").isEqualTo(2);
        assertThat(resolved.lateResolutions()).as("only fire 6 is resolved").isEqualTo(1);
        assertThat(resolved.totalExecutions()).as("fire 6 was an execution").isEqualTo(4);
        assertThat(resolved.lastOutcome()).as("fire 6 is the newest").isEqualTo(ScheduledTaskStateValue.OUTCOME_SUCCESS);
    }
}
