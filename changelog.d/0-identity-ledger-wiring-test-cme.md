### Fixed
- `IdentityLedgerWiringTest`: every read of the operator-warning list that the `identity-ledger-writer` thread fills now goes through one guarded snapshot (`raisedEvents()`); the offers-journal poll streamed the live `Collections.synchronizedList` unguarded and failed one rc5 run with a `ConcurrentModificationException`. Test-only; no product change.
