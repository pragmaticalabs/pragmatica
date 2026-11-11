// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Byte-range file locks, one byte per port, in ONE lock file in a fixed directory (M9, M11).
///
/// The channel is opened `READ, WRITE, CREATE` (an exclusive lock on a read-only channel throws
/// `NonWritableChannelException`), once per JVM and path, and never closed. Locks are fcntl record locks: the kernel drops
/// them when the process dies. The lock file is never deleted. The directory is created world-writable with the sticky bit
/// (1777) and the file 0666 so a second user can lock it; cost: any local user can hold locks and starve claims (denial of
/// service on a shared machine, not corruption). A file that cannot be opened or created REFUSES the claim, naming the path
/// and the override; it never proceeds unlocked. A second user racing the creator between `createDirectory` and its chmod
/// can be refused spuriously (residual; the override is the remedy).
sealed interface PortLockFile {
    Logger log = LoggerFactory.getLogger(PortLockFile.class);
    String FILE_NAME = "ports.lock";
    int DIR_MODE = 01777;
    int FILE_MODE = 0666;
    ConcurrentMap<Path, FileChannel> CHANNELS = new ConcurrentHashMap<>();
    Object OPEN_LOCK = new Object();

    /// All ports of the block locked, or none: `none` when any port is held elsewhere (everything taken is released).
    static Result<Option<List<FileLock>>> lockAll(Path lockDir, PortBlock block) {
        return channel(lockDir).flatMap(channel -> lockFrom(channel,
                                                            block.ports().boxed().toList(),
                                                            0,
                                                            List.of()));
    }

    @Contract
    static void release(List<FileLock> locks) {
        locks.forEach(PortLockFile::release);
    }

    @Contract
    static void release(FileLock lock) {
        // design-out: a lock whose release fails stays held until JVM exit, when the kernel drops it (fcntl semantics);
        // the only cost is that one port stays unavailable to other claimants for the rest of this JVM's life
        Result.lift(Causes::fromThrowable, lock::release).run(cause -> log.warn("Releasing a port lock failed; it stays held until JVM exit: {}",
                                                                                cause.message()),
                                                              PortLockFile::ignore);
    }

    private static void ignore(Unit unit) {}

    private static Result<FileChannel> channel(Path lockDir) {
        return option(CHANNELS.get(lockDir)).map(Result::success)
                     .or(() -> openOnce(lockDir));
    }

    private static Result<FileChannel> openOnce(Path lockDir) {
        synchronized (OPEN_LOCK) {
            return option(CHANNELS.get(lockDir)).map(Result::success)
                         .or(() -> open(lockDir));
        }
    }

    private static Result<FileChannel> open(Path lockDir) {
        var file = lockDir.resolve(FILE_NAME);

        return ensureDirectory(lockDir).flatMap(_ -> openChannel(file))
                              .onSuccess(channel -> CHANNELS.put(lockDir, channel));
    }

    private static Result<FileChannel> openChannel(Path file) {
        var created = !Files.exists(file);

        return Result.lift(refused(file),
                           () -> FileChannel.open(file,
                                                  StandardOpenOption.READ,
                                                  StandardOpenOption.WRITE,
                                                  StandardOpenOption.CREATE))
                     .flatMap(channel -> created
                                         ? chmod(file, FILE_MODE).map(_ -> channel)
                                         : success(channel));
    }

    private static Result<Path> ensureDirectory(Path lockDir) {
        return Files.isDirectory(lockDir)
               ? success(lockDir)
               : Result.lift(refused(lockDir.resolve(FILE_NAME)),
                             () -> Files.createDirectories(lockDir))
                       .flatMap(_ -> chmod(lockDir, DIR_MODE));
    }

    private static Result<Path> chmod(Path path, int mode) {
        return Result.lift(refused(path), () -> Files.setAttribute(path, "unix:mode", mode));
    }

    private static Fn1<Cause, Throwable> refused(Path file) {
        return t -> PortError.LockFileUnavailable.FACTORY.apply(file.toString(), Causes.fromThrowable(t));
    }

    private static Result<Option<List<FileLock>>> lockFrom(FileChannel channel,
                                                           List<Integer> ports,
                                                           int index,
                                                           List<FileLock> held) {
        return index == ports.size()
               ? success(some(held))
               : tryLock(channel,
                         ports.get(index)).onFailureRun(() -> release(held))
                        .flatMap(lock -> afterLock(channel, ports, index, held, lock));
    }

    private static Result<Option<List<FileLock>>> afterLock(FileChannel channel,
                                                            List<Integer> ports,
                                                            int index,
                                                            List<FileLock> held,
                                                            Option<FileLock> lock) {
        return lock.fold(() -> missed(held),
                         taken -> lockFrom(channel, ports, index + 1, append(held, taken)));
    }

    private static Result<Option<List<FileLock>>> missed(List<FileLock> held) {
        return success(Option.<List<FileLock>> none()).onSuccessRun(() -> release(held));
    }

    private static List<FileLock> append(List<FileLock> held, FileLock lock) {
        var next = new ArrayList<>(held);

        next.add(lock);

        return List.copyOf(next);
    }

    private static Result<Option<FileLock>> tryLock(FileChannel channel, int port) {
        return Result.lift(t -> lockCause(port, t),
                           () -> option(channel.tryLock(port, 1, false)))
                     .fold(PortLockFile::heldOrFailed, Result::success);
    }

    private static Cause lockCause(int port, Throwable t) {
        return t instanceof OverlappingFileLockException
               ? PortError.General.PORT_HELD
               : PortError.LockFailed.FACTORY.apply(port, Causes.fromThrowable(t));
    }

    /// Held by another channel of this JVM (another copy of this class): taken, not an error.
    private static Result<Option<FileLock>> heldOrFailed(Cause cause) {
        return cause == PortError.General.PORT_HELD
               ? success(none())
               : cause.result();
    }

    record unused() implements PortLockFile {}
}
