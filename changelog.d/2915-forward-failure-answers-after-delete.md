### Fixed (2026-11-08 — #2915: a forward that never left the node was answered before its stored upload was deleted)
- A streaming upload whose forward never left the ingress node (`NotSent`, `TooLargeToForward`) is deleted fire-and-forget; the 503/413 was
  sent without waiting, so a client holding the answer could still see the object. The answer is now sent once the delete has finished.
