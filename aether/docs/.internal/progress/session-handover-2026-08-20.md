# Session handover — 2026-08-19/20: two design gaps closed on evidence, and the value-object floor that made AWS ingress cheap

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers into this directory on this shared branch — check the banner before
> reading one as your own state.
>
> - **This stream** (`~/IdeaProjects/pragmatica`): releases, integration-test environment, cloud sweeps.
>   Handovers keep the UNSUFFIXED name (`session-handover-<date>.md`).
> - **pragmatica-clone** (`~/IdeaProjects/pragmatica-clone`): design artifacts, jbct tooling, PRs for
>   this stream to review. Handovers carry a `-clone` suffix — `session-handover-2026-08-20-clone.md`
>   is the parallel one for this same day and covers pg-tools/peglib work.

**Branch:** `release-1.0.0-rc3` · **HEAD:** `2a9fbaf51` · **ALL PUSHED** · tree clean · candidate tag on
HEAD · `./build.sh` green, `lint: 49 findings (all in baseline; 0 new)`.

**rc3 open: 44 → 49.** Scope growth is DELIBERATE — owner ruling 2026-08-19: close design gaps before
rc4, and rc4 should be production-ready. Do not "trim" this milestone back.

---

## §1 The pattern that ran through the whole session: check the premise, not the ticket

Four separate items rested on premises the code did not support. In each case the check was cheap and
the fix would have been wrong without it.

- **#611** ("three independent durable logs, each hardened separately") — CLOSED, rejected on evidence.
  There is ONE durable log. `TransitionJournal` is two `ArrayDeque`s with no `java.io`/`java.nio` import
  at all; Rabia has no log — whole-snapshot save/load, four call sites, NONE on the commit path, and
  disabled by default (`BackupConfig` returns `enabled=false`). Only `PartitionWal` is a real durable
  log (fsync-before-ack, group commit, CRC + torn-tail replay), and it is already generic.
- **§11's fail-closed rule** — would have fired on healthy clusters. `allow_ingress` is OPTIONAL and
  PF-23 explicitly tells operators to manage ingress themselves, so "no firewall for this source" is a
  supported configuration where every bootstrap peer is equally unfirewalled.
- **§12's "two consumers"** — there are FOUR readers of `CAUGHT_UP`, and two of them must NOT be
  guarded (self rows: a node never acks itself, #593).
- **#444** — its own title's "provider-agnostic SourceProfile" premise was already satisfied by other
  means; the real residual was unfirewalled auto-heal replacements.

**The generalisation:** a ticket is a hypothesis. Read the code it names before implementing it, and
read the code it does NOT name before believing its scope.

## §2 Landed: defects

- **Entity min-sync counted the owner twice.** `DurableEntityConfig.minSyncReplicas()` documents that
  `2` means "owner plus one peer, i.e. ONE distinct non-self ack", but `StreamEntityLogSubstrate`
  passed the raw value to a function counting non-self acks. At the default RF=3 that required BOTH
  peers alive — losing any single peer failed every entity write, which is the failure replication
  exists to survive. At RF=2 no entity write could ever succeed. Both stream writers already
  subtracted; this was the third writer on the same barrier and the only one that did not.
  **Distinct from #596** — that is the absence of owner-forwarding, and its own evidence (4 of 40
  creates acked) rules this out as its cause.
- **Unknown segment age blocked ALL eviction.** `SegmentIndex.rebuildFromRefs` reconstructs from ref
  NAMES, which carry only `stream/partition/start-end`, so every segment came back with
  `maxTimestamp = 0` after a restart — and `isSegmentExpired` returned false on that BEFORE calling the
  policy, so size and count limits were skipped too. Nothing sealed before a restart was ever
  reclaimable. Age-based retention for those segments is still impossible (the age is recorded nowhere);
  fixing that needs `maxTimestamp` persisted, which is a ref-format change.
- **A sync-quorum test raced the resync timer, not a short sleep.** `RabiaEngine.doSynchronize` CLEARS
  `syncResponses` when a retry finds fewer than a quorum, so the test's first response was discarded
  before its second arrived. **Waiting longer could never have fixed it** — the response is gone. Fixed
  with a 60s retry interval so one sync round spans the test. Failed ~80% locally and twice on CI,
  including on a docs-only commit, which is what proved it was never a code regression.

## §3 Landed: §11 and §12 from the previous handover

