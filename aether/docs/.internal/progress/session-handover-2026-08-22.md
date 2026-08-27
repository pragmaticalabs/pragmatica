# Session handover — 2026-08-22/23: the named-command primitive, owner-forwarding, and a livelock that took three refuted hypotheses to find

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as your
> own state. This stream (`~/IdeaProjects/pragmatica`) keeps the UNSUFFIXED name; `pragmatica-clone`
> handovers carry a `-clone` suffix.

**Branch:** `release-1.0.0-rc3` · **HEAD:** `1ce829bc9` · **all six open PRs merged** ·
`./build.sh` green as of the merge commit.

⚠ **ONE FILE UNCOMMITTED:** `PartitionBackfill.java` — the #631 fix (§10). Compiles;
`aether-stream` 676/0. NOT yet run through `./build.sh`, and NOT yet pinned by a test.

---

## §1 `Mutator<S>` — the primitive the rest of #345 was blocked on

`DurableEntity<K, S>` became `DurableEntity<K, S, C extends Mutator<S>>`; `update` AND `scheduleTimer`
take `C`.

**Why it had to come first.** A lambda has no name, so it can be neither persisted for a durable
timer's `onFire` (#351) nor forwarded to a partition owner (#596). The slice JAR is already on every
node, so the CODE is cluster-wide — only the DATA identifying which transition to run has to travel,
and a record has a name where a lambda does not. This is the primitive #351/#353/#354 need; forwarding
falls out as a side effect.

**Deliberately NOT `Fn1<S, S>`.** `Fn1.then`/`before` return a COMPOSED LAMBDA typed as `Fn1` — not a
record, no generated codec, no tag. Inheriting them would let `a.then(b)` typecheck and produce
something that looks like a transition and cannot cross a boundary, on exactly the paths this type
exists to make safe. (Owner's call, and it is the right one.)

**The type parameter does two jobs.** Codec collection walks type arguments of a resource-qualified
parameter, so `C` is collected for free — and because implementors declare a SEALED hierarchy, a
lambda cannot be passed at all. The blueprint build REJECTED a surviving `OrderState::expired` method
reference at compile time, which is the guarantee being enforced by the type system rather than review.

`[verified: durable-entity 136/0; aether/node 874/0 with no tag collision in the full assembly]`

### The codegen fix that makes it real

`FactoryClassGenerator.addResourceTypeArgumentEntry` bailed on anything not RECORD or ENUM, so a sealed
command root landed in `requiredTypes` with NO codec for any variant — **and the build still
succeeded**. Now it recurses into permitted subclasses.

`[verified: mutation — removing the recursion drops the blueprint's generated codec references from 8
to 1 while the build stays GREEN. That silence is the whole point: without the recursion the failure
waits for the first attempt to put a command on the wire.]`

## §2 #596 write-half — owner-forwarding, wired but NOT cluster-proven

A non-owner now forwards the write to the committed owner instead of refusing it.

- `EntityOwnerForward` / `EntityForwardRegistry` — SPI pair in `durable-entity`. Same pattern as
  `EntityLogSubstrate`: the entity states what it needs, the node registers the implementation, and
  `resource/durable-entity` still does not depend on any transport.
- `EntityOwnerAdmission.remoteOwner` — a POSITIVE owner reading. Empty for BOTH self-owned and
  not-yet-committed, so neither is mistaken for a destination.
- `EntityForwardService` (node) — wire pair on the FORWARD lane over `network::send`, correlation-id
  protocol, 30s timeout.

**Properties, each with a test:** unwired is inert; a failed forward writes NOTHING locally; the owner
re-runs its own admission so the hop cannot land a write its fence would refuse; an owner never
forwards to itself.

`[verified: unit + in-process seam — 6 tests, mutation-checked (disable forwarding → 1 failure + 1
error; forward when self IS owner → 1 failure)]`

**NOT integration-verified.** No command has crossed a real network between two real nodes. `02w-entity-crash`
is the suite that would show it, and it has been blocked all session. Do not upgrade this claim without
that run.

**The read half is untouched** — `BOUNDED_STALE` and `LINEARIZABLE` on a non-owner still refuse. #596
needs both halves.

## §3 CI now runs the gate it never ran

`jbct:check` (format-check + lint, non-mutating, `-pl '!jbct'`) added to `ci.yml`. PR #618 passed CI
and broke `./build.sh` because `mvn install` compiles unformatted, lint-violating sources happily.

**Proven in both directions before shipping:** format violation → BUILD FAILURE; lint violation
(`JBCT-EX-02`) → BUILD FAILURE; clean tree → SUCCESS across 66 modules.

**Scope is narrower than the step name suggests.** `jbct.skip=true` is inherited from the root pom and
only `aether/**` overrides it, so `core/` and `integrations/` are SKIPPED and pass unconditionally. My
first mutation test "passed" for exactly that reason. This mirrors `build.sh`, so it changes nothing —
but the AWS client work in `integrations/cloud/aws` is not lint-gated by either.

## §4 A live paid-server footgun, closed

`HetznerCloudIT` gated only on `HCLOUD_TOKEN` being PRESENT. `install` runs after `verify`, so any
machine exporting a token provisions billed infrastructure from an ordinary `mvn install` — and
**`HCLOUD_TOKEN` is set on this machine**. Now `@EnabledIfEnvironmentVariable(named =
"HETZNER_CLOUD_TESTS", matches = "true")`, following the repo's own convention (`ServerIT`,
`DomainNameResolverIT`). `[verified: 9 tests, 9 skipped without the flag]`

