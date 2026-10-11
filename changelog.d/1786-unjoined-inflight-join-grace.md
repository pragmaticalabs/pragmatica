### Fixed (2026-09-30 — #1783 part 2: an unjoined in-flight replacement masked a core deficit for the full 600s ceiling)
- **The leader counted a provider-CONFIRMED replacement that never joined membership toward effective
  capacity until the ten-minute ceiling**, so `NO_DEFICIT` held on 37 of 39 reconcile passes while a core
  was missing, and a 720s recovery budget could not be met.
  [mechanism: `LeaderReconciler.effectiveCapacity` unioned every `inFlightProvisioning` key]
- A CONFIRMED entry still unjoined once the join grace (6 x `split_timeout`, 90s at the default) has passed
  since its mint no longer counts: the deficit re-opens and re-dispatches after the normal debounce. The entry
  is kept (polled, bounded by the ceiling); if it joins late the existing surplus path drains exactly one node
  once the substitute joins. The grace runs from dispatch, or the ULID mint time for an inherited entry, so a
  leader handover does not restart it. [unverified: the x6 factor is a judgement against 50-63s cloud boots
  and is not measured on a cloud run]
  [verified: `LeaderReconcilerTest$InFlightInstanceState` — three new tests, each reddened by its mutation]