- **§11 firewall-by-label** — auto-heal replacements were created with NO firewall association (accepts
  ALL inbound). Now resolved by `(cluster, source)` label at create. **The fail policy needed an
  owner-approved refinement**: source-scoped lookup empty ⇒ ONE cluster-scoped list to distinguish "this
  source manages no ingress" (create + WARN, parity with its equally-unfirewalled peers) from "a
  firewall exists but this source did not select it" (REFUSE). Do not simplify back to a bare
  fail-closed.
- **§12 CAUGHT_UP freshness** — lag-based, bound in OFFSETS, via `ReplicaRegistry.freshPeersFor`, ONE
  method for both consumers so it cannot be half-applied. Self rows deliberately unguarded.
  `PartitionBackfill.selectSource` also reads the raw state and is ALSO correct — it takes
  `max(confirmedOffset)`, which IS the freshness reference, so its donor has lag 0 by construction. An
  audit will flag it; check the arithmetic before "completing" that fix.

## §4 The value-object floor (#617)

Four commits, in order: firewall id widened from `long` to provider-opaque → `SourceName` →
`ClusterName` → `FirewallId` + `FirewallName`. 3095 tests green, `aether/slice` untouched throughout.

**Rules that made it work, for whoever continues #617:**
1. The VO lives in the LOWEST module all consumers can see — `environment-integration`, since
   `aether-config` depends on it and not the reverse.
2. Validate to the **common denominator across clouds**: RFC-1035 label (`[a-z]([-a-z0-9]{0,61}[a-z0-9])?`),
   which is the GCP network-tag grammar and the strictest of the supported providers. This replaces
   SANITIZING with PARSING — providers previously coerced out-of-charset values, silently producing a
   label that no longer round-tripped with the selector meant to find it.
3. **A total factory must still VALIDATE.** `sourceNameOrDefault` originally did not, so it could mint
   an instance violating the type's own grammar — which makes the type's central promise false. Mutation
   testing found the tightening completely unpinned, AND a test asserting the opposite.
4. `aether/slice` KV value records keep primitives. `TopologyEntry`'s own javadoc states the rule for
   `role`; `sourceName` follows it.

**Deleted a sentinel worth noting:** `UNKNOWN_CLUSTER = "unknown"` is itself a VALID cluster name, so a
cluster genuinely named `unknown` was indistinguishable from an unattributable server — and was refused.
Now `Option<ClusterName>`. Two behaviour changes: such a cluster now provisions, and a non-conforming
`AETHER_CLUSTER_NAME` reads as ABSENT rather than being mangled into a label no selector matches.

## §5 AWS ingress — code-complete, NOT verified beyond unit

`openIngress`/`closeIngress`, resolve-at-create, and full wiring. Six commits.

- **Client**: five EC2 Query calls, idempotent BOTH ways — duplicate authorize (`InvalidPermission.Duplicate`)
  and absent revoke/delete resolve as SUCCESS, or a re-bootstrap fails on rules that already exist.
- **Fail policy differs from Hetzner BY OWNER RULING.** Hetzner fails OPEN (no firewall ⇒ accepts all
  inbound) so it refuses; AWS/GCP/Azure default-DENY, so an unresolved group means unreachable, not
  exposed — WARN and proceed. Refusing there kills auto-heal to prevent an exposure that cannot occur.
- **VPC derived from the configured subnet**, not a new `vpc_id` knob. A security group must be in the
  same VPC as its instances; `AwsEnvironmentConfig` had `subnetId` and no VPC, and the LocalStack
  contract test builds a NON-default VPC — so passing none would have shipped a broken path. A second
  knob could be set inconsistently with the first; a derived value cannot.
- **Teardown became structural, not a second branch.** `ComputeProvider.disposeIngress(FirewallId)` is
  new: cleanup resolves a provider and calls it, with no vendor knowledge. GCP/Azure inherit a working
  teardown seam. Previously `BootstrapCleanup` was Hetzner-gated, which would have stranded every AWS
  security group as a billable orphan.

`[verified: unit — client 59, provider 65, cli 663, aether-config 333, hetzner 85, all 0 failures;
jbct:check clean; ./build.sh green]`. **NOT LocalStack-verified, NOT cloud-verified.** Per #463 AWS is
Tier-2 and its operative bar until credentials arrive is the LocalStack contract test — which exists and
already creates a real VPC/subnet/security group. **Running it is the next step**, and it is what would
exercise the VPC-derivation decision against a real EC2 surface.

## §6 Build-system change: generated sources

