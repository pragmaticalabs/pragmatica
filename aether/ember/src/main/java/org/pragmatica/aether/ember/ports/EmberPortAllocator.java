// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// Port claims for Ember clusters (#3021 step 2, §4): per-port claims in a JVM-wide registry, then byte-range locks in one
/// lock file ([PortLockFile]), then a bind probe ([BindProbe]); a base is tried in ascending 128-port steps.
/// The back-off budget belongs to ONE claim (never JVM-static). A block lost to a foreign binder between probe and bind is
/// detected as a bind collision by [#startCluster] and retried on a fresh block, bounded, counted by [#bindRetries].
///
/// `claims(OFF)` is a test seam for the probes' red arms ONLY: it turns off the registry AND the file locks together, leaving
/// the bind probe.
///
/// Guarantee: no claimant that uses the same lock directory holds any port of a claimed block while it is claimed
/// [mechanism: registry within a JVM, fcntl record locks across processes; the kernel drops them with the process]. A binder
/// that uses neither is only detected by the probe or by the bind's own failure.
public record EmberPortAllocator(Path lockDir,
                                 int first,
                                 int last,
                                 long budgetMs,
                                 long rescanMs,
                                 Claims claims,
                                 Function<PortBlock, Result<Unit>> afterProbe,
                                 AtomicInteger bindRetries) {
    public enum Claims {
        ON,
        OFF
    }

    /// A started cluster and the claim that must stay open as long as it runs.
    public record Started<C>(C cluster, PortClaim claim) {
        public static <C> Started<C> started(C cluster, PortClaim claim) {
            return new Started<>(cluster, claim);
        }
    }

    /// How to build, start and stop the cluster for a base port.
    public record ClusterOps<C>(IntFunction<C> clusterAt,
                                Function<C, Promise<Unit>> start,
                                Function<C, Promise<Unit>> stop,
                                TimeSpan startBound) {
        public static <C> ClusterOps<C> clusterOps(IntFunction<C> clusterAt,
                                                   Function<C, Promise<Unit>> start,
                                                   Function<C, Promise<Unit>> stop,
                                                   TimeSpan startBound) {
            return new ClusterOps<>(clusterAt, start, stop, startBound);
        }
    }

    public static final int RANGE_FIRST = 20_000;
    public static final int RANGE_LAST = 31_999;
    public static final long BUDGET_MS = 90_000L;
    public static final long RESCAN_MS = 2_000L;
    public static final int START_ATTEMPTS = 5;
    public static final String DEFAULT_LOCK_DIR = "/tmp/aether-ember-ports";

    public static EmberPortAllocator emberPortAllocator() {
        return new EmberPortAllocator(defaultLockDir(),
                                      RANGE_FIRST,
                                      RANGE_LAST,
                                      BUDGET_MS,
                                      RESCAN_MS,
                                      Claims.ON,
                                      _ -> success(unit()),
                                      new AtomicInteger());
    }

    /// Property, then environment, then the fixed default; printed by the dump so a sandbox that cannot write it is visible.
    public static Path defaultLockDir() {
        return resolveLockDir(option(System.getProperty(PortError.LOCK_DIR_PROPERTY)),
                              option(System.getenv(PortError.LOCK_DIR_ENV)));
    }

    public static Path resolveLockDir(Option<String> property, Option<String> environment) {
        return Path.of(property.orElse(() -> environment).or(DEFAULT_LOCK_DIR));
    }

    public EmberPortAllocator lockDir(Path dir) {
        return new EmberPortAllocator(dir, first, last, budgetMs, rescanMs, claims, afterProbe, bindRetries);
    }

    public EmberPortAllocator range(int newFirst, int newLast) {
        return new EmberPortAllocator(lockDir, newFirst, newLast, budgetMs, rescanMs, claims, afterProbe, bindRetries);
    }

    public EmberPortAllocator budget(long newBudgetMs, long newRescanMs) {
        return new EmberPortAllocator(lockDir, first, last, newBudgetMs, newRescanMs, claims, afterProbe, bindRetries);
    }

    public EmberPortAllocator claims(Claims mode) {
        return new EmberPortAllocator(lockDir, first, last, budgetMs, rescanMs, mode, afterProbe, bindRetries);
    }

    public EmberPortAllocator afterProbe(Function<PortBlock, Result<Unit>> hook) {
        return new EmberPortAllocator(lockDir, first, last, budgetMs, rescanMs, claims, hook, bindRetries);
    }

    /// Cluster starts retried on a fresh block after a bind collision, over every allocator copy sharing this counter.
    public int bindRetryCount() {
        return bindRetries.get();
    }

    public Result<PortClaim> claim(int slots) {
        return claim(slots, Set.of());
    }

    /// Claims a block for a slot pool of `slots` (`poolSize * stride`), skipping `excludedBases`.
    public Result<PortClaim> claim(int slots, Set<Integer> excludedBases) {
        return PortBlock.checkSlots(slots).flatMap(checked -> scan(checked, excludedBases, deadline()));
    }

    /// The start loop: a claim per attempt; a bind collision stops the cluster, releases the claim, counts a retry and moves
    /// to a fresh block; any other start failure fails at once; a cluster that cannot be stopped keeps its claim until JVM
    /// exit (M15).
    @TerminalOperation
    public <C> Result<Started<C>> startCluster(int slots, ClusterOps<C> ops) {
        return attempt(slots, ops, 1, Set.of(), "");
    }

    private <C> Result<Started<C>> attempt(int slots,
                                           ClusterOps<C> ops,
                                           int number,
                                           Set<Integer> excluded,
                                           String last) {
        return number > START_ATTEMPTS
               ? PortError.BindCollisionsExhausted.FACTORY.apply(START_ATTEMPTS, last).result()
               : claim(slots, excluded).flatMap(claim -> startOn(claim, slots, ops, number, excluded));
    }

    @TerminalOperation
    private <C> Result<Started<C>> startOn(PortClaim claim,
                                           int slots,
                                           ClusterOps<C> ops,
                                           int number,
                                           Set<Integer> excluded) {
        var cluster = ops.clusterAt().apply(claim.block().base());

        return ops.start()
                  .apply(cluster)
                  .await(ops.startBound())
                  .fold(cause -> failedStart(claim, cluster, cause, slots, ops, number, excluded),
                        _ -> success(Started.started(cluster, claim)));
    }

    @TerminalOperation
    private <C> Result<Started<C>> failedStart(PortClaim claim,
                                               C cluster,
                                               Cause failure,
                                               int slots,
                                               ClusterOps<C> ops,
                                               int number,
                                               Set<Integer> excluded) {
        return ops.stop()
                  .apply(cluster)
                  .await(ops.startBound())
                  .fold(stopFailure -> PortError.CleanupFailed.FACTORY.apply(claim.block().base(),
                                                                             failure.message(),
                                                                             stopFailure.message())
                                                                      .result(),
                        _ -> afterStop(claim, failure, slots, ops, number, excluded));
    }

    private <C> Result<Started<C>> afterStop(PortClaim claim,
                                             Cause failure,
                                             int slots,
                                             ClusterOps<C> ops,
                                             int number,
                                             Set<Integer> excluded) {
        claim.close();

        return BindProbe.isBindCollision(failure.message())
               ? retry(claim.block().base(),
                       failure,
                       slots,
                       ops,
                       number,
                       excluded)
               : PortError.StartFailed.FACTORY.apply(claim.block().base(),
                                                     failure.message())
                                              .result();
    }

    private <C> Result<Started<C>> retry(int base,
                                         Cause failure,
                                         int slots,
                                         ClusterOps<C> ops,
                                         int number,
                                         Set<Integer> excluded) {
        bindRetries.incrementAndGet();

        return attempt(slots, ops, number + 1, withBase(excluded, base), failure.message());
    }

    private static Set<Integer> withBase(Set<Integer> excluded, int base) {
        return Stream.concat(excluded.stream(),
                             Stream.of(base))
                     .collect(Collectors.toUnmodifiableSet());
    }

    private long deadline() {
        return System.nanoTime() + budgetMs * 1_000_000L;
    }

    private Result<PortClaim> scan(int slots, Set<Integer> excluded, long deadline) {
        return PortWait.poll(deadline,
                             rescanMs,
                             () -> scanRange(slots, excluded),
                             () -> PortError.NoFreeBlock.FACTORY.apply(first, last, budgetMs));
    }

    private Result<Option<PortClaim>> scanRange(int slots, Set<Integer> excluded) {
        return bases().filter(base -> !excluded.contains(base))
                    .mapToObj(base -> PortBlock.portBlock(base, slots).flatMap(this::tryBlock))
                    .filter(EmberPortAllocator::decisive)
                    .findFirst()
                    .orElseGet(() -> success(none()));
    }

    private IntStream bases() {
        return IntStream.range(0, (last - first + 1) / PortBlock.STRIDE).map(index -> first + index * PortBlock.STRIDE);
    }

    private static boolean decisive(Result<Option<PortClaim>> outcome) {
        return outcome.fold(_ -> true, Option::isPresent);
    }

    private Result<Option<PortClaim>> tryBlock(PortBlock block) {
        return claims == Claims.ON
               ? tryLocked(block)
               : tryUnlocked(block);
    }

    private Result<Option<PortClaim>> tryUnlocked(PortBlock block) {
        return BindProbe.blockFree(block)
               ? hook(PortClaim.portClaim(block, new Object(), List.of(), false))
               : success(none());
    }

    private Result<Option<PortClaim>> tryLocked(PortBlock block) {
        var owner = new Object();

        return ClaimRegistry.take(block, owner)
               ? PortLockFile.lockAll(lockDir, block)
                             .onFailureRun(() -> ClaimRegistry.release(block, owner))
                             .flatMap(locks -> locks.map(held -> probe(PortClaim.portClaim(block, owner, held, true)))
                                                    .or(() -> releaseRegistry(block, owner)))
               : success(none());
    }

    private Result<Option<PortClaim>> releaseRegistry(PortBlock block, Object owner) {
        return success(Option.<PortClaim> none()).onSuccessRun(() -> ClaimRegistry.release(block, owner));
    }

    private Result<Option<PortClaim>> probe(PortClaim claim) {
        return BindProbe.blockFree(claim.block())
               ? hook(claim)
               : success(Option.<PortClaim> none()).onSuccessRun(claim::close);
    }

    private Result<Option<PortClaim>> hook(PortClaim claim) {
        return afterProbe.apply(claim.block())
                         .map(_ -> some(claim))
                         .onFailureRun(claim::close);
    }
}
