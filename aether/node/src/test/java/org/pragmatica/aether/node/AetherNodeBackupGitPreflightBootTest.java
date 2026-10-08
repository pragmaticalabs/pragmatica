// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.BackupConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.node.backup.BackupPreflight;
import org.pragmatica.aether.node.backup.BackupPreflight.GitProbeTimedOut;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #2007, through the REAL boot path (`AetherNode.aetherNode`): the `[backup]` git preflight runs when `[backup]` is enabled, BEFORE
/// the cluster-events limits are read and before any port binds, and it is not run at all when `[backup]` is off. The probe is the
/// boot's git seam, so "git missing" and "git hangs" are stood in without touching the JVM's PATH. A refusal that comes from
/// `CLUSTER_EVENTS_MAX_COUNT` (a map-supplied environment, no node is built) is the marker that the boot got PAST the preflight.
class AetherNodeBackupGitPreflightBootTest {
    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void backupEnabled_gitUnavailable_refusesTheBoot_beforeAnythingBinds() {
        var probes = new AtomicInteger();
        var config = config(true);
        var port = port(config);

        var result = boot(config, Map.of(), () -> {
            probes.incrementAndGet();
            return Causes.cause("[backup] is enabled but git cannot be run (stand-in)").result();
        });

        assertRefused(result, "git cannot be run (stand-in)");
        assertThat(probes.get()).as("the probe ran exactly once").isEqualTo(1);
        assertThat(isFree(port)).as("the refusal comes before anything binds the cluster port " + port).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void backupEnabled_gitProbeTimesOut_refusesTheBoot_withTheTypedTimeoutCause() {
        var result = boot(config(true), Map.of(),
                          () -> BackupPreflight.requireGit(List.of("sleep", "30"), TimeSpan.timeSpan(300).millis()));

        result.onSuccess(booted -> {
            node = booted;
            fail("#2007: a git that times out must refuse the boot; it booted");
        }).onFailure(cause -> assertThat(cause).isInstanceOf(GitProbeTimedOut.class));
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void backupEnabled_gitFine_theBootGoesOnPastThePreflight() {
        var probes = new AtomicInteger();
        var result = boot(config(true), Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, "not-a-number"), () -> {
            probes.incrementAndGet();
            return Result.success(Unit.unit());
        });

        assertRefused(result, ClusterEventsLimits.MAX_COUNT_VARIABLE);
        assertThat(probes.get()).as("the probe was consulted before the limits were read").isEqualTo(1);
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void backupOff_noProbe_theBootGoesOnToTheLaterChecks() {
        var probes = new AtomicInteger();
        var result = boot(config(false), Map.of(ClusterEventsLimits.MAX_COUNT_VARIABLE, "not-a-number"), () -> {
            probes.incrementAndGet();
            return Causes.cause("must not be asked").result();
        });

        assertRefused(result, ClusterEventsLimits.MAX_COUNT_VARIABLE);
        assertThat(probes.get()).as("[backup] off: git is never probed").isZero();
    }

    private void assertRefused(Result<AetherNode> result, String fragment) {
        result.onSuccess(booted -> {
            node = booted;
            fail("boot must have been refused (" + fragment + "); it booted");
        }).onFailure(cause -> assertThat(cause.message()).contains(fragment));
    }

    private static Result<AetherNode> boot(AetherNodeConfig config,
                                           Map<String, String> environment,
                                           java.util.function.Supplier<Result<Unit>> probe) {
        return AetherNode.aetherNode(config,
                                     MessageRouter.DelegateRouter.delegate(),
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {},
                                     () -> {},
                                     () -> {},
                                     variable -> Option.option(environment.get(variable)),
                                     probe);
    }

    private AetherNodeConfig config(boolean backupEnabled) {
        var self = NodeId.nodeId("backup-git-preflight-boot-test").unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self)
                                .coreNodes(List.of(selfInfo))
                                .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig())
                                .artifactRepo(DHTConfig.FULL)
                                .coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig())
                                .tls(Option.none())
                                .quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none())
                                .configProvider(Option.some(ConfigurationProvider.builder().build()))
                                .environment(Option.none())
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(HermeticStorage.nodeStorageIn(tempDir, false))
                                .backupConfig(BackupConfig.backupConfig(backupEnabled,
                                                                        tempDir.resolve("backups").toString(),
                                                                        "",
                                                                        BackupConfig.RestoreMode.AUTO))
                                .build();
    }

    private static int port(AetherNodeConfig config) {
        return config.topology().coreNodes().getFirst().address().port();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static boolean isFree(int port) {
        try (var tcp = new ServerSocket(port); var udp = new DatagramSocket(port)) {
            return tcp.getLocalPort() == port && udp.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }
}
