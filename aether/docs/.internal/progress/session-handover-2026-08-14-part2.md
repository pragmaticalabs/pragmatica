# Session handover — 2026-08-14 part 2: owner decisions, the entity-error rename, and codec tag space

**Branch:** `release-1.0.0-rc3` · **HEAD:** `2c2809548` · **PUSHED** · **tree clean** · candidate tag re-pointed

Second arc of 2026-08-14, following `session-handover-2026-08-14.md` (auto-heal #597 + the harness
repairs). That handover's header says "NOT pushed (21 commits)" — **stale, everything is pushed now**:
24 commits from `598a1c021` to `2c2809548`.

This arc was mostly **owner decisions taken one at a time**, then executed. The rulings matter more
than the code; they are recorded in §1 so they are not re-litigated.

---

## §1 OWNER RULINGS — do not re-open without the owner

1. **API breaks are FREE right now.** No users, no published API, no backward-compatibility
   constraint. Where a name or shape is wrong, fix it now rather than working around it. This ruling
   drove #432 and the codec change, and it applies to #596.
2. **#432 — rename the CODE, not the spec.** The spec had been amended to the shipped names (v0.4.0);
   the owner reversed that where the shipped name was the weaker one. Done, closed.
3. **#596 — explore option B (command-shaped mutations)**, not A (caller-routing). The command
   "should remain `Fn1<S, S>`, just somewhat hidden behind a sealed interface and record to make it
   transferable across the net." Design proposal posted; **awaiting sign-off, NOT approved yet.**
4. **#592 — fix the SOURCE (`SwimMember` label propagation), option 2.** Not the topology-consensus
   route, not the partial `WorkerConfig.zone` route.
5. **Codec tags — split the space:** `0..16383` system, ALL manually assigned, never reused;
   `16384+` hashed for slice-generated types. "Everything we have in Aether code now will be pinned
   since day one."
6. **The Aether book is mine to manage** — commit when necessary, no need to ask.
7. **Next-session order: codec Phase 2 → #590 → 02y/02w → zone test.** §2 is that plan.

## §2 THE PLAN FOR THIS SESSION (owner-set order)

### 1. Codec Phase 2 — pin the system types
Phase 1 landed (§4). Phase 2 is what the owner asked for and it is NOT done:

- Pin every current Aether/framework codec type into `0..16383` with hand-assigned tags. Framework
  primitives already occupy `0..20`; **`21..127` is free 1-byte space** (108 slots) and the hot
  protocol set is ~46 types (`DHTMessage` 14, `RabiaProtocolMessage` 7, `SwimMessage` ~7,
  `NetworkMessage` 6, `KVCommand` 4, `cluster.metrics.*` ~8) — it FITS, with room spare. Those go
  2 bytes → **1 byte**, a net wire WIN on the cluster's own highest-frequency traffic.
- **Implementation shape that needs no codegen change:** the generator already emits
  `SliceCodec.deterministicTag("fqcn")` as a RUNTIME CALL, so pinning happens inside that function —
  consult a central `SystemTags` map first, fall through to the hash. One reviewable file, no churn
  across 96 `@Codec` annotations, and no envelope-version question.
- **Enumeration must NOT come from grep.** I tried: 76 registered FQCNs vs 134 `@Codec` annotations,
  and the grep list contains test artifacts (`com.example.MyClass`,
  `ClusterEventCodecTest.TestExtendedEvent`). Enumerate from the processor's own view at build time,
  and add a check that FAILS when a system type is left unpinned.
- **Also owed: the blueprint-level assembly-time collision check** the owner asked for. Cross-blueprint
  collisions are structurally impossible (verified, §4), so the check is scoped to types WITHIN one
  blueprint.
- **Never renumber, never reuse.** A tag is a wire contract; two nodes disagreeing on one is
  undiagnosable corruption, not a clean failure.

### 2. #590 — worker communities do not dissolve on core isolation
`blocking`. The CP contract at the community tier is **unimplemented, not merely unvalidated**. It
also blocks #367's outputs 1 and 2, and it BOUNDS what the zone test may claim (§2.4).

