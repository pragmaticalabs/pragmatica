// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ClusterEvent;
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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Network;
import org.pragmatica.lang.parse.Text;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;
import static org.assertj.core.api.Assertions.assertThat;


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
/// **The flip (PR-1a, the record and tag half of PR-1u, lane A).** Tag 2118 is retired, so the old document cannot be decoded: arm 2
/// asserts "cluster-state writes refused `RestorePending`", and arm 3's first half asserts backup status `HEAD_UNREADABLE` and
/// `declare-genesis` refused `HeadUnreadable`. At lane A's head this is T17's control reading: the refusal is attributable to the
/// retired tag alone. Arm 1 (`fresh` opens the gate and serves) and arms 3 / 3b second halves (the operator steps reach a new head)
/// hold at every head.
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

    private static final String ARM2_BLOCKED = "arm 2: the restore is BLOCKED (backup-restore-blocked, document undecodable), not still running"
                                             + " and not restored; restored=";

    private static final URL FIXTURE_URL = EmberArtifactFlagDayRestoreTest.class.getResource("/" + RESOURCE);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final long POLL_NANOS = 250_000_000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path temp;

    private EmberCluster cluster;
    private Path baseDir;

    @AfterEach
    void tearDown() {
        option(cluster).onPresent(_ -> stopCluster());
    }

    /// T17 arms 2, 1, 3, 3b (#420, R-7 gate): the old document is refused at every backup step that reads it.
    @Test
    @Timeout(1200)
    void theOldBackup_afterPr1a_isRefusedAtEveryStep() {
        walk();
    }

    private void walk() {
        var remote = temp.resolve("remote.git");

        baseDir = temp.resolve("nodes");
        var seeded = seedRemote(remote);

        controlsOnTheFixture();
        // arm 2: restore = "auto" over the old remote
        startCluster(remote, RestoreMode.AUTO);
        arm2Refused();
        stopCluster();
        // control before arms 1 and 3: the remote head is the seeded commit, or a re-encoding that still decodes and still
        // carries the blueprint line; recorded so that a red can be attributed
        controlRemoteHead(remote, seeded);
        // arm 1: restore = "fresh" over the old remote
        restart(remote, RestoreMode.FRESH);
        arm1();
        // arm 3, first half: the old remote after arm 1's boot
        arm3First();
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
    }

    // --- arms ---
    /// Arm 2: the old document cannot be decoded. The restore runs asynchronously after the leader is elected, so the
    /// check first awaits the TERMINAL decision (blocked, or restored) under a deadline: asserting "refused" while the restore is
    /// still running would pass vacuously, and would pass at a head where the restore succeeds.
    private void arm2Refused() {
        var terminal = awaitFor(() -> blockedWarningRaised() || allHold(BackupRestoreOutcome.RESTORED), LONG_WAIT_MS);

        assertThat(terminal).as("arm 2: the restore reaches a terminal state (blocked, or restored) within the deadline")
                  .isTrue();
        assertThat(blockedWarningRaised()).as(ARM2_BLOCKED + allHold(BackupRestoreOutcome.RESTORED)).isTrue();
        assertThat(applyProbe(cluster.allNodes().getFirst())).as("arm 2: the old document cannot be decoded, so the write is refused")
                  .isInstanceOf(RestoreGate.RestorePending.class);
        assertThat(allHold(BackupRestoreOutcome.RESTORED)).isFalse();
    }

    /// Arm 1 evaluates every assertion (soft), so each one is reachable and goes red on its own mutation: the decision,
    /// the blueprint line, a write on each node, the artifact PUT, the artifact GET.
    private void arm1() {
        var soft = new SoftAssertions();

        arm1Decision(soft);
        arm1Blueprint(soft);
        arm1Writes(soft);
        arm1Push(soft);
        arm1Read(soft);
        soft.assertAll();
        // Arm 1 shows that the gate opens; it does not show that the old document is harmless: the FRESH route is content-blind.
    }

    private void arm1Decision(SoftAssertions soft) {
        soft.assertThat(awaitFor(() -> allHold(BackupRestoreOutcome.FRESH),
                                 LONG_WAIT_MS))
            .as("arm 1: the committed restore decision is FRESH on every node (red when route() ignores RestoreMode.FRESH and reads the backup)")
            .isTrue();
    }

    private void arm1Blueprint(SoftAssertions soft) {
        soft.assertThat(cluster.allNodes().stream().noneMatch(EmberArtifactFlagDayRestoreTest::holdsBlueprint))
            .as("arm 1: the old document's blueprint line is absent from the KV")
            .isTrue();
    }

    private void arm1Writes(SoftAssertions soft) {
        cluster.allNodes()
               .forEach(node -> soft.assertThat(applyProbe(node))
                                    .as("arm 1: a cluster-state write lands on %s",
                                        node.self())
                                    .isNull());
    }

    private void arm1Push(SoftAssertions soft) {
        soft.assertThat(httpPut(leaderPort(),
                                ARTIFACT_PATH,
                                POM))
            .as("arm 1: the artifact push after the boot")
            .isEqualTo(200);
    }

    private void arm1Read(SoftAssertions soft) {
        soft.assertThat(awaitFor(() -> httpGet(leaderPort(),
                                               ARTIFACT_PATH) == 200,
                                 SHORT_WAIT_MS))
            .as("arm 1: an artifact deployed after the boot answers GET 200")
            .isTrue();
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort()
                      .unwrap();
    }

    private void arm3First() {
        assertThat(awaitFor(() -> leaderStatus() == KvBackupService.Status.HEAD_UNREADABLE,
                            LONG_WAIT_MS)).as("arm 3 (first half, the old remote after arm 1's boot): backup status HEAD_UNREADABLE; found "
                                             + leaderStatus())
                  .isTrue();
        assertThat(declareGenesis()).as("arm 3: declare-genesis against the undecodable head")
                  .isInstanceOf(DeclareGenesisError.HeadUnreadable.class);
    }

    private void reapplyAndAwaitHead(Path remote, String what) {
        assertThat(awaitFor(() -> allHold(BackupRestoreOutcome.FRESH),
                            LONG_WAIT_MS)).as(what + ": the restore decision is FRESH before step (4)")
                  .isTrue();
        var leader = leaderNode();

        assertThat(applyProbe(leader)).as(what + ": step (4), one cluster-state write re-applied").isNull();
        assertThat(awaitFor(() -> headIsNew(remote),
                            LONG_WAIT_MS)).as(what
                                             + ": the head commit on branch " + BRANCH
                                             + " exists and carries this cluster's lineage, not " + OLD_LINEAGE
                                             + "; found " + headLineage(remote))
                  .isTrue();
    }

    private static boolean headIsNew(Path remote) {
        return headLineage(remote).filter(lineage -> !lineage.equals(OLD_LINEAGE))
                          .isPresent();
    }

    // --- controls ---
    /// In-run controls on the fixture, at every head: its bytes match its `sha256=` line, and its artifact line names tag 2118.
    private void controlsOnTheFixture() {
        var lines = fixture().lines().toList();

        controlChecksum(lines);
        controlArtifactTag(lines);
    }

    private static void controlChecksum(List<String> lines) {
        var sumLine = firstLine(lines, line -> line.startsWith("sha256="));
        var others = lines.stream().filter(line -> !line.equals(sumLine)).collect(Collectors.joining("\n", "", "\n"));

        assertThat(sumLine).as("control: the resource's bytes match its sha256= line")
                  .isEqualTo("sha256=" + sha256(others));
    }

    private static void controlArtifactTag(List<String> lines) {
        var artifactLine = firstLine(lines,
                                     line -> line.endsWith(" artifact-content/org.example:flagday/1.0.0/flagday-1.0.0.jar"));
        var value = Text.decodeBase64(artifactLine.substring(0, artifactLine.indexOf(' '))).unwrap();

        assertThat(leadingTag(value, 0, 0)).as("control: the artifact line names tag 2118").isEqualTo(2118);
    }

    private static String firstLine(List<String> lines, Predicate<String> match) {
        return Option.from(lines.stream().filter(match).findFirst()).unwrap();
    }

    private void controlRemoteHead(Path remote, String seeded) {
        var head = git(remote, "rev-parse", BRANCH).strip();

        if (head.equals(seeded)) {
            System.out.println("T17 control: the remote head is the seeded commit " + seeded);

            return;
        }

        var decoded = codec().decode(git(remote, "show", BRANCH + ":" + FILE));

        System.out.println("T17 control: the remote head " + head
                          + " is a re-encoding (arm 2's flush over the seed); decodes=" + decoded.isSuccess());
        assertThat(decoded.isSuccess()).as("control: a re-encoded head still decodes at PR-1r").isTrue();
        assertThat(decoded.unwrap().entries()).as("control: a re-encoded head still carries the blueprint line")
                  .containsKey(blueprintKey());
    }

    // --- cluster helpers ---
    private void startCluster(Path remote, RestoreMode mode) {
        cluster = EmberTestPorts.startedCluster(PORTS, basePort -> emberWithBackup(basePort, remote, mode), START_BOUND);
        awaitLeader();
    }

    private EmberCluster emberWithBackup(int basePort, Path remote, RestoreMode mode) {
        var attempt = emberCluster(CLUSTER_SIZE, basePort, basePort + MGMT_OFFSET, basePort + APP_HTTP_OFFSET, PREFIX);

        attempt.withKvBackup(baseDir, remote.toString(), mode);

        return attempt;
    }

    private void restart(Path remote, RestoreMode mode) {
        cluster.withKvBackup(baseDir, remote.toString(), mode);
        assertThat(cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started")).isEqualTo("started");
        awaitLeader();
    }

    private void stopCluster() {
        assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
    }

    private void awaitLeader() {
        assertThat(awaitFor(() -> cluster.currentLeader()
                                         .isPresent(),
                            LONG_WAIT_MS)).as("a leader is elected").isTrue();
    }

    private boolean blockedWarningRaised() {
        return cluster.allNodes()
                      .stream()
                      .anyMatch(EmberArtifactFlagDayRestoreTest::hasBlockedWarning);
    }

    private static boolean hasBlockedWarning(AetherNode node) {
        return node.eventAggregator()
                   .events()
                   .await(APPLY_BOUND)
                   .map(EmberArtifactFlagDayRestoreTest::anyBlocked)
                   .or(false);
    }

    private static boolean anyBlocked(List<ClusterEvent> events) {
        return events.stream()
                     .anyMatch(EmberArtifactFlagDayRestoreTest::isBlockedRestoreWarning);
    }

    private static boolean isBlockedRestoreWarning(ClusterEvent event) {
        return event instanceof ClusterEvent.OperatorWarning warning && "backup-restore-blocked".equals(warning.details()
                                                                                                               .get("code"));
    }

    private AetherNode leaderNode() {
        return leaderOption().unwrap();
    }

    private Option<AetherNode> leaderOption() {
        return cluster.currentLeader()
                      .flatMap(this::nodeNamed);
    }

    private Option<AetherNode> nodeNamed(String nodeId) {
        return cluster.getNode(nodeId);
    }

    private KvBackupService.Status leaderStatus() {
        return leaderOption().flatMap(AetherNode::backupGenesis)
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
        return ! cluster.allNodes()
                        .isEmpty() && cluster.allNodes()
                                             .stream()
                                             .allMatch(node -> holdsOutcome(node, outcome));
    }

    private static boolean holdsOutcome(AetherNode node, BackupRestoreOutcome outcome) {
        return RestoreGate.decision(node.kvStore())
                          .map(BackupRestoreValue::outcome)
                          .equals(some(outcome));
    }

    /// The failure a direct consensus submission of a cluster-state write meets, or `null` when it lands.
    private static Cause applyProbe(AetherNode node) {
        List<KVCommand<AetherKey>> commands = List.of(new KVCommand.Put<>(ConfigKey.forKey("probe-" + node.self().id()),
                                                                          ConfigValue.configValue("probe", "v")));

        return node.<Object> apply(commands)
                   .await(APPLY_BOUND)
                   .fold(cause -> cause, _ -> null);
    }

    private static boolean holdsBlueprint(AetherNode node) {
        return node.kvStore()
                   .get(blueprintKey())
                   .isPresent();
    }

    private static AppBlueprintKey blueprintKey() {
        return AppBlueprintKey.appBlueprintKey(BlueprintId.blueprintId("org.example:flagday-blueprint:1.0.0").unwrap());
    }

    private static BackupEntryCodec codec() {
        return BackupEntryCodec.backupEntryCodec(NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()));
    }

    // --- fixture and git ---
    private static String fixture() {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.readString(Path.of(FIXTURE_URL.toURI())))
                     .unwrap();
    }

    /// Seeds the frozen document as the head of the bare remote, under the commit subject the service parses; returns the commit.
    private String seedRemote(Path remote) {
        var work = temp.resolve("seed-work");

        git(temp, "init", "--quiet", "--bare", remote.toString());
        populate(work).unwrap();
        git(work, "init", "--quiet", "--initial-branch=" + BRANCH);
        git(work, "add", FILE);
        git(work, "-c", "user.email=s@x", "-c", "user.name=seed", "commit", "--quiet", "-m", commitSubject());
        git(work, "push", "--quiet", remote.toString(), BRANCH);

        return git(work, "rev-parse", "HEAD").strip();
    }

    private static Result<Path> populate(Path work) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.createDirectories(work))
                     .flatMap(EmberArtifactFlagDayRestoreTest::seedFileIn);
    }

    private static Result<Path> seedFileIn(Path directory) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.writeString(directory.resolve(FILE), fixture()));
    }

    private static String commitSubject() {
        var header = fixture().lines().toList();

        return "kv backup " + header.get(1) + " " + header.get(2) + " " + header.get(3);
    }

    /// The lineage in the header of the document at the head of `remote`'s backup branch; none when the branch is absent.
    private static Option<String> headLineage(Path remote) {
        return gitResult(remote, "show", BRANCH + ":" + FILE).option()
                        .flatMap(EmberArtifactFlagDayRestoreTest::lineageOf);
    }

    private static Option<String> lineageOf(String text) {
        return Option.from(text.lines().filter(line -> line.startsWith("lineage=")).findFirst()).map(line -> line.substring("lineage=".length()));
    }

    private static String sha256(String text) {
        return sha256Digest().map(digest -> hashed(digest, text))
                           .map(EmberArtifactFlagDayRestoreTest::hex)
                           .unwrap();
    }

    private static byte[] hashed(MessageDigest digest, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);

        return digest.digest(bytes);
    }

    private static Result<MessageDigest> sha256Digest() {
        return Result.lift(Causes::fromThrowable, () -> MessageDigest.getInstance("SHA-256"));
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /// The value's leading type tag: an unsigned LEB128 varint (2118 is the bytes `c6 10`).
    private static int leadingTag(byte[] value, int index, int shift) {
        var next = value[index] & 0xFF;
        var low = (next & 0x7F) << shift;

        return (next & 0x80) == 0
               ? low
               : low | leadingTag(value, index + 1, shift + 7);
    }

    private static int httpGet(int mgmtPort, String path) {
        return exchange(request(mgmtPort, path).GET().build());
    }

    private static int httpPut(int mgmtPort, String path, String body) {
        return exchange(request(mgmtPort, path).PUT(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpRequest.Builder request(int mgmtPort, String path) {
        return HttpRequest.newBuilder()
                          .uri(Network.parseURI("http://127.0.0.1:" + mgmtPort + path).unwrap())
                          .timeout(HTTP_TIMEOUT);
    }

    private static int exchange(HttpRequest request) {
        return Result.lift(Causes::fromThrowable,
                           () -> HTTP.send(request,
                                           HttpResponse.BodyHandlers.ofString())
                                     .statusCode())
                     .or(-1);
    }

    private static boolean awaitFor(BooleanSupplier condition, long budgetMs) {
        return awaitUntil(condition, System.currentTimeMillis() + budgetMs);
    }

    private static boolean awaitUntil(BooleanSupplier condition, long deadline) {
        return condition.getAsBoolean() || System.currentTimeMillis() <= deadline && pauseThenAwait(condition, deadline);
    }

    private static boolean pauseThenAwait(BooleanSupplier condition, long deadline) {
        LockSupport.parkNanos(POLL_NANOS);

        return awaitUntil(condition, deadline);
    }

    private static String git(Path dir, String... args) {
        return gitResult(dir, args).unwrap();
    }

    private static Result<String> gitResult(Path dir, String... args) {
        var command = Stream.concat(Stream.of("git", "-C", dir.toString()),
                                    Stream.of(args)).toList();

        return Result.lift(Causes::fromThrowable,
                           () -> new ProcessBuilder(command).redirectErrorStream(true)
                                                            .start())
                     .flatMap(EmberArtifactFlagDayRestoreTest::output);
    }

    private static Result<String> output(Process process) {
        return Result.lift(Causes::fromThrowable,
                           () -> new String(process.getInputStream().readAllBytes(),
                                            StandardCharsets.UTF_8))
                     .flatMap(text -> exitCheck(process, text));
    }

    private static Result<String> exitCheck(Process process, String text) {
        return Result.lift(Causes::fromThrowable,
                           () -> process.waitFor())
                     .flatMap(code -> verdict(code, text));
    }

    private static Result<String> verdict(int code, String text) {
        return code == 0
               ? success(text)
               : cause("git exited " + code + ": " + text).result();
    }
}
