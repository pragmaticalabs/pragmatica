### Fixed (2026-09-05 — #304: node-mode live events never key correctly)

- `events.js` no longer dedups/keys live events on `event.timestamp`. `ClusterEventView` has
  never had a `timestamp` field — its HLC time has always been named `at`
  (`HlcTimestamp`, packed physical-ms/counter), a naming convention shared by every record in
  the `ClusterEvent` sealed interface and predating the `type` discriminator entirely; the
  client-side `timestamp` read was an original wrong assumption, not a later rename. New
  `eventMillis`/`eventKey` helpers read `at` (unpacking `HlcTimestamp.pack`'s physical-ms
  component) when present, falling back to `timestamp` for Forge-mode events that do use that
  field.
  [verified: `ClusterEventKeyContractTest.eventsJs_computesKeyFromAtOrTimestamp_notTimestampAlone`]
- `index.html`'s event-feed template now keys and displays time through the store's
  `eventKey`/`eventMillis` helpers instead of reading `event.timestamp` directly.
  [verified: `ClusterEventKeyContractTest.indexHtml_usesEventKeyHelper_andNeverReadsEventTimestampDirectly`]
- `ClusterEventView`'s wire shape (`at`, never `timestamp`) is pinned both by serialization and
  by reflection on its record components, as a guard against a future accidental rename in
  either direction.
  [verified: `ClusterEventKeyContractTest.clusterEventView_serializesAtField_neverTimestamp`,
  `ClusterEventKeyContractTest.clusterEventView_recordComponents_nameTheTimeFieldAt_notTimestamp`]
- **Out of scope for this fix, re-scope note for #304:** the trace waterfall (`trace-detail`
  component, loaded in `index.html` but never wired to a data source or a click handler) is
  unbuilt. Building it needs: (1) a click/selection handler from the event feed or invocation
  explorer into the component, (2) a server-side trace-fetch endpoint (none of the existing
  `/api/events`-family routes return per-invocation span/trace data), and (3) a waterfall
  rendering layer inside the component — none of which exists today. This is new feature work,
  not a fix, and should be tracked as its own ticket rather than folded back into #304.
  [mechanism: `index.html:512-524` loads the component and never references it again;
  repo-wide grep for a trace-fetch route under `aether/node`'s REST layer finds none]
