// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.config.ConfigNotificationManager;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.deployment.node.machine.LegCommand;
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
import org.pragmatica.lang.Unit;
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
/// | `PREPARE_TEARDOWN` | the copied teardown builders (`SliceLegBuilders.teardown`) | `Done(removes and releases)`; builds, severs nothing |
/// | `UNPUBLISH` | routes, bridge, config registration | `Done`, always (best effort) |
/// | `DEACTIVATE` | `SliceStore.deactivateSlice` | `Done`, or `Failed(cause)` with the store's own cause |
/// | `UNLOAD` | `SliceStore.unloadSlice` | `Done`, or `Failed(cause)` with the store's own cause |
/// | `SUSPEND` | routes, bridge | `Done`, always (a quorum loss writes nothing) |
/// | `EVICT` | `SliceStore.unloadSlice` for an entry that is not a loaded slice | `Done`, always |
/// | `FORCED_CLEANUP` | the node-scoped legs through the cluster, routes, bridge, config, `deactivateSlice`, `unloadSlice` | `Done`, or the first `Failed` of the deactivation and the unload |
/// | `NOTIFY_CONFIG` | `ConfigNotificationManager.notifyChange` | `Done`; carries no artifact, a key |
///
/// **Every step is wired.** The switch over `LocalStep` is exhaustive, so a step added to the enum is a compile error here and not a step that
/// fails at run time. A step handed no artifact (or `NOTIFY_CONFIG` handed no key) fails loudly rather than being skipped.
///
/// **`UNLOAD` and the store.** `SliceStore.unloadSlice` takes the entry out of its map before it stops and cleans up, so an unload that fails
/// leaves a leaked class loader but never a slice the store still lists. That is what makes the node FSM's "not loaded" after any UNLOAD true of
/// the store (pinned by `SliceStoreTest`).
///
/// **`EVICT` and the store.** `SliceStore.loadSlice` removes its own entry when the load fails (`SliceStore.java:254`), but the removal is a
/// promise callback and may trail the FSM's `EVICT` by a moment, so a failed entry can still be there when `EVICT` arrives. `unloadSlice`
/// drops such an entry and reports nothing to unload; applied to a loaded slice it would unload it, so `EVICT` leaves a loaded slice alone.
public final class SliceRuntimeAdapter implements LocalOpExecutor.StepRunner {
    private static final Logger log = LoggerFactory.getLogger(SliceRuntimeAdapter.class);

    private static final Fn1<Cause, LocalStep> NEEDS_ARTIFACT = Causes.forOneValue("local step %s needs an artifact");

    private static final Cause NO_CONFIG_KEY = Causes.cause("local step NOTIFY_CONFIG carries no config key");

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

    /// What this adapter was actually given, for a test that reads the node's real collaborators where they are used.
    public Collaborators collaborators() {
        return c;
    }

    public static SliceRuntimeAdapter sliceRuntimeAdapter(Collaborators collaborators) {
        return new SliceRuntimeAdapter(collaborators);
    }

    @Override
    public Promise<LocalOutcome> run(NodeFsmEffect.LocalOp op) {
        return switch (op.step()) {
            case LOAD -> onArtifact(op, this::load);
            case ACTIVATE -> onArtifact(op, this::activate);
            case REGISTER_BRIDGE -> onArtifact(op, artifact -> settled(registerBridge(artifact)));
            case BUILD_LEGS -> onArtifact(op, artifact -> settled(buildLegs(artifact)));
            case REGISTER_CONFIG -> onArtifact(op, artifact -> settled(registerConfig(artifact)));
            case PUBLISH_ROUTES -> onArtifact(op, this::publishRoutes);
            case DESCRIBE_ACTIVE -> onArtifact(op, artifact -> settled(describeActive(artifact)));
            case PREPARE_TEARDOWN -> onArtifact(op, artifact -> settled(prepareTeardown(artifact)));
            case UNPUBLISH -> onArtifact(op, artifact -> unpublish(artifact, true));
            case DEACTIVATE -> onArtifact(op, this::deactivate);
            case UNLOAD -> onArtifact(op, this::unload);
            case EVICT -> onArtifact(op, this::evict);
            case FORCED_CLEANUP -> onArtifact(op, this::forcedCleanup);
            case SUSPEND -> onArtifact(op, artifact -> unpublish(artifact, false));
            case NOTIFY_CONFIG -> settled(notifyConfig(op.argument()));
        };
    }

    private static Promise<LocalOutcome> onArtifact(NodeFsmEffect.LocalOp op,
                                                    Fn1<Promise<LocalOutcome>, Artifact> step) {
        return op.artifact()
                 .fold(() -> Promise.success(LocalOutcome.failed(NEEDS_ARTIFACT.apply(op.step()))),
                       step::apply);
    }

    private Promise<LocalOutcome> load(Artifact artifact) {
        return c.sliceStore()
                .loadSlice(artifact)
                .map(_ -> LocalOutcome.done())
                .recover(LocalOutcome::failed);
    }

