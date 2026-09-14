### Changed (2026-09-14 — #677: the #576-rejected stream keys are descoped from 1.0, not wired)
- **Decision: descope, keep the refusal.** `[streams.X] compression`, a non-default `encryption-key-id`,
  `auto-offset-reset` other than `earliest`, and the seven `[streams.X.consumers.Y]` tuning keys are read by
  nothing at runtime and have been REJECTED at deploy time since #576 (`StreamResourceValidator.guardInertConfig`).
  Wiring them is feature work across `slice-api`, `aether-stream` and the `AetherNode`/`StreamConsumerManager`
  construction sites (compression sink, per-consumer plumbing) plus #253's production key source for encryption
  — not a wire-or-delete chore — so 1.0 ships the honest floor: the keys stay in the schema as loud refusals
  (removing the record fields would turn refusal into silent ignore, since `StreamConfigParser` has no
  unknown-key gate), the refusal messages no longer promise a wiring "until #576's runtime wiring lands"
  but say not-supported-in-1.0 with the descope recorded here, and the docs that called the gap "the #677
  decision" now state the outcome (`streaming-spec.md` header and §3.2/§3.3, `in-memory-streams-spec.md`,
  `known-limitations.md`). `auto-offset-reset = earliest` stays permanent by the #478 ruling, unchanged.
  [mechanism: `StreamResourceValidatorTest` continues to pin the rejections by rule id
  (`inert-stream-config-key`, `inert-consumer-config-key`); the message tail is not asserted by any test]
- **Premise correction, stated:** the ticket's "second independent TOML parser for `[streams.X]` at
  `NodeDeploymentState`" no longer exists at the tip — since #488/#1040 the node resolves a consumer's stream
  through the reflective `ConfigService.config(section, StreamConfig.class)` binding and reads only `name`;
  `StreamConfigParser` is the one parser. No consolidation was needed.
- Post-GA wiring epic (compression sink, per-consumer tuning through `StreamConsumerManager`, encryption
  once #253 lands) is a ticket draft in the report; the CTO files it.
- [unverified: no deploy run — the rejection behaviour is unchanged and pinned by the existing validator
  tests; only message text and docs moved]
