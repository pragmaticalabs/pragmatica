### Fixed (2026-09-10 — #952: start-postgres.sh reported success for a database that was not there)

- **The readiness loop had no failure branch.** It counted to 30 and fell through, printing
  `PostgreSQL ready` and exiting 0 whether or not anything was listening — so its success carried no
  information, and a container that had died 295 ms after start was indistinguishable from a healthy
  one. The failure surfaced ten seconds later attributed to `mvn install`, pointing the next reader
  at a component that was working.
  The loop can now fail. It checks the container is RUNNING rather than that a counter elapsed,
  bounds itself in time (`PG_READY_TIMEOUT`, default 60s), names the database and the container in
  the message, prints the container's own last 20 log lines, and exits non-zero. On success it
  reports the elapsed time and the probe COUNT, because "ready" with no number attached is exactly
  what the old loop printed for a dead container.
- **The readiness wait now runs on every branch.** The already-running branch skipped it entirely,
  which was a second blind path to the same false "ready": a container that is up is not a database
  that answers.
- **The data directory is derived from the image instead of hardcoded.** The script pinned
  `postgres:18-alpine` while mounting the pre-18 path. Measured on that image: `PGDATA` is
  `/var/lib/postgresql/18/docker` and the declared VOLUME is `/var/lib/postgresql`, so a volume
  mounted at `/var/lib/postgresql/data` held **nothing** — the cluster lived in the container's
  writable layer and every row was discarded by the next `rm -f` the script itself performs. The
  mount is now read from the image (its declared VOLUME containing `PGDATA`, falling back to
  `PGDATA`), so it cannot drift from the image pin again; an image that declares neither makes the
  script refuse rather than guess.
- **Forge's startup-deploy failure no longer names a cause it did not check.** The message ended by
  telling the reader to verify the artifacts were installed (`mvn install`). Forge never checks that,
  and on the clean-room run that found this the artifacts were all present. It now states what it
  actually did — POSTed the coordinates and recorded the response — says outright that it did not
  establish why that failed, and lists the candidates (wrong coordinates, unresolvable artifact, or
  an unavailable resource such as a database) without ranking them.
- **The scaffold template that generates this script was fixed too**, not only the four checked-in
  examples. Every new project gets its `start-postgres.sh` from `SliceProjectInitializer`, and that
  template carried the same blind loop; fixing only the examples would have left the defect shipping
  to every new user. The four example copies are now pinned byte-identical to one another — they had
  already drifted once, the examples moving to `postgres:18` while the template stayed on 17.
- [verified: the ticket's false success was REPRODUCED and then killed, under a single symmetric
  mutation applied identically to both arms (`max_connections=THIS-IS-NOT-A-NUMBER`, so postgres
  exits at startup deterministically): the script as committed at `8f02cd3cf` printed
  `PostgreSQL ready:` and exited **0** while `docker inspect` reported `Running=false ExitCode=1`;
  the fixed script, same condition, exits **1** with
  `ERROR: PostgreSQL database 'forge' is NOT available … exit code 1, after 1s and 1 readiness
  probe(s)` and surfaces postgres's own `FATAL: invalid value for parameter "max_connections"`.
  Data path proven by consequence on `postgres:18-alpine`, both arms same image: mounted at
  `/var/lib/postgresql/data` a row written before `docker rm` came back
  `relation "persistence_probe" does not exist`; mounted at the image's declared VOLUME it came back
  `42` — the second arm is the positive control that the probe can detect persistence at all.
  Re-checked through the real fixed script end to end: write, destroy container, re-run script, read
  → `952`.
  `script-gate` — `StartPostgresScriptTest` 7/7 against a stubbed container runtime (dead container
  names the database and never prints `PostgreSQL ready`; timeout bounded and probe-counted;
  already-running container still probed; ready container succeeds; and `dataMount_followsAPostgres18Image`
  / `dataMount_followsAPre18Image` expect mutually exclusive paths from the same code, so no hardcoded
  constant satisfies both). `jbct/jbct-init` — `GeneratedStartPostgresScriptTest` 3/3 running the
  GENERATED script. `aether/forge/forge-core` — `ForgeServerMessageTest` 4/4.
  Mutations, each reverted clean: deleting the container-running branch reddens `deadContainer_…`;
  hardcoding the mount reddens the pre-18 test and the refuse-to-guess test while the pg18 test stays
  green; restoring the `mvn install` blame reddens all 4 message tests; deleting the template's
  failure branch reddens `generatedScript_failsNamingTheDatabaseWhenTheContainerIsDead`.
  `mvn jbct:check -pl aether/forge/forge-core` — 2 Java files, 0 format, 0 lint errors.
  Root `mvn clean install` — BUILD SUCCESS, 145 modules, 13,445 tests, 0 failures/errors]
- **What is NOT covered:** the container DEATH the ticket reports (295 ms, exit 1 on a virgin Hetzner
  box) was **not reproduced on the verifying machine** — on aarch64 Docker Desktop 29.2.1 the same
  pre-18 mount starts and serves. What is established here is the mount defect by its persistence
  consequence, which holds on every box, and the readiness loop's false success by direct paired
  reproduction; the reporter's death mechanism is neither confirmed nor guessed at. Verification was
  not done clean-room: throwaway container and volume names meant no volume state pre-existed, but
  the image cache was warm. An existing `forge-pgdata` volume is effectively re-initialised by the
  mount change — it never held anything at the old path — and a pre-`postgres:17` volume would leave
  its old files orphaned inside the new mount; the now-failing readiness loop surfaces that loudly
  rather than silently, and no separate migration was added.
