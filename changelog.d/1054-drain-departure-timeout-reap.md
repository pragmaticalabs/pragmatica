### Fixed (2026-09-13 — #1054: a drain-initiated departure reaps the target after a fixed 15s even if the DRAIN was never delivered)
- **An undelivered DRAIN declared a live, serving node DEAD and reaped its instance.** A drain moves the target to
  DEPARTING on the issuer's membership FSM at once, but the DRAIN itself reaches the target only on the leader's next
  ping. The DEPARTING timeout (`splitTimeout`, 15s) terminalized the target unconditionally, so a delayed or lost
  delivery (leader change, transient link loss) produced DEAD → `NodeRemoved` → the active CTM's container reap for a
  node that had never heard the command.
- The DEPARTING timeout now withdraws a drain back to MEMBER when all of these hold:
  - the departure was drain-initiated;
  - the member had joined;
  - the target never acknowledged this drain;
  - no death evidence arrived: no SWIM FAULTY, and no liveness loss that a re-established link has not since
    contradicted.

  A withdrawn drain produces no DEAD edge and no REMOVED delta, so no `NodeRemoved` and no reap
  `[design intent — unverified]` on a multi-node live path. It is exercised in-JVM by `MembershipFsmTest.DepartingTimeoutH2`
  and, through an assembled three-node leader, by `EmberDrainAcknowledgementWiringTest`.
- **The acknowledgement is latched per drain episode.** The leader records `DRAINING` from the target's pong, and the
  pong fan reports it to the FSM as it lands. A drainee that acknowledges and then halts stops ponging, and the leader's
  readiness sweep forgets it within three pings. The latch keeps it acknowledged, so it still terminalizes at expiry
  instead of being withdrawn to MEMBER and re-added to the DHT ring. The latch resets on every entry into DEPARTING;
  a newer incarnation ends the episode through the existing recovery edge `[design intent — unverified]`.
- A delivered, acknowledged drain terminalizes at expiry with exactly one REMOVED edge. A withdrawn drain delivered later
  ends in exactly one death through SWIM's FAULTY edge, which emits `FaultyObserved` and `DepartedObserved` together
  `[mechanism: REMOVED fires only on the fresh DEAD edge of a member that had joined, and DEAD absorbs every later SWIM event]`.
- Non-drain departures are unchanged: a sustained-absence departure (`DownHysteresisMet`) and a drain issued before the
  member joined still terminalize at expiry `[design intent — unverified]`.
- **Not covered here:**
  - The CTM's drain grace-terminate backstop still terminates the target after `provisioningTimeout` (60s default)
    whether or not the DRAIN arrived. That path is #1050.
  - A withdrawn target can be re-selected by the reconciler's surplus follow-up and drained again about every 15s while
    the DRAIN stays undeliverable, pruning and re-adding it on the DHT ring each time. It is scoped with #1055.
  - Drain behaviour on a real multi-node forge cluster is covered only by the Heavy forge tests, which run nightly or
    on label under #1063, not on every PR.
- **Operator recovery:** a withdrawn drain logs `DEPARTING timeout for <node>: DRAIN never acknowledged and no death
  evidence — withdrawing the drain`. The target is a counted member again, but only until the CTM's grace-terminate
  backstop fires `provisioningTimeout` (60s default) after the original drain request. That backstop terminates the
  target's instance and clears the DRAIN command. If the node should stay, cancel the scale-down that selected it before
  that backstop fires. If it should go, let the backstop reap it, or re-issue the drain once the leader can reach it.
