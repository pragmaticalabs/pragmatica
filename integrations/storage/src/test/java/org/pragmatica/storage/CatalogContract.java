package org.pragmatica.storage;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.storage.CatalogFixture.entry;
import static org.pragmatica.storage.CatalogFixture.name;


/// The catalog contract: what every [Catalog] implementation holds, run once per implementation by a subclass. Only
/// properties of a finished call are pinned here; what a reader sees DURING a call (a local rename briefly carries both
/// names, a cluster rename is one step) is stated by each implementation and tested beside it.
abstract class CatalogContract {
    private Catalog catalog;

    /// A fresh, empty catalog of namespace [CatalogFixture#NAMESPACE].
    abstract Catalog newCatalog();

    @BeforeEach
    void setUp() {
        catalog = newCatalog();
    }

    private ObjectId inserted(long size, int seed) {
        var id = ObjectId.objectId();

        catalog.insert(id, entry(size, seed)).await().unwrap();

        return id;
    }

    private static <T> Cause failureOf(Promise<T> promise) {
        return promise.await()
                      .fold(cause -> cause, value -> { throw new AssertionError("expected a failure, got " + value); });
    }

    private Option<ObjectId> holderOf(String value) {
        return catalog.lookup(name(value), none()).await().unwrap().map(info -> info.ref().id());
    }

    private Option<ObjectName> nameOf(ObjectId id) {
        return catalog.stat(id).await().unwrap().flatMap(ObjectInfo::name);
    }

    @Test
    void stat_unknownObject_isNone() {
        assertThat(catalog.stat(ObjectId.objectId()).await().unwrap().isEmpty()).isTrue();
    }

    @Test
    void insert_thenStat_reportsTheUnnamedObjectWithItsManifestAndSize() {
        var id = ObjectId.objectId();
        var info = catalog.insert(id, entry(42, 7)).await().unwrap();
        var found = catalog.stat(id).await().unwrap().unwrap();

        assertThat(info.ref().id()).isEqualTo(id);
        assertThat(found.ref().manifest()).isEqualTo(entry(42, 7).manifest());
        assertThat(found.meta().size()).isEqualTo(42);
        assertThat(found.name().isEmpty()).isTrue();
        assertThat(found.ref().namespace()).isEqualTo(CatalogFixture.NAMESPACE);
    }

    @Test
    void bind_nameFree_onAFreeName_bindsIt() {
        var id = inserted(1, 1);
        var info = catalog.bind(name("a"), id, Precondition.nameFree()).await().unwrap();

        assertThat(info.name()).isEqualTo(some(name("a")));
        assertThat(holderOf("a")).isEqualTo(some(id));
        assertThat(nameOf(id)).isEqualTo(some(name("a")));
    }

    @Test
    void bind_nameFree_onABoundName_failsAndChangesNothing() {
        var first = inserted(1, 1);
        var second = inserted(1, 2);

        catalog.bind(name("a"), first, Precondition.nameFree()).await().unwrap();

        assertThat(failureOf(catalog.bind(name("a"), second, Precondition.nameFree()))).isInstanceOf(ContentStoreError.PreconditionFailed.class);
        assertThat(holderOf("a")).isEqualTo(some(first));
        assertThat(nameOf(second).isEmpty()).isTrue();
    }

    @Test
    void bind_boundTo_passesOnlyForTheCurrentHolder() {
        var first = inserted(1, 1);
        var second = inserted(1, 2);

        catalog.bind(name("a"), first, Precondition.nameFree()).await().unwrap();

        assertThat(failureOf(catalog.bind(name("a"), second, Precondition.boundTo(second)))).isInstanceOf(ContentStoreError.PreconditionFailed.class);
        assertThat(holderOf("a")).isEqualTo(some(first));

        catalog.bind(name("a"), second, Precondition.boundTo(first)).await().unwrap();

        assertThat(holderOf("a")).isEqualTo(some(second));
    }

    @Test
    void bind_unconditional_takesTheNameFromItsPreviousObject() {
        var first = inserted(1, 1);
        var second = inserted(1, 2);

        catalog.bind(name("a"), first, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("a"), second, Precondition.unconditional()).await().unwrap();

        assertThat(holderOf("a")).isEqualTo(some(second));
        assertThat(nameOf(second)).isEqualTo(some(name("a")));
        assertThat(nameOf(first).isEmpty()).as("the previous object no longer reports the name").isTrue();
    }

    @Test
    void bind_aSecondName_movesTheName() {
        var id = inserted(1, 1);

        catalog.bind(name("old"), id, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("new"), id, Precondition.nameFree()).await().unwrap();

        assertThat(holderOf("new")).isEqualTo(some(id));
        assertThat(holderOf("old").isEmpty()).as("after the call returns, the old name is released").isTrue();
        assertThat(nameOf(id)).isEqualTo(some(name("new")));
    }

