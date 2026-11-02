// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ObservabilityBaseline;
import org.pragmatica.aether.api.ObservabilityConfigRegistry;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.deployment.cluster.ClusterReplication;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.HttpRoutePublisher;
import org.pragmatica.aether.http.HttpRouteRegistry;
import org.pragmatica.aether.http.forward.AccessibilityFilter;
import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledClaimStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.ResourceProviderSetup;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.LeaderCatchUpGate;
import org.pragmatica.aether.node.ScheduledFireAnnouncer;
import org.pragmatica.aether.node.health.WorkerEndpointDirectory;
import org.pragmatica.aether.node.stream.StreamConsumerRegistry;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.resource.SliceScopedResourceProvider;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.interceptor.CacheDhtClient;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.DeferredSliceInvokerFacade;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.SharedLibraryClassLoader;
import org.pragmatica.aether.slice.ConfigChangeFeed;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.blueprint.OwningBlueprintResolver;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.dependency.SliceRegistry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.aether.slice.stream.BlueprintStreamAddresses;
import org.pragmatica.aether.stream.StreamAddressResolver;
import org.pragmatica.aether.update.AbTestManager;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.node.SwitchableClusterNode;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.NamedConfigProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.DistributedDHTClient;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarningSink;

import io.netty.channel.EventLoopGroup;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.node.AetherNode.configuredWorker;


/// The slice runtime unit of the node assembly: the shared library loader and resource provider facade, the slice store,
/// the invocation metrics and handler, the endpoint and subscription registries, the slice invoker with the A/B test
/// manager and scheduled tasks, and the application HTTP server. Moved out of `AetherNode` without change; the root
/// calls the phases in the order it always ran them.
public sealed interface SliceRuntimeAssembly {
    /// What `resourceProviderFacade` reads from the assembly built so far.
    record ResourceProviderFacadeInputs(AetherNodeConfig config) {}

    /// What `resourceProviderFacade` hands back for the rest of the assembly.
    record ResourceProviderFacadeOutputs(SharedLibraryClassLoader sharedLibraryLoader,
                                         Result<AetherNode.ResourceProviderSetup> resourceProviderSetupResult) {}

    /// The shared library loader and the resource provider facade; the root fails the node boot when the facade cannot be built.
    static ResourceProviderFacadeOutputs resourceProviderFacade(ResourceProviderFacadeInputs in) {
        var config = in.config();
        var sharedLibraryLoader = createSharedLibraryLoader(config);
        // #904: a configured resource provider whose secrets cannot be resolved is a boot failure
        // naming the secret, propagated exactly like `baseStorageSetupsResult` above -- never a boot
        // on the no-op facade with provisioning silently disabled.
        var resourceProviderSetupResult = createResourceProviderFacade(config);

        return new ResourceProviderFacadeOutputs(sharedLibraryLoader, resourceProviderSetupResult);
    }

    /// What `sliceStore` reads from the assembly built so far.
    record SliceStoreInputs(AetherNodeConfig config,
                            SliceRegistry sliceRegistry,
                            DeferredSliceInvokerFacade deferredInvoker,
                            SliceCodec nodeCodec,
                            List<Repository> repositories,
                            SharedLibraryClassLoader sharedLibraryLoader,
                            AetherNode.ResourceProviderSetup resourceProviderSetup,
                            Option<ConfigChangeFeed> configFeed) {}

