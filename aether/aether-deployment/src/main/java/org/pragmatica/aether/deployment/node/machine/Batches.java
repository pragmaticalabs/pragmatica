// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Building the rows and batches the node submits: the version-fenced observed Put and the commands that ride with it
/// (spec R-3.4.3 "W s", R-3.4.6, R-3.4.9 (4)).
sealed interface Batches {
    static SliceObservedKey observedKey(NodeFsm fsm, Artifact artifact) {
        return SliceObservedKey.sliceObservedKey(fsm.config().self(),
                                                 artifact);
    }

    /// "W s": `state` at `version`, for the goal generation the chain executes, by this process, stamped with the injected
    /// wall clock (B-152).
    static SliceObservedValue observed(NodeFsm fsm, SliceObservedState state, long version, Generation generation) {
        return row(fsm, state, version, generation, none(), false, List.of());
    }

    /// ACTIVE carries the instance number and the endpoint methods when the slice has any (B-152, B-153).
    static SliceObservedValue active(NodeFsm fsm, long version, Generation generation, List<String> methods) {
        return row(fsm, SliceObservedState.ACTIVE, version, generation, none(), false, methods);
    }

    /// FAILED carries the real reason and the disposition (B-152).
    static SliceObservedValue failed(NodeFsm fsm, long version, FailureNote note) {
        return row(fsm,
                   SliceObservedState.FAILED,
                   version,
                   note.generation(),
                   some(note.reason()),
                   note.fatal(),
                   List.of());
    }

    private static SliceObservedValue row(NodeFsm fsm,
                                          SliceObservedState state,
                                          long version,
                                          Generation generation,
                                          Option<String> reason,
                                          boolean fatal,
                                          List<String> methods) {
        return new SliceObservedValue(state,
                                      version,
                                      generation,
                                      fsm.config().process(),
                                      reason,
                                      fatal,
                                      methods.isEmpty()
                                      ? 0
                                      : fsm.config().instanceNumber(),
                                      methods,
                                      fsm.now());
    }

    static KVCommand<AetherKey> put(NodeFsm fsm, Artifact artifact, SliceObservedValue value) {
        return new KVCommand.Put<AetherKey, AetherValue>(observedKey(fsm, artifact), value);
    }

    /// The batch for a chain write: the observed Put first, then the commands earlier steps collected, filtered for the
    /// state being written. A teardown batch drops the cluster-scoped removes while another live current replica hosts the
    /// artifact (R-3.4.5); a reactivation drops the stream-ref ACQUIRE (R-3.4.7).
    static List<KVCommand<AetherKey>> chainBatch(NodeFsm fsm,
                                                 NodeFsmModel model,
                                                 Artifact artifact,
                                                 Chain chain,
                                                 SliceObservedValue value) {
        return assembled(fsm, model, artifact, chain, value.state(), put(fsm, artifact, value), chain.collected());
    }

    /// `observed` first, then the `legs` a builder produced, filtered for the state being written (see [#chainBatch]). A write
    /// probe calls this with the legs its rebuild produced and the attempt's own observed command (spec R-3.4.4, O1).
    static List<KVCommand<AetherKey>> assembled(NodeFsm fsm,
                                                NodeFsmModel model,
                                                Artifact artifact,
                                                Chain chain,
                                                SliceObservedState state,
                                                KVCommand<AetherKey> observed,
                                                List<LegCommand> legs) {
        var batch = new ArrayList<KVCommand<AetherKey>>();

        batch.add(observed);
        legs.stream()
            .filter(leg -> keeps(fsm, model, artifact, chain, state, leg))
            .map(LegCommand::command)
            .forEach(batch::add);

        return batch;
    }

    private static boolean keeps(NodeFsm fsm,
                                 NodeFsmModel model,
                                 Artifact artifact,
                                 Chain chain,
                                 SliceObservedState state,
                                 LegCommand leg) {
        return switch (state) {
            case DEACTIVATING, UNLOADING -> keepsOnTeardown(fsm, model, artifact, leg);
            case ACTIVE -> chain.kind() != Chain.ChainKind.REACTIVATE || leg.kind() != LegCommand.LegKind.STREAM_REF;
            default -> false;
        };
    }

    private static boolean keepsOnTeardown(NodeFsm fsm, NodeFsmModel model, Artifact artifact, LegCommand leg) {
        return switch (leg.kind()) {
            case TOPIC_SUBSCRIPTION, STREAM_REF -> true;
            case STREAM_REGISTRATION, SCHEDULED_TASK -> !Rows.hostedElsewhere(model, artifact);
        };
    }

    record unused() implements Batches {}
}
