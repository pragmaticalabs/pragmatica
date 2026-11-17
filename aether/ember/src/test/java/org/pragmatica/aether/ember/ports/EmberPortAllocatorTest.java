// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.lang.Unit.unit;


/// #3021 step 2: the allocator's claims, per-claim budget, slot-pool claim, refusal on an unopenable lock file, the
/// `claims(OFF)` seam and the bind-collision retry loop. Each test uses its own sub-range of 20000-31999 below the
/// legacy pool; claims are closed in `tearDown`.
class EmberPortAllocatorTest {
    private static final int BLOCK = PortBlock.STRIDE;
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

    @TempDir
    Path lockDir;

    @TempDir
    Path otherLockDir;

    private final List<PortClaim> open = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        open.forEach(PortClaim::close);
    }

    private EmberPortAllocator allocator(int firstBlock, int blocks) {
        var first = 22_000 + firstBlock * BLOCK;

        return EmberPortAllocator.emberPortAllocator()
                                 .lockDir(lockDir)
                                 .range(first, first + blocks * BLOCK - 1)
                                 .budget(300, 50);
    }

    private PortClaim claimed(EmberPortAllocator allocator, int slots) {
        var claim = allocator.claim(slots).unwrap();

        open.add(claim);
        return claim;
    }

    private static Cause failureOf(EmberPortAllocator allocator, int slots) {
        return allocator.claim(slots).fold(cause -> cause, claim -> {
            claim.close();
            return null;
        });
    }

    @Test
    @Timeout(60)
    void claim_concurrentClaimsInOneJvm_areDisjoint() throws Exception {
        var allocator = allocator(0, 16);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<PortClaim>>();

        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return allocator.claim(4).unwrap();
            }));
        }
        start.countDown();
        for (var future : futures) {
            open.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        var ports = open.stream().flatMap(claim -> claim.block().ports().boxed()).toList();

        assertThat(open).hasSize(8);
        assertThat(ports).doesNotHaveDuplicates();
    }

    @Test
    @Timeout(60)
    void claim_differentLockDirsInOneJvm_stillNeverShareABlock_becauseTheRegistryIsJvmWide() {
        var first = allocator(50, 1);
        var second = first.lockDir(otherLockDir);

        claimed(first, 3);

        assertThat(failureOf(second, 3)).isInstanceOf(PortError.NoFreeBlock.class);

        var endingOnTheClaimedFirstPort = second.range(first.first() - PortBlock.STRIDE + 1, first.first());

        assertThat(failureOf(endingOnTheClaimedFirstPort, 3)).as("a refused attempt left the registry entry in place")
                                                              .isInstanceOf(PortError.NoFreeBlock.class);
    }

    @Test
    @Timeout(60)
    void claim_foreignTcpBinderInTheFirstBlock_skipsThatBlock() throws Exception {
        var allocator = allocator(51, 2);
        var firstBase = allocator.first();

        try (var foreign = new ServerSocket(firstBase + 5, 1, InetAddress.getLoopbackAddress())) {
            assertThat(claimed(allocator, 1).block().base()).isEqualTo(firstBase + BLOCK);
        }
    }

    @Test
    @Timeout(60)
    void claim_foreignUdpBinderInTheFirstBlock_skipsThatBlock() throws Exception {
        var allocator = allocator(53, 2);
        var firstBase = allocator.first();

        try (var foreign = new DatagramSocket(firstBase + 7, InetAddress.getLoopbackAddress())) {
            assertThat(claimed(allocator, 1).block().base()).isEqualTo(firstBase + BLOCK);
        }
    }

    @Test
    @Timeout(60)
    void claimsOff_foreignBinderInTheFirstBlock_isStillSkippedByTheBindProbe() throws Exception {
        var allocator = allocator(55, 2).claims(EmberPortAllocator.Claims.OFF);
        var firstBase = allocator.first();

        try (var foreign = new ServerSocket(firstBase + 3, 1, InetAddress.getLoopbackAddress())) {
            assertThat(claimed(allocator, 1).block().base()).isEqualTo(firstBase + BLOCK);
        }
    }

    @Test
    @Timeout(60)
    void claim_afterProbeHookFails_releasesTheClaim() {
        var allocator = allocator(57, 1);
        var hooked = allocator.afterProbe(_ -> Causes.cause("hook refused").result());

        assertThat(failureOf(hooked, 1).message()).contains("hook refused");
        claimed(allocator, 1);
    }

    @Test
    void isBindCollision_recognisesEachOfTheTwoMessages_andNothingElse() {
        assertThat(BindProbe.isBindCollision("Failed to bind to port 1: Address already in use")).isTrue();
        assertThat(BindProbe.isBindCollision("java.net.BindException: cannot assign")).isTrue();
        assertThat(BindProbe.isBindCollision("connection refused")).isFalse();
    }

    @Test
    @Timeout(60)
    void claim_lockChannelBroken_isRefusedAsLockFailed_notReadAsABusyBlock() throws Exception {
        var allocator = allocator(58, 1);

        claimed(allocator, 1).close();
        PortLockFile.CHANNELS.get(lockDir).close();

        assertThat(failureOf(allocator, 1)).isInstanceOf(PortError.LockFailed.class);
    }

    @Test
    @Timeout(60)
    void claim_locksEveryPortOfTheBlock() throws Exception {
        var claim = claimed(allocator(59, 1), 1);

        try (var other = FileChannel.open(lockDir.resolve("ports.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            for (var port : claim.block().ports().boxed().toList()) {
                assertThatThrownBy(() -> other.tryLock(port, 1, false)).as("port %d is locked", port)
                                                                       .isInstanceOf(OverlappingFileLockException.class);
            }
        }
    }

    @Test
    @Timeout(60)
    void claim_releasedBlock_isClaimableAgain_andHeldBlockIsNot() {
        var allocator = allocator(16, 1);
        var first = claimed(allocator, 3);

        assertThat(failureOf(allocator, 3)).isInstanceOf(PortError.NoFreeBlock.class);

        first.close();
        open.remove(first);
        claimed(allocator, 3);
    }

    @Test
    @Timeout(60)
    void claim_everyBlockBusy_failsAfterThePerClaimBudget_andEachClaimGetsItsOwnBudget() {
        var allocator = allocator(17, 1);

        claimed(allocator, 1);
        var first = elapsedMsOfFailingClaim(allocator);
        var second = elapsedMsOfFailingClaim(allocator);

        assertThat(first).isGreaterThanOrEqualTo(250);
        assertThat(second).as("a static budget would be spent by the first claim").isGreaterThanOrEqualTo(250);
    }

    private long elapsedMsOfFailingClaim(EmberPortAllocator allocator) {
        var startedAt = System.nanoTime();
        var failure = failureOf(allocator, 1);

        assertThat(failure).isInstanceOf(PortError.NoFreeBlock.class);
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    @Test
    void claim_slotPoolAboveTheBlock_isRefusedNamingBothNumbers_andAFullBlockIsClaimed() {
        var allocator = allocator(18, 1);
        var poolSize = 2 * 3 + 2;
        var stride = 4;

        var failure = failureOf(allocator, poolSize * stride);

        assertThat(failure).isInstanceOf(PortError.SlotPoolTooLarge.class);
        assertThat(failure.message()).contains("32").contains("28");

        var claim = claimed(allocator, poolSize * 3);
        var lastSlotQuic = claim.block().quicBase() + (poolSize - 1) * 3;

        assertThat(claim.block().ports().boxed()).contains(lastSlotQuic,
                                                           claim.block().mgmtBase() + poolSize * 3 - 1,
                                                           claim.block().appBase() + poolSize * 3 - 1,
                                                           claim.block().swimBase() + poolSize * 3 - 1);
    }

    @Test
    void claim_lockFileIsARegularFileInsteadOfADirectory_isRefusedNamingPathAndOverride() throws Exception {
        var notADirectory = Files.createFile(lockDir.resolve("blocker"));
        var allocator = allocator(19, 1).lockDir(notADirectory);

        var failure = failureOf(allocator, 1);

        assertThat(failure).isInstanceOf(PortError.LockFileUnavailable.class);
        assertThat(failure.message()).contains(notADirectory.resolve("ports.lock").toString())
                                     .contains(PortError.LOCK_DIR_PROPERTY)
                                     .contains(PortError.LOCK_DIR_ENV);
        claimed(allocator(19, 1), 1);
    }

    @Test
    void claim_lockFileIsADirectory_isRefused_neverFallsBackToUnlocked() throws Exception {
        Files.createDirectories(lockDir.resolve("ports.lock"));

        assertThat(failureOf(allocator(20, 1), 1)).isInstanceOf(PortError.LockFileUnavailable.class);
    }

    @Test
    void claim_createsTheDirectoryWorldWritableWithStickyBit_andTheFile0666() throws Exception {
        var nested = lockDir.resolve("fresh-dir");

        claimed(allocator(21, 1).lockDir(nested), 1);

        assertThat(((Integer) Files.getAttribute(nested, "unix:mode")) & 07777).isEqualTo(01777);
        assertThat(((Integer) Files.getAttribute(nested.resolve("ports.lock"), "unix:mode")) & 0777).isEqualTo(0666);
    }

    @Test
    void claimsOff_turnsOffTheRegistryAndTheLocks_soTwoClaimantsGetTheSameBlock() {
        var on = allocator(22, 2);
        var off = on.claims(EmberPortAllocator.Claims.OFF);

        var held = claimed(on, 1);
        var overlapping = claimed(off, 1);
        var other = claimed(on, 1);

        assertThat(overlapping.block().base()).as("OFF ignores the claim").isEqualTo(held.block().base());
        assertThat(other.block().base()).as("ON skips the claimed block").isNotEqualTo(held.block().base());
    }

    @Test
    void claim_overlappingSpan_isRejectedPerPort_andAcceptedWithClaimsOff() {
        var low = allocator(24, 1);
        var shifted = low.range(low.first() + 64, low.first() + 64 + BLOCK - 1);

        claimed(low, 1);

        assertThat(failureOf(shifted, 1)).isInstanceOf(PortError.NoFreeBlock.class);
        assertThat(claimed(shifted.claims(EmberPortAllocator.Claims.OFF), 1).block().base()).isEqualTo(low.first() + 64);
    }

    private EmberPortAllocator.ClusterOps<String> ops(List<Integer> bases, List<Option<Cause>> startOutcomes) {
        return EmberPortAllocator.ClusterOps.clusterOps(base -> {
                                                            bases.add(base);
                                                            return "cluster-" + base;
                                                        },
                                                        _ -> outcome(startOutcomes.get(Math.min(bases.size(), startOutcomes.size()) - 1)),
                                                        _ -> Promise.success(unit()),
                                                        BOUND);
    }

    private static Promise<org.pragmatica.lang.Unit> outcome(Option<Cause> failure) {
        return failure.fold(() -> Promise.success(unit()), Cause::promise);
    }

    private static final Cause BIND_COLLISION = Causes.cause("Transport failure: java.net.BindException: Address already in use");

    @Test
    @Timeout(60)
    void startCluster_bindCollision_retriesOnAFreshBlock_countsTheRetry_andReleasesTheLostBlock() {
        var allocator = allocator(25, 4);
        var bases = new ArrayList<Integer>();
        var started = allocator.startCluster(3, ops(bases, List.of(Option.some(BIND_COLLISION), Option.none()))).unwrap();

        open.add(started.claim());

        assertThat(allocator.bindRetryCount()).isEqualTo(1);
        assertThat(bases).hasSize(2);
        assertThat(bases.get(1)).isNotEqualTo(bases.get(0));
        assertThat(started.claim().block().base()).isEqualTo(bases.get(1));
        claimed(allocator.range(bases.get(0), bases.get(0) + BLOCK - 1), 1);
    }

    @Test
    @Timeout(60)
    void startCluster_noCollision_countsNoRetry() {
        var allocator = allocator(29, 2);
        var started = allocator.startCluster(3, ops(new ArrayList<>(), List.of(Option.none()))).unwrap();

        open.add(started.claim());

        assertThat(allocator.bindRetryCount()).isZero();
    }

    @Test
    @Timeout(60)
    void startCluster_failureThatIsNotABindCollision_failsAtOnce_withoutRetry_andReleasesTheBlock() {
        var allocator = allocator(31, 2);
        var bases = new ArrayList<Integer>();
        var failure = allocator.startCluster(3, ops(bases, List.of(Option.some(Causes.cause("boom"))))).fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(PortError.StartFailed.class);
        assertThat(allocator.bindRetryCount()).isZero();
        assertThat(bases).hasSize(1);
        claimed(allocator.range(bases.get(0), bases.get(0) + BLOCK - 1), 1);
    }

    @Test
    @Timeout(60)
    void startCluster_everyStartCollides_failsAfterTheBoundedAttempts() {
        var allocator = allocator(33, 8);
        var failure = allocator.startCluster(3, ops(new ArrayList<>(), List.of(Option.some(BIND_COLLISION)))).fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(PortError.BindCollisionsExhausted.class);
        assertThat(allocator.bindRetryCount()).isEqualTo(EmberPortAllocator.START_ATTEMPTS);
    }

    @Test
    @Timeout(60)
    void startCluster_stopFails_keepsTheClaimUntilJvmExit() {
        var allocator = allocator(41, 1);
        var bases = new ArrayList<Integer>();
        var ops = EmberPortAllocator.ClusterOps.<String>clusterOps(base -> {
                                                                       bases.add(base);
                                                                       return "cluster";
                                                                   },
                                                                   _ -> BIND_COLLISION.promise(),
                                                                   _ -> Causes.cause("stop refused").promise(),
                                                                   BOUND);
        var failure = allocator.startCluster(3, ops).fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(PortError.CleanupFailed.class);
        assertThat(failureOf(allocator, 1)).as("the retained claim keeps the only block").isInstanceOf(PortError.NoFreeBlock.class);
    }

    @Test
    void resolveLockDir_propertyThenEnvironmentThenFixedDefault() {
        assertThat(EmberPortAllocator.resolveLockDir(Option.some("/p"), Option.some("/e"))).isEqualTo(Path.of("/p"));
        assertThat(EmberPortAllocator.resolveLockDir(Option.none(), Option.some("/e"))).isEqualTo(Path.of("/e"));
        assertThat(EmberPortAllocator.resolveLockDir(Option.none(), Option.none()))
            .isEqualTo(Path.of("/tmp/aether-ember-ports"));
    }
}