I had proposed `-DskipITs` for this; checking first showed it would have silently dropped
`EndpointRegistryIT`'s 7 real tests while being described as a safety improvement.

## §5 Harness: a failed suite no longer destroys its own evidence

`run_suite` now calls `capture_node_logs` on failure, BEFORE teardown, writing `docker logs --tail 400`
per node into `failure-logs/<suite>/`. Also: `AETHER_BIN` now rejects a binary not named `aether` —
resolution is `command -v aether` after prepending its directory, so a correctly-pointed,
differently-named binary passed the `-x` check and was silently ignored (cost me a run).

**This fix paid for itself the first time it fired.** Every diagnosis in §7 came from it. Yesterday's
baseline had to be REPRODUCED before it could be investigated, and a failure that does not reproduce
could not be investigated at all.

**Still owed:** `wait_for` evaluates its deadline only BETWEEN iterations and the per-call timeout is
cloud-only, so on `--env remote` one hung predicate ran **4596s against a 480s budget**. The #441 fix
bounded the LOOP, not a single CALL. Not attempted — bounding it needs either exported functions into
a `timeout … bash -c` subshell or backgrounding-and-killing, on the primitive all 17 suites depend on.

## §6 Six PRs merged

#623 (build stamp in `--version`), #625 (`jbct.includeTests` inert — subclass field shadowed the one
`collectJavaFiles` reads), #626 (nested-comment severity docs), #627 (`examples/banking` never ran the
jbct plugin), #629 (`%nest`), #630 (peglib 0.7.3).

