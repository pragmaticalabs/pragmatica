// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.SliceNotInStore;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Executes each `LocalStep` of the node FSM against the real slice runtime (phase-3 design 4.3 step map): the slice store, the invocation
/// bridge, the HTTP route publisher and the config-update notifier. It runs a step and says how it ended; it decides nothing.
///
/// | Step | Collaborator | Outcome |
/// |---|---|---|
/// | `LOAD`, `ACTIVATE` | `SliceStore.loadSlice` / `activateSlice` | `Done`, or `Failed(cause)` with the store's own cause |
/// | `REGISTER_BRIDGE` | the invocation bridge | `Done`; a slice absent from the store is `Failed(SliceNotInStore)` |
/// | `BUILD_LEGS` | the copied builders (`SliceLegBuilders`) | `Done(legs)`; an invalid schedule string fails the step |
/// | `REGISTER_CONFIG` | `ConfigNotificationManager` | `Done` |
/// | `PUBLISH_ROUTES` | `HttpRoutePublisher.publishRoutes` | `Done`; a failure fails the chain |
/// | `DESCRIBE_ACTIVE` | the slice's methods; the stream-ref ACQUIRE builder | `Done(methods, ACQUIRE commands)` |
/// | `UNPUBLISH` | routes, bridge, config registration | `Done`, always (best effort) |
/// | `SUSPEND` | routes, bridge | `Done`, always (a quorum loss writes nothing) |
/// | `EVICT` | `SliceStore.unloadSlice` for an entry that is not a loaded slice | `Done`, always |
///
/// **Not in this tracer.** `PREPARE_TEARDOWN`, `DEACTIVATE`, `UNLOAD`, `FORCED_CLEANUP` and `NOTIFY_CONFIG` lie outside the path T and E-01
/// walk (no undeploy, no scale-down, no config change). They fail loudly, `unwired in phase 3 tracer`, rather than pretend; the switch over
/// `LocalStep` stays exhaustive, so a new step is a compile error here.
///
/// **`EVICT` and the store.** `SliceStore.loadSlice` removes its own entry when the load fails (`SliceStore.java:254`), but the removal is a
/// promise callback and may trail the FSM's `EVICT` by a moment, so a failed entry can still be there when `EVICT` arrives. `unloadSlice`
/// drops such an entry and reports nothing to unload; applied to a loaded slice it would unload it, so `EVICT` leaves a loaded slice alone.
public final class SliceRuntimeAdapter implements LocalOpExecutor.StepRunner {
    private static final Logger log = LoggerFactory.getLogger(SliceRuntimeAdapter.class);
    private static final Fn1<Cause, LocalStep> UNWIRED = Causes.forOneValue("unwired in phase 3 tracer: %s");

    /// The steps this adapter does not execute in the tracer: they lie outside the path T and E-01 walk. [#wired] is the single answer to "does a step
    /// run?", so a binding that feeds the adapter's steps (the config hook feeding `NOTIFY_CONFIG`) can ask instead of assuming.
    private static final Set<LocalStep> UNWIRED_STEPS = EnumSet.of(LocalStep.PREPARE_TEARDOWN,
                                                                   LocalStep.DEACTIVATE,
                                                                   LocalStep.UNLOAD,
                                                                   LocalStep.FORCED_CLEANUP,
                                                                   LocalStep.NOTIFY_CONFIG);

    /// What the adapter drives.
    public record Collaborators(NodeId self,
                                SliceStore sliceStore,
                                InvocationHandler invocationHandler,
                                SliceCodec nodeCodec,
                                Option<HttpRoutePublisher> routePublisher,
                                Option<SliceInvokerFacade> invokerFacade,
                                ConfigNotificationManager configNotifications,
                                KVStore<AetherKey, AetherValue> kvStore,
                                Supplier<ClusterNode<KVCommand<AetherKey>>> cluster,
                                LongSupplier clock,
                                Supplier<OperatorWarningSink> warnings) {}

    private final Collaborators c;
    private final SliceLegBuilders builders;