    private Promise<LocalOutcome> activate(Artifact artifact) {
        return c.sliceStore()
                .activateSlice(artifact)
                .map(_ -> LocalOutcome.done())
                .recover(LocalOutcome::failed);
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

    // -- teardown ------------------------------------------------------------------------------------------------------
    /// The removes and releases of the teardown batch, built from the slice as it stands. A slice absent from the store has nothing to build from
    /// and the batch carries only the row.
    private Result<LocalOutcome> prepareTeardown(Artifact artifact) {
        return Result.success(loaded(artifact).map(slice -> LocalOutcome.done(builders.teardown(artifact,
                                                                                                slice.slice())))
                                    .or(LocalOutcome.done()));
    }

    private Promise<LocalOutcome> deactivate(Artifact artifact) {
        return c.sliceStore()
                .deactivateSlice(artifact)
                .map(_ -> LocalOutcome.done())
                .recover(LocalOutcome::failed);
    }

    private Promise<LocalOutcome> unload(Artifact artifact) {
        return c.sliceStore()
                .unloadSlice(artifact)
                .map(_ -> LocalOutcome.done())
                .recover(LocalOutcome::failed);
    }

    /// N17 (B-148, with B-166's change): the goal is gone while the slice is loaded. The node-scoped legs are released through the cluster,
    /// everything is severed (routes, bridge and, unlike legacy, the config registration), and the slice is deactivated and unloaded.
    ///
    /// **Only the node-scoped legs.** The cluster-scoped ones (stream registration, scheduled task and its pause key) are kept: whether this
    /// node was the last replica is the FSM's knowledge (`Rows.hostedElsewhere`), a forced cleanup carries no batch the FSM could filter, and
    /// removing them while another replica hosts the artifact would destroy a live registration and an operator's pause, where leaving them
    /// behind costs a stale row. `[guess: the direction of the error; settled by the CTO, or by the FSM naming the last replica on the op]`
    ///
    /// A deactivation that fails must not keep the slice in the store, which the FSM has already recorded as not loaded: the unload runs
    /// regardless (it stops an ACTIVE slice itself) and the step reports the deactivation's failure if the unload succeeded.
    private Promise<LocalOutcome> forcedCleanup(Artifact artifact) {
        return releaseLegs(artifact).flatMap(_ -> severing(artifact, true))
                          .flatMap(_ -> deactivate(artifact))
                          .flatMap(deactivated -> unload(artifact).map(unloaded -> firstFailure(deactivated, unloaded)));
    }

    private static LocalOutcome firstFailure(LocalOutcome first, LocalOutcome second) {
        return first instanceof LocalOutcome.Failed
               ? first
               : second;
    }

    /// FER (degrade forward): a release the cluster refuses is logged and the cleanup goes on, because the slice has to leave either way. The
    /// guarantee earned is that the slice is gone; the cost is a topic-subscription row or a stream reference count left behind until the next
    /// goal for the pair. The mechanism is a single attempt, with no retry (legacy retried three times).
    private Promise<Unit> releaseLegs(Artifact artifact) {
        var commands = loaded(artifact).map(slice -> nodeScoped(builders.teardown(artifact,
                                                                                  slice.slice())))
                             .or(List.of());

        return commands.isEmpty()
               ? Promise.unitPromise()
               : c.cluster()
                  .get()
                  .<Object> apply(commands)
                  .onFailure(cause -> log.warn("Could not release the legs of {} on forced cleanup: {}",
                                               artifact,
                                               cause.message()))
                  .mapToUnit()
                  .recover(_ -> Unit.unit());
    }

    private static List<KVCommand<AetherKey>> nodeScoped(List<LegCommand> legs) {
        return legs.stream()
                   .filter(leg -> leg.kind() == LegCommand.LegKind.TOPIC_SUBSCRIPTION || leg.kind() == LegCommand.LegKind.STREAM_REF)
                   .map(LegCommand::command)
                   .toList();
    }

    // -- config -------------------------------------------------------------------------------------------------------
    /// NI-5: a committed config key changed; every registered slice whose declared section the key falls under is told, through its own
    /// composite facade (#381). The op carries the key and no artifact.
    private Result<LocalOutcome> notifyConfig(Option<String> key) {
        return key.toResult(NO_CONFIG_KEY)
                  .flatMap(changed -> c.configNotifications()
                                       .notifyChange(changed, this::configFacade))
                  .map(_ -> LocalOutcome.done());
    }

    // -- severing ------------------------------------------------------------------------------------------------------
    /// Routes, the bridge and, for `UNPUBLISH`, the config registration. FER (degrade forward): the step reports `Done` even if the publisher
    /// fails, because the FSM has nothing to do about a route that would not go away. The guarantee earned is that the bridge is gone (it is
    /// unregistered first and cannot fail) and that the chain proceeds; the route may linger. The mechanism is the publisher's own WARN
    /// below, and the next `PUBLISH_ROUTES` of the artifact, which replaces what is there.
    private Promise<LocalOutcome> unpublish(Artifact artifact, boolean includingConfig) {
        return severing(artifact, includingConfig).map(_ -> LocalOutcome.done());
    }

    private Promise<Unit> severing(Artifact artifact, boolean includingConfig) {
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
                .recover(_ -> Unit.unit());
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