#629/#630 showed FAILING CI purely because peglib 0.7.3 was not yet in Central at run time — the same
pattern as #600 that morning. Merged locally after resolving purely additive CHANGELOG conflicts.
`build.sh` green afterwards, and **the regenerated parser reformatted nothing** — the real risk, since
a parser change is what produced the `Class< ?>` regression (#621) earlier the same day.

Three of the six are the same defect class as much of this session: **something configured, documented
and visibly present that does nothing.**

## §7 Three defects, separated by evidence

Full remote run on a pristine host: **12 cluster-A suites green.**

- **#598 `06-deployment`** — pre-existing. Two cluster-A suites publish blueprints whose default
  `schema/` root both name the datasource `database`; the loser gets 409 and fails four steps later
  with an unrelated signature. The captured logs contain the literal 409, so this is now proven rather
  than reconstructed. **Serializing the suites would NOT fix it** (the claim persists) and **dropping
  v2's migrations would NOT fix it** (ownership strips the version, so 1.0.0 is refused too). One of
  the two ARTIFACTS must move to a named datasource — a design call, still open.
- **#631 `03-scaling` + `02y-stream-crash`** — NEW, filed. `PartitionBackfill.waitThenPromote`
  suppresses cold-start promotion whenever a committed owner RECORD exists, without checking the owner
  still exists. 1436 pins on `entity:orders` (7 of 8) and 212 on `multipart-events` (4 of 4). In `02y`
  the pinned stream is the suite's OWN fresh blueprint and the pin spans the entire 240s deploy window
  — the livelock IS the timeout, making this core-path, not a scale-down edge case.
- **#628 `02-chaos`** — mechanism still UNKNOWN. 5p/2f in **17476s (4.9h)** vs 5565s baseline; pre-kill
  precondition found **0 running containers**. Since the suite kills into sub-quorum and expects
  survivors to self-drain and exit BY DESIGN, an empty cluster is a legitimate intermediate state —
  so look at the inter-scenario RESTORE, not the kill path.

**I was wrong once here and the evidence caught it.** I posted the #631 livelock as #628's candidate
mechanism; `02-chaos`'s own logs refuted it (2 occurrences vs 1436/212). Corrected on the issue. A
plausible-but-wrong mechanism on a tracked ticket is worse than none.

## §10 #631 — the livelock, and the three hypotheses that were wrong

**Read this before touching `PartitionBackfill`.** Three plausible diagnoses died here; each would have
produced a fix that changed nothing.

### What it is NOT

1. **"The promotion gate never checks owner liveness."** It does —
   `AetherNode.committedOwnerStillAlive(membershipFsm, owner)` already exists, is already wired into the
   source `PartitionBackfill` receives, and already handles the empty-member-view caveat
   (*"Empty membership means 'cannot judge liveness', not 'nobody is alive'"*).
2. **"Membership failed to evict the dead owner."** It did not fail. `02y` logs: owner DEAD at 16:50:01,
   evicted by 16:50:09, replacement joined 16:50:32 — all BEFORE the livelock began at 16:51:06.
3. **"The owner self-promotes without pulling from a replica that is ahead."** `decideOwnerCatchup`
   already pulls when `bestTail > localWatermark`, and already refuses to promote when ANY peer is
   unreachable. The owner-side logic is correct.

### What it actually is

`02y` deploys a **brand-new stream**. Nothing has ever been written, so:

- the owner is legitimately at watermark **-1** and self-promotes — correct, and its probe confirmed
  every peer reachable and equally empty (`"owner self-promoting to CAUGHT_UP at watermark -1
  (no reachable source ahead) [authoritative owner]"`)
- replicas request catch-up, get an EMPTY response
- **#445 says an empty owner read is never trustworthy** → route to the no-source path
- bound elapses → "a committed owner exists" → stay SYNCING → forever

**#445 cannot distinguish "empty because failover lost the history" from "empty because nothing was ever
written".** For a fresh deploy the second is the NORMAL case, and the gate treats it as the dangerous
one. Two safety gates, each correct alone, jointly making genesis unrecoverable.

### The fix (uncommitted)

`waitThenPromote` suppressed on the mere EXISTENCE of an owner record. It now defers only on a POSITIVE
reading that the owner is AHEAD:

- owner tail > self → defer (it holds history we lack — the real #445 case)
- owner unreachable → defer (an unknown tail must not be read as an empty one)
- owner not ahead → fall through to the probe contest

**No wire-format change**, deliberately: `CatchupResponse` is tag-pinned at 99 and rolling upgrade is
Phase-1 only, so adding a record component would change a system message's encoded shape. Asking the
owner for its tail is strictly more information than asking whether its record exists.

**The gate gets FINER, not looser.** `decidePromotion` — the contest it falls through to — already
refuses to promote when any peer is unreachable or any peer is ahead. The blanket suppression it
replaces was strictly coarser than the check it deferred to.

`[verified: aether-stream 676/0. NOT pinned by a test yet, NOT through build.sh, NOT run against the
suites.]`

### Scope limit — do not overstate this

**Proven for `02y` (genesis). UNPROVEN for `03-scaling`.** `03-scaling`'s captured nodes show only
replica-side messages; its owner's log is the one the capture LOST to `Connection reset by … port 22`
(42 bytes). Its shape may be a genuine failover this fix does not touch. A re-run with a working
capture is the only way to know — and the capture should probably retry on SSH failure.

## §8 Traps found

- **Maven's incremental compiler will hand you a false green.** `test-compile` reported BUILD SUCCESS
  in 0.73s against 59 STALE class files, because test SOURCES had not changed even though the interface
  they compile against had. Only `clean test-compile` revealed 7 broken files. Same family as the
  fixture-staleness trap.
- **`git checkout --` on a file whose changes are uncommitted reverts ALL of them.** I used it to undo
  a mutation and destroyed the method I was testing. Use a `cp` backup for mutation testing.
- **A CI re-run rebuilds the SHA pinned in the ORIGINAL event.** It does not retest a moved base; only
  a new push does. (Now in memory.)
- **`@Contract` is the sanctioned marker for registration sinks** (`EntityCheckpointDriver` uses it 5×).
  And JBCT-RET-07 fires on a METHOD REFERENCE where it accepts an identical lambda — my first fix
  (reformatting) was wrong.

## §9 Next

1. **#631 — fix is written but UNCOMMITTED (§10).** Next: pin it with a test (empty owner + empty
   replicas → promotes; owner ahead → still defers; owner unreachable → still defers), then `build.sh`,
   then `02y` and `03-scaling`. Ignore the earlier "add a membership filter" plan recorded elsewhere —
   that check already exists and was not the defect.
2. **#598** — pick which artifact moves to a named datasource. The product's own error message says
   "give this blueprint its own datasource section". Moving `test-persistence` is cheapest but
   `10-database` is the schema suite, so the default `[database]` path is arguably its subject; moving
   `url-shortener` changes a shipped example; a dedicated migration-free fixture for `06-deployment` is
   cleanest and most work.
3. **#628** — inter-scenario restore, and the unexplained 3× duration blow-up.
4. **#596 read half**, then the integration proof for the write half once `02w` can run.
