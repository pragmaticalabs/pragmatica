### Fixed (2026-09-19 — #1249: DurableTopicDeliveryForgeTest was @Disabled and had never been observed green)
- **The composed durable pub/sub forge test gated nothing**: it was disabled, its setUp waited on a
  warm-up event that is never delivered on this branch, three arms counted each other's events, and
  the group-isolation arm could pass without the probe reaching the healthy group.
- The test is enabled. Every arm now publishes under its own ids and counts only those, per id, from
  the fixture's per-group records. Counts are taken once per slice instance, not once per HTTP port:
  app HTTP forwards when a node has no active local instance, and one run counted a 5-attempt event
  as 10. The isolation arm now requires the probe to reach the failing group, the healthy group to
  handle it exactly once, and the failing group's budget to stay at 5.
- Two arms are blocked by #1238 (PR #1285): the pre-attach backlog and serial dispatch under
  late acks. Each is kept `@Disabled` beside an enabled tripwire that asserts today's behaviour
  (the warm-up stays stranded; ten late-acked events come back 10, 9, … 1 times) and fails with
  instructions to swap them once #1285 lands. With #1285 merged into rc4 both real arms pass.
  [verified: aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/DurableTopicDeliveryForgeTest.java]
- Publish outcomes (#1236) and pre-durability visibility (#1235) have no arm. This harness cannot
  drive either without losing quorum or failing over the owner. [unverified: no arm reaches them]