    private SliceRuntimeAdapter(Collaborators collaborators) {
        this.c = collaborators;
        this.builders = new SliceLegBuilders(new SliceLegBuilders.Sources(collaborators.self(),
                                                                          collaborators.sliceStore(),
                                                                          collaborators.kvStore(),
                                                                          collaborators.cluster(),
                                                                          collaborators.clock(),
                                                                          collaborators.warnings()));
    }

    public static SliceRuntimeAdapter sliceRuntimeAdapter(Collaborators collaborators) {
        return new SliceRuntimeAdapter(collaborators);
    }

    /// Whether `step` is executed by this adapter. A step that is not fails loudly (`unwired in phase 3 tracer`), so whatever input would
    /// cause it must not be bound to the node FSM (N-2).
    public static boolean wired(LocalStep step) {
        return ! UNWIRED_STEPS.contains(step);
    }

    @Override
    public Promise<LocalOutcome> run(NodeFsmEffect.LocalOp op) {
        return op.artifact()
                 .fold(() -> UNWIRED.apply(op.step())
                                    .<LocalOutcome> promise()
                                    .recover(LocalOutcome::failed),
                       artifact -> step(op.step(),
                                        artifact));
    }

    private Promise<LocalOutcome> step(LocalStep step, Artifact artifact) {
        return switch (step) {
            case LOAD -> c.sliceStore().loadSlice(artifact).map(_ -> LocalOutcome.done()).recover(LocalOutcome::failed);
            case ACTIVATE -> c.sliceStore().activateSlice(artifact).map(_ -> LocalOutcome.done()).recover(LocalOutcome::failed);
            case REGISTER_BRIDGE -> settled(registerBridge(artifact));
            case BUILD_LEGS -> settled(buildLegs(artifact));
            case REGISTER_CONFIG -> settled(registerConfig(artifact));
            case PUBLISH_ROUTES -> publishRoutes(artifact);
            case DESCRIBE_ACTIVE -> settled(describeActive(artifact));
            case UNPUBLISH -> unpublish(artifact, true);
            case SUSPEND -> unpublish(artifact, false);
            case EVICT -> evict(artifact);
            case PREPARE_TEARDOWN, DEACTIVATE, UNLOAD, FORCED_CLEANUP, NOTIFY_CONFIG -> Promise.success(LocalOutcome.failed(UNWIRED.apply(step)));
        };
    }

    private static Promise<LocalOutcome> settled(Result<LocalOutcome> outcome) {
        return Promise.success(outcome.fold(LocalOutcome::failed, done -> done));
    }

    private Option<SliceStore.LoadedSlice> loaded(Artifact artifact) {
        return Option.from(c.sliceStore()
                            .loaded()
                            .stream()
                            .filter(slice -> slice.artifact()
                                                  .equals(artifact))
                            .findFirst());
    }

    // -- the chain ---------------------------------------------------------------------------------------------------
    private Result<LocalOutcome> registerBridge(Artifact artifact) {
        return loaded(artifact).toResult(SliceNotInStore.sliceNotInStore(artifact.asString(),
                                                                         "invocation registration"))
                     .map(slice -> bridgeRegistered(artifact,
                                                    slice.slice()));
    }

    private LocalOutcome bridgeRegistered(Artifact artifact, Slice slice) {
        c.invocationHandler()
         .registerSlice(artifact,
                        DefaultSliceBridge.defaultSliceBridge(artifact,
                                                              slice,
                                                              slice.codec(c.nodeCodec())));
        log.debug("Registered slice {} for invocation", artifact);

        return LocalOutcome.done();
    }

    private Result<LocalOutcome> buildLegs(Artifact artifact) {
        return loaded(artifact).fold(() -> Result.success(LocalOutcome.done()),
                                     slice -> builders.legs(artifact,
                                                            slice.slice())
                                                      .map(legs -> LocalOutcome.done(legs)));
    }

    private Result<LocalOutcome> registerConfig(Artifact artifact) {
        loaded(artifact).onPresent(slice -> registerForConfigUpdates(artifact, slice.slice()));

        return Result.success(LocalOutcome.done());
    }

