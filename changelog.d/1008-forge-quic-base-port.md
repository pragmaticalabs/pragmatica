### Fixed (2026-09-11 — #1008: Forge's QUIC base port is configurable, and a collision on it is now loud)
- **`[cluster] base_port` is now a `forge.toml` setting** (default `6000`, unchanged), so all four of
  Forge's ports move together instead of three moving and the fourth staying pinned. `EmberConfig`
  gains a `basePort` component and validates it as a range — a base port that is individually valid
  can still run a cluster of `nodes` off the end of the port space, so `base_port + nodes - 1 > 65535`
  is refused. `ForgeServer` and `EmberInstance` now read it instead of passing
  `EmberCluster.DEFAULT_BASE_PORT` positionally. Every existing `emberConfig(...)` overload keeps its
  signature and defaults the new value, so no caller changes behaviour.
- **Configurability alone would not have fixed this, and propagating the bind failure would not
  either — there is no bind failure.** `QuicClusterServer` sets `SO_REUSEADDR` on its
  `NioDatagramChannel` (deliberately: a restarting node must rebind its own port at once), and two UDP
  sockets that BOTH set it bind the same port successfully. Measured on Darwin 25.5.0 across all four
  holder/probe combinations: both-`SO_REUSEADDR` is the ONLY one where the second bind succeeds, and
  Forge-versus-Forge is exactly that case. So the second instance does not fail to bind — it binds,
  splits the range's datagrams with the first instance, and never reaches quorum.
  `QuicTransportError.BindFailed` already names the port and already aborts the whole cluster start;
  it simply never fires here.
- **New `ForgePortPreflight`, run before any node is created**, binds each port of
  `base_port .. base_port + nodes - 1` **without** `SO_REUSEADDR` — which the kernel does refuse while
  any holder exists — and on a collision Forge logs the occupied ports and exits non-zero.
  **The message discriminates the two causes of `activePeerCount=1`**, naming the ports, stating that
  it is a port collision caught before startup, and that no node was started so it is not stale
  `forge-data` state. That ambiguity is what cost the debugging time in the reported incident.
  The startup banner now also prints the QUIC range.
- **Limitation, stated rather than papered over:** the preflight is a time-of-check/time-of-use probe.
  Each socket is closed before the cluster binds the port for real, so a process claiming it inside
  that window is still silent. It converts the overwhelmingly common case — another Forge already
  running — from silent to loud; it is not a lock and is not claimed to be one.
- **Not done here:** issue item 3, enumerating likely causes on a live `activePeerCount=1`, is left
  open. It belongs to the membership/status surface rather than to Forge startup, and the preflight
  removes the port collision from that symptom's candidate set at the only point where it can be
  ruled out cheaply.
- `aether/docs/slice-developers/forge-guide.md` documents `base_port` and what running two instances
  requires; `aether/docs/operators/runbooks/lifecycle-verification.md` step 3 said that no
  configuration overrides the QUIC base port and that two instances therefore cannot coexist — true
  when written, false as of this change, and corrected.
  [mechanism: `ForgePortPreflight` plain-bind probe — `ForgePortPreflightTest` (7 tests, `forge-core`),
  `EmberConfigBasePortTest` (7 tests, `ember`); both mutation-probed, see the PR for the reddened sets]
