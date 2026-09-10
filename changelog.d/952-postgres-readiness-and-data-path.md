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
