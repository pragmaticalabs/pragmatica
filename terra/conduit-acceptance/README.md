# Conduit (RealWorld) on Terra: nightly acceptance app (#2898)

A complete [RealWorld](https://github.com/realworld-apps/realworld) "Conduit" backend written as Terra slices (19 slices, PostgreSQL via
`@PgSql`, Terra's built-in HS256 `Token` JWT security), vendored here so the framework is exercised end to end by a third-party
API suite (vendored byte-identical, see Provenance) every night. It is an acceptance app, not a release artifact: it is not published.

## What runs

`run-acceptance.sh` starts a disposable PostgreSQL 17 container, applies `schema/V1__conduit.sql`, starts the assembled distribution
(`target/conduit-terra-terra`), and runs the upstream API suite in `spec/hurl` (13 files, MIT, see `spec/LICENSE`, pinned to upstream commit `25588846c314b15a5e427ffe816e6b4af7be68c7`) with a pinned
Hurl 8.0.1 container, one invocation per file. It prints one line per file and a summary, and **exits non-zero** on any failed request or
assertion, on a database or application that does not come up, and on a suite that examined nothing.

```
mvn install -pl terra/conduit-acceptance -am      # builds target/conduit-terra-terra
terra/conduit-acceptance/run-acceptance.sh        # needs docker, java 25, curl, awk, python3
```

CI: the `conduit-acceptance` job of `.github/workflows/heavy-forge.yml`. Nightly and `workflow_dispatch` only, never on a pull request.
A red night keeps the `heavy-nightly` issue open and lists `conduit-acceptance` as a new red.

## Coverage

Registration, login, current user and update; profiles, follow and unfollow; articles (create, read, update, delete, list with tag,
author and favorited filters, pagination, feed); comments; favorites; tags; and the error paths (`errors_*`: validation, auth and
authorization status codes). See the summary line of a run for the current request and assertion counts.

## Project-owned requests (`local/hurl`)

`spec/hurl` stays byte-identical to the vendored upstream suite. Coverage the upstream suite lacks lives in `local/hurl`, which the runner
executes after it and counts in the summary (`project-owned N`):

- `pagination_extra.hurl`: which article each page returns for the unfiltered, tag, favorited and combined-filter listings and for `/feed`
  (limit and offset; #3236 finding A).
- `auth_tokens.hurl`: a forged signature, `alg=none`, an expired but validly signed token and a wrong scheme each answer 401, with a freshly
  minted valid token as the control that answers 200 (#3236 finding B). The runner registers a user and mints the variants with
  `local/mint_tokens.py` from that user's token and the signing secret.

`check-fail-closed.sh` checks that the runner refuses (exit 2, with a message) a missing distribution and an `APP_PORT` that is already in use.

## Provenance

`spec/hurl` is a byte-identical copy of `specs/api/hurl` (the 13 `.hurl` files) from `realworld-apps/realworld` at commit
`%s` (includes b8a37f107c "validate email format"). `spec/UPSTREAM` records that commit and the SHA-256 of every file;
`check-vendored.sh` fails on any changed, missing or extra file, and `run-acceptance.sh` runs it first (exit 2). Re-vendor by copying a newer
upstream commit and regenerating `spec/UPSTREAM`.

## Exclusions (what is not the upstream suite verbatim)

Terra answers RFC 9457 problem details (owner ruling 2026-11-08), where the spec expects `{"errors": {...}}`. The vendored files are never
edited; the runner changes a scratch copy before the run:

- **Error bodies are asserted through `detail`.** Each `jsonpath "$.errors.<field>[0]" == "<msg>"` under an `HTTP 4xx`/`5xx` line becomes
  `jsonpath "$.detail" contains "<field>: <msg>"` (32 cases). Status lines, success assertions and captures are untouched.
- **12 cases are exempt by name** (`local/exempt-by-name.txt`): every `token: is missing`. The framework auth gate answers
  `detail: "Authorization Bearer token required"`, which names Bearer where Conduit's scheme is Token. The status code of each is still asserted.
  The runner fails closed if a listed line is not such an assertion or the applied count differs from the list.
- **Hurl, not Newman.** The upstream project removed its Postman collection; the Hurl files are its current source of truth.
- **`terra.toml` carries a placeholder `jwt.secret`.** `terra:assemble` runs `--check` at build time and the default reactor builds this
  module without a secret exported. The runner overrides it through `TERRA_JWT__SECRET`; the placeholder is a test value, not a secret.

## Failures

A failing request on the unmodified app is a framework gap, not something to patch around here: file it against the framework with the
request and assertion from the Hurl output (`report/<file>.out` in the uploaded artifact, plus `app.log`).
