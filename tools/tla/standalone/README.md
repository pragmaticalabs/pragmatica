# Rabia TLA+ models (standalone)

Three TLA+ models of the Rabia consensus engine (`integrations/consensus/.../rabia/`), written as whole modules rather
than as fragments in Java doc comments (the other models in `tools/tla/` are embedded). Each directory holds
`<Module>.tla` and one `<variant>.cfg` per row of `tools/tla/models.tsv`. `check.sh` and `run.sh` treat a root that
contains `<Module>.tla` as a standalone model: the `.tla` and the `.cfg` files are used as they are, with no
extraction. The source the models were read against is commit `3cee146793` (source only, not a build); the
Java references in the module headers are method names at that commit and their line numbers have since moved.

**Results are bounds, not proofs.** Each row is exhaustive only within the constants of its config (few nodes, one or
two slots, one reconfiguration). A `pass` row says TLC found no violation within those constants. Every config names
what it removes or swaps, and a known-bad config must fail on the property it names.

| Module | What it models | Deviations from the Rabia paper |
|---|---|---|
| `RabiaSlot` | one slot sequence at a fixed roster and epoch: proposals, two vote rounds, decision, learn, slot garbage collection | D1, D8, D9, D11 |
| `RabiaEpoch` | in-log reconfiguration: epoch per slot boundary, exact-epoch ballot counting, decision relay | D2, D6, D12 |
| `RabiaAdopt` | recovery and state adoption after a crash, a pause or a reconfiguration, with and without the amnesia fence | D3, D4, D5 |

## The deviations, as plain statements

- **D1** The coin is deterministic and common (a function of slot, epoch and round) where the paper uses a random common coin. Safety does not depend on it; the paper's termination argument against an oblivious scheduler no longer applies.
- **D2** A decision is accepted from any trusted peer when its epoch equals the voter epoch at the slot; the paper decides from the replica's own round-2 tally.
- **D3** Ballots are not persisted. A restart under the same node id is refused by every peer (boot-token fence), and safety rests on that fence. The paper assumes crash-stop.
- **D4** State adoption after a pause or restart needs n/2 responders when the node vouches for its own durable history or a participation marker proves it never voted, and n/2+1 otherwise. The paper has no state transfer.
- **D5** A newer epoch (a new roster) is adopted from a single live responder when all live responders claim one configuration. The paper's core has no reconfiguration.
- **D6** A reconfiguration is decided in a slot and governs from the next slot. A ballot counts only from a member of the installed roster at the exact epoch; ballots for a newer epoch are deferred.
- **D8** A proposal is the lowest pending batch id, and a voter with nothing pending proposes an empty batch.
- **D9** Decided slots are garbage-collected. A ballot for a past slot is answered by repair and never counted again. The paper retains the log.
- **D11** A node activates only after its participation marker is durably recorded, and the receiver counts only installed roster members, whatever the sender believes about itself.
- **D12** Rabia is leaderless. The cluster leader is a value committed through Rabia; the models treat it as a source of at most two competing reconfiguration requests.

## Reading the rows

- `pass` rows that are named as removals (`noexactepoch`, `norelayepoch`, `nopastguard`, `nooneconfig`, `noselfvouch`, `paperD4`, `paperD5`) are known-bad configs that did **not** go red: the removed rule is redundant at those bounds. They do not show the rule is needed.
- `paper_*` and `paperD*` configs swap one of our rules for the paper's, keeping every other fix. Red means the paper rule is unsafe against the environment the model keeps (sync adoption, garbage collection), not that the paper is wrong in its own setting.
- `ctl_*` configs are instrument checks. A `fail` control (its `Reach` invariant violated) shows an adoption path is reachable; a `pass` control shows it is never reached under that rule.
- `rjT_*` rows (token-memory rejoin) pass vacuously for rejoin: `ctl_rjTAny` shows no rejoined incarnation ever completes an adoption there, so only the fence-dropped row (`rjT_noamnesiafence`) is informative.
- `rjA_*` and `rjW_*` admit an old or wiped incarnation by construction (not the code) and fail on Agreement, which is the cost of admitting one.

## Not embedded

A config whose result is only an incomplete run (timeout, out of memory), a random-simulation hit, or an unreconciled
pair of runs has no row. They are `RabiaSlot`: `code_roster`, `live`, `coinsplit`, `norogue`, `norosterballot`,
`equivocate`, `paper_d11`; `RabiaEpoch`: the `_b1` variants of `noprefixsafe` and `norelayepoch_noprefix`;
`RabiaAdopt`: `ctl_rjClaimed2`, `ctl_rjTAny3`, `ctl_rjAmnesiac1_selfvouch`.

## Running

```
tools/tla/run.sh small 'RabiaSlot|RabiaEpoch|RabiaAdopt'
tools/tla/check.sh RabiaSlot code tools/tla/standalone/RabiaSlot
```

The larger configs take several minutes and gigabytes of heap (`RabiaEpoch` `noprefixsafe`, `norelayepoch_noprefix` and the `RabiaAdopt` `rjT_*` and `ctl_rjT*` rows are the longest; set `TLC_JAVA_OPTS=-Xmx12g`). Run them on a build host, not on a laptop.
