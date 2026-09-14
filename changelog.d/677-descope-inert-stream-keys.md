### Changed (2026-09-14 — #677: the #576-rejected stream keys are descoped from 1.0, not wired)
- **Decision: descope, keep the refusal.** `[streams.X] compression`, a non-default `encryption-key-id`,
  `auto-offset-reset` other than `earliest`, and the seven `[streams.X.consumers.Y]` tuning keys are read by
  nothing at runtime and have been REJECTED by blueprint validation since #576 (`StreamResourceValidator.guardInertConfig`)
  — though that verdict does not block the publish; see the reachability bullet below.
  Wiring them is feature work across `slice-api`, `aether-stream` and the `AetherNode`/`StreamConsumerManager`
  construction sites (compression sink, per-consumer plumbing) plus #253's production key source for encryption
  — not a wire-or-delete chore — so 1.0 ships the honest floor: the keys stay in the schema as loud refusals
  (removing the record fields would turn refusal into silent ignore, since `StreamConfigParser` has no
  unknown-key gate), the refusal messages no longer promise a wiring "until #576's runtime wiring lands"
  but say not-supported-in-1.0 with the descope recorded here, and the docs that called the gap "the #677
  decision" now state the outcome (`streaming-spec.md` header and §3.2/§3.3, `in-memory-streams-spec.md`,
  `known-limitations.md`). `auto-offset-reset = earliest` stays permanent by the #478 ruling, unchanged.
  [verified: `StreamResourceValidatorTest$DescopedRefusalWording` — the refusal wording is now pinned by
  CONTRACT rather than by exact string: every one of the ten refusals names its offending key, the two
  DESCOPED refusals state "not supported in 1.0", and neither carries a pending-wiring promise; the #478
  `auto-offset-reset` refusal is asserted NOT to claim a descope, so a blanket sentence cannot satisfy the
  pair. Reverting the message hunk reddens 3 of those 5 while all 7 rule-id tests stay green — which is
  why the rule-id tests alone did not pin this]
- **Premise correction, stated:** the ticket's "second independent TOML parser for `[streams.X]` at
  `NodeDeploymentState`" no longer exists at the tip — since #488/#1040 the node resolves a consumer's stream
  through the reflective `ConfigService.config(section, StreamConfig.class)` binding and reads only `name`;
  `StreamConfigParser` is the one parser. No consolidation was needed.
- Post-GA wiring epic (compression sink, per-consumer tuning through `StreamConsumerManager`, encryption
  once #253 lands) is a ticket draft in the report; the CTO files it.
- **Feature-catalog rows 141, 142 and 143 now state the descope.** Row 141 (consumer read-preference) and row 142
  (segment compression) both read as *accepted-but-partial* while their `[streams.X]` TOML keys are refused;
  each now says so and names the refusing symbol. Two stale citations in row 141 were corrected in passing
  because the row is in this diff: `selectReplicaAndRead()` has **zero declarations** in `src/main` (15 test
  method names and 2 comments keep the old name; `readWithPreference` is the live symbol), and the enum list
  omitted `LINEARIZABLE`. The bare line number `PartitionedStreamAccess.java:278` pointed at unrelated javadoc
  and was replaced by the symbol. Row 207 already carried the #576 statement for `encryption-key-id`.
  **Row 143 (segment encryption) needed BOTH halves fixed, not one:** besides missing the descope clause, its
  own neighbouring claim — "`.encrypt(` has zero callers in `src/main`" — is FALSE; there are **4**
  (`StorageSegmentSink.applyEncryption`, `EncryptingStorageTier`, and two SWIM transports; positive control
  `.decrypt(` = 4, same instrument). Adding a true clause beside a false count would have blessed the false
  half, so the count is corrected too, and the row now marks explicitly what is **not settled here** —
  whether #253's `EncryptingStorageTier` (row 207) closes the row's AHSE-engine gap, a pass this PR did not
  take and which is filed separately. The narrower claim that `writeToAllTiers` does not *itself* invoke the
  encryptor survives and is kept.
- **Found while checking the inverse (documented-as-refused but still reachable): THE REFUSAL DOES NOT FAIL A
  DEPLOY.** `StreamResourceValidator.validate` has exactly one production caller — `BlueprintService.streamBindings`
  — which ends `.or(List.<NamedAddress>of())`, discarding the `Cause` and publishing an EMPTY bindings entry.
  A blueprint carrying a descoped key therefore publishes successfully and the refusal text never reaches the
  operator; a slice consuming that alias fails later with the generic `StreamAddressError.UnboundStreamAlias`,
  and if nothing consumes it the key is silently ignored. This is pre-existing and by design as documented at
  `BlueprintService` ("rc1's deploy chain has no stream-resource validation gate … the gate that would HTTP-422
  on bad stream config is a separate stage") — it is NOT introduced here. **Filed as #1181**, and pinned here
  by an ENABLED TRIPWIRE, `BlueprintPublishOwnershipTest$StreamBindings`
  `.publish_withDescopedStreamKey_stillSucceedsWithEmptyBindings_KNOWN_DEFECT`, which asserts the current
  wrong behaviour on purpose so it reddens the moment #1181's gate lands and tells the reader to delete it.
  **Spec wording corrected with it:** `known-limitations.md`, `streaming-spec.md` and `in-memory-streams-spec.md`
  said these keys are "REJECTED AT DEPLOY TIME", which overstates it — the validator returns that verdict but
  nothing acts on it. **8 passages across those 3 files** were corrected, not the 4 an initial phrase-grep
  found: `does not deploy today` (×2, in the TOML examples), `deploy-time rejection` (§3.3) and the
  `## Last Updated` header carried the same claim in other words. Re-enumerated over every line citing
  #576/#677 in the three files (19 lines, all read); residual sweep now 0
  `[verified: one production call site of `validate`; `Result.or(T)` returns the replacement and drops the
  Cause; tripwire green with in-run controls both ways]`
- [unverified: no deploy run — the rejection behaviour is unchanged and pinned by the existing validator
  tests; only message text and docs moved]
