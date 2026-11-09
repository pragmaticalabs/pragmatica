### Fixed (2026-11-08 — #2921: four more refusals were answered before their stored upload was deleted)
- A stored upload refused at dispatch (security), whose route no longer resolves, refused before the slice ran (rate limit, invocation admission) or
  routed to a remote node with no forwarder was deleted fire-and-forget; the 401/404/429/503 was sent without waiting, so a client holding the answer could
  still see the object. Each is now answered once the delete has finished, and a delete that fails still produces the answer.
