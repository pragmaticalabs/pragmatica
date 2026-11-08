package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The copy-on-write page tree against a [TreeMap] model: random batches of puts and removes, checked after every batch,
/// with every earlier root still reading the state it was written for.
class PageTreeTest {
    private final CatalogFixture.Pages pages = new CatalogFixture.Pages();

    private record Snapshot(Option<BlockId> root, Map<String, String> model) {}

    private Option<BlockId> written(Option<BlockId> root, NavigableMap<String, Option<byte[]>> edits, int fanout) {
        var write = PageTree.write(root, edits, fanout, pages.source()).await().unwrap();

        pages.store(write.blocks());

        if (fanout < PageTree.DEFAULT_FANOUT) {
            // the large trees skip it: re-reading every page after every batch is what would dominate their run time
            assertThat(reachable(write.root())).as("every page a write returns is part of its tree")
                                                .containsAll(write.blocks().keySet());
        }

        return write.root();
    }

    private Set<BlockId> reachable(Option<BlockId> root) {
        var found = new HashSet<BlockId>();

        root.onPresent(id -> collect(id, found));

        return found;
    }

    private void collect(BlockId id, Set<BlockId> found) {
        found.add(id);
        if (pages.source().page(id).await().unwrap() instanceof CatalogPage.Branch branch) {
            branch.children().forEach(child -> collect(child.page(), found));
        }
    }

    private Map<String, String> contents(Option<BlockId> root) {
        var contents = new TreeMap<String, String>();

        PageTree.cells(root, pages.source())
                .await()
                .unwrap()
                .forEach(cell -> contents.put(cell.key(), new String(cell.value(), StandardCharsets.UTF_8)));

        return contents;
    }

