### Fixed (2026-09-25 — #1500, #1501, #1502: three integration-harness reds from the s27 cluster-B cloud run)
- **#1500: the harness's partition-chaos firewalls blocked every CTM replacement while a partition was up.** `cloud_partition_node` labelled them `aether-cluster=<cluster>`. The Hetzner provider counts any firewall with that label as an ingress firewall its per-source selector missed, and refuses to provision. So a minority that CTM evicted during 12-network S06 could not be replaced until the heal deleted the firewalls.
  - The firewalls now carry `aether-chaos-cluster=<cluster>` and never `aether-cluster`.
  - A reused firewall from an earlier run is relabelled, and its legacy `aether-cluster` label is removed.
  - `remove-label` cannot tell "already absent" from a real API failure, so the firewall is read back afterwards. A surviving `aether-cluster` label, or a failed read-back, is logged as a WARN.
  [verified: `aether/tests/integration/test/test-restore-gate.sh` F1–F4. Putting `aether-cluster` back reddens F1 and F2. Dropping the legacy-label removal reddens F2. Dropping the read-back check reddens F3. F4 is the control: a clean read-back does not warn]
- `tools/cloud-reaper.sh` now selects `aether-chaos-cluster` in every mode, so leaked chaos firewalls are still found:
  - `--cluster X` and `--cluster X --strict-cluster` use the exact `aether-chaos-cluster=X`;
  - the catch-all mode uses the bare key.
  A chaos firewall is scoped and protected like the cluster it names, including by `--exclude-cluster`, so `test-pg`'s is never deleted. The dry-run listing includes chaos firewalls. [verified: same file, K1–K5 against a stubbed Hetzner API. Removing the selectors reddens all five. Removing the owner-column fallback reddens K5, where the protected firewall gets deleted]
- On cloud, S06 measures CTM **replacement**, not rejoin, when the minority is evicted, because CTM deletes the evicted VMs. The comment on its docker-calibrated 30s budget and its failure text now say so. The budget is unchanged, because there is no clean cloud measurement of replacement time yet. [unverified: whether 30s × `TIMEOUT_SCALE` is enough for replacement on cloud once #1500 is fixed; it needs a cloud run] The provider-side guard, which counts any `aether-cluster`-labelled firewall, is tracked separately as #1503.
- **#1501: 02w reported a transient `FoldInProgress` refusal as "unreachable (no node answered)".** `read_amount` took the first live 2xx answer as final. It now retries a `failureType` on an explicit allow-list within `TRANSIENT_READ_DEADLINE_S` (default 60s).
  - The allow-list is `ENTITY_TRANSIENT_FAILURE_TYPES`, matched by exact name against the body's first `failureType`. It starts with `FoldInProgress`.
  - A refusal still transient at the deadline is a distinct rc 5, reported with its true last body. Both readbacks count it as UNREACHABLE, never as loss or as a bad readback.
  - A transient refusal followed by no answer at all names the earlier refusal.
  - A body that is neither found, absent nor transient is reported as what it is.
  [verified: `aether/tests/integration/test/test-cloud-helpers.sh` W1–W13, replaying the body captured in the run log. Making the refusal final, or emptying the allow-list, reddens W1, W2, W4, W6–W8, W10, W12 and W13. Retrying every type reddens W3 and W11. Counting rc 5 as loss reddens W9 post-kill and W10 pre-kill. Substring matching reddens W11. Forgetting the earlier refusal reddens W12. Taking the last `failureType` reddens W13. W4 and W8 keep ABSENT-after-refusal a loss]
- **#1502: 02y scored ACKED-event survival from one best-effort read**, and reported "40 missing" when the next read found 81 events. The app read route is `NEAREST` and returns only `events`, with no watermark. Survival and pre-kill readability are now scored on the **final round** of a settled read. It re-reads every partition until one of three things holds:
  - **complete**: every acked marker is present;
  - **stable, with every owner head**: two consecutive rounds agree on each partition's count and last offset, and no partition is behind its owner's `ownerHeadOffset`. That value comes from the replicas view and is trusted only with `servedByOwner=true`;
  - **stable, headless**: the owner head is missing for any partition. The same result must then hold for `SETTLED_HEADLESS_WINDOW_S` (default 30s), not merely across two reads. Two agreeing reads were enough before, and in a leaderless window with the head unavailable that reproduced the s27 false loss.

  A settled read that still lacks an acked marker fails as loss. Reporting UNMEASURABLE whenever no head was obtained was rejected, because a real loss would then never be named. The stream coordinate is resolved once per settle, not per partition per round. Events are deduplicated on (partition, offset), so a repeated offset in a response is not reported as a duplicate append. Real duplicates are reported as a `DUPLICATES` WARN with their count.

  A partition that no endpoint could read fails as UNMEASURABLE. Markers absent from the readable partitions are named "NOT FOUND", not "MISSING", because a keyless publish may live in the unreadable partition.

  [verified: same file, Y1–Y12, with virtual time so the window and the deadline run at their defaults:
  - reverting to a single read reddens Y1 (the s27 shape), Y2, Y4, Y5 and Y7–Y12;
  - Y10: settling headless on two agreeing reads reddens Y10 (the review's leaderless reproduction), Y2 and Y9;
  - Y7: carrying payloads across rounds reddens Y7, where a marker vanishes;
  - Y1, Y6 and Y12: re-reading after settling reddens Y1 and Y12 (post-kill) and Y6 (pre-kill);
  - Y4 and Y8: tolerating a partition that is one short reddens Y8, and removing the head check reddens Y4 and Y8;
  - Y9: trusting a non-owner head reddens it;
  - Y11: resolving the coordinate per call reddens it;
  - Y12 and Y3: removing the offset dedupe reddens Y12, and removing the duplicate WARN reddens Y3;
  - Y2: counting a missing marker as present reddens Y2, Y5 and Y7;
  - Y5: calling NOT FOUND "MISSING", or dropping the UNMEASURABLE fail, reddens it]

  [unverified: whether a 30s headless window outlasts every cloud leaderless window, and whether 90s covers the slow path. In s27 the window was about 2s, and 02w saw re-elections about every 21s the same day]
