### Fixed (2026-10-04 — the node's webhook forwarder leaked its HttpClient on stop)
- **`AlertForwarder` owns a JDK `HttpClient` when `[alerts]` webhooks are enabled, and nothing closed it.**
  Same class as #1097: the client's selector-manager thread outlived the node. `AlertForwarder` is now
  `AsyncCloseable`, `AlertManager.closeForwarder()` releases the bound forwarder, and `AetherNode.stop()`
  calls it. Pinned by `AetherNodeAlertForwarderShutdownTest`, which counts selector threads before boot,
  while up and after stop.
