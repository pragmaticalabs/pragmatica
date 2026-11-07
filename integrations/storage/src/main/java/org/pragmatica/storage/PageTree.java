package org.pragmatica.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A copy-on-write B+tree of [CatalogPage] blocks, keyed by strings in [String#compareTo] order. Pure: it reads pages
/// through a [PageSource] and returns the pages a change writes; it stores nothing and decides nothing about where the
/// root is kept.
///
/// A [#write] rewrites only the pages on the paths to the edited keys, each once per call however many of its keys the
/// call edits (a batch shares one rewrite per page), and leaves every other page shared with the tree it started from.
/// The tree it started from stays fully readable through its own root. A page holds at most `fanout` cells or children;
/// a fuller page is split into the fewest pages that fit, evenly. A page that loses all its cells is dropped from its
/// parent, and a root with one child is replaced by that child. Pages that only shrink are not merged with their
/// neighbours, so a tree emptied in places keeps the depth it had.
sealed interface PageTree {
    record unused() implements PageTree {}

    /// The cluster catalog's default page fanout: depth 2 holds up to about a million keys.
    int DEFAULT_FANOUT = 1000;

    /// Reads a page by id: from the tiers in a cluster store, from a map in tests, and from the pages of an unstored
    /// write when one change is built on another.
    @FunctionalInterface
    interface PageSource {
        Promise<CatalogPage> page(BlockId id);
    }

    /// The result of a [#write]: the new root (none for an empty tree) and every page block the new tree has that the old
    /// one did not, by id.
    record TreeWrite(Option<BlockId> root, Map<BlockId, byte[]> blocks) {
        public TreeWrite {
            blocks = Map.copyOf(blocks);
        }
    }

    /// A rewritten subtree: its pages as children, in key order, at the height of the page it replaced, and the blocks
    /// written for it.
    record Level(List<CatalogPage.Child> pages, Map<BlockId, byte[]> blocks) {
        static Level level(List<CatalogPage.Child> pages, Map<BlockId, byte[]> blocks) {
            return new Level(pages, blocks);
        }
    }

    /// An encoded page: its child entry and its block.
    record Encoded(CatalogPage.Child child, byte[] bytes) {}

    /// The value stored under `key`.
    static Promise<Option<byte[]>> get(Option<BlockId> root, String key, PageSource source) {
        return root.fold(() -> Promise.success(Option.<byte[]> none()), id -> getIn(id, key, source));
    }

    private static Promise<Option<byte[]>> getIn(BlockId id, String key, PageSource source) {
        return source.page(id)
                     .flatMap(page -> switch (page) {
                                  case CatalogPage.Leaf leaf -> Promise.success(valueIn(leaf, key));
                                  case CatalogPage.Branch branch -> getIn(branch.children().get(childFor(branch, key)).page(),
                                                                          key,
                                                                          source);
                              });
    }

    private static Option<byte[]> valueIn(CatalogPage.Leaf leaf, String key) {
        return Option.from(leaf.cells()
                               .stream()
                               .filter(cell -> cell.key()
                                                   .equals(key))
                               .map(CatalogPage.Cell::value)
                               .findFirst());
    }

    /// The child of `branch` whose key range holds `key`: the last child whose low key is not above it, else the first.
    private static int childFor(CatalogPage.Branch branch, String key) {
        var children = branch.children();
        var index = 0;

        for (var i = 1; i < children.size() && children.get(i).low().compareTo(key) <= 0; i++) {
            index = i;
        }

        return index;
    }

    /// Every cell of the tree, in key order.
    static Promise<List<CatalogPage.Cell>> cells(Option<BlockId> root, PageSource source) {
        return root.fold(() -> Promise.success(List.<CatalogPage.Cell> of()), id -> cellsIn(id, source));
    }

    private static Promise<List<CatalogPage.Cell>> cellsIn(BlockId id, PageSource source) {
        return source.page(id)
                     .flatMap(page -> switch (page) {
                                  case CatalogPage.Leaf leaf -> Promise.success(leaf.cells());
                                  case CatalogPage.Branch branch -> allOf(branch.children().stream().map(child -> cellsIn(child.page(),
                                                                                                                          source)).toList()).map(PageTree::concatenated);
                              });
    }

    /// The number of page levels from the root to the leaves: 0 for an empty tree, 1 for a lone leaf.
    static Promise<Integer> depth(Option<BlockId> root, PageSource source) {
        return root.fold(() -> Promise.success(0), id -> depthOf(id, source));
    }

    private static Promise<Integer> depthOf(BlockId id, PageSource source) {
        return source.page(id)
                     .flatMap(page -> switch (page) {
                                  case CatalogPage.Leaf _ -> Promise.success(1);
                                  case CatalogPage.Branch branch -> depthOf(firstChild(branch),
                                                                            source).map(below -> below + 1);
                              });
    }

    /// The first child of `branch`. Total for every branch this tree meets: [CatalogPage#fromBytes] refuses a page with no
    /// cells or children, and [#write] never builds one.
    private static BlockId firstChild(CatalogPage.Branch branch) {
        return branch.children()
                     .getFirst()
                     .page();
    }

    /// The tree `root` with `edits` applied: a present value is stored under its key, an absent one removes the key.
    /// `fanout` is at least 2.
    static Promise<TreeWrite> write(Option<BlockId> root,
                                    NavigableMap<String, Option<byte[]>> edits,
                                    int fanout,
                                    PageSource source) {
        return Verify.ensure(fanout,
                             Verify.Is::greaterThanOrEqualTo,
                             2,
                             ContentStoreError.InvalidConfig.invalidConfig("catalog fanout", "must be at least 2"))
                     .async()
                     .flatMap(_ -> edits.isEmpty()
                                   ? Promise.success(new TreeWrite(root,
                                                                   Map.of()))
                                   : root.fold(() -> leafLevel(new TreeMap<>(),
                                                               edits,
                                                               fanout).async(),
                                               id -> rewrite(id, edits, fanout, source))
                                         .flatMap(level -> topOf(level, fanout, source)));
    }

    /// The single root above `level`: pages are grouped under new branches until one is left, and a root branch with
    /// one child is replaced by that child.
    private static Promise<TreeWrite> topOf(Level level, int fanout, PageSource source) {
        return switch (level.pages()
                            .size()) {
            case 0 -> Promise.success(new TreeWrite(none(), level.blocks()));
            case 1 -> collapsed(level.pages().getFirst().page(),
                                level.blocks(),
                                source);
            default -> branchLevel(level.pages(), level.blocks(), fanout).async().flatMap(above -> topOf(above,
                                                                                                         fanout,
                                                                                                         source));
        };
    }

    /// `id` as the root, unless it is a branch with one child: then that child is, in turn, and the branch's block is not
    /// part of the write. A page this write wrote is read from its own blocks.
    private static Promise<TreeWrite> collapsed(BlockId id, Map<BlockId, byte[]> blocks, PageSource source) {
        return Option.option(blocks.get(id))
                     .fold(() -> source.page(id),
                           bytes -> CatalogPage.fromBytes(bytes).async())
                     .flatMap(page -> switch (page) {
                                  case CatalogPage.Branch branch when branch.children().size() == 1 -> collapsed(firstChild(branch),
                                                                                                                 without(blocks,
                                                                                                                         id),
                                                                                                                 source);
                                  default -> Promise.success(new TreeWrite(some(id),
                                                                           blocks));
                              });
    }

    private static Map<BlockId, byte[]> without(Map<BlockId, byte[]> blocks, BlockId id) {
        var kept = new HashMap<>(blocks);

        kept.remove(id);

        return kept;
    }

    /// The page `id` with `edits` (all inside its key range) applied: zero or more pages at the same height.
    private static Promise<Level> rewrite(BlockId id,
                                          NavigableMap<String, Option<byte[]>> edits,
                                          int fanout,
                                          PageSource source) {
        return source.page(id)
                     .flatMap(page -> switch (page) {
                                  case CatalogPage.Leaf leaf -> leafLevel(cellMap(leaf),
                                                                          edits,
                                                                          fanout).async();
                                  case CatalogPage.Branch branch -> rewriteBranch(branch, edits, fanout, source);
                              });
    }

    private static TreeMap<String, byte[]> cellMap(CatalogPage.Leaf leaf) {
        var cells = new TreeMap<String, byte[]>();

        leaf.cells().forEach(cell -> cells.put(cell.key(), cell.value()));

        return cells;
    }

    private static Result<Level> leafLevel(TreeMap<String, byte[]> cells,
                                           NavigableMap<String, Option<byte[]>> edits,
                                           int fanout) {
        edits.forEach((key, value) -> value.onPresent(bytes -> cells.put(key, bytes))
                                           .onEmpty(() -> cells.remove(key)));
        var all = cells.entrySet().stream().map(cell -> CatalogPage.Cell.cell(cell.getKey(), cell.getValue())).toList();

        return built(chunked(all, fanout).stream().map(CatalogPage::leaf).toList(),
                     Map.of());
    }

    /// Each child whose key range holds an edit is rewritten (all of them at once); the others are kept as they are.
    private static Promise<Level> rewriteBranch(CatalogPage.Branch branch,
                                                NavigableMap<String, Option<byte[]>> edits,
                                                int fanout,
                                                PageSource source) {
        var children = branch.children();
        var parts = new ArrayList<Promise<Level>>();

        for (var i = 0; i < children.size(); i++) {
            var mine = editsOf(children, i, edits);
            var child = children.get(i);

            parts.add(mine.isEmpty()
                      ? Promise.success(kept(child))
                      : rewrite(child.page(), mine, fanout, source));
        }

        return allOf(parts).flatMap(levels -> branchLevel(levels.stream()
                                                                .flatMap(level -> level.pages()
                                                                                       .stream())
                                                                .toList(),
                                                          blocksOf(levels),
                                                          fanout).async());
    }

    /// A child kept unchanged: its low key is the one its parent recorded, its page is not re-read.
    private static Level kept(CatalogPage.Child child) {
        return Level.level(List.of(child), Map.of());
    }

    /// The edits in the key range of `children[index]`: from its low key (from the start, for the first child) up to the
    /// next child's low key.
    private static NavigableMap<String, Option<byte[]>> editsOf(List<CatalogPage.Child> children,
                                                                int index,
                                                                NavigableMap<String, Option<byte[]>> edits) {
        var from = index == 0
                   ? edits
                   : edits.tailMap(children.get(index).low(),
                                   true);

        return index == children.size() - 1
               ? from
               : from.headMap(children.get(index + 1).low(),
                              false);
    }

    private static Result<Level> branchLevel(List<CatalogPage.Child> children, Map<BlockId, byte[]> below, int fanout) {
        return built(chunked(children, fanout).stream().map(CatalogPage::branch).toList(),
                     below);
    }

    /// `pages` encoded, with their blocks added to `below`.
    private static Result<Level> built(List<CatalogPage> pages, Map<BlockId, byte[]> below) {
        return Result.allOf(pages.stream()
                                 .map(page -> page.toBytes()
                                                  .flatMap(bytes -> BlockId.blockId(bytes).map(id -> new Encoded(CatalogPage.Child.child(page.low(),
                                                                                                                                         id),
                                                                                                                 bytes))))
                                 .toList()).map(encoded -> Level.level(encoded.stream().map(Encoded::child).toList(),
                                                                       withBlocks(below, encoded)));
    }

    private static Map<BlockId, byte[]> withBlocks(Map<BlockId, byte[]> below, List<Encoded> encoded) {
        var blocks = new HashMap<>(below);

        encoded.forEach(page -> blocks.put(page.child().page(),
                                           page.bytes()));

        return blocks;
    }

    private static Map<BlockId, byte[]> blocksOf(List<Level> levels) {
        var blocks = new HashMap<BlockId, byte[]>();

        levels.forEach(level -> blocks.putAll(level.blocks()));

        return blocks;
    }

    /// `items` cut into the fewest runs of at most `fanout`, as even as they can be; none for no items.
    private static <T> List<List<T>> chunked(List<T> items, int fanout) {
        var count = (items.size() + fanout - 1) / fanout;
        var runs = new ArrayList<List<T>>();

        for (var i = 0; i < count; i++) {
            runs.add(items.subList(items.size() * i / count, items.size() * (i + 1) / count));
        }

        return runs;
    }

    private static <T> Promise<List<T>> allOf(List<Promise<T>> promises) {
        return Promise.allOf(promises).flatMap(results -> Result.allOf(results).async());
    }

    private static <T> List<T> concatenated(List<List<T>> lists) {
        return lists.stream()
                    .flatMap(List::stream)
                    .toList();
    }
}
