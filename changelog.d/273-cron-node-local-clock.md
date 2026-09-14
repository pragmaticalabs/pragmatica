### Changed (2026-09-14 — #273 item 3: the cron clock source is documented as node-local)
- `@Scheduled` cron next-fire times come from the node's own wall clock (`Instant.now()`, UTC via `CronExpression`);
  no cluster clock, HLC not consulted. Stated in `ScheduledTaskManager.TaskOps.nextCronFireAt`'s javadoc and in
  `resource-reference.md` ("Clock Source"): ALL-mode fires per node at that node's reading of the boundary, SINGLE-mode
  uses the leader's clock and a leader change moves the reference, a stepping clock can double-fire or skip a boundary
  on that node. No behaviour change [mechanism: `nextCronFireAt` → `cron.delayUntilNext(Instant.now())`].
