package org.pragmatica.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// [LocalDiskTier#scan] while files vanish under it (v2829 FIX 3 and 4). A put renames its `.partial` over the block path
/// and a delete removes the block, both at any moment of a scan; the scan must neither fail for a file that went away nor
/// let one take a page slot. The listing observer removes files deterministically at the worst point: after the listing
/// that named them, before any stat. Before FIX 3 the shard walk read every listed file's attributes itself and failed the
/// scan with `NoSuchFileException`; the page-slot test kills mutant d8 (the vanished-file filter removed).
class LocalDiskTierScanVanishTest {
    private static final String DIRECTORY = "ab";
    private static final String SUBDIRECTORY = "cd";

    @TempDir
    Path dir;

    private Path shard;
    private List<BlockId> blocks;

    @BeforeEach
    void setUp() throws IOException {
        shard = dir.resolve(DIRECTORY).resolve(SUBDIRECTORY);
        Files.createDirectories(shard);
        blocks = IntStream.rangeClosed(1, 6).mapToObj(LocalDiskTierScanVanishTest::blockNamed).toList();
        blocks.forEach(this::create);
    }

    /// Six blocks named `abcd…01` to `abcd…06`, in ascending order; a scan reads only names and stats, never content.
    private static BlockId blockNamed(int index) {
        return BlockId.fromHex(DIRECTORY + SUBDIRECTORY + "%060x".formatted(index)).unwrap();
    }

    private void create(BlockId id) {
        write(shard.resolve(id.hexString()));
    }

    private static void write(Path path) {
        try {
            Files.write(path, path.getFileName().toString().getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path path) {
        try (var tree = Files.walk(path)) {
            tree.sorted(Comparator.reverseOrder()).forEach(LocalDiskTierScanVanishTest::deleteOne);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteOne(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A tier whose listing observer removes `doomed` the first time it sees a file of the shard directory, or the
    /// directory `vanishing` when it sees that directory listed.
    private LocalDiskTier tierRemoving(List<Path> doomed, Option<Path> vanishing) {
        var fired = new AtomicBoolean();

        return LocalDiskTier.localDiskTier(dir,
                                           1 << 20,
                                           TimeSpan.timeSpan(30).seconds(),
                                           Option.none(),
                                           Option.none(),
                                           Option.none(),
                                           path -> removeOnSight(path, doomed, vanishing, fired))
                            .unwrap();
    }

    private Unit removeOnSight(Path path, List<Path> doomed, Option<Path> vanishing, AtomicBoolean fired) {
        if (shard.equals(path.getParent()) && fired.compareAndSet(false, true)) {
            doomed.forEach(LocalDiskTierScanVanishTest::delete);
        }
        vanishing.filter(path::equals).onPresent(LocalDiskTierScanVanishTest::delete);

        return Unit.unit();
    }

    private Path pathOf(BlockId id) {
        return shard.resolve(id.hexString());
    }

    private static List<BlockId> idsOf(Page<BlockStat> page) {
        return page.items().stream().map(BlockStat::id).toList();
    }

    /// A partial renamed away, two blocks deleted and a whole subdirectory removed while the shard is listed: the scan
    /// succeeds and lists exactly the blocks still there.
    @Test
    void scan_succeeds_whenAPartialABlockAndADirectoryVanishWhileListed() throws IOException {
        var partial = shard.resolve(blocks.get(0).hexString() + ".3.partial");
        var otherDirectory = dir.resolve(DIRECTORY).resolve("ce");

        write(partial);
        Files.createDirectories(otherDirectory);
        write(otherDirectory.resolve(DIRECTORY + "ce" + "%060x".formatted(1)));

        var tier = tierRemoving(List.of(partial, pathOf(blocks.get(1)), pathOf(blocks.get(2))), Option.some(otherDirectory));

        var page = tier.scan(KeyShard.all(), Option.none(), 100).await();

        assertThat(page.isSuccess()).as("the scan failed: %s", page).isTrue();
        assertThat(idsOf(page.unwrap())).containsExactly(blocks.get(0), blocks.get(3), blocks.get(4), blocks.get(5));
    }

    /// The two lowest blocks vanish after the listing: the page of four is filled by the four still held and carries a
    /// cursor. Counting the vanished two against the limit gives a short page of two, which ends the scan and silently
    /// skips the last two blocks (mutant d8).
    @Test
    void scan_aBlockThatVanishedWhileListed_takesNoPageSlot() {
        var tier = tierRemoving(List.of(pathOf(blocks.get(0)), pathOf(blocks.get(1))), Option.none());

        var page = tier.scan(KeyShard.all(), Option.none(), 4).await().unwrap();

        assertThat(idsOf(page)).containsExactly(blocks.get(2), blocks.get(3), blocks.get(4), blocks.get(5));
        assertThat(page.next().isPresent()).as("a full page carries a cursor").isTrue();
    }

    /// A subdirectory that is there but cannot be listed fails the scan: only one that is gone lists as empty, because a
    /// sweep must never read a tier it cannot list as an empty one.
    @Test
    void scan_aSubdirectoryThatCannotBeListed_failsTheScan() throws IOException {
        var tier = tierRemoving(List.of(), Option.none());

        Files.setPosixFilePermissions(shard, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(shard), "the user running the test reads any directory (root)");

            assertThat(tier.scan(KeyShard.all(), Option.none(), 100).await().isFailure()).isTrue();
        } finally {
            Files.setPosixFilePermissions(shard, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /// Control: with nothing removed, the observer sees the listing and the scan returns every block.
    @Test
    void scan_withNothingRemoved_listsEveryBlock() {
        var tier = tierRemoving(List.of(), Option.none());

        assertThat(idsOf(tier.scan(KeyShard.all(), Option.none(), 100).await().unwrap())).containsExactlyElementsOf(blocks);
    }
}
