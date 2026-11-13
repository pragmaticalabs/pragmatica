### Fixed (2026-11-12 — #2642 item 3: a superseded consumer-group rebalance answered HTTP 500)
- **`CoordinatorError.SUPERSEDED` now answers 409 Conflict** on the consumer-group join, leave and group-create routes. A
  deposed leader refusing a rebalance because a newer term already committed is an expected race outcome, the same class of
  answer as `NOT_LEADER`; it was an unmapped cause and fell through to 500. The status is a marked guess (the issue allows
  "409 or a retriable status"). Listed in the central `ManagementStatus` table; pinned through the three real routes.
- Pinned (test only): the shared rebalance counter stays strictly increasing across leader term changes 1 -> 2 -> 3.
