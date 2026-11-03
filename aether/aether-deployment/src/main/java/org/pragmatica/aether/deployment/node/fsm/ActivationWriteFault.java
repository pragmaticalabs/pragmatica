// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// TEST-ONLY fault for the #1117 tracer, INERT unless a test arms it. Production never arms it, so
/// [#leavesUnanswered] answers `false` and every state write goes to consensus unchanged.
///
/// Armed for one node and one artifact, the FIRST submission (attempt 0) of every ACTIVE write that node makes for
/// that artifact is never handed to consensus: the caller gets a promise that never resolves, which is what an
/// unanswered submission looks like from the node. Retries (attempt 1 and later) go through. This is the shape the
/// #1117 evidence reports: the slice's ACTIVE confirmation write is lost, a later resubmission is answered at once
/// (#2143). The fault reaches every ACTIVE write path, because they all submit through the one call site that consults
/// it: the activation chain's last write, the ROUTING ack fast path and the stuck-slice re-drive.
///
/// Ember runs every node in one JVM, so one static target addresses exactly one node.
public final class ActivationWriteFault {
    private static final AtomicReference<Option<Target>> TARGET = new AtomicReference<>(Option.none());
    private static final AtomicInteger UNANSWERED = new AtomicInteger();

    private ActivationWriteFault() {}

    /// The node whose ACTIVE writes for `artifact` lose their first submission.
    public record Target(NodeId node, Artifact artifact) {
        boolean names(NodeId self, Artifact written) {
            return node.equals(self) && artifact.equals(written);
        }
    }

    /// Arms the fault and resets the count of submissions it left unanswered.
    @Contract
    public static void arm(NodeId node, Artifact artifact) {
        UNANSWERED.set(0);
        TARGET.set(Option.some(new Target(node, artifact)));
    }

    @Contract
    public static void disarm() {
        TARGET.set(Option.none());
    }

    /// How many submissions the armed fault left unanswered since it was last armed. A tracer reads it to prove the
    /// fault fired, so a run in which it never did is not mistaken for a run the engine survived.
    public static int unansweredSubmissions() {
        return UNANSWERED.get();
    }

    /// The FSM2 submission point (phase-3 design 13.1, T): the first submission of an ACTIVE write of the target node for the target artifact is left
    /// unanswered. FSM2 counts attempts from 1, and a probe resubmission carries the attempt of the write it re-sends (`probe > 0`; spec R-3.4.4: a probe
    /// does not consume an attempt), so the fault takes `attempt == 1 && probe == 0` only: keyed on the attempt alone it would swallow the probe too, and
    /// T would stay red under FSM2 for the wrong reason. An ACTIVE write is one whose commands put this node's observed row for the artifact as ACTIVE.
    public static boolean leavesUnansweredFsm2(NodeId self,
                                               Artifact artifact,
                                               int attempt,
                                               int probe,
                                               List<KVCommand<AetherKey>> commands) {
        var unanswered = attempt == 1 && probe == 0 && writesActive(self, artifact, commands) && TARGET.get()
                                                                                                       .filter(target -> target.names(self,
                                                                                                                                      artifact))
                                                                                                       .isPresent();

        if (unanswered) {
            UNANSWERED.incrementAndGet();
        }

        return unanswered;
    }

    private static boolean writesActive(NodeId self, Artifact artifact, List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .anyMatch(command -> command instanceof KVCommand.Put<AetherKey, ?> put
                                            && put.key() instanceof AetherKey.SliceObservedKey key
                                            && key.nodeId()
                                                  .equals(self)
                                            && key.artifact()
                                                  .equals(artifact)
                                            && put.value() instanceof AetherValue.SliceObservedValue observed
                                            && observed.state() == AetherValue.SliceObservedState.ACTIVE);
    }

    /// True when this submission must be left unanswered; counts it when so.
    static boolean leavesUnanswered(NodeId self, Artifact artifact, SliceState state, int attempt) {
        var unanswered = state == SliceState.ACTIVE && attempt == 0 && TARGET.get()
                                                                             .filter(target -> target.names(self,
                                                                                                            artifact))
                                                                             .isPresent();

        if (unanswered) {
            UNANSWERED.incrementAndGet();
        }

        return unanswered;
    }
}