`pg-parser`'s peglib output moved from `src/main/java` (committed, opt-in profile) to
`target/generated-sources/peglib` (generated every build, not committed). Owner-approved.

Two symptoms had one root: `jbct:check` failed on generator output it cannot change
(`PgSqlVisitor.defaultResult()` returns null → JBCT-RET-03), and `jbct:format` rewrote 600+ lines of
generated code per run, leaving a permanently dirty tree. **The grammar is now the single source of
truth** rather than the grammar plus a snapshot of its output.

Required `build-helper-maven-plugin` (build-scope only): peglib's whole parameter surface is
`grammarFile`, `importDirectory`, `outputDirectory`, `packageName`, three class names, `smokeInput`,
`failOnWarning` — no source-root registration, and Maven only auto-compiles
`target/generated-sources/annotations`.

**`jbct-parser` has the identical pattern** and has never hit it because `jbct/` sets `jbct.skip=true`
for self-dogfooding. Same stale-snapshot risk; not fixed, other stream's module.

## §7 ⚠ CI is strictly weaker than the local gate

PR #618 passed CI and still broke `./build.sh`. **CI runs `mvn install -B -pl '!examples'` and never
invokes `jbct:check` or `build.sh`.** That is how generated-code lint violations and unformatted sources
both reached the branch. Worth a ticket; not filed — it is a workflow change affecting both streams.

Related tooling lesson: **`git diff -w` does not distinguish a deleted comment from a re-wrapped one.**
The formatter was silently deleting comments between an annotation and its member (all four comment
styles). Caught only by comparing non-whitespace content hashes of every touched file against `HEAD`.
Fixed by the clone stream in #618. Keep that check when reviewing bulk formatter output.

## §8 Issues

- **#611 CLOSED** (rejected, evidence in the closing comment).
- **#616** — reframed by owner from "wire a durable substrate" to an **AetherKey audit**: classify every
  key as declared / derivable / earned, confirm each earned key's recovery story, write the durability
  model down. Owner ruling: consensus state does NOT need a durable log — backup covers the non-dynamic
  half, the dynamic half is auto-restored. Five keys fit neither bucket and are listed there as guesses
  to confirm.
- **#615** — filed AND fixed: elected LB on non-Hetzner clouds opened no ingress and emitted no warning.
  AWS has since moved out of the warning set.
- **#617** — the VO sweep, with the observed inventory and the four rules above.
- **#444** — commented with the full scope reconciliation; its last item (cross-provider ingress) is now
  one third done. Closing it needs GCP and Azure.
- **#618 MERGED** after review; my review found the jOOQ fixture encoded three real old-parser bugs
  (a dropped `ON DELETE CASCADE` and two indexes wrongly marked unique). The clone stream fixed the ROOT
  (`CREATE INDEX` uniqueness was detected across the whole script), not just the fixture.

## §9 Next

1. **AWS LocalStack contract test** — the Tier-2 bar, reachable today without credentials.
2. **GCP ingress.** Two things the survey established that will not port: GCP firewall rules do NOT
   support labels (selection must key on the NAME), and one rule has ONE rule-level `sourceRanges` for
   all its `allowed[]` — so **one-firewall-per-(cluster, source) does not survive**; the key becomes
   `(cluster, source, sourceCidr)`. Also see the truncation-collision warning in `FirewallName`'s
   javadoc: name-based selection makes it load-bearing there in a way it is not on Hetzner.
3. **Azure ingress — blocked.** `AzureComputeProvider:167` passes `config.vnetSubnetId()` into a field
   ARM requires to be a NIC id, and no integration test has ever exercised it. If that is right, Azure
   VM creation is broken today and there is no NIC to attach an NSG to. Fix that first.
4. **#596** — top remaining blocker (durable entities unreachable off the partition owner).
5. **#590 / #509** — need cluster runs, not code.

## §10 Process notes

- **Four agent deaths this session** (two stalls, one machine sleep, one immediate). Large single-shot
  delegations failed; the same work split into client-sized briefs succeeded. Check the tree before
  concluding an agent achieved nothing — two left substantial, recoverable work needing only imports or
  one missing method.
- **The watchdog fires at 600s.** Anything short of that is quiet, not dead — I diagnosed one agent as
  dead at 437s and duplicated work it then finished underneath me.
- **Push the branch BEFORE re-pointing the candidate tag.** I did it in the wrong order once; the push
  was rejected (concurrent stream) and the tag briefly referenced a commit that existed only locally.
