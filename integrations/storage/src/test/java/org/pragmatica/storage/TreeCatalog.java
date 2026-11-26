package org.pragmatica.storage;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


/// TEST DOUBLE, not the cluster catalog: the pure core ([CatalogCommit], [CatalogDraft], [PageTree]) behind the
/// [Catalog] seam, with pages in a map and the root pointer in an [AtomicReference] instead of consensus. It shows that
/// the core implements the contract the local catalog is held to. Each operation is one commit; a commit that loses the
/// root CAS is rebuilt onto the root that won (the rebase), at most [#ATTEMPTS] times.
final class TreeCatalog implements Catalog {
    private static final int ATTEMPTS = 8;

    private final CatalogFixture.Pages pages = new CatalogFixture.Pages();
    private final AtomicReference<CatalogRoot> root = new AtomicReference<>(CatalogRoot.empty());
    private final int fanout;

    TreeCatalog(int fanout) {
        this.fanout = fanout;
    }

    CatalogRoot root() {
        return root.get();
    }

    @Override
    public Promise<ObjectInfo> insert(ObjectId id, ObjectEntry entry) {
        return single(CatalogMutation.create(id, entry));
    }

    @Override
    public Promise<Option<ObjectInfo>> stat(ObjectId id) {
        return draft().entry(id)
                      .map(entry -> entry.map(found -> found.info(CatalogFixture.NAMESPACE, id)));
    }

    @Override
    public Promise<Option<ObjectInfo>> lookup(ObjectName name, Option<Long> minVersion) {
        var draft = draft();

        return draft.holder(name)
                    .flatMap(holder -> holder.fold(() -> Promise.success(Option.<ObjectInfo> none()),
                                                   id -> draft.entry(id)
                                                              .map(entry -> entry.map(found -> found.info(CatalogFixture.NAMESPACE,
                                                                                                          id)))));
    }

    @Override
    public Promise<ObjectInfo> bind(ObjectName name, ObjectId id, Precondition precondition) {
        return single(CatalogMutation.bind(name, id, precondition));
    }

    @Override
    public Promise<ObjectInfo> update(ObjectId id, MetaPatch patch) {
        return single(CatalogMutation.patch(id, patch));
    }

    @Override
    public Promise<Option<ObjectInfo>> remove(ObjectId id) {
        return commit(List.of(CatalogMutation.remove(id)), 0).map(List::getFirst);
    }

    @Override
    public Promise<Page<ObjectInfo>> list(Option<String> namePrefix, Option<Cursor> after, int limit) {
        return PageTree.cells(root.get()
                                  .idTree(),
                              pages.source())
                       .map(cells -> cells.stream()
                                          .map(cell -> ObjectEntry.fromBytes(cell.value())
                                                                  .map(entry -> entry.info(CatalogFixture.NAMESPACE,
                                                                                           ObjectId.objectId(cell.key())
                                                                                                   .unwrap())))
                                          .map(info -> info.unwrap())
                                          .toList())
                       .map(infos -> LocalCatalog.pageOf(infos, namePrefix, after, limit));
    }

    private CatalogDraft draft() {
        return CatalogDraft.catalogDraft(root.get(), CatalogFixture.NAMESPACE, pages.source());
    }

    private Promise<ObjectInfo> single(CatalogMutation mutation) {
        return commit(List.of(mutation), 0).flatMap(outcomes -> outcomes.getFirst()
                                                                        .async(Causes.cause("no outcome")));
    }

    private Promise<List<Option<ObjectInfo>>> commit(List<CatalogMutation> mutations, int attempt) {
        var seen = root.get();

        return attempt == ATTEMPTS
               ? Causes.cause("catalog contended").promise()
               : CatalogCommit.catalogCommit(mutations)
                              .onto(seen, CatalogFixture.NAMESPACE, pages.source(), fanout)
                              .flatMap(write -> published(seen, write, mutations, attempt));
    }

    /// Pages first, then the root moves only if it is still the one the commit was built on; otherwise rebase.
    private Promise<List<Option<ObjectInfo>>> published(CatalogRoot seen,
                                                        CatalogDraft.CatalogWrite write,
                                                        List<CatalogMutation> mutations,
                                                        int attempt) {
        pages.store(write.blocks());

        return root.compareAndSet(seen, write.root())
               ? Promise.success(write.outcomes())
               : commit(mutations, attempt + 1);
    }
}
