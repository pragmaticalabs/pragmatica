### Changed (2026-11-02 — #2608: the KVStore leader fence is the generic epoch fence)
- `LeaderValue` is `EpochBearing<Long>` (epoch = `viewSequence`, minting, refusals not counted). `KVStore`'s
  leader-specific `Put`/`Remove` arms are gone; every leader accept/reject decision under the `LeaderKey` is
  unchanged (pinned by `KVStoreLeaderFenceMatrixTest`, identical at base and head).
- The fence now follows the value, not the key: a `LeaderValue` committed under any other key is fenced too (a lower
  `Put` and a witnessless `Remove` are refused; both applied before). No production code writes one there
  (`KVStoreLeaderValueOffKeyTest`).
- Epochs of different classes are never compared: a mismatched kind never fences a `Put` and never authorizes a
  `Remove`. Two different non-leader epoch types under one key used to throw `ClassCastException` out of the applier;
  they now apply the `Put` and refuse the `Remove` (`KVStoreEpochKindMismatchTest`).
