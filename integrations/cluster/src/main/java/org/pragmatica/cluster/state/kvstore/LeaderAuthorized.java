package org.pragmatica.cluster.state.kvstore;

/// Values whose mutations require a committed leader witness and compare-and-set precondition.
/// Plain Put/Remove commands targeting these values are silently dropped without notifications;
/// their legacy previous-value return is not an acceptance signal. Use a caller-correlated
/// LeaderTransaction (including for one mutation) to observe acceptance or refusal.
/// The protection cannot be downgraded in place: a write replacing a committed LeaderAuthorized
/// value with an unmarked one is rejected, even inside an authorized transaction, so a derived or
/// defaulted replacement cannot silently reopen the key to bare Put/Remove. De-authorizing a key
/// means DELETING it through an authorized transaction, which leaves no unprotected key behind.
public interface LeaderAuthorized {}
