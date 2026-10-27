package org.pragmatica.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The object-entry block of the v2 store: what a committed object is. It holds the object's [ObjectMeta], the id of its
/// manifest block (every object has a manifest, a one-chunk object included) and the entry's version, and it is stored
/// under envelope kind `E` ([ContentEnvelope]) at the ref `o/<namespace>/<object id>`.
///
/// Layout after the envelope: a format byte ([#FORMAT_VERSION]), the manifest block id (32 bytes), the catalog version
/// (long), the bound name (flag, UTF), the second name that may still be bound while a rename is in flight or was interrupted (flag, UTF), then the metadata: display name (flag, UTF), content type (UTF), size (long), digest
/// (32 bytes), owner (UTF), creation time (epoch seconds long, nanos int), expiry (flag, seconds, nanos), attribute count
/// (int) and each key and value (UTF). Nothing may follow; a format byte this build does not know is refused.
///
/// The same block, moved into catalog pages, is what the clustered catalog will hold, so its layout is the one a page
/// leaf needs.
record ObjectEntry(BlockId manifest, long catalogVersion, Option<ObjectName> name, Option<ObjectName> alsoBound, ObjectMeta meta) {
    static final byte FORMAT_VERSION = 1;

    static ObjectEntry objectEntry(BlockId manifest, ObjectMeta meta) {
        return new ObjectEntry(manifest, 1, none(), none(), meta);
    }

    ObjectEntry patched(MetaPatch patch) {
        return new ObjectEntry(manifest, catalogVersion + 1, name, alsoBound, meta.patched(patch));
    }

    /// The entry recording `newName` as its name and `other` as a second name that may still be bound to the object.
    ObjectEntry named(Option<ObjectName> newName, Option<ObjectName> other) {
        return new ObjectEntry(manifest, catalogVersion + 1, newName, other, meta);
    }

    ObjectEntry withVersion(long version) {
        return new ObjectEntry(manifest, version, name, alsoBound, meta);
    }

    ObjectInfo info(String namespace, ObjectId id) {
        return ObjectInfo.objectInfo(ObjectRef.objectRef(namespace, id, manifest, meta.size(), meta.digest()),
                                     name,
                                     meta,
                                     catalogVersion);
    }

    Result<byte[]> toBytes() {
        return Result.lift(Causes::fromThrowable, this::encode);
    }

    private byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);

        out.writeByte(FORMAT_VERSION);
        out.write(manifest.hash());
        out.writeLong(catalogVersion);
        writeOption(out, name.map(ObjectName::value));
        writeOption(out, alsoBound.map(ObjectName::value));
        writeOption(out, meta.displayName());
        out.writeUTF(meta.contentType());
        out.writeLong(meta.size());
        out.write(meta.digest().bytes());
        out.writeUTF(meta.owner());
        writeInstant(out, meta.createdAt());
        out.writeBoolean(meta.expiresAt().isPresent());
        meta.expiresAt().onPresent(expiry -> writeInstantUnchecked(out, expiry));
        out.writeInt(meta.attributes().size());

        for (var attribute : meta.attributes().entrySet()) {
            out.writeUTF(attribute.getKey());
            out.writeUTF(attribute.getValue());
        }

        return ContentEnvelope.wrap(ContentEnvelope.ENTRY, bytes.toByteArray());
    }

    static Result<ObjectEntry> fromBytes(byte[] block) {
        return ContentEnvelope.payloadOf(ContentEnvelope.ENTRY, block)
                             .toResult(ContentStoreError.General.ENTRY_PARSE_FAILED)
                             .flatMap(payload -> Result.lift(Causes::fromThrowable, () -> decode(payload)))
                             .mapError(_ -> ContentStoreError.General.ENTRY_PARSE_FAILED);
    }

    private static ObjectEntry decode(byte[] payload) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(payload));

        if (in.readByte() != FORMAT_VERSION) {
            throw new IOException("unknown entry format");
        }

        var manifest = new BlockId(in.readNBytes(Sha256.LENGTH));
        var version = in.readLong();
        var name = readOption(in).map(ObjectName::new);
        var alsoBound = readOption(in).map(ObjectName::new);
        var displayName = readOption(in);
        var contentType = in.readUTF();
        var size = in.readLong();
        var digest = new Sha256(in.readNBytes(Sha256.LENGTH));
        var owner = in.readUTF();
        var createdAt = readInstant(in);
        var expiresAt = in.readBoolean()
                        ? some(readInstant(in))
                        : Option.<Instant>none();
        var attributes = readAttributes(in);

        if (in.available() > 0 || manifest.hash().length != Sha256.LENGTH || digest.bytes().length != Sha256.LENGTH) {
            throw new IOException("malformed object entry");
        }

        return new ObjectEntry(manifest,
                               version,
                               name,
                               alsoBound,
                               new ObjectMeta(displayName, contentType, size, digest, owner, createdAt, expiresAt, attributes));
    }

    private static Map<String, String> readAttributes(DataInputStream in) throws IOException {
        var count = in.readInt();
        var attributes = new LinkedHashMap<String, String>();

        for (var i = 0; i < count; i++) {
            attributes.put(in.readUTF(), in.readUTF());
        }

        return attributes;
    }

    private static void writeOption(DataOutputStream out, Option<String> value) throws IOException {
        out.writeBoolean(value.isPresent());

        if (value.isPresent()) {
            out.writeUTF(value.or(""));
        }
    }

    private static Option<String> readOption(DataInputStream in) throws IOException {
        return in.readBoolean()
               ? some(in.readUTF())
               : none();
    }

    private static void writeInstant(DataOutputStream out, Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }

    private static void writeInstantUnchecked(DataOutputStream out, Instant instant) {
        try {
            writeInstant(out, instant);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        return Instant.ofEpochSecond(in.readLong(), in.readInt());
    }
}
