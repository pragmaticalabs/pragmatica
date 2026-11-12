### Fixed (2026-11-12 — #1697: the owner's view of a replica kept counting copies a restarted replica had lost)
- **A replica that restarted under the same node id and now held less could not lower the owner's view of it.** Since
  #2888 the owner kept the highest acknowledged offset within one owner epoch, so a restarted replica's lower ack was
  refused and the owner kept counting records that replica no longer held. `ReplicateAck` now carries the replica
  process's boot token (random per process, compared by equality only). The owner keeps the highest offset acknowledged by
  one process. An ack from a different process replaces the row, even with a lower offset. A reordered ack from the same
  process still cannot lower the view. Acks and evidence that name no process (token `0`, such as the owner's own pull from
  a survivor) can still only raise the row.
- **Flag day:** the `ReplicateAck` wire shape gains a trailing `long bootToken` (tag 100 unchanged), and the codec is
  positional, so a node on the previous build cannot decode acks from a node on this build, or the reverse. Upgrade by
  replacing every node.
- Not guaranteed: tokens are unordered, so an ack from a SUPERSEDED process that arrives after the new process's ack also
  replaces the row, until the current process's next ack replaces it again. In production a restarted process gets a
  new node id, so this case arises only with Forge/Ember same-id restarts.
