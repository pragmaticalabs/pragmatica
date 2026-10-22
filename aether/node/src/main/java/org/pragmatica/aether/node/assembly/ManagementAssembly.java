// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementServer;
import org.pragmatica.aether.api.ObservabilityConfigRegistry;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
import org.pragmatica.aether.deployment.cluster.SliceOwnershipQuery;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.http.TlsRotation;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.AetherNode.ResourceProviderSetup;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.slice.*;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.*;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.net.quic.QuicClusterNetwork;
import org.pragmatica.consensus.net.quic.QuicTlsProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.security.CertificateBundle;
import org.pragmatica.net.tcp.security.CertificateRenewalScheduler;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarningSink;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.node.AetherNode.LOG;
import static org.pragmatica.aether.node.AetherNode.findSelfAddress;
import static org.pragmatica.aether.node.assembly.DrainAssembly.requestDrainThroughFsm;


/// The management unit of the node assembly: the certificate renewal scheduler with the rotation of the QUIC, management
/// and app-HTTP listeners it drives, and the management server with its security validator. Moved out of `AetherNode`
/// without change; the root calls the phases in the order it always ran them.
public sealed interface ManagementAssembly {
    /// What `certRenewal` reads from the assembly built so far.
    record CertRenewalInputs(AetherNodeConfig config,
                             RabiaNode<KVCommand<AetherKey>> clusterNode,
                             OperatorWarningSink operatorWarningSink,
                             AppHttpServer appHttpServer,
                             AtomicReference<Option<ManagementServer>> managementServerRef) {}

    /// The certificate renewal scheduler, which rotates the cluster, management and app-HTTP listeners when a certificate renews.
    static Option<CertificateRenewalScheduler> certRenewal(CertRenewalInputs in) {
        var config = in.config();
        var clusterNode = in.clusterNode();
        var operatorWarningSink = in.operatorWarningSink();
        var appHttpServer = in.appHttpServer();
        var managementServerRef = in.managementServerRef();
        var certRenewalScheduler = createCertRenewalScheduler(config,
                                                              clusterNode,
                                                              appHttpServer,
                                                              managementServerRef::get,
                                                              operatorWarningSink);

        return certRenewalScheduler;
    }

    /// What `managementServer` reads from the assembly built so far.
    record ManagementServerInputs(AetherNodeConfig config,
                                  KVStore<AetherKey, AetherValue> kvStore,
                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                  Serializer serializer,
                                  Deserializer deserializer,
                                  AtomicReference<MembershipFsm> membershipFsmRef,
                                  AetherNode.ResourceProviderSetup resourceProviderSetup,
                                  LogLevelRegistry logLevelRegistry,
                                  InvocationTraceStore traceStore,
                                  ObservabilityConfigRegistry configRegistry,
                                  ScheduledTaskRegistry scheduledTaskRegistry,
                                  ScheduledTaskStateRegistry scheduledTaskStateRegistry,
                                  DrainCommandRegistry drainCommandRegistry,
                                  AlertManager alertManager,
                                  Option<DynamicConfigManager> dynamicConfigManager,
                                  OperatorWarningSink operatorWarningSink,
                                  SliceInvoker sliceInvoker,
                                  ScheduledTaskManager scheduledTaskManager,
                                  Option<EventLoopGroup> serverBossGroup,
                                  Option<EventLoopGroup> serverWorkerGroup,
                                  AtomicReference<Option<ManagementServer>> managementServerRef,
                                  EntityCheckpointDriver entityCheckpointDriver,
                                  AetherNode node,
                                  CompositeMeterRegistry deploymentMeters) {}

