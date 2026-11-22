### Fixed (2026-11-21 — #3531, #3532: the management API and configuration references match the code)
- **The management route table is generated from the registered routes** (`tools/gen-management-routes.py`), with
  method, path and minimum role for every route; a CI check fails when the page and the routes disagree. The three
  stream routes documented at the wrong paths now carry their real paths, and the 35 missing routes are listed.
- **The configuration reference documents the keys the code reads but the page omitted**: `[alerts.webhook]`,
  `management_protocol`, the worker `core_nodes` and `swim` keys, and the blueprint `[deployment]` table with its
  canary stages. Keys that are parsed but have no effect today are marked as such.
- Corrected notes: an alert webhook retries a non-2xx response but not a transport error; `h3`/`both` serve over QUIC
  with a self-signed certificate when `[cluster] tls` is not set.
