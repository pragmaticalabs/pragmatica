// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.function.Consumer;

import org.pragmatica.aether.deployment.cluster.FenceFailure.AttemptCancelled;
import org.pragmatica.aether.deployment.cluster.FenceFailure.CalledUnderOwnerMonitor;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.slf4j.LoggerFactory;


/// The narrowest sink of a DRAIN request: every writer of a DRAIN passes through one implementation.
public interface DrainSink {
    /// Requests a drain of `target`; `gate` runs once, in the continuation, before the write.
    Promise<Unit> request(NodeId target, DrainGate gate);
    /// The request already in flight for `target`, if any. Its promise succeeds only if the target is registered after every joined
    /// gate ran, fails with [FenceFailure.AttemptCancelled] if a gate ran and nothing is registered, and fails with the witness's
    /// cause on a refused witness.
    Option<Promise<Unit>> inFlight(NodeId target);

    /// The attempt that requested the drain of `target` is over, and the command is not: the sink forgets what it held for the
    /// attempt (#2315). Called when an attempt ends without a clear of the command (a failed request, an eviction at its gate); the
    /// sink that holds nothing keeps the default. A clear of the command ends the attempt through `drainCommandClear` instead.
    @Contract
    default void release(NodeId target) {}

    /// A sink that runs the whole request on the calling thread: it reads [DrainGate#ownerMonitorHeld] at entry and fails with
    /// [FenceFailure.CalledUnderOwnerMonitor], logged at ERROR with the target, if it answers `true`. Otherwise it adds the target
    /// through `registryAdd` under [DrainGate#runIfOwned]; when the gate ran, it calls `fsmCall` and succeeds, else it fails with
    /// [FenceFailure.AttemptCancelled]. It keeps no request in flight.
    static DrainSink synchronous(Consumer<NodeId> registryAdd, Consumer<NodeId> fsmCall) {
        return synchronous(registryAdd,
                           fsmCall,
                           _ -> {});
    }

    /// [#synchronous(Consumer, Consumer)] whose [#release] calls `registryRelease`: the holder of the ids `registryAdd` took.
    static DrainSink synchronous(Consumer<NodeId> registryAdd,
                                 Consumer<NodeId> fsmCall,
                                 Consumer<NodeId> registryRelease) {
        return new DrainSink() {
            @Override
            public Promise<Unit> request(NodeId target, DrainGate gate) {
                return gate.ownerMonitorHeld()
                       ? refuseUnderOwnerMonitor(target)
                       : drainIfOwned(registryAdd, fsmCall, target, gate);
            }

            @Override
            public Option<Promise<Unit>> inFlight(NodeId target) {
                return Option.none();
            }

            @Contract
            @Override
            public void release(NodeId target) {
                registryRelease.accept(target);
            }
        };
    }

    private static Promise<Unit> refuseUnderOwnerMonitor(NodeId target) {
        return CalledUnderOwnerMonitor.calledUnderOwnerMonitor(target)
                                      .<Unit> promise()
                                      .onFailure(cause -> LoggerFactory.getLogger(DrainSink.class).error("{}",
                                                                                                         cause.message()));
    }

    private static Promise<Unit> drainIfOwned(Consumer<NodeId> registryAdd,
                                              Consumer<NodeId> fsmCall,
                                              NodeId target,
                                              DrainGate gate) {
        return gate.runIfOwned(() -> registryAdd.accept(target))
               ? callFsm(fsmCall, target)
               : AttemptCancelled.attemptCancelled().promise();
    }

    private static Promise<Unit> callFsm(Consumer<NodeId> fsmCall, NodeId target) {
        fsmCall.accept(target);

        return Promise.unitPromise();
    }
}
