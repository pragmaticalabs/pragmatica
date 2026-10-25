package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A0b: a disk-tier mount belongs to one live node. A second node on the same mount is refused, a claim left by a process
/// that is gone is taken over, and a graceful shutdown gives the mount up.
class MountClaimTest {
    @TempDir
    Path mount;

    private Path claimFile() {
        return mount.resolve(MountClaim.CLAIM_FILE);
    }

    @Test
    void claim_secondNodeOnTheSameMount_isRefusedNamingTheHolder() {
        MountClaim.claim(mount, "node-a").unwrap();

        var refusal = MountClaim.claim(mount, "node-b");

        assertThat(refusal.isFailure()).as("a second live node must not share the mount").isTrue();
        refusal.onFailure(cause -> {
            assertThat(cause).isInstanceOf(MountClaim.Held.class);
            assertThat(cause.message()).contains("node-a").contains(mount.toString()).contains("remove");
        });
    }

    /// The holder is dead: its recorded process id has no live process.
    @Test
    void claim_staleClaimOfADeadProcess_isReclaimed() throws Exception {
        var gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Files.writeString(claimFile(), "node=node-dead\npid=" + gone.pid() + "\nstarted=" + startedOf(gone.toHandle()) + "\n");

        var claim = MountClaim.claim(mount, "node-b");

        assertThat(claim.isSuccess()).as("a claim whose process is gone is reclaimed").isTrue();
        assertThat(Files.readString(claimFile())).contains("node=node-b");
    }

    /// A recycled process id is not the holder: the live process with this id started at another time.
    @Test
    void claim_claimWhoseProcessIdWasRecycled_isReclaimed() throws IOException {
        var self = ProcessHandle.current();
        Files.writeString(claimFile(), "node=node-old\npid=" + self.pid() + "\nstarted=" + (startedOf(self) - 60_000) + "\n");

        var claim = MountClaim.claim(mount, "node-b");

        assertThat(claim.isSuccess()).as("a live process that did not start the claim does not hold it").isTrue();
    }

    @Test
    void claim_sameNodeInTheSameProcess_takesTheClaimOver() {
        MountClaim.claim(mount, "node-a").unwrap();

        assertThat(MountClaim.claim(mount, "node-a").isSuccess()).as("a node restarted in one JVM").isTrue();
    }

    @Test
    void release_givesTheMountUp_soAnotherNodeMayClaimIt() {
        var claim = MountClaim.claim(mount, "node-a").unwrap();

        claim.release();

        assertThat(claimFile()).doesNotExist();
        assertThat(MountClaim.claim(mount, "node-b").isSuccess()).isTrue();
    }

    @Test
    void release_claimTakenOverByAnotherNode_isLeftAlone() {
        var first = MountClaim.claim(mount, "node-a").unwrap();
        // node-a's process is gone from the mount's point of view: its claim file is replaced by node-b's.
        var dead = replaceClaimWith("node-b");

        first.release();

        assertThat(claimFile()).as("a claim that is no longer ours is not deleted").exists();
        assertThat(dead).isTrue();
    }

    @Test
    void openClaiming_secondTierOnTheSameMount_failsWithHeldAndTouchesNothing() throws IOException {
        var partial = mount.resolve("ab").resolve("cd");
        Files.createDirectories(partial);
        var leftover = Files.writeString(partial.resolve("abcdef.1.partial"), "in-flight write of the first node");
        LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-a")).unwrap();

        var second = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-b"));

        assertThat(second.isFailure()).isTrue();
        second.onFailure(cause -> assertThat(cause).isInstanceOf(MountClaim.Held.class));
        assertThat(leftover).as("a refused node must not sweep the holder's partial blocks").exists();
    }

    @Test
    void localDiskTier_claimFileIsNotABlock() {
        var tier = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(opened -> opened.openClaiming("node-a")).unwrap();

        assertThat(tier.usedBytes()).as("the claim file is bookkeeping, not stored bytes").isZero();
        tier.release();
        assertThat(claimFile()).doesNotExist();
    }

    private boolean replaceClaimWith(String nodeId) {
        try {
            Files.writeString(claimFile(),
                              "node=" + nodeId + "\npid=" + ProcessHandle.current().pid() + "\nstarted="
                              + startedOf(ProcessHandle.current()) + "\n");
            return true;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long startedOf(ProcessHandle handle) {
        return handle.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L);
    }
}
