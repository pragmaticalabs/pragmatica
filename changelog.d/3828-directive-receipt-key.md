### Added (2026-11-27 - #3828: the directive receipt's key type, inert)
- **New runtime key `DirectiveReceiptKey(NodeId nodeId)`**, wire tag 3120 (`directive-receipt/<node>`): the key the directive receipt will live under. Nothing writes it yet. It is cluster-FSM-only: the worker replica excludes it and the worker projection gives it no scope.
- **No flag day**: the baseline gains two lines (the key's `SHAPE` and `TAG`) and removes none, so the wire generation is unchanged.

### Added (2026-11-27 - #3828: the directive receipt's value, inert)
- **New value `DirectiveReceiptValue(String role)`**, wire tag 3121, `LeaderAuthorized`: the value stored under `DirectiveReceiptKey`; `role` is the role of the directive it was issued with. Nothing writes or reads it yet.
- **No flag day**: the baseline gains two lines (the value's `SHAPE` and `TAG`) and removes none, so the wire generation is unchanged.