    /// The slice store over the repositories, the shared library loader and the resource provider.
    static SliceStore sliceStore(SliceStoreInputs in) {
        var config = in.config();
        var sliceRegistry = in.sliceRegistry();
        var deferredInvoker = in.deferredInvoker();
        var nodeCodec = in.nodeCodec();
        var repositories = in.repositories();
        var sharedLibraryLoader = in.sharedLibraryLoader();
        var resourceProviderSetup = in.resourceProviderSetup();
        var sliceStore = SliceStore.sliceStore(sliceRegistry,
                                               repositories,
                                               sharedLibraryLoader,
                                               deferredInvoker,
                                               resourceProviderSetup.facade(),
                                               config.sliceAction(),
                                               resourceProviderSetup.nodeComposite(),
                                               Option.some(nodeCodec),
                                               resourceProviderSetup.secretResolver(),
                                               sliceClassLoader -> SliceScopedResourceProvider.sliceScopedResourceProvider(sliceClassLoader,
                                                                                                                           resourceProviderSetup.facade()),
                                               in.configFeed()
                                               // #773: the node is the only place that can see BOTH
                                               // the resource SPI and the slice loader, so the
                                               // slice-scoped overlay is built here and injected
                                               // down. The parameter is required rather than
                                               // optional precisely so this cannot be dropped
                                               // silently — omitting it is a compile error, not a
                                               // slice that deploys and then fails at load.
                                              );

        return sliceStore;
    }

    /// What `invocation` reads from the assembly built so far.
    record InvocationInputs(AetherNodeConfig config,
                            KVStore<AetherKey, AetherValue> kvStore,
                            RabiaNode<KVCommand<AetherKey>> clusterNode,
                            Serializer serializer,
                            Deserializer deserializer,
                            SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster) {}

    /// What `invocation` hands back for the rest of the assembly.
    record InvocationOutputs(HttpRoutePublisher httpRoutePublisher,
                             InvocationMetricsCollector invocationMetrics,
                             LogLevelRegistry logLevelRegistry,
                             InvocationTraceStore traceStore,
                             ObservabilityBaseline observabilityBaseline,
                             ObservabilityConfigRegistry configRegistry,
                             InvocationHandler invocationHandler) {}

    /// The route publisher, invocation metrics, log level and trace stores, the observability baseline and registry, and the invocation handler.
    static InvocationOutputs invocation(InvocationInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var switchableCluster = in.switchableCluster();
        var httpRoutePublisher = HttpRoutePublisher.httpRoutePublisher(config.self(),
                                                                       switchableCluster,
                                                                       GenerationSnapshotSource.noop(),
                                                                       routeMountMode(config.appHttp()));
        var invocationMetrics = InvocationMetricsCollector.invocationMetricsCollector();
        // #1573 B1: HTTP routes call the typed slice instance, not the bridge, so they record into the
        // same execution counters here. recordExecution cannot fail (it returns unitResult()).
        httpRoutePublisher.setRouteOutcomeRecorder((artifact, method, outcome) -> invocationMetrics.recordExecution(artifact,
                                                                                                                    method,
                                                                                                                    outcome)
                                                                                                   .or(Unit.unit()));
        var logLevelRegistry = LogLevelRegistry.logLevelRegistry(clusterNode, kvStore);
        var traceStore = InvocationTraceStore.invocationTraceStore();
        // #277 increment 5a: the strategy-cell system is the ONE observability engine. Unconfigured
        // injection points run the fleet baseline (sampling + tracing + depth-leveled logging + counting),
        // absorbed here from the retired ObservabilityInterceptor. depthThreshold < 0 disables observability
        // -> counting-only baseline (no fleet emission), the prior noOp-interceptor posture.
        var observabilityBaseline = config.observability().depthThreshold() < 0
                                    ? ObservabilityBaseline.countingOnly()
                                    : ObservabilityBaseline.fleet(AdaptiveSampler.adaptiveSampler(config.observability()
                                                                                                        .targetTracesPerSec()),
                                                                  traceStore,
                                                                  config.self().id(),
                                                                  config.observability().depthThreshold());
        var configRegistry = ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNode,
                                                                                     kvStore,
                                                                                     observabilityBaseline);
        var invocationHandler = InvocationHandler.invocationHandler(config.self(),
                                                                    clusterNode.network(),
                                                                    invocationMetrics,
                                                                    config.timeouts().invocation().timeout(),
                                                                    serializer,
                                                                    deserializer,
                                                                    httpRoutePublisher);
        // #277 increment 2: the config registry IS the write-side cell registrar for both dispatch
        // seams — bind it so bridge (east-west) and route (north-south) cells register at slice load /
        // route publish and deregister at unload, and a KV config put swaps their behaviour in place.
        invocationHandler.setObservabilityCellRegistrar(configRegistry);
        httpRoutePublisher.setObservabilityCellRegistrar(configRegistry);