    @Test
    void bind_theNameTheObjectHas_keepsIt() {
        var id = inserted(1, 1);

        catalog.bind(name("a"), id, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("a"), id, Precondition.boundTo(id)).await().unwrap();

        assertThat(holderOf("a")).isEqualTo(some(id));
        assertThat(nameOf(id)).isEqualTo(some(name("a")));
    }

    @Test
    void bind_anUnknownObject_isObjectNotFound() {
        assertThat(failureOf(catalog.bind(name("a"), ObjectId.objectId(), Precondition.unconditional()))).isInstanceOf(ContentStoreError.ObjectNotFound.class);
        assertThat(holderOf("a").isEmpty()).isTrue();
    }

    @Test
    void update_patchesTheMetadataAndKeepsTheName() {
        var id = inserted(9, 1);

        catalog.bind(name("a"), id, Precondition.nameFree()).await().unwrap();

        var patch = MetaPatch.metaPatch(none(), some("text/plain"), some(Map.of("k", "v")));
        var info = catalog.update(id, patch).await().unwrap();
        var found = catalog.stat(id).await().unwrap().unwrap();

        assertThat(info.meta().contentType()).isEqualTo("text/plain");
        assertThat(found.meta().contentType()).isEqualTo("text/plain");
        assertThat(found.meta().attributes()).isEqualTo(Map.of("k", "v"));
        assertThat(found.meta().size()).isEqualTo(9);
        assertThat(found.name()).isEqualTo(some(name("a")));
    }

    @Test
    void update_anUnknownObject_isObjectNotFound() {
        var patch = MetaPatch.metaPatch(none(), some("text/plain"), none());

        assertThat(failureOf(catalog.update(ObjectId.objectId(), patch))).isInstanceOf(ContentStoreError.ObjectNotFound.class);
    }

    @Test
    void remove_reportsTheObjectOnce_andReleasesItsName() {
        var id = inserted(5, 1);

        catalog.bind(name("a"), id, Precondition.nameFree()).await().unwrap();

        var removed = catalog.remove(id).await().unwrap();

        assertThat(removed.map(info -> info.ref().id())).isEqualTo(some(id));
        assertThat(removed.map(info -> info.ref().manifest())).isEqualTo(some(entry(5, 1).manifest()));
        assertThat(catalog.remove(id).await().unwrap().isEmpty()).as("a second removal reports nothing").isTrue();
        assertThat(catalog.stat(id).await().unwrap().isEmpty()).isTrue();
        assertThat(holderOf("a").isEmpty()).isTrue();
    }

    @Test
    void remove_concurrently_reportsTheObjectToExactlyOneCaller() {
        var id = inserted(5, 1);
        var first = catalog.remove(id);
        var second = catalog.remove(id);
        var reported = List.of(first.await().unwrap(), second.await().unwrap())
                           .stream()
                           .filter(Option::isPresent)
                           .count();

        assertThat(reported).isEqualTo(1);
    }

    @Test
    void remove_keepsANameThatMovedToAnotherObject() {
        var first = inserted(1, 1);
        var second = inserted(1, 2);

        catalog.bind(name("a"), first, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("a"), second, Precondition.unconditional()).await().unwrap();
        catalog.remove(first).await().unwrap();

        assertThat(holderOf("a")).isEqualTo(some(second));
    }

    @Test
    void remove_anUnknownObject_isNone() {
        assertThat(catalog.remove(ObjectId.objectId()).await().unwrap().isEmpty()).isTrue();
    }

    @Test
    void lookup_aFreeName_isNone() {
        assertThat(holderOf("nothing").isEmpty()).isTrue();
    }

    @Test
    void list_ordersUnnamedFirstThenByName_filtersByPrefix_andPagesByCursor() {
        var unnamed = inserted(1, 1);
        var b = inserted(1, 2);
        var a = inserted(1, 3);
        var other = inserted(1, 4);

        catalog.bind(name("doc/b"), b, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("doc/a"), a, Precondition.nameFree()).await().unwrap();
        catalog.bind(name("img/x"), other, Precondition.nameFree()).await().unwrap();

        var all = catalog.list(none(), none(), 10).await().unwrap();

        assertThat(all.items().stream().map(info -> info.ref().id()).toList()).containsExactly(unnamed, a, b, other);
        assertThat(all.next().isEmpty()).isTrue();

        var docs = catalog.list(some("doc/"), none(), 1).await().unwrap();

        assertThat(docs.items().stream().map(info -> info.ref().id()).toList()).containsExactly(a);

        var rest = catalog.list(some("doc/"), docs.next(), 1).await().unwrap();

        assertThat(rest.items().stream().map(info -> info.ref().id()).toList()).containsExactly(b);
        assertThat(rest.next().isEmpty()).as("the last page has no cursor").isTrue();
    }
}
