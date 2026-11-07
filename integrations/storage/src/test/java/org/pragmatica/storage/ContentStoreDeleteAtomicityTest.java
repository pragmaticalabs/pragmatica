package org.pragmatica.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.storage.ContentFixture.failureOf;
import static org.pragmatica.storage.ContentFixture.payload;
import static org.pragmatica.storage.ContentFixture.uploaded;


/// Delete in local mode is atomic against the catalog (A3-1 changed it from the pre-seam store, which dropped the entry
/// first and read the rest after): it reads the object's manifest first, then removes the object from the catalog, which
/// re-reads the entry. Pinned here: a rename landing inside the delete leaves no name binding behind, and an object whose
/// name binding is unreadable fails the delete with nothing changed and no manifest credit released.
@Timeout(value = 15, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ContentStoreDeleteAtomicityTest {
    private static final ObjectName OLD = ObjectName.objectName("old").unwrap();
    private static final ObjectName NEW = ObjectName.objectName("new").unwrap();

    private final ContentFixture fixture = ContentFixture.contentFixture();

    @Test
    void delete_racingARename_leavesNoNameBindingBehind() {
        var plain = fixture.store("alpha");
        var info = uploaded(plain, payload(40, 7));

        plain.bind(OLD, info.ref().id(), Precondition.nameFree()).await().unwrap();

        var manifest = info.ref().manifest();
        var fired = new AtomicBoolean();
        var racing = storeRenamingWhenManifestIsRead(plain, info.ref().id(), manifest, fired);

        racing.delete(info.ref().id()).await().unwrap();

        assertThat(fired.get()).as("the rename ran inside the delete").isTrue();
        assertThat(fixture.storage.resolveRef("o/alpha/" + info.ref().id().asString())).as("entry gone").isEqualTo(none());
        assertThat(fixture.storage.resolveRef("n/alpha/old")).as("old name gone").isEqualTo(none());
        assertThat(fixture.storage.resolveRef("n/alpha/new")).as("new name gone").isEqualTo(none());
    }

    @Test
    void delete_ofAnObjectWithAnUnreadableNameBinding_failsAndChangesNothing() {
        var store = fixture.store("alpha");
        var info = uploaded(store, payload(40, 9));
        var entryRef = "o/alpha/" + info.ref().id().asString();

        store.bind(ObjectName.objectName("x").unwrap(), info.ref().id(), Precondition.nameFree()).await().unwrap();
        fixture.storage.swapRef("n/alpha/x", ContentEnvelope.wrap(ContentEnvelope.NAME, new byte[3])).await().unwrap();

        var manifest = info.ref().manifest();
        var creditsBefore = fixture.refCountOf(manifest);
        var entryBefore = fixture.storage.resolveRef(entryRef);
        var cause = failureOf(store.delete(info.ref().id()));

        assertThat(cause).isEqualTo(ContentStoreError.General.ENTRY_PARSE_FAILED);
        assertThat(fixture.storage.resolveRef(entryRef)).as("entry untouched").isEqualTo(entryBefore);
        assertThat(entryBefore).as("entry existed").isNotEqualTo(none());
        assertThat(fixture.refCountOf(manifest)).as("no manifest credit released").isEqualTo(creditsBefore);
    }

    /// A store over the fixture's instance whose first read of the manifest block first renames the object (binds `NEW`
    /// unconditionally), the point after delete looked the object up and before it removes it.
    private ContentStore storeRenamingWhenManifestIsRead(ContentStore plain, ObjectId id, BlockId manifest, AtomicBoolean fired) {
        var hooked = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                              new Class<?>[]{StorageInstance.class},
                                                              (_, method, args) -> invokeRenaming(plain, id, manifest, fired, method, args));

        return ContentStore.contentStore(hooked, ContentStoreConfig.contentStoreConfig(ContentFixture.CHUNK, Compression.NONE, "alpha", 2, 2)).unwrap();
    }

    private Object invokeRenaming(ContentStore plain, ObjectId id, BlockId manifest, AtomicBoolean fired, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            if (method.getName().equals("get") && manifest.equals(args[0]) && fired.compareAndSet(false, true)) {
                plain.bind(NEW, id, Precondition.unconditional()).await().unwrap();
            }
            return method.invoke(fixture.storage, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
