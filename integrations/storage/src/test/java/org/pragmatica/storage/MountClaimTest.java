package org.pragmatica.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// A0b: a disk-tier mount belongs to one live node. The claim is an OS file lock, so the exclusion does not depend on guessing
/// whether another process is alive: a second node is refused whatever the claim file says, a holder that is gone leaves
/// nothing to reclaim, and a graceful shutdown gives the mount up.
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
            assertThat(cause.message()).contains("node-a").contains(mount.toString()).contains("disk_path");
        });
    }

    /// Liveness is the lock, never the file: a file that names a process which is gone (a crashed holder's leftover) holds nothing.
    @Test
    void claim_leftoverFileOfADeadProcess_isTakenWithoutAnyLivenessGuess() throws Exception {
        var gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Files.writeString(claimFile(), "node=node-dead\npid=" + gone.pid() + "\nstarted=1\n");

        var claim = MountClaim.claim(mount, "node-b");

        assertThat(claim.isSuccess()).as("no lock is held on the file").isTrue();
        assertThat(Files.readString(claimFile())).contains("node=node-b");
    }

    /// The old check reclaimed a claim whose recorded start time differed from the live process's, so a live process it could not
    /// time (another user's) lost its claim. The file naming a live process, with any start time, is not a claim either way.
    @Test
    void claim_leftoverFileNamingALiveProcess_isNotAClaim() throws IOException {
        Files.writeString(claimFile(), "node=node-old\npid=" + ProcessHandle.current().pid() + "\nstarted=0\n");

        assertThat(MountClaim.claim(mount, "node-b").isSuccess()).as("only a held lock is a claim").isTrue();
    }

    @Test
    void claim_sameNodeInTheSameProcess_takesTheClaimOver() {
        MountClaim.claim(mount, "node-a").unwrap();

        assertThat(MountClaim.claim(mount, "node-a").isSuccess()).as("a node restarted in one JVM").isTrue();
    }

    @Test
    void release_givesTheMountUp_soAnotherNodeMayClaimIt() throws IOException {
        var claim = MountClaim.claim(mount, "node-a").unwrap();

        claim.release();

        assertThat(Files.size(claimFile())).as("the file stays (deleting it races waiting claimants) but names nobody").isZero();
        assertThat(MountClaim.claim(mount, "node-b").isSuccess()).isTrue();
    }

    /// A claim superseded by the same node restarting in this JVM must not give up the mount its successor holds.
    @Test
    void release_ofAClaimTheSameNodeSupersededInThisJvm_leavesTheSuccessorHolding() throws IOException {
        var first = MountClaim.claim(mount, "node-a").unwrap();
        MountClaim.claim(mount, "node-a").unwrap();

        first.release();

        var refusal = MountClaim.claim(mount, "node-b");

        assertThat(refusal.isFailure()).as("the successor still holds the mount").isTrue();
        refusal.onFailure(cause -> assertThat(cause.message()).as("the successor is still registered as the holder").contains("node-a"));
        assertThat(Files.readString(claimFile())).contains("node=node-a");
    }

    /// The JVM holds the claim file through a channel the in-JVM registry does not know (another class loader's copy of this
    /// class): the OS refuses the second lock inside the JVM, and that is a refusal, not a claim.
    @Test
    void claim_lockHeldInThisJvmOutsideTheRegistry_isRefused() throws IOException {
        try (var outsider = java.nio.channels.FileChannel.open(claimFile(), java.nio.file.StandardOpenOption.CREATE,
                                                               java.nio.file.StandardOpenOption.WRITE);
             var lock = outsider.tryLock()) {
            assertThat(lock).as("the outsider holds the lock").isNotNull();

            var refusal = MountClaim.claim(mount, "node-b");

            assertThat(refusal.isFailure()).as("an unregistered holder in this JVM still excludes").isTrue();
            refusal.onFailure(cause -> assertThat(cause).isInstanceOf(MountClaim.Held.class));
        }
    }

    /// Many nodes racing for one mount: exactly one wins, whatever the interleaving (the old reclaim path let two win).
    @Test
    void claim_manyRacingNodes_exactlyOneWins() throws Exception {
        for (var round = 0; round < 50; round++) {
            var dir = Files.createDirectory(mount.resolve("race-" + round));
            var gone = new ProcessBuilder("true").start();
            gone.waitFor();
            Files.writeString(dir.resolve(MountClaim.CLAIM_FILE), "node=dead\npid=" + gone.pid() + "\nstarted=1\n");
            var results = raceForTheMount(dir, 8);

            assertThat(results.stream().filter(Result::isSuccess).count()).as("round %d: winners", round).isEqualTo(1);
            assertThat(results.stream().filter(Result::isFailure).map(r -> failureOf(r)).toList())
                .allSatisfy(cause -> assertThat(cause).isInstanceOf(MountClaim.Held.class));
            results.stream().filter(Result::isSuccess).forEach(r -> r.unwrap().release());
        }
    }

    private static java.util.List<Result<MountClaim>> raceForTheMount(Path dir, int nodes) throws Exception {
        var pool = Executors.newFixedThreadPool(nodes);
        var ready = new CountDownLatch(nodes);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<Result<MountClaim>>>();

        for (var i = 0; i < nodes; i++) {
            var node = "node-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return MountClaim.claim(dir, node);
            }));
        }
        ready.await();
        go.countDown();
        var results = new ArrayList<Result<MountClaim>>();
        for (var future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static Cause failureOf(Result<MountClaim> result) {
        var holder = new Cause[1];
        result.onFailure(cause -> holder[0] = cause);
        return holder[0];
    }

    @Test
    void openClaiming_secondTierOnTheSameMount_failsWithHeldAndTouchesNothing() throws IOException {
        LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-a")).unwrap();
        var partial = mount.resolve("ab").resolve("cd");
        Files.createDirectories(partial);
        var leftover = Files.writeString(partial.resolve("abcdef.1.partial"), "in-flight write of the first node");

        var second = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-b"));

        assertThat(second.isFailure()).isTrue();
        second.onFailure(cause -> assertThat(cause).isInstanceOf(MountClaim.Held.class));
        assertThat(leftover).as("a refused node must not sweep the holder's partial blocks").exists();
    }

    /// The claim is taken before open(); an open() that fails must give it back, or this process holds a mount it never used.
    @Test
    void openClaiming_whenTheOpenFails_givesTheClaimBack() throws IOException {
        var shard = Files.createDirectories(mount.resolve("ab"));
        var unreadable = Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(shard, unreadable);
        try {
            var opened = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-a"));

            assertThat(opened.isFailure()).as("the walk over an unreadable shard directory fails the open").isTrue();
            assertThat(MountClaim.claim(mount, "node-b").isSuccess()).as("the failed open left no claim behind").isTrue();
        } finally {
            Files.setPosixFilePermissions(shard, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void localDiskTier_claimFileIsNotABlock() {
        var tier = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(opened -> opened.openClaiming("node-a")).unwrap();

        assertThat(tier.usedBytes()).as("the claim file is bookkeeping, not stored bytes").isZero();
        tier.release();
        assertThat(MountClaim.claim(mount, "node-b").isSuccess()).as("release gave the mount up").isTrue();
    }

    /// An encrypted instance's tier is the wrapper, and the instance releases through the wrapper: a wrapper that kept the claim
    /// would hold the mount of a node that had shut down.
    @Test
    void release_throughTheEncryptingWrapper_givesTheMountUp() {
        var keyring = keyring();
        var disk = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-a")).unwrap();
        var armed = EncryptingStorageTier.armLocalDisk(disk, mount, keyring).unwrap();

        armed.tier().release();

        assertThat(MountClaim.claim(mount, "node-b").isSuccess()).as("the wrapper released the claim").isTrue();
    }

    @Test
    void armingEncryption_overAClaimedMount_doesNotReadTheClaimAsPlaintext() {
        var disk = LocalDiskTier.localDiskTier(mount, 1024 * 1024).flatMap(tier -> tier.openClaiming("node-a")).unwrap();

        var armed = EncryptingStorageTier.armLocalDisk(disk, mount, keyring());

        assertThat(armed.isSuccess()).as("the claim file is bookkeeping, not a plaintext block").isTrue();
    }

    // --- across processes: the exclusion the in-JVM registry cannot supply ---

    /// A forked JVM holds the claim: this JVM is refused naming that process, and when the process is killed (no release runs)
    /// the kernel drops the lock and the mount can be claimed.
    @Test
    void claim_heldByAnotherProcess_isRefusedWhileItLivesAndTakenAfterItIsKilled() throws Exception {
        var holder = forkHolder("node-fork");
        try {
            var refusal = MountClaim.claim(mount, "node-here");

            assertThat(refusal.isFailure()).as("a live process in another JVM holds the mount").isTrue();
            refusal.onFailure(cause -> {
                assertThat(cause).isInstanceOf(MountClaim.Held.class);
                var held = (MountClaim.Held) cause;
                assertThat(held.holderNodeId()).isEqualTo("node-fork");
                assertThat(held.holderPid()).isEqualTo(holder.pid());
            });
        } finally {
            holder.destroyForcibly();
            holder.waitFor(30, TimeUnit.SECONDS);
        }

        assertThat(holder.isAlive()).isFalse();
        assertThat(MountClaim.claim(mount, "node-here").isSuccess()).as("the kernel dropped the killed holder's lock").isTrue();
    }

    /// What the file says is never consulted: a live holder whose claim file reads as empty (the window between create and write)
    /// or as garbage is still refused. The old claim parsed such a file as stale and deleted a live claim.
    @Test
    void claim_heldByAnotherProcess_isRefusedWhateverTheFileSays() throws Exception {
        var holder = forkHolder("node-fork");
        try {
            for (var content : new String[] {"", "garbage", "node=x\npid=notanumber\nstarted=0\n"}) {
                Files.writeString(claimFile(), content);

                var refusal = MountClaim.claim(mount, "node-here");

                assertThat(refusal.isFailure()).as("claim file reads %s", content.isEmpty() ? "empty" : content).isTrue();
                refusal.onFailure(cause -> assertThat(cause).isInstanceOf(MountClaim.Held.class));
            }
        } finally {
            holder.destroyForcibly();
            holder.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private Process forkHolder(String nodeId) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), MountClaimHolderMain.class.getName(),
                                        mount.toString(), nodeId).redirectErrorStream(true).start();
        var line = new BufferedReader(new InputStreamReader(holder.getInputStream())).readLine();

        assertThat(line).as("the forked JVM took the claim").isEqualTo("claimed");
        return holder;
    }

    private static EncryptionKeyring keyring() {
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return EncryptionKeyring.encryptionKeyring(java.util.Map.of("k", BlockEncryptor.aesGcm(key, "k").unwrap()), "k").unwrap();
    }
}
