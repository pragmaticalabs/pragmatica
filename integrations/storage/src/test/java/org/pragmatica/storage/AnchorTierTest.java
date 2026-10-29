package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.cloud.aws.s3.S3Client;
import org.pragmatica.cloud.aws.s3.S3Error;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A3-0: the durable-anchor primitive on the two durable tiers ([LocalDiskTier], [RemoteTier] over an S3 simulator that
/// lists keys in ascending order and counts its requests, as S3 does), plus the key and frame codec they share.
@Timeout(60)
class AnchorTierTest {
    private static final AnchorName NAME = nameOf("ahse-dir/content");

    @TempDir
    Path tempDir;

    @Nested
    class Keys {
        /// Fixed width and inverted: the newest version sorts first, whatever the number of digits in the version.
        @Test
        void keysSortNewestFirst_acrossDigitCounts() {
            var versions = List.of(9L, 10L, 99L, 100L, Long.MAX_VALUE - 1, 0L);
            var keys = new ArrayList<>(versions.stream().map(AnchorFrame::key).toList());

            Collections.sort(keys);

            assertThat(keys.stream().map(key -> AnchorFrame.versionOf(key).unwrap()).toList())
                .containsExactly(Long.MAX_VALUE - 1, 100L, 99L, 10L, 9L, 0L);
            assertThat(keys).allMatch(key -> key.length() == 20 && key.startsWith("v"));
        }

        @Test
        void aNameThatIsNotAKey_hasNoVersion() {
            assertThat(AnchorFrame.versionOf(AnchorFrame.key(5) + ".1.partial").isEmpty()).isTrue();
            assertThat(AnchorFrame.versionOf("123").isEmpty()).isTrue();
            assertThat(AnchorFrame.versionOf(AnchorFrame.key(5).substring(1)).isEmpty()).as("no marker, no key").isTrue();
        }

        /// 19 digits above `Long.MAX_VALUE` name no version: not a key, rather than a parse failure that reads as I/O.
        @Test
        void digitsAboveTheLargestVersion_areNoKey() {
            assertThat(AnchorFrame.versionOf("v9999999999999999999").isEmpty()).isTrue();
            assertThat(AnchorFrame.versionOf("v9223372036854775807").unwrap()).isEqualTo(0L);
        }

        /// `/` is stored as `~`, which no segment may hold, so `a/b` and `a_b` (both legal) never share a storage
        /// segment and never interleave their versions.
        @Test
        void aNestedNameAndItsFlattenedLookalike_neverShareAStorageSegment() {
            assertThat(nameOf("a/b").storageSegment()).isNotEqualTo(nameOf("a_b").storageSegment());
            assertThat(nameOf("a/b").storageSegment()).isNotEqualTo(nameOf("a-b").storageSegment());
            assertThat(nameOf("a/b").storageSegment()).isNotEqualTo(nameOf("a.b").storageSegment());
        }

        @Test
        void anchorNames_areValidatedBeforeTheyReachAKeyOrAPath() {
            for (var bad : List.of("", "..", "a/..", "./a", "A", "a/b/c/d", "a//b", "a b", "a~b", "x".repeat(129))) {
                assertThat(AnchorName.anchorName(bad).isFailure()).as("'%s' is refused", bad).isTrue();
            }
            for (var good : List.of("ahse-dir/content", "a", "a/b/c", "ns.v1_x-y", "x".repeat(128))) {
                assertThat(AnchorName.anchorName(good).isSuccess()).as("'%s' is accepted", good).isTrue();
            }
        }
    }

    @Nested
    class Disk {
        @Test
        void latestIsTheHighestOfManyVersionsWrittenOutOfOrder() {
            var tier = fastDisk();

            for (var version : shuffled(300)) {
                await(tier.putAnchor(NAME, version, valueOf(version)));
            }

            assertLatest(tier, NAME, 299);
        }

        /// The disk fills half-way through the frame: only a partial file is torn, so the previous version stays the
        /// latest. Written in place, the torn frame would sit at the new version's key and fail every read.
        @Test
        void aWriteThatFailsBeforeResolving_leavesThePreviousLatest() {
            var failNext = new AtomicBoolean();
            var tier = disk((path, bytes) -> failNext.get()
                                             ? FileOps.writeBytes(path, java.util.Arrays.copyOf(bytes, bytes.length / 2))
                                                      .flatMap(_ -> new StorageError.WriteError("disk full").<Unit> result())
                                             : FileOps.writeBytes(path, bytes),
                            _ -> Result.unitResult());

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            failNext.set(true);

            assertThat(tier.putAnchor(NAME, 2, valueOf(2)).await().isFailure()).isTrue();
            assertLatest(tier, NAME, 1);
            assertThat(listing(anchorDir(NAME))).as("the failed write leaves no partial behind")
                                                .containsExactly(AnchorFrame.key(1));
        }

        /// The anchor's directory is forced after the rename and before the put resolves: without it, the renamed
        /// entry could vanish in a power loss after the acknowledgement.
        @Test
        void aPutForcesItsFileAndThenItsDirectoryBeforeResolving() {
            var events = new CopyOnWriteArrayList<String>();
            var tier = disk((path, bytes) -> FileOps.writeBytesForced(path, bytes)
                                                    .onSuccess(_ -> events.add("file " + path.getFileName())),
                            directory -> FileOps.forceDirectory(directory)
                                                .onSuccess(_ -> events.add("dir " + directory)));

            await(tier.putAnchor(NAME, 7, valueOf(7)));
            events.add("resolved");

            assertThat(events).hasSize(3);
            assertThat(events.get(0)).startsWith("file " + AnchorFrame.key(7)).endsWith(".partial");
            assertThat(events.get(1)).isEqualTo("dir " + anchorDir(NAME));
            assertThat(events.get(2)).isEqualTo("resolved");
        }

        @Test
        void aCorruptNewestVersion_failsClosed_namingTierAndVersion() throws Exception {
            var tier = fastDisk();

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            await(tier.putAnchor(NAME, 2, valueOf(2)));
            flipLastByte(anchorDir(NAME).resolve(AnchorFrame.key(2)));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.LOCAL_DISK, 2);
        }

        /// The version is repeated inside the frame: a copy of an older version's file onto a newer key is refused.
        @Test
        void aFrameCopiedOntoAnotherVersionsKey_isCorrupt() throws Exception {
            var tier = fastDisk();

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            Files.copy(anchorDir(NAME).resolve(AnchorFrame.key(1)), anchorDir(NAME).resolve(AnchorFrame.key(2)));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.LOCAL_DISK, 2);
        }

        @Test
        void anchorBytes_areNotCountedAsBlockBytes_afterReopen() {
            var tier = fastDisk();

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            await(tier.put(blockIdOf(new byte[]{1, 2, 3}), new byte[]{1, 2, 3}));

            var reopened = LocalDiskTier.localDiskTier(tempDir, 1 << 20).unwrap().open().unwrap();

            assertThat(reopened.usedBytes()).isEqualTo(3);
            assertLatest(reopened, NAME, 1);
        }

        @Test
        void pruneRemovesOnlyVersionsBelowTheBound() {
            var tier = fastDisk();

            LongStream.rangeClosed(0, 20).forEach(version -> await(tier.putAnchor(NAME, version, valueOf(version))));
            await(tier.pruneAnchors(NAME, 12, 1));

            assertThat(versionsIn(listing(anchorDir(NAME)))).containsExactlyInAnyOrderElementsOf(range(12, 20));
            assertLatest(tier, NAME, 20);
        }

        @Test
        void aMissingAnchor_isNone() {
            assertThat(fastDisk().latestAnchor(NAME).await().unwrap().isEmpty()).isTrue();
        }

        @Test
        void aNegativeVersion_isRefused() {
            assertThat(fastDisk().putAnchor(NAME, -1, valueOf(1)).await().isFailure()).isTrue();
        }
    }

    @Nested
    class S3 {
        /// More versions than one LIST page holds: the newest is still the first key.
        @Test
        void latestIsTheHighestOf2000VersionsWrittenOutOfOrder() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            for (var version : shuffled(2000)) {
                await(tier.putAnchor(NAME, version, valueOf(version)));
            }

            assertLatest(tier, NAME, 1999);
        }

        @Test
        void latestIsOneListOfOneKeyAndOneGet() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            LongStream.range(0, 50).forEach(version -> await(tier.putAnchor(NAME, version, valueOf(version))));
            s3.requests.clear();

            assertLatest(tier, NAME, 49);
            assertThat(s3.requests).containsExactly("LIST " + anchorPrefix(NAME) + "v 1", "GET " + anchorPrefix(NAME) + AnchorFrame.key(49));
        }

        @Test
        void aFailedPut_leavesThePreviousLatest() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            s3.failPuts.set(true);

            assertThat(tier.putAnchor(NAME, 2, valueOf(2)).await().isFailure()).isTrue();
            s3.failPuts.set(false);
            assertLatest(tier, NAME, 1);
        }

        @Test
        void aCorruptNewestVersion_failsClosed_namingTierAndVersion() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(NAME, 1, valueOf(1)));
            await(tier.putAnchor(NAME, 2, valueOf(2)));
            s3.flipLastByte(anchorPrefix(NAME) + AnchorFrame.key(2));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.REMOTE, 2);
        }

        /// A nested name lists under its own storage segment, so it never shows up as a version of its parent.
        @Test
        void aNestedName_isNotAVersionOfItsParent() {
            var s3 = new SortedS3();
            var tier = remote(s3);
            var parent = nameOf("a");
            var child = nameOf("a/1");

            await(tier.putAnchor(parent, 5, valueOf(5)));
            await(tier.putAnchor(child, 9, valueOf(9)));

            assertLatest(tier, parent, 5);
            assertLatest(tier, child, 9);
        }

        /// Anchors live under `anchors/`, never under a two-hex-character block shard.
        @Test
        void anchorKeys_areNeverUnderABlockShard() {
            var s3 = new SortedS3();
            var tier = remote(s3);
            var block = new byte[]{4, 5, 6};

            await(tier.putAnchor(NAME, 3, valueOf(3)));
            await(tier.put(blockIdOf(block), block));

            assertThat(s3.objects.keySet()).filteredOn(key -> key.contains(AnchorFrame.DIRECTORY))
                                           .allMatch(key -> key.startsWith("pfx/anchors/"));
            assertThat(s3.objects.keySet()).filteredOn(key -> key.matches("pfx/[0-9a-f]{2}/.*"))
                                           .noneMatch(key -> key.contains(AnchorFrame.DIRECTORY));
            assertThat(tier.usedBytes()).as("anchors reserve no block capacity").isEqualTo(3);
        }

        /// One LIST of at most `scanLimit` keys from the newest end; only versions below the bound in it are deleted.
        @Test
        void pruneDeletesOnlyVersionsBelowTheBound_withinOnePage() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            LongStream.rangeClosed(0, 30).forEach(version -> await(tier.putAnchor(NAME, version, valueOf(version))));
            await(tier.pruneAnchors(NAME, 25, 10));

            assertThat(s3.versions(anchorPrefix(NAME))).as("the page held 30..21: 24..21 deleted, older ones wait")
                                                       .containsExactlyInAnyOrderElementsOf(concat(range(0, 20),
                                                                                                   range(25, 30)));
            await(tier.pruneAnchors(NAME, 25, 30));
            assertThat(s3.versions(anchorPrefix(NAME))).containsExactlyInAnyOrderElementsOf(range(25, 30));
        }
    }

    /// B-1 (v-2453): the one key a one-key LIST returns must be a version key, or the read fails closed. None means the
    /// LIST returned no key at all.
    @Nested
    class S3StrayKeys {
        /// The empty "folder" object an S3 console creates at the anchor's prefix is not under the version prefix, so the
        /// newest version is still found. At `fbc86d5a2d` it was the first key listed and the answer was none.
        @Test
        void aFolderPlaceholderAtTheAnchorsPrefix_doesNotHideTheNewestVersion() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(NAME, 7, valueOf(7)));
            s3.objects.put(anchorPrefix(NAME), new byte[0]);

            assertLatest(tier, NAME, 7);
        }

        /// An object under the version prefix that is no version key sorts before every version (`.` < `0`): the read
        /// fails closed, naming it, instead of answering none.
        @Test
        void aStrayObjectUnderTheVersionPrefix_failsClosed_neverNone() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(NAME, 7, valueOf(7)));
            s3.objects.put(anchorPrefix(NAME) + "v.keep", new byte[0]);

            assertThat(causeOf(tier.latestAnchor(NAME))).isInstanceOfSatisfying(AnchorError.AnchorKeyMalformed.class,
                                                                                 malformed -> assertThat(malformed.key()).endsWith("v.keep"));
        }

        @Test
        void aStrayObjectAlone_failsClosed_neverNone() {
            var s3 = new SortedS3();

            s3.objects.put(anchorPrefix(NAME) + "v.keep", new byte[0]);

            assertThat(causeOf(remote(s3).latestAnchor(NAME))).isInstanceOf(AnchorError.AnchorKeyMalformed.class);
        }

        /// T-3: a version the LIST names and the GET does not find fails closed, never none.
        @Test
        void aListedVersionThatTheGetDoesNotFind_failsClosed_neverNone() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(NAME, 7, valueOf(7)));
            s3.missingGets.add(anchorPrefix(NAME) + AnchorFrame.key(7));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.REMOTE, 7);
        }

        /// T-1 on the tier: `a/b` and `a_b` write and read independently.
        @Test
        void aNestedNameAndItsFlattenedLookalike_writeIndependently() {
            var s3 = new SortedS3();
            var tier = remote(s3);

            await(tier.putAnchor(nameOf("a/b"), 5, valueOf(5)));
            await(tier.putAnchor(nameOf("a_b"), 9, valueOf(9)));

            assertLatest(tier, nameOf("a/b"), 5);
            assertLatest(tier, nameOf("a_b"), 9);
        }

        @Test
        void aNegativeVersion_isRefused() {
            assertThat(causeOf(remote(new SortedS3()).putAnchor(NAME, -1, valueOf(1)))).isInstanceOf(AnchorError.NegativeVersion.class);
        }
    }

    /// The frame's length and format checks, each under a VALID checksum, so the check itself is what refuses.
    @Nested
    class FrameChecks {
        @Test
        void aLengthThatDoesNotMatchTheFrame_isCorrupt_evenUnderAValidChecksum() throws Exception {
            var tier = fastDisk();

            await(tier.putAnchor(NAME, 3, valueOf(3)));
            rewriteWithValidCrc(anchorDir(NAME).resolve(AnchorFrame.key(3)), frame -> frame.putInt(9, 1));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.LOCAL_DISK, 3);
        }

        @Test
        void anUnknownFormat_isCorrupt_evenUnderAValidChecksum() throws Exception {
            var tier = fastDisk();

            await(tier.putAnchor(NAME, 3, valueOf(3)));
            rewriteWithValidCrc(anchorDir(NAME).resolve(AnchorFrame.key(3)), frame -> frame.put(0, (byte) 2));

            assertCorrupt(tier.latestAnchor(NAME), TierLevel.LOCAL_DISK, 3);
        }

        private static void rewriteWithValidCrc(Path file, java.util.function.Consumer<java.nio.ByteBuffer> edit) throws Exception {
            var bytes = Files.readAllBytes(file);
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            var crc = new java.util.zip.CRC32C();

            edit.accept(buffer);
            crc.update(bytes, 0, bytes.length - 4);
            buffer.putInt(bytes.length - 4, (int) crc.getValue());
            Files.write(file, bytes);
        }
    }

    @Nested
    class DefaultImplementation {
        /// A durable tier that does not implement anchors refuses; answering "none" would lose every anchored name.
        @Test
        void aDurableTierWithoutAnImplementation_refuses() {
            var tier = new MemoryLikeTier(true);

            assertThat(causeOf(tier.putAnchor(NAME, 1, valueOf(1)))).isInstanceOf(AnchorError.AnchorsUnsupported.class);
            assertThat(causeOf(tier.latestAnchor(NAME))).isInstanceOf(AnchorError.AnchorsUnsupported.class);
        }

        @Test
        void aNonDurableTier_storesNothingAndReturnsNone() {
            var tier = MemoryTier.memoryTier(1 << 20);

            await(tier.putAnchor(NAME, 1, valueOf(1)));

            assertThat(tier.latestAnchor(NAME).await().unwrap().isEmpty()).isTrue();
        }
    }

    // --- fixtures ---

    private LocalDiskTier fastDisk() {
        return disk(FileOps::writeBytes, _ -> Result.unitResult());
    }

    private LocalDiskTier disk(org.pragmatica.lang.Functions.Fn2<Result<Unit>, Path, byte[]> writer,
                               org.pragmatica.lang.Functions.Fn1<Result<Unit>, Path> directoryForcer) {
        return LocalDiskTier.localDiskTier(tempDir,
                                           1 << 20,
                                           timeSpan(30).seconds(),
                                           none(),
                                           Option.some(writer),
                                           Option.some(directoryForcer))
                            .unwrap();
    }

    private static RemoteTier remote(SortedS3 s3) {
        return RemoteTier.durableRemoteTier(s3, "pfx", 8, timeSpan(10).seconds()).unwrap();
    }

    private Path anchorDir(AnchorName name) {
        return tempDir.resolve(AnchorFrame.DIRECTORY).resolve(name.storageSegment()).toAbsolutePath();
    }

    private static String anchorPrefix(AnchorName name) {
        return "pfx/anchors/" + name.storageSegment() + "/";
    }

    static AnchorName nameOf(String value) {
        return AnchorName.anchorName(value).unwrap();
    }

    static byte[] valueOf(long version) {
        return ("root-" + version).getBytes(StandardCharsets.UTF_8);
    }

    static <T> T await(Promise<T> promise) {
        return promise.await(timeSpan(30).seconds())
                      .onFailure(cause -> fail(cause.message()))
                      .unwrap();
    }

    static Cause causeOf(Promise<?> promise) {
        return promise.await(timeSpan(30).seconds())
                      .fold(cause -> cause, value -> fail("expected a failure, got " + value));
    }

    static void assertLatest(StorageTier tier, AnchorName name, long version) {
        var latest = await(tier.latestAnchor(name)).unwrap();

        assertThat(latest.version()).isEqualTo(version);
        assertThat(latest.value()).isEqualTo(valueOf(version));
    }

    static void assertCorrupt(Promise<Option<Anchor>> latest, TierLevel tier, long version) {
        assertThat(causeOf(latest)).isInstanceOfSatisfying(AnchorError.AnchorCorrupt.class, corrupt -> {
            assertThat(corrupt.tier()).isEqualTo(tier);
            assertThat(corrupt.version()).as("the newest version is named; no older one is returned").isEqualTo(version);
        });
    }

    private static List<Long> shuffled(int count) {
        var versions = new ArrayList<>(LongStream.range(0, count).boxed().toList());

        Collections.shuffle(versions, new Random(42));

        return versions;
    }

    private static List<Long> range(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
        var all = new ArrayList<>(first);

        all.addAll(second);

        return all;
    }

    private static List<String> listing(Path directory) {
        return FileOps.list(directory).unwrap().stream().map(path -> path.getFileName().toString()).toList();
    }

    private static List<Long> versionsIn(List<String> names) {
        return names.stream().flatMap(name -> AnchorFrame.versionOf(name).stream()).toList();
    }

    private static void flipLastByte(Path file) throws Exception {
        var bytes = Files.readAllBytes(file);

        bytes[bytes.length - 1] ^= 0x01;
        Files.write(file, bytes);
    }

    static BlockId blockIdOf(byte[] content) {
        return BlockId.blockId(content).unwrap();
    }

    /// S3 as anchors need it: keys listed in ascending UTF-8 order, LIST sees a key once its PUT returned. Records
    /// every request it serves.
    static final class SortedS3 implements S3Client {
        final ConcurrentSkipListMap<String, byte[]> objects = new ConcurrentSkipListMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicBoolean failPuts = new AtomicBoolean();
        final AtomicInteger deletes = new AtomicInteger();
        final java.util.Set<String> missingGets = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public Promise<Unit> putObject(String key, byte[] content, String contentType) {
            requests.add("PUT " + key);

            if (failPuts.get()) {
                return new S3Error.ApiError(503, "SlowDown", "injected").promise();
            }

            objects.put(key, content.clone());

            return Promise.success(unit());
        }

        @Override
        public Promise<Option<byte[]>> getObject(String key) {
            requests.add("GET " + key);

            return missingGets.contains(key)
                   ? Promise.success(Option.none())
                   : Promise.success(Option.option(objects.get(key)).map(byte[]::clone));
        }

        @Override
        public Promise<Boolean> headObject(String key) {
            requests.add("HEAD " + key);

            return Promise.success(objects.containsKey(key));
        }

        @Override
        public Promise<Unit> deleteObject(String key) {
            requests.add("DELETE " + key);
            deletes.incrementAndGet();
            objects.remove(key);

            return Promise.success(unit());
        }

        @Override
        public Promise<List<String>> listObjects(String prefix, int maxKeys) {
            requests.add("LIST " + prefix + " " + maxKeys);

            return Promise.success(objects.tailMap(prefix)
                                          .keySet()
                                          .stream()
                                          .takeWhile(key -> key.startsWith(prefix))
                                          .limit(maxKeys)
                                          .toList());
        }

        void flipLastByte(String key) {
            objects.computeIfPresent(key, (_, bytes) -> {
                var copy = bytes.clone();

                copy[copy.length - 1] ^= 0x01;

                return copy;
            });
        }

        List<Long> versions(String prefix) {
            return objects.keySet()
                          .stream()
                          .filter(key -> key.startsWith(prefix))
                          .flatMap(key -> AnchorFrame.versionOf(key.substring(prefix.length())).stream())
                          .toList();
        }
    }

    /// A tier that implements nothing of anchors, durable or not.
    record MemoryLikeTier(boolean durable) implements StorageTier {
        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(none());
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(false);
        }

        @Override
        public TierLevel level() {
            return TierLevel.LOCAL_DISK;
        }

        @Override
        public long usedBytes() {
            return 0;
        }

        @Override
        public long maxBytes() {
            return 0;
        }

        @Override
        public boolean isDurable() {
            return durable;
        }
    }
}
