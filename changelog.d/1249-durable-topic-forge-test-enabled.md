### Fixed (2026-09-19 — #1249: DurableTopicDeliveryForgeTest was @Disabled and had never been observed green)
- **The composed durable pub/sub forge test gated nothing**: it was disabled, its setUp waited on a
  warm-up event that is never delivered on this branch, three arms counted each other's events, and
  the group-isolation arm could pass without the probe reaching the healthy group.
- The test is enabled. Every arm now publishes under its own ids and counts only those, per id, from
  the fixture's per-group records. Counts are taken once per slice instance, not once per HTTP port:
  app HTTP forwards when a node has no active local instance, and one run counted a 5-attempt event
  as 10. The isolation arm now requires the probe to reach the failing group, the healthy group to
  handle it exactly once, and the failing group's budget to stay at 5.
- Two arms were blocked by #1238 (PR #1285): the pre-attach backlog and serial dispatch under
  late acks. Until #1285 merged each was kept `@Disabled` beside an enabled tripwire asserting the
  pre-fix behaviour (the warm-up stays stranded; ten late-acked events come back 10, 9, … 1 times).
  With #1285 in rc4 both tripwires went red as designed, and both real arms are now enabled and pass.
  The class is `@Tag("Heavy")`, so `ci.yml`'s forge-tests job never runs it; only the `run-heavy`
  label or a `heavy-forge` dispatch does.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableTopicDeliveryForgeTest.java]
- The fixture is pinned to every node (`instances = minAvailable = maxInstances = 5`) and setUp waits
  for all five ACTIVE on distinct nodes before any arm publishes. Left autoscalable, the slice was
  descaled 5 → 4 → 3 mid-run and no consumer was attached anywhere for 5.5 minutes (#1389), and a
  late `forceActivatingToActive` moved the consumer inside an arm's window.
- The pre-attach arm asserts on one id, never a count: the order-events warm-up that is definitely
  in the log before the attach — its publish returned success, or its outcome came back unknown (5 s
  replication timeout, #1236) and the partition owner's head offset (`GET
  /api/v1/streams/{name}/{partition}/replicas-local`, the one stream read route that takes the raw
  `topic:` engine key) advanced by exactly one across the attempt — with `attachedSubscriptions`
  reading 0 on every node afterwards. Warm-ups that neither resolved nor could be observed are
  excluded and retried under a fresh id; a retry delivered by the listener after the attach used to
  satisfy the old `>= 1`, and the gate publishes nothing further once an id is established, because
  the gate's own retry append is what would drain a stranded backlog. The arm claims the
  subscribe-time backlog read only in push mode — the owner's instance ACTIVE when the id is
  established, and the group attached on the partition owner afterwards — since a non-owner assignee
  polls through forwarded reads and reads the backlog on every tick regardless. A run in which either
  is not observed skips the arm with the readings in its message (a `SETUP SHAPE` log line carries
  them too), since nothing in that run can speak about the backlog read. Observed rate: 0 skips in
  the 6 established runs of the final round (4 unmutated green, 2 with the kick removed red), but the
  shape is a race the test observes rather than forces, and the class dies in setUp when an instance
  stays LOADED/ACTIVATING in the cluster map for 4 minutes (#1117, 4 of 22 launches in the last two
  rounds). The deterministic form of this arm is #739.
- `StreamConsumerManager`'s guarantee doc said replay after an ungraceful move is bounded by the
  checkpoint cadence "≤1s of progress — 500ms for durable-topic groups"; the cadence is evaluated only
  when a delivery advances the cursor, so a lone trailing event is not checkpointed until the next
  delivery and a move in that window replays it (#1385). The line now says so.
- Publish outcomes (#1236) and pre-durability visibility (#1235) have no arm. This harness cannot
  drive either without losing quorum or failing over the owner. [unverified: no arm reaches them]
