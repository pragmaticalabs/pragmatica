### Fixed (2026-11-05 — #2694: ISR expansion could admit a replica without earlier acknowledged records at the start of an owner tenure)
- **The owner's ISR expansion read a member with no row as `-1`.** On a newly activated owner, an ISR member that had
  not yet confirmed to it lowered the high-water mark to `-1`, so any registered, unfenced replica outside the ISR —
  a returned node with a wiped disk, or a further node — joined without the records acknowledged in earlier tenures,
  and became electable. Expansion now counts only rows confirmed under the committed owner epoch, the same rows the
  acknowledgement counts (B11), and admits nobody until every ISR member has one. A replica that catches up joins
  once the members have confirmed; a row from an earlier tenure no longer admits a replica, however high.
