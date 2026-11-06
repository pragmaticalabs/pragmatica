package org.pragmatica.storage;

/// Which tiers a named write lands on (streams #2258, spec 3.2 item 5).
public enum TierScope {
    /// Every required tier, the cluster-shared last tier included: today's behaviour.
    ALL,
    /// Node-private required tiers only. For a fact of one node that nothing reads from a shared tier -- a stream's
    /// reclaimed-through floor (its value is parsed from the ref name) or a consumer cursor (its ref lives only in this
    /// node's metadata). When the instance has no private DURABLE tier (the disk-unavailable stack of memory plus DHT) the
    /// write falls back to [#ALL] and is counted, so it keeps working at today's cost.
    PRIVATE
}