    private void registerForConfigUpdates(Artifact artifact, Slice slice) {
        var entries = builders.configUpdates(artifact, slice);

        if (!entries.isEmpty()) {
            c.configNotifications()
             .register(artifact,
                       slice,
                       slice.getClass().getClassLoader(),
                       entries.factoryClassName(),
                       entries.sections());
            c.configNotifications().notifyInitial(artifact, entries.sections(), configFacade(artifact));
            log.debug("Registered slice {} for config updates on sections: {}", artifact, entries.sections());
        }
    }

    private ConfigFacade configFacade(Artifact artifact) {
        return c.sliceStore()
                .sliceComposite(artifact)
                .map(ProviderBasedConfigService::providerBasedConfigService)
                .<ConfigService> map(service -> service)
                .orElse(ConfigService::instance)
                .map(NodeDeploymentManager::configServiceToFacade)
                .or(NodeDeploymentManager.NO_OP_CONFIG);
    }

    private Promise<LocalOutcome> publishRoutes(Artifact artifact) {
        return c.routePublisher()
                .flatMap(publisher -> c.invokerFacade()
                                       .flatMap(facade -> loaded(artifact).filter(slice -> hasRoutes(publisher,
                                                                                                     slice.slice()))
                                                                .map(slice -> publisher.publishRoutes(artifact,
                                                                                                      slice.slice()
                                                                                                           .getClass()
                                                                                                           .getClassLoader(),
                                                                                                      slice.slice(),
                                                                                                      facade))))
                .or(Promise.unitPromise())
                .map(_ -> LocalOutcome.done())
                .recover(LocalOutcome::failed);
    }

    private static boolean hasRoutes(HttpRoutePublisher publisher, Slice slice) {
        return publisher.hasRoutes(slice.getClass().getClassLoader(),
                                   slice);
    }

    private Result<LocalOutcome> describeActive(Artifact artifact) {
        return Result.success(loaded(artifact).map(slice -> LocalOutcome.described(methodsOf(slice.slice()),
                                                                                   builders.streamReferences(artifact,
                                                                                                             slice.slice())))
                                    .or(LocalOutcome.done()));
    }

    private static List<String> methodsOf(Slice slice) {
        return slice.methods()
                    .stream()
                    .map(method -> method.name()
                                         .name())
                    .toList();
    }

    // -- teardown on the path ------------------------------------------------------------------------------------------
    /// Routes, the bridge and, for `UNPUBLISH`, the config registration. FER (degrade forward): the step reports `Done` even if the publisher
    /// fails, because the FSM has nothing to do about a route that would not go away. The guarantee earned is that the bridge is gone (it is
    /// unregistered first and cannot fail) and that the chain proceeds; the route may linger. The mechanism is the publisher's own WARN
    /// below, and the next `PUBLISH_ROUTES` of the artifact, which replaces what is there.
    private Promise<LocalOutcome> unpublish(Artifact artifact, boolean includingConfig) {
        c.invocationHandler().unregisterSlice(artifact);
        if (includingConfig) {
            c.configNotifications().unregister(artifact);
        }

        return c.routePublisher()
                .map(publisher -> publisher.unpublishRoutes(artifact))
                .or(Promise.unitPromise())
                .onFailure(cause -> log.warn("Could not unpublish routes of {}: {}",
                                             artifact,
                                             cause.message()))
                .map(_ -> LocalOutcome.done())
                .recover(_ -> LocalOutcome.done());
    }

    /// FER: a failing `unloadSlice` is not reported. The step is fire and forget (token 0), and what it clears is an entry a failed load already
    /// left unusable; the next load of the artifact replaces it.
    private Promise<LocalOutcome> evict(Artifact artifact) {
        return loaded(artifact).isPresent()
               ? Promise.success(LocalOutcome.done())
               : c.sliceStore()
                  .unloadSlice(artifact)
                  .map(_ -> LocalOutcome.done())
                  .recover(_ -> LocalOutcome.done());
    }
}
