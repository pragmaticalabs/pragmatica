# Golden answer sheets (test resources)

These four TOML sheets are the schema-form transcriptions of the four published derivation runs,
used as the entry-gate golden tests for issue #443 (Phase A).

## Provenance

The runs are published, in **prose only**, in `siy/derivation-artifacts` under `runs/<system>/`
(`ANSWER-SHEET.md`). That repo's `schema/README.md` states the schema-form sheets "arrive together
with the derivation engine itself (pragmaticalabs/pragmatica#443)" — i.e. these TOML files are that
deliverable, produced here. They are **transcriptions**, not copies: prose demands were mapped onto
the nine typed, scoped questions of schema v0.1. Genuinely-unknown demands are marked `UNKNOWN`,
never guessed. Each file's header comment records its source run and evidence grade.

The underlying artifacts are licensed CC BY 4.0; quoted material from engineering blogs and public
records remains with its original authors (see each run's `SOURCES.md` in the artifacts repo).

## This directory is canonical

The four published runs' sheets are **mirrored** into `siy/derivation-artifacts` under `schema/`,
which is where that repo's own `schema/README.md` promised they would land. **This copy is the
canonical one** — these files are the engine's golden tests, so a correction made here is exercised
by `GoldenSheetsTest`, while a correction made in the artifacts repo is not.

Each mirrored file carries a header naming the source path and the commit it was taken from, and
says plainly that edits there are lost at the next sync. When a sheet changes here, re-sync the
mirror and update the recorded commit; the mirror is a copy with provenance, not a second source of
truth.

`living-system.toml` is **not** mirrored — it is synthetic, with no corresponding prose run under
the artifacts repo's `runs/`, so publishing it beside four real transcriptions would read as a fifth
case study.

## Files

| File | Run | Grade | Mode |
|------|-----|-------|------|
| `companies-house.toml` | Companies House | A (isolated operators) | greenfield |
| `stack-overflow.toml`  | Stack Overflow 2016 | B | greenfield |
| `shopify.toml`         | Shopify pod era | B | greenfield |
| `discord.toml`         | Discord 2017-2023 | C | greenfield |
| `living-system.toml`   | synthetic | — | living (exercises `[current_vector]` + `[[floors]]`) |

All four published runs must pass the entry gate cleanly (`GoldenSheetsTest`).
