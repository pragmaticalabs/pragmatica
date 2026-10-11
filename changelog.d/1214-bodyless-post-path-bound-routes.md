### Fixed (2026-10-04 — #1214: a POST route bound entirely from the path failed without a request body)
- **`POST /api/v1/events/open/<event>` with no body answered an error (`Type mismatch: expected Request, got
  unknown`) while `-d '{}'` returned 200.** The slice-processor emitted `.withBody(...)` for EVERY
  POST/PUT/PATCH route, even when the request record's components are all bound from the path and query, so a
  request with no body frame had nothing to deserialize into the record. #772 only changed the status of
  that failure from 500 to 400; the route still demanded a body.
- **A route whose request record is bound entirely by path and query parameters is now generated as a
  path/query route and demands no body.** A body the client does send is ignored, so `-d '{}'` keeps working.
  A record with any component not bound by the path or query still takes its body as before; text, binary
  and multipart routes are unchanged.
- Pinned against the generated router by `GeneratedConstructorLiftRuntimeTest` (`postBoundEntirelyFromThePath_*`:
  a request built with zero body bytes serves, the id still binds from the path, a sent `{}` is harmless).