        return new InvocationOutputs(httpRoutePublisher,
                                     invocationMetrics,
                                     logLevelRegistry,
                                     traceStore,
                                     observabilityBaseline,
                                     configRegistry,
                                     invocationHandler);
    }

    /// What `registries` hands back for the rest of the assembly.
    record RegistriesOutputs(EndpointRegistry endpointRegistry,
                             TopicSubscriptionRegistry topicSubscriptionRegistry,
                             ScheduledTaskRegistry scheduledTaskRegistry,
                             StreamConsumerRegistry streamConsumerRegistry,
                             ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                             HttpRouteRegistry httpRouteRegistry) {}

    /// The endpoint, topic subscription, scheduled task, stream consumer and HTTP route registries.
    static RegistriesOutputs registries() {
        var endpointRegistry = EndpointRegistry.endpointRegistry();
        var topicSubscriptionRegistry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        var scheduledTaskRegistry = ScheduledTaskRegistry.scheduledTaskRegistry();
        // #488: constructed here (not next to the consumer manager further down) because the KV
        // notification routes are collected before the stream subsystem is built. The registry only
        // accumulates declarations; StreamConsumerManager attaches itself as its change listener.
        var streamConsumerRegistry = StreamConsumerRegistry.streamConsumerRegistry();
        var scheduledTaskStateRegistry = ScheduledTaskStateRegistry.scheduledTaskStateRegistry();
        var httpRouteRegistry = HttpRouteRegistry.httpRouteRegistry();

        return new RegistriesOutputs(endpointRegistry,
                                     topicSubscriptionRegistry,
                                     scheduledTaskRegistry,
                                     streamConsumerRegistry,
                                     scheduledTaskStateRegistry,
                                     httpRouteRegistry);
    }

    /// What `sliceInvoker` reads from the assembly built so far.
    record SliceInvokerInputs(AetherNodeConfig config,
                              MessageRouter.DelegateRouter delegateRouter,
                              KVStore<AetherKey, AetherValue> kvStore,
                              DeferredSliceInvokerFacade deferredInvoker,
                              RabiaNode<KVCommand<AetherKey>> clusterNode,
                              Serializer serializer,
                              Deserializer deserializer,
                              DistributedDHTClient baseDhtClient,
                              DistributedDHTClient cacheDhtClient,
                              StorageInstance contentStorage,
                              AetherNode.ResourceProviderSetup resourceProviderSetup,
                              SwitchableClusterNode<KVCommand<AetherKey>> switchableCluster,
                              InvocationMetricsCollector invocationMetrics,
                              InvocationHandler invocationHandler,
                              EndpointRegistry endpointRegistry,
                              TopicSubscriptionRegistry topicSubscriptionRegistry,
                              ScheduledTaskRegistry scheduledTaskRegistry,
                              ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                              AtomicReference<Runnable> scheduledTaskDrainHookRef,
                              DeploymentManager deploymentManager,
                              OperatorWarningSink operatorWarningSink) {}

    /// What `sliceInvoker` hands back for the rest of the assembly.
    record SliceInvokerOutputs(AbTestManager abTestManager,
                               SliceInvoker sliceInvoker,
                               ScheduledTaskManager scheduledTaskManager) {}

    /// The A/B test manager, the slice invoker bound into the deferred facade, the scheduled task manager and the runtime SPI extensions.
    static SliceInvokerOutputs sliceInvoker(SliceInvokerInputs in) {
        var config = in.config();
        var delegateRouter = in.delegateRouter();
        var kvStore = in.kvStore();
        var deferredInvoker = in.deferredInvoker();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var baseDhtClient = in.baseDhtClient();
        var cacheDhtClient = in.cacheDhtClient();
        var contentStorage = in.contentStorage();
        var resourceProviderSetup = in.resourceProviderSetup();
        var switchableCluster = in.switchableCluster();
        var invocationMetrics = in.invocationMetrics();
        var invocationHandler = in.invocationHandler();
        var endpointRegistry = in.endpointRegistry();
        var topicSubscriptionRegistry = in.topicSubscriptionRegistry();
        var scheduledTaskRegistry = in.scheduledTaskRegistry();
        var scheduledTaskStateRegistry = in.scheduledTaskStateRegistry();
        var scheduledTaskDrainHookRef = in.scheduledTaskDrainHookRef();
        var deploymentManager = in.deploymentManager();
        var operatorWarningSink = in.operatorWarningSink();
        var abTestManager = AbTestManager.abTestManager(clusterNode, kvStore, invocationMetrics);
        var sliceInvoker = SliceInvoker.sliceInvoker(config.self(),
                                                     clusterNode.network(),
                                                     endpointRegistry,
                                                     invocationHandler,
                                                     serializer,
                                                     deserializer,
                                                     config.timeouts().invocation().invokerTimeout().millis(),
                                                     config.timeouts().observability().invocationCleanup().millis(),
                                                     deploymentManager);

        deferredInvoker.setDelegate(sliceInvoker);
        var scheduledTaskManager = ScheduledTaskManager.scheduledTaskManager(scheduledTaskRegistry,
                                                                             sliceInvoker,
                                                                             config.self(),
                                                                             command -> switchableCluster.apply(List.of(command)),
                                                                             scheduledTaskStateRegistry::stateFor,
                                                                             clusterNode.leaderManager(),
                                                                             ScheduledTaskManager.DEFAULT_COMPLETION_BOUND,
                                                                             ScheduledFireAnnouncer.scheduledFireAnnouncer(config.self(),
                                                                                                                           delegateRouter::route),
                                                                             scheduledClaimStore(kvStore,
                                                                                                 switchableCluster));
        // #273 item 1: resolve the drain hook now the manager exists. `DrainProcedure.initiate` runs this
        // once at the INACTIVE->DRAINING CAS, for every trigger (QUORUM_LOSS, CORE_ABSENCE, COMMANDED).
        scheduledTaskDrainHookRef.set(scheduledTaskManager::onDrainInitiated);
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> registerRuntimeExtensions(spi,
                                                                         topicSubscriptionRegistry,
                                                                         sliceInvoker,
                                                                         baseDhtClient,
                                                                         cacheDhtClient,
                                                                         contentStorage,
                                                                         kvStore,
                                                                         operatorWarningSink));

        return new SliceInvokerOutputs(abTestManager, sliceInvoker, scheduledTaskManager);
    }

    /// What `appHttpServer` reads from the assembly built so far.
    record AppHttpServerInputs(AetherNodeConfig config,
                               RabiaNode<KVCommand<AetherKey>> clusterNode,
                               Serializer serializer,
                               Deserializer deserializer,
                               AtomicReference<MembershipFsm> membershipFsmRef,
                               LeaderCatchUpGate leaderCatchUpGate,
                               HttpRoutePublisher httpRoutePublisher,
                               InvocationMetricsCollector invocationMetrics,
                               HttpRouteRegistry httpRouteRegistry,
                               AtomicReference<BooleanSupplier> workerProjectionFreshRef,
                               InFlightRequestTracker inFlightTrackerForDrain,
                               DeploymentManager deploymentManager,
                               OperatorWarningSink operatorWarningSink,
                               SliceInvoker sliceInvoker) {}

    /// What `appHttpServer` hands back for the rest of the assembly.
    record AppHttpServerOutputs(Option<EventLoopGroup> serverBossGroup,
                                Option<EventLoopGroup> serverWorkerGroup,
                                Functions.Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver,
                                WorkerEndpointDirectory workerEndpointDirectory,
                                AppHttpServer appHttpServer) {}

    /// The owner resolver and accessibility filter that narrow forwarding, and the application HTTP server.
    static AppHttpServerOutputs appHttpServer(AppHttpServerInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var membershipFsmRef = in.membershipFsmRef();
        var leaderCatchUpGate = in.leaderCatchUpGate();
        var httpRoutePublisher = in.httpRoutePublisher();
        var invocationMetrics = in.invocationMetrics();
        var httpRouteRegistry = in.httpRouteRegistry();
        var workerProjectionFreshRef = in.workerProjectionFreshRef();
        var inFlightTrackerForDrain = in.inFlightTrackerForDrain();
        var deploymentManager = in.deploymentManager();
        var operatorWarningSink = in.operatorWarningSink();
        var sliceInvoker = in.sliceInvoker();
        var serverBossGroup = clusterNode.network().server().map(Server::bossGroup);
        var serverWorkerGroup = clusterNode.network().server().map(Server::workerGroup);
        // #231 Step 3: every control-plane TaskGroup is leader-pinned, so the owner of any group is
        // the current leader. #329: gated on consensus catch-up via leaderCatchUpGate (shared with the
        // ManageableNode.taskGroupOwnerResolver() override) — a lagging replacement leader is withheld
        // ownership (notAssigned) for a bounded window so leader-local commits do not stall and 503.
        Fn1<Result<NodeId>, TaskGroup> taskGroupOwnerResolver = leaderCatchUpGate::resolve;
        // Silent-death fix: the data-path forwarder narrows candidates to currently-reachable nodes so
        // a hard-killed peer (QUIC still CONNECTED until eviction) is dropped from selection/retry long
        // before the forward timeout would otherwise burn. Membership-FSM unification (Wave D, consumer
        // #2): reachability is now decided by the authoritative MembershipFsm.reachableMembers (counted
        // MEMBER + SUSPECT) rather than NTT.keepOnlyAccessible. The FSM is constructed later (membership
        // v2 wiring below), so the filter derefs the shared membershipFsmRef at request time; before the
        // FSM exists it degrades to identity (forward falls back to the connectedPeers-only behavior).
        var workerEndpointDirectory = org.pragmatica.aether.node.health.WorkerEndpointDirectory.workerEndpointDirectory();
        AccessibilityFilter accessibilityFilter = candidates -> Option.option(membershipFsmRef.get())
                                                                      .map(fsm -> configuredWorker(config)
                                                                                  ? workerEndpointDirectory.accessible(candidates,
                                                                                                                       fsm,
                                                                                                                       clusterNode.network()
                                                                                                                                  .connectedPeers())
                                                                                  : fsm.reachableMembers(candidates))
                                                                      .or(candidates);
        // #275: the same narrowing for slice-to-slice invocation — a co-confirmed-DEAD node's endpoints
        // stay registered until the CDM's removal cleanup lands, and without this a new invocation
        // was round-robined onto it and hung for the invoker timeout.
        sliceInvoker.setAccessibilityFilter(accessibilityFilter);
        var appHttpServer = AppHttpServer.appHttpServer(config.appHttp(),
                                                        config.timeouts().forwarding(),
                                                        config.self(),
                                                        httpRouteRegistry,
                                                        Option.some(httpRoutePublisher),
                                                        Option.some(clusterNode.network()),
                                                        Option.some(serializer),
                                                        Option.some(deserializer),
                                                        config.tls(),
                                                        Option.some(invocationMetrics),
                                                        serverBossGroup,
                                                        serverWorkerGroup,
                                                        Option.some(deploymentManager),
                                                        Option.empty(),
                                                        Option.some(taskGroupOwnerResolver),
                                                        accessibilityFilter);

        appHttpServer.setOperatorWarningSink(operatorWarningSink);
        appHttpServer.setInvocationAdmission(org.pragmatica.aether.invoke.InvocationAdmission.gated(inFlightTrackerForDrain,
                                                                                                    () -> workerProjectionFreshRef.get()
                                                                                                                                  .getAsBoolean()));

        return new AppHttpServerOutputs(serverBossGroup,
                                        serverWorkerGroup,
                                        taskGroupOwnerResolver,
                                        workerEndpointDirectory,
                                        appHttpServer);
    }

    /// #1573: the committed cluster-wide automatic-rollback policy. An absent config, or the bootstrap seed (which
    /// carries no source TOML), is the built-in default (ON). The section was validated at apply, so a failure here means a document committed
    /// before validation existed; automatic rollback is then OFF — never take a destructive action on a policy
    /// that cannot be read — and the failure is logged.
    /// The SINGLE-mode scheduled-task claims live in committed cluster state (#1155): read from this node's applied store, written
    /// through consensus. A write resolves once applied; one the applier refused (VersionFenced) resolves too, so the scheduler
    /// confirms every claim write by reading the row back.
    private static ScheduledClaimStore scheduledClaimStore(KVStore<AetherKey, AetherValue> kvStore,
                                                           SwitchableClusterNode<KVCommand<AetherKey>> cluster) {
        return new ScheduledClaimStore() {
            @Override
            public Option<AetherValue.ScheduledTaskClaimValue> read(AetherKey.ScheduledTaskClaimKey key) {
                return kvStore.get(key)
                              .filter(AetherValue.ScheduledTaskClaimValue.class::isInstance)
                              .map(AetherValue.ScheduledTaskClaimValue.class::cast);
            }

            @Override
            public Promise<Unit> write(KVCommand<AetherKey> command) {
                return cluster.apply(List.of(command))
                              .mapToUnit();
            }
        };
    }

    /// Resolve the cluster-level #198 route-mount mode (§7) from the app-HTTP config. Path mode is
    /// the default; header mode carries the configured API-version header name into dispatch.
    private static RouteMountMode routeMountMode(AppHttpConfig appHttp) {
        return appHttp.apiVersioningDetection()
                      .isHeaderMode()
               ? RouteMountMode.headerMode(appHttp.apiVersionHeaderName())
               : RouteMountMode.pathMode();
    }

    private static SharedLibraryClassLoader createSharedLibraryLoader(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        return config.sliceAction()
                     .frameworkJarsPath()
                     .fold(() -> {
                               log.debug("No framework JARs path configured, using Application ClassLoader as parent");

                               return new SharedLibraryClassLoader(AetherNode.class.getClassLoader());
                           },
                           path -> FrameworkClassLoader.fromDirectory(path)
                                                       .onFailure(cause -> log.warn("Failed to create FrameworkClassLoader from {}: {}. "
                                                                                   + "Falling back to Application ClassLoader.",
                                                                                    path,
                                                                                    cause.message()))
                                                       .map(loader -> {
                                                                log.info("Using FrameworkClassLoader with {} JARs as parent",
                                                                         loader.getLoadedJars().size());

                                                                return new SharedLibraryClassLoader(loader);
                                                            })
                                                       .or(new SharedLibraryClassLoader(AetherNode.class.getClassLoader())));
    }

    private static Result<ResourceProviderSetup> createResourceProviderFacade(AetherNodeConfig config) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        return config.configProvider()
                     .fold(() -> {
                               log.debug("No configuration provider configured, resource provisioning disabled");

                               return Result.success(new ResourceProviderSetup(noOpResourceProviderFacade(),
                                                                               Option.empty(),
                                                                               Option.empty(),
                                                                               Option.empty(),
                                                                               Option.empty()));
                           },
                           configProvider -> {
                               log.info("Creating ConfigService and ResourceProvider from configuration provider");
                               // Captured once and reused both for node.toml's own resolution below AND
                               // threaded down to SliceStore for the slice-intrinsic resources.toml layer
                               // (#269) — the SAME environment-secrets capability, not a second one. This
                               // stays valid even if node.toml's OWN secret fails to resolve below: that
                               // failure is about one node.toml key, not about the SecretsProvider itself
                               // being broken, so slice-level resolution is independent of it.
                               var secretsProvider = config.environment()
                                                           .flatMap(EnvironmentIntegration::secrets);
                               var secretResolver = secretsProvider.map(sp -> (Fn1<Promise<String>, String>) sp::resolveSecret);
                               // No SecretsProvider (no `[cloud]` block, or `Main.resolveEnvironment`
                               // dropped a failed one): a `${secrets:...}` placeholder in node.toml
                               // can never resolve, so it refuses here too rather than being served
                               // as the literal value -- the `NoSecretsProviderForStorageEncryption`
                               // rule applied to the config map. A placeholder-free provider (Ember,
                               // Forge) is returned as-is.
                               var resolvedProvider = secretsProvider.fold(() -> ConfigurationProvider.withoutSecretResolution(configProvider),
                                                                           sp -> ConfigurationProvider.withSecretResolution(configProvider,
                                                                                                                            sp::resolveSecret));
                               // #904: the operator DID configure a provider; the failure is in
                               // resolving its secrets, and the refusal says so. Booting on the
                               // no-op facade instead left `nodeComposite` empty, so every
                               // `ConfigurationSection` slice failed later with a cause that blamed
                               // the node for having no provider configured.
                               return resolvedProvider.fold(cause -> secretResolutionRefused(cause),
                                                            provider -> {
                                                                // KV-overlay layer: holds operator config puts only.
                                                                // Base is empty — the merged view is composed below via
                                                                // LayeredConfigProvider, so the dynamic overlay sees no
                                                                // node.toml fall-through and DynamicConfigManager can
                                                                // operate on the overlay in isolation (Batch 1 of the
                                                                // hierarchical config refactor).
                                                                var emptyBase = ConfigurationProvider.builder().build();
                                                                var dynamicProvider = DynamicConfigurationProvider.dynamicConfigurationProvider(emptyBase);
                                                                // node-composite = KV-overlay ⊕ node.toml
                                                                var kvLayer = NamedConfigProvider.namedConfigProvider("KV",
                                                                                                                      dynamicProvider);
                                                                var nodeTomlLayer = NamedConfigProvider.namedConfigProvider("node.toml",
                                                                                                                            provider);
                                                                ConfigurationProvider nodeComposite = LayeredConfigProvider.layered(List.of(kvLayer,
                                                                                                                                            nodeTomlLayer));
                                                                var configService = ProviderBasedConfigService.providerBasedConfigService(nodeComposite);

                                                                ConfigService.setInstance(configService);
                                                                var resourceProvider = SpiResourceProvider.spiResourceProvider();

                                                                ResourceProvider.setInstance(resourceProvider);
                                                                log.info("ConfigService and ResourceProvider initialized with hierarchical composite (KV-overlay + node.toml)");
                                                                // The provider's OWN complete facade, never a hand-rolled one.
                                                                // This was an anonymous class implementing the two `provide`
                                                                // overloads only, so `releaseAll` inherited the interface's
                                                                // `Promise.unitPromise()` default and every slice unload
                                                                // reported a successful release without one reaching
                                                                // `SpiResourceProvider` (#892).
                                                                return Result.success(new ResourceProviderSetup(resourceProvider.facade(),
                                                                                                                Option.some(dynamicProvider),
                                                                                                                Option.some(nodeComposite),
                                                                                                                Option.some(resourceProvider),
                                                                                                                secretResolver));
                                                            });
                           });
    }

    private static Result<ResourceProviderSetup> secretResolutionRefused(Cause cause) {
        return Causes.cause("configured resource provider cannot be built: " + cause.message()
                           + " — refusing to boot: the node would otherwise start with that configuration"
                           + " unusable, either with resource provisioning silently disabled or with the"
                           + " placeholder text served as the value (#904)",
                            Option.some(cause))
                     .result();
    }

    private static ResourceProviderFacade noOpResourceProviderFacade() {
        return new ResourceProviderFacade() {
            private static final Cause NOT_CONFIGURED = Causes.cause("Resource provisioning not configured. Use AetherNodeConfig.withConfigProvider() to enable.");

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return NOT_CONFIGURED.promise();
            }

            @Override
            public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                return NOT_CONFIGURED.promise();
            }

            /// Deliberate no-op, not an inherited one (#892): resource provisioning is disabled on
            /// this path, so there is provably nothing for a release to close.
            @Override
            public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
    }

    /// #1777 (CTO ruling R2 / Q4): the bare `DHTClient` extension is the cluster's REPLICATED DHT at the committed
    /// `[replication]` factors — what idempotency and every other namespace resolve. The cache namespace's lower `[cache]`
    /// replication is a separate extension type ([CacheDhtClient]) that only the cache asks for. Registering the cache
    /// client as `DHTClient`, as before, put idempotency's dedup records at RF 1.
    @Contract
    static void registerDhtExtensions(SpiResourceProvider spi,
                                      DHTClient replicatedDhtClient,
                                      DHTClient cacheDhtClient) {
        spi.registerExtension(DHTClient.class, replicatedDhtClient);
        spi.registerExtension(CacheDhtClient.class, new CacheDhtClient(cacheDhtClient));
    }

    private static void registerRuntimeExtensions(SpiResourceProvider spi,
                                                  TopicSubscriptionRegistry topicSubscriptionRegistry,
                                                  SliceInvoker sliceInvoker,
                                                  DHTClient replicatedDhtClient,
                                                  DHTClient cacheDhtClient,
                                                  StorageInstance contentStorage,
                                                  KVStore<AetherKey, AetherValue> kvStore,
                                                  OperatorWarningSink operatorWarningSink) {
        spi.registerExtension(TopicSubscriptionRegistry.class, topicSubscriptionRegistry);
        spi.registerExtension(SliceInvoker.class, sliceInvoker);
        registerDhtExtensions(spi, replicatedDhtClient, cacheDhtClient);
        // #251 (#99 regression): ContentStoreFactory.provision() requires a StorageInstance extension.
        // Register a tiered content store so slice-facing ContentStore resources can provision.
        spi.registerExtension(StorageInstance.class, contentStorage);
        // #1040: the stream resource factories materialize a declared stream under its ENGINE KEY
        // rather than the bare `resources.toml` section name, which needs the deploy-time
        // alias->ResourceAddress bindings. Those live in the cluster KV-Store, which aether-stream
        // cannot reach, so the lookup is supplied here as an extension. Registered unconditionally:
        // absence is what a test/Forge runtime looks like, and on a real node the resolver must
        // always be present or a declared stream silently falls back to the bare spelling again.
        spi.registerExtension(StreamAddressResolver.class,
                              (sliceId, alias) -> Artifact.artifact(sliceId).flatMap(artifact -> BlueprintStreamAddresses.engineKeyFor(kvStore,
                                                                                                                                       artifact,
                                                                                                                                       alias)));
        // #1216: a bare pub/sub topic name is scoped to the owning BLUEPRINT, exactly as a stream
        // alias is. PublisherFactory holds only the publishing slice's id and cannot reach the
        // cluster KV-Store where `SliceTargetValue.owningBlueprint` lives, so the one hop from slice
        // identity to blueprint identity is supplied here. Registered unconditionally and for the
        // same reason as the resolver above: absence is what a test/minimal runtime looks like, and
        // on a real node the resolver must always be present or a co-deployed publisher and
        // subscriber fall back to two DIFFERENT slice-derived namespaces and silently never meet.
        spi.registerExtension(OwningBlueprintResolver.class, OwningBlueprintResolver.kvBacked(kvStore));
        // #1564: every stream, durable topic and durable entity declaration resolves its replication_factor /
        // confirmation_factor against the COMMITTED cluster config — the `[replication]` defaults and the desired
        // core count — read on each resolution, exactly as deploy validation reads it.
        spi.registerExtension(ReplicationContext.Source.class,
                              ClusterReplication.source(() -> kvStore.getTyped(AetherKey.ClusterConfigKey.CURRENT,
                                                                               AetherValue.ClusterConfigValue.class)));
        // #1564 / #1617 (R10): the replication warnings a stream, durable topic or durable entity raises when it
        // activates reach this node's cluster event log through its own operator-warning sink.
        spi.registerExtension(OperatorWarningSink.class, operatorWarningSink);
    }

    record unused() implements SliceRuntimeAssembly {}
}
