// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.api.routes.StatusRoutes;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StorageEncryptionConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.storage.EncryptingStorageTier;
import org.pragmatica.storage.EncryptionError;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #858: exercises the post-formation DHT marker check on a REAL, self-forming single-node cluster
/// through `AetherNode.start()` -- the boundary the #858 fix moved the check across (previously
/// `createAll`, called from the constructor, awaited the marker check for up to
/// [StorageFactory#DHT_MARKER_TIMEOUT] before the `DHTClient` could route).
///
/// Four claims, each with its own test:
///
///   1. Construction stays fast even when the 'artifacts' instance carries a DHT tier with no
///      keyring -- the branch that used to block. "start() succeeds" is not evidence of this by
///      itself (a slow-but-eventually-successful construction would pass such an assertion); the
///      timing bound is the actual claim, and its discriminating power is demonstrated in the PR by
///      a mutation probe: reverting `StorageFactory.java`, `AetherNode.java` and `DhtStorageTier.java`
///      to their pre-#858 shapes (`e01e32dad`) and re-running this test alone shows it fail at ~30 s.
///   2. With a keyring configured on the DHT-backed 'artifacts' instance, `start()` performs the
///      deferred write for real: the marker is read back directly off the node's own `DHTClient`
///      (`ManageableNode.dhtClient()` -- a pre-existing public accessor, not a new test seam; see the
///      class-level note on why no new production surface was needed) and is absent before formation,
///      present with the active key id as its value after `start()` resolves.
///   3. #1052: a definite refusal stays fatal. A marker seeded into the node's own DHT before formation,
///      with no keyring configured, fails `start()` with `EncryptedTierRequiresKeyring`. That failure is
///      what `Main#exitWithError` turns into exit code 1, and `Main` is unchanged. The same test pins that
///      periodic work arms without waiting on the check.
///   4. #1052: readiness waits on admission. A node whose DHT tier is not admitted never reaches
///      lifecycle `ACTIVE`, even though `start()` resolved. A control node in the same test, admitted
///      normally, does reach it.
///   5. #1052 fix round 2 (SF-1): `/health/ready`'s `dht-admission` component reads THIS node's
///      `storageSetups()`. Pinned on a real node because `StatusRoutesDhtAdmissionTest` stubs
///      `storageSetups -> Map.of()`, and a stub that hands the builder the empty case cannot see the
///      builder ignoring its input (a `List.of()` at the wiring stayed green under round 1's mutation
///      matrix). Before `start()` the 'artifacts' gate is unresolved -- the same state the builder sees
///      while the check retries after formation -- so the component reads DOWN naming `artifacts`; once
///      `start()` has admitted the tier it reads UP. The retrying state itself is not inducible on a
///      single-node boot (below), so "before start()" is its real-node stand-in.
///
/// A genuine cross-boot scenario ("marker written by a PRIOR boot, no keyring THIS boot -> refusal")
/// stays infeasible as a real-boot test here: no seam exists across any of the four
/// `AetherNode.aetherNode(...)` factory overloads to share one node's in-memory DHT store with a
/// second, separately-constructed node. Claim 3 reaches the same refusal branch by seeding the marker
/// into this node's own store before `start()`, through the same pre-existing `dhtClient()` accessor. The
/// cross-boot shape stays covered at the `StorageFactory` unit level by
/// `StorageFactoryEncryptionTest#verifyDhtMarker_fails_whenDhtCarriesEncryptionMarker_andDiskUnavailable_andNoKeyringSupplied`.
/// The transient (retry) path cannot be induced on a real single-node boot, whose DHT always answers. It
/// is pinned at the `StorageFactory` level by `StorageFactoryDhtMarkerRetryTest`.
class AetherNodeDhtMarkerPostFormationBootTest {
    private static final String SECRET_PATH = "path/to/k1";
    private static final String ACTIVE_KEY_ID = "k1";
    private static final String VALID_AES256_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final TimeSpan START_BOUND = timeSpan(15).seconds();
    private static final TimeSpan MARKER_READ_BOUND = timeSpan(5).seconds();
    private static final long CONSTRUCTION_BOUND_MS = 5_000;
    private static final long ACTIVE_BOUND_MS = 30_000;
    private static final long MIN_NOT_ACTIVE_WINDOW_MS = 5_000;
    private static final Cause NOT_ADMITTED = Causes.cause("test: DHT tier not admitted");

    @TempDir
    Path tempDir;

    private AetherNode node;
    private AetherNode controlNode;

