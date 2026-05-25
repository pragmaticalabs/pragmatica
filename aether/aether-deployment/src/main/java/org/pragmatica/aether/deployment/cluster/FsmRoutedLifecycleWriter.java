// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandApplied;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
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
/// whether the command was ACCEPTED; this writer publishes `CommandReceived` on entry and
/// `CommandApplied(accepted)` after, preserving the audit contract (a reducer no-op surfaces as
/// `accepted=false` — the `decision=ILLEGAL_TRANSITION` observability the spec asks for).
///
/// The legacy `request*` methods map to the matching `LifecycleCommand`, stamping a fresh HLC
/// timestamp from `clock` — they carry none of their own, and the stamp flows to the resulting
/// `NodeLifecycleValue.updatedAt`, which drives STOPPED retention/GC (a zero stamp would GC a
/// freshly-FAILED_DRAIN atom immediately).
final class FsmRoutedLifecycleWriter implements LifecycleWriter {
    private final Function<LifecycleCommand, Promise<Boolean>> commandIngress;
    private final Supplier<HlcTimestamp> clock;
    private final StreamPublisher<CommandLifecycleEvent> auditPublisher;

    FsmRoutedLifecycleWriter(Function<LifecycleCommand, Promise<Boolean>> commandIngress,
                             Supplier<HlcTimestamp> clock,
                             StreamPublisher<CommandLifecycleEvent> auditPublisher) {
        this.commandIngress = commandIngress;
        this.clock = clock;
        this.auditPublisher = auditPublisher;
    }

    @Override public Promise<Unit> applyCommand(LifecycleCommand command) {
        return applyCommand(command, CommandLifecycleEvent.SOURCE_UNKNOWN);
    }

    @Override public Promise<Unit> applyCommand(LifecycleCommand command, String source) {
        publishReceived(command, source);

        return commandIngress.apply(command)
                             .onSuccess(accepted -> publishApplied(command, source, accepted))
                             .onFailure(_ -> publishApplied(command, source, false))
                             .mapToUnit();
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

    @Contract
    private void publishReceived(LifecycleCommand command, String source) {
        auditPublisher.publish(new CommandReceived(commandType(command),
                                                   command.peer().toString(),
                                                   reasonTag(command),
                                                   command.justification().message(),
                                                   source,
                                                   System.currentTimeMillis()));
    }

    @Contract
    private void publishApplied(LifecycleCommand command, String source, boolean accepted) {
        auditPublisher.publish(new CommandApplied(commandType(command),
                                                  command.peer().toString(),
                                                  reasonTag(command),
                                                  command.justification().message(),
                                                  source,
                                                  System.currentTimeMillis(),
                                                  accepted));
    }

    private static String commandType(LifecycleCommand command) {
        return command.getClass()
                      .getSimpleName();
    }

    private static String reasonTag(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> cmd.reason().name();
            case ForceDrain cmd -> cmd.reason().name();
            case ForceOnDuty _, RecordJoining _, RequestReJoin _ -> "";
        };
    }
}
