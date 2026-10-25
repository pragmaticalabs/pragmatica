package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A0b: a disk tier's directory belongs to one node. Each test names the mutation that reddens it.
class MountClaimTest {
    @TempDir
    Path mount;

    /// Mutation: `holdsAgainst` always false (every existing claim read as stale) -- the second node is let in.
    @Test
    void open_secondNodeOnTheSameMount_isRefused_andTouchesNothing() throws IOException {
        var first = disk().open("node-a").unwrap();
        var partial = Files.writeString(mount.resolve("ab.partial"), "a write the first node has in flight");
        var second = disk().open("node-b");

        assertThat(second.isFailure()).isTrue();
        second.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a second node must not open the mount"))
              .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StorageError.MountClaimed.class,
                                                                           claimed -> assertThat(claimed.holderNodeId()).isEqualTo("node-a")));
        assertThat(partial).as("the refused open must not sweep the first node's in-flight partial").exists();
        first.release();
    }

    /// Mutation: `holdsAgainst` always true (a claim is never stale) -- the takeover is refused.
    @Test
    void open_claimOfAProcessThatIsGone_isTakenOver() throws Exception {
        Files.writeString(mount.resolve(MountClaim.FILE_NAME), claimOf("node-dead", deadPid(), 1L));

        var taken = disk().open("node-b");

        assertThat(taken.isSuccess()).as("a claim whose process is gone is stale").isTrue();
        assertThat(Files.readString(mount.resolve(MountClaim.FILE_NAME))).startsWith("node-b\n");
    }

    /// Mutation: the start-time comparison dropped -- a recycled pid keeps the claim alive.
    @Test
    void open_claimWhosePidNowBelongsToAnotherProcess_isTakenOver() throws IOException {
        var ourPid = ProcessHandle.current().pid();
        var ourStart = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();

        Files.writeString(mount.resolve(MountClaim.FILE_NAME), claimOf("node-old", ourPid, ourStart - 3_600_000L));

        assertThat(disk().open("node-b").isSuccess()).as("same pid, other start time: the id was reused").isTrue();
    }

    /// Mutation: the host comparison dropped -- another host's claim is judged by a local pid lookup and taken over.
    @Test
    void open_claimFromAnotherHost_isRefused_becauseItsLivenessCannotBeChecked() throws IOException {
        Files.writeString(mount.resolve(MountClaim.FILE_NAME), "node-far\nsome-other-host\n" + deadPid() + "\n1\n");

        assertThat(disk().open("node-b").isFailure()).isTrue();
    }

    /// Mutation: `Holder.parse` accepts a short file (`lines.length >= 1`) -- the open dies on a bad index instead of
    /// taking the unreadable claim over.
    @Test
    void open_claimFileThatIsNotAClaim_isTakenOver() throws IOException {
        Files.writeString(mount.resolve(MountClaim.FILE_NAME), "half a claim");

        assertThat(disk().open("node-b").isSuccess()).as("an unreadable claim names no live holder").isTrue();
        assertThat(Files.readString(mount.resolve(MountClaim.FILE_NAME))).startsWith("node-b\n");
    }

    /// Mutation: the process id is read from the start-time line (`lines[2]` becomes `lines[3]`) -- a live holder looks gone.
    @Test
    void open_claimOfALiveProcessOnThisHost_isRefused() throws IOException {
        Files.writeString(mount.resolve(MountClaim.FILE_NAME), claimOf("node-live", ProcessHandle.current().pid(), startOfThisProcess()));

        assertThat(disk().open("node-b").isFailure()).isTrue();
    }

    /// Mutation: `sameProcessAs` always false -- a node restarting in its own process is locked out by itself.
    @Test
    void open_sameNodeAgain_inTheSameProcess_isAllowed() {
        var first = disk().open("node-a").unwrap();

        assertThat(disk().open("node-a").isSuccess()).isTrue();
        first.release();
    }

    /// Mutation: `release` does nothing -- the claim outlives the node.
    @Test
    void release_letsAnotherNodeOpen() {
        var first = disk().open("node-a").unwrap();

        first.release();

        assertThat(disk().open("node-b").isSuccess()).isTrue();
    }

    /// Mutation: `release` deletes unconditionally -- the stale holder removes its successor's claim.
    @Test
    void release_afterATakeover_doesNotRemoveTheSuccessorsClaim() throws IOException {
        var first = disk().open("node-a").unwrap();

        Files.writeString(mount.resolve(MountClaim.FILE_NAME), claimOf("node-b", ProcessHandle.current().pid(), startOfThisProcess()));
        first.release();

        assertThat(mount.resolve(MountClaim.FILE_NAME)).exists();
    }

    /// Mutation: the claim file counted as a block in `blockBytes` -- the tier reports bytes it does not hold.
    @Test
    void open_claimFile_isNotCountedAsStoredBytes() {
        var tier = disk().open("node-a").unwrap();

        assertThat(tier.usedBytes()).isZero();
        tier.release();
    }

    private LocalDiskTier disk() {
        return LocalDiskTier.localDiskTier(mount, 1_000_000L).unwrap();
    }

    private static String claimOf(String nodeId, long pid, long startMillis) {
        return nodeId + "\n" + localHost() + "\n" + pid + "\n" + startMillis + "\n";
    }

    private static long startOfThisProcess() {
        return ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli();
    }

    private static String localHost() {
        return MountClaim.Holder.self("probe").host();
    }

    /// The pid of a process that has finished: no process answers to it now.
    private static long deadPid() {
        try {
            var process = new ProcessBuilder("true").start();

            process.waitFor();

            return process.pid();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
