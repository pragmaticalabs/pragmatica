### Fixed (2026-11-27 — #3815: a target with an empty placement audience is no longer silent)
- **A committed target that no node can host now raises an operator warning.** With fewer workers than `[community_policy] viability_floor`
  (one worker, the default floor 3) a `WORKERS_ONLY` target stayed unplaced with no event. The cluster FSM now raises
  `placement-audience-empty` once per episode (subject: the target; the message names the policy, each community with its state and live
  members, the floor, and `allow_below_rf`) and closes it with `placement-audience-available` when the audience is non-empty, the placement
  no longer parses, or the target is removed. The viability floor is unchanged.
