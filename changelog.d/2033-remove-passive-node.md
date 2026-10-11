### Removed (2026-10-08 — #2033: `PassiveNode`, a library surface with no caller, instead of fixing its one-shot snapshot request)
- **`org.pragmatica.cluster.node.passive.PassiveNode` is deleted.** #2033 reported that its KV snapshot
  request was one-shot (sent on the first `ConnectionEstablished`, never retried after a lost request,
  a lost response or a failed restore). A repo-wide search found no caller: nothing in the repository
  instantiated it, `aether-node` does not run on it, and the only live references were changelog and
  design-note prose. Removing dead code closes the defect without maintaining a retry and an operator
  event for a path nobody reaches. Pre-GA, so no deprecation window.
- **Left in place deliberately:** `NetworkMessage.KVSyncRequest` / `KVSyncResponse`, `RabiaNode`'s
  responder, `SyncHoldRegistry` / `SyncHoldConfig`, their wire tags (40, 41), the inbound-policy entries
  and the wire baseline. With `PassiveNode` gone nothing in the repository sends a `KVSyncRequest`; the
  receiving side is now an orphaned protocol, tracked separately because removing a pinned wire tag
  is a deliberate baseline change of its own.
