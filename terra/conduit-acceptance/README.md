# Conduit (RealWorld) on Terra: nightly acceptance app (#2898)

A complete [RealWorld](https://github.com/realworld-apps/realworld) "Conduit" backend written as Terra slices (19 slices, PostgreSQL via
`@PgSql`, Terra's built-in HS256 `Token` JWT security), vendored here so the framework is exercised end to end by an unmodified third-party
API suite every night. It is an acceptance app, not a release artifact: it is not published.

## What runs

`run-acceptance.sh` starts a disposable PostgreSQL 17 container, applies `schema/V1__conduit.sql`, starts the assembled distribution
(`target/conduit-terra-terra`), and runs the upstream API suite in `spec/hurl` (13 files, MIT, see `spec/LICENSE`) with a pinned
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
authorization status codes). 154 requests and 651 assertions at the first green run.

## Exclusions (what is not the upstream suite verbatim)

- **Error-response bodies are not asserted.** Terra answers RFC 9457 problem details (owner ruling 2026-11-08), where the spec expects
  `{"errors": {...}}`. Before the run the script removes, from a scratch copy, the `jsonpath "$.errors..."` assertions that sit under an
  `HTTP 4xx`/`5xx` response line (41 of them; the count is printed on every run). Every status line, every success-body assertion and every
  capture is kept, so a wrong status code on an error path still fails the run.
- **Hurl, not Newman.** The upstream project removed its Postman collection; the Hurl files are its current source of truth.
- **`terra.toml` carries a placeholder `jwt.secret`.** `terra:assemble` runs `--check` at build time and the default reactor builds this
  module without a secret exported. The runner overrides it through `TERRA_JWT__SECRET`; the placeholder is a test value, not a secret.

## Failures

A failing request on the unmodified app is a framework gap, not something to patch around here: file it against the framework with the
request and assertion from the Hurl output (`report/<file>.out` in the uploaded artifact, plus `app.log`).
