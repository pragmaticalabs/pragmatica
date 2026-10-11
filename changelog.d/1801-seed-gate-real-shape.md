### Fixed (2026-10-01 — #1790: the 03 seed's membership gate could never settle on a real node)
- **The gate required `installedVoters == targetVoters`, but a real STABLE node sends no `targetVoters` at all** (the serializer
  omits empty lists; the documented meaning is "the requested roster, empty when none"). On the cloud run it read
  `installed=[core-0..core-4] target=[] members=5` and warned "not settled within 90s" after a full 90 s every time. The
  gate now needs stage `STABLE`, any reported `targetVoters` equal to `installedVoters`, and members == voters, on two reads.
  Its tests had fed a `targetVoters` list the node never sends (they shared the gate's premise); they now run against a
  captured real body (`test/fixtures/node-status-real-stable.json`).
- **A parser that no longer matches the wire shape is a loud FAIL, not a wait-then-warn:** a body that names
  `voterReconfiguration` / `installedVoters` but yields no stage / no installed list returns rc 2 with a counted `log_fail`, and
  the seed does not run on top of it.
  [verified: `aether/tests/integration/test/test-scale-down-victims.sh` M1-M7, T2; reverting the gate to the installed==target
  rule reddens M1 and M4; removing the tripwire reddens M5-M6. No cloud run.]
