// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #962: pins the `AetherNode` WIRING of `[management] security_mode` into the Management API listener. The config
/// tests prove the resolved flag, `ManagementServerSecurityCouplingTest` proves the server obeys the flag handed to it;
/// neither reddens when `AetherNode` hands the server the app plane's flag instead of the management plane's. A real
/// single-node boot with the two planes set to OPPOSITE modes, probed over HTTP on the live management port: the
/// answer can only follow the management plane's own mode.
class ManagementSecurityModeWiringBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final String PROBE_PATH = "/api/v1/no-such-route";
    private static final String CONFIGURED_KEY = "k-962-configured-key";

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void managementNone_servesAnonymousRequests_whileTheAppPlaneIsApiKey() throws Exception {
        var port = boot(appHttp(SecurityMode.API_KEY).withManagementSecurityMode(Option.some(SecurityMode.NONE)));

        assertThat(probe(port)).as("management plane is open: dispatch reached, not refused")
                               .isNotIn(401, 403);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void managementApiKey_refusesAnonymousRequests_whileTheAppPlaneIsNone() throws Exception {
        var port = boot(appHttp(SecurityMode.NONE).withManagementSecurityMode(Option.some(SecurityMode.API_KEY)));

        assertThat(probe(port)).as("management plane is armed: anonymous request refused")
                               .isEqualTo(401);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void managementAbsent_inheritsTheAppPlane() throws Exception {
        var port = boot(appHttp(SecurityMode.NONE));

        assertThat(probe(port)).as("inherited none: management plane open")
                               .isNotIn(401, 403);
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void configuredKey_authenticatesOnTheArmedManagementPlane() throws Exception {
        var port = boot(appHttp(SecurityMode.API_KEY, Map.of(CONFIGURED_KEY, ApiKeyEntry.apiKeyEntry("ops", Set.of()))));

        assertThat(probe(port, Option.some(CONFIGURED_KEY))).as("the key declared in [app-http.api-keys] is accepted by the management plane")
                                                           .isNotIn(401, 403);
        assertThat(probe(port, Option.some("not-" + CONFIGURED_KEY))).as("control: an unknown key is refused")
                                                                     .isIn(401, 403);
        assertThat(probe(port)).as("control: no key is refused")
                               .isEqualTo(401);
    }

    private static AppHttpConfig appHttp(SecurityMode mode) {
        return appHttp(mode, Map.of());
    }

    private static AppHttpConfig appHttp(SecurityMode mode, Map<String, ApiKeyEntry> apiKeys) {
        return AppHttpConfig.appHttpConfig(false,
                                           AppHttpConfig.DEFAULT_APP_HTTP_PORT,
                                           apiKeys,
                                           AppHttpConfig.DEFAULT_MAX_REQUEST_SIZE,
                                           mode,
                                           Option.empty(),
                                           HttpProtocol.H1)
                            .unwrap();
    }

    private int boot(AppHttpConfig appHttp) {
        var self = NodeId.nodeId("mgmt-security-wiring-boot-" + UUID.randomUUID()).unwrap();
        var managementPort = ClusterTestPorts.freeTcpAndUdpPort();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir, managementPort, appHttp), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));
        return managementPort;
    }

    private static int probe(int managementPort) throws Exception {
        return probe(managementPort, Option.none());
    }

    private static int probe(int managementPort, Option<String> apiKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + PROBE_PATH))
                                 .GET();

        apiKey.onPresent(key -> builder.header("X-API-Key", key));

        var request = builder.build();

        return HttpClient.newHttpClient()
                         .send(request, HttpResponse.BodyHandlers.discarding())
                         .statusCode();
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot, int managementPort, AppHttpConfig appHttp) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(managementPort)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(appHttp)
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}
