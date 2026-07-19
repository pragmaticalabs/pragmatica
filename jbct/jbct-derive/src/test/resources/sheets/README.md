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

## Files

| File | Run | Grade | Mode |
|------|-----|-------|------|
| `companies-house.toml` | Companies House | A (isolated operators) | greenfield |
| `stack-overflow.toml`  | Stack Overflow 2016 | B | greenfield |
| `shopify.toml`         | Shopify pod era | B | greenfield |
| `discord.toml`         | Discord 2017-2023 | C | greenfield |
| `living-system.toml`   | synthetic | — | living (exercises `[current_vector]` + `[[floors]]`) |

All four published runs must pass the entry gate cleanly (`GoldenSheetsTest`).
