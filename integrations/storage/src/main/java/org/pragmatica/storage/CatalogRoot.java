package org.pragmatica.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The root block of one namespace's cluster catalog: the catalog version, the object tree (object id -> [ObjectEntry]),
/// the name tree (name -> object id), the bytes the catalog's objects hold and their number. Each commit writes a new root
/// with the version one above the root it was built on; the cluster store points consensus at the newest one.
///
/// Layout (format 1) after the envelope (kind `R`, [ContentEnvelope]): a format byte ([#FORMAT_VERSION]), the version
/// (long), the object tree root and the name tree root (each a flag, then 32 bytes when present), the committed bytes
/// (long) and the object count (long). Nothing may follow; a format byte this build does not know is refused.
@SuppressWarnings("JBCT-EX-01")  // binary codec leaf over java.io Data streams; toBytes and fromBytes lift it into Result
record CatalogRoot(long version,
                   Option<BlockId> idTree,
                   Option<BlockId> nameTree,
                   long committedBytes,
                   long objectCount) {
    static final byte FORMAT_VERSION = 1;

    /// The catalog before its first commit: version 0, no objects, no names.
    static CatalogRoot empty() {
        return new CatalogRoot(0, none(), none(), 0, 0);
    }

    static CatalogRoot catalogRoot(long version,
                                   Option<BlockId> idTree,
                                   Option<BlockId> nameTree,
                                   long committedBytes,
                                   long objectCount) {
        return new CatalogRoot(version, idTree, nameTree, committedBytes, objectCount);
    }

    Result<byte[]> toBytes() {
        return Result.lift(Causes::fromThrowable, this::encode);
    }

    private byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);

        out.writeByte(FORMAT_VERSION);
        out.writeLong(version);
        writeTree(out, idTree);
        writeTree(out, nameTree);
        out.writeLong(committedBytes);
        out.writeLong(objectCount);

        return ContentEnvelope.wrap(ContentEnvelope.ROOT, bytes.toByteArray());
    }

    private static void writeTree(DataOutputStream out, Option<BlockId> tree) throws IOException {
        out.writeBoolean(tree.isPresent());
        if (tree.isPresent()) {
            out.write(tree.map(BlockId::hash).or(new byte[0]));
        }
    }

    static Result<CatalogRoot> fromBytes(byte[] block) {
        return ContentEnvelope.payloadOf(ContentEnvelope.ROOT, block)
                              .toResult(ContentStoreError.General.CATALOG_ROOT_PARSE_FAILED)
                              .flatMap(payload -> Result.lift(Causes::fromThrowable,
                                                              () -> decode(payload)))
                              .mapError(_ -> ContentStoreError.General.CATALOG_ROOT_PARSE_FAILED);
    }

    private static CatalogRoot decode(byte[] payload) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(payload));

        if (in.readByte() != FORMAT_VERSION) {
            throw new IOException("unknown catalog root format");
        }

        var version = in.readLong();
        var idTree = readTree(in);
        var nameTree = readTree(in);
        var committedBytes = in.readLong();
        var objectCount = in.readLong();

        if (in.available() > 0) {
            throw new IOException("malformed catalog root");
        }

        return new CatalogRoot(version, idTree, nameTree, committedBytes, objectCount);
    }

    private static Option<BlockId> readTree(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return none();
        }

        var hash = in.readNBytes(Sha256.LENGTH);

        if (hash.length != Sha256.LENGTH) {
            throw new IOException("malformed catalog tree id");
        }

        return some(new BlockId(hash));
    }
}
