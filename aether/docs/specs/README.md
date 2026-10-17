# Specs

Design and implementation specifications for Aether subsystems.

- **This directory** — active specs describing shipped or in-progress work, plus the planned
  implementation specs listed below.
- **Planned** — design-only specs COMMITTED to a named 1.0.0-line release but not yet started; each
  names its release target, tracking issue and prerequisites. Listed below.
- **[archive/](archive/)** — specs superseded by a newer design; kept for historical context and cited load-bearing sections.
- **[future/](future/)** — designed-only specs with no shipped implementation and no 1.0.0-line release target.

Per-spec `Status:` headers are being standardized across this directory; until that lands, check each document's own status block for its current state.

## Planned implementation specifications

- [Deterministic Cluster Supervision and Predictive Capacity Planning](cluster-supervision-spec.md)
  — unified executable runbooks, bounded recovery, continuously active reactive scaling, and
  calendar-aware regional/market capacity planning. **Design only; not implemented. Target
  1.0.0-rc5 (#1251); starts once its listed prerequisites close.** Includes integration contracts,
  delivery phases, and acceptance tests.
- [DNS load balancing over a floating-IP pool](floating-ip-load-balancing-spec.md)
  — `load_balancer = "floating_ip"`: a leader-fenced reconcile loop moves provider IPs between
  regular nodes behind static DNS; replaces `elected`. **Design only; not implemented. Target
  1.0.0-rc5 (#1867).** Operator procedures: [runbook](../operators/runbooks/floating-ip-load-balancing.md).
- [Aether Artifact Repository](artifact-repository-spec.md)
  — a full Maven repository replacing the built-in artifact store: an optional part of every
  cluster, with the internal slice namespace (write-once, no SNAPSHOT) beside hosted, proxy and
  group repositories, sealed operation, retention and backup/restore. **Proposed; design only;
  not implemented. AR1–AR3 planned for 1.0.0-rc5 (epic #1831), AR4–AR6 for the following rc
  (epic #1836).** Evidence and claim verification: [component readiness](artifact-repository-readiness.md).
