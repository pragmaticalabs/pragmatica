package org.pragmatica.cluster.state.kvstore;

import java.util.List;


/// Marker for a KEY that is written only as a member of a retirement unit (#3860): the committed value is never put or replaced, and it leaves
/// the store only in a transaction that retires its unit mates too.
///
/// Admission is by the SHAPE of the transaction against committed storage, never by a flag the builder sets: a bare `Put` of such a key is
/// dropped whatever its value, a bare `Remove` is refused by the committed value's [LeaderAuthorized] marker, and a transaction may only REMOVE
/// the key, in a transaction that also covers every key of [#unitMates]: each mate is removed in the same transaction, or witnessed absent
/// (a read witness whose expected value is none, which the applier has already compared to committed storage). Nothing the builder chooses
/// (the transaction id, a token, a type) takes part in that decision, so a builder cannot make a transaction admissible except by building the
/// retirement of the whole unit: a put, a removal of the member alone, a removal beside a mate that is present and untouched, or another
/// node's unit is refused ([KVCommand.RefusalKind#ARM], naming the key). The decision reads only committed storage and the command, so every
/// replica decides identically. Snapshot install and replica effects replay already-admitted state and bypass the fence, as they do every other one.
///
/// A future writer of such a key (an issuer) is a second owner and needs its own admission here; there is none today. A future receipt WRITER must
/// add its own admitted shape in KVStore's arm, and the pin `aBarePutOfAnotherValueAtTheReceiptKey_isDropped` will go RED until it does.
public interface RetiredWithUnit {
    /// The other keys of this key's retirement unit, each of which must be removed or witnessed absent in a transaction that removes this key.
    List<? extends StructuredKey> unitMates();
}
