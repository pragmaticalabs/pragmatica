package org.pragmatica.storage;

import java.util.List;

import org.pragmatica.lang.Promise;


/// One multi-mutation commit to a cluster catalog: all of its mutations land in one new root or none of them do, so a
/// rename (the entry, the new name and the old name) or a create together with its first bind is one atomic step.
///
/// The mutations are intents ([CatalogMutation]), so the same commit is built on whatever root is current: [#onto] the
/// root read before the first attempt, and [#onto] the newer root that won the CAS when an attempt lost. That second call
/// is the rebase: every precondition is checked again against the newer root, and a mutation on a key the winner did not
/// touch lands exactly as it would have. The CAS loop that decides when to rebase belongs to the cluster store.
record CatalogCommit(List<CatalogMutation> mutations) {
    public CatalogCommit {
        mutations = List.copyOf(mutations);
    }

    static CatalogCommit catalogCommit(List<CatalogMutation> mutations) {
        return new CatalogCommit(mutations);
    }

    /// The commit built on `root` of `namespace`'s catalog, reading its pages from `source`, with pages of at most `fanout`
    /// cells or children; or the first mutation's failure, with nothing built.
    Promise<CatalogDraft.CatalogWrite> onto(CatalogRoot root,
                                            String namespace,
                                            PageTree.PageSource source,
                                            int fanout) {
        return CatalogDraft.catalogDraft(root, namespace, source)
                           .apply(mutations)
                           .flatMap(draft -> draft.write(fanout));
    }
}
