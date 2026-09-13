### Fixed (2026-09-13 — #1054: a drain-initiated departure reaps the target after a fixed 15s even if the DRAIN was never delivered)
- **An undelivered DRAIN declared a live, serving node DEAD and reaped its instance.** A drain moves the target to
  DEPARTING on the issuer's membership FSM at once, but the DRAIN itself reaches the target only on the leader's next
  ping. The DEPARTING timeout (`splitTimeout`, 15s) terminalized the target unconditionally, so a delayed or lost
  delivery (leader change, transient link loss) produced DEAD → `NodeRemoved` → the active CTM's container reap for a
  node that had never heard the command.
- At expiry, a departure that was drain-initiated, whose member had joined, that carries no death evidence (SWIM
  FAULTY, or a liveness loss not contradicted by a re-established link) and whose DRAIN the issuer never saw
  acknowledged is now withdrawn back to MEMBER: no DEAD edge, no REMOVED delta, so no `NodeRemoved` and no reap
  `[mechanism: MemberTracking.terminalizeIfStillDeparting dispatches DrainUnacknowledged (DEPARTING → MEMBER) instead of Stopped; pinned in-JVM by MembershipFsmTest.DepartingTimeoutH2 and DrainAcknowledgementSeamTest, which drive the real FSM timer]`.
  The acknowledgement is the target reporting `DRAINING` on its pong, read from the leader readiness view that the
  CDM draining set already reads.
- A delivered, acknowledged drain still terminalizes at expiry with exactly one REMOVED edge, and a withdrawn drain
  delivered later ends in exactly one death through SWIM's FAULTY edge, which emits `FaultyObserved` and
  `DepartedObserved` together `[mechanism: REMOVED fires only on the fresh DEAD edge of a joined member (everJoined), and DEAD absorbs every later SWIM event]`.
- Non-drain departures are unchanged: a sustained-absence departure (`DownHysteresisMet`) and a drain issued before
  the member joined still terminalize at expiry `[mechanism: withdrawal requires the DEPARTING tenure to have been entered on DrainRequested and the member to have joined]`.
- `[design intent — unverified]` on the live path: no multi-node run with a dropped DRAIN has been performed.
- **Not covered here:** the CTM's own drain grace-terminate backstop still reaps the target after
  `provisioningTimeout` (60s default) whether or not the DRAIN arrived. That path is #1050.
- **Scope of the acknowledgement:** it is visible only on the node receiving pongs, which is the leader. A drain issued
  on a follower is never taken as acknowledged, so its expiry always withdraws. A leader change between delivery and
  expiry moves that drainer's reap from the 15s timeout to SWIM's FAULTY edge.
- **Operator recovery:** a withdrawn drain logs `DEPARTING timeout for <node>: DRAIN never acknowledged and no death
  evidence — withdrawing the drain`. The target stays a counted member, and the drain stays registered until the
  CTM grace-terminate clears it. To remove the node, re-issue the drain once the leader can reach it
  (`aether node drain <node>`, or let the reconciler re-issue it on its next surplus evaluation).
