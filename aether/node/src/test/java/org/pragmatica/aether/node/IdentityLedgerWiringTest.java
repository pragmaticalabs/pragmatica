// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.consensus.net.IdentityFacts;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.BootTokens.Admission;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;

/// Where a node keeps its identity ledger and how it is attached at boot (#2429): the location convention, the operator-set
/// versus synthesized boot rule, and the operator events a ledger that is lost or cannot be written raises.
class IdentityLedgerWiringTest {
    private static final NodeId SELF = new NodeId("n1");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

    @TempDir
    Path directory;

    private final List<OperatorWarning> events = Collections.synchronizedList(new ArrayList<>());
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(events::add);

    /// A disk path the operator wrote.
    private Map<String, StorageConfig> storageAt(Path diskPath) {
        return Map.of("artifacts", withDiskPath(diskPath).withDiskPathExplicit());
    }

    private static StorageConfig withDiskPath(Path diskPath) {
        var defaults = StorageConfig.storageConfig();

        return new StorageConfig(defaults.memoryMaxBytes(),
                                 defaults.diskMaxBytes(),
                                 diskPath.toString(),
                                 defaults.snapshotPath(),
                                 defaults.snapshotMutationThreshold(),
                                 defaults.snapshotMaxInterval(),
                                 defaults.snapshotRetentionCount(),
                                 defaults.walPath(),
                                 defaults.encrypted());
    }

    private static void await(String what, BooleanSupplier condition) {
        var deadline = System.nanoTime() + BOUND.nanos();

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            sleep();
        }

        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean raised(OperatorWarningCode code) {
        synchronized (events) {
            return events.stream().anyMatch(event -> event.code() == code);
        }
    }

    @Test
    void location_isTheIdentitySiblingOfTheArtifactsDiskPath_perNode() {
        var location = IdentityLedgerWiring.location(SELF, Option.none(), storageAt(directory.resolve("data").resolve("storage")));

        assertThat(location.file()).isEqualTo(directory.resolve("data").resolve("identity").resolve("n1").resolve("ledger"));
        assertThat(location.stated()).as("a disk_path the operator wrote").isTrue();
    }

    @Test
    void location_aDiskPathNotWrittenByTheOperator_isNotStated_evenWhenItDiffersFromTheDefault() {
        var location = IdentityLedgerWiring.location(SELF, Option.none(), Map.of("artifacts", withDiskPath(directory.resolve("derived"))));

        assertThat(location.stated()).as("provenance decides, not the value").isFalse();
    }

    @Test
    void location_theDefaultPathWrittenExplicitly_isStated() {
        var written = StorageConfig.storageConfig().withDiskPathExplicit();
        var location = IdentityLedgerWiring.location(SELF, Option.none(), Map.of("artifacts", written));

        assertThat(location.stated()).as("an explicitly written default path also refuses when unusable").isTrue();
    }

    @Test
    void location_sharesItsParentWithTheStreamDataDir_soBothSitOnTheSameDurableVolume() {
        var storage = storageAt(directory.resolve("data").resolve("storage"));
        var ledgerRoot = IdentityLedgerWiring.location(SELF, Option.none(), storage).file().getParent().getParent().getParent();

        assertThat(AetherNode.streamDataDir(storage, SELF).getParent().getParent()).isEqualTo(ledgerRoot);
    }

    @Test
    void location_withNoArtifactsSection_isTheBuiltInDefault_andNotStated() {
        var location = IdentityLedgerWiring.location(SELF, Option.none(), Map.of());

        assertThat(location.stated()).isFalse();
        assertThat(location.file().getFileName().toString()).isEqualTo("ledger");
    }

    @Test
    void location_aFileTheHarnessStates_winsOverTheDerivedOne_andCountsAsStated() {
        var stated = directory.resolve("harness").resolve("ledger");
        var location = IdentityLedgerWiring.location(SELF, Option.some(stated), Map.of());

        assertThat(location.file()).isEqualTo(stated);
        assertThat(location.stated()).isTrue();
    }