    @AfterEach
    void tearDown() {
        if (controlNode != null) {
            controlNode.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void construction_staysUnderFiveSeconds_whenArtifactsInstanceHasDhtTierButNoKeyring() {
        var constructStarted = System.nanoTime();

        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none(), tempDir), () -> {})
                          .onFailure(cause -> fail("construction must not touch the DHT any more (#858) - " + cause.message()))
                          .unwrap();

        assertThat(elapsedMs(constructStarted)).as("construction must not block on the DHT marker check (#858); "
                                                    + "the pre-#858 code awaited the full 30 s marker timeout here "
                                                    + "-- see the PR's mutation-probe evidence for a run against "
                                                    + "the reverted hunk")
                                                .isLessThan(CONSTRUCTION_BOUND_MS);
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_writesDhtMarkerPostFormation_absentBeforePresentAfter_readThroughNodesOwnDhtClient() {
        var provider = (SecretsProvider) path -> Promise.success(Map.of(SECRET_PATH, VALID_AES256_KEY).get(path));
        var environment = Option.some(EnvironmentIntegration.environmentIntegration(Option.none(), Option.some(provider), Option.none()));
        var encryption = Option.some(StorageEncryptionConfig.storageEncryptionConfig(Map.of(ACTIVE_KEY_ID, "${secrets:" + SECRET_PATH + "}"),
                                                                                      ACTIVE_KEY_ID,
                                                                                      false));
        var artifactsConfig = new StorageConfig(8L * 1024 * 1024, 64L * 1024 * 1024,
                                                tempDir.resolve("artifacts-disk").toString(),
                                                tempDir.resolve("snapshots").toString(),
                                                1000, "60s", 5, "", true);

        node = AetherNode.aetherNode(minimalConfig(environment, encryption, artifactsConfig), () -> {})
                          .onFailure(cause -> fail("construction must not touch the DHT any more (#858), even with "
                                                  + "a keyring configured - " + cause.message()))
                          .unwrap();

        var check = node.storageSetups()
                        .get("artifacts")
                        .dhtMarkerCheck()
                        .unwrap();
        var client = node.dhtClient().unwrap();
        var markerKey = check.dhtKeyPrefix() + "/" + EncryptingStorageTier.MARKER_FILE_NAME;

        var beforeMarker = client.get(markerKey).await(MARKER_READ_BOUND).unwrap();
        assertThat(beforeMarker.isPresent()).as("the marker must not exist before cluster formation -- "
                                                + "verifyDhtMarker runs post-formation, from start(), never during "
                                                + "construction")
                                             .isFalse();

        node.start().await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed and write the DHT marker post-formation - " + cause.message()));

        var afterMarker = client.get(markerKey).await(MARKER_READ_BOUND).unwrap();
        assertThat(afterMarker.isPresent()).as("start() must have written the DHT marker for the encrypted "
                                              + "'artifacts' instance once cluster formation resolved")
                                            .isTrue();
        assertThat(new String(afterMarker.unwrap(), StandardCharsets.UTF_8)).isEqualTo(ACTIVE_KEY_ID);
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void start_failsWithEncryptedTierRequiresKeyring_whenDhtMarkerPresentAndNoKeyring() {
        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none(), tempDir), () -> {})
                          .onFailure(cause -> fail("construction must succeed - " + cause.message()))
                          .unwrap();

        var check = node.storageSetups()
                        .get("artifacts")
                        .dhtMarkerCheck()
                        .unwrap();
        var markerKey = check.dhtKeyPrefix() + "/" + EncryptingStorageTier.MARKER_FILE_NAME;

        node.dhtClient()
            .unwrap()
            .put(markerKey, ACTIVE_KEY_ID.getBytes(StandardCharsets.UTF_8))
            .await(MARKER_READ_BOUND)
            .onFailure(cause -> fail("PRECONDITION: seeding the marker into the node's own DHT failed - " + cause.message()));

        node.start()
            .await(START_BOUND)
            .onSuccess(_ -> fail("a DHT marker present with no keyring must still fail start() -- #858's safety "
                                 + "property, which #1052 keeps; Main#exitWithError turns this failure into exit 1"))
            .onFailure(cause -> assertThat(cause).as("the refusal must end start() with its own cause; a CoreError.Timeout "
                                                     + "here means the definite mismatch was being retried")
                                                 .isInstanceOf(EncryptionError.EncryptedTierRequiresKeyring.class));

        assertThat(node.nodeLifecycle().currentState()).as("a node whose DHT tier was refused must never report ACTIVE")
                                                      .isNotEqualTo(NodeState.ACTIVE);
        assertThat(node.periodicTasks().armedCount()).as("#1052: periodic work arms once formation resolves and must not "
                                                         + "wait on the marker check -- a replacement still retrying can "
                                                         + "already be leader, and its leader ticks must run")
                                                     .isPositive();
    }

