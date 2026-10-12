// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Activate;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Deactivate;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Rebuild;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Timer;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.WriteSettled;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState.Active;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState.Dormant;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState.Rebuilding;


/// The cluster (leader-side) deployment state machine as a pure transition function (spec 3.1, 3.2, 3.3):
/// `step(state, input) -> (state', effects)`.
///
/// **Purity.** `step` performs no I/O and holds no mutable state. Its only reads beyond its arguments are the two
/// injected clocks (R-3.1.4: wall `nowMs` for committed timestamps, monotonic `monoNs` for leader-local intervals),
/// read **once** at the start of each step, and the injected `jitter` of [Settings]. The result is the next state and
/// the effects as data (R-3.1.2): the interpreter performs them and feeds the completions back as inputs. The model
/// passed in is never modified (see [ClusterModel]).
///
/// **Execution model** (R-3.1.1): one mailbox, one consumer. The FSM drops stale inputs by incarnation (R-3.1.3),
/// coalesces its own `Reconcile` (R-3.1.5), and treats `Rebuild` as the first item after `Activate` (R-3.1.6): inputs
/// that arrive in between are held and replayed in order.
public final class ClusterFsm {
    private final Settings settings;
    private final LongSupplier wallMs;
    private final LongSupplier monoNs;

    private ClusterFsm(Settings settings, LongSupplier wallMs, LongSupplier monoNs) {
        this.settings = settings;
        this.wallMs = wallMs;
        this.monoNs = monoNs;
    }

    public static ClusterFsm clusterFsm(Settings settings, LongSupplier wallMs, LongSupplier monoNs) {
        return new ClusterFsm(settings, wallMs, monoNs);
    }

    public Settings settings() {
        return settings;
    }

    /// The result of one step.
    public record Stepped(ClusterState state, List<ClusterEffect> effects) {
        public Stepped {
            effects = List.copyOf(effects);
        }
    }

    public Stepped step(ClusterState state, ClusterInput input) {
        var ctx = new Ctx(settings, wallMs.getAsLong(), monoNs.getAsLong());
        var next = switch (state) {
            case Dormant dormant -> whileDormant(dormant, input, ctx);
            case Rebuilding rebuilding -> whileRebuilding(rebuilding, input, ctx);
            case Active active -> whileActive(active, input, ctx);
        };

        return new Stepped(next, ctx.effects());
    }

    // -- Dormant (3.2) ---------------------------------------------------------------------------------------------
    private ClusterState whileDormant(Dormant dormant, ClusterInput input, Ctx ctx) {
        if (input instanceof Activate) {
            var incarnation = dormant.lastIncarnation() + 1;

            counted(ctx, input, "applied");
            ctx.emit(new ClusterEffect.RequestRebuild(incarnation));

            return new Rebuilding(incarnation, List.of());
        }

        counted(ctx, input, "dropped_dormant");

        return dormant;
    }

    // -- Rebuilding: Rebuild is the first mailbox item (R-3.1.6, NT-01) ----------------------------------------------
    private ClusterState whileRebuilding(Rebuilding rebuilding, ClusterInput input, Ctx ctx) {
        return switch (input) {
            case Rebuild rebuild -> rebuilt(rebuilding, rebuild, ctx);
            case Deactivate _ -> {
                counted(ctx, input, "applied");
                yield new Dormant(rebuilding.incarnation());
            }
            case Activate _ -> {
                counted(ctx, input, "ignored");
                yield rebuilding;
            }
            case Timer _, WriteSettled _ -> {
                counted(ctx, input, "dropped_stale");
                yield rebuilding;
            }
            default -> {
                counted(ctx, input, "held");
                yield hold(rebuilding, input);
            }
        };
    }

    private static Rebuilding hold(Rebuilding rebuilding, ClusterInput input) {
        var held = new ArrayList<>(rebuilding.held());

        held.add(input);

        return new Rebuilding(rebuilding.incarnation(), held);
    }

    /// C1: build the indexes from the snapshot, then replay what arrived while rebuilding, in order.
    private ClusterState rebuilt(Rebuilding rebuilding, Rebuild rebuild, Ctx ctx) {
        counted(ctx, rebuild, "applied");
        var model = Lifecycle.rebuild(ctx, rebuilding.incarnation(), rebuild.snapshot());

        for (var held : rebuilding.held()) {
            model = apply(model, held, ctx);
        }

        return new Active(model);
    }

    // -- Active ----------------------------------------------------------------------------------------------------
    private ClusterState whileActive(Active active, ClusterInput input, Ctx ctx) {
        var model = active.model();

        return switch (input) {
            case Deactivate _ -> {
                counted(ctx, input, "applied");
                yield deactivated(model, ctx);
            }
            case Activate _ -> {
                counted(ctx, input, "ignored");
                yield active;
            }
            case Timer timer when timer.incarnation() != model.incarnation() -> {
                counted(ctx, input, "dropped_stale");
                yield active;
            }
            case WriteSettled settled when settled.incarnation() != model.incarnation() -> {
                counted(ctx, input, "dropped_stale");
                yield active;
            }
            case Rebuild rebuild -> {
                counted(ctx, input, "applied");
                yield new Active(Lifecycle.rebuild(ctx, model.incarnation(), rebuild.snapshot()));
            }
            default -> {
                counted(ctx, input, "applied");
                yield new Active(apply(model, input, ctx));
            }
        };
    }

    /// `Deactivate`: cancel the incarnation's timers and discard the model (3.2, NT-02).
    private static ClusterState deactivated(ClusterModel model, Ctx ctx) {
        model.timers.toList()
                    .stream()
                    .sorted(Comparator.comparing(TimerId::subject).thenComparing(timer -> timer.kind()
                                                                                               .name()))
                    .forEach(timer -> ctx.emit(new ClusterEffect.Cancel(timer)));

        return new Dormant(model.incarnation());
    }

    /// One input against a copy of the model, then the end-of-step bookkeeping: a flush is queued if the handlers left
    /// intents (R-5.7.4).
    private ClusterModel apply(ClusterModel model, ClusterInput input, Ctx ctx) {
        return Outbox.requestFlush(Handlers.handle(model.copy(), ctx, input),
                                   ctx);
    }

    private static Ctx counted(Ctx ctx, ClusterInput input, String result) {
        ctx.metric("aether_deployment_fsm_inputs_total",
                   Map.of("fsm",
                          "cluster",
                          "input",
                          input.getClass().getSimpleName(),
                          "result",
                          result));

        return ctx;
    }
}
