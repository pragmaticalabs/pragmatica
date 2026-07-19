package org.pragmatica.jbct.lint.layer;


/// A JBCT architecture layer, ordered by dependency rank.
///
/// The book's layering rule is "imports point up only": `domain <- application <- adapter <-
/// bootstrap`. Each layer may depend on itself and every lower-ranked layer, never a higher one.
/// [#rank()] encodes that order — domain lowest (0), bootstrap highest (3) — and JBCT-ARCH-01 flags
/// an import whose target layer outranks the importer. Each layer also carries its [Zone]: only
/// `ADAPTER` is the adapter boundary; every other layer is business code.
public enum Layer {
    DOMAIN(0, Zone.BUSINESS),
    APPLICATION(1, Zone.BUSINESS),
    ADAPTER(2, Zone.ADAPTER_BOUNDARY),
    BOOTSTRAP(3, Zone.BUSINESS);

    private final int rank;
    private final Zone zone;

    Layer(int rank, Zone zone) {
        this.rank = rank;
        this.zone = zone;
    }

    /// Dependency rank — domain lowest, bootstrap highest. An import is legal only when the target's
    /// rank is less than or equal to the importer's.
    public int rank() {
        return rank;
    }

    /// The [Zone] this layer belongs to.
    public Zone zone() {
        return zone;
    }
}
