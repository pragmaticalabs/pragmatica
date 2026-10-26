package org.pragmatica.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The exclusive claim one node holds on a disk-tier mount (A0b, `upload-overhaul-spec-v2` section 9 "Two nodes per
/// mount"): an exclusive operating-system lock on a small file in the mount's base directory, held for as long as the
/// node's tier is in use.
///
/// Two nodes that share one mount record their blocks separately and each collects the other's files as its own
/// orphans, so the second node refuses to boot instead of sharing.
///
/// **What earns the guarantee.** The lock is taken with `FileChannel.tryLock` and the kernel releases it when the holding
/// process ends, however it ends, so no node guesses whether another is alive: there is no pid or start-time check, no
/// stale claim to reclaim, and so no window in which two reclaimers both win or a live claim is deleted. The node id and
/// process id written into the file are for the refusal message only; nothing decides on them. The file is never deleted
/// (deleting it would let a waiting claimant lock an unlinked inode while a third creates a new one); [#release] empties it.
///
/// **What it does not establish.**
/// - The lock is advisory and per-host-kernel: it excludes every process that goes through this class on the same
///   kernel, including processes in other containers that bind-mount the same directory. A process that does not take
///   the claim (an operator's `cp`, another program) is not excluded.
/// - On a network filesystem (NFS and similar) the guarantee is only as good as that filesystem's lock service: a mount
///   shared between hosts is excluded only when the server enforces byte-range locks across clients, and a lock service
///   that loses state can release a live holder's lock. A filesystem that does not support locks fails the claim with an
///   I/O error, never silently.
/// - Inside one JVM (Ember, Forge) the lock cannot tell claimants apart, so an in-JVM registry does: a second node id on a
///   held mount is refused, and the same node id taking its mount again is a node restarted inside one JVM, which takes
///   over (the earlier claim is closed first).
/// - Another file handle opened on the claim file inside the holder's JVM can drop the lock on Linux; nothing in this
///   repository opens it, and the in-JVM refusal never does.
public final class MountClaim {
    /// Name of the claim file inside the mount. It is not a block: [LocalDiskTier] leaves it out of the bytes it counts.
    public static final String CLAIM_FILE = ".mount-claim";
    private static final Logger log = LoggerFactory.getLogger(MountClaim.class);
    private static final int HOLDER_BYTES = 512;
    /// Claims held by this JVM, keyed by the claim file's real path. Guards every acquire and release.
    private static final Map<Path, MountClaim> HELD_IN_JVM = new HashMap<>();

    private final Path file;
    private final Holder holder;
    private final FileChannel channel;
    private final FileLock lock;

    private MountClaim(Path file, Holder holder, FileChannel channel, FileLock lock) {
        this.file = file;
        this.holder = holder;
        this.channel = channel;
        this.lock = lock;
    }

    /// Another live node holds the mount. `holderNodeId` is empty and `holderPid` is -1 when the holder's file could not be read.
    public record Held(Path file, String holderNodeId, long holderPid) implements Cause {
        @Override
        public String message() {
            var holder = holderNodeId.isEmpty()
                         ? "another process"
                         : "node '" + holderNodeId + "' (process " + holderPid + ")";

            return "Storage mount " + file.getParent()
                 + " is already claimed by " + holder
                 + "; two nodes must not share a disk tier. Give this node its own disk_path. The claim is an exclusive lock on " + file
                 + " that its holder's exit releases: if no such process exists, the mount is on a network filesystem"
                 + " whose lock service still holds it";
        }
    }

    /// Claims `mount` for `nodeId`, creating the directory when it is missing. A second live claimant is refused with [Held].
    public static Result<MountClaim> claim(Path mount, String nodeId) {
        return FileOps.createDirectoriesDurable(mount)
                      .flatMap(dir -> Result.lift(Causes::fromThrowable,
                                                  () -> dir.toRealPath()))
                      .flatMap(real -> acquire(real.resolve(CLAIM_FILE),
                                               Holder.current(nodeId)));
    }

    /// Gives the mount up: the file is emptied and the lock dropped. A claim already superseded by the same node
    /// restarted in this JVM is left alone.
    public Unit release() {
        synchronized (HELD_IN_JVM) {
            if (HELD_IN_JVM.get(file) == this) {
                HELD_IN_JVM.remove(file);
                close(true);
            }
        }

        return unit();
    }

    private static Result<MountClaim> acquire(Path file, Holder mine) {
        synchronized (HELD_IN_JVM) {
            var here = HELD_IN_JVM.get(file);

            if (here != null && !here.holder.nodeId().equals(mine.nodeId())) {
                return new Held(file, here.holder.nodeId(), here.holder.pid()).result();
            }

            if (here != null) {
                HELD_IN_JVM.remove(file);
                here.close(false);
            }

            return lockFile(file, mine).onSuccess(taken -> HELD_IN_JVM.put(file, taken));
        }
    }

    private static Result<MountClaim> lockFile(Path file, Holder mine) {
        return Result.lift(Causes::fromThrowable,
                           () -> FileChannel.open(file,
                                                  StandardOpenOption.CREATE,
                                                  StandardOpenOption.READ,
                                                  StandardOpenOption.WRITE))
                     .flatMap(channel -> lockOrRefuse(channel, file, mine));
    }

    private static Result<MountClaim> lockOrRefuse(FileChannel channel, Path file, Holder mine) {
        try {
            var lock = channel.tryLock();

            if (lock == null) {
                return refuse(channel, file);
            }

            return record(channel, lock, file, mine);
        } catch (OverlappingFileLockException e) {
            // This JVM holds the file through a channel the registry does not know (another class loader).
            closeQuietly(channel);

            return new Held(file, "", -1).result();
        } catch (IOException e) {
            closeQuietly(channel);

            return Causes.fromThrowable(e).result();
        }
    }

    private static Result<MountClaim> refuse(FileChannel channel, Path file) {
        var held = readHolder(channel).or(new Holder("", -1));

        closeQuietly(channel);

        return new Held(file, held.nodeId(), held.pid()).<MountClaim> result();
    }

    private static Result<MountClaim> record(FileChannel channel, FileLock lock, Path file, Holder mine) {
        try {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(mine.render().getBytes(StandardCharsets.UTF_8)),
                          0);
            channel.force(true);

            return Result.success(new MountClaim(file, mine, channel, lock));
        } catch (IOException e) {
            closeQuietly(channel);

            return Causes.fromThrowable(e).result();
        }
    }

    private static Option<Holder> readHolder(FileChannel channel) {
        var buffer = ByteBuffer.allocate(HOLDER_BYTES);

        try {
            var read = channel.read(buffer, 0);

            return read <= 0
                   ? Option.none()
                   : Holder.parse(new String(buffer.array(), 0, read, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Option.none();
        }
    }

    private void close(boolean empty) {
        try {
            if (empty) {
                channel.truncate(0);
            }

            lock.release();
        } catch (IOException e) {
            log.warn("Could not release the mount claim {}: {}", file, e.getMessage());
        } finally {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Closing a mount claim channel failed: {}", e.getMessage());
        }
    }

    /// Who wrote the claim: for the refusal message, never for a decision.
    private record Holder(String nodeId, long pid) {
        static Holder current(String nodeId) {
            return new Holder(nodeId,
                              ProcessHandle.current().pid());
        }

        static Option<Holder> parse(String text) {
            var lines = Arrays.stream(text.split("\n")).map(String::trim).toList();

            return Option.all(valueOf(lines, "node"),
                              valueOf(lines, "pid").flatMap(Holder::number))
                         .map(Holder::new);
        }

        String render() {
            return "node=" + nodeId + "\npid=" + pid + "\n";
        }

        private static Option<String> valueOf(List<String> lines, String key) {
            return Option.from(lines.stream()
                                    .filter(line -> line.startsWith(key + "="))
                                    .map(line -> line.substring(key.length() + 1))
                                    .findFirst());
        }

        private static Option<Long> number(String text) {
            return org.pragmatica.lang.parse.Number.parseLong(text)
                                                   .option();
        }
    }
}
