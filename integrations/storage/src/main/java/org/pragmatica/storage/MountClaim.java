package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

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
/// mount"): a small file in the mount's base directory naming the node, the process that holds it and when that
/// process started.
///
/// Two nodes that share one mount record their blocks separately and each collects the other's files as its own
/// orphans, so the second node refuses to boot instead of sharing. A claim whose process is gone is reclaimed.
///
/// **What the liveness check can establish.** It asks this host whether the recorded process id is alive with the
/// recorded start time, so a recycled id does not keep a dead node's claim alive. It cannot see a process on another host: two
/// hosts on one network mount are not told apart, and a claim left by a host that is gone is removed by hand, as the
/// refusal says. A claim held by the same node id in the same process is taken over, which is a node restarted inside one JVM.
public final class MountClaim {
    /// Name of the claim file inside the mount. It is not a block: [LocalDiskTier] leaves it out of the bytes it counts.
    public static final String CLAIM_FILE = ".mount-claim";
    private static final Logger log = LoggerFactory.getLogger(MountClaim.class);
    private static final Cause UNREADABLE = Causes.cause("mount claim file is unreadable");

    private final Path file;
    private final Holder holder;

    private MountClaim(Path file, Holder holder) {
        this.file = file;
        this.holder = holder;
    }

    /// Another live node holds the mount.
    public record Held(Path file, String holderNodeId, long holderPid) implements Cause {
        @Override
        public String message() {
            return "Storage mount " + file.getParent()
                   + " is already claimed by node '" + holderNodeId + "' (process " + holderPid
                   + "); two nodes must not share a disk tier. Give this node its own disk_path, or if that process is gone, remove "
                   + file;
        }
    }

    /// Claims `mount` for `nodeId`, creating the directory when it is missing. A second live claimant is refused with [Held].
    public static Result<MountClaim> claim(Path mount, String nodeId) {
        return FileOps.createDirectoriesDurable(mount)
                      .flatMap(dir -> acquire(dir.resolve(CLAIM_FILE), Holder.current(nodeId)));
    }

    /// Gives the mount up when this claim is still the one on file; a claim another node took over is left alone.
    public Unit release() {
        FileOps.readString(file)
               .map(Holder::parse)
               .onSuccess(read -> read.filter(holder::equals)
                                      .onPresent(_ -> FileOps.deleteIfExists(file)));

        return unit();
    }

    private static Result<MountClaim> acquire(Path file, Holder mine) {
        return create(file, mine).fold(cause -> FileOps.exists(file)
                                               ? reclaimOrRefuse(file, mine)
                                               : cause.<MountClaim> result(),
                                       Result::success);
    }

    private static Result<MountClaim> create(Path file, Holder mine) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.write(file,
                                             mine.render()
                                                 .getBytes(StandardCharsets.UTF_8),
                                             StandardOpenOption.CREATE_NEW,
                                             StandardOpenOption.WRITE,
                                             StandardOpenOption.SYNC)).map(_ -> new MountClaim(file, mine));
    }

    private static Result<MountClaim> reclaimOrRefuse(Path file, Holder mine) {
        return FileOps.readString(file)
                      .map(Holder::parse)
                      .flatMap(held -> held.filter(other -> other.alive() && !other.sameClaimant(mine))
                                           .fold(() -> takeOver(file, mine),
                                                 other -> new Held(file, other.nodeId(), other.pid()).<MountClaim> result()));
    }

    private static Result<MountClaim> takeOver(Path file, Holder mine) {
        log.warn("Reclaiming the stale mount claim {} for node '{}'", file, mine.nodeId());

        return FileOps.delete(file).flatMap(_ -> create(file, mine));
    }

    private record Holder(String nodeId, long pid, long startedMillis) {
        static Holder current(String nodeId) {
            var self = ProcessHandle.current();

            return new Holder(nodeId, self.pid(), startedOf(self));
        }

        static Option<Holder> parse(String text) {
            var lines = Arrays.stream(text.split("\n"))
                              .map(String::trim)
                              .toList();

            return Option.all(valueOf(lines, "node"),
                              valueOf(lines, "pid").flatMap(Holder::number),
                              valueOf(lines, "started").flatMap(Holder::number))
                         .map(Holder::new);
        }

        String render() {
            return "node=" + nodeId + "\npid=" + pid + "\nstarted=" + startedMillis + "\n";
        }

        /// Alive when a process with this id exists and started when the claim says: a recycled id fails the second test.
        boolean alive() {
            return ProcessHandle.of(pid)
                                .filter(ProcessHandle::isAlive)
                                .filter(process -> startedMillis == 0 || startedOf(process) == startedMillis)
                                .isPresent();
        }

        boolean sameClaimant(Holder other) {
            return pid == other.pid && nodeId.equals(other.nodeId);
        }

        private static long startedOf(ProcessHandle process) {
            return process.info()
                          .startInstant()
                          .map(java.time.Instant::toEpochMilli)
                          .orElse(0L);
        }

        private static Option<String> valueOf(java.util.List<String> lines, String key) {
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
