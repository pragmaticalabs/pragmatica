// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.function.Function;
import java.util.function.Supplier;


/// `LifecycleWriter` that routes every `LifecycleCommand` through the sovereign `MembershipFsm`
/// reducer (`commandIngress = membershipFsm::applyLifecycleCommand`) instead of writing KV
/// directly. The reducer owns lifecycle transitions, so an illegal command-on-state — e.g.
/// `ForceOnDuty` against a `STOPPED+FORCED` peer (the S01 re-projection) — is a no-op rather than
/// the unconditional overwrite the legacy `DirectLifecycleWriter` performed. The ingress returns
/// whether the command was ACCEPTED; this writer maps that to `Unit` and discards the boolean
/// (reducer no-ops are observable via FSM/KV inspection rather than an audit-stream tee since
/// E2 Phase 2c-α.1a deleted the audit publisher).
///
/// The legacy `request*` methods map to the matching `LifecycleCommand`, stamping a fresh HLC
/// timestamp from `clock` — they carry none of their own, and the stamp flows to the resulting
/// `NodeLifecycleValue.updatedAt`, which drives STOPPED retention/GC (a zero stamp would GC a
/// freshly-FAILED_DRAIN atom immediately).
final class FsmRoutedLifecycleWriter implements LifecycleWriter {
    private final Function<LifecycleCommand, Promise<Boolean>> commandIngress;
    private final Supplier<HlcTimestamp> clock;

    FsmRoutedLifecycleWriter(Function<LifecycleCommand, Promise<Boolean>> commandIngress,
                             Supplier<HlcTimestamp> clock) {
        this.commandIngress = commandIngress;
        this.clock = clock;
    }

    @Override public Promise<Unit> applyCommand(LifecycleCommand command) {
        return commandIngress.apply(command).mapToUnit();
    }

    @Override public Promise<Unit> requestDrain(NodeId target) {
        return applyCommand(new ForceDrain(target,
                                           DrainReason.OPERATOR_DRAIN,
                                           Causes.cause("requestDrain " + target.id()),
                                           clock.get()));
    }

    @Override public Promise<Unit> requestDecommission(NodeId target) {
        return applyCommand(new ForceDecommission(target,
                                                  StopReason.FORCED,
                                                  Causes.cause("requestDecommission " + target.id()),
                                                  clock.get()));
    }

    @Override public Promise<Unit> requestActivate(NodeId target) {
        return applyCommand(new ForceOnDuty(target,
                                            Causes.cause("requestActivate " + target.id()),
                                            clock.get()));
    }

    @Override public Promise<Unit> requestFailedDrain(NodeId target) {
        return applyCommand(new ForceDecommission(target,
                                                  StopReason.DRAIN_FAILED,
                                                  Causes.cause("requestFailedDrain " + target.id()),
                                                  clock.get()));
    }

    @Override public Promise<Unit> requestRecordJoining(NodeId target) {
        return applyCommand(new RecordJoining(target,
                                              Option.none(),
                                              Causes.cause("requestRecordJoining " + target.id()),
                                              clock.get()));
    }

    @Override public Promise<Unit> requestReJoin(NodeId target) {
        return applyCommand(new RequestReJoin(target,
                                              Causes.cause("requestReJoin " + target.id()),
                                              clock.get()));
    }
}
