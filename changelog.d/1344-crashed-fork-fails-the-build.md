### Fixed (2026-09-20 — #1344: a crashed surefire fork could leave Maven exiting 0 with later test classes unrun)
- **A test fork that ended its plan early and then said goodbye read as BUILD SUCCESS.** Surefire
  does fail a fork that dies without saying goodbye, and JUnit reports a `StackOverflowError` on the
  test thread as an ordinary error; the false green was a third path. A recursion bottomed out inside
  `java.lang.StringBuffer`'s static initialiser and poisoned the class for the JVM's lifetime; when the
  test's await later failed, surefire's listener threw `NoClassDefFoundError` while writing the
  failure, JUnit's attempt to log that threw the same error, the Throwable escaped the launcher, and
  `ForkedBooter.execute()` caught it, could not report it, and said goodbye from its `finally` —
  BYE, `System.exit(0)`, five classes never run. [mechanism: surefire 3.5.1/3.5.4
  `ForkedBooter.execute` → catch(Throwable) → `logger.error(e.getLocalizedMessage(), e)` throws →
  `finally { acknowledgedExit() }`; the fork's `*-jvmRun1.dump` from the 2026-09-19 run and from 2 of
  3 re-runs on `0a244edc3` shows `Could not initialize class java.lang.StringBuffer … Caused by:
  java.lang.StackOverflowError`]
- **Two witnesses now fail the module after every test phase**, through the root pom's
  `test-plan-gate` profile (active for every module with `src/test`, skipped with `-DskipTests`):
  the new `test-plan-witness` module registers a JUnit `TestExecutionListener` that writes
  `target/surefire-reports/<ts>-<pid>.plan-incomplete`, listing the planned classes, when the plan
  starts and removes it only when the plan finishes — which JUnit does not call from a finally, so
  whatever ends the plan early leaves the marker; and surefire's own fork-side `*-jvmRun*.dump` is
  read as the second witness. `maven-antrun` fails the module at `test` (surefire-reports) and
  `verify` (failsafe-reports) when either survives, naming the files, and sweeps stale ones before
  the plugin that fills them, so a Ctrl-C last week cannot fail today's build.
  [verified: `script-gate` `PlanWitnessTest` — the marker exists while a plan is open, names the
  planned class, is gone once finished, survives a plan that never finishes, and
  `thisFork_isWitnessed_byAMarkerNamingThisClass` reads this module's own marker from inside the
  running fork, so unregistering the listener reddens it; a deterministic repro of the goodbye path
  (an `OutOfMemoryError` subclass whose message throws, so JUnit rethrows it as unrecoverable and the
  fork's error report fails) went from rc=0 / 0 testcases / 3 classes unrun to rc=1 with the gate,
  and s1234b's P18 on `0a244edc3` went from 2/3 false greens to 3/3 red; removing the gate's `<fail>`
  makes the repro green again]
- **Known limits.** (1) A fork that finished its plan but could not exit within surefire's 30 s
  deadline (a blocking shutdown hook) MAY now be RED — by ruling, since a surefire dump is abnormal;
  the gate's message says which kind survived ("plan incomplete: …" vs "surefire fork dump
  present: …"). It is not a certain red: surefire's `*-jvmRun*.dump` for the exit-deadline case races
  the fork's own halt against the plugin's 30 s timer, so the same >30 s hook went red in 1 of 3
  runs (rev1348 round 2; the other 2 wrote only a `.dumpstream`, which the gate does not read). (2) The gate fails loud when surefire wrote reports in this run
  but no plan was witnessed (a deactivated listener, a missing dependency), so the marker half cannot
  be switched off silently; the check reads only XML newer than the sweep's stamp, because reports
  are never swept. (3) The published parent pom's `test-plan-gate` profile depends on
  `test-plan-witness`, which is never deployed (`maven.deploy.skip`): an external project that
  inherits from `org.pragmatica-lite:pragmatica` and has `src/test` fails dependency resolution on
  `org.pragmatica-lite:test-plan-witness:jar` the moment its test phase runs, and cannot opt out
  without `-P!test-plan-gate`. Not addressed here; the fix is either to deploy the witness or to
  guard the profile so it activates only inside this reactor.
