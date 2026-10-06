### Added (2026-10-06 — #1543 part D: a replacement is paired with the node it replaces)
- **`NodeReplacement` KV record** (`node-replacement/<original>` → replacement id, role, phase, phase deadline),
  leader-authorized, runtime state (not backed up), with pinned wire tags 2129–2131.
- **The surplus reapers honour a live pairing.** A fresh +1 replacement core is no longer an excluded core
  (`retireExcludedCores` would otherwise drain it as `OVERPROVISION_SCALE_DOWN` within a second), and the CTM
  refuses every surplus-trim drain of a paired node — including the `LeaderReconciler` surplus drain, which picks
  a fresh ephemeral core first. A join-grace reap of a never-joined replacement is not gated (it has no other
  reaper). The original is protected too until its pairing reaches `RETIRING_OLD`.
- **Voter selection swaps a paired original for its ready replacement in one reconfiguration**, keeping the voter
  count (works at 3 cores), only when the roster is otherwise healthy, one seat per change, never the leader's own.
- **Worker surplus selection** treats a paired replacement as surge rather than surplus and takes the paired
  original first once it is due for retirement.
- Nothing commits a pairing yet (the phase-driving reconciler is part E), so every guard is inert and behaviour is
  unchanged until then. [design intent — unverified end to end: no multi-node run]
