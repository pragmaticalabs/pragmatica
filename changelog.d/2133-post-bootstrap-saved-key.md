### Fixed (2026-11-06 — #2133: after `aether cluster bootstrap`, a plain `aether status` in a new shell got a bare 401)
- **A plain command sent no API key to the cluster a bootstrap had just made active.** The active context was
  dialled, but its key was read only from the env var the registry names (`AETHER_<NAME>_API_KEY`), which a new
  shell does not have; only `--cluster` read the key `cluster bootstrap` saves in `~/.aether/clusters/<name>/api-key`.
- One resolver now gives a registered cluster's key on every path that dials it (the active context of
  `aether status` and the other top-level commands, the `cluster` family, `--cluster`, `cluster destroy`): the saved
  file first, then the env var the entry records. The file wins because `cluster rotate-key` rewrites it, so an
  exported variable can hold a revoked key. An explicit `--connect` still gets no context key.
  [verified: aether/cli/src/test/java/org/pragmatica/aether/cli/AetherCliPostBootstrapKeyTest.java]
  [verified: aether/cli/src/test/java/org/pragmatica/aether/cli/AetherCliEndpointPrecedenceTest.java]
- `--cluster X` now also falls back to the env var X's entry records when X has no saved key file (it sent no key).
  [mechanism: `ClusterTargetMixin.apiKeyOf`]
