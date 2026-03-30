package org.pragmatica.aether.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Default snapshot manager that writes text-format snapshots to local disk.
/// Dual-condition trigger: snapshot when mutation count exceeds threshold OR time exceeds interval.
final class DefaultSnapshotManager implements SnapshotManager {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSnapshotManager.class);
    private static final String SNAPSHOT_PREFIX = "snapshot-";
    private static final String SNAPSHOT_SUFFIX = ".dat";
    private static final String LATEST_LINK = "LATEST";
    private static final HexFormat HEX = HexFormat.of();

    private final MetadataStore metadataStore;
    private final SnapshotConfig config;
    private final AtomicLong lastSnapshotEpoch = new AtomicLong();
    private final AtomicBoolean snapshotInProgress = new AtomicBoolean();
    private volatile long lastSnapshotTimestamp;

    DefaultSnapshotManager(MetadataStore metadataStore, SnapshotConfig config) {
        this.metadataStore = metadataStore;
        this.config = config;
        this.lastSnapshotTimestamp = System.currentTimeMillis();
    }

    @Override
    public void maybeSnapshot() {
        if (shouldSnapshot() && snapshotInProgress.compareAndSet(false, true)) {
            try {
                forceSnapshot();
            } finally {
                snapshotInProgress.set(false);
            }
        }
    }

    @Override
    public void forceSnapshot() {
        var snapshot = captureSnapshot();
        writeSnapshotToDisk(snapshot)
            .onSuccess(_ -> recordSnapshotTaken(snapshot))
            .onFailure(cause -> LOG.warn("Snapshot write failed: {}", cause.message()));
    }

    @Override
    public Option<MetadataSnapshot> restoreFromLatest() {
        return readLatestSnapshotPath()
            .flatMap(this::readAndValidateSnapshot);
    }

    @Override
    public long lastSnapshotEpoch() {
        return lastSnapshotEpoch.get();
    }

    @Override
    public long lastSnapshotTimestamp() {
        return lastSnapshotTimestamp;
    }

    // --- Trigger logic ---

    private boolean shouldSnapshot() {
        var currentEpoch = metadataStore.currentEpoch();
        var epochDelta = currentEpoch - lastSnapshotEpoch.get();
        var timeDelta = System.currentTimeMillis() - lastSnapshotTimestamp;

        return epochDelta >= config.mutationThreshold() || timeDelta >= config.maxIntervalMillis();
    }

    // --- Snapshot capture ---

    private MetadataSnapshot captureSnapshot() {
        return MetadataSnapshot.metadataSnapshot(
            metadataStore.currentEpoch(),
            config.nodeId(),
            metadataStore.listAllLifecycles(),
            metadataStore.listAllRefs()
        );
    }

    private void recordSnapshotTaken(MetadataSnapshot snapshot) {
        lastSnapshotEpoch.set(snapshot.epoch());
        lastSnapshotTimestamp = snapshot.timestamp();
        pruneOldSnapshots();
        LOG.info("Snapshot taken: epoch={}, lifecycles={}, refs={}",
                 snapshot.epoch(), snapshot.lifecycles().size(), snapshot.refs().size());
    }

    // --- Disk write ---

    private Result<Path> writeSnapshotToDisk(MetadataSnapshot snapshot) {
        return ensureDirectory()
            .flatMap(_ -> serializeAndWrite(snapshot));
    }

    private Result<Path> ensureDirectory() {
        return Result.lift(SnapshotError.DirectoryCreateFailed::new,
                           () -> Files.createDirectories(config.snapshotPath()));
    }

    private Result<Path> serializeAndWrite(MetadataSnapshot snapshot) {
        var fileName = snapshotFileName(snapshot.epoch());
        var filePath = config.snapshotPath().resolve(fileName);
        var content = serializeSnapshot(snapshot);

        return Result.lift(SnapshotError.WriteFailed::new, () -> Files.writeString(filePath, content, StandardCharsets.UTF_8))
                     .flatMap(_ -> updateLatestLink(filePath));
    }

    private Result<Path> updateLatestLink(Path snapshotFile) {
        var latestPath = config.snapshotPath().resolve(LATEST_LINK);

        return Result.lift(SnapshotError.WriteFailed::new,
                           () -> Files.writeString(latestPath, snapshotFile.getFileName().toString(), StandardCharsets.UTF_8));
    }

    // --- Disk read ---

    private Option<Path> readLatestSnapshotPath() {
        var latestPath = config.snapshotPath().resolve(LATEST_LINK);

        return Result.lift(SnapshotError.ReadFailed::new, () -> Files.readString(latestPath, StandardCharsets.UTF_8))
                     .map(String::trim)
                     .map(name -> config.snapshotPath().resolve(name))
                     .onFailure(cause -> LOG.debug("No LATEST snapshot file: {}", cause.message()))
                     .option();
    }

    private Option<MetadataSnapshot> readAndValidateSnapshot(Path path) {
        return Result.lift(SnapshotError.ReadFailed::new, () -> Files.readString(path, StandardCharsets.UTF_8))
                     .flatMap(DefaultSnapshotManager::parseSnapshot)
                     .filter(SnapshotError.INTEGRITY_CHECK_FAILED, MetadataSnapshot::isValid)
                     .onFailure(cause -> LOG.warn("Snapshot restore failed: {}", cause.message()))
                     .option();
    }

    // --- Pruning ---

    private void pruneOldSnapshots() {
        Result.lift(SnapshotError.PruneFailed::new, this::performPrune)
              .onFailure(cause -> LOG.warn("Snapshot pruning failed: {}", cause.message()));
    }

    private void performPrune() throws IOException {
        var snapshots = listSnapshotFiles();

        if (snapshots.size() <= config.retentionCount()) {
            return;
        }

        var toDelete = snapshots.subList(0, snapshots.size() - config.retentionCount());
        for (var file : toDelete) {
            Files.deleteIfExists(file);
        }
    }

    private List<Path> listSnapshotFiles() throws IOException {
        var result = new ArrayList<Path>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(config.snapshotPath(), SNAPSHOT_PREFIX + "*" + SNAPSHOT_SUFFIX)) {
            stream.forEach(result::add);
        }

        result.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return result;
    }

    // --- Serialization ---

    private static String serializeSnapshot(MetadataSnapshot snapshot) {
        var sb = new StringBuilder();
        sb.append("epoch=").append(snapshot.epoch()).append('\n');
        sb.append("timestamp=").append(snapshot.timestamp()).append('\n');
        sb.append("nodeId=").append(snapshot.nodeId()).append('\n');
        sb.append("contentHash=").append(HEX.formatHex(snapshot.contentHash())).append('\n');
        sb.append('\n');
        sb.append("# Lifecycles\n");
        snapshot.lifecycles().forEach(lc -> appendLifecycleLine(sb, lc));
        sb.append('\n');
        sb.append("# Refs\n");
        snapshot.refs().forEach((name, id) -> appendRefLine(sb, name, id));
        return sb.toString();
    }

    private static void appendLifecycleLine(StringBuilder sb, BlockLifecycle lc) {
        var tiers = lc.presentIn().stream()
                      .map(TierLevel::name)
                      .collect(Collectors.joining(","));
        sb.append(lc.blockId().hexString())
          .append('|').append(tiers)
          .append('|').append(lc.refCount())
          .append('|').append(lc.lastAccessedAt())
          .append('|').append(lc.createdAt())
          .append('\n');
    }

    private static void appendRefLine(StringBuilder sb, String name, BlockId id) {
        sb.append(name).append('|').append(id.hexString()).append('\n');
    }

    // --- Deserialization ---

    private static Result<MetadataSnapshot> parseSnapshot(String content) {
        return Result.lift(SnapshotError.ParseFailed::new, () -> parseSnapshotContent(content));
    }

    private static MetadataSnapshot parseSnapshotContent(String content) {
        var lines = content.split("\n");
        var headers = parseHeaders(lines);

        var epoch = Long.parseLong(headers.get("epoch"));
        var timestamp = Long.parseLong(headers.get("timestamp"));
        var nodeId = headers.get("nodeId");
        var contentHash = HEX.parseHex(headers.get("contentHash"));

        var lifecycles = new ArrayList<BlockLifecycle>();
        var refs = new LinkedHashMap<String, BlockId>();
        var section = "";

        for (var line : lines) {
            if (line.equals("# Lifecycles")) {
                section = "lifecycles";
            } else if (line.equals("# Refs")) {
                section = "refs";
            } else if (!line.isEmpty() && !line.startsWith("#") && !line.contains("=")) {
                parseDataLine(section, line, lifecycles, refs);
            }
        }

        return new MetadataSnapshot(epoch, timestamp, nodeId, lifecycles, refs, contentHash);
    }

    private static Map<String, String> parseHeaders(String[] lines) {
        var headers = new LinkedHashMap<String, String>();

        for (var line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                break;
            }
            var eqIndex = line.indexOf('=');
            if (eqIndex > 0) {
                headers.put(line.substring(0, eqIndex), line.substring(eqIndex + 1));
            }
        }

        return headers;
    }

    private static void parseDataLine(String section, String line,
                                      List<BlockLifecycle> lifecycles, Map<String, BlockId> refs) {
        if ("lifecycles".equals(section)) {
            parseLifecycleLine(line).onSuccess(lifecycles::add);
        } else if ("refs".equals(section)) {
            parseRefLine(line, refs);
        }
    }

    private static Result<BlockLifecycle> parseLifecycleLine(String line) {
        var parts = line.split("\\|");

        return BlockId.fromHex(parts[0])
                      .map(id -> buildLifecycle(id, parts));
    }

    private static BlockLifecycle buildLifecycle(BlockId id, String[] parts) {
        var tiers = parseTierSet(parts[1]);
        var refCount = Integer.parseInt(parts[2]);
        var lastAccessed = Long.parseLong(parts[3]);
        var created = Long.parseLong(parts[4]);

        return BlockLifecycle.blockLifecycle(id, tiers, refCount, lastAccessed, created);
    }

    private static Set<TierLevel> parseTierSet(String tierString) {
        if (tierString.isEmpty()) {
            return EnumSet.noneOf(TierLevel.class);
        }

        var tiers = EnumSet.noneOf(TierLevel.class);

        for (var name : tierString.split(",")) {
            tiers.add(TierLevel.valueOf(name));
        }

        return tiers;
    }

    private static void parseRefLine(String line, Map<String, BlockId> refs) {
        var pipeIndex = line.indexOf('|');

        if (pipeIndex > 0) {
            var name = line.substring(0, pipeIndex);
            BlockId.fromHex(line.substring(pipeIndex + 1))
                   .onSuccess(id -> refs.put(name, id));
        }
    }

    // --- File naming ---

    private static String snapshotFileName(long epoch) {
        return SNAPSHOT_PREFIX + String.format("%06d", epoch) + SNAPSHOT_SUFFIX;
    }
}
