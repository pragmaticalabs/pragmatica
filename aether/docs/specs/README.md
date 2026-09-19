# Specs

Design and implementation specifications for Aether subsystems.

- **This directory** — active specs describing shipped or in-progress work.
- **[archive/](archive/)** — specs superseded by a newer design; kept for historical context and cited load-bearing sections.
- **[future/](future/)** — designed-only specs with no shipped implementation, out of scope for RC1.

Per-spec `Status:` headers are being standardized across this directory; until that lands, check each document's own status block for its current state.

## Planned implementation specifications

- [Deterministic Cluster Supervision and Predictive Capacity Planning](cluster-supervision-spec.md)
  — unified executable runbooks, bounded recovery, continuously active reactive scaling, and
  calendar-aware regional/market capacity planning. **Design only; not implemented; release target
  unassigned.** Includes integration contracts, delivery phases, and acceptance tests.
