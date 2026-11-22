package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Option;


/// A value that is the cluster's restore-authority decision (#3303). The store cannot read the decision itself, so the
/// value answers the two questions the store's fences ask of it.
///
/// Such a value lives ONLY under a [RestoreAuthorityKey] key: a write of a `RestoreAuthority` value to any other key is
/// rejected, and a write of anything else to a `RestoreAuthorityKey` key is rejected too, so the one cell holds exactly
/// one kind of value. A write to that cell is admitted only when [#admitsAfter] accepts the committed value it replaces.
///
/// Deterministic like every other arm: both methods must be pure functions of this value and their argument, so every
/// replica decides identically inside the consensus applier.
public interface RestoreAuthority {
    /// Whether this committed decision admits restore-unit writes.
    boolean admitsUnits();
    /// Whether this value may replace the committed one; `committed` is none when the cell is empty.
    boolean admitsAfter(Option<RestoreAuthority> committed);
}
