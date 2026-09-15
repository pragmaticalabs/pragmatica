### Fixed (2026-09-14 — #1187: the S19 quorum-loss arbitration polled a cached IP and never checked which node answered)
- **A recycled cloud IP made the arbitration interrogate a healthy stranger for its entire 540 s budget
  and report "quorum held".** `_s19_resolve_survivor_ip` returns a pre-kill **cached** IP unconditionally
  — no liveness check, no identity check — and the membership read never verified who replied. Hetzner
  recycles public IPs aggressively: in the 2026-09-14 cloud round **two IPs hosted six distinct VMs each**,
  and a survivor's cached IP hosted three. A replacement landing on that IP answers with
  `belowThreshold=false` indefinitely.
  [mechanism: the read **succeeds**, so the empty-read degrade path never fires and the survivor-2
  fallback — gated on an *empty* `membership` — is suppressed; a failed read is suspicious, a successful
  read from the wrong subject is not]
- **The identity was already in the response and simply not read.**
  `ClusterMembershipResponse(String nodeId, …)` is documented as *"`nodeId` is the answering survivor"*,
  is `LOCAL` and never leader-forwarded. The probe parsed `belowThreshold` out of that same body while
  discarding the field that says **whose** `belowThreshold` it is. No new endpoint, field or product
  change was needed.
- **The poll now asserts identity and VOIDs the round on a mismatch — loud and terminal, never "lost".**
  `membership_identity_matches` (`lib/topology.sh`) keeps rc 1 (**mismatch**: we provably polled a
  stranger) distinct from rc 2 (**indeterminate**: we cannot tell); both void a measurement, but only the
  first is evidence the recycled-IP failure occurred.
  [verified: `aether/tests/integration/test/test-s19-identity-guard.sh` — 9 assertions, both directions:
  the guard accepts the right node **and** fires for a wrong one, since a validator only ever observed
  accepting is indistinguishable from one that cannot reject]
- **Consequence for prior results:** any S19 race outcome produced before this is **uninterpretable
  rather than merely unachieved**, because the instrument could not name the node it measured.
- **`[unverified: the members-array truncation]`** — the live round that validated this guard did **not**
  exercise it. The live body serialises `nodeId` before `members`, so a naive first-match extractor would
  have returned the identical value. Presence and equality are live-validated; the truncation is covered
  by the offline mutation pin `I6` alone.