    private static Option<byte[]> value(String text) {
        return some(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void randomBatches_matchATreeMap_andEveryEarlierRootKeepsItsState() {
        for (var fanout : new int[]{2, 3, 4, 7, 32}) {
            randomBatches(fanout);
        }
    }

    private void randomBatches(int fanout) {
        for (var seed = 0L; seed < 20; seed++) {
            var random = new SplittableRandom(seed * 31 + fanout);
            var root = Option.<BlockId> none();
            var model = new TreeMap<String, String>();
            var snapshots = new ArrayList<Snapshot>();

            for (var batch = 0; batch < 30; batch++) {
                var edits = new TreeMap<String, Option<byte[]>>();
                var size = 1 + random.nextInt(random.nextBoolean() ? 3 : 40);

                for (var i = 0; i < size; i++) {
                    var key = "k" + random.nextInt(300);

                    if (random.nextInt(3) == 0) {
                        edits.put(key, none());
                        model.remove(key);
                    } else {
                        var text = "v" + batch + "." + i;

                        edits.put(key, value(text));
                        model.put(key, text);
                    }
                }

                root = written(root, edits, fanout);
                snapshots.add(new Snapshot(root, new TreeMap<>(model)));

                assertThat(contents(root)).as("seed %d batch %d", seed, batch).isEqualTo(model);
                for (var probe = 0; probe < 10; probe++) {
                    var key = "k" + random.nextInt(300);
                    var found = PageTree.get(root, key, pages.source()).await().unwrap().map(bytes -> new String(bytes, StandardCharsets.UTF_8));

                    assertThat(found).as("get %s", key).isEqualTo(Option.option(model.get(key)));
                }
            }

            for (var snapshot : snapshots) {
                assertThat(contents(snapshot.root())).isEqualTo(snapshot.model());
            }
        }
    }

    @Test
    void removingEveryKey_leavesAnEmptyTree() {
        var edits = new TreeMap<String, Option<byte[]>>();

        for (var i = 0; i < 50; i++) {
            edits.put("k" + i, value("v"));
        }

        var root = written(none(), edits, 3);

        edits.replaceAll((_, _) -> none());

        var emptied = written(root, edits, 3);

        assertThat(emptied.isEmpty()).isTrue();
        assertThat(PageTree.depth(emptied, pages.source()).await().unwrap()).isZero();
    }

    @Test
    void removingAllButOneKey_collapsesTheRootChainToALeaf() {
        var edits = new TreeMap<String, Option<byte[]>>();

        for (var i = 0; i < 50; i++) {
            edits.put("k%02d".formatted(i), value("v"));
        }

        var root = written(none(), edits, 3);

        edits.remove("k25");
        edits.replaceAll((_, _) -> none());

        var shrunk = written(root, edits, 3);

        assertThat(PageTree.depth(root, pages.source()).await().unwrap()).isGreaterThan(1);
        assertThat(PageTree.depth(shrunk, pages.source()).await().unwrap()).as("a root with one child is replaced by it").isEqualTo(1);
        assertThat(contents(shrunk)).isEqualTo(Map.of("k25", "v"));
    }

    @Test
    void noEdits_keepTheRootAndWriteNothing() {
        var root = written(none(), new TreeMap<>(Map.of("a", value("1"))), 4);
        var write = PageTree.write(root, new TreeMap<>(), 4, pages.source()).await().unwrap();

        assertThat(write.root()).isEqualTo(root);
        assertThat(write.blocks()).isEmpty();
    }

    @Test
    void oneEdit_rewritesOnlyThePathToItsKey() {
        var edits = new TreeMap<String, Option<byte[]>>();

        for (var i = 0; i < 1000; i++) {
            edits.put("k%04d".formatted(i), value("v"));
        }

        var root = written(none(), edits, 10);
        var depth = PageTree.depth(root, pages.source()).await().unwrap();
        var one = PageTree.write(root, new TreeMap<>(Map.of("k0500", value("w"))), 10, pages.source()).await().unwrap();

        assertThat(depth).isEqualTo(3);
        assertThat(one.blocks()).as("one page per level").hasSize(depth);
    }

    @Test
    void aFanoutBelowTwo_isRefused() {
        var write = PageTree.write(none(), new TreeMap<>(Map.of("a", value("1"))), 1, pages.source()).await();

        assertThat(write.isFailure()).isTrue();
    }

    @Test
    void aMillionKeys_atTheDefaultFanout_fitInDepthTwo() {
        var edits = new TreeMap<String, Option<byte[]>>();
        var one = value("x");

        for (var i = 0; i < PageTree.DEFAULT_FANOUT * PageTree.DEFAULT_FANOUT; i++) {
            edits.put("k%07d".formatted(i), one);
        }

        var root = written(none(), edits, PageTree.DEFAULT_FANOUT);

        assertThat(PageTree.depth(root, pages.source()).await().unwrap()).isEqualTo(2);

        edits.clear();
        edits.put("k9999999", one);

        var over = written(root, edits, PageTree.DEFAULT_FANOUT);

        assertThat(PageTree.depth(over, pages.source()).await().unwrap()).as("one key more needs a third level").isEqualTo(3);
        assertThat(PageTree.get(over, "k0123456", pages.source()).await().unwrap().isPresent()).isTrue();
    }

    @Test
    void incrementalInserts_atTheDefaultFanout_stayInDepthTwo() {
        var root = Option.<BlockId> none();
        var one = value("x");

        for (var batch = 0; batch < 200; batch++) {
            var edits = new TreeMap<String, Option<byte[]>>();

            for (var i = 0; i < 1000; i++) {
                edits.put("k%07d".formatted(batch * 1000 + i), one);
            }

            root = written(root, edits, PageTree.DEFAULT_FANOUT);
        }

        assertThat(PageTree.depth(root, pages.source()).await().unwrap()).isEqualTo(2);
        assertThat(PageTree.cells(root, pages.source()).await().unwrap()).hasSize(200_000);
    }

    @Test
    void pageCodec_roundTrips_andRefusesWhatItDoesNotKnow() {
        var leaf = CatalogPage.leaf(List.of(CatalogPage.Cell.cell("a", new byte[]{1, 2}), CatalogPage.Cell.cell("b", new byte[0])));
        var bytes = leaf.toBytes().unwrap();
        var back = (CatalogPage.Leaf) CatalogPage.fromBytes(bytes).unwrap();

        assertThat(back.cells()).hasSize(2);
        assertThat(back.cells().getFirst().key()).isEqualTo("a");
        assertThat(back.cells().getFirst().value()).containsExactly(1, 2);

        var branch = CatalogPage.branch(List.of(CatalogPage.Child.child("a", new BlockId(new byte[Sha256.LENGTH]))));
        var branchBack = (CatalogPage.Branch) CatalogPage.fromBytes(branch.toBytes().unwrap()).unwrap();

        assertThat(branchBack.children().getFirst().low()).isEqualTo("a");

        var unknownFormat = bytes.clone();

        unknownFormat[ContentEnvelope.marker().length + 1] = 2;

        var trailing = Arrays.copyOf(bytes, bytes.length + 1);

        assertThat(CatalogPage.fromBytes(unknownFormat).isFailure()).isTrue();
        assertThat(CatalogPage.fromBytes(trailing).isFailure()).isTrue();
        assertThat(CatalogPage.fromBytes(ContentEnvelope.wrap(ContentEnvelope.ENTRY, new byte[]{1})).isFailure()).isTrue();
        assertThat(CatalogPage.fromBytes(CatalogPage.leaf(List.of()).toBytes().unwrap()).isFailure()).as("an empty page").isTrue();
    }

    /// The kind byte is the only thing separating a page from a root: a valid page body under the root kind is refused by
    /// the page decoder, a valid root body under the page kind by the root decoder, and the kinds are distinct.
    @Test
    void pageAndRootKinds_areNotInterchangeable() {
        var page = CatalogPage.leaf(List.of(CatalogPage.Cell.cell("a", new byte[]{1}))).toBytes().unwrap();
        var root = CatalogRoot.catalogRoot(5, some(new BlockId(new byte[Sha256.LENGTH])), none(), 77, 3).toBytes().unwrap();
        var pageAsRoot = page.clone();
        var rootAsPage = root.clone();

        pageAsRoot[ContentEnvelope.marker().length] = ContentEnvelope.ROOT;
        rootAsPage[ContentEnvelope.marker().length] = ContentEnvelope.PAGE;

        assertThat(ContentEnvelope.PAGE).isNotEqualTo(ContentEnvelope.ROOT);
        assertThat(CatalogPage.fromBytes(pageAsRoot).isFailure()).as("page body under the root kind").isTrue();
        assertThat(CatalogRoot.fromBytes(rootAsPage).isFailure()).as("root body under the page kind").isTrue();
        assertThat(CatalogRoot.fromBytes(page).isFailure()).as("a page into the root decoder").isTrue();
        assertThat(CatalogPage.fromBytes(root).isFailure()).as("a root into the page decoder").isTrue();
    }

    /// The refusal names what failed: a page refusal says page, a root refusal says root.
    @Test
    void codecRefusals_nameTheirBlockKind() {
        var garbage = new byte[]{1, 2, 3};
        var pageCause = ContentFixture.failureOf(CatalogPage.fromBytes(garbage).async());
        var rootCause = ContentFixture.failureOf(CatalogRoot.fromBytes(garbage).async());

        assertThat(pageCause).isEqualTo(ContentStoreError.General.CATALOG_PAGE_PARSE_FAILED);
        assertThat(pageCause.message()).contains("catalog page");
        assertThat(rootCause).isEqualTo(ContentStoreError.General.CATALOG_ROOT_PARSE_FAILED);
        assertThat(rootCause.message()).contains("catalog root");
    }
}
