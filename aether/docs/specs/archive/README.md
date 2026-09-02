# Archived Specs

Specs superseded by newer designs. Kept for historical context and because some sections are still cited as load-bearing elsewhere. Do not implement against these documents — follow the replacement listed.

| Document | Superseded by | Note |
|----------|---------------|------|
| [swim-driven-topology-spec.md](swim-driven-topology-spec.md) | [cluster-topology-overhaul-spec.md](../cluster-topology-overhaul-spec.md) (2026-06-10) | KEEP §5 (ANNOUNCE protocol — load-bearing wire format) |
| [membership-architecture-v2-spec.md](membership-architecture-v2-spec.md) | [cluster-topology-overhaul-spec.md](../cluster-topology-overhaul-spec.md) | KEEP §8 (drain procedure), §12.7 (terminal-removal invariant), §4 (two-counts naming) |
| [membership-unification-spec.md](membership-unification-spec.md) | [cluster-topology-overhaul-spec.md](../cluster-topology-overhaul-spec.md) (transitively, via membership-architecture-v2) | Marked superseded 2026-05-30 |
| [integration-test-overhaul-spec.md](integration-test-overhaul-spec.md) | [integration-test-overhaul-v2-spec.md](../integration-test-overhaul-v2-spec.md) | v1 of the same effort; v2 is the design that was carried forward |

For current membership/topology design, start at [cluster-topology-overhaul-spec.md](../cluster-topology-overhaul-spec.md).
