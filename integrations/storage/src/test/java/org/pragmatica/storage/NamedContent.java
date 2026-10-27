package org.pragmatica.storage;

import java.io.ByteArrayOutputStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CONTENT_NOT_FOUND;


/// Whole-content access by name over a [ContentStoreV2], for the tests that pin store properties through "put a document
/// under a name, read it back, overwrite it, delete it". The store has no overwrite: a put uploads a new object, binds the
/// name to it and then deletes the object the name held before, so the previous document's blocks are released only after
/// the new one is stored and named. Every property such a test pins is a property of the store's upload, bind and delete.
final class NamedContent {
    private static final int PIECE = 65_539;

    private final ContentStoreV2 store;

    NamedContent(ContentStoreV2 store) {
        this.store = store;
    }

    static NamedContent namedContent(StorageInstance storage, ContentStoreConfig config) {
        return new NamedContent(ContentStoreV2.contentStoreV2(storage, config).unwrap());
    }

    ContentStoreV2 store() {
        return store;
    }

    /// The hex id of the manifest block of the stored document.
    Promise<String> put(String name, byte[] content) {
        return V2Fixture.upload(store, ObjectSpec.objectSpec("application/octet-stream"), content, PIECE)
                        .flatMap(info -> replaceHolder(name, info));
    }

    private Promise<String> replaceHolder(String name, ObjectInfo info) {
        return store.lookup(ObjectName.objectName(name).unwrap(), none())
                    .flatMap(previous -> bindThenRetire(name, info, previous))
                    .map(_ -> info.ref().manifest().hexString());
    }

    private Promise<Unit> bindThenRetire(String name, ObjectInfo info, Option<ObjectInfo> previous) {
        return store.bind(ObjectName.objectName(name).unwrap(), info.ref().id(), Precondition.unconditional())
                    .flatMap(_ -> previous.filter(old -> !old.ref().id().equals(info.ref().id()))
                                          .fold(() -> Promise.success(unit()), old -> store.delete(old.ref().id())));
    }

    /// Fails with `CONTENT_NOT_FOUND` for a name nothing is bound to.
    Promise<Option<byte[]>> get(String name) {
        return store.lookup(ObjectName.objectName(name).unwrap(), none())
                    .flatMap(found -> found.async(CONTENT_NOT_FOUND))
                    .flatMap(info -> store.open(info.ref(), ByteRange.whole()))
                    .flatMap(stream -> drain(stream, new ByteArrayOutputStream()))
                    .map(bytes -> some(bytes));
    }

    private static Promise<byte[]> drain(ChunkStream stream, ByteArrayOutputStream out) {
        return stream.next().flatMap(next -> next.fold(() -> Promise.success(out.toByteArray()),
                                                       buffer -> {
                                                           var bytes = new byte[buffer.remaining()];

                                                           buffer.get(bytes);
                                                           out.write(bytes, 0, bytes.length);

                                                           return drain(stream, out);
                                                       }));
    }

    Promise<Boolean> exists(String name) {
        return store.lookup(ObjectName.objectName(name).unwrap(), none()).map(Option::isPresent);
    }

    Promise<Unit> delete(String name) {
        return store.lookup(ObjectName.objectName(name).unwrap(), none())
                    .flatMap(found -> found.fold(() -> Promise.success(unit()), info -> store.delete(info.ref().id())));
    }
}
