package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Option;


/// Marker for a KEY whose writes may be made on behalf of a holder committed under ANOTHER key (#1155): the
/// [HolderBound] writes to it are admitted only while that holder is still the one whose row is committed under
/// [#holderKey].
///
/// A `Put` to a `HolderGuarded` key whose incoming value is a [HolderBound] bound to a holder is rejected unless the
/// committed value under [#holderKey] is a [HolderFenced] carrying the SAME token. That is what refuses the late
/// write of a superseded holder: once a new acquisition is committed (or the row is gone) the old token no longer
/// matches, whatever the writer's own view says. A value bound to no holder passes untouched, and so does a key that
/// names no holder key (`Option.none()`), so writers that act for nobody are not gated.
///
/// Unlike [AssignmentGuarded], which gates EVERY write to its key, this gates only the writes that claim a holder:
/// the same key is also written by observers (counters of skipped ticks, operator clean-up) that hold nothing.
///
/// Deterministic like every other arm: reads only committed storage (the holder key's value) and the incoming value.
/// A rejected write mutates nothing and emits NO notification (see [VersionFenced] for the caller-side caveat).
public interface HolderGuarded {
    /// The key whose committed [HolderFenced] value is the authority for holder-bound writes to this key; none when
    /// this key is not written on behalf of a holder.
    Option<Object> holderKey();
}
