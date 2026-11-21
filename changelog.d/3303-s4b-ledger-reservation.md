### Added (2026-11-21 — #3303: wire tags 3070..3109 reserved for stream split state)
- Wire tags 3070..3109 are reserved in the system tag ledger for the stream split state (partition state, epoch floor, life outcome); nothing is pinned in the block yet. `[verified: integrations/serialization/api/src/test/java/org/pragmatica/serialization/SystemTagsLedgerTest.java s4b_reserves3070to3109]`
