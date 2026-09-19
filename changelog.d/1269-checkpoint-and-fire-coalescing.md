### Performance (2026-09-19 — #1269: entity checkpoints encoded the whole fold before checking whether it advanced; saves and timer fires did not coalesce)
- **Every checkpoint tick copied and encoded the whole fold of every folded partition, owner and replica
  alike, before checking whether it advanced**, so an idle partition paid a full encode every 30 s for
  nothing. A checkpoint save slower than the tick was started again, and a timer whose key was stalled
  had one more fire queued behind the stall on every 1 s tick.
- `EntityFold.checkpointCandidate` now takes the offset last written and returns nothing, before copying
  anything, when the fold has not passed it.
  [mechanism: the floor is compared before `encodedFold` runs] — pinned by a unit test measuring bytes
  allocated per tick: an idle tick over about 10 MB of state went from 10,401,744 bytes to under 64 KB.
- The checkpoint driver skips a partition whose save is still in flight, and clears that mark however the
  save ends, including a synchronous throw. The timer tick queues at most one fire per timer while that
  fire is queued or running; the re-checks inside the key's tail are unchanged.
  [mechanism: a per-registration in-flight partition set and a per-entity in-flight `TimerId` set] —
  pinned by unit tests.
- **Known limit:** a save whose promise never settles now holds its partition's checkpoints until it does,
  where before each tick started another. `writes` and `checkpointedThrough` on the checkpoint snapshot
  surface stop advancing for that partition.
  [design intent — unverified: no timeout is applied to a save]
