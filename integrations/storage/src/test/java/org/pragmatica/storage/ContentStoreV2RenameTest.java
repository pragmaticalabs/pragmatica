package org.pragmatica.storage;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.GarbageCollectorConfig.garbageCollectorConfig;
import static org.pragmatica.storage.StorageGarbageCollector.storageGarbageCollector;
import static org.pragmatica.storage.V2Fixture.failureOf;
import static org.pragmatica.storage.V2Fixture.payload;
import static org.pragmatica.storage.V2Fixture.read;
import static org.pragmatica.storage.V2Fixture.uploaded;


/// Local-mode rename is never unnamed and may briefly carry both names: the new name is bound first, the old one retired
/// after, and the call returns after both. The window between the steps is driven by a hook on the instance's `dropRef` of
/// a name binding (the retire step), never by timing.
class ContentStoreV2RenameTest {
    private static final ObjectName OLD = ObjectName.objectName("old").unwrap();
    private static final ObjectName NEW = ObjectName.objectName("new").unwrap();

    private final V2Fixture fixture = V2Fixture.v2Fixture();
    private final ContentStoreV2 plain = fixture.store("alpha");

    /// A store over the fixture's instance whose retire of a name binding first waits for `beforeRetire`; a failed hook
    /// stands for a crash at that point: the retire never happens.
    private ContentStoreV2 storeWithRetireHook(Function<String, Promise<Unit>> beforeRetire) {
        var hooked = (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                              new Class<?>[]{StorageInstance.class},
                                                              (_, method, args) -> invoke(beforeRetire, method, args));

        return ContentStoreV2.contentStoreV2(hooked, ContentStoreConfig.contentStoreConfig(V2Fixture.CHUNK, Compression.NONE, "alpha", 2, 2)).unwrap();
    }

    private Object invoke(Function<String, Promise<Unit>> beforeRetire, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            if (method.getName().equals("dropRef") && args[0] instanceof String ref && ref.startsWith("n/")) {
                return beforeRetire.apply(ref).flatMap(_ -> fixture.storage.dropRef(ref));
            }

            return method.invoke(fixture.storage, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private ObjectInfo namedAs(ContentStoreV2 store, ObjectName name, byte[] data) {
        var info = uploaded(store, data);

        store.bind(name, info.ref().id(), Precondition.nameFree()).await().unwrap();

        return info;
    }

    private Option<ObjectId> found(ContentStoreV2 store, ObjectName name) {
        return store.lookup(name, none()).await().unwrap().map(info -> info.ref().id());
    }

    /// A concurrent reader never sees not-found for an object under rename: the old name until it is retired, the new name
    /// from the bind on. Observed at the point between the steps. Mutation `retirefirst` (retire before bind) -> red.
    @Test
    void aReaderBetweenTheStepsFindsTheObjectUnderBothNames() {
        var seen = new ArrayList<String>();
        var store = storeWithRetireHook(_ -> {
            seen.add("old=" + found(plain, OLD).isPresent() + ",new=" + found(plain, NEW).isPresent());
            return Promise.success(unit());
        });
        var info = namedAs(store, OLD, payload(8, 41));

        store.bind(NEW, info.ref().id(), Precondition.nameFree()).await().unwrap();

        assertThat(seen).as("one retire, and both names resolved when it ran").containsExactly("old=true,new=true");
        assertThat(found(store, OLD)).as("the old name is retired once rename returned").isEqualTo(none());
        assertThat(found(store, NEW)).isEqualTo(Option.some(info.ref().id()));
        assertThat(store.stat(info.ref().id()).await().unwrap().unwrap().name().map(ObjectName::value)).isEqualTo(Option.some("new"));
    }

    /// A crash between the steps (stop after the bind) leaves both names resolving to the same object, and a release or
    /// collector pass reclaims nothing. Mutation `retirefirst` (the crash stops after the retire) -> red.
    @Test
    void aCrashBetweenTheSteps_leavesBothNamesOnTheSameObject_andReclaimsNothing() {
        var crashed = storeWithRetireHook(_ -> Causes.cause("crash after the bind").promise());
        var data = payload(40, 42);
        var info = namedAs(crashed, OLD, data);
        var id = info.ref().id();
        var gc = storageGarbageCollector(fixture.storage, fixture.metadata, garbageCollectorConfig(0, 500));

        assertThat(failureOf(crashed.bind(NEW, id, Precondition.nameFree()))).isNotNull();

        assertThat(found(plain, OLD)).as("the old name still resolves").isEqualTo(Option.some(id));
        assertThat(found(plain, NEW)).as("the new name resolves to the same object").isEqualTo(Option.some(id));
        assertThat(read(plain, plain.lookup(NEW, none()).await().unwrap().unwrap().ref(), ByteRange.whole())).isEqualTo(data);
        var held = new ArrayList<BlockId>(fixture.chunksOf(info));

        held.add(info.ref().manifest());
        held.add(fixture.storage.resolveRef("o/alpha/" + id.asString()).unwrap());
        held.add(fixture.storage.resolveRef("n/alpha/old").unwrap());
        held.add(fixture.storage.resolveRef("n/alpha/new").unwrap());
        assertThat(held).as("chunks, manifest, entry and both bindings (equal bytes: one block, a credit per binding)").allSatisfy(block -> assertThat(fixture.refCountOf(block)).isGreaterThanOrEqualTo(1));

        gc.activate();
        gc.collectGarbage();
        assertThat(held).as("a collector pass reclaimed none of them").allSatisfy(block -> assertThat(fixture.storage.exists(block).await().unwrap()).isTrue());
        assertThat(found(plain, OLD)).isEqualTo(Option.some(id));
        assertThat(found(plain, NEW)).isEqualTo(Option.some(id));
        assertThat(read(plain, info.ref(), ByteRange.whole())).isEqualTo(data);
    }

    /// Rename returns only after both steps: while the retire is pending the call is pending, and it resolves once the retire
    /// has run. Mutation `resolveearly` (resolve after the bind, retire fire-and-forget) -> red.
    @Test
    void renameReturnsOnlyAfterBothSteps() {
        Promise<Unit> retireGate = Promise.promise();
        List<String> order = new ArrayList<>();
        var store = storeWithRetireHook(_ -> retireGate.map(u -> {
            order.add("retire");
            return u;
        }));
        var info = namedAs(store, OLD, payload(8, 43));

        var rename = store.bind(NEW, info.ref().id(), Precondition.nameFree()).map(done -> {
            order.add("returned");
            return done;
        });

        assertThat(rename.isResolved()).as("the retire is still pending").isFalse();
        retireGate.succeed(unit());
        rename.await().unwrap();

        assertThat(order).containsExactly("retire", "returned");
        assertThat(found(store, OLD)).isEqualTo(none());
    }
}
