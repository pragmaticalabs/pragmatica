package org.pragmatica.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// One page of a cluster catalog tree ([PageTree]): a leaf of key/value cells or a branch of children, each child named by
/// the lowest key it holds. Pages are immutable blocks: a change writes new pages and a new root, and the old ones stay
/// readable for as long as they are kept.
///
/// Layout (format 1) after the envelope (kind `P`, [ContentEnvelope]): a format byte ([#FORMAT_VERSION]), a kind byte (`L`
/// leaf, `B` branch), the count (int), then per cell the key (UTF) and the value (length int, bytes), or per child the low
/// key (UTF) and the child page id (32 bytes). Keys are in ascending order and unique. Nothing may follow; a format byte
/// this build does not know is refused.
@SuppressWarnings("JBCT-EX-01")  // binary codec leaf over java.io Data streams; toBytes and fromBytes lift it into Result
sealed interface CatalogPage {
    byte FORMAT_VERSION = 1;
    byte LEAF = 'L';
    byte BRANCH = 'B';

    /// A key and the value stored under it.
    record Cell(String key, byte[] value) {
        static Cell cell(String key, byte[] value) {
            return new Cell(key, value);
        }
    }

    /// A child page and the lowest key it holds. The first child of a branch also holds every key below its low key.
    record Child(String low, BlockId page) {
        static Child child(String low, BlockId page) {
            return new Child(low, page);
        }
    }

    record Leaf(List<Cell> cells) implements CatalogPage {
        public Leaf {
            cells = List.copyOf(cells);
        }
    }

    record Branch(List<Child> children) implements CatalogPage {
        public Branch {
            children = List.copyOf(children);
        }
    }

    static CatalogPage leaf(List<Cell> cells) {
        return new Leaf(cells);
    }

    static CatalogPage branch(List<Child> children) {
        return new Branch(children);
    }

    /// The lowest key the page holds.
    default String low() {
        return switch (this) {
            case Leaf leaf -> leaf.cells().getFirst().key();
            case Branch branch -> branch.children().getFirst().low();
        };
    }

    default Result<byte[]> toBytes() {
        return Result.lift(Causes::fromThrowable, this::encode);
    }

    private byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);

        out.writeByte(FORMAT_VERSION);
        switch (this) {
            case Leaf leaf -> writeLeaf(out, leaf);
            case Branch branch -> writeBranch(out, branch);
        }

        return ContentEnvelope.wrap(ContentEnvelope.PAGE, bytes.toByteArray());
    }

    private static void writeLeaf(DataOutputStream out, Leaf leaf) throws IOException {
        out.writeByte(LEAF);
        out.writeInt(leaf.cells().size());
        for (var cell : leaf.cells()) {
            out.writeUTF(cell.key());
            out.writeInt(cell.value().length);
            out.write(cell.value());
        }
    }

    private static void writeBranch(DataOutputStream out, Branch branch) throws IOException {
        out.writeByte(BRANCH);
        out.writeInt(branch.children().size());
        for (var child : branch.children()) {
            out.writeUTF(child.low());
            out.write(child.page().hash());
        }
    }

    static Result<CatalogPage> fromBytes(byte[] block) {
        return ContentEnvelope.payloadOf(ContentEnvelope.PAGE, block)
                              .toResult(ContentStoreError.General.CATALOG_PAGE_PARSE_FAILED)
                              .flatMap(payload -> Result.lift(Causes::fromThrowable,
                                                              () -> decode(payload)))
                              .mapError(_ -> ContentStoreError.General.CATALOG_PAGE_PARSE_FAILED);
    }

    private static CatalogPage decode(byte[] payload) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(payload));

        if (in.readByte() != FORMAT_VERSION) {
            throw new IOException("unknown catalog page format");
        }

        var kind = in.readByte();
        var count = in.readInt();

        if (count < 1) {
            throw new IOException("empty catalog page");
        }

        var page = switch (kind) {
            case LEAF -> readLeaf(in, count);
            case BRANCH -> readBranch(in, count);
            default -> throw new IOException("unknown catalog page kind");
        };

        if (in.available() > 0) {
            throw new IOException("malformed catalog page");
        }

        return page;
    }

    private static CatalogPage readLeaf(DataInputStream in, int count) throws IOException {
        var cells = new ArrayList<Cell>();

        for (var i = 0; i < count; i++) {
            var key = in.readUTF();
            var length = in.readInt();

            if (length < 0 || length > in.available()) {
                throw new IOException("malformed catalog cell");
            }

            cells.add(Cell.cell(key, in.readNBytes(length)));
        }

        return leaf(cells);
    }

    private static CatalogPage readBranch(DataInputStream in, int count) throws IOException {
        var children = new ArrayList<Child>();

        for (var i = 0; i < count; i++) {
            var low = in.readUTF();
            var hash = in.readNBytes(Sha256.LENGTH);

            if (hash.length != Sha256.LENGTH) {
                throw new IOException("malformed catalog child");
            }

            children.add(Child.child(low, new BlockId(hash)));
        }

        return branch(children);
    }
}
