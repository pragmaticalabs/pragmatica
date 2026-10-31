### Added
- A model-based test of provisioning (G3, #2515): the real leader reconciler, topology manager and capacity layer run against a recording fake provider that mirrors measured provider behaviour, with leader kills, partitions, outages and lost replies injected between calls, over 200 seeded schedules. Test-only; no runtime change.
