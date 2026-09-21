# PR #1341 Heavy Forge validation

The slow-rejoin probe models durable returning members: each Ember node retains its own consensus storage and participation marker. It must not substitute fresh bootstrap identities or reset participation. The same five identities return, the hold window permits no replacement creates, and the positive control subsequently requests seven cores.

Membership readiness is insufficient before restart. Membership callbacks execute during consensus application, before the applied phase advances. The fixture therefore commits an inert history marker and observes it on every configured node. Independently, production shutdown drains accepted application work before saving its final checkpoint; it rejects new submissions after stopping starts. This protects shutdown during any application, rather than relying on a fixture delay.

The checkpoint adapter strips the exact Git persistence phase envelope before Base64 decoding. Invalid envelopes and invalid payloads remain failures. The opt-in Ember persistence seam requires pre-created per-node directories, including nodes introduced by the scaling control.

## Evidence and limits

The first local persistence/header fix passed both slow-rejoin cases (153 seconds). Subsequent GitHub run 35639240753 at c5a0bd3e0 still failed: 73 cases, two failures, two errors, one skip. This was a normal Maven test failure, not a job-timeout cancellation. The log shows a ten-second application overlapping shutdown, leaving one retained node at phase zero while two retained peers restored phase one. That failure motivated shutdown serialization and the explicit fixture history barrier.

The same run also recorded two independent failures requiring separate diagnosis:

- OwnershipFenceBaselineTest (#1406) accepted 1,999 of 2,000 writes. Initial ownership/backfill completed during its supposedly steady-epoch measurement; the test discards individual failure causes, so the precise refused-write cause is unavailable. A follow-up should establish committed ownership before measurement and retain refusal diagnostics without weakening the write count.
- MultiPartitionStreamTest (#1109) failed loading because the owning blueprint had no visible stream bindings, then rolled back. Its current-state FAILED poll missed the transient failure and waited four minutes for HTTP readiness. Blueprint publication already submits bindings in the same batch as the blueprint, but KV notifications run command by command; a deterministic visibility/interleaving reproduction is needed before choosing a production fix. Increasing readiness timeouts would not recover this failed deployment.

Both unrelated cases passed the earlier CI run. Their intermittent nature does not establish correctness, and a targeted restart pass does not make the complete Heavy Forge suite green. The final-head evidence below covers the restart change; it does not close those independent failures.

## Final validation on the build host

Implementation commit `36544f5c6` was merged with current rc4 `d444d22c6`; validation ran at `2efc9e4e6`. The merge changed only stream consumer production/tests and its changelog. The final evidence update changes documentation only.

- All six `build.sh` steps passed, including JBCT, runtime installation and fixture compilation.
- The complete consensus module passed 799 tests with zero failures, errors or skips before the stream-only merge. Replacing serialized shutdown with synchronous shutdown made the latch-controlled regression fail by assertion; restoring the exact implementation passed both focused shutdown tests.
- The merged `StreamConsumerRuntimeTest` passed 68 cases with zero failures, errors or skips.
- Three serial `./forge.sh PostRestartSlowRejoinDeficitFillProbeTest` repetitions each passed two cases with zero failures, errors or skips. Gate wall times were **153 / 153 / 153 seconds**; XML suite times were **149.647 / 149.509 / 149.481 seconds**.
- Every repetition verified all 70 runtime dependencies fresh in the isolated Maven repository. No build or install ran during live Forge. `HCLOUD_TOKEN` was removed from all build and test environments.
- Each run observed zero creates during the unchanged hold/rejoin, the same five identities returning, and exactly two creates in the positive 5→7 control.

This fulfills the requested three build-host repetitions in #1398. New-head runner execution remains separate evidence, and the unrelated #1109/#1406 failures remain unresolved.