### 3. 02y / 02w — broader codec verification
The smoke suite passed on the new tag derivation but only exercises consensus, membership, KV and one
slice. **Streams, entities and the DHT have the densest codec hierarchies and were NOT exercised.**
Run `02y` and `02w` before Phase 2 builds further on Phase 1. Both suites are green as of this session.

### 4. Zone test — #599
Filed. Write it to FAIL against current HEAD first (expects two zone-split communities, observes one),
then land #592, then confirm it flips green. Step 1 is not optional — without it the test passes today
for the wrong reason, because communities form by SIZE and zone never enters. Then update #367 to
cover the zone split alongside the size split. **No chaos in v1** (see #590).

## §3 #432 CLOSED — entity errors renamed

| Was | Now |
|---|---|
| `DurableEntityError` | `EntityError` |
| `DurableEntityProvisioningError` | `EntityProvisioningError` |
| `KeyAlreadyExists` | `EntityAlreadyExists` |
| `KeyNotFound` | `EntityNotFound` |
| `StaleOwner` | `StaleOwnerEpoch` |
| `TimerNotFound(key)` | `TimerNotFound(key, TimerToken)` |

**The principle for what was NOT renamed** — worth keeping, it generalises: *the same name for the
same CONCEPT across subsystems is a feature; the same name for DIFFERENT concepts is the defect.*
`StreamError.NotCurrentOwner` and `EntityError.NotCurrentOwner` say the same thing about a partition
owner, so they stay shared. `KeyNotFound` was **three unrelated types** — JWKS keys
(`SecurityError`), config keys (`ConfigError`), entity keys — which is what made it worth changing.
Unchanged for that reason: `NotCurrentOwner`, `StaleEpochRead`, `OwnershipNotYetCommitted`,
`LinearizableUnavailable`, `StorageFailed`, `TimerNotSupported`.

**A coupling that nearly bit:** the fixture slice reports `cause.getClass().getSimpleName()`, so **the
record's simple name IS the wire value**. The rename changed strings asserted by
`DurableEntityForgeTest` AND by the `02w-entity-crash` integration suite. A Java-only rename would
have left two green-looking tests asserting a string nothing emits.

Spec → **v0.5.0**. Book updated and pushed (`085284d` in `coding-technology`) — it now lists all TEN
variants rather than the spec's six, deliberately, because `sealed` makes the permitted set exhaustive
and a six-of-ten listing teaches a `switch` that will not compile.

## §4 Codec tag space — Phase 1 LANDED

`(hashCode % 16256) + 128` put every type in ONE 16256-slot space. Tags are VLQ-encoded, so 16256 was
not arbitrary: it is exactly the 2-byte varint ceiling. But it is **birthday-bound at ~127 types**, and
with ~100 codec types the collision probability was already **~27%** — and it hit for real.

**Now:** system `0..16383` (hand-assigned, 1-2 bytes) and user `16384..2097151` (FNV-1a hash, 3 bytes),
disjoint by construction. FNV-1a replaced `String.hashCode()` because our FQCNs share long prefixes
(`org.pragmatica.aether.resource.entity.Entity…`) and `hashCode` clusters badly on exactly that shape.

**Collision structure, verified in `SliceCodec.sliceCodec(parent, codecs)`:**
- slice vs system — impossible, disjoint ranges;
- **blueprint vs blueprint — impossible**, each slice gets its OWN registry layered over the shared
  system parent, so their types never meet (this is the owner's point, and it checks out);
- within one blueprint — possible, hence the assembly-time check still owed.

User tags live in a MAP, not the flat array: an array spanning the wide range would be ~16MB **per
slice**, and every slice builds its own registry. System tags keep the flat-array index — they are the
hot path.

**Evidence:** `./build.sh` green, lint 49/0 new · 1725 unit tests across 4 modules with ONE failure (a
stale range assertion, fixed) · serialization 31/0 · **smoke 2p/0f on a live 5-node remote cluster**,
which formed, elected a leader, reached quorum, deployed a blueprint and served app HTTP entirely on
the new tags · **the historical collision reproduces under the old derivation and not the new**:
`AetherValue.EntityCheckpointValue` and `HealthHintWire` both → **7612**. That pair is now pinned by a
named test so a future hash change cannot silently reintroduce it.

## §5 #596 — design proposed, AWAITING SIGN-OFF

Not approved. Do not implement without the owner.

`EntityCommand<S> extends Fn1<S, S>`; `DurableEntity<K, S, C extends EntityCommand<S>>`; commands are
self-applying records in a sealed hierarchy. The blocker for forwarding is that `update` takes
`Fn1<S,S>` and a lambda cannot cross a node boundary — but the slice JAR is on every node, so the CODE
is already cluster-wide and only the DATA needs to travel. A lambda has no name; a record does.

Three points from the discussion worth keeping:
1. **The strongest argument is not forwarding.** #351 durable timers must PERSIST `onFire`, and
   #353/#354's "journaled run-once step" is by definition a serializable description of work. This is
   the primitive I4–I6 are already blocked on; forwarding falls out as a side effect.
2. **Self-applying beats a registered handler** because it makes "command arrived, handler missing"
   unrepresentable — precisely on the forwarding path being added — and because a record's components
   ARE its state, so codec generation makes transferability a BUILD-TIME guarantee. No amount of
   discipline gets that from a lambda.
3. **The command type must be a TYPE PARAMETER** (owner's catch): `collectTypeArguments` walks every
   type argument of a resource-qualified parameter, so `C` is collected for free. A command type that
   is not a type argument is invisible to codec generation. The one gap:
   `addResourceTypeArgumentEntry` bails on non-record/enum, so a sealed interface lands in
   `requiredTypes` with no codec generated — it needs to recurse into permitted subclasses. Each
   variant is a record and takes the existing path, and since every variant is its own registered
   codec type, **the tag IS the discriminator** — no new wire concept.

**When #596 lands:** run the suite and confirm forwarding works, and DELETE `02w-entity-crash`'s
per-node endpoint rotation so it exercises the product's routing rather than the harness's.

## §6 Issues filed this arc

- **#599** — validate zone-based community formation on a real two-zone cloud cluster. Covers the ZONE
  axis; #367/#591 cover the SIZE axis and never touch zone.

## §7 Traps found this arc

- **`build.sh` does NOT run tests.** It reported PASS twice for changes whose real risk it structurally
  cannot see. For anything behavioural, run `mvn -pl <module> test -DskipITs` as well.
- **The Bash tool runs ZSH; the integration suites run BASH.** zsh does not word-split unquoted
  expansions. A helper returned ZERO endpoints under the Bash tool and FIVE under `bash -c '...'` — I
  nearly "fixed" correct code. Test anything destined for `tests/integration/` with `bash -c`.
- **`pgrep -f "<script>"` matches your own waiter.** Wait on a log MARKER instead.
- **Do not hand-create the harness's Docker networks.** `docker network create aether-b-network` makes
  a network without compose's label; compose then REFUSES it and the next run's cluster-A bringup
  fails. Let the harness own its networks.
- **`.claude/worktrees/` (11 of them) is a `find`/`grep` trap like `.ndx/`.** A bare
  `find . -name X.java` returned a WORKTREE copy first and I read a stale record definition from it.
- **Verify built artifacts by CONTENT.** The jar's mtime predated the commit and was still correct;
  `javap -constants` on the shipped class is what settled it.

## §8 Standing hazards

- `HCLOUD_TOKEN` is set. `mvn verify`/`install` reach `HetznerCloudIT` and create a REAL PAID server.
  Safe spellings: `./build.sh`, or `mvn -pl <module> test -DskipITs`.
- The `aether` CLI on PATH is rc2 and aborts the harness at version-parity preflight. Pin `AETHER_BIN`
  to a launcher that execs `aether/cli/target/aether.jar`.
- `.ndx/` is 144 GB — exclude from every repo-wide sweep.
- #250 storage GC — DO NOT WIRE naively.
- Before ANY cloud run: `tools/provision-test-pg.sh --print-only`, scoped reap only, hard 2h cap. The
  bare-reap path has destroyed `test-pg` before (#572).
