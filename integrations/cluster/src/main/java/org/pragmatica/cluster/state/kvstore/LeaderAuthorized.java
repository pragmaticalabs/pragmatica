package org.pragmatica.cluster.state.kvstore;

/// Values whose mutations require a committed leader witness and compare-and-set precondition.
/// Plain Put/Remove commands targeting these values are silently dropped without notifications;
/// their legacy previous-value return is not an acceptance signal. Use a caller-correlated
/// LeaderTransaction (including for one mutation) to observe acceptance or refusal.
public interface LeaderAuthorized {}
