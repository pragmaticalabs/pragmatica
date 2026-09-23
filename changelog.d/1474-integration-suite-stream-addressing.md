### Fixed (2026-09-23 — #1474: the cloud integration suite could not address a stream, and could not say why)

- **Streams were addressed by bare name, which the CLI has refused since #1044.** `aether streams
  read <bare>` returns `invalid stream address: '<name>' is a bare stream name, which is ambiguous:
  it names no namespace`, because a bare name used to default to `system:`, which holds no app
  stream. The harness already had the machinery to avoid this — `stream_coordinate` resolves a name
  against the **live catalog** and `stream_identity` renders the CLI's colon form, with a comment
  saying "callers must use this rather than a bare name" — but exactly one suite used it. The two
  load-bearing reads (`04-streaming` replication, `08-resources` pub/sub) now resolve the identity;
  four best-effort `streams delete` teardowns move to a new `stream_delete_if_present`, which treats
  absent-from-catalog as a legitimate no-op and warns only when a delete that *was* attempted fails.
  Those four had been `|| true`, so since #1044 the pre-clean silently stopped pre-cleaning and the
  cleanup silently stopped cleaning, both while looking like they still worked.

- **Those same four teardowns were ALSO missing `--force`, a second defect the first one hid.**
  `aether streams delete` prompts `Are you sure you want to delete stream '…'? (y/N)`, and the suite
  gives it no tty, so the command blocks on the prompt and exits non-zero **even for a perfectly
  addressed stream**. Fixing only the address would therefore have produced a delete that still
  silently did nothing. Found only because the new helper warns instead of swallowing: measured
  against a live cluster, without `--force` the stream is still in the catalog afterwards; with it,
  it is gone, and an unrelated stream created alongside it survives.

- **Five REST call sites still used the pre-catalog flat paths — a DIFFERENT defect from the bare
  name, with a different blast radius.** The 2026-09-02 migration (`7a523c9e3`) moved every stream
  route to `/streams/{namespace}/{stream}/{version}/…`, but the **verb-first** `/streams/publish/{name}`
  and the two-segment `/streams/{name}` survived in `04-streaming`, `13-edge-cases` and
  `01-stability`. The URL *shape* is wrong independently of the name, so correcting only the name
  would have left a URL that still fails. This class accounts for the symptoms most easily misread
  as product failures: a measured **100.00% stream publish error rate** and `A=400, B=400`.

  Measured on a live 5-node cluster, with a control: a path no route can claim returns
  **404 `File not found`**, whereas `POST /api/v1/streams/publish/{name}` returns
  **400 `Bad Request: Missing stream name`**. The 400-not-404 is the point — the verb-first path is
  **not a dead route, it MISROUTES into `STREAM_CREATE` (`POST /streams`)**, which then rejects the
  body for carrying no `name`. That is a fresh instance of the RouteMatcher bucketing hazard already
  documented on `stream_coordinate`: "the old flat paths did NOT 404 — they MISROUTED."

- **`13-edge-cases` also carried a false premise in a comment**: "Publish to two streams in parallel
  (auto-creates them)". Publish auto-create materialises a ring by engine key and **never registers a
  catalog entry** (the #1224 gap), so the streams were not resolvable afterwards. The comment is
  removed rather than left to mislead the next reader as it misled this test's author.

- **Five suites published to streams nothing had created.** "Streams auto-create on first publish"
  stopped being true at #1224 and was still asserted in three comments. `stream_coordinate` then
  found nothing and returned empty, which is the origin of most `expected NOT '', got ''` in the
  2026-09-23 baseline. `test-events`, `isolation-test`, `load-test-stream`, `notifications` and
  `concurrent-test-{a,b}` are now created at a catalog address with a presence assertion, mirroring
  the pattern `test-stream-replication.sh` already used.

### Fixed — diagnosability (the reason the above took a full-suite cloud run to find)

- **`stream_coordinate` returned 1 in silence on both its failure paths**, and every caller
  (`stream_publish`, `stream_info`, `stream_identity`, `stream_replicas`) inherited that silence.
  Surfacing an HTTP body cannot help here because **no request is ever made** on that path. It now
  distinguishes "catalog unreachable" from "stream absent from an otherwise healthy catalog" — they
  call for different actions — and the absent case lists what the catalog *does* hold.

- **`http_status` discards the response body** (`curl -o /dev/null`), which is why `A=400, B=400`
  carried no detail. `http_status_with_body` already existed beside it as a documented drop-in with
  the same stdout contract; the four stream-publish sites now use it. In the two sustained-publish
  loops only the **first** failure dumps a body — a 1h soak would otherwise bury the diagnosis it
  exists to surface.

- **Four batch-publish loops threw away stderr** with `2>&1`, discarding the `api … status=NNN:
  <body>` diagnostic `_api_call` already emits. That is why `expected '50', got '25'` and
  `expected '20', got '0'` carried no reason for the failures they counted. Note for the record:
  `api_post`/`api_get` were **not** the body-discarding layer — `_api_call` has surfaced status and
  body since before the rc4 cut, and those diagnostics are present in the baseline log.

### Fixed — two checks that could not fail

- `test-streaming-resources.sh` grepped the stream catalog for a **`name`** field. The response
  carries `namespace`/`stream`/`version` and **no `name` field at all** (measured: 0 occurrences vs
  5 of `stream`), so the grep could never match — and nobody noticed because the absent branch
  warned and then passed a *different* claim. Both halves are fixed: the field is `stream`, and
  absence is now a real failure.
- `test-streaming-soak.sh`'s "stream exists" check logged whatever it found and passed
  unconditionally. It now creates the stream and asserts it reached the catalog.

Verified against a live 5-node docker cluster at the rc4 tip, comparing the unmodified tree with the
fixed one on freshly re-formed clusters: **13 failures → 4**, with `test-stream-publish`,
`test-stream-under-load`, `test-pub-sub` and `test-streaming-resources` going fully green and the
sustained-publish error rate going 100.00% → 0.00%. The 4 remaining are byte-identical in both arms
and unrelated to addressing.
