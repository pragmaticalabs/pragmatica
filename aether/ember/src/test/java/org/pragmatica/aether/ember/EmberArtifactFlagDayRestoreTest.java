// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.BackupConfig.RestoreMode;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.node.backup.BackupGenesis.DeclareGenesisError;
import org.pragmatica.aether.node.backup.KvBackupService;
import org.pragmatica.aether.node.backup.RestoreGate;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// T17 (#420, ruling `925b10b70a`, amended by `84b6b0322b`): an OLD backup across the flag day, walked through each backup step that
/// reads it: the boot restore, the flush, and `declare-genesis`. One three-node in-JVM cluster, restarted between arms
/// (`withKvBackup` is called again before each restart to change the restore mode or the remote). Arms run 2, 1, 3, 3b.
///
/// **The fixture** is a FROZEN document (`flag-day-old-backup.txt`, generated once with this release's codec and never
/// regenerated): an artifact-content line (value tag 2118), an artifact-versions line, one blueprint line and the incarnation line
/// of ANOTHER cluster identity. It is seeded as the head of the shared bare remote that the boot restore reads, under the commit
/// subject `kv backup lineage=... incarnation=... revision=...`, and not through the live codec, so it stays an old document
/// from PR-1a on.
///
/// **Tripwires.** Arm 2 and arm 3's first half assert TODAY's value and are ENABLED; each message names the PR that flips it
/// (PR-1a, the record and tag half of PR-1u). The flipped assertions are the same walk run under [Era#PR_1A] in
/// [#theOldBackup_afterPr1a_isRefusedAtEveryStep], `@Disabled` until then. Arm 1 (`fresh` opens the gate and serves) and arms 3 /
/// 3b second halves (the operator steps reach a new head) hold at every head.
@PortBudget
class EmberArtifactFlagDayRestoreTest {
    private static final int CLUSTER_SIZE = 3;
    private static final int SLOTS = 2 * CLUSTER_SIZE;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final EmberTestPorts.Block PORTS = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                                EmberTestPorts.POOL_LAST,
                                                                                EmberTestPorts.POOL_STEP,
                                                                                SLOTS,
                                                                                MGMT_OFFSET,
                                                                                APP_HTTP_OFFSET);
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan APPLY_BOUND = TimeSpan.timeSpan(15).seconds();
    private static final long LONG_WAIT_MS = 120_000L;
    private static final long SHORT_WAIT_MS = 45_000L;
    private static final String PREFIX = "t17";
    private static final String RESOURCE = "flag-day-old-backup.txt";
    private static final String OLD_LINEAGE = "01K0000000000000000000OLD2";
    private static final String BRANCH = "kv-backup";
    private static final String FILE = "kv-backup.txt";
    private static final String ARTIFACT_PATH = "/repository/org/aether/flagday420/probe/1.0.0/probe-1.0.0.pom";
    private static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>org.aether.flagday420</groupId>"
                                      + "<artifactId>probe</artifactId><version>1.0.0</version></project>";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /// Which release's value the walk asserts: today's (PR-1r) or the one PR-1a flips it to.
    enum Era {
        PR_1R,
        PR_1A
    }

    @TempDir
    Path temp;

    private EmberCluster cluster;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop()
                              .await(STOP_BOUND)
                              .fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
    }

    @Test
    @Timeout(1200)
    void theOldBackup_acrossTheUpgrade_walksEachBackupStep_asItDoesToday() {
        walk(Era.PR_1R);
    }

    @Test
    @Disabled("T17 arms 2 and 3 (#420): enable in PR-1a, when the retired tag 2118 makes the old document undecodable; then arm 2 is"
              + " 'cluster-state writes refused RestorePending' and arm 3's first half is HEAD_UNREADABLE with declare-genesis refused"
              + " HeadUnreadable, and the PR_1R test above is deleted")
    @Timeout(1200)
    void theOldBackup_afterPr1a_isRefusedAtEveryStep() {
        walk(Era.PR_1A);
    }

    private void walk(Era era) {
        var remote = temp.resolve("remote.git");
        var flipped = new ArrayList<String>();

        baseDir = temp.resolve("nodes");
        var seeded = seedRemote(remote);

        controlsOnTheFixture();
        // arm 2: restore = "auto" over the old remote
        startCluster(remote, RestoreMode.AUTO);
        arm2(era, flipped);
        stopCluster();
        // control before arms 1 and 3: the remote head is the seeded commit, or a re-encoding that still decodes and still
        // carries the blueprint line; recorded so that a red can be attributed
        controlRemoteHead(remote, seeded);
        // arm 1: restore = "fresh" over the old remote
        restart(remote, RestoreMode.FRESH);
        arm1();
        // arm 3, first half: the old remote after arm 1's boot
        arm3First(era, flipped);
        stopCluster();
        // arm 3, second half: step (2) re-point the remote at a new empty repository, (3) fresh, (4) re-apply, (5) the flush writes
        var repointed = temp.resolve("remote-new.git");

        git(temp, "init", "--quiet", "--bare", repointed.toString());
        restart(repointed, RestoreMode.FRESH);
        reapplyAndAwaitHead(repointed, "arm 3: the new remote's first flush");
        stopCluster();
        // arm 3b: step (2) other variant, the old remote kept and its backup branch moved aside
        git(remote, "branch", "-m", BRANCH, BRANCH + "-aside");
        restart(remote, RestoreMode.FRESH);
        reapplyAndAwaitHead(remote, "arm 3b: the first flush on the original branch name");

        assertThat(flipped).as("T17 tripwires that no longer hold today's value").isEmpty();
    }

    // --- arms ---
    private void arm2(Era era, List<String> flipped) {
        switch (era) {
            case PR_1R -> {
                var restored = awaitFor(() -> allHold(BackupRestoreOutcome.RESTORED), SHORT_WAIT_MS);
                var blueprint = restored && cluster.allNodes()
                                                   .stream()
                                                   .allMatch(node -> node.kvStore()
                                                                         .get(blueprintKey())
                                                                         .isPresent());

                if (!(restored && blueprint)) {
                    flipped.add("arm 2 (restore = auto): today the frozen document restores (decision RESTORED, blueprint line present),"
                                + " found restored=" + restored + " blueprint=" + blueprint
                                + ". PR-1a (tag 2118 retired) flips this to 'cluster-state writes refused RestorePending': replace this"
                                + " tripwire with the @Disabled arm 2 assertion and enable it");
                }
            }
            case PR_1A -> {
                assertThat(applyProbe(cluster.allNodes()
                                             .getFirst())).as("arm 2: the old document cannot be decoded, so the write is refused")
                                                          .isInstanceOf(RestoreGate.RestorePending.class);
                assertThat(allHold(BackupRestoreOutcome.RESTORED)).isFalse();
            }
        }
    }

    private void arm1() {
        var fresh = awaitFor(() -> allHold(BackupRestoreOutcome.FRESH), LONG_WAIT_MS);

        assertThat(fresh).as("arm 1: the committed restore decision is FRESH on every node (red when route() ignores"
                             + " RestoreMode.FRESH and reads the backup)")
                         .isTrue();
        assertThat(cluster.allNodes()).as("arm 1: the old document's blueprint line is absent from the KV")
                                      .allSatisfy(node -> assertThat(node.kvStore()
                                                                         .get(blueprintKey())).isEqualTo(Option.none()));
        for (var node : cluster.allNodes()) {
            assertThat(applyProbe(node)).as("arm 1: a cluster-state write lands on %s", node.self())
                                        .isNull();
        }
        var leaderPort = cluster.getLeaderManagementPort()
                                .unwrap();

        assertThat(http(leaderPort, "PUT", ARTIFACT_PATH, POM)).as("arm 1: the artifact push after the boot")
                                                               .isEqualTo(200);
        assertThat(awaitFor(() -> http(cluster.getLeaderManagementPort()
                                              .or(leaderPort),
                                       "GET",
                                       ARTIFACT_PATH,
                                       null) == 200,
                            SHORT_WAIT_MS)).as("arm 1: an artifact deployed after the boot answers GET 200")
                                           .isTrue();
        // Arm 1 shows that the gate opens; it does not show that the old document is harmless: the FRESH route is content-blind.
    }

    private void arm3First(Era era, List<String> flipped) {
        var expected = era == Era.PR_1R
                       ? KvBackupService.Status.GATED
                       : KvBackupService.Status.HEAD_UNREADABLE;
        var reached = awaitFor(() -> leaderStatus() == expected, LONG_WAIT_MS);

        if (!reached) {
            flipped.add("arm 3 (first half, the old remote after arm 1's boot): expected backup status " + expected + ", found " + leaderStatus()
                        + ". Today the frozen document decodes and names another cluster identity, so GATED. PR-1a flips it to"
                        + " HEAD_UNREADABLE and adds the declare-genesis refusal HeadUnreadable: replace this tripwire with the"
                        + " @Disabled assertion and enable it");
        }
        if (era == Era.PR_1A) {
            assertThat(declareGenesis()).as("arm 3: declare-genesis against the undecodable head")
                                        .isInstanceOf(DeclareGenesisError.HeadUnreadable.class);
        }
    }

    private void reapplyAndAwaitHead(Path remote, String what) {
        assertThat(awaitFor(() -> allHold(BackupRestoreOutcome.FRESH), LONG_WAIT_MS)).as(what + ": the restore decision is FRESH before step (4)")
                                                                                     .isTrue();
        var leader = leaderNode();

        assertThat(applyProbe(leader)).as(what + ": step (4), one cluster-state write re-applied")
                                      .isNull();
        assertThat(awaitFor(() -> headLineage(remote).filter(lineage -> !lineage.equals(OLD_LINEAGE))
                                                      .isPresent(),
                            LONG_WAIT_MS)).as(what + ": the head commit on branch " + BRANCH + " exists and carries this cluster's lineage, not "
                                              + OLD_LINEAGE + "; found " + headLineage(remote))
                                          .isTrue();
    }

    // --- controls ---
    /// In-run controls on the fixture, at every head: its bytes match its `sha256=` line, and its artifact line names tag 2118.
    private void controlsOnTheFixture() {
        var document = fixture();
        var lines = document.lines()
                            .toList();
        var sumLine = lines.stream()
                           .filter(line -> line.startsWith("sha256="))
                           .findFirst()
                           .orElseThrow();
        var others = lines.stream()
                          .filter(line -> !line.equals(sumLine))
                          .collect(Collectors.joining("\n", "", "\n"));

        assertThat(sumLine).as("control: the resource's bytes match its sha256= line")
                           .isEqualTo("sha256=" + sha256(others));
        var artifactLine = lines.stream()
                                .filter(line -> line.endsWith(" artifact-content/org.example:flagday/1.0.0/flagday-1.0.0.jar"))
                                .findFirst()
                                .orElseThrow();
        var value = Base64.getDecoder()
                          .decode(artifactLine.substring(0, artifactLine.indexOf(' ')));

        assertThat(leadingTag(value)).as("control: the artifact line names tag 2118")
                                     .isEqualTo(2118);
    }

    private void controlRemoteHead(Path remote, String seeded) {
        var head = git(remote, "rev-parse", BRANCH).strip();

        if (head.equals(seeded)) {
            System.out.println("T17 control: the remote head is the seeded commit " + seeded);
            return;
        }
        var decoded = codec().decode(git(remote, "show", BRANCH + ":" + FILE));

        System.out.println("T17 control: the remote head " + head + " is a re-encoding (arm 2's flush over the seed); decodes="
                           + decoded.isSuccess());
        assertThat(decoded.isSuccess()).as("control: a re-encoded head still decodes at PR-1r")
                                       .isTrue();
        assertThat(decoded.unwrap()
                          .entries()).as("control: a re-encoded head still carries the blueprint line")
                                     .containsKey(blueprintKey());
    }

    // --- cluster helpers ---
    private void startCluster(Path remote, RestoreMode mode) {
        cluster = EmberTestPorts.startedCluster(PORTS, basePort -> {
                                                    var attempt = emberCluster(CLUSTER_SIZE,
                                                                               basePort,
                                                                               basePort + MGMT_OFFSET,
                                                                               basePort + APP_HTTP_OFFSET,
                                                                               PREFIX);

                                                    attempt.withKvBackup(baseDir, remote.toString(), mode);
                                                    return attempt;
                                                },
                                                START_BOUND);
        awaitLeader();
    }

    private void restart(Path remote, RestoreMode mode) {
        cluster.withKvBackup(baseDir, remote.toString(), mode);
        assertThat(cluster.start()
                          .await(START_BOUND)
                          .fold(Cause::message, _ -> "started")).isEqualTo("started");
        awaitLeader();
    }

    private void stopCluster() {
        assertThat(cluster.stop()
                          .await(STOP_BOUND)
                          .fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
    }

    private void awaitLeader() {
        assertThat(awaitFor(() -> cluster.currentLeader()
                                         .isPresent(), LONG_WAIT_MS)).as("a leader is elected")
                                                                     .isTrue();
    }

    private AetherNode leaderNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private KvBackupService.Status leaderStatus() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .flatMap(AetherNode::backupGenesis)
                      .map(genesis -> genesis.service()
                                             .status())
                      .or((KvBackupService.Status) null);
    }

    private Cause declareGenesis() {
        var node = leaderNode();

        return node.backupGenesis()
                   .unwrap()
                   .declare(commands -> node.<Object> apply(commands))
                   .await(APPLY_BOUND)
                   .fold(cause -> cause, _ -> null);
    }

    private boolean allHold(BackupRestoreOutcome outcome) {
        return !cluster.allNodes()
                       .isEmpty()
               && cluster.allNodes()
                         .stream()
                         .allMatch(node -> RestoreGate.decision(node.kvStore())
                                                      .map(BackupRestoreValue::outcome)
                                                      .equals(Option.some(outcome)));
    }

    /// The failure a direct consensus submission of a cluster-state write meets, or `null` when it lands.
    private static Cause applyProbe(AetherNode node) {
        List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(ConfigKey.forKey("probe-" + node.self()
                                                                                                          .id()),
                                                                          ConfigValue.configValue("probe", "v")));

        return node.<Object> apply(commands)
                   .await(APPLY_BOUND)
                   .fold(cause -> cause, _ -> null);
    }

    private static AppBlueprintKey blueprintKey() {
        return AppBlueprintKey.appBlueprintKey(BlueprintId.blueprintId("org.example:flagday-blueprint:1.0.0")
                                                          .unwrap());
    }

    private static BackupEntryCodec codec() {
        return BackupEntryCodec.backupEntryCodec(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    // --- fixture and git ---
    private static String fixture() {
        try (var stream = EmberArtifactFlagDayRestoreTest.class.getResourceAsStream("/" + RESOURCE)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Seeds the frozen document as the head of the bare remote, under the commit subject the service parses; returns the commit.
    private String seedRemote(Path remote) {
        var work = temp.resolve("seed-work");
        var header = fixture().lines()
                              .toList();
        var lineage = header.get(1)
                            .substring("lineage=".length());
        var incarnation = header.get(2)
                                .substring("incarnation=".length());
        var revision = header.get(3)
                             .substring("revision=".length());

        git(temp, "init", "--quiet", "--bare", remote.toString());
        try {
            Files.createDirectories(work);
            git(work, "init", "--quiet", "--initial-branch=" + BRANCH);
            Files.writeString(work.resolve(FILE), fixture());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(work, "add", FILE);
        git(work,
            "-c",
            "user.email=s@x",
            "-c",
            "user.name=seed",
            "commit",
            "--quiet",
            "-m",
            "kv backup lineage=" + lineage + " incarnation=" + incarnation + " revision=" + revision);
        git(work, "push", "--quiet", remote.toString(), BRANCH);

        return git(work, "rev-parse", "HEAD").strip();
    }

    /// The lineage in the header of the document at the head of `remote`'s backup branch; none when the branch is absent.
    private static Option<String> headLineage(Path remote) {
        try {
            var show = new ProcessBuilder("git", "-C", remote.toString(), "show", BRANCH + ":" + FILE).redirectErrorStream(true)
                                                                                                     .start();
            var text = new String(show.getInputStream()
                                      .readAllBytes(),
                                  StandardCharsets.UTF_8);

            return show.waitFor() != 0
                   ? Option.none()
                   : text.lines()
                         .filter(line -> line.startsWith("lineage="))
                         .findFirst()
                         .map(line -> line.substring("lineage=".length()))
                         .map(Option::some)
                         .orElse(Option.none());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            return Option.none();
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// The value's leading type tag: an unsigned LEB128 varint (2118 is the bytes `c6 10`).
    private static int leadingTag(byte[] value) {
        var tag = 0;
        var shift = 0;
        var index = 0;
        int next;

        do {
            next = value[index++] & 0xFF;
            tag |= (next & 0x7F) << shift;
            shift += 7;
        } while ((next & 0x80) != 0);
        return tag;
    }

    private static int http(int mgmtPort, String method, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://127.0.0.1:" + mgmtPort + path))
                                 .timeout(HTTP_TIMEOUT)
                                 .method(method,
                                         body == null
                                         ? HttpRequest.BodyPublishers.noBody()
                                         : HttpRequest.BodyPublishers.ofString(body))
                                 .build();

        try (var client = HttpClient.newBuilder()
                                    .connectTimeout(HTTP_TIMEOUT)
                                    .build()) {
            return client.send(request,
                               HttpResponse.BodyHandlers.ofString())
                         .statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            return -1;
        }
    }

    private static boolean awaitFor(BooleanSupplier condition, long budgetMs) {
        var deadline = System.currentTimeMillis() + budgetMs;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            pause(250);
        }
        return true;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    private static String git(Path dir, String... args) {
        var command = new ArrayList<>(List.of("git", "-C", dir.toString()));

        command.addAll(List.of(args));
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                                                     .start();
            var output = new String(process.getInputStream()
                                           .readAllBytes(),
                                    StandardCharsets.UTF_8);

            if (process.waitFor() != 0) {
                throw new AssertionError(String.join(" ", command) + " failed: " + output);
            }
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new AssertionError(e);
        }
    }
}