    @Test
    @Timeout(value = 150, unit = SECONDS)
    void start_neverReportsActive_whileADhtTierIsNotAdmitted_whileAdmittedControlDoes() throws InterruptedException {
        controlNode = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none(), tempDir), () -> {})
                                 .onFailure(cause -> fail("control construction must succeed - " + cause.message()))
                                 .unwrap();
        controlNode.start()
                   .await(START_BOUND)
                   .onFailure(cause -> fail("control start() must succeed - " + cause.message()));

        var controlActiveMs = awaitActive(controlNode);

        controlNode.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        controlNode = null;

        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none(), tempDir), () -> {})
                          .onFailure(cause -> fail("construction must succeed - " + cause.message()))
                          .unwrap();
        // Stands in for a marker check that has not admitted the tier: the gate is first-writer-wins, so
        // start()'s own (successful) check can no longer admit it. Pending and refused are the same case
        // for readiness -- neither is success.
        node.storageSetups()
            .get("artifacts")
            .dhtMarkerCheck()
            .unwrap()
            .readGate()
            .resolve(NOT_ADMITTED.result());

        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() itself must succeed: this node's DHT holds no marker - " + cause.message()));

        var window = Math.max(MIN_NOT_ACTIVE_WINDOW_MS, 3 * controlActiveMs);
        var deadline = System.currentTimeMillis() + window;

        while (System.currentTimeMillis() < deadline) {
            assertThat(node.nodeLifecycle().currentState()).as("a node whose DHT tier is not admitted must not report ACTIVE "
                                                              + "(the admitted control reached it in %d ms; watched %d ms)",
                                                              controlActiveMs,
                                                              window)
                                                          .isNotEqualTo(NodeState.ACTIVE);
            Thread.sleep(50);
        }
    }

    @Test
    @Timeout(value = 60, unit = SECONDS)
    void readiness_reportsDhtAdmissionDownNamingArtifacts_beforeStart_andUpOnceAdmitted() {
        node = AetherNode.aetherNode(minimalConfig(Option.none(), Option.none(), tempDir), () -> {})
                          .onFailure(cause -> fail("construction must succeed - " + cause.message()))
                          .unwrap();
        var routes = StatusRoutes.statusRoutes(() -> node, node::appHttpServer);

        assertThat(node.storageSetups().get("artifacts").dhtAdmissionPending()).as("PRECONDITION: the real node's 'artifacts' "
                                                                                    + "marker check is pending before start()")
                                                                                .isTrue();

        var before = dhtAdmissionComponent(routes);
        assertThat(before.status()).as("/health/ready must read the REAL node's pending check as DOWN -- an empty or "
                                       + "stubbed setups map at the wiring would read UP here")
                                   .isEqualTo("DOWN");
        assertThat(before.detail()).as("the component must name the instance holding the node not-ready")
                                   .contains("artifacts");

        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: this node's DHT holds no marker - " + cause.message()));

        var after = dhtAdmissionComponent(routes);
        assertThat(after.status()).as("once start() admitted the tier the same wiring must read UP (detail: %s)",
                                      after.detail())
                                  .isEqualTo("UP");
    }

    private static ComponentHealth dhtAdmissionComponent(StatusRoutes routes) {
        return routes.buildReadinessResponse()
                     .components()
                     .stream()
                     .filter(component -> component.name().equals("dht-admission"))
                     .findFirst()
                     .orElseGet(() -> fail("/health/ready must carry the dht-admission component"));
    }

    private static long awaitActive(AetherNode candidate) throws InterruptedException {
        var started = System.nanoTime();
        var deadline = System.currentTimeMillis() + ACTIVE_BOUND_MS;

        while (candidate.nodeLifecycle().currentState() != NodeState.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(candidate.nodeLifecycle().currentState()).as("CONTROL: an admitted single-node boot must reach ACTIVE "
                                                               + "within %d ms, or the not-ACTIVE assertion proves nothing",
                                                               ACTIVE_BOUND_MS)
                                                           .isEqualTo(NodeState.ACTIVE);

        return elapsedMs(started);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /// #1276: the synthesized-artifacts shape, rooted in the test's `@TempDir` instead of the machine-global
    /// `/data/aether/...` default. The explicit `artifacts` instance is encrypted exactly when a node-wide
    /// keyring is configured, which is what the synthesized one would have been.
    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                  Option<StorageEncryptionConfig> storageEncryption,
                                                  Path storageRoot) {
        return minimalConfig(environment,
                             storageEncryption,
                             HermeticStorage.storageConfigAt(HermeticStorage.uncreatableRootIn(storageRoot),
                                                             storageEncryption.isPresent()));
    }

    private static AetherNodeConfig minimalConfig(Option<EnvironmentIntegration> environment,
                                                  Option<StorageEncryptionConfig> storageEncryption,
                                                  StorageConfig artifactsConfig) {
        var self = NodeId.nodeId("dht-marker-post-formation-" + UUID.randomUUID()).unwrap();
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", freePort()).unwrap());

        return AetherNodeConfig.builder()
                                .self(self).coreNodes(List.of(selfInfo)).managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                                .sliceConfig(SliceConfig.sliceConfig()).artifactRepo(DHTConfig.FULL).coreMax(1)
                                .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none()).quicTls(TlsConfig.selfSignedMutual())
                                .certificateProvider(Option.none()).configProvider(Option.none()).environment(environment)
                                .managementHttpProtocol(HttpProtocol.H1)
                                .storageConfig(Map.of("artifacts", artifactsConfig))
                                .build().withStorageEncryption(storageEncryption);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