    @Test
    void verifyBootable_aStatedLocationThatCannotBeWritten_failsNamingTheFile() throws Exception {
        var blocker = directory.resolve("blocker");

        Files.writeString(blocker, "a file where a directory is needed");
        var location = new IdentityLedgerWiring.Location(blocker.resolve("identity").resolve("ledger"), true);

        var verdict = IdentityLedgerWiring.verifyBootable(location);

        assertThat(verdict.isFailure()).isTrue();
        verdict.onFailure(cause -> assertThat(cause.message()).contains(location.file().toString()).contains("disk_path"));
    }

    /// A volume remounted read-only before a restart: the ledger file is there and parses, the directory no longer takes writes.
    @Test
    void verifyBootable_aStatedLocationWhoseLedgerExistsButWhoseDirectoryIsReadOnly_fails() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("id").resolve("ledger"), true);

        assertThat(IdentityLedgerWiring.verifyBootable(location).isSuccess()).as("control: the first boot creates the ledger").isTrue();
        var ledgerDirectory = directory.resolve("id").toFile();

        ledgerDirectory.setWritable(false);
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(ledgerDirectory.toPath()), "running as a user the permission does not bind");

            assertThat(IdentityLedgerWiring.verifyBootable(location).isFailure()).as("the ledger exists, the directory is read-only").isTrue();
        } finally {
            ledgerDirectory.setWritable(true);
        }
    }

    @Test
    void wire_aLoadedLedgerOnAnUnwritableDirectory_raisesNotDurable_andTheGaugeReadsZero() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("id").resolve("ledger"), false);

        IdentityLedgerWiring.wire(location, SELF, BootTokens.bootTokens(1L), sink);
        var ledgerDirectory = directory.resolve("id").toFile();
        var tokens = BootTokens.bootTokens(2L);

        ledgerDirectory.setWritable(false);
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(ledgerDirectory.toPath()), "running as a user the permission does not bind");

            IdentityLedgerWiring.wire(location, SELF, tokens, sink);

            assertThat(tokens.ledgerDurable()).as("durable follows the writability probe, not the parse").isFalse();
            await("the not-durable event", () -> raised(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE));
        } finally {
            ledgerDirectory.setWritable(true);
        }
    }

    @Test
    void verifyBootable_aStatedLocationThatCanBeWritten_passes() {
        assertThat(IdentityLedgerWiring.verifyBootable(new IdentityLedgerWiring.Location(directory.resolve("id").resolve("ledger"), true)).isSuccess())
            .isTrue();
    }

    @Test
    void verifyBootable_aStatedLocationOnANewNode_createsTheLedger_asBootWould() {
        var location = new IdentityLedgerWiring.Location(directory.resolve("fresh").resolve("ledger"), true);

        assertThat(IdentityLedgerWiring.verifyBootable(location).isSuccess()).isTrue();
        assertThat(Files.isRegularFile(location.file())).as("a directory that did not exist is a node being created: CREATED, written empty").isTrue();
    }

    @Test
    void verifyBootable_aStatedLocationWhoseLedgerFileIsMissing_leavesItMissing_theNodeIsWipedNotCreated() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("existing").resolve("ledger"), true);

        Files.createDirectories(location.file().getParent());

        assertThat(IdentityLedgerWiring.verifyBootable(location).isSuccess()).isTrue();
        assertThat(Files.exists(location.file())).as("a wiped ledger stays unwritten; the gate only probes").isFalse();
    }

    @Test
    void verifyBootable_aSynthesizedLocationThatCannotBeWritten_passes_theNodeDegradesInstead() throws Exception {
        var blocker = directory.resolve("blocker");

        Files.writeString(blocker, "a file where a directory is needed");

        assertThat(IdentityLedgerWiring.verifyBootable(new IdentityLedgerWiring.Location(blocker.resolve("identity").resolve("ledger"), false)).isSuccess())
            .isTrue();
    }

    @Test
    void wire_onANewNode_createsTheLedger_andAConflictIsWrittenBeforeItIsAnswered() {
        var tokens = BootTokens.bootTokens(1L);
        var location = new IdentityLedgerWiring.Location(directory.resolve("identity").resolve("n1").resolve("ledger"), true);

        IdentityLedgerWiring.wire(location, SELF, tokens, sink);

        assertThat(Files.isRegularFile(location.file())).as("created at boot, so a restart is not mistaken for a wiped node").isTrue();
        assertThat(tokens.ledgerDurable()).isTrue();
        assertThat(tokens.admit(PEER_A, 10L)).isEqualTo(Admission.ADMITTED);
        assertThat(tokens.admitDurably(PEER_A, 11L).await(BOUND).unwrap()).isEqualTo(Admission.CONFLICT);
        assertThat(readFile(location.file())).contains("peer-a");
        assertThat(events).as("a healthy ledger raises nothing").isEmpty();
    }

    @Test
    void wire_aRestartedNode_loadsWhatItRetired_beforeAdmissionOpens() {
        var location = new IdentityLedgerWiring.Location(directory.resolve("identity").resolve("n1").resolve("ledger"), true);
        var before = BootTokens.bootTokens(1L);

        IdentityLedgerWiring.wire(location, SELF, before, sink);
        before.admit(PEER_A, 10L);
        before.admitDurably(PEER_A, 11L).await(BOUND);

        var after = BootTokens.bootTokens(2L);

        IdentityLedgerWiring.wire(location, SELF, after, sink);

        assertThat(after.isRetired(PEER_A)).isTrue();
        assertThat(after.admit(PEER_A, 99L)).isEqualTo(Admission.RETIRED);
    }

    @Test
    void wire_aSynthesizedLocationThatCannotBeWritten_raisesNotDurable_andAdmitsAsBefore() throws Exception {
        var blocker = directory.resolve("blocker");

        Files.writeString(blocker, "a file where a directory is needed");
        var tokens = BootTokens.bootTokens(1L);

        IdentityLedgerWiring.wire(new IdentityLedgerWiring.Location(blocker.resolve("identity").resolve("ledger"), false), SELF, tokens, sink);

        await("the not-durable event", () -> raised(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE));
        assertThat(tokens.ledgerDurable()).as("the gauge reads 0").isFalse();
        assertThat(tokens.admit(PEER_A, 10L)).as("the node runs as it did before the ledger existed").isEqualTo(Admission.ADMITTED);
    }

    @Test
    void wire_aLedgerFileLostOnANodeThatIsNotNew_raisesNotDurable_andAdmitsNoNewPeer() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("identity").resolve("n1").resolve("ledger"), true);
        var tokens = BootTokens.bootTokens(1L);

        Files.createDirectories(location.file().getParent());
        IdentityLedgerWiring.wire(location, SELF, tokens, sink);

        await("the wiped event", () -> raised(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE));
        assertThat(tokens.admit(PEER_A, 10L)).isEqualTo(Admission.UNAVAILABLE);
        assertThat(Files.exists(location.file())).as("not rewritten empty").isFalse();
        assertThat(tokens.ledgerDurable()).as("a wiped node does not claim the protection: the gauge reads 0").isFalse();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.code()).isEqualTo(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE);
            assertThat(event.subject()).as("the node's own id").isEqualTo(SELF.id());
            assertThat(event.message()).as("isolation until a merge, and the remedy that is safe").contains("ISOLATED").contains("majority of the configured core seats").contains("NEW node id");
        });
    }

    @Test
    void wire_aWriteThatFailsThenSucceeds_raisesNotDurableThenTheRecovery() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("identity").resolve("n1").resolve("ledger"), true);
        var tokens = BootTokens.bootTokens(1L);
        var dir = location.file().getParent();
        var aside = directory.resolve("aside");

        IdentityLedgerWiring.wire(location, SELF, tokens, sink);
        tokens.admit(PEER_A, 10L);
        tokens.admit(PEER_B, 20L);
        Files.move(dir, aside);
        Files.writeString(dir, "now a file");

        assertThat(tokens.admitDurably(PEER_A, 11L).await(BOUND).unwrap()).as("the refusal does not depend on the disk").isEqualTo(Admission.CONFLICT);
        await("the not-durable event", () -> raised(OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE));
        assertThat(tokens.ledgerDurable()).isFalse();

        Files.delete(dir);
        Files.move(aside, dir);

        assertThat(tokens.admitDurably(PEER_B, 21L).await(BOUND).unwrap()).isEqualTo(Admission.CONFLICT);
        await("the recovery event", () -> raised(OperatorWarningCode.IDENTITY_LEDGER_DURABLE));
        assertThat(tokens.ledgerDurable()).isTrue();
        synchronized (events) {
            assertThat(events).as("both transitions are about this node").allSatisfy(event -> assertThat(event.subject()).isEqualTo(SELF.id()));
        }
    }

    /// The majority a wiped core waits for is counted over these seats: the core-role entries of the configured topology, this node
    /// included, and no worker.
    @Test
    void configuredCoreSeats_areTheCoreRoleEntriesOfTheConfiguredTopology() {
        var config = Mockito.mock(AetherNodeConfig.class);
        var address = new NodeAddress("127.0.0.1", 9000);
        var core = NodeInfo.nodeInfo(new NodeId("c1"), address);
        var labelledCore = NodeInfo.nodeInfo(new NodeId("c2"), address, Map.of(NodeInfo.LABEL_ROLE, "core"));
        var worker = NodeInfo.nodeInfo(new NodeId("w1"), address, Map.of(NodeInfo.LABEL_ROLE, "worker"));

        Mockito.when(config.topology()).thenReturn(new TopologyConfig(new NodeId("c1"), 5, TimeSpan.timeSpan(1).seconds(), TimeSpan.timeSpan(10).seconds(), List.of(core, labelledCore, worker)));

        assertThat(IdentityLedgerWiring.configuredCoreSeats(config)).containsExactlyInAnyOrder(new NodeId("c1"), new NodeId("c2"));
    }

    /// Booting from a configuration hands the registry the configured seats: a wiped node of three seats leaves that state on the
    /// offers of two OTHER seats than the one joining, and not before.
    @Test
    void wire_fromAConfiguration_countsTheWipedMajorityOverTheConfiguredCoreSeats() throws Exception {
        var ledger = directory.resolve("identity").resolve("c1").resolve("ledger");
        var address = new NodeAddress("127.0.0.1", 9000);
        var config = Mockito.mock(AetherNodeConfig.class);
        var tokens = BootTokens.bootTokens(1L);

        Files.createDirectories(ledger.getParent());
        Mockito.when(config.self()).thenReturn(new NodeId("c1"));
        Mockito.when(config.topology()).thenReturn(new TopologyConfig(new NodeId("c1"), 5, TimeSpan.timeSpan(1).seconds(), TimeSpan.timeSpan(10).seconds(),
                                                                      List.of(NodeInfo.nodeInfo(new NodeId("c1"), address),
                                                                              NodeInfo.nodeInfo(new NodeId("c2"), address),
                                                                              NodeInfo.nodeInfo(new NodeId("c3"), address))));
        Mockito.when(config.protocol()).thenReturn(ProtocolConfig.testConfig(Option.none(), Option.some(ledger)));
        Mockito.when(config.storageConfig()).thenReturn(Map.of());
        IdentityLedgerWiring.wire(config, tokens, sink);

        assertThat(tokens.admit(PEER_A, 10L)).as("control: wiped").isEqualTo(Admission.UNAVAILABLE);

        tokens.mergeOffered(new NodeId("c2"), Option.some(IdentityFacts.identityFacts())).await(BOUND);
        assertThat(tokens.admit(PEER_A, 10L)).as("one seat heard: no majority without the joiner's own offer").isEqualTo(Admission.UNAVAILABLE);

        tokens.mergeOffered(new NodeId("c3"), Option.some(IdentityFacts.identityFacts())).await(BOUND);
        assertThat(tokens.admit(PEER_A, 10L)).as("two seats heard").isEqualTo(Admission.ADMITTED);
    }

    /// A wiped node whose offers journal is unreadable carries on as if it held no offers, but says so: the operator event names the
    /// journal (the listener is registered before the journal is read).
    @Test
    void wire_anUnreadableOffersJournal_isReportedToTheOperator_andCountsAsNoOffers() throws Exception {
        var location = new IdentityLedgerWiring.Location(directory.resolve("identity").resolve("n1").resolve("ledger"), true);
        var tokens = BootTokens.bootTokens(1L);

        Files.createDirectories(location.file().getParent());
        Files.writeString(location.file().resolveSibling("ledger.offers"), "garbage, not a journal");
        IdentityLedgerWiring.wire(location, SELF, tokens, sink);

        await("the offers journal event", () -> events.stream().anyMatch(event -> event.message().contains("offers journal")));
        assertThat(tokens.admit(PEER_A, 10L)).as("no offers counted: still closed").isEqualTo(Admission.UNAVAILABLE);
    }

    private static String readFile(Path file) {
        return FileOps.readString(file).or("");
    }
}
