package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for artifact repository distributed operations.
///
///
/// Tests cover:
///
///   - Artifact metadata availability after upload
///   - Artifact resolution from non-leader nodes
///   - Artifact download content integrity
///   - Artifact survival after single node failure
///   - Artifact survival after leader failover
///   - Maven metadata listing uploaded versions
///
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order. Artifacts persist in DHT, no cleanup needed between tests.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class ArtifactRepositoryE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_GROUP_PATH = "org/pragmatica-lite/aether/test";
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.15.1");
    private static final String TEST_ARTIFACT_ID = "echo-slice-echo-service";

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration RECOVERY_TIMEOUT = adapt(timeSpan(90).seconds().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();

    private static AetherCluster cluster;
    private static byte[] localArtifactBytes;
    private static String localArtifactSha1;

    @BeforeAll
    static void createCluster() throws Exception {
        System.out.println("[DEBUG] Creating cluster...");
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        System.out.println("[DEBUG] Awaiting quorum...");
        cluster.awaitQuorum();
        System.out.println("[DEBUG] Awaiting all healthy...");
        cluster.awaitAllHealthy();
        System.out.println("[DEBUG] Awaiting leader election...");
        cluster.awaitLeader();
        System.out.println("[DEBUG] Uploading test artifacts to DHT...");
        cluster.uploadTestArtifacts();
        System.out.println("[DEBUG] Cluster ready for tests");

        var jarPath = Path.of(System.getProperty("user.home"), ".m2", "repository",
            TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION,
            TEST_ARTIFACT_ID + "-" + TEST_ARTIFACT_VERSION + ".jar");
        localArtifactBytes = Files.readAllBytes(jarPath);
        localArtifactSha1 = sha1Hex(localArtifactBytes);
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void ensureClusterHealthy() {
        // Restore any stopped nodes from previous tests
        cluster.nodes().stream()
            .filter(n -> !n.isRunning())
            .forEach(n -> {
                System.out.println("[SETUP] Restoring stopped node: " + n.nodeId());
                n.start();
            });

        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();

        // Re-upload test artifacts to replenish replicas after node restarts
        System.out.println("[SETUP] Re-uploading test artifacts to ensure replica coverage");
        cluster.uploadTestArtifacts();
    }

    @Test
    @Order(1)
    void uploadArtifact_metadataAvailable() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var info = leader.getArtifactInfo(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);
        System.out.println("[TEST] Artifact info: " + info);

        assertThat(info).doesNotContain("\"error\"");
        assertThat(info).contains("\"size\"");
        assertThat(info).contains("\"chunkCount\"");
        assertThat(info).contains("\"md5\"");
        assertThat(info).contains("\"sha1\"");
        assertThat(info).contains("\"deployedAt\"");
        assertThat(info).contains(String.valueOf(localArtifactBytes.length));
    }

    @Test
    @Order(2)
    void uploadArtifact_resolvableFromDifferentNode() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        // Find a non-leader node
        var otherNode = cluster.nodes().stream()
            .filter(n -> n.isRunning() && !n.nodeId().equals(leader.nodeId()))
            .findFirst()
            .orElseThrow();

        var leaderInfo = leader.getArtifactInfo(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);
        var otherInfo = otherNode.getArtifactInfo(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);

        System.out.println("[TEST] Leader info: " + leaderInfo);
        System.out.println("[TEST] Other node info: " + otherInfo);

        assertThat(otherInfo).doesNotContain("\"error\"");
        assertThat(otherInfo).contains("\"size\"");
        assertThat(otherInfo).contains("\"sha1\"");
    }

    @Test
    @Order(3)
    void downloadArtifact_contentMatchesUpload() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var otherNode = cluster.nodes().stream()
            .filter(n -> n.isRunning() && !n.nodeId().equals(leader.nodeId()))
            .findFirst()
            .orElseThrow();

        var downloaded = otherNode.downloadArtifact(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);
        System.out.println("[TEST] Downloaded " + downloaded.length + " bytes from " + otherNode.nodeId());

        assertThat(downloaded.length).isEqualTo(localArtifactBytes.length);
        assertThat(sha1Hex(downloaded)).isEqualTo(localArtifactSha1);
    }

    @Test
    @Order(4)
    void artifactSurvives_singleNodeFailure() {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        // Kill a non-leader node
        var victimId = cluster.nodes().stream()
            .filter(n -> n.isRunning() && !n.nodeId().equals(leader.nodeId()))
            .map(AetherNodeContainer::nodeId)
            .findFirst()
            .orElseThrow();

        cluster.killNode(victimId);
        cluster.awaitQuorum();

        var downloaded = cluster.anyNode().downloadArtifact(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);
        System.out.println("[TEST] Downloaded " + downloaded.length + " bytes after killing " + victimId);

        assertThat(downloaded.length).isEqualTo(localArtifactBytes.length);
        assertThat(sha1Hex(downloaded)).isEqualTo(localArtifactSha1);

        // Restore node
        cluster.node(victimId).start();
        cluster.awaitQuorum();
    }

    @Test
    @Order(5)
    void artifactSurvives_leaderFailover() {
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var originalLeaderId = originalLeader.nodeId();

        cluster.killNode(originalLeaderId);

        // Wait for new leader
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var newLeader = cluster.leader();
                   return newLeader.isPresent() &&
                          !newLeader.toResult(Causes.cause("")).unwrap().nodeId().equals(originalLeaderId);
               });

        // DHT ring needs time to process NodeRemoved event and update routing
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .untilAsserted(() -> {
                   var downloaded = cluster.anyNode().downloadArtifact(TEST_GROUP_PATH, TEST_ARTIFACT_ID, TEST_ARTIFACT_VERSION);
                   System.out.println("[TEST] Downloaded " + downloaded.length + " bytes after leader failover");
                   assertThat(downloaded.length).isEqualTo(localArtifactBytes.length);
                   assertThat(sha1Hex(downloaded)).isEqualTo(localArtifactSha1);
               });

        // Restore original leader
        cluster.node(originalLeaderId).start();
        cluster.awaitQuorum();
    }

    @Test
    @Order(6)
    void mavenMetadata_listsUploadedVersions() {
        var metadata = cluster.anyNode().get(
            "/repository/" + TEST_GROUP_PATH + "/" + TEST_ARTIFACT_ID + "/maven-metadata.xml");
        System.out.println("[TEST] Maven metadata: " + metadata);

        assertThat(metadata).contains(TEST_ARTIFACT_VERSION);
    }

    // ===== Utility Helpers =====

    private static String sha1Hex(byte[] data) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-1");
            var hash = digest.digest(data);
            var sb = new StringBuilder();
            for (var b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
