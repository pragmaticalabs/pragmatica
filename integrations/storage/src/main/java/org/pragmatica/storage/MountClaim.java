package org.pragmatica.storage;

import java.net.InetAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// An exclusive claim on a disk tier's directory, taken when the tier is opened for a node (A0b).
///
/// The directory's blocks and metadata snapshots belong to one node: a second node on the same mount would count the
/// first node's blocks as its own, and its garbage collection could delete files the first still records as present.
/// The claim is a file, [#FILE_NAME], inside the directory (so it lives on the shared mount itself, not beside it),
/// created exclusively and holding the claimer's node id, host, process id and process start time.
///
/// A claim whose holder is this very node (same id, host and process: an in-process restart) is taken again. A claim
/// whose holder cannot be alive is stale and is taken over: a process on this host that no longer exists, or exists
/// with another start time (the id was reused). A claim held from ANOTHER host cannot be checked from here, so it
/// counts as alive and the open is refused until the operator removes the file; the message names it. That is the
/// price of not guessing across hosts.
///
/// Not covered: two nodes racing to take over the same stale claim in the same instant can both read it as stale; the
/// claim each wrote last wins, and the earlier one verifies its own write only once.
public final class MountClaim {
    /// Name of the claim file inside the directory. Not a block: block walks skip it.
    public static final String FILE_NAME = ".mount-claim";
    private static final Logger log = LoggerFactory.getLogger(MountClaim.class);
    private static final long START_TOLERANCE_MILLIS = 2_000L;
    /// The one outcome of `CREATE_NEW` that is an answer, not a failure: the file exists.
    private static final Cause ALREADY_THERE = Causes.cause("mount claim file already exists");

    private final Path file;
    private final Holder self;

    private MountClaim(Path file, Holder self) {
        this.file = file;
        this.self = self;
    }

    /// Claims `directory` for `nodeId`. The directory must exist. Fails with [StorageError.MountClaimed] when a live
    /// holder has it.
    public static Result<MountClaim> mountClaim(Path directory, String nodeId) {
        var file = directory.resolve(FILE_NAME);
        var self = Holder.self(nodeId);

        return createExclusively(file, self.encode()).flatMap(created -> created
                                                                         ? Result.success(new MountClaim(file, self))
                                                                         : takeOverUnlessAlive(file, self, directory));
    }

    /// Gives the claim up, unless the file already names another holder: a successor's claim is never deleted.
    public Result<Unit> release() {
        return FileOps.readString(file)
                      .map(Holder::parse)
                      .flatMap(holder -> holder.filter(self::equals)
                                               .isPresent()
                                         ? FileOps.deleteIfExists(file).mapToUnit()
                                         : Result.success(unit()));
    }

    private static Result<MountClaim> takeOverUnlessAlive(Path file, Holder self, Path directory) {
        return FileOps.readString(file)
                      .map(Holder::parse)
                      .flatMap(holder -> takeOver(file, self, directory, holder));
    }

    private static Result<MountClaim> takeOver(Path file, Holder self, Path directory, Option<Holder> holder) {
        return holder.filter(existing -> existing.holdsAgainst(self))
                     .fold(() -> overwrite(file, self, holder),
                           existing -> StorageError.MountClaimed.mountClaimed(directory,
                                                                              existing.nodeId(),
                                                                              existing.host(),
                                                                              existing.pid())
                                                                .result());
    }

    private static Result<MountClaim> overwrite(Path file, Holder self, Option<Holder> stale) {
        stale.onPresent(holder -> log.info("Taking over mount claim {} from {} (pid {} on {}): not alive",
                                           file,
                                           holder.nodeId(),
                                           holder.pid(),
                                           holder.host()));

        return FileOps.writeString(file,
                                   self.encode())
                      .map(_ -> new MountClaim(file, self));
    }

    /// True when this call created the claim file, false when the file was already there (someone holds the directory, or
    /// held it): `CREATE_NEW` is atomic, so exactly one of two racing claimers creates it.
    private static Result<Boolean> createExclusively(Path file, String content) {
        return Result.lift(MountClaim::createFailure,
                           () -> Files.writeString(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
                     .fold(MountClaim::alreadyThereOrFailed, _ -> Result.success(true));
    }

    private static Cause createFailure(Throwable thrown) {
        return thrown instanceof FileAlreadyExistsException
               ? ALREADY_THERE
               : Causes.fromThrowable(thrown);
    }

    private static Result<Boolean> alreadyThereOrFailed(Cause cause) {
        return cause == ALREADY_THERE
               ? Result.success(false)
               : cause.result();
    }

    /// Who holds the directory: a node id, on a host, as a process started at `startMillis`.
    record Holder(String nodeId, String host, long pid, long startMillis) {
        static Holder self(String nodeId) {
            var handle = ProcessHandle.current();

            return new Holder(nodeId, localHost(), handle.pid(), startMillisOf(handle));
        }

        String encode() {
            return nodeId + "\n" + host + "\n" + pid + "\n" + startMillis + "\n";
        }

        static Option<Holder> parse(String content) {
            var lines = content.split("\n");

            return lines.length >= 4
                   ? parseNumbers(lines)
                   : Option.none();
        }

        private static Option<Holder> parseNumbers(String[] lines) {
            return org.pragmatica.lang.parse.Number.parseLong(lines[2])
                                                   .flatMap(pid -> org.pragmatica.lang.parse.Number.parseLong(lines[3])
                                                                                                   .map(start -> new Holder(lines[0],
                                                                                                                            lines[1],
                                                                                                                            pid,
                                                                                                                            start)))
                                                   .option();
        }

        /// True when this holder must be respected by `claimer`: it is not the claimer itself and may be alive.
        boolean holdsAgainst(Holder claimer) {
            return ! sameProcessAs(claimer) && mayBeAlive(claimer.host());
        }

        private boolean sameProcessAs(Holder other) {
            return nodeId.equals(other.nodeId)
                   && host.equals(other.host)
                   && pid == other.pid;
        }

        private boolean mayBeAlive(String claimerHost) {
            return ! host.equals(claimerHost) || isLiveProcess();
        }

        private boolean isLiveProcess() {
            return Option.from(ProcessHandle.of(pid))
                         .filter(ProcessHandle::isAlive)
                         .filter(handle -> startMatches(handle))
                         .isPresent();
        }

        private boolean startMatches(ProcessHandle handle) {
            return startMillis == 0L || startMillisOf(handle) == 0L || Math.abs(startMillisOf(handle) - startMillis) <= START_TOLERANCE_MILLIS;
        }

        private static long startMillisOf(ProcessHandle handle) {
            return Option.from(handle.info().startInstant())
                         .map(java.time.Instant::toEpochMilli)
                         .or(0L);
        }

        /// FER (degrade forward): a host name that cannot be resolved is recorded as `unknown-host` rather than refusing
        /// the boot. Guarantee earned: the claim is still taken and still keyed by node id and process; what is lost is the
        /// cross-host distinction, so two hosts that both cannot resolve compare as the same host and judge each other's
        /// claim by a local pid lookup (a live claim of the other host can then look gone). Mechanism: none beyond this
        /// single attempt; resolving the name is the operator's fix.
        private static String localHost() {
            return Result.lift(Causes::fromThrowable,
                               () -> InetAddress.getLocalHost().getHostName())
                         .or("unknown-host");
        }
    }
}