    /// The management security validator and the management server, published through the holder the route entries and the certificate rotation read.
    static ManagementServer managementServer(ManagementServerInputs in) {
        var config = in.config();
        var kvStore = in.kvStore();
        var clusterNode = in.clusterNode();
        var serializer = in.serializer();
        var deserializer = in.deserializer();
        var membershipFsmRef = in.membershipFsmRef();
        var resourceProviderSetup = in.resourceProviderSetup();
        var logLevelRegistry = in.logLevelRegistry();
        var traceStore = in.traceStore();
        var configRegistry = in.configRegistry();
        var scheduledTaskRegistry = in.scheduledTaskRegistry();
        var scheduledTaskStateRegistry = in.scheduledTaskStateRegistry();
        var drainCommandRegistry = in.drainCommandRegistry();
        var alertManager = in.alertManager();
        var dynamicConfigManager = in.dynamicConfigManager();
        var operatorWarningSink = in.operatorWarningSink();
        var sliceInvoker = in.sliceInvoker();
        var scheduledTaskManager = in.scheduledTaskManager();
        var serverBossGroup = in.serverBossGroup();
        var serverWorkerGroup = in.serverWorkerGroup();
        var managementServerRef = in.managementServerRef();
        var entityCheckpointDriver = in.entityCheckpointDriver();
        var node = in.node();
        var deploymentMeters = in.deploymentMeters();
        var mgmtSecurityEnabled = config.appHttp().managementSecurityEnabled();
        // #962: `[management] security_mode` decides whether the Management API authenticates and,
        // when absent, INHERITS `[app-http] security_mode`. Both planes check the same
        // `api-keys` and the same cluster bootstrap admin key. They authorize on
        // DIFFERENT axes though: app-HTTP route policies test the key's `roles`, this plane
        // tests its `authorization_role` (ADMIN > OPERATOR > VIEWER). The refusals name the
        // plane and the axis (ManagementServerImpl#denialDetail); the operator documentation
        // states the coupling where api-key mode is configured.
        //
        // #1121: `security_mode = "none"` leaves this plane FULLY OPEN. `ManagementServerImpl`
        // gates on `mgmtSecurityEnabled` and, when false, dispatches without consulting any
        // validator -- on the local path, the forwarded path, the websocket feeds and the Maven
        // push admission alike. An earlier version of this block installed
        // `denyUnlessPublicValidator` here and claimed non-public routes were refused unless a
        // bootstrap key was present; that validator was never consulted, so the claim was false
        // and the validator is gone. The validator below is built for the enabled case only.
        var configValidator = SecurityValidator.apiKeyValidator(config.appHttp().apiKeys());

        if (!mgmtSecurityEnabled) {
            LOG.warn("Management API on port {} is FULLY OPEN: its security mode is \"none\" ([management] security_mode, or inherited from"
                    + " [app-http] security_mode when that is absent), which disables authentication and"
                    + " authorization on the management plane, so ANY caller that can reach the port can run every"
                    + " operation, including cluster changes and artifact pushes. Use it only on a loopback or"
                    + " otherwise isolated listener; set [management] security_mode = \"api-key\" to protect this plane (#1121).",
                     config.managementPort());
        }
        // The OTHER credential-less shape, and until now it had no voice at all. Above is
        // `security_mode = "none"`. This is security ON with an EMPTY key set — which is what
        // the published image now boots with, since `docker/aether-node/aether.toml` no longer
        // bakes a key. The node is fail-closed and that is intended, so this is not a
        // misconfiguration warning; it states WHICH credential is the only way in, because the
        // alternative is an operator reading 401s with nothing in the log explaining them.
        //
        // Deliberately not silent-because-intended: a default applied when nothing was
        // configured has to be loud, or it becomes the fail-open nobody notices. The precedent
        // is this repo's own log-and-drop-plus-caller-default, which turned a fail-closed
        // encryption refusal into a green boot with plaintext.
        if (mgmtSecurityEnabled && config.appHttp().apiKeys().isEmpty()) {
            LOG.warn("Management API on port {} has NO key declared in configuration. This is the"
                    + " FAIL-CLOSED default: every non-public route is REFUSED until a credential exists."
                    + " The cluster bootstrap admin key (printed once at formation, derived from the"
                    + " cluster secret) is the only credential this node will accept; it is enumerable and"
                    + " revocable via /api/v1/cluster/keys. To pre-provision one instead, set"
                    + " AETHER_API_KEYS=<key>:<name>:<roles>:<ROLE> or an [app-http.api-keys.<key>] table.",
                     config.managementPort());
        }

        var mgmtSecurityValidator = SecurityValidator.kvStoreAwareValidator(configValidator, () -> node.kvStore());
        var managementServer = ManagementServer.managementServer(config.managementPort(),
                                                                 () -> node,
                                                                 entityCheckpointDriver,
                                                                 alertManager,
                                                                 configRegistry,
                                                                 traceStore,
                                                                 logLevelRegistry,
                                                                 dynamicConfigManager,
                                                                 scheduledTaskRegistry,
                                                                 scheduledTaskManager,
                                                                 sliceInvoker,
                                                                 scheduledTaskStateRegistry,
                                                                 config.tls(),
                                                                 mgmtSecurityValidator,
                                                                 mgmtSecurityEnabled,
                                                                 () -> config.appHttp()
                                                                             .apiKeys(),
                                                                 serverBossGroup,
                                                                 serverWorkerGroup,
                                                                 config.managementHttpProtocol(),
                                                                 config.timeouts().forwarding(),
                                                                 Option.some(clusterNode.network()),
                                                                 Option.some(serializer),
                                                                 Option.some(deserializer),
                                                                 target -> requestDrainThroughFsm(drainCommandRegistry,
                                                                                                  membershipFsmRef,
                                                                                                  target),
                                                                 drainCommandRegistry::drainTargets,

        // #1720: operator drain and shutdown honour the slice minAvailable floor,
        // as the automatic drain does (same KV-backed guard, same counting rules).
        NodeLifecycleRoutes.SliceFloor.sliceFloor(SliceOwnershipQuery.minAvailableDrainViolations(kvStore),
                                                  operatorWarningSink));

        managementServer.setOperatorWarningSink(operatorWarningSink);
        managementServerRef.set(Option.some(managementServer));
        deploymentMeters.add(managementServer.meterRegistry());
        // #278: expose the node's real MeterRegistry to slice-facing resource
        // provisioning so MetricsInterceptorFactory records into the SAME
        // registry the Management API scrapes, instead of each interceptor
        // fabricating its own disconnected one.
        resourceProviderSetup.spiProvider()
                             .onPresent(spi -> spi.registerExtension(MeterRegistry.class,
                                                                     managementServer.meterRegistry()));

        return managementServer;
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Option<CertificateRenewalScheduler> createCertRenewalScheduler(AetherNodeConfig config,
                                                                                  RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                  AppHttpServer appHttpServer,
                                                                                  Supplier<Option<ManagementServer>> managementServerSupplier,
                                                                                  OperatorWarningSink warningSink) {
        return config.certificateProvider()
                     .flatMap(provider -> buildCertRenewalScheduler(config,
                                                                    provider,
                                                                    clusterNode,
                                                                    appHttpServer,
                                                                    managementServerSupplier,
                                                                    warningSink));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Option<CertificateRenewalScheduler> buildCertRenewalScheduler(AetherNodeConfig config,
                                                                                 org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                                 RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                                 AppHttpServer appHttpServer,
                                                                                 Supplier<Option<ManagementServer>> managementServerSupplier,
                                                                                 OperatorWarningSink warningSink) {
        var nodeId = config.self().id();
        var hostname = resolveHostname(config);

        return provider.issueCertificate(nodeId, hostname)
                       .map(bundle -> createSchedulerFromBundle(provider,
                                                                nodeId,
                                                                hostname,
                                                                bundle,
                                                                clusterNode,
                                                                appHttpServer,
                                                                managementServerSupplier,
                                                                warningSink))
                       .option();
    }

    private static CertificateRenewalScheduler createSchedulerFromBundle(org.pragmatica.net.tcp.security.CertificateProvider provider,
                                                                         String nodeId,
                                                                         String hostname,
                                                                         CertificateBundle bundle,
                                                                         RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                                         AppHttpServer appHttpServer,
                                                                         Supplier<Option<ManagementServer>> managementServerSupplier,
                                                                         OperatorWarningSink warningSink) {
        var renewalAlarm = TlsRotation.clusterRenewal();

        renewalAlarm.useSink(warningSink);

        return CertificateRenewalScheduler.certificateRenewalScheduler(provider,
                                                                       nodeId,
                                                                       hostname,
                                                                       newBundle -> onCertificateRenewed(newBundle,
                                                                                                         clusterNode,
                                                                                                         appHttpServer,
                                                                                                         managementServerSupplier,
                                                                                                         renewalAlarm),
                                                                       bundle.notAfter());
    }

    @SuppressWarnings({"JBCT-PAT-01", "JBCT-RET-01"})
    static void onCertificateRenewed(CertificateBundle newBundle,
                                     RabiaNode<KVCommand<AetherKey>> clusterNode,
                                     AppHttpServer appHttpServer,
                                     Supplier<Option<ManagementServer>> managementServerSupplier,
                                     TlsRotation renewalAlarm) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        log.info("Certificate renewed, valid until {}", newBundle.notAfter());
        Result.all(QuicSslContextFactory.createServerFromBundle(newBundle,
                                                                ClientAuthPolicy.REQUIRED,
                                                                QuicTlsProvider.CLUSTER_PROTOCOL),
                   QuicSslContextFactory.createClientFromBundle(newBundle, QuicTlsProvider.CLUSTER_PROTOCOL))
              .id()
              .onSuccess(tuple -> {
                             renewalAlarm.applied();
                             triggerCertRotation(clusterNode,
                                                 tuple.first(),
                                                 tuple.last(),
                                                 newBundle,
                                                 appHttpServer,
                                                 managementServerSupplier);
                         })
              .onFailure(cause -> {
                             log.error("Failed to build SSL contexts from renewed certificate: {}",
                                       cause.message());
                             renewalAlarm.renewalRefused(cause);
                         });
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static void triggerCertRotation(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                            io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                            io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                            CertificateBundle newBundle,
                                            AppHttpServer appHttpServer,
                                            Supplier<Option<ManagementServer>> managementServerSupplier) {
        var log = LoggerFactory.getLogger(AetherNode.class);

        rotateQuicNetwork(clusterNode, serverSsl, clientSsl, log);
        managementServerSupplier.get().onPresent(mgmt -> rotateManagementServer(mgmt, newBundle, log));
        rotateAppHttpServer(appHttpServer, newBundle, log);
    }

    private static void rotateQuicNetwork(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                          io.netty.handler.codec.quic.QuicSslContext serverSsl,
                                          io.netty.handler.codec.quic.QuicSslContext clientSsl,
                                          Logger log) {
        if (clusterNode.network() instanceof QuicClusterNetwork quicNetwork) {
            quicNetwork.rotateCertificate(serverSsl, clientSsl)
                       .onSuccess(_ -> log.info("QUIC certificate rotation complete"))
                       .onFailure(cause -> log.error("QUIC certificate rotation failed: {}",
                                                     cause.message()));
        } else {
            log.warn("QUIC certificate rotation skipped: network is not QUIC-based");
        }
    }

    private static void rotateManagementServer(ManagementServer mgmt, CertificateBundle newBundle, Logger log) {
        mgmt.rotateCertificate(newBundle)
            .onSuccess(_ -> log.info("Management server certificate rotation complete"))
            .onFailure(cause -> log.error("Management server certificate rotation failed: {}",
                                          cause.message()));
    }

    private static void rotateAppHttpServer(AppHttpServer appHttpServer, CertificateBundle newBundle, Logger log) {
        appHttpServer.rotateCertificate(newBundle)
                     .onSuccess(_ -> log.info("App HTTP server certificate rotation complete"))
                     .onFailure(cause -> log.error("App HTTP server certificate rotation failed: {}",
                                                   cause.message()));
    }

    /// #517: same invariant as [#findSelfAddress]; `localhost` was a dead placeholder, never a default.
    private static String resolveHostname(AetherNodeConfig config) {
        return findSelfAddress(config).host();
    }

    record unused() implements ManagementAssembly {}
}
