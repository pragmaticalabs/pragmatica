package org.pragmatica.cluster.state.kvstore;

/// Marker for the KEY of the one restore-authority cell (#3303), typing the key as [HolderGuarded] does.
///
/// Every write to a `RestoreAuthorityKey` key is fenced by the key, not by the value it carries: a bare `Put` is
/// dropped, a bare `Remove` is refused, an authorized transaction may not delete it, and a transaction `Put` is admitted
/// only for a [RestoreAuthority] value that accepts the committed one (see [RestoreAuthority#admitsAfter]). A committed
/// value that is not a `RestoreAuthority` admits nothing (fail closed). Snapshot install bypasses the fence, as it does
/// every other one.
public interface RestoreAuthorityKey {}
